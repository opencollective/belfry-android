package sh.belfry.core.mail

import sh.belfry.core.markdown.Js
import sh.belfry.core.markdown.Markdown
import java.nio.charset.Charset
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

// Just enough MIME (RFC 2045-2047) for Nostr Mail (kind 1301): read the text, subject, sender and
// attachments; write a plain-text + HTML answer. From src/core/mime.ts; vectors/mime.json.

class MailPart(val name: String, val type: String, val data: ByteArray)

class ParsedMail(
    /** Header names in lower case; the first occurrence wins; folded lines joined. */
    val headers: Map<String, String>,
    val subject: String,
    /** The From header, decoded: `"Ana" <ana@x.org>` (see [Mime.addressOf]). */
    val from: String,
    /** The new text: quoted history removed. */
    val text: String,
    /** The whole text, history included. */
    val full: String,
    val attachments: List<MailPart>,
)

data class MailAddress(val name: String, val address: String)

class BuiltMail(val raw: String, val messageId: String)

object Mime {
    private const val W = Js.WS

    private fun decode(bytes: ByteArray, charset: String = "utf-8"): String {
        val cs = charset.trim().lowercase()
        val c: Charset = try {
            when (cs) {
                "", "utf-8", "utf8", "unicode-1-1-utf-8" -> Charsets.UTF_8
                // as browsers' TextDecoder: latin1 and ascii labels mean windows-1252
                "iso-8859-1", "iso8859-1", "latin1", "l1", "ascii", "us-ascii", "cp1252", "x-cp1252" -> Charset.forName("windows-1252")
                else -> Charset.forName(cs)
            }
        } catch (e: Exception) {
            Charsets.UTF_8
        }
        return String(bytes, c)
    }

    private val NOT_B64 = Pattern.compile("[^A-Za-z0-9+/]")

    private fun b64(s: String): ByteArray {
        var clean = NOT_B64.matcher(s).replaceAll("")
        if (clean.length % 4 == 1) clean = clean.dropLast(1)
        return try {
            Base64.getDecoder().decode(clean)
        } catch (e: IllegalArgumentException) {
            ByteArray(0)
        }
    }

    private val SOFT_BREAK = Pattern.compile("=\\r?\\n")

