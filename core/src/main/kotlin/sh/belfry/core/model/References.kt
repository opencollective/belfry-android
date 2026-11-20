package sh.belfry.core.model

import sh.belfry.core.crypto.Nip19
import sh.belfry.core.markdown.Js

/** What a reference in a message points to (SPEC 19.44). */
sealed interface RefTarget {
    /** A NIP-29 group: a channel, a group (pod), an event's chat or a proposal's chat; typed when shown. */
    data class Group(val id: String) : RefTarget
    /** An event by its coordinate (naddr 31922 / 31923) or its page's link (slug or d). */
    data class Event(val coord: String? = null, val link: String? = null) : RefTarget
    /** A proposal by its coordinate (naddr 30023). */
    data class Proposal(val coord: String) : RefTarget
    /** A to-do of a channel: `#/c/<group>/todo/<task>`. */
    data class Todo(val group: String, val task: String) : RefTarget
    /** A message (or thread reply) by its event id, in a channel (`#/c/<group>/m|thread/<id>`) or an event's chat (`#/events/<link>/m/<id>`). */
    data class Message(val id: String, val group: String? = null, val link: String? = null) : RefTarget
    /** A person: `#/u/<npub or hex>`. */
    data class Person(val pubkey: String) : RefTarget
    /** A drop box link: `https://<domain>/drop/<token>`. */
    data class Drop(val token: String) : RefTarget
}

/** A piece of a message's text: words, a reference inside them (shown as its name), or the end box. */
sealed interface RefPart {
    data class Text(val text: String) : RefPart
    data class Ref(val target: RefTarget, val raw: String) : RefPart
    data class Box(val target: RefTarget, val raw: String) : RefPart
}

/**
 * References in a message (SPEC 19.44, `vectors/references.json` as the web's chat-source has it,
 * ported step for step from its `src/core/refs.ts`; canvas channels/link-preview-end + -inline;
 * Xavier 2026-11-20: a link to something in the belfry never shows as a raw link): any address of
 * this belfry's chat (`https://chat.<domain>/#/…` or `https://<domain>/chat/#/…`) that names a
 * channel or one of its pages, a to-do, a message, an event (`#/cal/<coord>`, `#/events/<link>`)
 * or a person; its event pages; its drop box links; and naddrs of kind 39000 / 31922 / 31923 /
 * 30023. The last one, when the text (trimmed of trailing white space) ends with it, comes out of
 * the text as a preview box. Anything else stays as written.
 */
object References {
    private val URL = Regex(Links.URL_RE)
    private val NADDR = Regex("nostr:(naddr1[02-9ac-hj-np-z]{20,400})")
    private val TRAILING = Regex("[${Js.WS}]+$")

    fun target(token: String, domain: String): RefTarget? {
        if (token.startsWith("nostr:")) {
            val a = Nip19.decodeNaddr(token.removePrefix("nostr:")) ?: return null
            return when (a.kind) {
                39000 -> RefTarget.Group(a.identifier)
                31922, 31923 -> RefTarget.Event(coord = "${a.kind}:${a.pubkey}:${a.identifier}")
                30023 -> RefTarget.Proposal("${a.kind}:${a.pubkey}:${a.identifier}")
                else -> null
            }
        }
        if (domain.isEmpty()) return null
        val d = Regex.escape(domain)
        Regex("^https://(?:chat\\.$d|$d/chat)/#/([^?#${Js.WS}]{1,600})$").matchEntire(token)?.let { return route(it.groupValues[1]) }
        Regex("^https://(?:chat\\.$d|$d)/events/([A-Za-z0-9_-]{1,80})/?$").matchEntire(token)?.let { return RefTarget.Event(link = it.groupValues[1]) }
        Regex("^https://$d/drop/([A-Za-z0-9_-]{8,100})/?$").matchEntire(token)?.let { return RefTarget.Drop(it.groupValues[1]) }
        return null
    }

    private val HEX = Regex("^[0-9a-f]{64}$")
    private val LINK = Regex("^[A-Za-z0-9_-]{1,80}$")
    private val TASK = Regex("^[A-Za-z0-9_-]{1,100}$")
    private val COORD = Regex("^3192[23]:[0-9a-f]{64}:[^${Js.WS}]{1,200}$")
    /** A channel's pages that show the channel itself (the web's App.tsx). */
    private val GROUP_PAGES = setOf("group", "info", "about", "members", "images", "photos", "files", "todos", "log", "events")

