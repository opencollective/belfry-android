package sh.belfry.core.sealed

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Random
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.parseJsonOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The device side of sealed conversations (sealed.md v1 up to ac5574b): the first device, a new
 * device's request, Allow / Not me, removal, ready, the key hand-over, choosing how to send, and
 * opening what arrives. Pure: the relay, the session list, the account's signer and the key store
 * are given ([SealedPorts]), so the engine runs against a fake relay in tests.
 *
 * Rules kept here (the web's bugs, the lead's review):
 * - never the old way on silence: only on a complete answer (after AUTH, to EOSE) seen since this
 *   start showing that a participant has no ready chain; otherwise the send is refused, text kept;
 * - a sealed conversation never goes the old way; with no head to seal to, the send is refused;
 * - every participant's chain is asked again before each send (at most 3 s, then what is known),
 *   people already ready included;
 * - a queued wrap is sealed again when its recipient's head changed (reactions and deletes too);
 * - a root only after a complete answer showing no anchor, never in a guest session;
 * - removal "because the session is gone" only from a well-formed list holding my own session;
 * - a hand-over that arrives before my own chain is loaded is kept and retried, never dropped;
 * - the roster lock is never held through the random wait.
 */
class SealedEngine(
    private val ports: SealedPorts,
    /** This device's key (the session's client key). */
    val device: LocalSigner,
    val account: String,
    private val clock: () -> Long,
    /** A guest session never makes a chain. */
    private val guest: Boolean = false,
    /** The random wait before acting on a gone session (§5.6), seconds; tests pass 0. */
    private val removalJitter: Int = 60,
) {
    /** The answer the relay gave to one query: COMPLETE = after AUTH and to EOSE. */
    class Answer(val events: List<Event>, val complete: Boolean)

    private val lock = Mutex()

    val keys: KeyChain = KeyChain(ports.loadKeys())

    private class Known(val chain: Chain?, val completeAt: Long?)
    private val known = HashMap<String, Known>()
    /** Per person: the root first accepted and the head last accepted (§3.4). */
    private val pinnedRoot = HashMap<String, String>()
    private val pinnedHead = HashMap<String, String>()
    /** The last valid head this device knew per person, kept when a chain disappears (§6). */
    private val lastHead = HashMap<String, String>()

    /** Conversations this device saw sealed, with when it first saw each (§6). */
    val sealedSince = HashMap<String, Long>()

    /** Wraps kept for later: locked here, or waiting for the author's chain (≤ 30 days). */
    private val pending = LinkedHashMap<String, Pair<Event, Long>>()
    /** Hand-overs that came before my own chain was there. */
    private val pendingKeys = LinkedHashMap<String, Event>()

    /** The link secrets this device showed, with when (15 minutes). */
    private val linkSecrets = HashMap<String, Long>()

    // ---- chains -------------------------------------------------------------------------------

    fun chainOf(person: String): Chain? = known[person]?.chain

    /** Asks the relay again for PERSON's anchor and rosters (never by `#lb`). */
    suspend fun refresh(person: String): Chain? {
        val qa = ports.query(Query(kinds = listOf(Anchor.KIND), authors = listOf(person), d = listOf(Anchor.D)))
        val qr = ports.query(Query(kinds = listOf(Roster.KIND), p = listOf(person)))
        val complete = qa.complete && qr.complete
        // the pin holds even when the relay no longer has its events: the stored path and anchor join in
        val a = SealedEngine.Answer((qa.events + listOfNotNull(anchors[person])).distinctBy { it.id }, qa.complete)
        val r = SealedEngine.Answer((qr.events + pathEvents[person].orEmpty()).distinctBy { it.id }, qr.complete)
        val anchorRoot = Anchor.root(a.events, person)
        if (anchorRoot != null && pinnedRoot[person] != null && pinnedRoot[person] != anchorRoot) {
            pinnedHead.remove(person) // started fresh
            if (person == account) known[person]?.chain?.letterboxes?.let { oldLetterboxes.addAll(it) }
        }
        val before = known[person]?.chain
        val chain = Chain.of(person, a.events, r.events, pinned = pinnedHead[person])
        if (chain != null) {
            noteSwitch(person, before, chain, Anchor.read(a.events, person))
            pinnedRoot[person] = chain.root.id
            pinnedHead[person] = chain.head.id
            lastHead[person] = chain.head.lb
        }
        val prev = known[person]
        known[person] = Known(chain ?: prev?.chain?.takeIf { !complete }, if (complete) clock() else prev?.completeAt)
        if (chain != null) {
            remember(person, chain, a.events, r.events)
            retryPending()
        }
        return known[person]?.chain
    }

    // ---- pins kept across restarts (sealed.md §3.4, durable) -------------------------------------

    private val anchors = HashMap<String, Event>()
    private val pathEvents = HashMap<String, List<Event>>()

    /** Shared history sent after someone's reset (the amber line becomes plain): person → when. */
    val sharedAfter = HashMap<String, Long>()

    /** Keeps PERSON's pin: the newest valid anchor and the roster events of the chain's path. */
    private fun remember(person: String, chain: Chain, anchorEvents: List<Event>, rosterEvents: List<Event>) {
        val newest = anchorEvents.filter { it.pubkey == person && it.kind == Anchor.KIND }.maxByOrNull { it.createdAt }
        if (newest != null) anchors[person] = newest
        val byId = rosterEvents.associateBy { it.id }
        pathEvents[person] = chain.rosters.mapNotNull { byId[it.id] }
        saveState()
    }

    /** Written at once, as one value, beside the conversation data (not secrets); wiped with the account. */
    fun saveState() {
        val o = buildJsonObject {
            put("v", JsonPrimitive(1))
            put("pins", buildJsonObject {
                for (p in (pinnedRoot.keys + pinnedHead.keys).toSet()) put(p, buildJsonObject {
                    pinnedRoot[p]?.let { put("root", JsonPrimitive(it)) }
                    pinnedHead[p]?.let { put("head", JsonPrimitive(it)) }
                    anchors[p]?.let { put("anchor", it.toJson()) }
                    put("path", JsonArray(pathEvents[p].orEmpty().map { it.toJson() }))
                    put("marks", JsonArray(seenAnchored[p].orEmpty().map { JsonPrimitive(it) }))
                    resetAt[p]?.let { put("reset", JsonPrimitive(it)) }
                    sharedAfter[p]?.let { put("shared", JsonPrimitive(it)) }
                })
            })
            put("sealed", buildJsonObject { sealedSince.forEach { (c, t) -> put(c, JsonPrimitive(t)) } })
            put("untrusted", JsonArray(untrusted.map { JsonPrimitive(it) }))
            put("oldLb", JsonArray(oldLetterboxes.map { JsonPrimitive(it) }))
        }
        ports.saveState(o.toString())
    }

    private fun loadState() {
        val o = parseJsonOrNull(ports.loadState() ?: return) as? JsonObject ?: return
        (o["pins"] as? JsonObject)?.forEach { (p, v) ->
            val pin = v as? JsonObject ?: return@forEach
            (pin["root"] as? JsonPrimitive)?.content?.let { pinnedRoot[p] = it }
            (pin["head"] as? JsonPrimitive)?.content?.let { pinnedHead[p] = it }
            (pin["anchor"] as? JsonObject)?.let { Event.fromJson(it) }?.let { anchors[p] = it }
            pathEvents[p] = (pin["path"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { e -> Event.fromJson(e) } }.orEmpty()
            seenAnchored[p] = (pin["marks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().toMutableSet()
            (pin["reset"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { resetAt[p] = it }
            (pin["shared"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { sharedAfter[p] = it }
            val chain = anchors[p]?.let { Chain.of(p, listOf(it), pathEvents[p].orEmpty(), pinned = pinnedHead[p]) }
            if (chain != null) { known[p] = Known(chain, null); lastHead[p] = chain.head.lb }
        }
        (o["sealed"] as? JsonObject)?.forEach { (c, t) -> (t as? JsonPrimitive)?.content?.toLongOrNull()?.let { sealedSince[c] = it } }
        (o["untrusted"] as? JsonArray)?.forEach { (it as? JsonPrimitive)?.content?.let(untrusted::add) }
        (o["oldLb"] as? JsonArray)?.forEach { (it as? JsonPrimitive)?.content?.let(oldLetterboxes::add) }
    }

    init { loadState() }

    /**
     * Shares a conversation's sealed messages with PERSON's new head (§8, from the amber line): kinds
     * 14 / 7 / 5 only, packed under the ceiling, each 8564 sealed to their head. The line becomes plain.
     */
    suspend fun shareHistory(person: String, rumors: List<Event>) {
        val chain = refresh(person) ?: throw NotDone()
        val shares = Sealing.shareRumors(account, person, rumors.filter { it.kind in Sealing.SHARED_KINDS }.sortedBy { it.createdAt }, clock())
        for (sh in shares) if (!ports.publish(Sealing.seal(sh, device, person, chain.head.lb, clock()))) throw NotDone()
        sharedAfter[person] = clock()
        saveState()
    }

    /** Rosters this client saw named by the anchor, per person (the pin's "seen anchored" mark, 2a1b30e). */
    private val seenAnchored = HashMap<String, MutableSet<String>>()

    /** When each person started fresh or their chain was switched: the amber notice (§3.4, §3.3). */
    val resetAt = HashMap<String, Long>()

    /** After a switch of my own chain: devices of the new head this client never held, never answered without a prompt. */
    private val untrusted = HashSet<String>()

    /**
     * A SWITCH (sealed.md 2a1b30e): the anchor names another branch of the same root while the head
     * this client held had been seen anchored. Followed, but treated like a fresh start: the amber
     * notice; for my own account, this device re-checks its place, and the devices new to the head
     * go through Allow (the server holds the key that signs anchors).
     */
    private fun noteSwitch(person: String, before: Chain?, after: Chain, anchor: Pair<String, String?>?) {
        val marks = seenAnchored.getOrPut(person) { HashSet() }
        val anchoredId = anchor?.let { it.second ?: it.first }
        val freshStart = before != null && before.root.id != after.root.id
        val switched = before != null && !freshStart && before.rosters.any { it.id in marks } &&
            before.head.id !in after.rosters.map { it.id } && after.rosters.none { it.id == before.head.id }
        if (freshStart || switched) {
            resetAt[person] = clock()
            saveState()
            if (person == account) {
                if (switched) untrusted.addAll(after.devices - before!!.devices.toSet())
                if (device.pubkey !in after.devices) mine = Mine.WAITING
            }
        }
        if (anchoredId != null && after.rosters.any { it.id == anchoredId }) marks.add(anchoredId)
    }

    /** Letterboxes of earlier pinned chains of mine (a fresh start keeps them mine, §5.3). */
    private val oldLetterboxes = HashSet<String>()

    /**
     * My chain's letterboxes (valid rosters of the current chain and the previously pinned one),
     * ONLY when my chain is current on this connection (6e0bc8e): before that a wrap naming another
     * lb is kept, never dropped (it may be my newest letterbox, ahead of its roster).
     */
    private fun myLetterboxesIfCurrent(): Set<String>? {
        val k = known[account] ?: return null
        if (k.completeAt == null) return null
        return (k.chain?.letterboxes.orEmpty() + oldLetterboxes).toSet()
    }

    private fun knowsNoReadyChain(person: String): Boolean {
        val k = known[person] ?: return false
        val at = k.completeAt ?: return false
        val fresh = clock() - at < 600 // expires after 10 minutes (64a07fa)
        return fresh && k.chain?.ready != true
    }

    // ---- my own chain -------------------------------------------------------------------------

    /** My chain's state on this device. */
    enum class Mine { UNKNOWN, NONE, IN_HEAD, WAITING }

    var mine: Mine = Mine.UNKNOWN
        private set

    /**
     * At start: read my chain; with a complete answer and no anchor, make the first roster (§5.1);
     * when a chain exists and this device isn't in its head, ask (§5.2).
     */
    suspend fun start(myName: String, kind: String, link: String? = null) = lock.withLock {
        val qa = ports.query(Query(kinds = listOf(Anchor.KIND), authors = listOf(account), d = listOf(Anchor.D)))
        val qr = ports.query(Query(kinds = listOf(Roster.KIND), p = listOf(account)))
        val complete = qa.complete && qr.complete
        val a = SealedEngine.Answer((qa.events + listOfNotNull(anchors[account])).distinctBy { it.id }, qa.complete)
        val r = SealedEngine.Answer((qr.events + pathEvents[account].orEmpty()).distinctBy { it.id }, qr.complete)
        val chain = Chain.of(account, a.events, r.events, pinned = pinnedHead[account])
        if (chain != null) {
            noteSwitch(account, known[account]?.chain, chain, Anchor.read(a.events, account))
            known[account] = Known(chain, if (complete) clock() else null)
            remember(account, chain, a.events, r.events)
            pinnedRoot[account] = chain.root.id
            pinnedHead[account] = chain.head.id
            lastHead[account] = chain.head.lb
        } else if (complete) {
            known[account] = Known(null, clock())
        }
        when {
            // in the head but without its letterbox's secret (a lost hand-over): ask too (c233eec, §5.5)
            chain != null && device.pubkey in chain.devices && keys.secretFor(chain.head.lb) == null -> {
                mine = Mine.WAITING
                ports.publish(device.signNow(Sealing.request(account, device.pubkey, link, clock())))
            }
            chain != null && device.pubkey in chain.devices -> {
                mine = Mine.IN_HEAD
                anchorOwnTail(chain)
            }
            chain != null -> {
                mine = Mine.WAITING
                ports.publish(device.signNow(Sealing.request(account, device.pubkey, link, clock())))
            }
            complete && Anchor.root(a.events, account) == null && !guest && !firstRefused -> {
                // one pending key, reused on every try (no orphan keys; c233eec)
                val (lbPub, lbSecret) = pendingFirst ?: KeyChain.newLetterbox().also { pendingFirst = it }
                val root = device.signNow(Roster.template(account, 1, null, lbPub, listOf(device.pubkey), false, clock()))
                // two devices at once: if an anchor appeared meanwhile, drop mine and ask
                val again = ports.query(Query(kinds = listOf(Anchor.KIND), authors = listOf(account), d = listOf(Anchor.D)))
                if (again.complete && Anchor.root(again.events, account) != null) {
                    mine = Mine.WAITING
                    ports.publish(device.signNow(Sealing.request(account, device.pubkey, link, clock())))
                } else {
                    keys.add(lbPub, lbSecret); saveKeys()
                    val anchor = ports.signAsAccount(Anchor.template(root.id, root.id, clock()))
                    if (!ports.publish(root) || !ports.publish(anchor)) {
                        // refused: stop trying (the relay said no; not every few minutes)
                        firstRefused = true
                        mine = Mine.UNKNOWN
                        return@withLock
                    }
                    pendingFirst = null
                    ports.publish(ports.signAsAccount(DeviceNames.template(device.pubkey, DeviceNames.Entry(myName, kind, clock()), lbPub, lbSecret, clock())))
                    mine = Mine.IN_HEAD
                    val first = Chain.of(account, listOf(anchor), listOf(root))
                    known[account] = Known(first, clock())
                    if (first != null) remember(account, first, listOf(anchor), listOf(root))
                    pinnedRoot[account] = root.id; pinnedHead[account] = root.id; lastHead[account] = lbPub
                    saveState()
                }
            }
            else -> mine = Mine.UNKNOWN // silence or a guest: nothing is created
        }
    }

    private fun saveKeys() = ports.saveKeys(keys.toMap())

    private var pendingFirst: Pair<String, String>? = null
    /** The relay refused my first roster: don't try again in this run. */
    var firstRefused = false
        private set

    /**
     * A roster of mine past the anchored end (published, its anchor lost): anchored now (§3.2,
     * c233eec), at start and at each upkeep. Lock held by the caller.
     */
    private suspend fun anchorOwnTail(chain: Chain) {
        val anchored = anchoredHead() ?: return
        if (chain.head.id == anchored || chain.head.signer != device.pubkey) return
        if (chain.rosters.none { it.id == anchored }) return
        val anchor = try { ports.signAsAccount(Anchor.template(chain.root.id, chain.head.id, clock())) } catch (e: Exception) { return }
        ports.publish(anchor)
    }

    /** The 6-hour upkeep: gone sessions, ready, my own unanchored roster. */
    suspend fun upkeep() {
        lock.withLock { refresh(account)?.let { anchorOwnTail(it) } }
        checkSessions()
        checkReady()
    }

    /** The connection dropped: what the relay said no longer counts as knowledge (c233eec). */
    fun connectionLost() {
        for ((k, v) in known.entries.toList()) known[k] = Known(v.chain, null)
    }

    /**
     * Roster n+1 after HEAD (lock held by the caller): signed here, the anchor naming it signed by
     * the signer, and only then both sent, roster first. If the anchor can't be signed, neither goes
     * (the change is tried again later; 294c94f).
     */
    private suspend fun publishNext(head: Chain, devices: List<String>, lb: String, ready: Boolean): Boolean {
        val r = device.signNow(Roster.template(account, head.head.n + 1, head.head.id, lb, devices, ready, clock()))
        val anchor = ports.signAsAccount(Anchor.template(head.root.id, r.id, clock()))
        return land(r, anchor)
    }

    /**
     * Roster and anchor as one unit (64a07fa): done only when the relay accepted BOTH, and my chain,
     * read again, has the roster on its anchored path. Otherwise not done (the caller redoes it on
     * the new head); a roster the relay refused is never kept here.
     */
    private suspend fun land(r: Event, anchor: Event): Boolean {
        if (!ports.publish(r)) return false
        if (!ports.publish(anchor)) return false
        refresh(account) ?: return false
        return anchoredHead() == r.id
    }

    /** The roster my anchor names, as the relay has it now (null = unknown or the root). */
    private suspend fun anchoredHead(): String? {
        val a = ports.query(Query(kinds = listOf(Anchor.KIND), authors = listOf(account), d = listOf(Anchor.D)))
        return Anchor.read(a.events, account)?.let { it.second ?: it.first }
    }

    /** A roster change that didn't land after a few tries on the newest head. */
    class NotDone : IllegalStateException("Not done yet: another of your devices changed the list at the same moment. Try again.")

    // ---- requests, Allow / Not me (§5.2) ------------------------------------------------------

    /** A link secret for the QR of Link a phone or tablet, kept 15 minutes (only in the QR). */
    fun newLinkSecret(): String = Random.hex(32).also { linkSecrets[it] = clock() }

    sealed interface OnRequest {
        /** Not a live chat session of mine, or not well formed: nothing to show. */
        data object Ignore : OnRequest
        /** Allowed at once (a valid link proof from a code this device showed). */
        data object Allowed : OnRequest
        /** Ask: Allow / Not me, with the session's device name. */
        data class Ask(val device: String, val name: String) : OnRequest
    }

    suspend fun onRequest(req: Event): OnRequest {
        val (dev, acc) = Sealing.readRequest(req) ?: return OnRequest.Ignore
        if (acc != account || mine != Mine.IN_HEAD) return OnRequest.Ignore
        val sessions = ports.sessions() ?: return OnRequest.Ignore
        val row = sessions.firstOrNull { it.clientPubkey == dev && it.chat } ?: return OnRequest.Ignore
        val head = chainOf(account)
        if (head != null && dev in head.devices && dev !in untrusted) {
            // a device of the head this device holds (its pinned head, never an older roster an anchor
            // points back to) that lost its hand-over: the keys at once, no prompt (§5.5, f28220c) —
            // only for a request newer than the roster that added it and than my last hand-over to it
            if (keys.secretFor(head.head.lb) == null) return OnRequest.Ignore
            val addedAt = addedLast(head, dev) ?: return OnRequest.Ignore
            val lastGiven = ports.loadNote("handover:$dev")?.toLongOrNull() ?: 0L
            if (req.createdAt <= addedAt || req.createdAt <= lastGiven) return OnRequest.Ignore
            Sealing.keysFor(device, account, dev, keys, clock()).forEach { ports.publish(it) }
            ports.saveNote("handover:$dev", clock().toString())
            return OnRequest.Allowed
        }
        val now = clock()
        val live = linkSecrets.filterValues { now - it <= 15 * 60 }.keys
        if (Sealing.linkMatches(req, live)) { allow(dev); return OnRequest.Allowed }
        return OnRequest.Ask(dev, row.device?.ifEmpty { null } ?: row.applicationName)
    }

    class TooMany : IllegalStateException("A roster holds at most 20 devices: remove one first.")

    /** Allow: keys to the new device, then roster n+1 (same lb, devices + it), then the anchor. Refused at 20. */
    suspend fun allow(newDevice: String) = lock.withLock {
        val chain = refresh(account) ?: throw IllegalStateException("no chain")
        if (newDevice in chain.devices) return@withLock
        if (chain.devices.size >= Roster.MAX_DEVICES) throw TooMany()
        // the session is still live (294c94f)
        val sessions = ports.sessions() ?: throw IllegalStateException("The list of sessions could not be read. Try again.")
        if (sessions.none { it.clientPubkey == newDevice && it.chat }) throw IllegalStateException("That device is no longer signed in.")
        // the roster first; keys only once it is on the anchored path (never keys in no roster, 64a07fa)
        var cur = chain
        repeat(3) {
            if (publishNext(cur, (cur.devices + newDevice).distinct(), cur.head.lb, cur.ready)) {
                Sealing.keysFor(device, account, newDevice, keys, clock()).forEach { ports.publish(it) }
                ports.saveNote("handover:$newDevice", clock().toString())
                return@withLock
            }
            cur = refresh(account) ?: throw NotDone()
            val anchored = anchoredHead()?.let { id -> cur.rosters.firstOrNull { it.id == id } }
            if (anchored != null && newDevice in anchored.devices) { Sealing.keysFor(device, account, newDevice, keys, clock()).forEach { ports.publish(it) }; return@withLock }
            if (cur.devices.size >= Roster.MAX_DEVICES) throw TooMany()
        }
        throw NotDone()
    }

    /** Not me: revoke that session. No roster change. */
    suspend fun notMe(newDevice: String) {
        ports.revoke(newDevice)
    }

    // ---- removal (§5.6) -----------------------------------------------------------------------

    /** Remove DEVICE (never this one): a new letterbox, keys to each remaining device, roster n+1, anchor. */
    suspend fun remove(dev: String, revoke: Boolean = true) = lock.withLock {
        require(dev != device.pubkey) { "this device signs out instead" }
        val chain = refresh(account) ?: return@withLock
        if (dev !in chain.devices) return@withLock
        // a user's removal revokes the session FIRST and changes nothing when that fails (294c94f)
        if (revoke) ports.revoke(dev)
        // the session list can't be read: no rotation (never a letterbox handed to nobody; §5.6, c233eec)
        val live = ports.sessions()?.mapNotNull { it.clientPubkey }?.toSet() ?: throw NotDone()
        var cur = chain
        repeat(3) {
            if (dev !in cur.devices) return@withLock
            val (lbPub, lbSecret) = KeyChain.newLetterbox()
            val remaining = cur.devices - dev
            val withNew = KeyChain(keys.toMap()).also { it.add(lbPub, lbSecret) }
            // new keys only to devices that stay AND have a live session
            val wraps = remaining.filter { it != device.pubkey && it in live }.flatMap { d -> Sealing.keysFor(device, account, d, withNew, clock()) }
            // roster and anchor both signed before anything goes; the keys go first, then the two as one unit
            val r = device.signNow(Roster.template(account, cur.head.n + 1, cur.head.id, lbPub, remaining, cur.ready, clock()))
            val anchor = ports.signAsAccount(Anchor.template(cur.root.id, r.id, clock()))
            keys.add(lbPub, lbSecret); saveKeys()
            wraps.forEach { ports.publish(it) }
            if (land(r, anchor)) return@withLock
            cur = refresh(account) ?: throw NotDone()
        }
        throw NotDone()
    }

    /**
     * Devices of my head whose chat session is gone: removed, after a random wait (lock not held
     * through it), the head read again. Only from a well-formed list that holds my own session.
     */
    suspend fun checkSessions() {
        val sessions = ports.sessions() ?: return
        if (sessions.none { it.clientPubkey == device.pubkey }) return
        val liveKeys = sessions.mapNotNull { it.clientPubkey }.toSet()
        val gone = chainOf(account)?.devices?.filter { it != device.pubkey && it !in liveKeys }.orEmpty()
        if (gone.isEmpty()) return
        if (removalJitter > 0) delay(Random.int(removalJitter * 1000).toLong())
        for (d in gone) {
            val head = refresh(account) ?: return
            if (d in head.devices) remove(d, revoke = false)
        }
        checkReady()
    }

    // ---- ready (§6) ---------------------------------------------------------------------------

    /** Publishes a roster with `ready` when every counted chat session is a device of my head. */
    suspend fun checkReady() {
        val sessions = ports.sessions() ?: return
        lock.withLock {
            val chain = refresh(account) ?: return@withLock
            if (chain.ready || device.pubkey !in chain.devices) return@withLock
            val counted = Sealing.countedSessions(sessions.map { Triple(it.clientPubkey, it.chat, it.lastSeen) }, clock())
            if (Sealing.readyFor(chain.devices, counted)) publishNext(chain, chain.devices, chain.head.lb, true)
        }
    }

    // ---- keys arriving (§5.5) -----------------------------------------------------------------

    /** A wrap to this device: a hand-over. Kept and retried when my chain isn't there yet. */
    suspend fun onDeviceWrap(wrap: Event): Boolean {
        val h = Sealing.openKeys(wrap, device) ?: return false
        val mineChain = chainOf(account)
        if (mineChain == null) { pendingKeys[wrap.id] = wrap; return false }
        if (!Sealing.acceptHandover(h, account, listOf(mineChain))) return false
        val added = keys.merge(h.keys)
        if (added > 0) { saveKeys(); onKeys?.invoke() }
        pendingKeys.remove(wrap.id)
        if (device.pubkey in (refresh(account)?.devices ?: emptyList())) mine = Mine.IN_HEAD
        retryPending()
        return true
    }

    // ---- sending (§4, §6) ---------------------------------------------------------------------

    sealed interface Plan {
        /** Seal to these heads (account → lb), the self-copy included. */
        data class Seal(val heads: Map<String, String>) : Plan
        /** Today's way: known, from a complete answer, that someone has no ready chain. */
        data object OldWay : Plan
        /** Sealed with no head (WHO: the person with no head to seal to), or this device is locked: not sent, the text kept. */
        data class Refuse(val why: String, val who: String? = null) : Plan
        /** Can't tell yet: hold it undecided and plan again at the first complete answer (64a07fa). */
        data object Hold : Plan
    }

    /** How to send in CONV to PARTICIPANTS (me included): every chain asked again first (≤ 3 s). */
    suspend fun plan(conv: String, participants: Collection<String>): Plan {
        val people = (participants + account).toSet()
        withTimeoutOrNull(3000) { people.forEach { refresh(it) } }
        val chains = people.associateWith { chainOf(it) }
        val sealed = conv in sealedSince
        if (!sealed && Sealing.canSeal(people, chains)) { sealedSince[conv] = clock(); saveState() }
        if (conv in sealedSince) {
            val heads = people.associateWith { chains[it]?.head?.lb ?: lastHead[it] }
            if (mine != Mine.IN_HEAD) return Plan.Refuse("Not sent: the lock for this conversation isn't ready on this device. Try again in a moment.")
            heads.entries.firstOrNull { it.value == null }?.let { return Plan.Refuse("Not sent yet: their new device isn't ready.", who = it.key) }
            return Plan.Seal(heads.mapValues { it.value!! })
        }
        if (people.any { knowsNoReadyChain(it) }) return Plan.OldWay
        return Plan.Hold
    }

    /** The wraps of a rumor for a Seal plan. A rumor over 40 000 bytes is refused. */
    fun seal(rumor: Event, plan: Plan.Seal): List<Queued> =
        plan.heads.map { (who, lb) -> Queued(rumor, who, lb, Sealing.seal(rumor, device, who, lb, clock())) }

    /** A wrap waiting in the outbox, with what it was sealed to. */
    data class Queued(val rumor: Event, val recipient: String, val lb: String, val wrap: Event)

    /** Sealed again when the recipient's head changed before it was sent (§9), any kind (14, 7, 5…). */
    fun resealIfNeeded(q: Queued): Queued {
        val head = chainOf(q.recipient)?.head?.lb ?: return q
        if (!Sealing.needsReseal(q.lb, head)) return q
        return Queued(q.rumor, q.recipient, head, Sealing.seal(q.rumor, device, q.recipient, head, clock()))
    }

    // ---- receiving (§4) -----------------------------------------------------------------------

    /** Unanswered chain reads back off per author, so waiting wraps don't hold up the rest (64a07fa). */
    private val askedAt = HashMap<String, Pair<Long, Long>>()

    private fun mayAsk(author: String): Boolean {
        val (at, wait) = askedAt[author] ?: return true.also { askedAt[author] = clock() to 60L }
        if (clock() - at < wait) return false
        askedAt[author] = clock() to minOf(wait * 2, 3600L)
        return true
    }

    /** What happened to an arriving wrap; ACCEPT carries the rumor. */
    data class Received(val verdict: Sealing.Verdict, val rumor: Event? = null, val sealPubkey: String? = null)

    /** Opens a sealed wrap to me. CONV_OF names a rumor's conversation (to mark it sealed). */
    suspend fun receive(wrap: Event, belfry: String?, convOf: (Event) -> String?): Received {
        val o = Sealing.open(wrap, keys)
        val firstSeen = pending[wrap.id]?.second ?: clock()
        val author = (o as? Opening.Opened)?.rumor?.pubkey
        if (author != null && author != belfry && chainOf(author) == null && mayAsk(author)) refresh(author)
        val v = Sealing.verdict(o, author?.let { chainOf(it) }, firstSeen, clock(), belfry, myLetterboxes = myLetterboxesIfCurrent())
        when (v) {
            Sealing.Verdict.ACCEPT -> {
                pending.remove(wrap.id)
                val r = (o as Opening.Opened).rumor
                convOf(r)?.let { if (sealedSince.putIfAbsent(it, clock()) == null) saveState() }
                return Received(v, r, (o as Opening.Opened).sealPubkey)
            }
            Sealing.Verdict.KEEP, Sealing.Verdict.LOCKED -> pending[wrap.id] = wrap to firstSeen
            Sealing.Verdict.DROP -> pending.remove(wrap.id)
            Sealing.Verdict.NOT_SEALED -> {}
        }
        return Received(v)
    }

    /** Accepted later (a new roster of the author, or keys arrived). */
    val later = ArrayList<Event>()

    private suspend fun retryPending() {
        for ((id, p) in pending.entries.toList()) {
            val o = Sealing.open(p.first, keys)
            val author = (o as? Opening.Opened)?.rumor?.pubkey
            val v = Sealing.verdict(o, author?.let { known[it]?.chain }, p.second, clock(), null)
            if (v == Sealing.Verdict.ACCEPT) { pending.remove(id); later.add((o as Opening.Opened).rumor) }
            if (v == Sealing.Verdict.DROP) pending.remove(id)
        }
        if (pendingKeys.isNotEmpty() && known[account]?.chain != null) {
            for (w in pendingKeys.values.toList()) onDeviceWrap(w)
        }
    }

    /** Told when keys arrived: the app reads its wraps again from 0 (§5.5). */
    @Volatile var onKeys: (() -> Unit)? = null

    /** When DEV last came into my chain (the roster that added it, after any earlier removal). */
    private fun addedLast(chain: Chain, dev: String): Long? {
        var at: Long? = null
        for (i in chain.rosters.indices) {
            val inIt = dev in chain.rosters[i].devices
            val before = i > 0 && dev in chain.rosters[i - 1].devices
            if (inIt && !before) at = chain.rosters[i].createdAt
        }
        return at
    }

    /** This device is in my head but has no secret for it (a lost hand-over): "Waiting for another of your devices". */
    val inHeadWithoutKey: Boolean get() = chainOf(account)?.let { device.pubkey in it.devices && keys.secretFor(it.head.lb) == null } == true

    /** Wraps kept (tests). */
    val pendingCount: Int get() = pending.size
    val pendingKeysCount: Int get() = pendingKeys.size

    /** My devices' names (one 30078 per device, §3.5), as far as this device can open them. */
    suspend fun deviceNames(): Map<String, DeviceNames.Entry> {
        val head = chainOf(account) ?: return emptyMap()
        val a = ports.query(Query(kinds = listOf(Anchor.KIND), authors = listOf(account), d = head.rosters.flatMap { it.devices }.distinct().map { DeviceNames.d(it) }))
        return DeviceNames.all(a.events, account, keys)
    }

    /** Writes this device's own name record under the head letterbox (§3.5), when it is in the head. */
    suspend fun writeMyName(name: String, kind: String) {
        val head = chainOf(account) ?: return
        val secret = keys.secretFor(head.head.lb) ?: return
        if (device.pubkey !in head.devices) return
        ports.publish(ports.signAsAccount(DeviceNames.template(device.pubkey, DeviceNames.Entry(name, kind, head.addedAt(device.pubkey)), head.head.lb, secret, clock())))
    }

    /** Requests (8562) to my account on the relay, newest first. */
    suspend fun requests(): List<Event> =
        ports.query(Query(kinds = listOf(Sealing.REQUEST), p = listOf(account))).events.sortedByDescending { it.createdAt }

    /** Is DEVICE removable from this device's list (not this one, and this device is in the head)? */
    fun canRemove(dev: String): Boolean = mine == Mine.IN_HEAD && dev != device.pubkey && chainOf(account)?.devices?.contains(dev) == true
}

/** A relay query (only what sealed conversations ask; never `#lb`). */
data class Query(
    val kinds: List<Int>,
    val authors: List<String>? = null,
    val p: List<String>? = null,
    val d: List<String>? = null,
)

/** One row of GET /api/me/sessions, as the engine needs it. LAST_SEEN = last_activity, else created_at. */
data class SessionRow(val clientPubkey: String?, val chat: Boolean, val lastSeen: Long?, val device: String?, val applicationName: String, val id: String = "")

/** What the engine needs from the outside. */
interface SealedPorts {
    suspend fun query(q: Query): SealedEngine.Answer
    /** True when the relay accepted it (OK true). */
    suspend fun publish(e: Event): Boolean
    /** Signs as the account (the signer). */
    suspend fun signAsAccount(t: EventTemplate): Event
    /** null = no answer or not well formed (a missing `sessions` field is not "no sessions"). */
    suspend fun sessions(): List<SessionRow>?
    suspend fun revoke(clientPubkey: String)
    fun loadKeys(): Map<String, String>
    fun saveKeys(keys: Map<String, String>)
    /** The engine's pins and sealed conversations, one value, kept beside the conversation data. */
    fun loadState(): String? = null
    fun saveState(json: String) {}
    /** Small records kept across restarts (my last hand-over to each device, f28220c). */
    fun loadNote(name: String): String? = null
    fun saveNote(name: String, value: String) {}
}

internal fun hexOk(s: String) = Hex.isKey(s)