    private fun qp(input: String, header: Boolean = false): ByteArray {
        var s = input
        if (header) s = s.replace('_', ' ')
        s = SOFT_BREAK.matcher(s).replaceAll("")
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 2 < s.length) {
                val h = Character.digit(s[i + 1], 16)
                val l = Character.digit(s[i + 2], 16)
                if (h >= 0 && l >= 0 && s[i + 1].code < 128 && s[i + 2].code < 128) {
                    out.write((h shl 4) or l)
                    i += 3
                    continue
                }
            }
            // one code point (a surrogate pair stays together)
            val n = if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) 2 else 1
            out.write(s.substring(i, i + n).toByteArray(Charsets.UTF_8))
            i += n
        }
        return out.toByteArray()
    }

    private val WORD_GAP = Pattern.compile("\\?=[$W]+=\\?")
    private val WORD = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")
    private val LANG = Pattern.compile("\\*.*\\z", Pattern.DOTALL)

    /** RFC 2047 encoded words: `=?utf-8?B?…?=` and `=?iso-8859-1?Q?…?=`. */
    fun decodeWords(v: String): String {
        val joined = WORD_GAP.matcher(v).replaceAll("?==?")
        return Js.replace(WORD, joined) { m ->
            val cs = LANG.matcher(m.group(1)).replaceFirst("")
            val bytes = if (m.group(2).uppercase() == "B") b64(m.group(3)) else qp(m.group(3), header = true)
            decode(bytes, cs)
        }
    }

    private val BLANK_LINE = Pattern.compile("\\r?\\n\\r?\\n")

    private fun split(raw: String): Pair<String, String> {
        val m = BLANK_LINE.matcher(raw)
        return if (m.find()) raw.substring(0, m.start()) to raw.substring(m.end()) else raw to ""
    }

    private val FOLD = Pattern.compile("\\r?\\n[ \\t]+")
    private val EOL = Pattern.compile("\\r?\\n")

    private fun headersOf(block: String): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        for (line in EOL.split(FOLD.matcher(block).replaceAll(" "), -1)) {
            val i = line.indexOf(':')
            if (i > 0) {
                val k = Js.trim(line.substring(0, i)).lowercase()
                if (!h.containsKey(k)) h[k] = Js.trim(line.substring(i + 1))
            }
        }
        return h
    }

    /** decodeURIComponent; the text as is when it is malformed. */
    private fun percentDecode(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return s
                val h = Character.digit(s[i + 1], 16)
                val l = Character.digit(s[i + 2], 16)
                if (h < 0 || l < 0) return s
                out.write((h shl 4) or l)
                i += 3
            } else {
                val n = if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) 2 else 1
                out.write(s.substring(i, i + n).toByteArray(Charsets.UTF_8))
                i += n
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            s
        }
    }

    private fun param(header: String?, name: String): String {
        if (header == null) return ""
        val star = Pattern.compile(";[$W]*$name\\*=([^';]*)'[^']*'([^;]*)", Pattern.CASE_INSENSITIVE).matcher(header)
        if (star.find()) return percentDecode(star.group(2))
        val m = Pattern.compile(";[$W]*$name=(\"([^\"]*)\"|[^;]*)", Pattern.CASE_INSENSITIVE).matcher(header)
        return if (m.find()) decodeWords(Js.trim(m.group(2) ?: m.group(1))) else ""
    }

    private fun body(h: Map<String, String>, b: String): ByteArray =
        when ((h["content-transfer-encoding"] ?: "").lowercase()) {
            "base64" -> b64(b)
            "quoted-printable" -> qp(b)
            else -> b.toByteArray(Charsets.UTF_8)
        }

    private class Walk {
        var text = ""
        var html = ""
        val attachments = ArrayList<MailPart>()
    }

    private fun walk(raw: String, out: Walk, depth: Int = 0) {
        val (hb, b) = split(raw)
        val h = headersOf(hb)
        val ct = h["content-type"] ?: "text/plain"
        val type = Js.trim(ct.split(";")[0]).lowercase()
        if (type.startsWith("multipart/") && depth < 8) {
            val boundary = param(ct, "boundary")
            if (boundary.isEmpty()) return
            val sep = Pattern.compile("\\r?\\n?--" + Pattern.quote(boundary) + "(?:--)?[ \\t]*\\r?\\n?")
            val parts = ArrayList<String>()
            val m = sep.matcher(b)
            var last = 0
            while (m.find()) {
                parts.add(b.substring(last, m.start()))
                last = m.end()
            }
            parts.add(b.substring(last))
            for (p in parts.drop(1)) if (!Js.isBlank(p)) walk(p, out, depth + 1)
            return
        }
        val disposition = h["content-disposition"]
        val name = param(disposition, "filename").ifEmpty { param(ct, "name") }
        val attached = (disposition ?: "").lowercase().startsWith("attachment") || (name.isNotEmpty() && !type.startsWith("text/"))
        if (attached || !type.startsWith("text/")) {
            if (type != "message/delivery-status") out.attachments.add(MailPart(name.ifEmpty { "attachment" }, type, body(h, b)))
            return
        }
        val text = decode(body(h, b), param(ct, "charset").ifEmpty { "utf-8" })
        if (type == "text/html") {
            if (out.html.isEmpty()) out.html = text
        } else if (out.text.isEmpty()) {
            out.text = text
        }
    }

    private val H_DROP = Pattern.compile("<(script|style|blockquote)[^>]*>[\\s\\S]*?</\\1>", Pattern.CASE_INSENSITIVE)
    private val H_HEAD = Pattern.compile("<head[^>]*>[\\s\\S]*?</head>", Pattern.CASE_INSENSITIVE)
    private val H_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->")
    private val H_BR = Pattern.compile("<br[$W]*/?>", Pattern.CASE_INSENSITIVE)
    private val H_BLOCK_END = Pattern.compile("</(p|div|li|tr|h\\d)>", Pattern.CASE_INSENSITIVE)
    private val H_TAG = Pattern.compile("<[^>]+>")
    private val H_ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|nbsp|lt|gt|quot|apos|amp);")

    /** HTML as plain text (no DOM here: tags stripped, blocks become lines, quoted blocks dropped). */
    fun htmlText(html: String): String {
        var s = H_DROP.matcher(html).replaceAll("")
        s = H_HEAD.matcher(s).replaceAll("")
        s = H_COMMENT.matcher(s).replaceAll("")
        s = H_BR.matcher(s).replaceAll("\n")
        s = Js.replace(H_BLOCK_END, s) { it.group() + "\n" }
        s = H_TAG.matcher(s).replaceAll("")
        return Js.replace(H_ENTITY, s) { m ->
            when (val e = m.group(1)) {
                "nbsp" -> " "
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "amp" -> "&"
                else -> {
                    val code = if (e[1] == 'x' || e[1] == 'X') e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code) && code != 0) String(Character.toChars(code)) else m.group()
                }
            }
        }
    }

    private val QUOTE_HEADER =
        Pattern.compile("^[$W]*(On .{4,200} wrote:|Le .{4,200} a écrit[$W]?:|-----[$W]*Original Message[$W]*-----)[$W]*\\z")
    private val MANY_NEWLINES = Pattern.compile("\\n{3,}")

    /** The new text of a reply: no quoted lines, nothing after "On … wrote:". */
    fun withoutQuote(text: String): String {
        val out = ArrayList<String>()
        for (line in text.replace("\r\n", "\n").split("\n")) {
            if (QUOTE_HEADER.matcher(line).find()) break
            if (line.startsWith(">")) continue
            out.add(Js.trimEnd(line))
        }
        return Js.trim(MANY_NEWLINES.matcher(out.joinToString("\n")).replaceAll("\n\n"))
    }

    fun parseMail(raw: String): ParsedMail {
        val headers = headersOf(split(raw).first)
        val w = Walk()
        walk(raw, w)
        val body = w.text.ifEmpty { htmlText(w.html) }
        return ParsedMail(
            headers = headers,
            subject = decodeWords(headers["subject"] ?: ""),
            from = decodeWords(headers["from"] ?: ""),
            text = withoutQuote(body),
            full = Js.trim(body.replace("\r\n", "\n")),
            attachments = w.attachments,
        )
    }

    private val ADDRESS = Pattern.compile("^[$W]*\"?([^\"<]*?)\"?[$W]*<([^>]+)>[$W]*\\z")

    /** `Ana <ana@x.org>` → ("Ana", "ana@x.org"); a bare address has an empty name. */
    fun addressOf(v: String): MailAddress {
        val m = ADDRESS.matcher(v)
        if (m.find()) return MailAddress(Js.trim(m.group(1)), Js.trim(m.group(2)).lowercase())
        return MailAddress("", Js.trim(v).lowercase())
    }

    private fun isAscii(s: String): Boolean = s.all { it.code in 0x20..0x7e }
    private fun word(s: String): String =
        if (isAscii(s)) s else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="

    private fun wrap76(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 76 <= s.length) {
            sb.append(s, i, i + 76).append("\r\n")
            i += 76
        }
        sb.append(s, i, s.length)
        return sb.toString()
    }

    private val NAME_UNSAFE = Pattern.compile("[\"\\\\\\r\\n]")
    private val NEWLINES = Pattern.compile("[\\r\\n]+")
    private val DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss '+0000'", Locale.US).withZone(ZoneOffset.UTC)

    /**
     * An e-mail (UTF-8) ready for the bridge: `multipart/alternative` with the text as written
     * (plain) and the Markdown rendered (HTML), both base64. [now] is Unix seconds.
     */
    fun buildMail(
        fromName: String,
        from: String,
        to: String,
        subject: String,
        text: String,
        domain: String,
        inReplyTo: String? = null,
        references: String? = null,
        now: Long = System.currentTimeMillis() / 1000,
    ): BuiltMail {
        val messageId = "<${UUID.randomUUID()}@$domain>"
        val name = NAME_UNSAFE.matcher(fromName).replaceAll("")
        val h = ArrayList<String>()
        val display = if (name.isEmpty()) "" else (if (isAscii(name)) "\"$name\"" else word(name)) + " "
        h.add("From: $display<$from>")
        h.add("To: <$to>")
        h.add("Subject: " + word(NEWLINES.matcher(subject).replaceAll(" ")))
        h.add("Date: " + DATE.format(Instant.ofEpochSecond(now)))
        h.add("Message-ID: $messageId")
        if (!inReplyTo.isNullOrEmpty()) {
            h.add("In-Reply-To: $inReplyTo")
            h.add("References: " + listOfNotNull(references, inReplyTo).filter { it.isNotEmpty() }.joinToString(" "))
        }
        h.add("MIME-Version: 1.0")
        val boundary = "b" + UUID.randomUUID().toString().replace("-", "")
        fun part(type: String, body: String): String =
            "--$boundary\r\nContent-Type: $type; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
                wrap76(Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))) + "\r\n"
        h.add("Content-Type: multipart/alternative; boundary=\"$boundary\"")
        val body = part("text/plain", text) + part("text/html", "<!doctype html><html><body>${Markdown.html(text)}</body></html>") + "--$boundary--\r\n"
        return BuiltMail(h.joinToString("\r\n") + "\r\n\r\n" + body, messageId)
    }
}
