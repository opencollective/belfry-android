package sh.belfry.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import sh.belfry.core.enc.Chain
import sh.belfry.core.enc.Devices
import sh.belfry.core.enc.Enc
import sh.belfry.core.enc.EncKeys
import sh.belfry.core.enc.EncLists
import sh.belfry.core.enc.EncPlan
import sh.belfry.core.enc.EncRules
import sh.belfry.core.enc.EncText
import sh.belfry.core.enc.EncWrite
import sh.belfry.core.enc.HeldKey
import sh.belfry.core.enc.HoldReason
import sh.belfry.core.enc.ListChange
import sh.belfry.core.enc.PeopleLimit
import sh.belfry.core.enc.PersonDevices
import sh.belfry.core.net.Filters
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Tag
import sh.belfry.core.sealed.SealedEngine
import java.util.concurrent.ConcurrentHashMap

/** What the runtime needs of its session (a seam: tests give fakes). Never handed a secret to log. */
interface Enc3Ports {
    /** belfry.json `nostr.pubkey`: the only key whose 8570s count. */
    fun belfryKey(): String?
    /** belfry.json `groups_key.pubkey` (or nostr.json `_groups`). */
    fun groupsKey(): String?
    /** The community relay's connection now (a new number after each reconnect); null: not connected. */
    fun connection(): Int?
    /** FILTER read on the community relay, paged to the end; null: no answer. Complete = known whole (after AUTH, same socket). */
    suspend fun query(filter: JsonObject, pageMs: Long): SealedEngine.Answer?
    /** Into the session's log and store (regular events, never pruned). */
    fun addEvents(events: List<Event>)
    /** How many chat sessions my `/me/sessions` lists; null: no answer. */
    suspend fun chatSessions(): Int?
    /** Queued in the outbox, signed at sending by this DEVICE's key (never the signer), for the community relay. */
    suspend fun enqueueByDevice(kind: Int, tags: List<Tag>, content: String)
    /** The live subscription's filters changed: send it again. */
    fun resubscribe()
    /** Background work (cancelled at sign-out). */
    fun later(block: suspend () -> Unit)
    fun nowMs(): Long
}

/** Is this device on its account's current list (docs/encrypted.md §3, §17.4)? */
enum class Here {
    /** On the current list. */
    YES,
    /** Not yet, less than 60 s after this runtime started: nothing is said, senders hold. */
    NOT_YET,
    /** Not on it 60 s on: "This device isn't set up for encrypted messages yet · Try again"; senders hold. */
    NOT_LISTED,
    /** §17.4: twenty devices are listed and this is the twenty-first: "Remove one"; senders hold. */
    FULL,
}

/** One account's list warnings for part 5 (they never go by themselves: §17.7, §18.5). */
data class ListWarnings(val fork: Boolean, val invalidAbove: Boolean, val gaps: Boolean) {
    val holds: Boolean get() = fork || invalidAbove
}

/** What the app shows of this device's format-3 set-up (SPEC 19.82; the final screens are part 4's). */
data class Enc3State(
    val here: Here = Here.NOT_YET,
    /** This belfry writes format 3 (the device's cache): only then is anything said. */
    val format3: Boolean = false,
    /** My own lists forked or a newer one isn't valid (the warning of §17.7; words are part 5's). */
    val myWarning: Boolean = false,
    /** Envelope entries for keys never on my lists (§17.7; counted by part 3). */
    val strangeEntries: Int = 0,
) {
    /** The line where the app says it (Messages), or null. */
    val line: String? get() = if (!format3) null else when (here) {
        Here.NOT_LISTED -> EncText.NOT_SET_UP
        Here.FULL -> EncText.FULL
        else -> null
    }

    /** The line has a Try again (not when twenty devices are listed: removing one is the way). */
    val tryAgain: Boolean get() = format3 && here == Here.NOT_LISTED
}

/**
 * Encrypted conversations, format 3, on this device (SPEC 19.82; docs/encrypted.md; a port of the
 * list and key-store parts of the web's `src/core/enc/runtime.ts`, branch enc-v3): the device lists
 * (8570) of my account, of the people I talk to and of anyone a key or seal waits on; the three-way
 * [Devices] answer; this device on its own list; the conversation keys this device holds; the
 * device's `belfry:device:reads` marker. Opening and encrypting are part 3.
 *
 * Only for a signed-in member's session with a device key: never in the demo, never for a guest's
 * session (§25). DEVICE is the session's NIP-46 client key; it signs locally, never through the signer.
 */
