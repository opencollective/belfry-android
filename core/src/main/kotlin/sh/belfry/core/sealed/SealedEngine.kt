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
    private val startedAt = clock()

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
        val a = ports.query(Query(kinds = listOf(Anchor.KIND), authors = listOf(person), d = listOf(Anchor.D)))
        val r = ports.query(Query(kinds = listOf(Roster.KIND), p = listOf(person)))
        val complete = a.complete && r.complete
        val anchorRoot = Anchor.root(a.events, person)
        if (anchorRoot != null && pinnedRoot[person] != null && pinnedRoot[person] != anchorRoot) pinnedHead.remove(person) // started fresh
        val chain = Chain.of(person, a.events, r.events, pinned = pinnedHead[person])
        if (chain != null) {
            pinnedRoot[person] = chain.root.id
            pinnedHead[person] = chain.head.id
            lastHead[person] = chain.head.lb
        }
        val prev = known[person]
        known[person] = Known(chain ?: prev?.chain?.takeIf { !complete }, if (complete) clock() else prev?.completeAt)
        if (chain != null) retryPending()
        return known[person]?.chain
    }

    private fun knowsNoReadyChain(person: String): Boolean {
        val k = known[person] ?: return false
        val at = k.completeAt ?: return false
        val fresh = at >= startedAt || clock() - at < 600
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
        val a = ports.query(Query(kinds = listOf(Anchor.KIND), authors = listOf(account), d = listOf(Anchor.D)))
        val r = ports.query(Query(kinds = listOf(Roster.KIND), p = listOf(account)))
        val complete = a.complete && r.complete
        val chain = Chain.of(account, a.events, r.events, pinned = pinnedHead[account])
        if (chain != null) {
            known[account] = Known(chain, if (complete) clock() else null)
            pinnedRoot[account] = chain.root.id
            pinnedHead[account] = chain.head.id
            lastHead[account] = chain.head.lb
        } else if (complete) {
            known[account] = Known(null, clock())
        }
        when {
            chain != null && device.pubkey in chain.devices -> mine = Mine.IN_HEAD
            chain != null -> {
                mine = Mine.WAITING
                ports.publish(device.signNow(Sealing.request(account, device.pubkey, link, clock())))
            }
            complete && Anchor.root(a.events, account) == null && !guest -> {
                val (lbPub, lbSecret) = KeyChain.newLetterbox()
                val root = device.signNow(Roster.template(account, 1, null, lbPub, listOf(device.pubkey), false, clock()))
                // two devices at once: if an anchor appeared meanwhile, drop mine and ask
                val again = ports.query(Query(kinds = listOf(Anchor.KIND), authors = listOf(account), d = listOf(Anchor.D)))
                if (again.complete && Anchor.root(again.events, account) != null) {
                    mine = Mine.WAITING
                    ports.publish(device.signNow(Sealing.request(account, device.pubkey, link, clock())))
                } else {
                    keys.add(lbPub, lbSecret); saveKeys()
                    val anchor = ports.signAsAccount(Anchor.template(root.id, root.id, clock()))
                    ports.publish(root)
                    ports.publish(anchor)
                    ports.publish(ports.signAsAccount(DeviceNames.template(device.pubkey, DeviceNames.Entry(myName, kind, clock()), lbPub, lbSecret, clock())))
                    mine = Mine.IN_HEAD
                    known[account] = Known(Chain.of(account, listOf(anchor), listOf(root)), clock())
                    pinnedRoot[account] = root.id; pinnedHead[account] = root.id; lastHead[account] = lbPub
                }
            }
            else -> mine = Mine.UNKNOWN // silence or a guest: nothing is created
        }
    }

    private fun saveKeys() = ports.saveKeys(keys.toMap())

    /**
     * Roster n+1 after HEAD (lock held by the caller): signed here, the anchor naming it signed by
     * the signer, and only then both sent, roster first. If the anchor can't be signed, neither goes
     * (the change is tried again later; 294c94f).
     */
    private suspend fun publishNext(head: Chain, devices: List<String>, lb: String, ready: Boolean) {
        val r = device.signNow(Roster.template(account, head.head.n + 1, head.head.id, lb, devices, ready, clock()))
        val anchor = ports.signAsAccount(Anchor.template(head.root.id, r.id, clock()))
        ports.publish(r)
        ports.publish(anchor)
        refresh(account)
    }

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
        if (chainOf(account)?.devices?.contains(dev) == true) return OnRequest.Ignore
        val sessions = ports.sessions() ?: return OnRequest.Ignore
        val row = sessions.firstOrNull { it.clientPubkey == dev && it.chat } ?: return OnRequest.Ignore
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
        Sealing.keysFor(device, account, newDevice, keys, clock()).forEach { ports.publish(it) }
        publishNext(chain, chain.devices + newDevice, chain.head.lb, chain.ready)
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
        val live = ports.sessions()?.mapNotNull { it.clientPubkey }?.toSet() ?: emptySet()
        val (lbPub, lbSecret) = KeyChain.newLetterbox()
        val remaining = chain.devices - dev
        val anchorKeys = KeyChain(keys.toMap()).also { it.add(lbPub, lbSecret) }
        // new keys only to devices that stay AND have a live session
        val wraps = remaining.filter { it != device.pubkey && it in live }.flatMap { d -> Sealing.keysFor(device, account, d, anchorKeys, clock()) }
        // roster and anchor both signed before anything goes; the keys go first, then the two together
        val r = device.signNow(Roster.template(account, chain.head.n + 1, chain.head.id, lbPub, remaining, chain.ready, clock()))
        val anchor = ports.signAsAccount(Anchor.template(chain.root.id, r.id, clock()))
        keys.add(lbPub, lbSecret); saveKeys()
        wraps.forEach { ports.publish(it) }
        ports.publish(r)
        ports.publish(anchor)
        refresh(account)
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
        if (added > 0) saveKeys()
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
        /** Can't tell, or sealed with no head: refused, the text kept. */
        data class Refuse(val why: String) : Plan
    }

    /** How to send in CONV to PARTICIPANTS (me included): every chain asked again first (≤ 3 s). */
    suspend fun plan(conv: String, participants: Collection<String>): Plan {
        val people = (participants + account).toSet()
        withTimeoutOrNull(3000) { people.forEach { refresh(it) } }
        val chains = people.associateWith { chainOf(it) }
        val sealed = conv in sealedSince
        if (!sealed && Sealing.canSeal(people, chains)) sealedSince[conv] = clock()
        if (conv in sealedSince) {
            val heads = people.associateWith { chains[it]?.head?.lb ?: lastHead[it] }
            if (heads.values.any { it == null } || mine != Mine.IN_HEAD) {
                return Plan.Refuse("Not sent: the lock for this conversation isn't ready on this device. Try again in a moment.")
            }
            return Plan.Seal(heads.mapValues { it.value!! })
        }
        if (people.any { knowsNoReadyChain(it) }) return Plan.OldWay
        return Plan.Refuse("Not sent: no answer from the belfry yet. Try again in a moment.")
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

    /** What happened to an arriving wrap; ACCEPT carries the rumor. */
    data class Received(val verdict: Sealing.Verdict, val rumor: Event? = null)

    /** Opens a sealed wrap to me. CONV_OF names a rumor's conversation (to mark it sealed). */
    suspend fun receive(wrap: Event, belfry: String?, convOf: (Event) -> String?): Received {
        val o = Sealing.open(wrap, keys)
        val firstSeen = pending[wrap.id]?.second ?: clock()
        val author = (o as? Opening.Opened)?.rumor?.pubkey
        if (author != null && author != belfry && chainOf(author) == null) refresh(author)
        val v = Sealing.verdict(o, author?.let { chainOf(it) }, firstSeen, clock(), belfry, myLetterboxes = chainOf(account)?.letterboxes?.toSet())
        when (v) {
            Sealing.Verdict.ACCEPT -> {
                pending.remove(wrap.id)
                val r = (o as Opening.Opened).rumor
                convOf(r)?.let { sealedSince.putIfAbsent(it, clock()) }
                return Received(v, r)
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

    /** Wraps kept (tests). */
    val pendingCount: Int get() = pending.size
    val pendingKeysCount: Int get() = pendingKeys.size

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
data class SessionRow(val clientPubkey: String?, val chat: Boolean, val lastSeen: Long?, val device: String?, val applicationName: String)

/** What the engine needs from the outside. */
interface SealedPorts {
    suspend fun query(q: Query): SealedEngine.Answer
    suspend fun publish(e: Event)
    /** Signs as the account (the signer). */
    suspend fun signAsAccount(t: EventTemplate): Event
    /** null = no answer or not well formed (a missing `sessions` field is not "no sessions"). */
    suspend fun sessions(): List<SessionRow>?
    suspend fun revoke(clientPubkey: String)
    fun loadKeys(): Map<String, String>
    fun saveKeys(keys: Map<String, String>)
}

internal fun hexOk(s: String) = Hex.isKey(s)
