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

/** The anchor: 30078 `d = belfry:sealed`, signed by the account, naming the root roster. */
object Anchor {
    const val KIND = 30078
    const val D = "belfry:sealed"

    /** The root id the newest valid anchor of ACCOUNT names, else null. */
    fun root(events: List<Event>, account: String): String? =
        events.filter { it.kind == KIND && it.pubkey == account && it.tag("d") == D && it.isValid() }
            .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
            .firstNotNullOfOrNull { e -> e.tags.singleValue("root")?.takeIf { Hex.isKey(it) } }

    fun template(root: String, now: Long): EventTemplate = EventTemplate(KIND, "", listOf(listOf("d", D), listOf("root", root)), now)
}

/** A device change between two heads, for the thread's notice lines (§3.4). */
data class RosterChange(val device: String, val added: Boolean, val at: Long)

/**
 * A person's valid chain (§3.3), root first. Its head's `lb` is where to seal for them; its
 * devices are theirs.
 */
class Chain(val account: String, val rosters: List<Roster>) {
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
         * null when there is none. Invalid events are skipped silently.
         */
        fun of(account: String, anchors: List<Event>, rosterEvents: List<Event>): Chain? {
            val rootId = Anchor.root(anchors, account) ?: return null
            val all = rosterEvents.mapNotNull { Roster.parse(it) }.filter { it.account == account }.associateBy { it.id }
            val root = all[rootId] ?: return null
            if (root.n != 1 || root.prev != null || root.signer !in root.devices) return null
            val byPrev = all.values.filter { it.prev != null }.groupBy { it.prev!! }
            val path = arrayListOf(root)
            val seenLb = hashSetOf(root.lb)
            while (true) {
                val cur = path.last()
                if (path.size > 100_000) break
                val next = byPrev[cur.id].orEmpty()
                    .filter { validAfter(cur, it, seenLb) }
                    .minByOrNull { it.id.lowercase() } ?: break
                path.add(next)
                seenLb.add(next.lb)
            }
            return Chain(account, path)
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
