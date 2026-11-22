package sh.belfry.core.sealed

import sh.belfry.core.crypto.Hex
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.isValid

/**
 * Sealed conversations, the roster (collective-belfry docs/sealed.md v1, §3): which devices are a
 * person's. A roster (kind 8560) is signed by a device; the anchor (30078 `d = belfry:sealed`) is
 * signed by the account and names the chain's root. Everything that doesn't fit is ignored.
 */
data class Roster(
    val id: String,
    /** The device that signed it. */
    val signer: String,
    val account: String,
    val n: Int,
    val prev: String?,
    /** The letterbox public key. */
    val lb: String,
    val devices: List<String>,
    val ready: Boolean,
    val createdAt: Long,
) {
    companion object {
        const val KIND = 8560
        const val MAX_DEVICES = 20

        /** A well-formed, correctly signed roster, else null. */
        fun parse(e: Event): Roster? {
            if (e.kind != KIND || !e.isValid()) return null
            val account = e.tags.singleValue("p") ?: return null
            val n = e.tags.singleValue("n")?.takeIf { it.matches(Regex("^[1-9][0-9]{0,8}$")) }?.toInt() ?: return null
            val prevs = e.tags.filter { it.size >= 2 && it[0] == "prev" }
            if (prevs.size > 1) return null
            val prev = prevs.firstOrNull()?.get(1)
            if (prev != null && !Hex.isKey(prev)) return null
            val lb = e.tags.singleValue("lb") ?: return null
            val devices = e.tags.filter { it.size >= 2 && it[0] == "device" }.map { it[1] }
            if (!Hex.isKey(account) || !Hex.isKey(lb)) return null
            if (devices.isEmpty() || devices.size > MAX_DEVICES || devices.toSet().size != devices.size || devices.any { !Hex.isKey(it) }) return null
            return Roster(e.id, e.pubkey, account, n, prev, lb, devices, e.tags.any { it.isNotEmpty() && it[0] == "ready" }, e.createdAt)
        }

        /** The roster this device publishes (signed by the device key). */
        fun template(account: String, n: Int, prev: String?, lb: String, devices: List<String>, ready: Boolean, now: Long): EventTemplate {
            require(devices.isNotEmpty() && devices.size <= MAX_DEVICES)
            val tags = ArrayList<List<String>>()
            tags.add(listOf("p", account))
            tags.add(listOf("n", n.toString()))
            if (prev != null) tags.add(listOf("prev", prev))
            tags.add(listOf("lb", lb))
            devices.forEach { tags.add(listOf("device", it)) }
            if (ready) tags.add(listOf("ready"))
            return EventTemplate(KIND, "", tags, now)
        }
    }
}

/**
 * The anchor: 30078 `d = belfry:sealed`, signed by the account, naming the root roster and the
 * newest roster (`head`; absent = the root). Every device that publishes a roster publishes the
 * anchor again right after it (sealed.md §3.2, 61d7846).
 */
object Anchor {
    const val KIND = 30078
    const val D = "belfry:sealed"

    /** The root and head ids the newest valid anchor of ACCOUNT names (head null = the root), else null. */
    fun read(events: List<Event>, account: String): Pair<String, String?>? =
        events.filter { it.kind == KIND && it.pubkey == account && it.tags.singleValue("d") == D && it.isValid() }
            .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
            .firstNotNullOfOrNull { e ->
                val root = e.tags.singleValue("root")?.takeIf { Hex.isKey(it) } ?: return@firstNotNullOfOrNull null
                root to e.tags.singleValue("head")?.takeIf { Hex.isKey(it) }
            }

    fun root(events: List<Event>, account: String): String? = read(events, account)?.first

    fun template(root: String, head: String?, now: Long): EventTemplate =
        EventTemplate(KIND, "", listOf(listOf("d", D), listOf("root", root)) + (head?.let { listOf(listOf("head", it)) } ?: emptyList()), now)
}

/** A device change between two heads, for the thread's notice lines (§3.4). */
data class RosterChange(val device: String, val added: Boolean, val at: Long)

/**
 * A person's valid chain (§3.3), root first. Its head's `lb` is where to seal for them; its
 * devices are theirs.
 */
class Chain(val account: String, val rosters: List<Roster>, /** The end of the anchored path (the root at least). */ val anchored: String = rosters.first().id) {
    val root: Roster get() = rosters.first()
    val head: Roster get() = rosters.last()
    val devices: List<String> get() = head.devices

    /** Once a chain has had `ready`, it stays ready (§6). */
    val ready: Boolean get() = rosters.any { it.ready }

    /** Every letterbox public key the chain has used, oldest first. */
    val letterboxes: List<String> get() = rosters.map { it.lb }.distinct()

    /**
     * May a seal signed by DEVICE carry a rumor dated AT (§4)? Yes when it is in the head, or when
     * a roster held it and AT is not after the roster that dropped it.
     */
    fun accepts(device: String, at: Long): Boolean {
        if (device in head.devices) return true
        for (i in 0 until rosters.size - 1) {
            if (device in rosters[i].devices && device !in rosters[i + 1].devices && at <= rosters[i + 1].createdAt) return true
        }
        return false
    }

