package sh.belfry.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Hex
import sh.belfry.core.enc.AnswerTo
import sh.belfry.core.enc.AnswerWord
import sh.belfry.core.enc.Asked
import sh.belfry.core.enc.Chain
import sh.belfry.core.enc.DeviceCard
import sh.belfry.core.enc.DeviceName
import sh.belfry.core.enc.Devices
import sh.belfry.core.enc.Enc
import sh.belfry.core.enc.Enc3DeviceState
import sh.belfry.core.enc.EncDevice
import sh.belfry.core.enc.EncDeviceText
import sh.belfry.core.enc.EncKeys
import sh.belfry.core.enc.EncLists
import sh.belfry.core.enc.EncPacks
import sh.belfry.core.enc.FirstRow
import sh.belfry.core.enc.KeyPerson
import sh.belfry.core.enc.PackKey
import sh.belfry.core.enc.RemovedAnswer
import sh.belfry.core.enc.RemovedHere
import sh.belfry.core.net.SessionInfo
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str
import java.util.concurrent.ConcurrentHashMap

/** What part 5 needs of its session (a seam: tests give fakes). Never handed a secret to log. */
interface Enc3DevicePorts {
    /** My `/me/sessions` (the devices' own names, their bunker keys); null: no answer. */
    suspend fun sessions(): List<SessionInfo>?
    /** `POST /me/sessions/revoke` for the session of DEVICE (by BUNKER when known, else by client key), `by` = BY. Throws [ActionException]. */
    suspend fun revoke(device: String, bunker: String?, by: String)
    /** `POST /agents {pubkey, reads}` (my agent's access, §11). Throws [ActionException]. */
    suspend fun agentReads(agent: String, reads: Boolean)
    /** An event signed already (by this device or a one-time key), through the outbox to the community relay. */
    suspend fun enqueueSigned(e: Event)
    /** §16.6: `GET /api/v1/sessions/removed`, NIP-98 signed by this DEVICE's key; null: no answer (offline, 404, an error). */
    suspend fun removedAnswer(): RemovedAnswer?
    /** Version-1 letterbox secrets this device holds (lb → secret), for Share. */
    fun v1Secrets(): Map<String, String>
    /** Version-1 letterbox secrets that came in a key pack (§16.3): to the version-1 side, never replacing one. */
    fun takeV1(secrets: Map<String, String>)
    /** Messages for me this device can't open (format 3 without its key here, version 1 never had): for asking again. */
    fun missing(): Int
    /** The first read that said this belfry writes format 3 (Unix seconds, 0 unknown). */
    fun since3(): Long
    fun later(block: suspend () -> Unit)
}

/**
 * Format 3, part 5 on this device (SPEC 19.85; docs/encrypted.md §7, §11, §16.3, §16.6, §16.12, §17.6, §17.7, §18.3,
 * §20, §26; the web's `runtime.ts` on enc-v3): the Ask (8574), key packs (8573) taken and made, answers (8575), the
 * card on my other devices, the warnings, the first screen, the removed device. Its notes are kept in the account's
 * key store beside the keys ([Enc3.note]), wiped with it.
 *
 * Everything this device sends here is signed by the DEVICE key ([Enc3.device]), never by the signer: the ask, the
 * answers and the seals of the packs (a pack's outer event is signed by a one-time key).
 */
class Enc3Devices(private val enc3: Enc3, private val ports: Enc3DevicePorts, private val clockMs: () -> Long = { System.currentTimeMillis() }) {
    private val me = enc3.me
    private val device = enc3.device
    private val secret: ByteArray get() = Hex.decode(device.secretHex)
    private fun nowS(): Long = clockMs() / 1000

    private val asks = ConcurrentHashMap<String, Event>()
    private val answers = ConcurrentHashMap<String, Event>()
    private val packs = ConcurrentHashMap<String, Event>()
    private val packSeen = java.util.Collections.synchronizedSet(HashSet<String>())

