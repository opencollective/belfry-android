package sh.belfry.core.preview

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.model.Links
import sh.belfry.core.model.References
import sh.belfry.core.nostr.Tag

/**
 * Link previews built by the sender (SPEC 19.87; collective-belfry docs/link-previews-v2.md;
 * vectors/link-previews.json). The SENDER's device reads the page once while composing and puts
 * what it found on the message; readers build the box from those tags alone and never fetch the
 * page. Pure rules here: which link, the tags, reading them back, the picture's checks.
 */

/**
 * The picture of a preview as the message carries it (its `imeta` entry with `purpose preview`).
 * [x] is the sha256 of what Blossom stores; [key] / [nonce] / [ox] only in an encrypted
 * conversation (AES-256-GCM like a file there, vectors/dm-files.json), [mime] then the plain type.
 */
data class PreviewPicture(
    val url: String,
    val mime: String,
    val width: Int?,
    val height: Int?,
    val x: String,
    val ox: String? = null,
    val key: String? = null,
    val nonce: String? = null,
) {
    val encrypted: Boolean get() = key != null

    /** The `imeta` entry, in the contract's order (url, m, dim, x, then the encryption's, then purpose). */
    fun imeta(): Tag = buildList {
        add("imeta")
        add("url $url")
        add("m $mime")
        if (width != null && height != null) add("dim ${width}x$height")
        add("x $x")
        if (key != null && nonce != null && ox != null) {
            add("ox $ox")
            add("encryption-algorithm ${DmFiles.ALGORITHM}")
            add("decryption-key $key")
            add("decryption-nonce $nonce")
        }
        add("purpose ${LinkPreviews.PURPOSE}")
    }

    /**
     * The picture from what was downloaded, or null (shown without it): encrypted → x on the
     * ciphertext, GCM's tag, ox on the plain bytes ([DmFiles.open]); plain → x on the bytes. Either
     * way the result must be a PNG, JPEG, WebP or GIF by its first bytes.
     */
    fun open(downloaded: ByteArray): ByteArray? {
        val plain = if (encrypted) {
            DmFiles.open(
                listOf(
                    listOf("encryption-algorithm", DmFiles.ALGORITHM), listOf("decryption-key", key!!),
                    listOf("decryption-nonce", nonce ?: return null), listOf("x", x), listOf("ox", ox ?: return null),
                ),
                downloaded,
            ) ?: return null
        } else {
            if (sha256Hex(downloaded) != x) return null
            downloaded
        }
        return plain.takeIf { PreviewImages.kind(it) != null }
    }
}

/**
 * One preview: [url] is the link as written in the text (what a tap opens); title, description
 * and site are plain text, already cleaned and cut. [picture]: null = none (or not shown).
 */
data class LinkPreview(
    val url: String,
    val title: String,
    val description: String,
    val site: String,
    val picture: PreviewPicture? = null,
) {
    /** The link's host without `www.`. */
    val host: String get() = LinkPreviews.hostOf(url)?.removePrefix("www.") ?: ""

    /** "ToolShare Journal · toolshare.example"; the host alone when the page names no site (or names itself by its host). */
    val siteLine: String get() = listOf(site, host).filter { it.isNotEmpty() }.distinct().joinToString(" · ")

    /** The picture's box, width over height: its own shape between 3:2 and 12:5, 2:1 without a size. */
    val ratio: Float get() = LinkPreviews.ratio(picture?.width, picture?.height)

    /** What the message carries: the `preview` tag, then the picture's `imeta` entry when there is one. */
    fun tags(): List<Tag> = listOf(listOf("preview", url, title, description, site)) + listOfNotNull(picture?.imeta())
}

/** How a preview's picture is stored: in the clear (a channel, an open group) or encrypted (a direct message, a private group). */
enum class PreviewMode { PLAIN, ENCRYPTED }

object LinkPreviews {
    /** `["preview","none"]`: the composer's ✕, no preview for this message. */
    val NO_PREVIEW: Tag = listOf("preview", "none")
    const val PURPOSE = "preview"

    const val TITLE_MAX = 200
    const val DESCRIPTION_MAX = 300
    const val SITE_MAX = 100

