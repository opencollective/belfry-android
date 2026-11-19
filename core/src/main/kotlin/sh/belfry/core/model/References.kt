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
}

/** A piece of a message's text: words, a reference inside them (shown as its name), or the end box. */
sealed interface RefPart {
    data class Text(val text: String) : RefPart
    data class Ref(val target: RefTarget, val raw: String) : RefPart
    data class Box(val target: RefTarget, val raw: String) : RefPart
}

/**
 * References in a message (SPEC 19.44, `vectors/references.json`, reference implementation
 * `tools/vectors/references.mjs`; canvas channels/link-preview-end + -inline): this belfry's chat
 * link to a group (never with /m/), its event pages, and naddrs of kind 39000 / 31922 / 31923 /
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
        Regex("^https://(?:chat\\.$d|$d/chat)/#/c/([^/?#]{1,120})/?$").matchEntire(token)?.let { m ->
            return try { RefTarget.Group(java.net.URLDecoder.decode(m.groupValues[1].replace("+", "%2B"), "UTF-8")) } catch (e: Exception) { null }
        }
        Regex("^https://(?:chat\\.$d/(?:#/)?events|$d/events)/([A-Za-z0-9_-]{1,80})/?$").matchEntire(token)?.let { m ->
            return RefTarget.Event(link = m.groupValues[1])
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
enum class RefKind(val word: String) { CHANNEL("#channel"), GROUP("#group"), EVENT("Event"), PROPOSAL("Proposal") }

/** What a reference shows here and now. */
sealed interface RefSeen {
    val kind: RefKind
    /** Not here yet: a grey bar, then the kind's word. */
    data class Loading(override val kind: RefKind) : RefSeen
    data class GroupSeen(val group: Group) : RefSeen { override val kind get() = if (group.isPod) RefKind.GROUP else RefKind.CHANNEL }
    data class EventSeen(val event: CalEvent) : RefSeen { override val kind get() = RefKind.EVENT }
    data class ProposalSeen(val proposal: Proposal) : RefSeen { override val kind get() = RefKind.PROPOSAL }
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

fun World.refSeen(t: RefTarget): RefSeen = when (t) {
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
