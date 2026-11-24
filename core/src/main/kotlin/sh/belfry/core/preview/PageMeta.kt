package sh.belfry.core.preview

import java.nio.charset.Charset

/**
 * What a page says about itself (SPEC 19.87; docs/link-previews-v2.md "How the sender gets it"):
 * `og:*` first, then `twitter:*`, then `<title>` and `<meta name="description">`. Values are
 * plain text: tags stripped, entities decoded, whitespace collapsed, cut ([LinkPreviews.TITLE_MAX],
 * [LinkPreviews.DESCRIPTION_MAX], [LinkPreviews.SITE_MAX]); [image] is absolute https (resolved
 * against the page's final address) or null. Pure: no network.
 */
data class PageMeta(val title: String, val description: String, val site: String, val image: String?) {
    /** Something to show: a title or a description. */
    val usable: Boolean get() = title.isNotEmpty() || description.isNotEmpty()

    companion object {
        private val COMMENT = Regex("(?s)<!--.*?-->")
        private val SCRIPT = Regex("(?is)<(script|style|noscript|template)\\b[^>]*>.*?</\\1\\s*>")
        private val TITLE = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")
        private val META = Regex("(?is)<meta\\b([^>]*)>")
        private val ATTR = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*(?:=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?")
        private val TAG = Regex("<[^>]*>")
        private val CHARSET = Regex("(?i)charset\\s*=\\s*[\"']?([-a-zA-Z0-9_:.]+)")

        /** The page's text: the charset of [contentType], else a `<meta charset>` in its first 1024 bytes, else UTF-8. */
        fun decode(bytes: ByteArray, contentType: String?): String {
            val named = contentType?.let { CHARSET.find(it)?.groupValues?.get(1) }
                ?: CHARSET.find(String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1))?.groupValues?.get(1)
            val cs = try { named?.let { Charset.forName(it) } } catch (e: Exception) { null } ?: Charsets.UTF_8
            return String(bytes, cs).removePrefix("\uFEFF")
        }

        private fun attrs(s: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (m in ATTR.findAll(s)) {
                val k = m.groupValues[1].lowercase()
                if (k in out) continue
                out[k] = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            }
            return out
        }

        /** Plain text of a value: tags stripped, entities decoded, whitespace collapsed, at most MAX. */
        fun text(v: String?, max: Int): String = LinkPreviews.words(Entities.decode(TAG.replace(v ?: "", " ")), max)

        /** HTML (as text) of a page whose final address is BASE. */
        fun parse(html: String, base: String): PageMeta {
            val clean = SCRIPT.replace(COMMENT.replace(html, " "), " ")
            val meta = HashMap<String, String>()
            for (m in META.findAll(clean)) {
                val a = attrs(m.groupValues[1])
                val content = a["content"] ?: continue
                for (k in listOfNotNull(a["property"], a["name"])) {
                    val key = k.trim().lowercase()
                    if (key.isNotEmpty() && key !in meta) meta[key] = content
                }
            }
            fun first(max: Int, vararg keys: String): String {
                for (k in keys) text(meta[k], max).let { if (it.isNotEmpty()) return it }
                return ""
            }
            val titleTag = TITLE.find(clean)?.groupValues?.get(1)
            val title = first(LinkPreviews.TITLE_MAX, "og:title", "twitter:title").ifEmpty { text(titleTag, LinkPreviews.TITLE_MAX) }
            val description = first(LinkPreviews.DESCRIPTION_MAX, "og:description", "twitter:description", "description")
            val site = first(LinkPreviews.SITE_MAX, "og:site_name", "application-name")
            var image: String? = null
            for (k in listOf("og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src")) {
                val v = meta[k]?.let { Entities.decode(it).trim() }?.takeIf { it.isNotEmpty() } ?: continue
                image = LinkPreviews.absolute(v, base)
                break
            }
            return PageMeta(title, description, site, image)
        }
    }
}

/** HTML character references: numeric ones and the named ones pages put in titles. Unknown names stay as written. */
object Entities {
    private val REF = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "laquo" to "«", "raquo" to "»", "middot" to "·", "bull" to "•", "copy" to "©", "reg" to "®", "trade" to "™",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "agrave" to "à", "aacute" to "á", "acirc" to "â", "ccedil" to "ç",
        "iacute" to "í", "oacute" to "ó", "ocirc" to "ô", "uacute" to "ú", "ntilde" to "ñ",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "Eacute" to "É", "euro" to "€", "pound" to "£", "deg" to "°", "times" to "×",
    )

    fun decode(s: String): String {
        if (!s.contains('&')) return s
        return REF.replace(s) { m ->
            val v = m.groupValues[1]
            if (v.startsWith("#")) {
                val cp = (if (v.length > 1 && (v[1] == 'x' || v[1] == 'X')) v.substring(2).toIntOrNull(16) else v.substring(1).toIntOrNull()) ?: 0xFFFD
                val ok = cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF
                String(Character.toChars(if (ok) cp else 0xFFFD))
            } else NAMED[v] ?: m.value
        }
    }
}