    /** Is DEVICE in any roster of the chain (the key handover's check, §5.5)? */
    fun everHad(device: String): Boolean = rosters.any { device in it.devices }

    /** "Added from ‹device›": the signer of the first roster that contains DEVICE, when that is another device. */
    fun addedFrom(device: String): String? = rosters.firstOrNull { device in it.devices }?.signer?.takeIf { it != device }

    /** When DEVICE first appeared (the roster's created_at). */
    fun addedAt(device: String): Long? = rosters.firstOrNull { device in it.devices }?.createdAt

    /** The roster that last ADDED DEVICE (in it, not in the one before; never the root), when it is still in the head. */
    fun addedBy(device: String): Roster? {
        if (device !in head.devices) return null
        var by: Roster? = null
        for (i in 1 until rosters.size) if (device in rosters[i].devices && device !in rosters[i - 1].devices) by = rosters[i]
        return by
    }

    /** Device changes after roster SINCE (by id; null = from the root), oldest first. */
    fun changesAfter(since: String?): List<RosterChange> {
        val start = if (since == null) 0 else rosters.indexOfFirst { it.id == since }.let { if (it < 0) return emptyList() else it + 1 }
        val out = ArrayList<RosterChange>()
        for (i in maxOf(start, 1) until rosters.size) {
            val before = rosters[i - 1].devices.toSet()
            val now = rosters[i].devices.toSet()
            now.filter { it !in before }.forEach { out.add(RosterChange(it, true, rosters[i].createdAt)) }
            before.filter { it !in now }.forEach { out.add(RosterChange(it, false, rosters[i].createdAt)) }
        }
        return out
    }

    companion object {
        /**
         * The valid chain of ACCOUNT from its anchors and rosters (both read without `since`), or
         * null when there is none (§3.3, 61d7846): the anchored path root → the anchor's `head`
         * (the root alone when `head` isn't a valid descendant of it); anything off that path is
         * ignored whatever its id; after its end, valid steps with the lower id winning for now.
         * PINNED is the head this client last accepted for ACCOUNT: an anchor whose `head` is an
         * ancestor of it is not followed back (PINNED is kept).
         */
        fun of(account: String, anchors: List<Event>, rosterEvents: List<Event>, pinned: String? = null): Chain? {
            val (rootId, anchorHead) = Anchor.read(anchors, account) ?: return null
            val all = rosterEvents.mapNotNull { Roster.parse(it) }.filter { it.account == account }.associateBy { it.id }
            val root = all[rootId] ?: return null
            if (root.n != 1 || root.prev != null || root.signer !in root.devices) return null

            var path = pathTo(anchorHead ?: rootId, root, all) ?: listOf(root)
            val anchoredEnd = path.last().id
            // no going back: an anchor naming an ancestor of the head I pinned keeps the pinned head
            if (pinned != null && pinned != path.last().id) {
                val p = pathTo(pinned, root, all)
                if (p != null && p.size > path.size && p.subList(0, path.size).map { it.id } == path.map { it.id }) path = p
            }
            val out = ArrayList(path)
            val seenLb = path.map { it.lb }.toHashSet()
            val byPrev = all.values.filter { it.prev != null }.groupBy { it.prev!! }
            while (out.size < 100_000) {
                val cur = out.last()
                val next = byPrev[cur.id].orEmpty().filter { validAfter(cur, it, seenLb) }.minByOrNull { it.id.lowercase() } ?: break
                out.add(next)
                seenLb.add(next.lb)
            }
            return Chain(account, out, anchoredEnd)
        }

        /** The valid path root → TARGET, or null when TARGET isn't a valid descendant of the root. */
        private fun pathTo(target: String, root: Roster, all: Map<String, Roster>): List<Roster>? {
            val back = ArrayList<Roster>()
            var cur = all[target] ?: return null
            val seen = HashSet<String>()
            while (true) {
                if (!seen.add(cur.id)) return null
                back.add(cur)
                if (cur.id == root.id) break
                cur = all[cur.prev ?: return null] ?: return null
            }
            val path = back.reversed()
            val lbs = hashSetOf(root.lb)
            for (k in 1 until path.size) {
                if (!validAfter(path[k - 1], path[k], lbs)) return null
                lbs.add(path[k].lb)
            }
            return path
        }

        private fun validAfter(prev: Roster, r: Roster, earlierLbs: Set<String>): Boolean {
            if (r.n != prev.n + 1 || r.account != prev.account) return false
            if (r.signer !in prev.devices) return false
            val missing = prev.devices.any { it !in r.devices }
            return if (missing) r.lb !in earlierLbs else r.lb == prev.lb
        }
    }
}

/** The one value of a tag that must appear once, else null. */
internal fun List<List<String>>.singleValue(name: String): String? {
    val all = filter { it.size >= 2 && it[0] == name }
    return if (all.size == 1) all[0][1] else null
}