    private val _state = MutableStateFlow(Enc3DeviceState())
    val state: StateFlow<Enc3DeviceState> = _state
    private val _removed = MutableStateFlow<RemovedHere?>(null)
    /** §16.6 + §26: set ONLY when the belfry said removed AND this device is off my current list (read whole here). */
    val removed: StateFlow<RemovedHere?> = _removed

    /** Cards shown on this device in this run: one answered by another device leaves a line ("… shared … with …"). */
    private val shown = java.util.Collections.synchronizedSet(HashSet<String>())
    private val notices = java.util.Collections.synchronizedList(ArrayList<String>())
    private val firstScreenSeen: Boolean
    @Volatile private var firstScreenDone = false

    init {
        // the first screen once per device: a device that had seen its first list in an earlier run is past it (as the web)
        firstScreenSeen = note(N_FIRST_SCREEN) == "1" || (note(N_FIRST_N)?.toIntOrNull() ?: 0) > 0
    }

    private fun note(n: String) = enc3.note(n)
    private fun setNote(n: String, v: String) = enc3.setNote(n, v)

    // ---- what comes in ------------------------------------------------------------------------------

    /** Asks and answers of my account, packs to this device (the store at start, the relay live). */
    fun offer(events: List<Event>) {
        var fresh = false
        var pack = false
        for (e in events) {
            if (!e.hasTag("p", me)) continue
            when (e.kind) {
                Enc.ASK -> if (asks.putIfAbsent(e.id, e) == null) fresh = true
                Enc.ANSWER -> if (answers.putIfAbsent(e.id, e) == null) fresh = true
                Enc.PACK -> if (e.hasTag("d", device.pubkey) && packs.putIfAbsent(e.id, e) == null) pack = true
            }
        }
        if (pack) ports.later { takePacks() }
        if (fresh) refresh()
    }

    private fun chain(): Chain? = (enc3.devices(me) as? Devices.Listed)?.chain

    /**
     * §7.5 + §16.3 + §17.6 + §18.3: key packs to this device, opened only when my lists are whole (gaps: my whole chain
     * read first; refused while they can't be) and only from a device on my current list listed before this one
     * ([EncPacks.openPack]). People and group keys are kept to read with (never adopted for sending: `mine` false), a
     * key held already is never replaced; version-1 secrets go to the version-1 side. True when a key came.
     */
    suspend fun takePacks(): Boolean {
        val todo = packs.values.filter { it.id !in packSeen }
        if (todo.isEmpty()) return false
        var c = chain()
        if (c == null || c.gaps) c = enc3.wholeChain(me)
        if (c == null || c.gaps) return false // judged again when my lists are whole
        var added = false
        for (p in todo) {
            packSeen.add(p.id)
            val o = try { EncPacks.openPack(secret, me, p, c) } catch (e: Exception) { null } ?: continue
            val v1 = LinkedHashMap<String, String>()
            for (k in o.keys) when (k) {
                is PackKey.People -> {
                    val r = k.rumor
                    val K = Enc.one(r, "K") ?: continue
                    val people = Enc.values(r, "p").distinct().sorted()
                    val lists = EncKeys.peopleLists(r.tags.filter { it.size >= 3 && it[0] == "list" }.map { it[1] to it[2] })
                    if (enc3.putKey(ConvKey(K, r.content, Enc3.peopleConv(people, me), r.pubkey, r.createdAt, mine = false, lists = lists, people = people, rumor = r))) added = true
                }
                is PackKey.Group -> if (enc3.putKey(ConvKey(k.pub, k.secret, Enc3.groupConv(k.group), o.from, p.createdAt, mine = false, group = true))) added = true
                is PackKey.V1 -> v1[k.lb] = k.secret
            }
            if (v1.isNotEmpty()) try { ports.takeV1(v1) } catch (e: Exception) { }
            setNote(N_SHARED_BY, "${o.from}:${p.createdAt}")
            added = true
        }
        if (added) enc3.onKeys?.invoke()
        refresh()
        return added
    }

    // ---- a new device: Ask (§7.3, §16.12) ----------------------------------------------------------

