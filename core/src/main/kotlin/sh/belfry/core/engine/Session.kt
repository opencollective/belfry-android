package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Random
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.dm.DirectMessages
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmCandidate
import sh.belfry.core.dm.DmException
import sh.belfry.core.dm.DmHost
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.mail.MailPart
import sh.belfry.core.model.Actions
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.Draft
import sh.belfry.core.model.Mentions
import sh.belfry.core.model.Message
import sh.belfry.core.model.NewEvent
import sh.belfry.core.model.NewProposal
import sh.belfry.core.model.People
import sh.belfry.core.model.Place
import sh.belfry.core.model.PodAccess
import sh.belfry.core.model.Proposal
import sh.belfry.core.model.Rsvp
import sh.belfry.core.model.Share
import sh.belfry.core.model.World
import sh.belfry.core.model.WorldContext
import sh.belfry.core.model.inviteText
import sh.belfry.core.model.isGuest
import sh.belfry.core.model.eventTagsFor
import sh.belfry.core.model.ComposerDraft
import sh.belfry.core.model.FormDrafts
import sh.belfry.core.net.Branding
import sh.belfry.core.net.Access
import sh.belfry.core.net.ApiException
import sh.belfry.core.net.BelfryApi
import sh.belfry.core.net.BunkerSigner
import sh.belfry.core.net.ChannelGuest
import sh.belfry.core.net.Community
import sh.belfry.core.net.Discovery
import sh.belfry.core.net.Filters
import sh.belfry.core.net.Follows
import sh.belfry.core.net.GuestLink
import sh.belfry.core.net.JoinedAccount
import sh.belfry.core.net.MyAgent
import sh.belfry.core.net.MyAgents
import sh.belfry.core.net.Relay
import sh.belfry.core.net.Revalidated
import sh.belfry.core.net.RelayException
import sh.belfry.core.net.RelayState
import sh.belfry.core.net.SessionInfo
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.SignerTimeout
import sh.belfry.core.nostr.Tag
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.str
import sh.belfry.core.nostr.unixNow
import sh.belfry.core.outbox.Attachment
import sh.belfry.core.outbox.Outbox
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.SendOutcome
import sh.belfry.core.outbox.Target
import sh.belfry.core.store.Store
import java.io.IOException
import java.time.ZoneId

/** Does the groups relay let this key in (SPEC 4)? */
enum class AccessState { UNKNOWN, IN, REFUSED }

/** Connection and outbox state, for the banner and the settings screen. */
data class Status(
    val online: Boolean = true,
    val groups: RelayState = RelayState.DOWN,
    val main: RelayState = RelayState.DOWN,
    val access: AccessState = AccessState.UNKNOWN,
    /** Outbox items waiting to go out. */
    val queued: Int = 0,
    /** Outbox items refused for good (they wait for Retry or Discard). */
    val failed: Int = 0,
    /** The remote signer did not answer the last time it was asked. */
    val signerTrouble: Boolean = false,
    /** The demo: everything stays on this device. */
    val localOnly: Boolean = false,
) {
    /** The groups relay is connected (or the demo, which needs none). */
    val connected: Boolean get() = localOnly || groups == RelayState.UP || groups == RelayState.AUTH

    /** One line for a banner; null when there is nothing to say. */
    val banner: String? get() = when {
        localOnly -> null
        access == AccessState.REFUSED -> "This belfry does not let you in (yet)."
        !online -> if (queued > 0) "Offline · $queued waiting to be sent" else "Offline · showing what is on this device"
        !connected -> if (queued > 0) "Connecting… · $queued waiting to be sent" else "Connecting…"
        signerTrouble -> "Your signer is not answering. Messages wait on this device."
        else -> null
    }
}

/** Someone who came in through a guest link: the channel they joined, and whether they saved their account (e-mail). */
data class GuestInfo(
    val channel: String,
    val saved: Boolean,
    /** A co-host link (an event's) used before saving an e-mail: used again once the e-mail is proven. */
    val coHost: String? = null,
)

/** A photo or file to send with a message. */
class NewAttachment(val name: String, val mime: String, val bytes: ByteArray, val width: Int? = null, val height: Int? = null)

data class PlaceResults(val places: List<Place>, val attribution: String?)

/**
 * A failure of an action, with a fixed message that is safe to show ([message] is never text
 * from the server or from a URL). [code] is the server's error key when there is one
 * (`guest_not_saved`, `agent`, `not_member`, `last_admin`, `taken`, `network`…).
 */
class ActionException(val code: String, message: String) : Exception(message)

/**
 * One signed-in member's connection to their belfry: the event log, the relays and their
 * subscriptions, the outbox, direct messages, and the belfry's HTTP API. The app talks only to
 * this. `localOnly = true` is the demo: no network at all, everything "published" on the device.
 *
 * Lifecycle: construct (loads the log from the store; [world] is valid at once) → [start] →
 * [wake] when the app comes to the foreground → [setOnline] on connectivity changes → [stop].
 * All suspend functions may be called from the main thread: they move work off it themselves.
 */
