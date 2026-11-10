package sh.belfry.core.model

import sh.belfry.core.crypto.Nip19
import java.util.Locale

/**
 * Channel references (the web chat's model.ts channelRef / channelOfNaddr / plainRefs, commit
 * 4d5682d). A channel picked after "#" in the composer is written as `nostr:naddr1…` (NIP-19/27:
 * kind 39000, the group's metadata, by the relay's key, d = group id, the groups relay as hint)
 * and shown as a "#name" chip that opens the channel. A "#word" typed by hand stays a topic.
 */
object ChannelRefs {
    const val KIND = 39000
    /** A reference in a text; group 1 = the bare naddr. */
    val NADDR = Regex("nostr:(naddr1[02-9ac-hj-np-z]{20,400})")
    private val NPUB_LOOSE = Regex("nostr:npub1[02-9ac-hj-np-z]+")
    // the web's /(^|[^\p{L}\p{N}_&/])#([\p{L}\p{N}_-]{0,30})$/u on the text before the caret
    private val PICKER = Regex("(^|[^\\p{L}\\p{N}_&/])#([\\p{L}\\p{N}_-]{0,30})\\z")

    /** The group id an naddr (without `nostr:`) points to, if it is a NIP-29 group's metadata. */
    fun groupOf(naddr: String): String? = Nip19.decodeNaddr(naddr)?.takeIf { it.kind == KIND }?.identifier

    /** The query of an open # picker, given the text before the caret; null when none is open. */
    fun query(beforeCaret: String): String? = PICKER.find(beforeCaret)?.groupValues?.get(2)

    /** A pick: replaces "#query" before the caret by "#Name ". Returns the new text and caret. */
    fun pick(text: String, caret: Int, query: String, name: String): Pair<String, Int> {
        val start = (caret - query.length - 1).coerceAtLeast(0)
        val label = "#$name"
        val next = text.substring(0, start) + label + " " + text.substring(caret.coerceAtMost(text.length))
        return next to (start + label.length + 1)
    }

    /** The text as sent: each picked "#Name" (longest first) → its `nostr:naddr…`. PICKED = (label "#Name", ref). */
    fun substitute(text: String, picked: List<Pair<String, String>>): String {
        var out = text
        for ((label, ref) in picked.sortedByDescending { it.first.length }) out = out.replace(label, ref)
        return out
    }

    /** One-line previews (the web's plainRefs): mentions as [npub], channel references as "#name"
     *  ([nameOf] gives the name of a known channel; its id otherwise); other naddrs as they are. */
    fun plain(text: String, npub: String = "@…", nameOf: (String) -> String?): String {
        val noKeys = NPUB_LOOSE.replace(text) { npub }
        return NADDR.replace(noKeys) { m ->
            val id = groupOf(m.groupValues[1])
            if (id == null) m.value else "#" + (nameOf(id) ?: id)
        }
    }
}

/** "nostr:naddr1…" for a channel, or null when its metadata hasn't been seen. */
fun World.channelRef(id: String): String? {
    val pubkey = metaAuthors[id] ?: return null
    val relays = ctx.groupsRelay.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
    return "nostr:" + Nip19.naddr(Nip19.Addr(ChannelRefs.KIND, pubkey, id, relays))
}

/** Text for one-line previews: mentions as "@…" (or [npub]), channel references as "#name". */
fun World.plainRefs(text: String, npub: String = "@…"): String = ChannelRefs.plain(text, npub) { group(it)?.name }

/** What a channel reference shows: the channel when it is known here, else null (a neutral label). */
fun World.channelOfRef(naddr: String): Group? = ChannelRefs.groupOf(naddr)?.let { group(it) }

/** A channel the # picker offers, with the reference a pick writes. */
data class ChannelPick(val group: Group, val ref: String)

/** The # picker: channels (not events' chats) whose id starts with the query or whose name has a
 *  word that does; most recently active first; at most 6. */
fun World.channelCandidates(query: String, limit: Int = 6): List<ChannelPick> {
    val q = query.lowercase(Locale.ROOT)
    return groups
        .filter { g -> !g.isEvent && (g.id.lowercase(Locale.ROOT).startsWith(q) || g.name.lowercase(Locale.ROOT).split(Regex("[\\s_-]+")).any { it.startsWith(q) }) }
        .mapNotNull { g -> channelRef(g.id)?.let { ChannelPick(g, it) } }
        .sortedByDescending { it.group.lastActivity }
        .take(limit)
}

/** For editing: each reference shown again as "#Name"; returns the text and the picks to send back. */
fun World.unsubstituteChannels(text: String): Pair<String, List<Pair<String, String>>> {
    val picked = ArrayList<Pair<String, String>>()
    val out = ChannelRefs.NADDR.replace(text) { m ->
        val id = ChannelRefs.groupOf(m.groupValues[1])
        if (id == null) m.value else {
            val label = "#" + (group(id)?.name ?: id)
            if (picked.none { it.first == label }) picked.add(label to m.value)
            label
        }
    }
    return out to picked
}

/** A bare naddr as previews name it ([plainRefs]): "#name", "#<id>" for a channel not known here,
 *  the reference as written when it is not a channel's. For `Markdown.plain(…, channel = …)`. */
fun World.refLabel(naddr: String): String =
    ChannelRefs.groupOf(naddr)?.let { "#" + (group(it)?.name ?: it) } ?: ("nostr:$naddr")