    fun asked(): Asked? {
        val o = parseJsonOrNull(note(N_ASKED) ?: return null) as? JsonObject ?: return null
        return Asked(o["at"].long() ?: return null, o["devices"].arr()?.mapNotNull { it.str() } ?: emptyList(), o.s("id") ?: "")
    }

    /**
     * "Ask for them": one 8574 signed by this device, `["p", account]`, through the outbox; again only after 24 h or when
     * another device of mine appeared on the list since. False when not asked now (too soon, not on my list).
     */
    suspend fun ask(force: Boolean = false): Boolean {
        val c = chain()
        if (c == null || device.pubkey !in c.current.devices) return false
        val now = nowS()
        if (!EncDevice.mayAsk(asked(), now, c.current.devices) && !force) return false
        val e = EncPacks.ask(secret, me, now)
        ports.enqueueSigned(e)
        setNote(N_ASKED, buildJsonObject {
            put("at", JsonPrimitive(now)); put("id", JsonPrimitive(e.id))
            put("devices", buildJsonArray { c.current.devices.forEach { add(JsonPrimitive(it)) } })
        }.toString())
        refresh()
        return true
    }

    /** The periodic look: asked before and still missing messages → asked again after 24 h or on a new device of mine. */
    suspend fun tick() {
        val a = asked() ?: return
        val c = chain() ?: return
        if (ports.missing() > 0 && EncDevice.mayAsk(a, nowS(), c.current.devices)) ask()
        if (packs.keys.any { it !in packSeen }) takePacks()
    }

    // ---- on my other devices: the card, Share, Remove (§7.4–§7.7, §17.6, §17.7) ----------------------

    private fun answeredHere(): Map<String, Pair<String, AnswerWord>> {
        val o = parseJsonOrNull(note(N_ANSWERED) ?: return emptyMap()) as? JsonObject ?: return emptyMap()
        return o.mapNotNull { (d, v) ->
            val x = v.obj() ?: return@mapNotNull null
            val w = AnswerWord.entries.firstOrNull { it.wire == x.s("answer") } ?: return@mapNotNull null
            d to ((x.s("by") ?: "") to w)
        }.toMap()
    }

    private fun saveAnswered(m: Map<String, Pair<String, AnswerWord>>) = setNote(N_ANSWERED, buildJsonObject {
        m.forEach { (d, a) -> put(d, buildJsonObject { put("by", JsonPrimitive(a.first)); put("answer", JsonPrimitive(a.second.wire)) }) }
    }.toString())

    private fun noteSet(n: String): Set<String> = note(n)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** Who answered DEVICE's card (another of my devices or this one), and how; null when nobody yet. */
    fun answerFor(d: String): Pair<String, AnswerWord>? {
        answeredHere()[d]?.let { return it }
        val c = chain() ?: return null
        val a = EncDevice.answers(answers.values, me, c, EncDevice.asks(asks.values, me, c).values)[d] ?: return null
        return a.by to a.answer
    }

    /**
     * §7.5 Share: every key this device can open (its store, after a whole read of the envelopes for my account on the
     * relay) and its version-1 letterbox secrets, in key packs ([EncPacks.packs], at most 40 000 bytes each, the seal
     * signed by this device) to the new device, through the outbox; then the answer 8575 "shared". PROGRESS: packs
     * queued of all. Only from a device on my current list listed before the new one (§17.6). Throws when it can't.
     */
    suspend fun share(card: DeviceCard, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val c = chain() ?: throw ActionException("not_listed", EncDeviceText.FAILED)
        if (card.reader || card.gone || device.pubkey !in c.current.devices || card.device !in c.current.devices ||
            !EncLists.listedBefore(c, device.pubkey, card.device)) throw ActionException("not_older", EncDeviceText.FAILED)
        if (!enc3.readMyEnvelopes()) throw ActionException("offline", EncDeviceText.FAILED)
        val list = packKeys(enc3.allKeys(), ports.v1Secrets())
        val ps = EncPacks.packs(secret, me, card.device, list)
        ps.forEachIndexed { i, p -> ports.enqueueSigned(p); progress(i + 1, ps.size) }
        answer(card, AnswerWord.SHARED)
        notices += EncDeviceText.shared(card.name)
        refresh()
    }

