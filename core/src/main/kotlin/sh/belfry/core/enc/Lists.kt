package sh.belfry.core.enc

import sh.belfry.core.nostr.Event

/** §3 one device list (kind 8570): devices (0 to 20, the account's own key when `self`) and readers (§11, outside the 20). */
data class DeviceList(
    val id: String,
    val account: String,
    val n: Int,
    val prev: String?,
    val devices: List<String>,
    val readers: List<String>,
    val createdAt: Long,
) {
    /** Every key that receives copies for this account now: devices and readers. */
    val receivers: List<String> get() = devices + readers
}

/** An account's lists, one per n seen, ascending (§17.7: the current list is the highest n; a missing older one doesn't block). */
data class Chain(
    val account: String,
    val lists: List<DeviceList>,
    /** some n below the highest is missing: an old seal may be judged once it comes */
    val gaps: Boolean,
    /** §17.7: two different lists with the same n, or a prev that doesn't name the list before: warn, for good */
    val fork: Boolean,
    /** a signed list of the belfry for this account, with a higher n than every valid one, that isn't valid: hold (§18.5) */
    val invalidAbove: Boolean,
) {
    val current: DeviceList get() = lists.last()
    fun listById(id: String): DeviceList? = lists.firstOrNull { it.id == id }
}

/**
 * §20 "three answers, never two": which devices an account has. Never fold the last two into one
 * value: a seal signed with a member's identity key is never accepted because the list is missing.
 */
sealed interface Devices {
    /** Its list chain. */
    data class Listed(val chain: Chain) : Devices
    /** Exactly the belfry key or the groups key: its own device, no list. */
    data object Service : Devices
    /** No list known: Held, judged again when one comes. */
    data object Unknown : Devices

    companion object {
        fun of(chain: Chain?): Devices = chain?.let { Listed(it) } ?: Unknown

        /** ACCOUNT's devices: SERVICE for exactly BELFRY or GROUPS_KEY (when known), else its chain or Unknown. */
        fun of(account: String, belfry: String, groupsKey: String?, chain: Chain?): Devices =
            if (account == belfry || (!groupsKey.isNullOrEmpty() && account == groupsKey)) Service else of(chain)
    }
}

/** A device change on one list against the list before it (§17.7). */
data class ListChange(val list: String, val n: Int, val added: List<String>, val removed: List<String>, val readersAdded: List<String>)

/** §3, §17, §18.5, §20, §21: device lists, chains and the dates around a listing. */
object EncLists {
    private val N = Regex("^[1-9][0-9]{0,8}$")

    /**
     * E as a device list of the belfry BELFRY, or null. Devices: 0 to 20 keys, each once; readers
     * (`["device", key, "reader"]`) apart; an unknown marker skips that entry (not the list); the
     * account's own key as `["device", account, "self"]` only alone on its list (readers allowed).
     */
    fun parseList(e: Event, belfry: String): DeviceList? {
        if (!Enc.valid(e) || e.kind != Enc.LIST || e.pubkey != belfry || e.content != "") return null
        val account = Enc.one(e, "p")
        val n = Enc.one(e, "n")
        val prevs = Enc.values(e, "prev")
        if (!Enc.isKey(account) || n == null || !N.matches(n)) return null
        val num = n.toInt()
        if (if (num == 1) prevs.isNotEmpty() else prevs.size != 1 || !Enc.isKey(prevs[0])) return null
        val devices = mutableListOf<String>()
        val readers = mutableListOf<String>()
        for (t in e.tags) {
            if (t.isEmpty() || t[0] != "device") continue
            val key = t.getOrNull(1)
            val mark = t.getOrNull(2)
            if (!Enc.isKey(key) || t.size > 3 || (mark == "self" && key != account)) return null
            if (t.size == 3 && mark != "reader" && mark != "self") continue // a marker this app doesn't know: that entry is skipped
            if (key!! in devices || key in readers) return null
            if (mark == "reader") readers += key else devices += key
        }
        if (devices.size > Enc.MAX_LIST) return null
        if (e.tags.any { it.size >= 3 && it[0] == "device" && it[2] == "self" } && devices.size != 1) return null
        return DeviceList(e.id, account!!, num, if (num > 1) prevs[0] else null, devices, readers, e.createdAt)
    }

