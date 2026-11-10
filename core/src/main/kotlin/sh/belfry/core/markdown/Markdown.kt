package sh.belfry.core.markdown

import java.util.regex.Matcher
import java.util.regex.Pattern

// The Markdown subset (SPEC 9) as a tree, never as HTML in the chat. Ported line for line from
// the web chat's markdown.tsx (blocks, inline) and Topics.tsx (mentions, #topics and nsite links
// in plain text); vectors/markdown.json pins the behaviour, quirks included.

sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class Code(val text: String) : Inline
    data class Link(val href: String, val children: List<Inline>) : Inline
    /** `nostr:npub1…` in a chat message: shown as @Name. */
    data class Mention(val npub: String) : Inline
    /** `#topic` in a chat message ([name] as written, without the #). */
    data class Topic(val name: String) : Inline
    /** `nsite:npub1…[/name]` in a chat message: a link to its preview on the belfry ([source] is the text as written). */
    data class Nsite(val npub: String, val name: String?, val source: String) : Inline
    /** `nostr:naddr1…` (without `nostr:`): a channel reference when it is a group's kind 39000 address. */
    data class ChannelRef(val naddr: String) : Inline
}

sealed interface Block {
    /** Lines of inline nodes; show a line break between lines. */
    data class Paragraph(val lines: List<List<Inline>>) : Block
    data class Heading(val level: Int, val content: List<Inline>) : Block
    data class ListBlock(val ordered: Boolean, val items: List<List<Inline>>) : Block
    data class Quote(val lines: List<List<Inline>>) : Block
    data class CodeBlock(val text: String) : Block
}

/** JavaScript's idea of white space (`\s`, `trim()`), wider than Java's ASCII `\s`. */
internal object Js {
    const val WS = "\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"
    private fun isWs(c: Char): Boolean = c == ' ' || c in '\u0009'..'\u000d' || c == ' ' || c == ' ' ||
        c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿'

    fun trim(s: String): String = trimEnd(trimStart(s))
    fun trimStart(s: String): String {
        var i = 0
        while (i < s.length && isWs(s[i])) i++
        return s.substring(i)
    }
    fun trimEnd(s: String): String {
        var i = s.length
        while (i > 0 && isWs(s[i - 1])) i--
        return s.substring(0, i)
    }
    fun isBlank(s: String): Boolean = trim(s).isEmpty()

    /** String.replace(regex /g, fn), for patterns that never match the empty string. */
    fun replace(p: Pattern, s: String, fn: (Matcher) -> String): String {
        val m = p.matcher(s)
        val sb = StringBuilder()
        var last = 0
        while (m.find()) {
            sb.append(s, last, m.start()).append(fn(m))
            last = m.end()
        }
        sb.append(s, last, s.length)
        return sb.toString()
    }
}

object Markdown {
    private const val W = Js.WS
    private val SAFE_URL = Pattern.compile("^(https?://|mailto:)", Pattern.CASE_INSENSITIVE)
    private val INLINE = Pattern.compile(
        "(\\*\\*([^*]+)\\*\\*|__([^_]+)__|`([^`]+)`|~~([^~]+)~~|\\[([^\\]]+)\\]\\(([^)$W]+)\\)|\\*([^*$W][^*]*)\\*|_([^_$W][^_]*)_|(https?://[^$W<>()]+[^$W<>().,;:!?'\"]))"
    )
    private val RICH = Pattern.compile(
        "nsite:(npub1[02-9ac-hj-np-z]{58})(?:/([a-z0-9-]{1,13}))?|nostr:(npub1[02-9ac-hj-np-z]{58})|(^|[^\\p{L}\\p{N}_&/])#([\\p{L}\\p{N}_][\\p{L}\\p{N}_-]{0,48})|nostr:(naddr1[02-9ac-hj-np-z]{20,400})"
    )
    private val NADDR_IN_TEXT = Pattern.compile("nostr:(naddr1[02-9ac-hj-np-z]{20,400})")
    private val FENCE = Pattern.compile("^```")
    private val HEADING = Pattern.compile("^(#{1,3})[$W]+(.*)\\z", Pattern.DOTALL)
    private val LIST_ITEM = Pattern.compile("^[$W]*([-*+]|\\d+[.)])[$W]+")
    private val ORDERED = Pattern.compile("^[$W]*\\d+[.)][$W]+")
    private val QUOTE = Pattern.compile("^>[$W]?")
    private val PARA_STOP = Pattern.compile("^(#{1,3}[$W]|>[$W]?|[$W]*([-*+]|\\d+[.)])[$W]+)")
    private val NPUB_IN_TEXT = Pattern.compile("nostr:npub1[02-9ac-hj-np-z]+")
    private val MARKS = Pattern.compile("\\*\\*|__|~~|`")