    /** What goes in packs: people keys as their rumor (as received, or as made here), group keys, version-1 secrets. */
    internal fun packKeys(keys: List<ConvKey>, v1: Map<String, String>): List<PackKey> {
        val out = ArrayList<PackKey>()
        for (k in keys) {
            if (k.group) { out += PackKey.Group(k.secret, k.pub, k.conv.removePrefix("g:")); continue }
            val people = k.people.ifEmpty { listOf(me) }
            if (me !in people) continue
            out += PackKey.People(k.rumor ?: k.made?.rumor ?: EncKeys.keyRumor(device.pubkey, k.secret, people.map { KeyPerson(it) }, k.at))
        }
        for ((lb, s) in v1) out += PackKey.V1(s, lb)
        return out
    }

    /**
     * Remove this device (the card's, after the person confirmed): its session revoked (`by` = this device's key), then
     * the answer 8575 "removed". Throws [ActionException] when the belfry said no.
     */
    suspend fun remove(card: DeviceCard) {
        val c = chain()
        if (c == null || device.pubkey !in c.current.devices) throw ActionException("not_listed", EncDeviceText.FAILED)
        if (card.reader) ports.agentReads(card.device, false)
        else ports.revoke(card.device, bunkerOf(card.device), device.pubkey)
        if (!card.gone) answer(card, AnswerWord.REMOVED)
        // a reader's card: the screen names the agent (its member name; the core has no names of members)
        notices += if (card.reader) EncDeviceText.agentOff(card.name ?: EncDeviceText.AGENT) else EncDeviceText.removed(card.name)
        refresh()
    }

    /**
     * Part 6 (SPEC 19.86; §17.9): OK on my agent's card ("‹agent› can now read your private messages"): answered once for
     * every device as the web does (an 8575 "shared" about the reader, signed by this device). Access stays on.
     */
    suspend fun readerOk(card: DeviceCard) {
        if (!card.reader) throw ActionException("not_reader", EncDeviceText.FAILED)
        val c = chain()
        if (c == null || device.pubkey !in c.current.devices) throw ActionException("not_listed", EncDeviceText.FAILED)
        answer(card, AnswerWord.SHARED)
        notices += EncDeviceText.readerOk(card.name ?: EncDeviceText.AGENT)
        refresh()
    }

    /**
     * Part 6 (SPEC 19.86; §11): my agent's access, `POST /agents {pubkey, reads}`. Whether it is on is never kept here:
     * the screens read it from my current list (a `reader` entry, [Enc3DeviceState.readers]) once the belfry lists it.
     * Turned off while its card shows: the card is answered "removed".
     */
    suspend fun agentReads(agent: String, reads: Boolean) {
        ports.agentReads(agent, reads)
        if (!reads) _state.value.cards.firstOrNull { it.reader && it.device == agent }?.let { if (chain()?.current?.devices?.contains(device.pubkey) == true) answer(it, AnswerWord.REMOVED) }
        refresh()
    }

    /**
     * Part 6 (SPEC 19.86): the trash icon of Me › Devices, One device and Who can read this, after the person confirmed:
     * another device of my list revoked (`by` = this device's key), or my agent's access off. Never this device (its
     * row has no trash: signing out is Me's). A card shown for it is answered "removed".
     */
    suspend fun removeListed(key: String, reader: Boolean) {
        if (key == device.pubkey) throw ActionException("this_device", EncDeviceText.FAILED)
        if (reader) { agentReads(key, false); return }
        ports.revoke(key, bunkerOf(key), device.pubkey)
        val listed = chain()?.current?.devices?.contains(device.pubkey) == true
        _state.value.cards.firstOrNull { !it.reader && !it.gone && it.device == key }?.let { if (listed) answer(it, AnswerWord.REMOVED) }
        removedFirst += key
        refresh()
    }