    /** The page: the first 512 kB, `text/html` only. The picture: at most 5 MB downloaded. */
    const val PAGE_MAX = 512 * 1024
    const val PICTURE_IN_MAX = 5 * 1024 * 1024
    /** What is uploaded: JPEG or WebP, the longest side at most 1200 px, at most 300 kB. */
    const val PICTURE_OUT_MAX = 300 * 1024
    const val PICTURE_SIDE = 1200
    /** A reader downloads at most this (a picture made by another client may be a little larger). */
    const val PICTURE_READ_MAX = 1024 * 1024
    const val REDIRECTS = 3
    const val TIMEOUT_MS = 5_000L

    fun noPreview(tags: List<Tag>): Boolean = tags.any { it.size >= 2 && it[0] == "preview" && it[1] == "none" }

    fun isPreviewImeta(t: Tag): Boolean = t.isNotEmpty() && t[0] == "imeta" && t.any { it == "purpose $PURPOSE" }

    /** The tags of TAGS that make the preview: `preview` ones and the preview's `imeta` entry. What an edit keeps. */
    fun wireTags(tags: List<Tag>): List<Tag> = tags.filter { (it.size >= 2 && it[0] == "preview") || isPreviewImeta(it) }

    // the web's MEDIA (src/core/linkpreview.ts): pictures and files show as themselves
    private val MEDIA = Regex("\\.(jpe?g|png|gif|webp|avif|heic|svg|mp4|mov|webm|m4a|mp3|ogg|wav|pdf)(\\?|#|$)", RegexOption.IGNORE_CASE)

    fun hostOf(url: String): String? = try {
        val u = java.net.URI(url)
        if (!"https".equals(u.scheme, ignoreCase = true) || u.rawUserInfo != null) null else u.host?.lowercase()?.trimEnd('.')?.ifEmpty { null }
    } catch (e: Exception) { null }

    /** [host] is the belfry's own: its domain or any name under it (chat., belfry., blossom., the website). */
    fun isOwn(host: String, domain: String): Boolean {
        val d = domain.lowercase()
        return d.isNotEmpty() && (host == d || host.endsWith(".$d"))
    }

    /**
     * The first link to an outside page in TEXT, or null: `https://` only, not something of this
     * belfry (its chat, its API, its files, its website: those have their own cards), not a file
     * (a message's attachments in its `imeta` tags, anything under [files], an address that ends
     * like a picture, a recording or a PDF), not one of [skip]. Null too for `["preview","none"]`.
     * The address is returned as written.
     */
    fun outsideLink(text: String, tags: List<Tag>, domain: String, files: String = "", skip: Collection<String> = emptyList()): String? {
        if (text.isEmpty() || noPreview(tags)) return null
        val attached = tags.filter { it.isNotEmpty() && it[0] == "imeta" }.flatMap { t -> t.filter { it.startsWith("url ") }.map { it.substring(4) } }.toSet()
        val fileHost = if (files.isEmpty()) null else hostOf(files)
        for (f in Links.find(text)) {
            val u = f.url
            if (!u.startsWith("https://")) continue
            if (u in attached || u in skip || MEDIA.containsMatchIn(u)) continue
            if (domain.isNotEmpty() && References.target(u, domain) != null) continue
            val host = hostOf(u) ?: continue
            if (isOwn(host, domain) || host == fileHost) continue
            return u
        }
        return null
    }

    /** `https://host` of an https address on the default port with no credentials, else null. */
    fun originOf(url: String): String? = try {
        val u = java.net.URI(url)
        val host = u.host?.lowercase()
        if (!"https".equals(u.scheme, ignoreCase = true) || host.isNullOrEmpty() || u.rawUserInfo != null || (u.port != -1 && u.port != 443)) null
        else "https://$host"
    } catch (e: Exception) { null }

    /** Whitespace collapsed, trimmed, at most MAX characters (Unicode code points, never half of one). */
    fun words(v: String?, max: Int): String = cut((v ?: "").replace(WS, " ").trim(), max)

    private val WS = Regex("[\\s\\u00a0\\u2028\\u2029]+")

    fun cut(s: String, max: Int): String =
        if (s.codePointCount(0, s.length) <= max) s else s.substring(0, s.offsetByCodePoints(0, max)).trimEnd()

