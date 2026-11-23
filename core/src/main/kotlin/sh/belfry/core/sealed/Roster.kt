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
    /** §5.7: the devices this roster endorses (its `endorse` tags), after they added themselves. */
    val endorses: List<String> = emptyList(),
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
            return Roster(e.id, e.pubkey, account, n, prev, lb, devices, e.tags.any { it.isNotEmpty() && it[0] == "ready" }, e.createdAt,
                e.tags.filter { it.size >= 2 && it[0] == "endorse" && Hex.isKey(it[1]) }.map { it[1] })
        }

        /** The roster this device publishes (signed by the device key). */
        fun template(account: String, n: Int, prev: String?, lb: String, devices: List<String>, ready: Boolean, now: Long, endorse: List<String> = emptyList()): EventTemplate {
            require(devices.isNotEmpty() && devices.size <= MAX_DEVICES)
            val tags = ArrayList<List<String>>()
            tags.add(listOf("p", account))
            tags.add(listOf("n", n.toString()))
            if (prev != null) tags.add(listOf("prev", prev))
            tags.add(listOf("lb", lb))
            devices.forEach { tags.add(listOf("device", it)) }
            if (ready) tags.add(listOf("ready"))
            // §5.7 "It was me": one tag per device that added itself and is confirmed here; always the last tags
            endorse.forEach { tags.add(listOf("endorse", it)) }
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
data class RosterChange(
    val device: String, val added: Boolean, val at: Long,
    /** The roster that made the change, and whether the device added itself by it (a join, §5.7). */
    val roster: String = "", val self: Boolean = false,
)

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

    /** §5.7: did DEVICE add itself (the roster that last added it is signed by it)? */
    fun selfAdded(device: String): Boolean = addedBy(device)?.signer == device

    /** Is roster I of the chain a join (signed by a device that is not in the roster before)? */
    fun isJoin(i: Int): Boolean = i > 0 && rosters[i].signer !in rosters[i - 1].devices

    /**
     * §5.7: the devices of the head that added themselves and that no later roster endorses (head order): they
     * read and write, and no roster they sign counts.
     */
    val unendorsed: List<String> get() = waiting(rosters).keys.let { w -> head.devices.filter { it in w } }

    /** The devices that may endorse DEVICE (those of the roster before its join), when it still waits; else empty. */
    fun endorsers(device: String): List<String> = waiting(rosters)[device].orEmpty()

    /** The joiners that roster I endorses (waiting before it, not after, still in it). */
    fun lifted(i: Int): List<String> {
        if (i <= 0 || i >= rosters.size) return emptyList()
        val a = waiting(rosters.subList(0, i)); val b = waiting(rosters.subList(0, i + 1))
        return a.keys.filter { it !in b && it in rosters[i].devices }
    }

    /** The device that brought letterbox LB into the chain (the signer of the first roster with it): it holds its secret. */
    fun broughtBy(lb: String): String? = rosters.firstOrNull { it.lb == lb }?.signer

    /** Device changes after roster SINCE (by id; null = from the root), oldest first. */
    fun changesAfter(since: String?): List<RosterChange> {
        val start = if (since == null) 0 else rosters.indexOfFirst { it.id == since }.let { if (it < 0) return emptyList() else it + 1 }
        val out = ArrayList<RosterChange>()
        for (i in maxOf(start, 1) until rosters.size) {
            val before = rosters[i - 1].devices.toSet()
            val now = rosters[i].devices.toSet()
            now.filter { it !in before }.forEach { out.add(RosterChange(it, true, rosters[i].createdAt, rosters[i].id, self = rosters[i].signer == it)) }
            before.filter { it !in now }.forEach { out.add(RosterChange(it, false, rosters[i].createdAt, rosters[i].id)) }
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
         *
         * §5.7 (belfry-sh 1440e79): a JOIN (a roster signed by a device that is not in its prev: prev's devices
         * in their order + the signer last, a letterbox and a device the chain never had, dated not before its
         * prev) is a valid step only on the anchored path, and in the pinned path only up to SEEN_ANCHORED (the
         * furthest roster this client saw anchored; null = the root); never in the tail. A normal roster signed by
         * a device that added itself is invalid, on the path and in the tail, until a roster after its join carries
         * `["endorse", it]` signed by a device of the roster before that join.
         */
        fun of(account: String, anchors: List<Event>, rosterEvents: List<Event>, pinned: String? = null, seenAnchored: String? = null): Chain? {
            val (rootId, anchorHead) = Anchor.read(anchors, account) ?: return null
            val all = rosterEvents.mapNotNull { Roster.parse(it) }.filter { it.account == account }.associateBy { it.id }
            val root = all[rootId] ?: return null
            if (root.n != 1 || root.prev != null || root.signer !in root.devices) return null

            var walk = pathTo(anchorHead ?: rootId, root, all, null) ?: Walk(root)
            val anchoredEnd = walk.path.last().id
            // no going back: an anchor naming an ancestor of the head I pinned keeps the pinned head
            if (pinned != null && pinned != anchoredEnd) {
                val p = pathTo(pinned, root, all, seenAnchored ?: rootId)
                val size = walk.path.size
                if (p != null && p.path.size > size && p.path.subList(0, size).map { it.id } == walk.path.map { it.id }) walk = p
            }
            val byPrev = all.values.filter { it.prev != null }.groupBy { it.prev!! }
            while (walk.path.size < 100_000) {
                val cur = walk.path.last()
                val kids = byPrev[cur.id].orEmpty()
                if (kids.isEmpty()) break
                val limited = waiting(walk.path)
                val next = kids.filter { follows(cur, it, walk.lbs) && it.signer !in limited }.minByOrNull { it.id.lowercase() } ?: break
                walk.add(next)
            }
            return Chain(account, walk.path, anchoredEnd)
        }

        /** A path being walked: its rosters, the letterboxes and the devices it ever had. */
        private class Walk(root: Roster) {
            val path = arrayListOf(root)
            val lbs = hashSetOf(root.lb)
            val had = HashSet(root.devices)
            fun add(r: Roster) { path.add(r); lbs.add(r.lb); had.addAll(r.devices) }
        }

        /**
         * The valid path root → TARGET, or null when TARGET isn't a valid descendant of the root. JOINS_UNTIL
         * null: a join is accepted anywhere on the way (the anchored path); else only up to that roster (a
         * pinned path: what this client saw anchored).
         */
        private fun pathTo(target: String, root: Roster, all: Map<String, Roster>, joinsUntil: String?): Walk? {
            val back = ArrayList<Roster>()
            var cur = all[target] ?: return null
            val seen = HashSet<String>()
            while (true) {
                if (!seen.add(cur.id)) return null
                back.add(cur)
                if (cur.id == root.id) break
                cur = all[cur.prev ?: return null] ?: return null
            }
            val way = back.reversed()
            val limit = if (joinsUntil == null) way.size else way.indexOfFirst { it.id == joinsUntil }.let { if (it < 0) 1 else it + 1 }
            val w = Walk(root)
            for (k in 1 until way.size) {
                val r = way[k]
                val prev = w.path.last()
                if (follows(prev, r, w.lbs)) {
                    if (r.signer in waiting(w.path)) return null // §5.7: a device that added itself changes nothing until endorsed
                } else if (!(k < limit && joins(prev, r, w.lbs, w.had))) return null
                w.add(r)
            }
            return w
        }

        /** §3.3 "Roster n": signed by a device of the roster before. */
        private fun follows(prev: Roster, r: Roster, earlierLbs: Set<String>): Boolean {
            if (r.n != prev.n + 1 || r.account != prev.account) return false
            if (r.signer !in prev.devices) return false
            val missing = prev.devices.any { it !in r.devices }
            return if (missing) r.lb !in earlierLbs else r.lb == prev.lb
        }

        /** §5.7: a device that signed in adds itself (only the anchor makes it count: the callers see to that). */
        private fun joins(prev: Roster, r: Roster, earlierLbs: Set<String>, had: Set<String>): Boolean =
            r.n == prev.n + 1 && r.account == prev.account && r.signer !in prev.devices && r.signer !in had &&
                r.devices == prev.devices + r.signer && r.lb !in earlierLbs && r.createdAt >= prev.createdAt

        /**
         * §5.7: the devices of PATH that added themselves and that no later roster endorses, each with the devices
         * of the roster before its join (who may endorse it). An endorsement is a roster's `["endorse", device]`
         * tag signed by one of those. A device that left the head waits for nothing.
         */
        internal fun waiting(path: List<Roster>): Map<String, List<String>> {
            val w = LinkedHashMap<String, List<String>>()
            for (i in 1 until path.size) {
                val before = path[i - 1]; val r = path[i]
                if (r.signer !in before.devices) { w[r.signer] = before.devices; continue }
                for (d in r.endorses) if (w[d]?.contains(r.signer) == true) w.remove(d)
                w.keys.filter { it !in r.devices }.forEach { w.remove(it) }
            }
            return w
        }
    }
}

/** The one value of a tag that must appear once, else null. */
internal fun List<List<String>>.singleValue(name: String): String? {
    val all = filter { it.size >= 2 && it[0] == name }
    return if (all.size == 1) all[0][1] else null
}
