package sh.belfry.core.model

import sh.belfry.core.markdown.Js
import java.util.regex.Pattern

/**
 * Web addresses in what people write (SPEC 19.36 "Links"). One rule everywhere: `http://` or
 * `https://`, then anything up to white space, `<`, `>`, `(` or `)`, and never ending on
 * `.` `,` `;` `:` `!` `?` `'` `"` (the sentence's punctuation). A bare host ("example.org") is
 * not a link. The same rule is the URL branch of the Markdown inline pattern.
 */
object Links {
    private const val W = Js.WS
    const val URL_RE = "https?://[^$W<>()]+[^$W<>().,;:!?'\"]"
    private val URL: Pattern = Pattern.compile(URL_RE)

    data class Found(val start: Int, val end: Int, val url: String)

    /** Every address in TEXT, in order. */
    fun find(text: String): List<Found> {
        if (!text.contains("http")) return emptyList()
        val out = ArrayList<Found>()
        val m = URL.matcher(text)
        while (m.find()) out.add(Found(m.start(), m.end(), m.group()))
        return out
    }

    /**
     * TEXT with the Markdown marks (`_` `*` `~` backtick) inside its addresses replaced by a
     * neutral character of the same length, so emphasis never starts or ends inside an address
     * ("my_file https://a.org/b_c" keeps the whole address). Positions are unchanged.
     */
    fun masked(text: String): String {
        val found = find(text)
        if (found.isEmpty()) return text
        val chars = text.toCharArray()
        fun mark(c: Char) = c == '_' || c == '*' || c == '~' || c == '`'
        for (f in found) {
            // marks at its very end close an emphasis around it ("**see https://a.org/x**")
            var end = f.end
            while (end > f.start && mark(text[end - 1])) end--
            for (i in f.start until end) if (mark(chars[i])) chars[i] = 'x'
        }
        return String(chars)
    }

    /** A link to something this app shows itself (on this belfry's chat host). */
    sealed interface InApp {
        data class Channel(val id: String, val messageId: String?) : InApp
        /** A message in an event's chat, `#/events/<link>/m/<event>` (SPEC 19.44): its chat at the message. */
        data class EventMessage(val link: String, val messageId: String) : InApp
        /** An event's page (also a ticket's `/t/<code>`): handled like an opened event link. */
        data class Event(val url: String) : InApp
        /** A guest link: handled like an opened invitation. */
        data class Join(val url: String) : InApp
        /** A printed card's address (`/g/<id>`, SPEC 19.72). */
        data class Card(val url: String) : InApp
    }

    private val CHANNEL = Regex("^/(?:chat/)?#/c/([^/?#]{1,120})(?:/m/([0-9a-f]{64}))?/?$")
    private val EVENT_MESSAGE = Regex("^/(?:chat/)?#/events/([A-Za-z0-9_-]{1,80})/m/([0-9a-f]{64})/?$")

    /**
     * What URL opens inside the app on the belfry at DOMAIN: `https://chat.<domain>/#/c/<id>[/m/<event>]`
     * (also `https://<domain>/chat/#/c/…`), its event pages (`/events/<link>`, `/#/events/<link>`)
     * and guest links (`/join/<token>`). Anything else (another belfry too) opens in the browser.
     */
    fun inApp(url: String, domain: String): InApp? {
        if (domain.isEmpty()) return null
        val u = url.trim()
        val rest = when {
            u.startsWith("https://chat.$domain/") -> u.removePrefix("https://chat.$domain")
            u.startsWith("https://$domain/chat/") -> u.removePrefix("https://$domain")
            else -> return null
        }
        CHANNEL.matchEntire(rest)?.let { m ->
            val id = try { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (e: Exception) { return null }
            return InApp.Channel(id, m.groupValues[2].ifEmpty { null })
        }
        EVENT_MESSAGE.matchEntire(rest)?.let { m -> return InApp.EventMessage(m.groupValues[1], m.groupValues[2]) }
        if (u.startsWith("https://chat.$domain/")) {
            sh.belfry.core.net.InviteLink.parse(u)?.let { if (it.domain == domain) return InApp.Join(u) }
            sh.belfry.core.net.EventLink.parse(u)?.let { if (it.domain == domain) return InApp.Event(u) }
            CardLink.parse(u)?.let { if (it.domain == domain) return InApp.Card(u) }
        }
        return null
    }
}