    /** The picture's box: its own shape between 3:2 and 12:5, 2:1 when no size was given. */
    fun ratio(width: Int?, height: Int?): Float =
        if (width != null && height != null && width > 0 && height > 0) (width.toFloat() / height).coerceIn(1.5f, 2.4f) else 2f

    private fun hex(s: String?, bytes: Int): String? = s?.takeIf { it.length == bytes * 2 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    private val SHOWN_TYPES = setOf("image/jpeg", "image/webp", "image/png", "image/gif")

    /**
     * An `imeta` entry with `purpose preview` as a picture to show, or null: its URL on the belfry's
     * Blossom ([blossom]'s origin exactly; [local]: the demo's own address prefix), an image type,
     * `x` (64 hex); with a key all of `ox`, `encryption-algorithm aes-gcm`, `decryption-key`,
     * `decryption-nonce` (any of them alone: no picture).
     */
    fun pictureOf(t: Tag, blossom: String, local: String? = null): PreviewPicture? {
        if (!isPreviewImeta(t)) return null
        val f = HashMap<String, String>()
        for (p in t.drop(1)) {
            val i = p.indexOf(' ')
            if (i > 0 && p.substring(0, i) !in f) f[p.substring(0, i)] = p.substring(i + 1)
        }
        val url = f["url"]?.takeIf { it.none { c -> c.isWhitespace() } } ?: return null
        val fromLocal = local != null && url.startsWith(local)
        if (!fromLocal) {
            val o = originOf(blossom) ?: return null
            if (originOf(url) != o) return null
        }
        val mime = f["m"]?.lowercase()?.takeIf { it in SHOWN_TYPES } ?: return null
        val x = hex(f["x"], 32) ?: return null
        val dim = f["dim"]?.split("x")
        val w = dim?.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..20_000 }
        val h = dim?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..20_000 }
        val sized = w != null && h != null && dim.size == 2
        val enc = listOf("ox", "encryption-algorithm", "decryption-key", "decryption-nonce").any { it in f }
        if (!enc) return PreviewPicture(url, mime, w.takeIf { sized }, h.takeIf { sized }, x)
        if (f["encryption-algorithm"] != DmFiles.ALGORITHM) return null
        val ox = hex(f["ox"], 32) ?: return null
        val key = hex(f["decryption-key"], 32) ?: return null
        val nonce = hex(f["decryption-nonce"], 12) ?: return null
        return PreviewPicture(url, mime, w.takeIf { sized }, h.takeIf { sized }, x, ox, key, nonce)
    }

    /**
     * What a reader shows for a message (TEXT as it reads now, TAGS of its latest version): null =
     * a plain link. `["preview","none"]` → null; the first `preview` tag with an https link that the
     * text holds, and a title or a description; its picture only through [pictureOf]. Nothing is
     * fetched here or because of this, but the picture (by the caller, from the belfry's Blossom).
     */
    fun read(text: String, tags: List<Tag>, blossom: String, local: String? = null): LinkPreview? {
        if (noPreview(tags)) return null
        val t = tags.firstOrNull { it.size >= 2 && it[0] == "preview" } ?: return null
        val url = t[1]
        if (!url.startsWith("https://") || url.any { it.isWhitespace() } || hostOf(url) == null) return null
        if (!text.contains(url)) return null
        val title = words(t.getOrNull(2), TITLE_MAX)
        val description = words(t.getOrNull(3), DESCRIPTION_MAX)
        if (title.isEmpty() && description.isEmpty()) return null
        val site = words(t.getOrNull(4), SITE_MAX)
        val picture = tags.firstOrNull { isPreviewImeta(it) }?.let { pictureOf(it, blossom, local) }
        return LinkPreview(url, title, description, site, picture)
    }

    /** The https address A resolved against BASE (relative, protocol-relative, absolute), or null when it isn't https. */
    fun absolute(a: String?, base: String): String? {
        val s = a?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val b = base.toHttpUrlOrNull() ?: return null
        val r = b.resolve(s) ?: return null
        return r.toString().takeIf { r.isHttps }
    }
}

/** Pictures by their first bytes (the contract: png / jpeg / webp / gif, nothing else). */
object PreviewImages {
    fun kind(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() &&
            b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 6 && String(b, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        else -> null
    }
}