    /** How plain text inside the Markdown is shown. */
    fun interface Plain {
        fun split(text: String): List<Inline>
    }

    /** Text as is (descriptions, profiles' about). */
    val asIs = Plain { listOf(Inline.Text(it)) }

    /** Plain text with @mentions (nostr:npub…), #topics and nsite links, as chat messages show it. */
    val rich = Plain { t ->
        val out = ArrayList<Inline>()
        val m = RICH.matcher(t)
        var last = 0
        while (m.find()) {
            val nsite = m.group(1)
            val npub = m.group(3)
            val naddr = m.group(6)
            if (naddr != null) {
                if (m.start() > last) out.add(Inline.Text(t.substring(last, m.start())))
                out.add(Inline.ChannelRef(naddr))
                last = m.end()
            } else if (nsite != null) {
                if (m.start() > last) out.add(Inline.Text(t.substring(last, m.start())))
                out.add(Inline.Nsite(nsite, m.group(2), m.group()))
                last = m.end()
            } else if (npub != null) {
                if (m.start() > last) out.add(Inline.Text(t.substring(last, m.start())))
                out.add(Inline.Mention(npub))
                last = m.end()
            } else {
                val at = m.start() + (m.group(4) ?: "").length
                if (at > last) out.add(Inline.Text(t.substring(last, at)))
                val name = m.group(5) ?: ""
                out.add(Inline.Topic(name))
                last = at + 1 + name.length
            }
        }
        if (last < t.length) out.add(Inline.Text(t.substring(last)))
        out
    }

    fun inline(text: String, plain: Plain = asIs): List<Inline> {
        val out = ArrayList<Inline>()
        val m = INLINE.matcher(text)
        var last = 0
        while (m.find()) {
            if (m.start() > last) out.addAll(plain.split(text.substring(last, m.start())))
            val bold = m.group(2) ?: m.group(3)
            val code = m.group(4)
            val strike = m.group(5)
            val label = m.group(6)
            val href = m.group(7)
            val italic = m.group(8) ?: m.group(9)
            val url = m.group(10)
            when {
                bold != null -> out.add(Inline.Bold(inline(bold, plain)))
                code != null -> out.add(Inline.Code(code))
                strike != null -> out.add(Inline.Strike(inline(strike, plain)))
                label != null && href != null ->
                    out.add(if (SAFE_URL.matcher(href).find()) Inline.Link(href, inline(label, asIs)) else Inline.Text(m.group()))
                italic != null -> out.add(Inline.Italic(inline(italic, plain)))
                url != null -> out.add(Inline.Link(url, listOf(Inline.Text(url))))
            }
            last = m.end()
        }
        if (last < text.length) out.addAll(plain.split(text.substring(last)))
        return out
    }