    /** A chat route (after "#/") as a reference, or null (the app's places, a ticket, …). */
    private fun route(path: String): RefTarget? {
        val seg = try {
            path.trimEnd('/').split("/").map { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        } catch (e: Exception) { return null }
        if (seg.any { it.isEmpty() || it.length > 200 }) return null
        val a = seg[0]; val b = seg.getOrNull(1); val c = seg.getOrNull(2); val dd = seg.getOrNull(3)
        val more = if (seg.size > 4) seg.subList(4, seg.size) else emptyList()
        if (a == "c" && b != null && b.length <= 120) {
            if (c == null) return RefTarget.Group(b)
            if (c == "todo" && dd != null && more.isEmpty() && TASK.matches(dd)) return RefTarget.Todo(b, dd)
            if ((c == "m" || c == "thread") && dd != null && HEX.matches(dd) && (more.isEmpty() || more == listOf("reply"))) return RefTarget.Message(dd, group = b)
            if (c in GROUP_PAGES && (dd == null || (c == "info" && dd == "members" && more.isEmpty()))) return RefTarget.Group(b)
            if (c == "t" && dd != null && more.isEmpty()) return RefTarget.Group(b)
            return null
        }
        if (a == "cal" && b != null && c == null && COORD.matches(b)) return RefTarget.Event(coord = b)
        if (a == "events" && b != null && LINK.matches(b)) {
            if (c == null) return RefTarget.Event(link = b)
            if (c == "m" && dd != null && HEX.matches(dd) && more.isEmpty()) return RefTarget.Message(dd, link = b)
            return null // a ticket (/t/<code>) stays a link
        }
        if (a == "u" && b != null && c == null) {
            if (HEX.matches(b)) return RefTarget.Person(b)
            sh.belfry.core.crypto.Bech32.npubToHex(b)?.let { return RefTarget.Person(it) }
        }
        return null
    }

    fun parts(text: String, domain: String): List<RefPart> {
        data class F(val start: Int, val end: Int, val t: RefTarget, val raw: String)
        val found = ArrayList<F>()
        for (re in listOf(NADDR, URL)) for (m in re.findAll(text)) {
            target(m.value, domain)?.let { found.add(F(m.range.first, m.range.last + 1, it, m.value)) }
        }
        if (found.isEmpty()) return listOf(RefPart.Text(text))
        found.sortBy { it.start }
        val out = ArrayList<RefPart>()
        fun push(s: String) {
            if (s.isEmpty()) return
            val l = out.lastOrNull()
            if (l is RefPart.Text) out[out.size - 1] = RefPart.Text(l.text + s) else out.add(RefPart.Text(s))
        }
        val trimmedEnd = TRAILING.replace(text, "").length
        var last = 0
        found.forEachIndexed { i, f ->
            if (f.start < last) return@forEachIndexed
            push(text.substring(last, f.start))
            if (i == found.size - 1 && f.end == trimmedEnd) {
                val l = out.lastOrNull()
                if (l is RefPart.Text) {
                    val t = TRAILING.replace(l.text, "")
                    if (t.isEmpty()) out.removeAt(out.size - 1) else out[out.size - 1] = RefPart.Text(t)
                }
                out.add(RefPart.Box(f.t, f.raw))
            } else {
                out.add(RefPart.Ref(f.t, f.raw))
            }
            last = f.end
        }
        if (out.lastOrNull() !is RefPart.Box) push(text.substring(last))
        return out
    }

    /** The end box of TEXT and the text without it, or null when it has none. */
    fun endBox(text: String, domain: String): Pair<String, RefPart.Box>? {
        val p = parts(text, domain)
        val box = p.lastOrNull() as? RefPart.Box ?: return null
        val before = text.substring(0, text.lastIndexOf(box.raw)).let { TRAILING.replace(it, "") }
        return before to box
    }
}

/** The kind's word, shown while a name hasn't come after 5 s (SPEC 19.43 / 19.44). */
enum class RefKind(val word: String) { CHANNEL("#channel"), GROUP("#group"), EVENT("Event"), PROPOSAL("Proposal"), TODO("To-do"), MESSAGE("Message"), PERSON("Someone"), DROP("Drop box") }

/** What a reference shows here and now. */
sealed interface RefSeen {
    val kind: RefKind
    /** Not here yet: a grey bar, then the kind's word. */
    data class Loading(override val kind: RefKind) : RefSeen
    data class GroupSeen(val group: Group) : RefSeen { override val kind get() = if (group.isPod) RefKind.GROUP else RefKind.CHANNEL }
    data class EventSeen(val event: CalEvent) : RefSeen { override val kind get() = RefKind.EVENT }
    data class ProposalSeen(val proposal: Proposal) : RefSeen { override val kind get() = RefKind.PROPOSAL }
    data class TodoSeen(val group: Group, val task: Task) : RefSeen { override val kind get() = RefKind.TODO }
    /** A message: its channel (an event's chat: [event] too) and the message as it reads now. */
    data class MessageSeen(val group: Group, val message: Message, val event: CalEvent? = null) : RefSeen { override val kind get() = RefKind.MESSAGE }
    data class PersonSeen(val pubkey: String) : RefSeen { override val kind get() = RefKind.PERSON }
    /** A live drop box link: its channel when known, until when it takes files. */
    data class DropSeen(val token: String, val url: String, val group: Group?, val until: Long) : RefSeen { override val kind get() = RefKind.DROP }
    /** I can't open it (a closed group, a members-only channel for a guest): no name, a lock. */
    data class Locked(override val kind: RefKind) : RefSeen
    data class Deleted(override val kind: RefKind) : RefSeen
}

private fun kindOfId(id: String): RefKind = when {
    id.startsWith("pod-") -> RefKind.GROUP
    id.startsWith("prop-") -> RefKind.PROPOSAL
    id.startsWith("ev-") -> RefKind.EVENT
    else -> RefKind.CHANNEL
}

/** Can I open group G? Not a closed one I'm not in, not a members-only one as a guest. */
fun World.canOpenRef(g: Group): Boolean {
    if (me in g.members) return true
    if (g.closed) return false
    if (amGuest && channelAccess(g) != ChannelAccess.PUBLIC) return false
    return true
}

/** What reference T shows; NOW (Unix seconds) ends a drop box link. */
fun World.refSeen(t: RefTarget, now: Long = System.currentTimeMillis() / 1000): RefSeen = when (t) {
    is RefTarget.Person -> RefSeen.PersonSeen(t.pubkey)
    is RefTarget.Drop -> {
        val e = ofKind(30078).lastOrNull { it.t("d") == "drop:${t.token}" }
        val until = e?.t("expiration")?.toDoubleOrNull()?.toLong() ?: 0
        when {
            e == null -> RefSeen.Loading(RefKind.DROP)
            e.id in deleted || "30078:${e.pubkey}:drop:${t.token}" in deleted || (until in 1..now) -> RefSeen.Deleted(RefKind.DROP)
            else -> RefSeen.DropSeen(t.token, dropUrl(t.token), e.t("h")?.let { group(it) }, until)
        }
    }
    is RefTarget.Todo -> {
        val g = group(t.group)
        when {
            g == null -> if (t.group in groupIndex.deleted) RefSeen.Deleted(RefKind.TODO) else RefSeen.Loading(RefKind.TODO)
            !canOpenRef(g) -> RefSeen.Locked(RefKind.TODO)
            else -> tasks(g.id).firstOrNull { it.id == t.task }?.let { RefSeen.TodoSeen(g, it) } ?: RefSeen.Loading(RefKind.TODO)
        }
    }
    is RefTarget.Message -> {
        val ev = t.link?.let { eventByLink(it) }
        val gid = t.group ?: ev?.groupId
        val g = gid?.let { group(it) }
        when {
            gid != null && g == null && gid in groupIndex.deleted -> RefSeen.Deleted(RefKind.MESSAGE)
            t.id in deleted -> RefSeen.Deleted(RefKind.MESSAGE)
            g != null && !canOpenRef(g) -> RefSeen.Locked(RefKind.MESSAGE)
            g == null -> RefSeen.Loading(RefKind.MESSAGE)
            else -> message(g.id, t.id)?.let { m -> RefSeen.MessageSeen(g, m, ev ?: calendar.firstOrNull { it.groupId == g.id }) } ?: RefSeen.Loading(RefKind.MESSAGE)
        }
    }
    is RefTarget.Group -> {
        val g = group(t.id)
        when {
            t.id in groupIndex.deleted -> RefSeen.Deleted(kindOfId(t.id))
            g == null -> RefSeen.Loading(kindOfId(t.id))
            !canOpenRef(g) -> RefSeen.Locked(if (g.isPod) RefKind.GROUP else if (g.isEvent) RefKind.EVENT else if (g.isProposal) RefKind.PROPOSAL else RefKind.CHANNEL)
            g.isEvent -> calendar.firstOrNull { it.groupId == g.id }?.let { RefSeen.EventSeen(it) } ?: RefSeen.Loading(RefKind.EVENT)
            g.isProposal -> proposals.firstOrNull { it.groupId == g.id }?.let { RefSeen.ProposalSeen(it) } ?: RefSeen.Loading(RefKind.PROPOSAL)
            else -> RefSeen.GroupSeen(g)
        }
    }
    is RefTarget.Event -> {
        val e = t.coord?.let { eventByCoord(it) } ?: t.link?.let { eventByLink(it) }
        when {
            t.coord != null && t.coord in deleted -> RefSeen.Deleted(RefKind.EVENT)
            e == null -> RefSeen.Loading(RefKind.EVENT)
            else -> RefSeen.EventSeen(e)
        }
    }
    is RefTarget.Proposal -> {
        val p = proposals.firstOrNull { it.coord == t.coord }
        when {
            t.coord in deleted -> RefSeen.Deleted(RefKind.PROPOSAL)
            p == null -> RefSeen.Loading(RefKind.PROPOSAL)
            else -> RefSeen.ProposalSeen(p)
        }
    }
}