class Session(
    community: Community,
    val store: Store,
    val signer: Signer,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    val localOnly: Boolean = false,
    private val clock: () -> Long = ::unixNow,
    guest: GuestInfo? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    @Volatile var community: Community = community
        private set

    /** My public key (hex). */
    val me: String = signer.pubkey

    /** Told when the community document was refreshed or the guest state changed (the app saves them). */
    @Volatile var onRecordChanged: ((Community, GuestInfo?) -> Unit)? = null

    private val logLock = Any()
    private val rebuildLock = Any()
    private val log = ArrayList<Event>()
    @Volatile private var extraAdmins: Set<String> = emptySet()
    @Volatile private var bridge: String? = null
    @Volatile private var groupsKey: String? = null
    private var lastCreatedAt = 0L

    private val _world: MutableStateFlow<World>
    /** Everything the screens show (a new immutable snapshot after every change). */
    val world: StateFlow<World> get() = _world

    private val _status = MutableStateFlow(Status(localOnly = localOnly))
    val status: StateFlow<Status> = _status

    private val _guest = MutableStateFlow(guest)
    /** Not null while I am here as a guest of one channel. */
    val guest: StateFlow<GuestInfo?> = _guest

    private val dm: DirectMessages
    /** Direct messages and e-mail. */
    val dms: StateFlow<DmSnapshot> get() = dm.snapshot

    private val groupsRelay: Relay? = if (localOnly) null else Relay(community.groupsRelay, http, scope)
    private val mainRelay: Relay? = if (localOnly) null else Relay(community.mainRelay, http, scope)
    private fun relay(t: Target): Relay? = if (t == Target.GROUPS) groupsRelay else mainRelay

    private sealed interface Incoming {
        class Ev(val e: Event) : Incoming
        class Eose(val target: Target) : Incoming
    }
    private val incoming = Channel<Incoming>(Channel.UNLIMITED)
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val flushWanted = Channel<Unit>(Channel.CONFLATED)
    // (declared before init, which starts its reader)
    private val draftWrites = Channel<Pair<String, String?>>(Channel.UNLIMITED) // store key → what to keep (null: forget)
    private val jobs = ArrayList<Job>()
    @Volatile private var started = false
    @Volatile private var stopped = false
    @Volatile private var firstChannelTried = false
    private val enqueueLock = Mutex()
    private val flushLock = Mutex()
    @Volatile private var again = false
    // declared before init: the first world is built there
    /** The register's answers this session (key → member | agent | guest). */
    private val kinds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val askedWhois: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** An old belfry without `/whois` (404): join and label events decide. */
    @Volatile private var noWhois = false
    @Volatile private var lastRecheck = 0L

    private val uploaded = HashMap<String, String>() // blobKey → URL, so a retry does not upload twice
    private val demoFollows = HashMap<String, String>()

    init {
        val all = store.allEvents()
        log.addAll(all)
        extraAdmins = parseJsonOrNull(store.get(K_ADMINS) ?: "").arr()?.mapNotNull { it.str() }?.filter(Hex::isKey)?.toSet() ?: emptySet()
        bridge = store.get(K_BRIDGE)?.takeIf(Hex::isKey)
        groupsKey = store.get(K_GROUPS_KEY)?.takeIf(Hex::isKey)
        lastCreatedAt = maxOf(
            all.filter { it.pubkey == me }.maxOfOrNull { it.createdAt } ?: 0,
            store.outbox().filter { it.signed == null }.maxOfOrNull { it.createdAt } ?: 0,
        )
        _world = MutableStateFlow(buildWorld())
        dm = DirectMessages(store, Host())
        dm.myAddressProvider = { _world.value.myAddress }
        dm.offer(all)
        countOutbox()
        jobs += scope.launch(Dispatchers.Default) { for (u in dirty) { rebuild(); delay(REBUILD_MS) } }
        jobs += scope.launch(Dispatchers.Default) { intake() }
        jobs += scope.launch(Dispatchers.Default) { for (u in flushWanted) flush() }
        jobs += scope.launch(Dispatchers.IO) { writeDrafts() }
    }

    // ---- the world ---------------------------------------------------------------------------------

    private fun ctx(): WorldContext = WorldContext(
        me = me, domain = community.domain, communityName = community.name,
        communityAdmins = community.admins.toSet() + extraAdmins, groupsKey = groupsKey, bridgeKey = bridge, zone = zone,
        groupsRelay = community.groupsRelay, agents = community.agents, kinds = HashMap(kinds),
    )

    /** The next snapshot; what PREV worked out for the channels nothing arrived in is taken over (see [World]). */
    private fun buildWorld(prev: World? = null, box: List<OutboxItem> = store.outbox()): World {
        val events = synchronized(logLock) { ArrayList(log) }
        return World(events, box, ctx(), prev)
    }

    private fun rebuild() {
        val box = store.outbox()
        val w = synchronized(rebuildLock) { buildWorld(_world.value, box).also { _world.value = it } }
        countOutbox(box)
        askWhoisAbout(w)
    }

    // ---- who is who (whois.py): the register says member / agent / guest ------------------------------

    /** New people brought in by a link or as an agent: asked once per session, in one batch. */
    private fun askWhoisAbout(w: World) {
        if (localOnly || noWhois || !started) return
        val fresh = w.guestJoinKeys.filter { askedWhois.add(it) }
        if (fresh.isNotEmpty()) launchSafely { refreshWhois(fresh) }
    }

    /**
     * Asks the register what KEYS are (batched by 500). A key left out holds no active pass: its
     * events decide again. Offline or refused: the answers so far stay, events decide the rest.
     */
    suspend fun refreshWhois(keys: Collection<String>) {
        if (localOnly || noWhois || keys.isEmpty()) return
        val got = try {
            api().whois(signer, keys)
        } catch (e: ApiException) {
            if (e.status == 404) noWhois = true
            return
        } catch (e: IOException) {
            return
        }
        var changed = false
        for (k in keys) {
            val v = got[k]
            if (v == null) { if (kinds.remove(k) != null) changed = true } else if (kinds.put(k, v) != v) changed = true
        }
        if (changed) markDirty()
    }

    /**
     * A profile or a members list opened: what the register says about these people now, and
     * belfry.json revalidated (at most every 30 s; agents and admins live there).
     */
    suspend fun recheckPeople(keys: Collection<String>) {
        if (localOnly) return
        val ask = keys.filter { it in _world.value.guestJoinKeys || kinds.containsKey(it) }
        refreshWhois(ask)
        val now = System.currentTimeMillis()
        if (now - lastRecheck > RECHECK_MS) { lastRecheck = now; refreshCommunity() }
    }

    private fun markDirty() { dirty.trySend(Unit) }

    private fun countOutbox(box: List<OutboxItem> = store.outbox()) {
        val queued = box.count { it.status == OutboxStatus.QUEUED }
        val failed = box.count { it.status == OutboxStatus.FAILED }
        setStatus { it.copy(queued = queued, failed = failed) }
    }

    private fun setStatus(f: (Status) -> Status) {
        while (true) {
            val old = _status.value
            if (_status.compareAndSet(old, f(old))) return
        }
    }

    /** The current time (Unix seconds) as this session sees it; pass it to `World` functions that take `now`. */
    fun now(): Long = clock()

    private fun addToLog(events: List<Event>) {
        if (events.isEmpty()) return
        val fresh = store.addEvents(events)
        if (fresh.isEmpty()) return
        synchronized(logLock) { log.addAll(fresh) }
        dm.offer(fresh)
        markDirty()
        if (fresh.any { it.kind == 1059 || it.kind == 4 }) launchSafely { dm.process() }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch(Dispatchers.Default) {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* next wake-up tries again */ }
        }
    }

    private suspend fun intake() {
        while (scope.isActive) {
            val first = incoming.receive()
            val batch = arrayListOf(first)
            while (batch.size < 1000) batch.add(incoming.tryReceive().getOrNull() ?: break)
            addToLog(batch.filterIsInstance<Incoming.Ev>().map { it.e })
            if (batch.any { it is Incoming.Eose && it.target == Target.GROUPS }) onGroupsEose()
        }
    }

    private suspend fun onGroupsEose() {
        setStatus { it.copy(access = AccessState.IN) }
        // A new belfry's groups relay has no channel yet: the first member in opens #general. Once.
        if (firstChannelTried) return
        val any = synchronized(logLock) { log.any { it.kind == 39000 } }
        if (any) return
        firstChannelTried = true
        send(Actions.createGeneral(community.name))
    }

    /** Waits until everything that arrived is decrypted and [world] is rebuilt (tests, screenshots, the demo). */
    suspend fun settle() = withContext(Dispatchers.Default) {
        dm.process()
        rebuild()
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    /** Connects (or, in the demo, makes sure there is a #general) and starts sending the outbox. Call once. */
    fun start() {
        if (started || stopped) return
        started = true
        launchSafely { dm.process() }
        if (localOnly) {
            setStatus { it.copy(access = AccessState.IN) }
            launchSafely {
                if (synchronized(logLock) { log.none { it.kind == 39000 } }) send(Actions.createGeneral(community.name))
                flush()
            }
            return
        }
        for (t in Target.entries) {
            val r = relay(t) ?: continue
            r.authSigner = signer
            r.onEvent = { _, e -> incoming.trySend(Incoming.Ev(e)) }
            r.onEose = { sub -> if (sub == SUB) { reopens[t.ordinal] = 0; incoming.trySend(Incoming.Eose(t)) } }
            r.onClosed = { sub, reason ->
                if (t == Target.GROUPS) noteRefusal(reason)
                if (sub == SUB) reopen(t, reason)
            }
            r.onAuthRefused = { reason -> if (t == Target.GROUPS) noteRefusal(reason) }
            r.onAuthenticated = { requestFlush() }
            r.onConnected = { requestFlush() }
            r.onDisconnect = { r.subscribe(SUB, filters(t)) } // catch up from the last event seen
            r.subscribe(SUB, filters(t))
            jobs += scope.launch { r.state.collect { s -> setStatus { if (t == Target.GROUPS) it.copy(groups = s) else it.copy(main = s) } } }
            if (_status.value.online) r.keepConnected()
        }
        jobs += scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(WATCHDOG_MS)
                if (_status.value.online) for (t in Target.entries) relay(t)?.let { if (!it.isUp) it.wake() }
                if (store.outbox().any { it.status != OutboxStatus.FAILED }) requestFlush()
            }
        }
        launchSafely { refreshCommunity() }
        requestFlush()
    }

    private val reopens = IntArray(Target.entries.size)

    /**
     * The relay ended the live subscription for a reason that is not a refusal: without a new one
     * nothing arrives until a restart (web sync.ts, 2026-11-11). Opened again while the socket
     * lives, 2 s later (then 4, 8… up to a minute if the relay keeps closing it). `auth-required`
     * is not handled here: the relay asks for NIP-42 and the subscriptions are sent again after it.
     */
    private fun reopen(t: Target, reason: String) {
        if (Access.isRefusal(reason) || reason.startsWith("auth-required")) return
        val n = reopens[t.ordinal].also { reopens[t.ordinal] = minOf(it + 1, 5) }
        scope.launch(Dispatchers.Default) {
            delay(minOf(60_000L, 2_000L shl n))
            val r = relay(t) ?: return@launch
            if (!stopped && r.isUp && _status.value.access != AccessState.REFUSED) r.subscribe(SUB, filters(t))
        }
    }

    /** A relay socket that says it is open but does not answer is dropped and opened again at once. */
    private fun probe() {
        for (t in Target.entries) {
            val r = relay(t) ?: continue
            launchSafely { if (r.isUp && !r.probe()) r.wake() }
        }
    }

    /** A wake-up: the app is back in the foreground. Reconnects now and sends what waits. */
    fun wake() {
        if (!started || stopped || localOnly) return
        if (_status.value.online) { for (t in Target.entries) relay(t)?.wake(); probe() }
        launchSafely { dm.process() }
        requestFlush()
    }

    /** The device's connectivity changed. Offline drops the sockets (they are dead anyway). */
    fun setOnline(online: Boolean) {
        if (_status.value.online == online) return
        setStatus { it.copy(online = online) }
        if (!started || stopped || localOnly) return
        for (t in Target.entries) {
            val r = relay(t) ?: continue
            if (online) { r.subscribe(SUB, filters(t)); r.keepConnected(); r.wake() } else r.stop()
        }
        if (online) requestFlush()
    }

    /** Closes the connections and stops the background work. The outbox stays in the store. */
    fun stop() {
        stopped = true
        for (t in Target.entries) relay(t)?.stop()
        synchronized(jobs) { jobs.forEach { it.cancel() }; jobs.clear() }
        (signer as? BunkerSigner)?.disconnect()
    }

    /** Ends this device's session with the belfry (best effort, 3 s), stops, and wipes everything stored. */
    suspend fun signOut() {
        if (!localOnly) {
            val b = signer as? BunkerSigner
            if (b != null) withTimeoutOrNull(3_000) {
                try { api().revokeSession(signer, b.bunker.remotePubkey) } catch (e: Exception) { /* offline: an admin or the member can end it later */ }
            }
        }
        stop()
        withContext(Dispatchers.IO) { store.wipe() }
    }

    private fun noteRefusal(reason: String) {
        if (Access.isRefusal(reason)) setStatus { it.copy(access = AccessState.REFUSED) }
    }

    private fun latest(pred: (Event) -> Boolean): Long = synchronized(logLock) {
        var at = 0L
        for (e in log) if (e.createdAt > at && pred(e)) at = e.createdAt
        at
    }

    private fun filters(t: Target): List<JsonObject> {
        if (t == Target.GROUPS) {
            val kinds = KINDS_GROUPS.toSet()
            return listOf(Filters.of(kinds = KINDS_GROUPS, since = maxOf(0, latest { it.kind in kinds } - 3600), limit = 2000))
        }
        val kinds = KINDS_MAIN.toSet()
        val since = maxOf(0, latest { it.kind in kinds } - 3600)
        val wraps = maxOf(0, latest { it.kind == 1059 } - 3 * 86400)
        val old = maxOf(0, latest { it.kind == 4 } - 3600)
        val extras = maxOf(0, latest { it.kind == 30078 && it.hasTag("d", World.PROFILE_D) } - 3600)
        return listOf(
            Filters.of(kinds = KINDS_MAIN, since = since, limit = 2000),
            Filters.of(kinds = listOf(30078), since = extras, limit = 2000, tags = mapOf("d" to listOf(World.PROFILE_D))),
            Filters.of(kinds = listOf(1059), since = wraps, limit = 1000, tags = mapOf("p" to listOf(me))),
            Filters.of(kinds = listOf(4), since = old, limit = 500, tags = mapOf("p" to listOf(me))),
            Filters.of(kinds = listOf(4), authors = listOf(me), since = old, limit = 500),
        )
    }

    /** belfry.json again (admins, name) and the well-known keys; the cached copies are used offline. */
    suspend fun refreshCommunity() {
        if (localOnly) return
        try {
            // always asked again (no-cache); 304 when the copy here is the belfry's current one
            when (val got = Discovery.revalidate(community.domain, http, store.get(K_DISCOVERY_ETAG))) {
                is Revalidated.NotModified -> {}
                is Revalidated.Fresh -> {
                    if (got.community != community) {
                        community = got.community
                        onRecordChanged?.invoke(got.community, _guest.value)
                    }
                    if (got.etag != null) store.set(K_DISCOVERY_ETAG, got.etag) else store.remove(K_DISCOVERY_ETAG)
                    extraAdmins = emptySet()
                    store.remove(K_ADMINS)
                }
            }
        } catch (e: IOException) {
            // offline: what we have
        }
        try {
            Discovery.bridgeKey(community.domain, http)?.let { bridge = it; store.set(K_BRIDGE, it) }
            Discovery.groupsKey(community.domain, http)?.let { groupsKey = it; store.set(K_GROUPS_KEY, it) }
        } catch (e: IOException) {
            // offline: the stored ones
        }
        markDirty()
    }

    // ---- drafts (unsent text per channel / thread, on this device) -----------------------------------

    /** The draft kept for KEY ([ComposerDraft.channel], [ComposerDraft.thread]), or null. */
    suspend fun draft(key: String): ComposerDraft? = withContext(Dispatchers.IO) {
        ComposerDraft.parse(store.get(ComposerDraft.storeKey(key)), clock())
    }

    /** Keeps (blank text: forgets) the draft of KEY. Returns at once; writes happen in order off the caller's thread. */
    fun saveDraft(key: String, text: String, picked: List<Pair<String, String>> = emptyList(), channels: List<Pair<String, String>> = emptyList()) {
        if (stopped) return
        val d = if (text.isBlank()) null else ComposerDraft(text, picked.filter { text.contains("@" + it.first) }, channels.filter { text.contains(it.first) }, clock())
        draftWrites.trySend(ComposerDraft.storeKey(key) to d?.toJsonString())
    }

    /** The fields kept for the form KEY ([FormDrafts]), or null. */
    suspend fun formDraft(key: String): Map<String, String>? = withContext(Dispatchers.IO) {
        FormDrafts.parse(store.get(FormDrafts.storeKey(key)), clock())
    }

    /** Keeps the fields of the form KEY (null: forgets them, once it is sent). Returns at once. */
    fun saveFormDraft(key: String, fields: Map<String, String>?) {
        if (stopped) return
        draftWrites.trySend(FormDrafts.storeKey(key) to fields?.let { FormDrafts.toJsonString(it, clock()) })
    }

    private suspend fun writeDrafts() {
        for ((key, d) in draftWrites) {
            try {
                if (d == null) store.remove(key) else store.set(key, d)
            } catch (e: Exception) {
                // a full disk: no draft
            }
        }
    }

    // ---- branding (admins) ---------------------------------------------------------------------------

    /** The belfry's logo, cover and colours as this device knows them. */
    val branding: Branding get() = community.branding

    /** An admin of the belfry sets its branding ("" clears a value). Throws [ActionException] (`not_admin`, `invalid`, `no_key`). */
    suspend fun saveBranding(b: Branding): Branding {
        val saved = if (localOnly) b else call { it.saveBranding(signer, b) }
        community = community.withBranding(saved)
        onRecordChanged?.invoke(community, _guest.value)
        rebuild()
        return saved
    }

    // ---- writing: the outbox (SPEC 5) ---------------------------------------------------------------

    private fun newLocalId(): String = "local-" + Random.hex(8)

    /**
     * Queues events: saved on this device BEFORE anything goes on the network, shown at once,
     * published when the relay answers. ATTACHMENTS go with the first draft. Returns the local ids
     * (the event ids while they are pending). Never throws for network reasons.
     */
    suspend fun send(drafts: List<Draft>, attachments: List<NewAttachment> = emptyList()): List<String> = withContext(Dispatchers.Default) {
        val ids = ArrayList<String>()
        enqueueLock.withLock {
            drafts.forEachIndexed { i, d ->
                val atts = if (i == 0) attachments.map { a ->
                    val key = Random.hex(8)
                    store.putBlob(key, a.bytes)
                    Attachment(key, a.name, a.mime.ifEmpty { "application/octet-stream" }, a.bytes.size.toLong(), a.width, a.height)
                } else emptyList()
                lastCreatedAt = Outbox.nextCreatedAt(lastCreatedAt, clock())
                val item = OutboxItem(
                    localId = newLocalId(), target = d.target, kind = d.kind, content = d.content,
                    tags = d.tags + listOf(Actions.CLIENT_TAG), createdAt = lastCreatedAt, attachments = atts,
                )
                store.putOutbox(item)
                ids.add(item.localId)
            }
        }
        rebuild()
        requestFlush()
        ids
    }

    suspend fun send(draft: Draft, attachments: List<NewAttachment> = emptyList()): String = send(listOf(draft), attachments).first()

    /** Saves an event that is signed already (a gift wrap) and publishes it when the relay answers. */
    suspend fun enqueueSigned(target: Target, event: Event, dm: String? = null): String = withContext(Dispatchers.Default) {
        val item = OutboxItem(newLocalId(), target, event.kind, event.content, event.tags, event.createdAt, signed = event, dm = dm)
        store.putOutbox(item)
        countOutbox()
        requestFlush()
        item.localId
    }

    /** "Retry" on a message that was refused: back in the queue. */
    suspend fun retry(localId: String) = withContext(Dispatchers.Default) {
        val item = store.outbox().firstOrNull { it.localId == localId } ?: return@withContext
        store.putOutbox(item.copy(status = OutboxStatus.QUEUED, error = null))
        rebuild()
        requestFlush()
    }

    /** Gives up on a queued or refused item: it is removed from the outbox (and from the feed). */
    suspend fun discard(localId: String) = withContext(Dispatchers.Default) {
        val item = store.outbox().firstOrNull { it.localId == localId } ?: return@withContext
        store.dropOutbox(localId)
        item.attachments.forEach { store.dropBlob(it.blobKey) }
        rebuild()
    }

    /** The bytes of an attachment still waiting in the outbox (`world.pending(id).attachments[i].blobKey`). */
    fun blob(blobKey: String): ByteArray? = store.blob(blobKey)

    /** The bytes behind a demo-only picture address (`https://blob.local/<key>`), else null. */
    fun localBlob(url: String): ByteArray? = if (url.startsWith(LOCAL_BLOB)) store.blob(url.removePrefix(LOCAL_BLOB)) else null

    private fun requestFlush() { flushWanted.trySend(Unit) }

    /** One run of the flush loop (normally triggered by itself; public for tests and "send now"). */
    suspend fun flush() {
        if (stopped) return
        if (!flushLock.tryLock()) { again = true; return }
        try {
            do {
                again = false
                val items = store.outbox().filter { !held(it) }
                if (items.none { it.status == OutboxStatus.QUEUED }) break
                if (!localOnly && !_status.value.online) break
                val before = items.associateBy { it.localId }
                val signedNow = HashMap<String, Event>()
                val handled = HashSet<String>()
                val result = Outbox.run(items) { item ->
                    val outcome = sendOne(item, signedNow)
                    if (outcome is SendOutcome.Ok) { handled.add(item.localId); sent(item, outcome.event ?: item.signed) }
                    outcome
                }
                for (s in result.sent) if (s.item.localId !in handled) sent(s.item, signedNow[s.item.localId] ?: s.item.signed) // duplicates: out already
                for (left in result.items) if (before[left.localId] != left) store.putOutbox(left)
                for (f in result.failed) dm.onFailed(f)
                rebuild()
                if (result.sent.isNotEmpty()) retryStep = 0
                if (result.stalled.isNotEmpty()) { retrySoon(); break }
            } while (again)
        } finally {
            flushLock.unlock()
        }
    }

    // A send that failed for now (a dropped socket, a signer that was reconnecting) is tried again
    // within seconds, not at the next watchdog tick: 1.5 s, 3 s, 6 s, then every 12 s (web sync.ts).
    @Volatile private var retryStep = 0
    private val retryPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun retrySoon() {
        if (stopped || !retryPending.compareAndSet(false, true)) return
        val ms = minOf(12_000L, 1_500L shl minOf(retryStep, 4))
        retryStep += 1
        scope.launch(Dispatchers.Default) {
            try { delay(ms) } finally { retryPending.set(false) }
            if (!stopped) requestFlush()
        }
    }

    /** The demo keeps one seeded item "waiting for connection" so that state can be seen. */
    private fun held(item: OutboxItem): Boolean = localOnly && item.tags.any { it.size >= 2 && it[0] == "demo" && it[1] == "hold" }

    private fun sent(item: OutboxItem, event: Event?) {
        store.dropOutbox(item.localId)
        if (!localOnly) item.attachments.forEach { store.dropBlob(it.blobKey); synchronized(uploaded) { uploaded.remove(it.blobKey) } }
        if (item.signed == null && event != null) {
            addToLog(listOf(event))
            if (localOnly) synthesizeRelayEvents(event)
        }
        dm.onSent(item)
        markDirty()
    }

    private suspend fun sendOne(item: OutboxItem, signedNow: MutableMap<String, Event>): SendOutcome {
        item.signed?.let { ev ->
            if (localOnly) return SendOutcome.Ok(ev)
            return publish(item.target, ev)
        }
        var content = item.content
        val tags = ArrayList<Tag>(item.tags)
        for (a in item.attachments) {
            val bytes = store.blob(a.blobKey) ?: continue
            val url = if (localOnly) LOCAL_BLOB + a.blobKey else try {
                synchronized(uploaded) { uploaded[a.blobKey] } ?: api().upload(signer, bytes, a.name, a.mime).also { u -> synchronized(uploaded) { uploaded[a.blobKey] = u } }
            } catch (e: SignerTimeout) {
                setStatus { it.copy(signerTrouble = true) }
                return SendOutcome.Error("timeout: the signer did not answer")
            } catch (e: Exception) {
                // BelfryApi.upload says "upload failed (<status>)": kept as is, the outbox reads the status
                val said = e.message.orEmpty()
                return SendOutcome.Error(if (said.startsWith("upload failed")) said else "upload failed" + (said.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""))
            }
            val imeta = arrayListOf("imeta", "url $url", "m ${a.mime}", "size ${a.size}")
            if (!localOnly) imeta.add("x ${sha256Hex(bytes)}")
            if (a.width != null && a.height != null) imeta.add("dim ${a.width}x${a.height}")
            imeta.add("alt ${a.name}")
            tags.add(imeta)
            content = if (content.isNotEmpty()) content + "\n" + url else url
        }
        val ev = try {
            signer.sign(EventTemplate(item.kind, content, tags, item.createdAt)).also { setStatus { s -> if (s.signerTrouble) s.copy(signerTrouble = false) else s } }
        } catch (e: SignerTimeout) {
            setStatus { it.copy(signerTrouble = true) }
            return SendOutcome.Error("timeout: the signer did not answer")
        } catch (e: Exception) {
            return SendOutcome.Error(e.message ?: "the signer refused")
        }
        signedNow[item.localId] = ev
        if (localOnly) return SendOutcome.Ok(ev)
        return publish(item.target, ev)
    }

    private suspend fun publish(target: Target, ev: Event): SendOutcome {
        val r = relay(target) ?: return SendOutcome.Error("timeout: no relay")
        return try {
            r.publish(ev, PUBLISH_TIMEOUT_MS)
            SendOutcome.Ok(ev)
        } catch (e: RelayException) {
            if (e.reason.startsWith("restricted") && target == Target.GROUPS) noteRefusal(e.reason)
            // no answer on a socket that says it is open: it is dead (a proxy restart can leave it so).
            // Drop it and open a new one now; the outbox tries again on that one.
            if (e.reason == "timeout: the relay did not answer" && r.isUp) {
                r.close()
                r.subscribe(SUB, filters(target))
                r.wake()
            }
            SendOutcome.Error(e.reason)
        } catch (e: SignerTimeout) {
            SendOutcome.Error("timeout: the signer did not answer")
        }
    }

    // In the demo there is no relay to turn 9007/9002 into 39000 metadata and member lists.
    private fun synthesizeRelayEvents(ev: Event) {
        val h = ev.tag("h") ?: return
        if (ev.kind != 9007 && ev.kind != 9002) return
        val meta = ev.tags.filter { it.isNotEmpty() && it[0] in setOf("name", "about", "picture", "closed", "open", "prompt") }
        val out = ArrayList<Event>()
        if (ev.kind == 9002 || meta.isNotEmpty()) out.add(Event("meta-" + ev.id, ev.pubkey, ev.createdAt, 39000, listOf(listOf("d", h)) + meta, ""))
        if (ev.kind == 9007) out.add(Event("members-" + ev.id, ev.pubkey, ev.createdAt, 39002, listOf(listOf("d", h), listOf("p", ev.pubkey)), ""))
        addToLog(out)
    }

    // ---- the common actions ---------------------------------------------------------------------------

    /**
     * Posts a message. PICKED are the people picked with @ in the composer (name → pubkey): each
     * "@Name" becomes a mention. ANNOUNCE only counts from a channel admin. Returns the local id.
     */
    suspend fun postMessage(groupId: String, text: String, files: List<NewAttachment> = emptyList(), topic: String? = null, announce: Boolean = false, picked: List<Pair<String, String>> = emptyList()): String {
        val d = Actions.postMessage(groupId, Mentions.substitute(text, picked).trim(), topic, announce)
        // an event's URL in the text: its `a` tag too, so every client shows the card
        val a = _world.value.eventTagsFor(d.content, community.mainRelay) { events.knownCoord(it) }
        return send(d.copy(tags = d.tags + a), files)
    }

    /** A reply in the thread under ROOT (a channel message). */
    suspend fun replyInThread(groupId: String, root: Event, text: String, files: List<NewAttachment> = emptyList(), picked: List<Pair<String, String>> = emptyList()): String =
        send(Actions.postThreadReply(groupId, root, Mentions.substitute(text, picked).trim()), files)

    /** Edits my own message or reply (kind 1010); its photos and files are kept. */
    suspend fun editMessage(groupId: String, m: Message, text: String, picked: List<Pair<String, String>> = emptyList()): String =
        send(Actions.editMessage(groupId, m, Mentions.substitute(text, picked).trim()))

    /** Deletes my message: one still in the outbox is simply dropped. */
    suspend fun deleteMessage(groupId: String, id: String) {
        if (_world.value.isPending(id)) discard(id) else send(Actions.deleteMessage(groupId, id))
    }

    /** Adds my reaction, or takes it back when I reacted with this emoji already. */
    suspend fun toggleReaction(groupId: String, messageId: String, author: String, emoji: String) {
        val w = _world.value
        val mine = w.reactionsOf(groupId, messageId).firstOrNull { it.emoji == emoji }?.mine
        if (mine != null && w.isPending(mine.id)) { discard(mine.id); return }
        Actions.toggleReaction(groupId, messageId, author, emoji, mine, false)?.let { send(it) }
    }

    /** Opening a channel tells the others I was there, at most every 6 hours per channel (SPEC 11). */
    suspend fun markSeen(groupId: String) {
        val w = _world.value
        if (w.group(groupId) == null || !w.maySee(groupId)) return
        val key = K_SEEN_SENT + groupId
        val lastHere = store.get(key)?.toLongOrNull() ?: 0
        val now = clock()
        if (!Actions.shouldMarkSeen(now, w.mySeen(groupId), lastHere)) return
        store.set(key, now.toString())
        send(Actions.seen(groupId))
    }

    /** A new channel; returns its id. */
    suspend fun createChannel(name: String, about: String): String {
        val id = Actions.newChannelId(name)
        send(Actions.createChannel(id, name.trim(), about.trim()))
        return id
    }

    /** A new pod under PARENT (a channel or a pod); returns its id. */
    suspend fun createPod(name: String, about: String, parent: String, access: PodAccess = PodAccess()): String {
        val id = Actions.newPodId(name)
        send(Actions.createPod(id, name.trim(), about.trim(), parent, access))
        return id
    }

    /** A new event posted in CHANNEL; returns its coordinate (`world.calEvent(coord)`). */
    suspend fun createEvent(n: NewEvent, channel: String): String {
        val (coord, drafts) = Actions.createEvent(n, channel, me, community.groupsRelay, community.mainRelay)
        send(drafts)
        return coord
    }

    suspend fun rsvp(e: CalEvent, status: Rsvp) {
        send(Actions.rsvp(e, status, me, _world.value.isPending(e.ev.id)))
    }

    /** A new proposal under PARENT; returns its chat's id. */
    suspend fun createProposal(n: NewProposal, parent: String): String {
        val (id, drafts) = Actions.createProposal(n, parent, me, community.groupsRelay, community.mainRelay, clock())
        send(drafts)
        return id
    }

    suspend fun reviseProposal(p: Proposal, title: String, summary: String, text: String, note: String) {
        send(Actions.reviseProposal(p, title, summary, text, note, community.groupsRelay, community.mainRelay, clock()))
    }

    /** A drop box link for a channel (DAYS 1, 7 or 30; LOGIN: uploaders must sign in). Returns the address to share. */
    suspend fun createDropLink(groupId: String, days: Int, login: Boolean): String {
        val (token, draft) = Actions.createDropLink(groupId, days.toDouble(), login, clock())
        send(draft)
        return _world.value.dropUrl(token)
    }

    /** My name and about (kind 0), the rest of my profile kept. */
    suspend fun saveProfile(name: String, about: String, nip05: String? = null) {
        send(Actions.saveProfile(_world.value.myProfileJson, name, about, nip05))
    }

    /** My tags and place (kind 30078 `belfry:profile`). */
    suspend fun saveExtras(tags: List<String>, share: Share, place: Place?) {
        send(Actions.saveExtras(tags, share, place))
    }

    /**
     * A new profile picture: BYTES are the picture already resized by the app (JPEG, 512 px at
     * most); it is uploaded to the file server, then my profile is republished with it.
     */
    suspend fun setAvatar(bytes: ByteArray, mime: String = "image/jpeg") {
        val url = uploadPicture(bytes, "avatar.jpg", mime)
        send(Actions.setAvatar(_world.value.myProfileJson, url))
    }

    /** Uploads a picture (resized by the app) to the file server and returns its URL; the demo keeps it on the device. */
    suspend fun uploadPicture(bytes: ByteArray, name: String, mime: String = "image/jpeg"): String = withContext(Dispatchers.Default) {
        if (localOnly) {
            val key = Random.hex(8)
            store.putBlob(key, bytes)
            LOCAL_BLOB + key
        } else try {
            api().upload(signer, bytes, name, mime)
        } catch (e: Exception) {
            throw ActionException("upload", "The picture could not be uploaded. Check your connection and try again.")
        }
    }

    // ---- direct messages and e-mail ------------------------------------------------------------------

    /**
     * Sends TEXT in a conversation (see [DmSnapshot]); SUBJECT is needed for a new e-mail
     * ("m:<address>"). Returns the conversation it is in now. Throws [ActionException].
     */
    suspend fun sendDm(conv: String, text: String, subject: String? = null): String { lastDmSentMs = System.currentTimeMillis(); return dmCall { dm.send(conv, text, subject) } }

    /** When this phone last sent a DM or e-mail (ms): a "dm" push right after it is my own copy (SPEC 19.19). */
    @Volatile var lastDmSentMs: Long = 0L
        private set

    /** Puts a refused direct message or e-mail back in the outbox. */
    suspend fun retryDm(rid: String) { lastDmSentMs = System.currentTimeMillis(); dmCall { dm.retry(rid) } }

    fun markRead(conv: String) = dm.markRead(conv)

    /** Where a new conversation with INPUT goes (a name, name@domain, key, npub or e-mail address); null = not understood. */
    suspend fun convFor(input: String): String? {
        val w = _world.value
        return dm.convFor(input, w.people.map { (pk, name) -> DmCandidate(pk, name, w.profiles[pk]?.nip05) })
    }

    /** Answers an e-mail card as the shared inbox channel (its admins only). Returns the email-id for `dms.value.report(id)`. */
    suspend fun sendAsChannel(channel: String, to: String, subject: String, text: String, inReplyTo: String?, cardId: String): String = dmCall {
        val name = _world.value.group(channel)?.name ?: channel
        val id = dm.sendAsChannel(channel, name, to, subject, text, inReplyTo, cardId)
        if (localOnly) {
            // the demo plays the bridge: the answer shows in the channel
            val key = groupsKey
            if (key != null) addToLog(listOf(Event("out-" + Random.hex(8), key, clock(), 9,
                listOf(listOf("h", channel), listOf("subject", subject), listOf("mail-to", to), listOf("sent-by", me), listOf("e", cardId, "", "reply"), listOf("client", "belfry-mail-out")), text)))
        }
        id
    }

    /** An e-mail's attachments with their bytes. */
    suspend fun attachmentsOf(d: Dm): List<MailPart> = withContext(Dispatchers.Default) { dm.attachmentsOf(d) }

    /** Asks the bridge again how my e-mails fared (also runs by itself after a send). */
    suspend fun checkReports() = try { dm.checkReports() } catch (e: Exception) { }

    private suspend fun <T> dmCall(block: suspend () -> T): T = withContext(Dispatchers.Default) {
        try { block() } catch (e: DmException) { throw ActionException("dm", e.message ?: "That did not work. Try again.") }
    }

    private inner class Host : DmHost {
        override val me: String get() = this@Session.me
        override val domain: String get() = community.domain
        override val blossom: String get() = community.blossom
        override val localOnly: Boolean get() = this@Session.localOnly
        override val signer: Signer get() = this@Session.signer
        override fun now(): Long = clock()
        override fun bridgeKey(): String? = bridge
        override fun realName(pubkey: String): String = _world.value.realName(pubkey)
        override fun outbox(): List<OutboxItem> = store.outbox()
        override suspend fun enqueueSigned(target: Target, event: Event, dm: String?): String = this@Session.enqueueSigned(target, event, dm)
        override suspend fun requeue(items: List<OutboxItem>) {
            for (o in items) store.putOutbox(o.copy(status = OutboxStatus.QUEUED, error = null))
            countOutbox()
            requestFlush()
        }
        override fun addLocalEvents(events: List<Event>) = addToLog(events)
        override suspend fun queryMain(filter: JsonObject): List<Event> = mainRelay?.query(listOf(filter)) ?: emptyList()
        override suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                res.body?.bytes() ?: ByteArray(0)
            }
        }
        override suspend fun nip05(name: String): String? = Discovery.nip05(name, community.domain, http)
        override fun later(delayMs: Long, block: suspend () -> Unit) {
            scope.launch(Dispatchers.Default) {
                delay(delayMs)
                if (!stopped) try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
            }
        }
    }

    // ---- the belfry's HTTP API (signed as me) ----------------------------------------------------------

    private fun api(): BelfryApi = BelfryApi(community, http)

    /** Belfry events: tickets, applications, the belfry's API (engine/Events.kt). */
    val events: EventsService by lazy { EventsService(this) }

    internal suspend fun <T> apiCall(block: suspend (BelfryApi) -> T): T = call(block)
    /** The demo: events "published" by others (the belfry's groups key issuing a ticket). */
    internal fun addLocal(events: List<Event>) = addToLog(events)

    private suspend fun <T> call(block: suspend (BelfryApi) -> T): T = withContext(Dispatchers.Default) {
        try {
            block(api())
        } catch (e: ApiException) {
            throw ActionException(e.code, e.message ?: "That did not work. Try again.")
        } catch (e: IOException) {
            throw ActionException("network", "No connection to the belfry. Check your connection and try again.")
        }
    }

    // The belfry refuses a NIP-98 authorization it has seen: two identical signed GETs in the same
    // second share an event id. Same-URL GETs are spaced out.
    private val getLock = Mutex()
    private val lastGet = HashMap<String, Long>()
    private suspend fun <T> spaced(url: String, block: suspend () -> T): T = getLock.withLock {
        val wait = (lastGet[url] ?: 0) + 1100 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try { block() } finally { lastGet[url] = System.currentTimeMillis() }
    }

    /**
     * A guest link for a channel. PEOPLE 1 or 50; LINK_DAYS 1, 7 or 30. AGENT: a link for one AI
     * agent that joins as my own (show its [GuestLink.agentUrl]). Throws [ActionException].
     */
    suspend fun createGuestLink(channel: String, people: Int = 1, linkDays: Int = 1, agent: Boolean = false): GuestLink {
        if (localOnly) {
            val token = "demo-" + Random.hex(12)
            return GuestLink("https://chat.${community.domain}/join/$token", "https://belfry.${community.domain}/invite/$token.md", if (agent) 0 else 30, if (people == 50 && !agent) 50 else 1, linkDays, null, agent)
        }
        return call { it.createGuestLink(signer, channel, people, linkDays, agent) }
    }

    /** What inviting someone who is here already did. */
    enum class Invited { ADMITTED, ASKED, SENT, GUEST }

    /**
     * Brings someone who is here already into GROUP_ID (the web's inviteMember): a guest of another
     * channel becomes this one's guest (`POST /channel-guests`, no message); a member gets one direct
     * message with the channel's link, and in a closed pod I administer is admitted first (9000);
     * a closed pod I don't administer: the message says it takes an application. Throws [ActionException].
     */
    suspend fun inviteMember(groupId: String, pubkey: String): Invited {
        val w = _world.value
        if (w.isGuest(pubkey)) {
            val got = if (localOnly) ChannelGuest(added = true, already = false, member = false) else call { it.addChannelGuest(signer, groupId, pubkey) }
            if (!got.member) return Invited.GUEST
        }
        val g = w.group(groupId)
        var state = Invited.SENT
        if (g != null && g.closed && pubkey !in g.members) {
            if (w.canAdmin(g)) { send(Actions.admit(groupId, pubkey)); state = Invited.ADMITTED } else state = Invited.ASKED
        }
        sendDm("n:$pubkey", w.inviteText(groupId, state == Invited.ASKED))
        return state
    }

    // ---- push notifications (docs/push.md) ----------------------------------------------------------

    private var demoNotifications = sh.belfry.core.push.NotificationSettings()

    /** Registers this phone (FCM [token], device key, name) for push. Throws [ActionException]. */
    suspend fun registerPush(token: String, keyB64: String, name: String): String {
        if (localOnly) return "demo"
        return call { it.addPushDevice(signer, token, keyB64, name) }
    }

    /** Stops pushes to [token]; best effort (sign-out goes on offline). */
    suspend fun unregisterPush(token: String) {
        if (localOnly) return
        try { call { it.removePushDevice(signer, token) } } catch (e: ActionException) { }
    }

    /** My notification settings. Throws [ActionException]. */
    suspend fun notificationSettings(): sh.belfry.core.push.NotificationSettings {
        if (localOnly) return demoNotifications
        return call { api -> spaced(community.apiBase + "/notifications") { api.notifications(signer) } }
    }

    /** Changes them ([set]: one switch or one channel's level) and returns them as now. Throws [ActionException]. */
    suspend fun setNotifications(set: kotlinx.serialization.json.JsonObject): sh.belfry.core.push.NotificationSettings {
        if (localOnly) { demoNotifications = demoNotifications.merged(set); return demoNotifications }
        return call { it.notifications(signer, set) }
    }

    /** My own agents and how many I may have. Throws [ActionException]. */
    suspend fun myAgents(): MyAgents {
        if (localOnly) return MyAgents(community.agents.filterValues { it == me }.keys.map { MyAgent(it, _world.value.realName(it), "") }, 1)
        return call { it.myAgents(signer) }
    }

    /**
     * MINE: a guest agent becomes my own (a member for as long as I am one); else my agent (or, for
     * an admin, anyone's) is removed. belfry.json's `agents` follows at once here, then from the
     * belfry. Codes: not_member, unknown, taken, not_guest_agent, not_inviter, limit (refused on
     * older belfries), not_agent, not_yours.
     */
    suspend fun setMyAgent(pubkey: String, mine: Boolean): MyAgents {
        val out = if (localOnly) null else call { it.setMyAgent(signer, pubkey, mine) }
        community = community.copy(agents = if (mine) community.agents + (pubkey to me) else community.agents - pubkey)
        rebuild()
        if (!localOnly) launchSafely { refreshCommunity() }
        return out ?: myAgents()
    }

    /** A guest link opened while signed in: this account gets the channel (no second account). */
    suspend fun joinWithAccount(token: String): JoinedAccount {
        if (localOnly) return JoinedAccount("general", true, false)
        val joined = call { it.joinWithAccount(signer, token) }
        if (joined.guest && _guest.value == null) setGuest(GuestInfo(joined.channel, false))
        relay(Target.GROUPS)?.let { it.subscribe(SUB, filters(Target.GROUPS)) } // the new channel's history
        return joined
    }

    /** A guest saves their account: a 6-digit code goes to EMAIL. Codes: `invalid`, `taken`, … */
    suspend fun guestSave(email: String) {
        if (localOnly) return
        call { it.guestSave(signer, email) }
    }

    /** The code from the e-mail; on success the guest's account is saved. */
    suspend fun guestVerify(email: String, code: String) {
        if (!localOnly) call { it.guestVerify(signer, email, code) }
        val g = _guest.value ?: return
        setGuest(g.copy(saved = true))
        // the co-host link waiting for this: now it makes them a host of the event
        val token = g.coHost ?: return
        joinWithAccount(token)
        _guest.value?.let { setGuest(it.copy(coHost = null)) }
    }

    private fun setGuest(g: GuestInfo?) {
        _guest.value = g
        onRecordChanged?.invoke(community, g)
    }

    /** Cities whose name starts with QUERY (2 characters at least). */
    suspend fun searchPlaces(query: String): PlaceResults {
        if (localOnly) return PlaceResults(People.demoPlaces(query), null)
        val r = call { it.searchPlaces(query) }
        return PlaceResults(r.places.map { Place(it.country, it.id, it.name, it.region, it.lat, it.lon, it.near) }, r.attribution)
    }

    /** A place that is not in the list: it takes the point of NEAR (a listed city). */
    suspend fun addPlace(name: String, country: String, near: Place): Place {
        if (localOnly) return Place(country, "b:demo", name, null, near.lat, near.lon, near.name)
        val nearId = near.id ?: throw ActionException("invalid", "Pick the nearest city from the list first.")
        val p = call { it.addPlace(signer, name, country, nearId) }
        return Place(p.country, p.id, p.name, p.region, p.lat, p.lon, p.near)
    }

    /**
     * Makes someone an admin of the belfry, or not any more (belfry admins only). Codes:
     * `not_admin`, `not_member`, `guest_not_saved`, `agent`, `last_admin`.
     */
    suspend fun setAdmin(pubkey: String, admin: Boolean) {
        if (!localOnly) call { it.setAdmin(signer, pubkey, admin) }
        val now = (community.admins.toSet() + extraAdmins).let { if (admin) it + pubkey else it - pubkey }
        if (localOnly) {
            community = community.copy(admins = now.toList())
        } else {
            extraAdmins = if (admin) extraAdmins + pubkey else extraAdmins - pubkey
            community = community.copy(admins = community.admins.filter { admin || it != pubkey })
            store.set(K_ADMINS, kotlinx.serialization.json.JsonArray(extraAdmins.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString())
            launchSafely { refreshCommunity() }
        }
        rebuild()
    }

    /** The channels I follow by e-mail (channel → `asap` or `monthly`) and the address they go to. */
    suspend fun follows(): Follows {
        if (localOnly) return Follows("you@example.org", HashMap(demoFollows))
        return call { api -> spaced(community.follows) { api.follows(signer) } }
    }

    /** CHOICE: `asap`, `monthly` or `stop`. Returns what is in force now (null = not following). */
    suspend fun setFollow(channel: String, choice: String): String? {
        if (localOnly) {
            if (choice == "stop") demoFollows.remove(channel) else demoFollows[channel] = choice
            return demoFollows[channel]
        }
        return call { it.setFollow(signer, channel, choice) }
    }

    /** Changes my address here (`<username>@<domain>`) and puts it in my profile. Codes: `taken`, `invalid`, `not_member`, `own_key`. */
    suspend fun setUsername(username: String): String {
        val w = _world.value
        val nip05 = if (localOnly) "${username.trim().lowercase()}@${community.domain}" else call { it.setUsername(signer, username.trim().lowercase()) }
        val p = w.profiles[me]
        send(Actions.saveProfile(w.myProfileJson, p?.name ?: "", p?.about ?: "", nip05))
        return nip05
    }

    /** Every session open on my key (this app on this phone is one of them). */
    suspend fun sessions(): List<SessionInfo> {
        if (localOnly) return emptyList()
        return call { api -> spaced(community.apiBase + "/me/sessions") { api.sessions(signer) } }
    }

    /** True when this row is the session this app runs on. */
    fun isThisSession(s: SessionInfo): Boolean = (signer as? BunkerSigner)?.bunker?.remotePubkey == s.bunkerPubkey

    suspend fun revokeSession(bunkerPubkey: String) {
        if (localOnly) return
        call { it.revokeSession(signer, bunkerPubkey) }
    }

    /** Admins: make the site a message links to (an nsite reference) the community's website. */
    suspend fun publishWebsite(ref: String, messageId: String) {
        if (localOnly) return
        call { it.publishWebsite(signer, ref, messageId) }
    }

    companion object {
        /** Today's kinds on the groups relay (the web chat's KINDS_GROUPS). */
        val KINDS_GROUPS = listOf(0, 39000, 39001, 39002, 9, 1111, 1010, 7, 9007, 9002, 9008, 9000, 9001, 9005, 9021, 9022, 1068, 1018, 5, 1985, 30078, 2100, 2101, 2102, 2103, 31925, 8534, 8535, 8536, 8538)
        val KINDS_MAIN = listOf(0, 31922, 31923, 31925, 30023, 30402, 5)
        /** Demo-only picture addresses: `https://blob.local/<blob key>` (see [localBlob]). */
        const val LOCAL_BLOB = "https://blob.local/"
        const val K_BRIDGE = "bridge-key"
        const val K_GROUPS_KEY = "groups-key"
        const val K_ADMINS = "extra-admins"
        const val K_DISCOVERY_ETAG = "belfry-json-etag"
        private const val RECHECK_MS = 30_000L
        private const val K_SEEN_SENT = "seen-sent:"
        private const val SUB = "main"
        private const val REBUILD_MS = 100L
        private const val WATCHDOG_MS = 15_000L
        /** Tests shorten it. */
        @Volatile var PUBLISH_TIMEOUT_MS = 15_000L
    }
}