    internal fun lines(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")

    /**
     * The blocks of a text. [plain] says how text between the marks is shown: [rich] for chat
     * messages (the default), [asIs] for descriptions.
     */
    fun parse(text: String, plain: Plain = rich): List<Block> {
        val blocks = ArrayList<Block>()
        val src = lines(text)
        var i = 0
        while (i < src.size) {
            val line = src[i]
            if (Js.isBlank(line)) { i++; continue }
            if (FENCE.matcher(line).find()) {
                val code = ArrayList<String>()
                i++
                while (i < src.size && !FENCE.matcher(src[i]).find()) code.add(src[i++])
                i++
                blocks.add(Block.CodeBlock(code.joinToString("\n")))
                continue
            }
            val h = HEADING.matcher(line)
            if (h.find()) {
                blocks.add(Block.Heading(h.group(1).length, inline(h.group(2), plain)))
                i++
                continue
            }
            if (LIST_ITEM.matcher(line).find()) {
                val ordered = ORDERED.matcher(line).find()
                val items = ArrayList<String>()
                while (i < src.size && LIST_ITEM.matcher(src[i]).find()) items.add(LIST_ITEM.matcher(src[i++]).replaceFirst(""))
                blocks.add(Block.ListBlock(ordered, items.map { inline(it, plain) }))
                continue
            }
            if (QUOTE.matcher(line).find()) {
                val q = ArrayList<String>()
                while (i < src.size && QUOTE.matcher(src[i]).find()) q.add(QUOTE.matcher(src[i++]).replaceFirst(""))
                blocks.add(Block.Quote(q.map { inline(it, plain) }))
                continue
            }
            val p = ArrayList<String>()
            while (i < src.size && !Js.isBlank(src[i]) && !PARA_STOP.matcher(src[i]).find()) p.add(src[i++])
            if (p.isEmpty()) p.add(src[i++]) // cannot happen (every stop has its own branch); never loop forever
            blocks.add(Block.Paragraph(p.map { inline(it, plain) }))
        }
        return blocks
    }

    /** Merges adjacent text nodes and drops empty ones (vectors are compared this way). */
    fun merged(nodes: List<Inline>): List<Inline> {
        val out = ArrayList<Inline>()
        for (n in nodes) {
            val m: Inline = when (n) {
                is Inline.Bold -> Inline.Bold(merged(n.children))
                is Inline.Italic -> Inline.Italic(merged(n.children))
                is Inline.Strike -> Inline.Strike(merged(n.children))
                is Inline.Link -> Inline.Link(n.href, merged(n.children))
                else -> n
            }
            val prev = out.lastOrNull()
            if (m is Inline.Text) {
                if (m.text.isEmpty()) continue
                if (prev is Inline.Text) { out[out.size - 1] = Inline.Text(prev.text + m.text); continue }
            }
            out.add(m)
        }
        return out
    }

    fun merged(block: Block): Block = when (block) {
        is Block.Paragraph -> Block.Paragraph(block.lines.map { merged(it) })
        is Block.Heading -> Block.Heading(block.level, merged(block.content))
        is Block.ListBlock -> Block.ListBlock(block.ordered, block.items.map { merged(it) })
        is Block.Quote -> Block.Quote(block.lines.map { merged(it) })
        is Block.CodeBlock -> block
    }

    /** The readable text of inline nodes, without marks. [mention] names an npub (default "@…"). */
    fun text(nodes: List<Inline>, mention: (String) -> String = { "@…" }, channel: (String) -> String = { "#…" }): String = nodes.joinToString("") { n ->
        when (n) {
            is Inline.Text -> n.text
            is Inline.Bold -> text(n.children, mention, channel)
            is Inline.Italic -> text(n.children, mention, channel)
            is Inline.Strike -> text(n.children, mention, channel)
            is Inline.Code -> n.text
            is Inline.Link -> text(n.children, mention, channel)
            is Inline.Mention -> mention(n.npub)
            is Inline.Topic -> "#" + n.name
            is Inline.Nsite -> n.source
            is Inline.ChannelRef -> channel(n.naddr)
        }
    }

    /**
     * One line without Markdown marks, for previews (channel list, thread snippets, notifications):
     * `**` `__` `~~` and backticks removed, mentions named by [mention] (default "@…"), new lines
     * as spaces. Same as the previews of the iPhone app; the web strips mentions the same way.
     * Channel references (`nostr:naddr1…`) are named by [channel] (given the bare naddr; the
     * app passes `World.plainRefs`'s "#name").
     */
    fun plain(text: String, mention: (String) -> String = { "@…" }, channel: (String) -> String = { "#…" }): String {
        val noMarks = MARKS.matcher(text).replaceAll("")
        val refs = Js.replace(NADDR_IN_TEXT, noMarks) { m -> channel(m.group(1)) }
        val named = Js.replace(NPUB_IN_TEXT, refs) { m -> mention(m.group().removePrefix("nostr:")) }
        return Js.trim(named.replace("\r\n", "\n").replace('\r', '\n').replace('\n', ' '))
    }

    // ---- e-mail HTML (markdownHtml in mime.ts): escaped first, links only http(s) and mailto ----

    internal fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val H_CODE = Pattern.compile("`([^`]+)`")
    private val H_BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*|__([^_]+)__")
    private val H_STRIKE = Pattern.compile("~~([^~]+)~~")
    private val H_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(((?:https?://|mailto:)[^)$W]+)\\)")
    private val H_URL = Pattern.compile("(^|[^\"=>])(https?://[^$W<>()]+[^$W<>().,;:!?'\"])")
    private val H_EM1 = Pattern.compile("(^|[$W(])\\*([^*$W][^*]*)\\*")
    private val H_EM2 = Pattern.compile("(^|[$W(])_([^_$W][^_]*)_")
    private val H_HEADING = Pattern.compile("^#{1,3}[$W]+")
    private val H_PARA_STOP = Pattern.compile("^(```|#{1,3}[$W]|>[$W]?|[$W]*([-*+]|\\d+[.)])[$W]+)")

    private fun htmlInline(t: String): String {
        var s = esc(t)
        s = Js.replace(H_CODE, s) { "<code style=\"background:#f4f4f5;padding:1px 4px;border-radius:4px\">${it.group(1)}</code>" }
        s = Js.replace(H_BOLD, s) { "<strong>${it.group(1) ?: it.group(2)}</strong>" }
        s = Js.replace(H_STRIKE, s) { "<s>${it.group(1)}</s>" }
        s = Js.replace(H_LINK, s) { "<a href=\"${it.group(2)}\">${it.group(1)}</a>" }
        s = Js.replace(H_URL, s) { "${it.group(1)}<a href=\"${it.group(2)}\">${it.group(2)}</a>" }
        s = Js.replace(H_EM1, s) { "${it.group(1)}<em>${it.group(2)}</em>" }
        s = Js.replace(H_EM2, s) { "${it.group(1)}<em>${it.group(2)}</em>" }
        return s
    }

    /** Basic Markdown as e-mail HTML (the same subset the chat shows); the text can never inject markup. */
    fun html(md: String): String {
        val lines = lines(md)
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (Js.isBlank(l)) { i++; continue }
            if (FENCE.matcher(l).find()) {
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !FENCE.matcher(lines[i]).find()) code.add(lines[i++])
                i++
                out.add("<pre style=\"background:#f4f4f5;padding:8px;border-radius:6px\">${esc(code.joinToString("\n"))}</pre>")
            } else if (H_HEADING.matcher(l).find()) {
                out.add("<p><strong>${htmlInline(H_HEADING.matcher(l).replaceFirst(""))}</strong></p>")
                i++
            } else if (LIST_ITEM.matcher(l).find()) {
                val ordered = ORDERED.matcher(l).find()
                val items = ArrayList<String>()
                while (i < lines.size && LIST_ITEM.matcher(lines[i]).find()) items.add("<li>${htmlInline(LIST_ITEM.matcher(lines[i++]).replaceFirst(""))}</li>")
                out.add(if (ordered) "<ol>${items.joinToString("")}</ol>" else "<ul>${items.joinToString("")}</ul>")
            } else if (QUOTE.matcher(l).find()) {
                val q = ArrayList<String>()
                while (i < lines.size && QUOTE.matcher(lines[i]).find()) q.add(htmlInline(QUOTE.matcher(lines[i++]).replaceFirst("")))
                out.add("<blockquote style=\"border-left:3px solid #d4d4d8;margin:0;padding-left:10px;color:#52525b\">${q.joinToString("<br>")}</blockquote>")
            } else {
                val p = ArrayList<String>()
                while (i < lines.size && !Js.isBlank(lines[i]) && !H_PARA_STOP.matcher(lines[i]).find()) p.add(htmlInline(lines[i++]))
                if (p.isEmpty()) p.add(htmlInline(lines[i++])) // cannot happen (every stop has its own branch); never loop forever
                out.add("<p>${p.joinToString("<br>")}</p>")
            }
        }
        return "<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;font-size:15px;line-height:1.5\">${out.joinToString("\n")}</div>"
    }
}
