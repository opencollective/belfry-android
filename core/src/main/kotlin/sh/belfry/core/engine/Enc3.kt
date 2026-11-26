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
import sh.belfry.core.enc.EnvelopeFor
import sh.belfry.core.enc.KeyPerson
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
        // SPEC 19.85: packs to this device, asks and answers of my account (part 5's Enc3Devices)
        val dev = events.filter { it.kind == Enc.PACK || it.kind == Enc.ASK || it.kind == Enc.ANSWER }
        if (dev.isNotEmpty()) onDeviceEvents?.invoke(dev)
        // SPEC 19.83: envelopes for my account; new ones (or new lists a waiting one may need) are judged
        val envFresh = offerEnvelopes(events)
        if (envFresh || (fresh && waitingEnvelopes)) ports.later { if (takeEnvelopes()) onKeys?.invoke() }
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

    internal fun chainOnly(a: String): Chain? = (devices(a) as? Devices.Listed)?.chain

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
        onCheck?.invoke()
    }

    /** SPEC 19.85: part 5's hooks (Enc3Devices): device events seen (8573–8575), and every state check. */
    @Volatile var onDeviceEvents: ((List<Event>) -> Unit)? = null
    @Volatile var onCheck: (() -> Unit)? = null

    /** The cached format is 3 now. */
    val isFormat3: Boolean get() = format3

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
            // SPEC 19.85 (§17.7): the entries for keys never on my lists stay seen across restarts
            notes[N_STRANGE]?.split(',')?.filter { Enc.isKey(it) }?.let { strange.addAll(it) }
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

    /**
     * §17.2 / §25 (the web's neverEncrypted; the lead, 2026-11-26): CONV was never encrypted, known from one COMPLETE read
     * of every envelope and message for me (paged, after AUTH) with every alias resolved. This device's memory alone is
     * not enough: a new device has none, and another device may have encrypted it. False when the read didn't complete,
     * when any alias stays unresolved (it may be this conversation), or when a key of CONV is found. Only then may a
     * people conversation go unencrypted (a guest in it, or over 100 devices).
     */
    suspend fun neverEncrypted(conv: String, pageMs: Long = 8_000): Boolean {
        if (everEncrypted(conv)) return false
        val got = ports.query(Filters.of(kinds = listOf(Enc.KEY, Enc.MSG), tags = mapOf("p" to listOf(me))), pageMs) ?: return false
        if (!got.complete) return false
        offer(got.events)
        takeEnvelopes()
        for (e in got.events) {
            val d = Enc.one(e, "d") ?: continue
            val k = keyByAlias(d) ?: return false // an alias I can't resolve may be this conversation
            if (k.conv == conv) return false
        }
        return !everEncrypted(conv)
    }
    fun firstKeyAt(conv: String): Long? = synchronized(keys) { convs[conv] }

    /** SPEC 19.88: the groups ever seen encrypted here (their ids). */
    fun encGroups(): List<String> = synchronized(keys) { convs.keys.filter { it.startsWith("g:") }.map { it.removePrefix("g:") } }

    /** §17.7: an envelope for my account had an entry for KEY, never on my lists (part 3 reports it). */
    fun strangeEntry(key: String) {
        if (!strange.add(key)) return
        setNote(N_STRANGE, synchronized(strange) { strange.sorted().take(50).joinToString(",") })
        check()
    }

    /** §17.7: the strange entries seen (kept for good, per account). */
    val strangeCount: Int get() = strange.size

    internal fun note(n: String): String? = synchronized(keys) { notes[n] }
    internal fun setNote(n: String, v: String) { synchronized(keys) { notes[n] = v }; persist() }

    /** Every key held (Share hands them on in key packs, SPEC 19.85). */
    fun allKeys(): List<ConvKey> = synchronized(keys) { keys.values.toList() }

    /**
     * SPEC 19.85 (§7.5 "plus the envelopes on the relays"): every envelope for my account read whole now and judged;
     * true when the read completed.
     */
    suspend fun readMyEnvelopes(pageMs: Long = 8_000): Boolean {
        val got = ports.query(Filters.of(kinds = listOf(Enc.KEY), tags = mapOf("p" to listOf(me))), pageMs) ?: return false
        val mine = got.events.filter { it.kind == Enc.KEY }
        if (mine.isNotEmpty()) { ports.addEvents(mine); offerEnvelopes(mine) }
        if (takeEnvelopes()) onKeys?.invoke()
        return got.complete
    }

    /** Sign-out: the keys go with the session; a late write is refused. */
    fun wipe() {
        keyStore?.wipe()
        synchronized(keys) { keys.clear(); byAlias.clear(); convs.clear(); notes.clear() }
        strange.clear()
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


    // ---- people conversations: reading (SPEC 19.83; §5, §6, §17.12, §20, §21) ----------------------

    /** Envelopes (8571) for my account, by id (at most [MAX_ENVELOPES]), and those judged for good. */
    private val envs = LinkedHashMap<String, Event>()
    private val envSeen = HashSet<String>()
    /** alias → a complete `#d` read found envelopes and none has an entry for this device ("can't be read"). */
    private val noEntry = ConcurrentHashMap<String, Boolean>()
    private val dAsked = ConcurrentHashMap<String, Long>()
    /** New keys were taken (or an alias's state changed): the session reads the messages waiting for them again. */
    @Volatile var onKeys: (() -> Unit)? = null
    internal val secret: ByteArray get() = sh.belfry.core.crypto.Hex.decode(device.secretHex)
    internal fun nowS(): Long = ports.nowMs() / 1000

    /** Envelopes among EVENTS kept for [takeEnvelopes]; true when one was new. */
    private fun offerEnvelopes(events: List<Event>): Boolean {
        var fresh = false
        synchronized(envs) {
            for (e in events) {
                if (e.kind != Enc.KEY || !e.hasTag("p", me) || e.tags.any { it.isNotEmpty() && it[0] == "h" } || e.id in envSeen) continue
                if (envs.putIfAbsent(e.id, e) == null) fresh = true
            }
            val over = envs.size - MAX_ENVELOPES
            if (over > 0) envs.keys.take(over).forEach { envs.remove(it) } // the oldest offered go (read again by #d when needed)
        }
        return fresh
    }

    private val waitingEnvelopes: Boolean get() = synchronized(envs) { envs.keys.any { it !in envSeen } }

    /**
     * Envelopes for my account: my entry opened, its key rumor judged (§5, §16.2, §20, §28), held when accepted (declared
     * once, §17.12). A bad envelope or one with no entry for this device is set aside, never blocking (§17.12); a key
     * whose lists aren't here yet waits (its people's lists asked). True when a key was added.
     */
    fun takeEnvelopes(): Boolean {
        val todo = synchronized(envs) { envs.values.filter { it.id !in envSeen } }
        if (todo.isEmpty()) return false
        val sk = secret
        var added = false
        fun done(e: Event) = synchronized(envs) { envSeen.add(e.id); envs.remove(e.id) }
        for (e in todo) {
            val o = try { EncKeys.openPeopleEnvelope(sk, e) } catch (x: Exception) { done(e); continue }
            checkEntries(e)
            if (o == null) { done(e); continue }
            val r = o.rumor
            val K = Enc.one(r, "K")
            if (K == null || Enc.one(e, "d") != EncKeys.alias(K, me)) { done(e); continue }
            val people = Enc.values(r, "p")
            when (EncKeys.acceptKeyRumor(r, me, ::devices, nowS())) {
                sh.belfry.core.enc.Verdict.WAIT -> { want(people); continue } // judged again when the lists come
                sh.belfry.core.enc.Verdict.NO -> { done(e); continue }
                sh.belfry.core.enc.Verdict.OK -> {}
            }
            done(e)
            // mine (§17.8): made by a device on the list the key names for MY account
            val named = r.tags.firstOrNull { it.size >= 3 && it[0] == "list" && it[1] == me }?.get(2)
            val mine = named != null && chainOnly(me)?.listById(named)?.devices?.contains(r.pubkey) == true
            val lists = EncKeys.peopleLists(r.tags.filter { it.size >= 3 && it[0] == "list" }.map { it[1] to it[2] })
            val b = ports.belfryKey()
            val g = ports.groupsKey()
            val service = (b != null && r.pubkey == b) || (!g.isNullOrEmpty() && r.pubkey == g)
            val accounts = people.distinct().sorted()
            if (putKey(ConvKey(K, r.content, peopleConv(accounts, me), r.pubkey, r.createdAt, mine, service, false, lists, accounts, rumor = r))) added = true
        }
        return added
    }

    /** §17.7 + the review's P2.5: an envelope for my account with an entry for a key never on my lists (whole chain only). */
    private fun checkEntries(env: Event) {
        val c = chainOnly(me) ?: return
        if (c.gaps) return
        val map = sh.belfry.core.enc.EncJson.parse(env.content) as? JsonObject ?: return
        val ever = c.lists.flatMap { it.devices + it.readers }.toSet()
        for (k in map.keys) if (Enc.isKey(k) && k !in ever) strangeEntry(k)
    }

    /** What opening one people 8572 gave (§6, §9). */
    sealed interface Opened3 {
        /** Accepted: RUMOR (its id computed) in the people conversation CONV, under KEY. */
        data class Ok(val rumor: Event, val conv: String, val key: ConvKey) : Opened3
        /**
         * Kept and judged again (never marked seen): its key isn't here yet ("Opening") or, after a complete read of its
         * envelopes, none has an entry for this device (UNREADABLE: "Can't be read on this device"); CONV when its key is
         * here (the author's lists are what it waits for).
         */
        data class Wait(val unreadable: Boolean, val conv: String?) : Opened3
        /** Refused for good: dropped (a copy that doesn't open with its key, a signer who isn't the author's device…). */
        data object Drop : Opened3
    }

    /**
     * An 8572 for me (§6): its key by its `d` (my alias of K), taken from the envelopes here or asked by `#d` (once a
     * minute, whatever came live: a forged empty answer never stops it); opened; the seal's signer judged at the outer
     * date (§18.2, §21); the rumor's people equal the key's. A wait is never a drop: a signer on no list of the author
     * is dropped only after a complete read of the author's whole chain on this connection still lists it nowhere.
     */
    suspend fun open(e: Event): Opened3 {
        if (e.kind != Enc.MSG || Enc.one(e, "p") != me || e.tags.any { it.isNotEmpty() && it[0] == "h" }) return Opened3.Drop
        val d = sh.belfry.core.enc.EncMessages.named(e)?.takeIf { Enc.isKey(it) } ?: return Opened3.Drop
        var key = keyByAlias(d)
        if (key == null && takeEnvelopes()) { key = keyByAlias(d); onKeys?.invoke() }
        if (key == null) {
            askEnvelopes(d)
            return Opened3.Wait(noEntry[d] == true, null)
        }
        val o = try { sh.belfry.core.enc.EncMessages.openPeopleMessage(key.secret, e) } catch (x: Exception) { return Opened3.Drop }
        val author = o.rumor.pubkey
        val people = EncKeys.peopleId(key.people.ifEmpty { listOf(me) })
        return when (sh.belfry.core.enc.EncMessages.acceptPeopleMessage(e, o.seal, o.rumor, people, devices(author))) {
            sh.belfry.core.enc.Verdict.OK -> { noteEncrypted(key.conv, key.at); Opened3.Ok(o.rumor, key.conv, key) }
            sh.belfry.core.enc.Verdict.NO -> Opened3.Drop
            sh.belfry.core.enc.Verdict.WAIT -> {
                val c = chainOnly(author)
                if (c != null && !c.gaps && readHere(author) && c.lists.none { o.seal.pubkey in it.devices }) Opened3.Drop
                else { want(listOf(author)); Opened3.Wait(false, key.conv) }
            }
        }
    }

    /** ALIAS's envelopes asked by `#p` + `#d` (at most once a minute): each one tried (a junk one never blocks), capped. */
    private fun askEnvelopes(alias: String) {
        val now = ports.nowMs()
        if (dAsked[alias]?.let { now - it < 60_000 } == true) return
        dAsked[alias] = now
        if (ports.belfryKey() == null) return
        ports.later {
            val got = ports.query(Filters.of(kinds = listOf(Enc.KEY), limit = MAX_PER_ALIAS, tags = mapOf("p" to listOf(me), "d" to listOf(alias))), 5_000) ?: return@later
            val mineNow = got.events.filter { it.kind == Enc.KEY && Enc.one(it, "d") == alias }.take(MAX_PER_ALIAS)
            if (mineNow.isNotEmpty()) ports.addEvents(mineNow)
            offerEnvelopes(mineNow)
            // N5: "can't be read" only when envelopes were found and none has an entry for this device
            val sk = secret
            val entry = mineNow.any { env -> try { EncKeys.openPeopleEnvelope(sk, env) != null } catch (x: Exception) { false } }
            val was = noEntry[alias] == true
            val unreadable = got.complete && mineNow.isNotEmpty() && !entry
            if (unreadable) noEntry[alias] = true else noEntry.remove(alias)
            if (takeEnvelopes() || was != unreadable) onKeys?.invoke()
        }
    }

    // ---- people conversations: writing (SPEC 19.83; §5, §6, §16.1, §17.5, §17.8, §18.1) -----------

    /** What one people message becomes: its key's envelopes (first) and one 8572 per person, under the key KEY. */
    data class Encrypted(val envelopes: List<Event>, val messages: List<Event>, val key: String, val missing: List<String>)

    private val encrypting = Any()

    /**
     * RUMOR encrypted for PLAN ([peopleReady]'s): the key is the newest of mine made for exactly the plan's lists, under
     * 30 days, not future-dated, never a service key ([EncKeys.pickKey]); else a new one, kept (with what makes its
     * envelopes again) BEFORE anything is queued, its envelopes for every device of every person (readers too). A key
     * whose envelopes aren't confirmed and aren't live in the outbox (LIVE) gets them again with fresh dates (same K).
     * Then one copy per person who has a device (mine silent; none for me when not SELF), each sealed by this DEVICE.
     * Throws [sh.belfry.core.enc.EncException] (too long) before any key is made.
     */
    fun encryptPeople(rumor: Event, plan: EncPlan.People, self: Boolean = true, live: (String) -> Boolean = { false }): Encrypted = synchronized(encrypting) {
        val r = Enc.withId(rumor)
        if (sh.belfry.core.enc.EncJson.rumorJson(r).toByteArray(Charsets.UTF_8).size > Enc.MAX_RUMOR) throw sh.belfry.core.enc.EncException("the message is too long to encrypt")
        val accounts = plan.per.map { it.account }.distinct().sorted()
        if (EncKeys.peopleOf(r) != EncKeys.peopleId(accounts)) throw sh.belfry.core.enc.EncException("the message is not for these people")
        val conv = peopleConv(accounts, me)
        val now = nowS()
        val sk = secret
        val envelopes = ArrayList<Event>()
        var ck: ConvKey? = EncKeys.pickKey(held(), conv, plan.lists, now)?.let { this.key(it.pub) }
        if (ck == null) {
            val kp = EncKeys.newKey()
            val kr = EncKeys.keyRumor(device.pubkey, kp.secret, plan.per.map { KeyPerson(it.account, it.list) }, now)
            val per = plan.per.filter { it.devices.isNotEmpty() }.map { EnvelopeFor(it.account, it.devices) }
            val made = EncKeys.peopleEnvelopes(sk, kr, per)
            val k = ConvKey(kp.pub, kp.secret, conv, device.pubkey, kr.createdAt, mine = true, lists = plan.lists, people = accounts, unconfirmed = made.map { it.id }, made = MadeKey(kr, per))
            putKey(k)
            ck = k
            envelopes += made
        } else if (ck.unconfirmed.any { !live(it) }) {
            envelopes += rebuildEnvelopes(ck.pub)
        }
        val K = ck.pub
        val to = accounts.filter { it !in plan.missing && (self || it != me) }
        val messages = sh.belfry.core.enc.EncMessages.peopleMessage(sk, r, K, to, me)
        noteEncrypted(conv, ck.at)
        Encrypted(envelopes, messages, K, plan.missing)
    }

    /** The envelopes of key K not confirmed out yet (event ids). */
    fun unconfirmed(K: String): List<String> = key(K)?.unconfirmed ?: emptyList()

    /** Key K's envelopes made again with fresh dates (same K, same receivers) and kept as its unconfirmed ones. */
    fun rebuildEnvelopes(K: String): List<Event> {
        val k = key(K) ?: return emptyList()
        val m = k.made ?: return emptyList()
        val fresh = EncKeys.peopleEnvelopes(secret, m.rumor, m.per)
        replaceKey(k.copy(unconfirmed = fresh.map { it.id }))
        return fresh
    }

    /** Whether a people 8572 under a key may go now ([envelopesFirst]). */
    sealed interface EnvGate {
        data object Go : EnvGate
        /** Not yet: its key's envelopes go first. DROP: outbox items to take out (refused ones), QUEUE: envelopes made again. */
        data class Wait(val drop: List<String>, val queue: List<Event>) : EnvGate
        /** Its key's envelopes were refused too often in this run: the copy says Not sent. */
        data object Refuse : EnvGate
    }

    private val envRebuilds = ConcurrentHashMap<String, Int>()

    /**
     * §5 "publishes its envelopes before the first message that uses it" (SPEC 19.83): a copy under K goes only once K's
     * envelopes are confirmed out. Those not queued any more in OUTBOX (refused, lost) are made again with fresh dates
     * (same K) and handed back to queue; the copy waits. At most [MAX_ENV_REBUILDS] times a run, then Refuse.
     */
    fun envelopesFirst(K: String, outbox: List<sh.belfry.core.outbox.OutboxItem>): EnvGate {
        val pending = unconfirmed(K)
        if (pending.isEmpty()) return EnvGate.Go
        val live = outbox.filter { it.status == sh.belfry.core.outbox.OutboxStatus.QUEUED }.mapNotNull { it.signed?.id }.toSet()
        if (pending.all { it in live }) return EnvGate.Wait(emptyList(), emptyList())
        val n = (envRebuilds[K] ?: 0) + 1
        if (n > MAX_ENV_REBUILDS) return EnvGate.Refuse
        envRebuilds[K] = n
        val drop = outbox.filter { it.kind == Enc.KEY && it.signed?.id in pending }.map { it.localId }
        return EnvGate.Wait(drop, rebuildEnvelopes(K))
    }

    /** The outbox says envelope ID is out. */
    fun envelopeSent(id: String) {
        val k = synchronized(keys) { keys.values.firstOrNull { id in it.unconfirmed } } ?: return
        replaceKey(k.copy(unconfirmed = k.unconfirmed - id))
    }

    internal fun replaceKey(k: ConvKey) {
        synchronized(keys) { if (k.pub in keys) keys[k.pub] = k else return }
        persist()
    }

    companion object {
        const val MAX_ENVELOPES = 2_000
        /** Envelopes tried under one alias at most (§17.12: junk never blocks, and never without end). */
        const val MAX_PER_ALIAS = 50
        /** A key's envelopes made again at most this often in a run before its copies say Not sent. */
        const val MAX_ENV_REBUILDS = 5
        const val LISTED_WITHIN_MS = 60_000L
        const val READS_D = "belfry:device:reads"
        private const val N_READS = "said-reads"
        private const val N_STRANGE = "strange-entries"

        /** A people conversation's id here (`n:` + the others sorted; `n:<me>` alone) from its accounts. */
        fun peopleConv(accounts: Collection<String>, me: String): String {
            val others = accounts.filter { it != me }.toSortedSet()
            return "n:" + (if (others.isEmpty()) me else others.joinToString(","))
        }

        fun groupConv(id: String): String = "g:$id"
    }
}