    /** §7.7 the answer (once for every device and conversation), signed by this device, through the outbox. */
    private suspend fun answer(card: DeviceCard, word: AnswerWord) {
        val e = EncPacks.answer(secret, me, card.ask?.let { AnswerTo.Ask(it) } ?: AnswerTo.Device(card.device), word, nowS())
        ports.enqueueSigned(e)
        saveAnswered(answeredHere() + (card.device to (device.pubkey to word)))
    }

    /** "added and removed while you were away": OK. */
    fun goneOk(d: String) { setNote(N_GONE_SEEN, (noteSet(N_GONE_SEEN) + d).joinToString(",")); refresh() }

    fun noticeSeen(text: String) { notices.remove(text); refresh() }

    fun lostOk() { setNote(N_LOST_OK, "1"); refresh() }

    // ---- the first screen (§18.3) ------------------------------------------------------------------

    fun firstScreenOk() { firstScreenDone = true; setNote(N_FIRST_SCREEN, "1"); refresh() }

    /** Remove on the first screen: a device's session revoked (by = this device), or my agent's access off. */
    suspend fun removeFirst(row: FirstRow) {
        if (row.device == device.pubkey) throw ActionException("this_device", EncDeviceText.FAILED)
        if (row.reader) ports.agentReads(row.device, false) else ports.revoke(row.device, bunkerOf(row.device), device.pubkey)
        removedFirst += row.device
        refresh()
    }
    private val removedFirst = java.util.Collections.synchronizedSet(HashSet<String>())

    // ---- the removed device (§16.6, §26) ------------------------------------------------------------

    @Volatile private var removedAskedMs = 0L

    /**
     * Was this device removed? Asked of the belfry (signed by the device key); only `removed: true` AND this device absent
     * from my current list, read whole on this connection now, set [removed] (the session then wipes this account). One
     * condition alone never does; no answer (offline) never does. At most every 30 s unless FORCE.
     */
    suspend fun checkRemoved(force: Boolean = false) {
        if (_removed.value != null) return
        val now = clockMs()
        if (!force && now - removedAskedMs < 30_000) return
        removedAskedMs = now
        val a = ports.removedAnswer() ?: return
        if (!a.removed) return
        if (!enc3.readLists(listOf(me), 5_000)) return // not readable now: asked again at the next look
        if (EncDevice.shouldWipe(a, enc3.readHere(me), chain(), device.pubkey)) _removed.value = RemovedHere(a.at, a.byName)
    }

    // ---- names (the devices' own, from /me/sessions; kept for offline) ------------------------------

    private val sessionRows = ConcurrentHashMap<String, SessionInfo>()
    @Volatile private var namesAskedMs = 0L

    private fun names(): Map<String, String> {
        val o = parseJsonOrNull(note(N_NAMES) ?: return emptyMap()) as? JsonObject ?: return emptyMap()
        return o.mapNotNull { (k, v) -> v.str()?.let { k to it } }.toMap()
    }

    private fun nameOf(d: String): String? = names()[d]

    private fun bunkerOf(d: String): String? = sessionRows[d]?.bunkerPubkey

    /** My sessions read again (at most once a minute unless FORCE): names for my devices. */
    suspend fun readNames(force: Boolean = false) {
        val now = clockMs()
        if (!force && now - namesAskedMs < 60_000) return
        namesAskedMs = now
        val rows = ports.sessions() ?: return
        val m = LinkedHashMap(names())
        for (r in rows) {
            val k = r.clientPubkey ?: continue
            sessionRows[k] = r
            val n = (r.device?.takeIf { it.isNotBlank() } ?: r.applicationName.takeIf { it.isNotBlank() })?.trim()?.take(60) ?: continue
            m[k] = n
        }
        setNote(N_NAMES, JsonObject(m.entries.toList().takeLast(60).associate { it.key to JsonPrimitive(it.value) }).toString())
        refresh()
    }

