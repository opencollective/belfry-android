package sh.belfry.core.sealed

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.nostr.Event

/**
 * What a client remembers of a person's chain: the pin (sealed.md §3.3 "No going back", §3.4),
 * kept for good. A port of the reference (collective-belfry sealed.py `_pin` / `follow` /
 * `allow_locally` / `answer_without_prompt`, vectors `follow` and `trust`).
 */
data class Pin(
    /** The root first accepted. */
    val root: String,
    /** The head last accepted, and the roster ids root → head. */
    val head: String,
    val path: List<String>,
    /** anchored = (anchoredHead == head). */
    val anchored: Boolean,
    /** The furthest roster of the path this client saw as the end of the anchored path (the root at least). */
    val anchoredHead: String,
    /** Null, or after a switch the devices answered without a prompt (sorted). */
    val trusted: List<String>? = null,
    /** The head followed at the switch. */
    val switchHead: String? = null,
    /** The root pinned before a fresh start. */
    val prevRoot: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("root", JsonPrimitive(root)); put("head", JsonPrimitive(head))
        put("path", JsonArray(path.map { JsonPrimitive(it) }))
        put("anchored", JsonPrimitive(anchored)); put("anchored_head", JsonPrimitive(anchoredHead))
        put("trusted", trusted?.let { t -> JsonArray(t.map { JsonPrimitive(it) }) } ?: JsonNull)
        put("switch_head", switchHead?.let { JsonPrimitive(it) } ?: JsonNull)
        put("prev_root", prevRoot?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        fun fromJson(o: JsonObject?): Pin? {
            if (o == null) return null
            return Pin(
                root = str(o, "root") ?: return null, head = str(o, "head") ?: return null,
                path = (o["path"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return null,
                anchored = (o["anchored"] as? JsonPrimitive)?.content == "true",
                anchoredHead = str(o, "anchored_head") ?: str(o, "root")!!,
                trusted = (o["trusted"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content },
                switchHead = str(o, "switch_head"), prevRoot = str(o, "prev_root"),
            )
        }
    }
}

object Follow {
    enum class Change(val wire: String) { NONE("none"), MOVED("moved"), IGNORED_BACK("ignored_back"), SWITCH("switch"), FRESH_START("fresh_start") }

    class Result(val chain: Chain?, val pin: Pin?, val event: Change)

    private fun pin(c: Chain, seen: String, was: Pin?, trusted: Set<String>? = was?.trusted?.toSet(), switchHead: String? = was?.switchHead, prevRoot: String? = was?.prevRoot) = Pin(
        root = c.root.id, head = c.head.id, path = c.rosters.map { it.id },
        anchored = seen == c.head.id, anchoredHead = seen,
        trusted = trusted?.sorted(), switchHead = switchHead, prevRoot = prevRoot,
    )

    /** One roster after a switch: drops leave trust; adds are trusted only when the signer was. */
    private fun trustStep(trusted: MutableSet<String>, before: Roster, r: Roster) {
        val vouches = r.signer in trusted
        trusted.removeAll(before.devices.filter { it !in r.devices }.toSet())
        if (vouches) trusted.addAll(r.devices.filter { it !in before.devices })
    }

    private fun carry(p: Pin, c: Chain, seenIn: String): Pin {
        val ids = c.rosters.map { it.id }
        var seen = seenIn
        if (seen !in ids || ids.indexOf(c.anchored) > ids.indexOf(seen)) seen = c.anchored
        var trusted = p.trusted?.toMutableSet()
        if (trusted != null && p.switchHead != null && p.switchHead in ids) {
            var known = 0
            while (known < minOf(ids.size, p.path.size) && ids[known] == p.path[known]) known++
            for (i in maxOf(known, ids.indexOf(p.switchHead) + 1) until ids.size) trustStep(trusted, c.rosters[i - 1], c.rosters[i])
        }
        return pin(c, seen, p, trusted = trusted)
    }

    private fun changed(p: Pin, n: Pin) = if (n.head != p.head) Change.MOVED else Change.NONE

    /** (chain, pin', event) for a client holding PIN, from one read: ANCHOR (the newest valid one) and ROSTERS. */
    fun follow(anchor: Event?, rosters: List<Event>, p: Pin?): Result {
        val account = anchor?.pubkey ?: return Result(null, p, Change.NONE)
        val c = Chain.of(account, listOf(anchor), rosters) ?: return Result(null, p, Change.NONE)
        if (p == null) return Result(c, pin(c, c.anchored, null), Change.NONE)
        if (c.root.id != p.root) return Result(c, pin(c, c.anchored, p, trusted = null, switchHead = null, prevRoot = p.root), Change.FRESH_START)
        val path = p.path
        val seen = p.anchoredHead
        if (c.anchored in path.dropLast(1)) {
            // the anchor names an ancestor of the pinned head: kept; "going back" only when behind what was seen anchored
            val kept = Chain.of(account, listOf(anchor), rosters, pinned = p.head)
            val back = seen in path && path.indexOf(c.anchored) < path.indexOf(seen)
            if (kept == null || kept.rosters.none { it.id == p.head }) return Result(null, p, if (back) Change.IGNORED_BACK else Change.NONE)
            val n = carry(p, kept, seen)
            return Result(kept, n, if (back) Change.IGNORED_BACK else changed(p, n))
        }
        val ids = c.rosters.map { it.id }
        if (p.head in ids) { val n = carry(p, c, seen); return Result(c, n, changed(p, n)) }
        if (seen in ids) return Result(c, carry(p, c, seen), Change.MOVED) // a tail never anchored gave way
        // the anchor left a head this client saw anchored: a switch
        val held = rosters.mapNotNull { Roster.parse(it) }.firstOrNull { it.id == p.head && it.account == account }
        var before = held?.devices?.toSet() ?: emptySet()
        if (p.trusted != null) before = before intersect p.trusted.toSet() // a second switch never widens the first
        val trusted = before intersect c.devices.toSet()
        return Result(c, pin(c, c.anchored, p, trusted = trusted, switchHead = c.head.id), Change.SWITCH)
    }

    /** The person allowed DEVICE on this device: trusted from now on (after a switch). */
    fun allowLocally(p: Pin, device: String): Pin = if (p.trusted == null) p else p.copy(trusted = (p.trusted + device).toSortedSet().toList())

    /** §5.5: the no-prompt hand-over to a device of the head (strictly newer request; trusted after a switch). */
    fun answerWithoutPrompt(c: Chain?, p: Pin?, device: String, liveChat: Collection<String>, requestAt: Long, addedAt: Long? = null, lastHandover: Long? = null): Boolean {
        if (c == null || p == null || c.head.id != p.head || c.root.id != p.root) return false
        val end = c.rosters.firstOrNull { it.id == c.anchored } ?: return false
        if (device !in end.devices || device !in c.devices) return false
        if (p.trusted != null && device !in p.trusted) return false
        if (device !in liveChat) return false
        val added = addedAt ?: c.addedAt(device) ?: return false
        if (requestAt <= added) return false
        return lastHandover == null || requestAt > lastHandover
    }
}