    /** JavaScript's Number(string) for the n of a list that isn't valid (NaN as null). */
    private fun jsNumber(s: String?): Double? {
        if (s == null) return null
        val t = s.trim()
        if (t.isEmpty()) return 0.0
        if (Regex("^0[xX][0-9a-fA-F]+$").matches(t)) return t.substring(2).toBigInteger(16).toDouble()
        if (Regex("^[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?$").matches(t)) return t.toDouble()
        return null
    }

    /** ACCOUNT's lists among EVENTS (several for one n: a fork, the lower id kept). Null when there is none (Held). */
    fun chainOf(events: List<Event>, belfry: String, account: String): Chain? {
        val byN = HashMap<Int, MutableList<DeviceList>>()
        var badTop = 0.0
        for (e in events) {
            val l = parseList(e, belfry)
            if (l == null) {
                // not valid: remember its n when it is the belfry's signed word about this account
                if (Enc.valid(e) && e.kind == Enc.LIST && e.pubkey == belfry && e.tags.any { it.size >= 2 && it[0] == "p" && it[1] == account }) {
                    val n = jsNumber(e.tags.firstOrNull { it.isNotEmpty() && it[0] == "n" }?.getOrNull(1))
                    if (n != null && n.isFinite() && n % 1.0 == 0.0 && n > badTop) badTop = n
                }
                continue
            }
            if (l.account != account) continue
            val at = byN.getOrPut(l.n) { mutableListOf() }
            if (at.none { it.id == l.id }) at += l
        }
        if (byN.isEmpty()) return null
        var fork = false
        val lists = byN.keys.sorted().map { n ->
            val c = byN.getValue(n).sortedBy { it.id }
            if (c.size > 1) fork = true
            c[0]
        }
        lists.forEachIndexed { i, l ->
            val before = lists.getOrNull(i - 1)
            if (before != null && before.n == l.n - 1 && l.prev != before.id) fork = true
        }
        val top = lists.last().n
        return Chain(account, lists, gaps = lists.size < top, fork = fork, invalidAbove = badTop > top)
    }

    /** §17.7 what each list added and removed against the list before (the first list known adds nothing). */
    fun changes(c: Chain): List<ListChange> = c.lists.drop(1).mapIndexed { i, l ->
        val b = c.lists[i]
        ListChange(l.id, l.n, l.devices.filter { it !in b.devices }, b.devices.filter { it !in l.devices }, l.readers.filter { it !in b.readers })
    }

    /** §17.6 + §20: OLDER's first list ever comes before DEVICE's first list ever (a device never listed has no "before"). */
    fun listedBefore(c: Chain, older: String, device: String): Boolean {
        val a = c.lists.indexOfFirst { older in it.devices }
        val b = c.lists.indexOfFirst { device in it.devices }
        return a >= 0 && b >= 0 && a < b
    }

    /**
     * §6 + §18.2 + §21: DEVICE may sign for the account at AT (the OUTER event's date). Readers never
     * sign as the account. Null = can't tell yet (wait): a list around AT is missing, or the device is
     * on no list known here and AT isn't before the newest list (its list may be behind).
     */
    fun signsAt(c: Chain, device: String, at: Long): Boolean? {
        val v = listedAt(c, device, at)
        if (v != false) return v
        if (c.lists.none { device in it.devices || device in it.readers } && at >= c.current.createdAt) return null
        return false
    }

    /**
     * §16.2 / §21: DEVICE was on the account's list current at AT, with the 10-minute grace after the
     * list that dropped it, and (§21) the first list of every run of lists that have it counts from its
     * date − 5 minutes. Null = the list current at AT may be missing.
     */
    fun listedAt(c: Chain, device: String, at0: Long): Boolean? {
        var at = at0
        val start = c.lists.withIndex().firstOrNull { (i, l) ->
            device in l.devices && !(i > 0 && device in c.lists[i - 1].devices) && at0 < l.createdAt && l.createdAt <= at0 + Enc.SKEW
        }?.value
        if (start != null) at = start.createdAt
        var i = -1
        c.lists.forEachIndexed { j, l -> if (l.createdAt <= at) i = j }
        if (i < 0) return if (c.lists[0].n == 1) false else null
        val after = c.lists.getOrNull(i + 1)
        if (after != null && after.n != c.lists[i].n + 1) return null
        if (device in c.lists[i].devices) return true
        val prev = c.lists.getOrNull(i - 1)
        if (prev == null || prev.n != c.lists[i].n - 1) return if (c.lists[i].n == 1) false else null
        return device in prev.devices && at <= c.lists[i].createdAt + Enc.GRACE
    }
}