class Enc3(
    val device: LocalSigner,
    val me: String,
    private val ports: Enc3Ports,
    private val keyStore: EncKeyStore?,
) {
    private val startedMs = ports.nowMs()

    // ---- device lists -------------------------------------------------------------------------------

    private val lists = ConcurrentHashMap<String, Event>() // id → a kind 8570 (any signer: chainOf keeps only the belfry's)
    @Volatile private var listsVersion = 0
    private val chains = HashMap<String, Chain?>()
    private var chainsVersion = -1
    private val partners = java.util.Collections.synchronizedSet(sortedSetOf<String>())
    private val wanted = java.util.Collections.synchronizedSet(sortedSetOf<String>())
    private val wantAsked = ConcurrentHashMap<String, Long>()
    /** The review's P2.5: what is known of a list counts only after one complete read of it on this connection. */
    private val readOn = ConcurrentHashMap<String, Int>()
    @Volatile private var chatSessions: Int? = null

    /** Lists among EVENTS (the store at start, the relay's events live): true when one was new. */
    fun offer(events: List<Event>): Boolean {
        var fresh = false
        for (e in events) if (e.kind == Enc.LIST && lists.putIfAbsent(e.id, e) == null) fresh = true
        if (fresh) listsVersion++
        return fresh
    }

    /** ACCOUNT's lists as a chain (verified once per change of the lists), or null when none is known. */
    fun chain(account: String): Chain? {
        val b = ports.belfryKey() ?: return null
        synchronized(chains) {
            val v = listsVersion
            if (v != chainsVersion) { chains.clear(); chainsVersion = v }
            return chains.getOrPut(account) { EncLists.chainOf(lists.values.filter { e -> e.tags.any { it.size >= 2 && it[0] == "p" && it[1] == account } }, b, account) }
        }
    }

    /**
     * §17.1 + §20, three answers never two: SERVICE only for exactly the belfry key or the groups key of
     * belfry.json; LISTED with the chain; UNKNOWN otherwise (wait, read once more: never the account key).
     */
    fun devices(account: String): Devices {
        val b = ports.belfryKey()
        val g = ports.groupsKey()
        if (!b.isNullOrEmpty() && account == b) return Devices.Service
        if (!g.isNullOrEmpty() && account == g) return Devices.Service
        if (b.isNullOrEmpty()) return Devices.Unknown
        return Devices.of(account, b, g, chain(account))
    }

    private fun chainOnly(a: String): Chain? = (devices(a) as? Devices.Listed)?.chain

    /** §17.7 / §18.5 for part 5: forks, a newer signed list that isn't valid (both hold), gaps (an older list missing: never blocks). */
    fun warnings(account: String): ListWarnings? = chainOnly(account)?.let { ListWarnings(it.fork, it.invalidAbove, it.gaps) }

    /** §17.7 for part 5: what each list of ACCOUNT added and removed against the one before. */
    fun changes(account: String): List<ListChange> = chainOnly(account)?.let { EncLists.changes(it) } ?: emptyList()

    /** The live subscription's filter: my lists, my partners' and the wanted ones' (kept on it, never with `since`: lists are kept for ever). */
    fun filters(): List<JsonObject> {
        val b = ports.belfryKey() ?: return emptyList()
        val who = (listOf(me) + synchronized(partners) { partners.toList() } + synchronized(wanted) { wanted.toList() }).distinct()
        return listOf(Filters.of(kinds = listOf(Enc.LIST), authors = listOf(b), limit = 300, tags = mapOf("p" to who)))
    }

    /** The people I have conversations with (their lists on the live subscription). True when it changed (then resubscribed). */
    fun setPartners(people: Collection<String>): Boolean {
        val next = people.filter { Enc.isKey(it) && it != me }.toSortedSet()
        val changed = synchronized(partners) { if (partners == next) false else { partners.clear(); partners.addAll(next); true } }
        if (changed) ports.resubscribe()
        return changed
    }

    /** People whose lists something here waits on (a key or a seal): on the live subscription, and read now (at most every 30 s each). */
    fun want(accounts: Collection<String>) {
        val keys = accounts.filter { Enc.isKey(it) }
        val fresh = keys.filter { synchronized(partners) { it !in partners } && wanted.add(it) }
        if (fresh.isNotEmpty()) ports.resubscribe()
        val now = ports.nowMs()
        val ask = keys.filter { k -> wantAsked[k]?.let { now - it <= 30_000 } != true }
        if (ask.isEmpty() || ports.belfryKey() == null) return
        ask.forEach { wantAsked[it] = now }
        ports.later { readLists(ask, 5_000) }
    }

    /** ACCOUNTS' lists read whole now; true when the read completed (they count as known on this connection). */
    suspend fun readLists(accounts: Collection<String>, pageMs: Long): Boolean {
        val b = ports.belfryKey() ?: return false
        val list = accounts.filter { Enc.isKey(it) }.distinct()
        if (list.isEmpty()) return true
        val conn = ports.connection() ?: return false
        val got = ports.query(Filters.of(kinds = listOf(Enc.LIST), authors = listOf(b), tags = mapOf("p" to list)), pageMs) ?: return false
        if (got.events.isNotEmpty()) { ports.addEvents(got.events); offer(got.events) }
        if (!got.complete) return false
        list.forEach { readOn[it] = conn }
        check()
        return true
    }

    /** ACCOUNT's lists were read whole on the connection there is now. */
    fun readHere(account: String): Boolean = ports.connection()?.let { readOn[account] == it } == true

    /** An old seal or key needs ACCOUNT's whole chain (gaps): it is read on demand, then given. */
    suspend fun wholeChain(account: String, pageMs: Long = 8_000): Chain? {
        if (chainOnly(account)?.gaps != false || !readHere(account)) readLists(listOf(account), pageMs)
        return chainOnly(account)
    }

    // ---- this device on my list (§3, §17.4) ---------------------------------------------------------

    /** Where this device stands on my account's current list now. */
    fun here(): Here {
        val c = chainOnly(me)
        if (c != null && device.pubkey in c.current.devices) return Here.YES
        val n = chatSessions
        if (c != null && c.current.devices.size >= Enc.MAX_LIST && n != null && n >= Enc.MAX_LIST) return Here.FULL
        return if (ports.nowMs() - startedMs < LISTED_WITHIN_MS) Here.NOT_YET else Here.NOT_LISTED
    }

    private val _state = MutableStateFlow(Enc3State())
    val state: StateFlow<Enc3State> = _state
    @Volatile private var format3 = false

    /** The device's cached format is (now) 3: the set-up line may show. */
    fun setFormat3(on: Boolean) { if (format3 != on) { format3 = on; check() } }

    /** The state again; this device's reads marker once it is on my list (§12). */
    fun check() {
        val h = here()
        val w = warnings(me)
        _state.value = Enc3State(h, format3, w?.holds == true, strange.size)
        if (h == Here.YES) sayReadsOnce()
    }

    /** The periodic look (the session calls it): my sessions counted when twenty devices are listed without this one. */
    suspend fun tick() {
        if (chainOnly(me) == null || !readHere(me)) readLists(listOf(me), 5_000)
        val c = chainOnly(me)
        if (c != null && device.pubkey !in c.current.devices && c.current.devices.size >= Enc.MAX_LIST) chatSessions = ports.chatSessions() ?: chatSessions
        check()
    }

    /** "Try again": my list read again now (and my sessions), then the state. */
    suspend fun tryAgain() {
        chatSessions = ports.chatSessions() ?: chatSessions
        readLists(listOf(me), 5_000)
        check()
    }

    // ---- the device's "reads" marker (§12) ---------------------------------------------------------

    private val saying = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Once per device: kind 30078 d=belfry:device:reads, ["v","3"], signed by the DEVICE key, through the outbox (community relay). */
    private fun sayReadsOnce() {
        if (note(N_READS) == device.pubkey || !saying.compareAndSet(false, true)) return
        ports.later {
            try {
                if (note(N_READS) != device.pubkey) {
                    ports.enqueueByDevice(30078, listOf(listOf("d", READS_D), listOf("v", "3")), "")
                    setNote(N_READS, device.pubkey)
                }
            } finally { saying.set(false) }
        }
    }

    // ---- the key store (§5, §16, §17.12) -----------------------------------------------------------

    private val keys = LinkedHashMap<String, ConvKey>() // K → key
    private val byAlias = HashMap<String, String>() // alias(K, me) → K (people keys)
    private val convs = HashMap<String, Long>() // a conversation ever seen encrypted → its first key's date
    private val notes = HashMap<String, String>()
    private val strange = java.util.Collections.synchronizedSet(HashSet<String>())

    init {
        val l = keyStore?.load()
        if (l != null) synchronized(keys) {
            for (k in l.keys) hold(k)
            convs.putAll(l.convs)
            notes.putAll(l.notes)
        }
    }

    private fun hold(k: ConvKey): Boolean {
        if (k.pub in keys) return false // §17.12: the first accepted declaration of a K fixes its conversation
        keys[k.pub] = k
        if (!k.group) byAlias[EncKeys.alias(k.pub, me)] = k.pub
        val was = convs[k.conv]
        if (was == null || k.at < was) convs[k.conv] = k.at
        return true
    }

    private fun persist() {
        val s = keyStore ?: return
        val (k, c, n) = synchronized(keys) { Triple(keys.values.toList(), HashMap(convs), HashMap(notes)) }
        s.save(k, c, n)
    }

    /** Part 3: a key accepted (or made). False when its K was declared before: the first stays, this one is ignored. */
    fun putKey(k: ConvKey): Boolean {
        val added = synchronized(keys) { hold(k) }
        if (added) persist()
        return added
    }

    fun key(K: String): ConvKey? = synchronized(keys) { keys[K] }

    /** The people key named by an 8571 / 8572 `d` alias for my account. */
    fun keyByAlias(alias: String): ConvKey? = synchronized(keys) { byAlias[alias]?.let { keys[it] } }

    /** CONV's keys, newest first. */
    fun keysOf(conv: String): List<ConvKey> = synchronized(keys) { keys.values.filter { it.conv == conv }.sortedByDescending { it.at } }

    /** Every key for [EncKeys.pickKey]. */
    fun held(): List<HeldKey> = synchronized(keys) { keys.values.map { it.held() } }

    val keyCount: Int get() = synchronized(keys) { keys.size }

    /** §17.2 / §26: CONV was seen encrypted (a key of it, or a message under one) at AT: written encrypted from now on, whatever the format. */
    fun noteEncrypted(conv: String, at: Long) {
        val changed = synchronized(keys) { val was = convs[conv]; if (was == null || at < was) { convs[conv] = at; true } else false }
        if (changed) persist()
    }

    fun everEncrypted(conv: String): Boolean = synchronized(keys) { conv in convs }
    fun firstKeyAt(conv: String): Long? = synchronized(keys) { convs[conv] }

    /** §17.7: an envelope for my account had an entry for KEY, never on my lists (part 3 reports it). */
    fun strangeEntry(key: String) { if (strange.add(key)) check() }

    private fun note(n: String): String? = synchronized(keys) { notes[n] }
    private fun setNote(n: String, v: String) { synchronized(keys) { notes[n] = v }; persist() }

    /** Sign-out: the keys go with the session; a late write is refused. */
    fun wipe() {
        keyStore?.wipe()
        synchronized(keys) { keys.clear(); byAlias.clear(); convs.clear(); notes.clear() }
    }

    // ---- before a send: the checks that hold (part 3 / part 7 encrypt) -----------------------------

    /**
     * A people conversation's write on format 3, checked (the web's `encryptFor` before the key): the lists of
     * ACCOUNTS (me added) read whole on this connection, this device on my list, every person's list known and
     * neither forked nor above an invalid one, §17.5 (no device), §4 + §17.2 (over 100 devices: Clear only when
     * never encrypted). V3 carries the plan.
     */
    suspend fun peopleReady(conv: String, accounts: Collection<String>, pageMs: Long = 3_000): EncWrite {
        val all = (accounts + me).filter { Enc.isKey(it) }.toSortedSet().toList()
        val toRead = all.filter { devices(it) != Devices.Service && (!readHere(it) || devices(it) == Devices.Unknown) }
        if (toRead.isNotEmpty() && !readLists(toRead, pageMs)) return EncWrite.Hold(HoldReason.NO_LIST, toRead.firstOrNull { devices(it) == Devices.Unknown })
        when (here()) {
            Here.YES -> {}
            Here.FULL -> return EncWrite.Hold(HoldReason.FULL)
            else -> return EncWrite.Hold(HoldReason.NOT_LISTED)
        }
        val per = ArrayList<PersonDevices>()
        for (a in all) {
            when (val d = devices(a)) {
                Devices.Unknown -> { want(listOf(a)); return EncWrite.Hold(HoldReason.NO_LIST, a) } // §17.1: never decided by absence
                Devices.Service -> per += PersonDevices(a, null, listOf(a))
                is Devices.Listed -> {
                    if (d.chain.fork || d.chain.invalidAbove) return EncWrite.Hold(HoldReason.FORK, a)
                    per += PersonDevices(a, d.chain.current.id, d.chain.current.receivers)
                }
            }
        }
        val missing = per.filter { it.account != me && it.devices.isEmpty() }.map { it.account }
        val others = per.filter { it.account != me }
        if (others.isNotEmpty() && missing.size == others.size) return EncWrite.Hold(HoldReason.NO_DEVICE, missing[0])
        val total = per.sumOf { it.devices.size }
        when (EncRules.peopleLimit(total, everEncrypted(conv))) {
            PeopleLimit.CLEAR -> return EncWrite.Clear
            PeopleLimit.HELD -> return EncWrite.Hold(HoldReason.TOO_MANY)
            PeopleLimit.ENCRYPT -> {}
        }
        val lists = EncKeys.peopleLists(per.mapNotNull { p -> p.list?.let { p.account to it } })
        return EncWrite.V3(EncPlan.People(per, missing, lists))
    }

    /**
     * A private group's write on format 3, checked (the web's `wrapForGroup` before the key): MEMBERS from the
     * group's member list (39002; part 7 reads it on this connection first), their lists read whole; a member
     * with no list after a second read is skipped for this message (§23 end); a guest member gets no entry (§25);
     * forks hold; over 1000 devices refuses (§4).
     */
    suspend fun groupReady(members: Collection<String>, isGuest: (String) -> Boolean, pageMs: Long = 5_000): EncWrite {
        if (me !in members) return EncWrite.Hold(HoldReason.GROUP_UNKNOWN)
        val people = members.filter { Enc.isKey(it) }.toSortedSet().toList()
        val toRead = people.filter { devices(it) != Devices.Service && (!readHere(it) || devices(it) == Devices.Unknown) }
        if (toRead.isNotEmpty() && !readLists(toRead, pageMs)) return EncWrite.Hold(HoldReason.NO_LIST)
        val none = people.filter { devices(it) == Devices.Unknown }
        if (none.isNotEmpty() && !readLists(none, pageMs)) return EncWrite.Hold(HoldReason.NO_LIST)
        when (here()) {
            Here.YES -> {}
            Here.FULL -> return EncWrite.Hold(HoldReason.FULL)
            else -> return EncWrite.Hold(HoldReason.NOT_LISTED)
        }
        val devices = ArrayList<String>()
        val lines = ArrayList<Pair<String, String>>()
        for (a in people) {
            when (val d = devices(a)) {
                Devices.Unknown -> continue
                Devices.Service -> devices += a
                is Devices.Listed -> {
                    if (isGuest(a)) continue
                    if (d.chain.fork || d.chain.invalidAbove) return EncWrite.Hold(HoldReason.FORK, a)
                    devices += d.chain.current.receivers
                    lines += a to d.chain.current.id
                }
            }
        }
        if (devices.size > Enc.MAX_GROUP_DEVICES) return EncWrite.Hold(HoldReason.TOO_MANY)
        return EncWrite.V3(EncPlan.Group(devices, lines, EncKeys.deviceSet(devices)))
    }

    companion object {
        const val LISTED_WITHIN_MS = 60_000L
        const val READS_D = "belfry:device:reads"
        private const val N_READS = "said-reads"

        /** A people conversation's id here (`n:` + the others sorted; `n:<me>` alone) from its accounts. */
        fun peopleConv(accounts: Collection<String>, me: String): String {
            val others = accounts.filter { it != me }.toSortedSet()
            return "n:" + (if (others.isEmpty()) me else others.joinToString(","))
        }

        fun groupConv(id: String): String = "g:$id"
    }
}