    // ---- the state for the screens -----------------------------------------------------------------

    /** Everything again (on each list check, event, answer and action). */
    fun refresh() {
        val c = chain()
        val listed = c != null && device.pubkey in c.current.devices
        // §6.4: the first list of mine this device sees brings no card
        var firstN = note(N_FIRST_N)?.toIntOrNull() ?: 0
        if (firstN == 0 && listed) { firstN = c!!.current.n; setNote(N_FIRST_N, "$firstN") }
        var cards = emptyList<DeviceCard>()
        var fork = note(N_FORK) == "1"
        if (c != null) {
            if ((c.fork || c.invalidAbove) && !fork) { fork = true; setNote(N_FORK, "1") } // the warning that doesn't go by itself
            val askMap = EncDevice.asks(asks.values, me, c)
            val counted = EncDevice.answers(answers.values, me, c, askMap.values)
            val here = answeredHere()
            // a card shown here answered on another device: one line says so
            for (d in shown.toList()) {
                val a = counted[d] ?: continue
                if (a.by != device.pubkey && d !in here) notices += EncDeviceText.answeredElsewhere(nameOf(a.by), nameOf(d), a.answer)
                shown.remove(d)
            }
            cards = EncDevice.cards(c, device.pubkey, firstN, askMap, here.keys + counted.keys, noteSet(N_GONE_SEEN)).map { it.copy(name = nameOf(it.device)) }
            cards.filter { !it.gone && !it.reader }.forEach { shown += it.device }
        }
        val older = EncDevice.older(c, device.pubkey).map { DeviceName(it, nameOf(it)) }
        val first = if (firstScreenSeen || firstScreenDone) emptyList() else EncDevice.firstScreen(c, device.pubkey)
            .filter { it.device !in removedFirst }.map { it.copy(name = nameOf(it.device)) }
        // part 6: the devices whose card was answered "shared" (here or by another device of mine)
        val sharedWith = HashSet<String>()
        answeredHere().forEach { (d, a) -> if (a.second == AnswerWord.SHARED) sharedWith += d }
        if (c != null) EncDevice.answers(answers.values, me, c, EncDevice.asks(asks.values, me, c).values).forEach { (d, a) -> if (a.answer == AnswerWord.SHARED) sharedWith += d }
        val shared = note(N_SHARED_BY)?.split(':')?.takeIf { it.size == 2 && Enc.isKey(it[0]) }?.let { DeviceName(it[0], nameOf(it[0])) }
        _state.value = Enc3DeviceState(
            on = enc3.isFormat3,
            listed = listed,
            older = older,
            askedAt = asked()?.at,
            sharedBy = shared,
            lostOk = note(N_LOST_OK) == "1",
            cards = cards,
            notices = synchronized(notices) { notices.distinct() },
            fork = fork,
            strange = enc3.strangeCount > 0,
            firstScreen = first,
            onlyDevice = EncDevice.onlyDevice(c, device.pubkey, enc3.keyCount),
            since3 = ports.since3(),
            devices = c?.current?.devices.orEmpty(),
            readers = c?.current?.readers.orEmpty(),
            added = c?.let { EncDevice.since(it) }.orEmpty(),
            here = device.pubkey,
            sharedWith = sharedWith,
        )
    }

    init {
        // last: every field above is set before Enc3 can call in (from another thread)
        enc3.onDeviceEvents = { offer(it) }
        enc3.onCheck = { refresh() }
    }

    companion object {
        private const val N_ASKED = "p5-asked"
        private const val N_FIRST_N = "p5-first-n"
        private const val N_FIRST_SCREEN = "p5-first-screen"
        private const val N_ANSWERED = "p5-answered"
        private const val N_GONE_SEEN = "p5-gone-seen"
        private const val N_SHARED_BY = "p5-shared-by"
        private const val N_LOST_OK = "p5-lost-ok"
        private const val N_FORK = "p5-fork"
        private const val N_NAMES = "p5-names"
    }
}
