package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Random
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.enc.EncRules
import sh.belfry.core.dm.DirectMessages
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.dm.DmNewFile
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmCandidate
import sh.belfry.core.dm.DmException
import sh.belfry.core.dm.DmHost
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.dm.Report
import sh.belfry.core.mail.MailPart
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Buttons
import sh.belfry.core.model.MessageButton
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
import sh.belfry.core.model.GroupLock
import sh.belfry.core.model.GroupPresence
import sh.belfry.core.model.lockedGroups
import sh.belfry.core.model.presenceAfter
import sh.belfry.core.model.presenceAskable
import sh.belfry.core.model.presenceCandidates
import sh.belfry.core.model.inviteText
import sh.belfry.core.model.isGuest
import sh.belfry.core.model.everyonePins
import sh.belfry.core.model.eventTagsFor
import sh.belfry.core.model.ComposerDraft
import sh.belfry.core.model.FormDrafts
import sh.belfry.core.model.GuestMode
import sh.belfry.core.model.GuestTopic
import sh.belfry.core.net.GuestSignIn
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.net.Branding
import sh.belfry.core.net.Access
import sh.belfry.core.net.Reopen
import sh.belfry.core.net.ApiException
import sh.belfry.core.net.BelfryApi
import sh.belfry.core.net.Backoff
import sh.belfry.core.net.BunkerSigner
import sh.belfry.core.net.Connections
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
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str
import sh.belfry.core.nostr.unixNow
import sh.belfry.core.outbox.Attachment
import sh.belfry.core.outbox.ErrorClass
import sh.belfry.core.outbox.Outbox
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.SendOutcome
import sh.belfry.core.outbox.SendProgress
import sh.belfry.core.outbox.SendState
import sh.belfry.core.outbox.SendTrace
import sh.belfry.core.outbox.Target
import sh.belfry.core.store.Store
import sh.belfry.core.read.ReadState
import sh.belfry.core.read.Unread
import java.io.IOException
import java.time.ZoneId

/** Does the groups relay let this key in (SPEC 4)? */
/**
 * REFUSED = the belfry's register confirms I am no member, guest or agent (POST /whois): the
 * full screen. RELAY_REFUSED = only the relay said no (a restart, an AUTH race, a whitelist lag
 * after a role change): a line, the channels on this device stay (the lead's rule, 2026-11-19).
 */
enum class AccessState { UNKNOWN, IN, REFUSED, RELAY_REFUSED }

/**
 * What POST /whois says of my own key, and what that means when the relay refused me: the full
 * "Almost in" screen only for NO_ROLE (no role at all, or 401 / 403); a guest is let into their
 * channels and has "apply to become a member" on Home, so a guest, a member or no answer = the line.
 */
enum class RegisterAnswer {
    NO_ROLE, GUEST, MEMBER, NO_ANSWER;

    companion object {
        fun of(kind: String?): RegisterAnswer = when (kind) {
            null -> NO_ROLE
            "guest" -> GUEST
            "member", "agent" -> MEMBER
            else -> NO_ROLE
        }
        fun fullScreen(a: RegisterAnswer): Boolean = a == NO_ROLE
    }
}

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
        access == AccessState.RELAY_REFUSED -> "The belfry didn't let this phone in just now · showing what is on this device"
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
    /**
     * Guest mode (SPEC 19.26): only opened a link so far, no name. They read; nothing is sent
     * until they join with a name, an e-mail and the code mailed to it.
     */
    val reading: Boolean = false,
    /**
     * A member link (roles.py): joining with a name, an e-mail and the code makes them a member
     * with this role. While [reading], the app shows only the member invitation (no channel).
     */
    val member: sh.belfry.core.net.MemberLink? = null,
    /** The member link's token, kept so that "Log in instead" can use it with an account. */
    val invite: String? = null,
    /**
     * They came in through a printed card's guest link (docs/cards.md §3; SPEC 19.74): `group` or
     * `topic`, as the join answer said. Kept for the rule that lets a reading guest post in a topic
     * they reached by a card (SPEC 19.77, `GuestTopic`).
     */
    val card: String? = null,
) {
    /** A member link opened and not joined yet: the app shows the invitation, never the channel behind it. */
    val memberInvite: Boolean get() = member != null && reading
}

/** A photo or file to send with a message. */
class NewAttachment(val name: String, val mime: String, val bytes: ByteArray, val width: Int? = null, val height: Int? = null)

data class PlaceResults(val places: List<Place>, val attribution: String?)

/**
 * A failure of an action, with a fixed message that is safe to show ([message] is never text
 * from the server or from a URL). [code] is the server's error key when there is one
 * (`guest_not_saved`, `agent`, `not_member`, `last_admin`, `taken`, `network`…).
 */
open class ActionException(val code: String, message: String) : Exception(message)

/**
 * Joining from guest mode or saving a guest account did not work (SPEC 19.26). [code]: `invalid`,
 * `name_needed`, `too_many`, `wrong_code`, `expired`, `taken`, `not_guest`, `offline`, `failed`;
 * [moved] (with `taken`): the belfry gave the account with that address the channel.
 */
class GuestJoinException(
    code: String,
    val moved: Boolean = false,
    /** With `taken`: that account's name@domain as the belfry said it (use only through [GuestMode.signInPrefill]). */
    val nip05: String? = null,
    /** With `wrong_code`: tries left, when the belfry said. */
    val triesLeft: Int? = null,
) : ActionException(code, GuestMode.message(code, triesLeft))

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
    /** Show the town's default places by their on-brand names (SPEC 19.31): the app's choice; tests and vectors keep the ids. */
    private val placeNames: Boolean = false,
) {
    @Volatile var community: Community = community
        private set

    /** My public key (hex). */
    val me: String = signer.pubkey

    /** Which account on this device this is (SPEC 19.24): its id and where its things are kept. Set by [Onboarding]. */
    @Volatile var accountId: String = ""
    @Volatile var slot: String = ""

    /** Where this account's letterbox secrets are kept (set by [Onboarding]; null: no sealed conversations). */
    @Volatile var keyVault: Vault? = null
    /** How often the sealed side looks at requests and its own state (tests shorten it). */
    @Volatile internal var sealedPollMs = 120_000L

    /** Tests only: a device key when the signer is no bunker (the sealed side needs one). */
    @Volatile internal var deviceKeyOverride: sh.belfry.core.nostr.LocalSigner? = null

    /** This device's own name and kind for sealed conversations (the app sets them: Build.MODEL, phone / tablet). */
    @Volatile var deviceName: String = "Android phone"
    @Volatile var deviceKind: String = "phone"
    /** The Link-a-phone secret this sign-in came from: proves it in the request (§5.2); memory only. */
    @Volatile var linkSecret: String? = null

    private val sealedKeys: SealedKeyStore? by lazy { keyVault?.let { SealedKeyStore(it, Slots.key(SealedKeyStore.V_KEYS, slot), me) } }

    /**
     * Sealed conversations on this device (sealed.md): null in the demo, for a guest, without a
     * bunker session (no device key) or a key store. Started once the community relay authenticated.
     */
    val sealed: sh.belfry.core.sealed.SealedEngine? by lazy {
        val device = (signer as? BunkerSigner)?.device ?: deviceKeyOverride
        val ks = sealedKeys
        if (localOnly || device == null || ks == null || _guest.value != null) null
        else sh.belfry.core.sealed.SealedEngine(
            SessionSealedPorts({ mainRelay }, { t -> signer.sign(t) }, { api().sessions(signer) }, { b -> api().revokeSession(signer, b) }, ks,
                // pins and sealed conversations beside the conversation data (wiped with the account), one value
                { store.get(K_SEALED_STATE) } to { v -> if (!stopped) store.set(K_SEALED_STATE, v) }, // never after sign-out's wipe
                v1Writes = { !v1Stopped() }),
            device, me, clock = clock, guest = false,
        ).also { e ->
            // a note to myself written while I have no chain: the first-device path, now (SPEC 19.66)
            e.makeChain = { withContext(Dispatchers.Default) { sealedStart() } }
        }
    }

    // ---- encrypted conversations, format 3 (SPEC 19.82) -----------------------------------------------

    /** This device's key: the NIP-46 client key (tests: [deviceKeyOverride]). Signs format 3's device events locally. */
    private fun deviceKey(): LocalSigner? = (signer as? BunkerSigner)?.device ?: deviceKeyOverride

    /** This belfry's `encrypted.format` as this device knows it: device-wide per belfry, outside every account slot. */
    private val encFormat: EncFormatCache by lazy { EncFormatCache(keyVault, community.domain) }

    private val enc3Keys: EncKeyStore? by lazy { keyVault?.let { EncKeyStore(it, Slots.key(EncKeyStore.V_KEYS, slot), me) } }

    /**
     * Format 3 on this device (lists, keys, the device's set-up): null in the demo, for a guest's session (§25:
     * it runs no encryption at all) and without a device key.
     */
    val enc3: Enc3? by lazy {
        val device = deviceKey()
        if (localOnly || device == null || _guest.value != null) null
        else Enc3(device, me, object : Enc3Ports {
            override fun belfryKey(): String? = community.belfryPubkey
            override fun groupsKey(): String? = community.groupsKey ?: groupsKey
            override fun connection(): Int? = mainRelay?.takeIf { it.isUp }?.socketsMade
            override suspend fun query(filter: JsonObject, pageMs: Long): sh.belfry.core.sealed.SealedEngine.Answer? {
                val r = mainRelay ?: return null
                return Paging.all { f -> r.queryKnown(listOf(f), pageMs) }(filter).takeIf { it.complete || it.events.isNotEmpty() }
            }
            override fun addEvents(events: List<Event>) = addToLog(events)
            override suspend fun chatSessions(): Int? = try { api().sessions(signer).count { it.chat } } catch (e: Exception) { null }
            override suspend fun enqueueByDevice(kind: Int, tags: List<Tag>, content: String) { this@Session.enqueueByDevice(kind, tags, content) }
            override fun resubscribe() { mainRelay?.let { r -> if (r.isUp) r.subscribe(SUB, filters(Target.MAIN)) } }
            override fun later(block: suspend () -> Unit) = launchSafely(block)
            override fun nowMs(): Long = System.currentTimeMillis()
        }, enc3Keys)
    }

    private val noEnc3 = MutableStateFlow(Enc3State())
    /** This device's format-3 set-up for the screens (the Messages line); nothing to say in the demo or for a guest. */
    val enc3State: StateFlow<Enc3State> get() = enc3?.state ?: noEnc3

    /** Tests and screenshots only (SPEC 19.84): the set-up state a session without format 3 shows (the 21st device line). */
    fun demoEnc3State(st: Enc3State) { noEnc3.value = st }

    /** Tests and screenshots only (SPEC 19.84): a made-up format-3 view ([DemoEnc3View]); the demo itself stays format 1. */
    @Volatile var demoEnc3View: Enc3View? = null

    private val realEnc3View: Enc3View? by lazy { enc3?.let { e -> RealEnc3View(e, { encFormat.state().format == 3 }, { sessions() }) } }

    /** What the format-3 conversation screens read (SPEC 19.84): the lock, Who can read this; null in the demo and for a guest. */
    val enc3View: Enc3View? get() = demoEnc3View ?: realEnc3View

    /** "Try again" on the set-up line: my list read again. */
    suspend fun enc3TryAgain() = withContext(Dispatchers.Default) { enc3?.tryAgain(); Unit }

    // ---- format 3, part 5 (SPEC 19.85): a new device, the card on my other devices, the removed device --------------

    /** Part 5 on this device (null wherever [enc3] is: the demo, a guest's session, no device key). */
    val enc3Devices: Enc3Devices? by lazy {
        val e = enc3 ?: return@lazy null
        Enc3Devices(e, object : Enc3DevicePorts {
            override suspend fun sessions(): List<SessionInfo>? = try { api().sessions(signer) } catch (x: Exception) { null }
            override suspend fun revoke(device: String, bunker: String?, by: String) {
                guard()
                if (!call { it.revokeDevice(signer, bunker, device, by) }) throw ActionException("not_yet", sh.belfry.core.enc.EncDeviceText.NOT_YET)
            }
            override suspend fun agentReads(agent: String, reads: Boolean) {
                guard()
                if (!call { it.agentReads(signer, agent, reads) }) throw ActionException("not_yet", sh.belfry.core.enc.EncDeviceText.NOT_YET)
            }
            override suspend fun enqueueSigned(e: Event) { this@Session.enqueueSigned(Target.MAIN, e) }
            override suspend fun removedAnswer(): sh.belfry.core.enc.RemovedAnswer? =
                if (!_status.value.online) null else try { api().sessionsRemoved(e.device) } catch (x: Exception) { null }
            override fun v1Secrets(): Map<String, String> = sealed?.keys?.toMap() ?: emptyMap()
            override fun takeV1(secrets: Map<String, String>) { sealed?.takeV1Secrets(secrets) }
            override fun missing(): Int = dm.snapshot.value.let { it.unreadableCount + it.lockedCount }
            override fun since3(): Long = encFormat.state().since3
            override fun later(block: suspend () -> Unit) = launchSafely(block)
        })
    }

    private val demoEnc3 = MutableStateFlow(sh.belfry.core.enc.Enc3DeviceState())
    private val noRemoved = MutableStateFlow<sh.belfry.core.enc.RemovedHere?>(null)

    /** What the screens show of part 5 (the demo: what a test set with [demoEnc3Device]). */
    val enc3Device: StateFlow<sh.belfry.core.enc.Enc3DeviceState> get() = enc3Devices?.state ?: demoEnc3

    /** §16.6 + §26: this device was removed by another of mine (both conditions met); the app wipes this account. */
    val enc3Removed: StateFlow<sh.belfry.core.enc.RemovedHere?> get() = enc3Devices?.removed ?: noRemoved

    /** Tests and screenshots only: the demo's part-5 state (the demo stays format 1: there is no runtime). */
    fun demoEnc3Device(st: sh.belfry.core.enc.Enc3DeviceState) { if (localOnly) demoEnc3.value = st }

    /** The demo's "removed" answer (tests: the wipe and its screen). */
    fun demoEnc3Removed(r: sh.belfry.core.enc.RemovedHere?) { if (localOnly) noRemoved.value = r }

    /** The demo's conversations shown as encrypted here (tests). */
    @Volatile var demoEncConvs: Set<String> = emptySet()

    /** Demo counters for the behaviour tests: Share and Remove reached the session this often. */
    @Volatile var demoShares = 0
        private set
    @Volatile var demoRemovals = 0
        private set
    @Volatile var demoAsks = 0
        private set

    /** CONV (a people conversation) was seen encrypted here: the earlier-messages line may stand above its history. */
    fun encConvHere(conv: String): Boolean = enc3?.everEncrypted(conv) == true || (localOnly && conv in demoEncConvs)

    /** "Ask for them" (§7.3): an 8574 signed by this device; again only after 24 h or a new device of mine. */
    suspend fun enc3Ask() {
        // the demo's state changes on the caller's thread (a screen's): tests resume composition where it was written
        if (localOnly) { demoAsks++; demoEnc3.value = demoEnc3.value.copy(askedAt = now()); return }
        withContext(Dispatchers.Default) { guard(); enc3Devices?.ask() }
    }

    /** Share earlier messages with the card's device (§7.5): key packs, then the answer. PROGRESS: packs queued of all. */
    suspend fun enc3Share(card: sh.belfry.core.enc.DeviceCard, progress: (Int, Int) -> Unit = { _, _ -> }) {
        if (localOnly) {
            demoShares++
            for (i in 1..3) { kotlinx.coroutines.delay(150); progress(i, 3) }
            val st = demoEnc3.value
            demoEnc3.value = st.copy(cards = st.cards.filter { it.device != card.device }, notices = st.notices + sh.belfry.core.enc.EncDeviceText.shared(card.name))
            return
        }
        withContext(Dispatchers.Default) {
            guard()
            (enc3Devices ?: throw ActionException("not_set_up", sh.belfry.core.enc.EncDeviceText.FAILED)).share(card, progress)
        }
    }

    /** Remove the card's device (§7.6), after the person confirmed: its session revoked (by = this device), then the answer. */
    suspend fun enc3Remove(card: sh.belfry.core.enc.DeviceCard) {
        if (localOnly) {
            demoRemovals++
            if (card.reader) demoAgentReads += card.device to false else demoRevoked += card.device
            val st = demoEnc3.value
            val said = if (card.reader) sh.belfry.core.enc.EncDeviceText.agentOff(card.name ?: sh.belfry.core.enc.EncDeviceText.AGENT) else sh.belfry.core.enc.EncDeviceText.removed(card.name)
            demoEnc3.value = st.copy(cards = st.cards.filter { it.device != card.device }, notices = st.notices + said)
            return
        }
        withContext(Dispatchers.Default) { (enc3Devices ?: throw ActionException("not_set_up", sh.belfry.core.enc.EncDeviceText.FAILED)).remove(card) }
    }

    /** Remove on the first screen (§18.3). */
    suspend fun enc3RemoveFirst(row: sh.belfry.core.enc.FirstRow) {
        if (localOnly) {
            demoRemovals++
            demoEnc3.value = demoEnc3.value.let { it.copy(firstScreen = it.firstScreen.filter { r -> r.device != row.device }) }
            return
        }
        withContext(Dispatchers.Default) { (enc3Devices ?: throw ActionException("not_set_up", sh.belfry.core.enc.EncDeviceText.FAILED)).removeFirst(row) }
    }

    // ---- format 3, part 6 (SPEC 19.86): Me › Devices, my agent's access, the reader card ------------------------

    /** Demo records for the behaviour tests: who was revoked (by this device), and each access call (agent, reads). */
    val demoRevoked: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    val demoAgentReads: MutableList<Pair<String, Boolean>> = java.util.Collections.synchronizedList(ArrayList())

    /**
     * The trash icon on another device of my list (revoked, `by` = this device's key) or on my agent (its access off), after
     * the person confirmed. Never this device. The demo changes its made-up list at once (the belfry's new list).
     */
    suspend fun enc3RemoveListed(key: String, reader: Boolean) {
        if (localOnly) {
            val st = demoEnc3.value
            if (key == st.here) throw ActionException("this_device", sh.belfry.core.enc.EncDeviceText.FAILED)
            demoRemovals++
            if (reader) demoAgentReads += key to false else demoRevoked += key
            demoEnc3.value = st.copy(devices = st.devices - key, readers = st.readers - key, cards = st.cards.filter { it.device != key })
            return
        }
        withContext(Dispatchers.Default) { guard(); (enc3Devices ?: throw ActionException("not_set_up", sh.belfry.core.enc.EncDeviceText.FAILED)).removeListed(key, reader) }
    }

    /**
     * My agent's access (§11): `POST /agents {pubkey, reads}` by me, its owner. On or off is read from my list afterwards
     * ([sh.belfry.core.enc.Enc3DeviceState.readers]), never kept from this call. The demo records the call and leaves its
     * list as it was (a test plays the belfry's new list).
     */
    suspend fun enc3AgentReads(agent: String, reads: Boolean) {
        if (localOnly) { demoAgentReads += agent to reads; return }
        withContext(Dispatchers.Default) {
            guard()
            val d = enc3Devices
            if (d != null) d.agentReads(agent, reads)
            else if (!call { it.agentReads(signer, agent, reads) }) throw ActionException("not_yet", sh.belfry.core.enc.EncDeviceText.NOT_YET)
        }
    }

    /** OK on my agent's card (§17.9): answered for every device; access stays on. CARD's name is the agent's. */
    suspend fun enc3ReaderOk(card: sh.belfry.core.enc.DeviceCard) {
        if (localOnly) {
            val st = demoEnc3.value
            demoEnc3.value = st.copy(cards = st.cards.filter { it.device != card.device }, notices = st.notices + sh.belfry.core.enc.EncDeviceText.readerOk(card.name ?: sh.belfry.core.enc.EncDeviceText.AGENT))
            return
        }
        withContext(Dispatchers.Default) { guard(); (enc3Devices ?: throw ActionException("not_set_up", sh.belfry.core.enc.EncDeviceText.FAILED)).readerOk(card) }
    }

    fun enc3GoneOk(device: String) {
        if (localOnly) demoEnc3.value = demoEnc3.value.let { it.copy(cards = it.cards.filter { c -> c.device != device }) } else enc3Devices?.goneOk(device)
    }

    fun enc3NoticeSeen(text: String) {
        if (localOnly) demoEnc3.value = demoEnc3.value.let { it.copy(notices = it.notices - text) } else enc3Devices?.noticeSeen(text)
    }

    fun enc3LostOk() { if (localOnly) demoEnc3.value = demoEnc3.value.copy(lostOk = true) else enc3Devices?.lostOk() }

    fun enc3FirstScreenOk() { if (localOnly) demoEnc3.value = demoEnc3.value.copy(firstScreen = emptyList()) else enc3Devices?.firstScreenOk() }

    /** My devices' names again (the cards, the earlier line, the first screen). */
    suspend fun enc3Names() = withContext(Dispatchers.Default) { enc3Devices?.readNames(); Unit }

    @Volatile private var removedLookMs = 0L

    /** §16.6: asked of the belfry when the app starts, comes to the front, every 5 minutes, and when a relay refuses me. */
    private fun lookRemoved(force: Boolean = false) {
        val d = enc3Devices ?: return
        removedLookMs = System.currentTimeMillis()
        launchSafely { d.checkRemoved(force) }
    }

    /** The format a write uses now ([sh.belfry.core.enc.EncFormatState.writeFormat]); in the demo 1. */
    fun encWriteFormat(): Int? = if (localOnly) 1 else encFormat.state().writeFormat(clock())

    private suspend fun encFacts(readIfStale: Boolean): sh.belfry.core.enc.EncWrites.Facts {
        if (readIfStale && !localOnly && encFormat.state().wantsRead(clock())) withTimeoutOrNull(3_000) { readBelfryJson() }
        return sh.belfry.core.enc.EncWrites.Facts(localOnly, _guest.value != null, encWriteFormat())
    }

    /**
     * What a write in the people conversation or e-mail thread CONV (`n:` / `m:`) does now (SPEC 19.82): the table
     * ([sh.belfry.core.enc.EncWrites.people]); a format that matters and is stale is read again first (3 s), still
     * unknown = held; V3 only once the lists are checked ([Enc3.peopleReady], which carries the plan).
     */
    suspend fun encWriteDm(conv: String): sh.belfry.core.enc.EncWrite = withContext(Dispatchers.Default) {
        val e = enc3
        // e-mail out (SPEC 19.83; §10): the belfry key is the one other person of that message (service: its own device)
        val mail = conv.startsWith("m:")
        val peers = if (mail) listOfNotNull(community.belfryPubkey) else sh.belfry.core.mail.MailThreads.peers(conv)
        val w0 = _world.value
        val guestIn = peers.any { it != me && w0.isGuest(it) }
        val ever = e?.everEncrypted(conv) == true
        var w = sh.belfry.core.enc.EncWrites.people(conv, encFacts(false), ever, guestIn)
        if (w is sh.belfry.core.enc.EncWrite.Hold && w.reason == sh.belfry.core.enc.HoldReason.FORMAT) w = sh.belfry.core.enc.EncWrites.people(conv, encFacts(true), ever, guestIn)
        if (w is sh.belfry.core.enc.EncWrite.V3) w = when {
            mail && peers.isEmpty() -> sh.belfry.core.enc.EncWrite.Hold(sh.belfry.core.enc.HoldReason.NO_LIST) // no belfry key known yet
            else -> e?.peopleReady(if (mail) Enc3.peopleConv(peers, me) else conv, peers + me) ?: sh.belfry.core.enc.EncWrite.Hold(sh.belfry.core.enc.HoldReason.NOT_LISTED)
        }
        w
    }

    /**
     * Format 3 (SPEC 19.83; docs/encrypted.md §12): this device knows the belfry as format 3, so nothing of version 1 is
     * written any more (no roster, anchor, device request, hand-over, confirmation, name record, letterbox wrap). Reading
     * version 1 goes on. The demo never.
     */
    internal fun v1Stopped(): Boolean = !localOnly && encFormat.state().format == 3

    /** Queues a people 8572 (format 3) of message DM under key KEY: it goes once the key's envelopes are out. */
    internal suspend fun enqueueEnc(event: Event, dm: String, key: String): String = withContext(Dispatchers.Default) {
        guard()
        val item = OutboxItem(newLocalId(), Target.MAIN, event.kind, event.content, event.tags, event.createdAt, signed = event, dm = dm, encKey = key)
        store.putOutbox(item)
        countOutbox()
        requestFlush()
        item.localId
    }

    /**
     * Format 3 (SPEC 19.83; §5): an 8572 goes only after its key's envelopes are confirmed out ([Enc3.envelopesFirst]);
     * lost or refused ones are made again and queued first. Null: it may go.
     */
    private fun envelopesFirst(item: OutboxItem): SendOutcome? {
        val K = item.encKey ?: return null
        if (item.kind != sh.belfry.core.enc.Enc.MSG) return null
        val e = enc3 ?: return null
        return when (val g = e.envelopesFirst(K, store.outbox())) {
            Enc3.EnvGate.Go -> null
            Enc3.EnvGate.Refuse -> SendOutcome.Error("invalid: the conversation key could not be stored at the belfry")
            is Enc3.EnvGate.Wait -> {
                g.drop.forEach { store.dropOutbox(it) }
                for (env in g.queue) store.putOutbox(OutboxItem(newLocalId(), Target.MAIN, env.kind, env.content, env.tags, env.createdAt, signed = env))
                if (g.queue.isNotEmpty() || g.drop.isNotEmpty()) countOutbox()
                SendOutcome.Error("waiting: the conversation key goes first")
            }
        }
    }

    /** "Try again" on a message format 3 holds: decided again now. */
    suspend fun retryHeldDms() = withContext(Dispatchers.Default) { heldRetries.incrementAndGet(); dm.retryHeldNow() }

    /** How many times Try again asked for held messages (tests). */
    val heldRetries = java.util.concurrent.atomic.AtomicInteger()

    /**
     * What a write of KIND (with TAGS) in the group GROUP_ID does now (SPEC 19.82; the web's `groupWriteMode` and its
     * guest guard): private = its metadata (a group, closed + private) or a key of it seen here; V3 once its members'
     * lists are checked ([Enc3.groupReady]).
     */
    suspend fun encWriteGroup(groupId: String, kind: Int, tags: List<Tag> = emptyList()): sh.belfry.core.enc.EncWrite = withContext(Dispatchers.Default) {
        val e = enc3
        val w0 = _world.value
        val g = w0.group(groupId)
        val privateMeta = g != null && g.isPod && g.closed && g.isPrivate
        val clearKind = EncRules.clearInGroup(Event("", me, 0, kind, tags, ""), community.groupsKey ?: groupsKey)
        val ever = e?.everEncrypted(Enc3.groupConv(groupId)) == true
        fun decide(f: sh.belfry.core.enc.EncWrites.Facts) = sh.belfry.core.enc.EncWrites.group(f, ever, privateMeta, g != null, clearKind)
        var w = decide(encFacts(false))
        if (w is sh.belfry.core.enc.EncWrite.Hold && w.reason == sh.belfry.core.enc.HoldReason.FORMAT) w = decide(encFacts(true))
        if (w is sh.belfry.core.enc.EncWrite.V3) w = if (e == null || g == null) sh.belfry.core.enc.EncWrite.Hold(sh.belfry.core.enc.HoldReason.NOT_LISTED)
            else e.groupReady(g.members, { w0.isGuest(it) })
        w
    }

    /** Queues an event the DEVICE key signs when it is sent (format 3's device events; [OutboxItem.byDevice]). */
    internal suspend fun enqueueByDevice(kind: Int, tags: List<Tag>, content: String): String = withContext(Dispatchers.Default) {
        guard()
        val item = enqueueLock.withLock {
            lastCreatedAt = Outbox.nextCreatedAt(lastCreatedAt, clock())
            OutboxItem(newLocalId(), Target.MAIN, kind, content, tags, lastCreatedAt, byDevice = true).also { store.putOutbox(it) }
        }
        countOutbox()
        requestFlush()
        item.localId
    }

    /**
     * Format 3 (SPEC 19.83; §6 "no typing indicators in encrypted conversations"): CONV is (or will be written)
     * encrypted here: seen encrypted, or a people conversation on a belfry this device knows as format 3.
     */
    private fun encryptedHere(conv: String): Boolean =
        enc3?.everEncrypted(conv) == true || (conv.startsWith("n:") && !localOnly && encFormat.state().format == 3)

    /** My conversations' people: their lists on the live subscription. */
    private fun encPartners() {
        val e = enc3 ?: return
        e.setPartners(dm.snapshot.value.threads.flatMap { sh.belfry.core.mail.MailThreads.peers(it.conv) })
    }

    /** Keys arrived: kept wraps tried again, and every wrap to me read again from 0, paged (§5.5). */
    private suspend fun rereadWraps(joined: Set<String> = emptySet()) {
        dm.retryKept()
        val r = mainRelay ?: return
        val all = Paging.all { f -> r.queryKnown(listOf(f)) }(Filters.of(kinds = listOf(1059), tags = mapOf("p" to listOf(me))))
        // the letterboxes of devices that added themselves, first followed now (sealed.md §5.7): their wraps are
        // read again even when an earlier build settled them as not for me
        if (joined.isNotEmpty()) dm.forgetSeen(all.events.filter { it.kind == 1059 && it.tag("lb") in joined }.map { it.id })
        addToLog(all.events)
        dm.offer(all.events)
        dm.process()
        try { encryption.refresh() } catch (x: Exception) { }
    }

    @Volatile private var nameWritten = false

    /** My device name record, once per run once this device is in my head (also after keys arrived later). */
    private suspend fun writeNameOnce(e: sh.belfry.core.sealed.SealedEngine) {
        if (nameWritten) return
        val own = try { api().sessions(signer).firstOrNull { it.clientPubkey == e.device.pubkey }?.device } catch (x: Exception) { null }
        e.writeMyName(sh.belfry.core.sealed.DeviceNames.myName(own, deviceName), deviceKind)
        nameWritten = true
    }

    private val chainReads = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** A live roster / anchor of WHO: their chain read again (at most every 5 s each). */
    private val chainReadDue = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** A live roster / anchor of WHO: their chain read again, at most every 5 s each; one that comes sooner is deferred, not lost. */
    private fun sealedChainMoved(who: String) {
        val now = System.currentTimeMillis()
        val last = chainReads[who] ?: 0L
        val wait = if (now - last >= 5_000) 1_000L else 5_000L - (now - last)
        if (!chainReadDue.add(who)) return // one read already scheduled covers this one
        launchSafely {
            delay(wait)
            chainReadDue.remove(who)
            chainReads[who] = System.currentTimeMillis()
            sealed?.refresh(who)
        }
    }

    private val upkeepRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var subscribedPeers: Set<String> = emptySet()

    /** The sealed set gained a person: the community relay's live subscription is sent again to cover them (N3). */
    private fun resubscribeIfSealedGrew() {
        val now = sealedPeers().toSet()
        if ((now - subscribedPeers).isEmpty()) return
        subscribedPeers = now
        mainRelay?.let { r -> if (r.isUp) r.subscribe(SUB, filters(Target.MAIN)) }
    }

    /** A sealed conversation opened: its people's chains asked again, kept wraps tried again (§4; the review's 1). */
    fun sealedOpened(conv: String) {
        val e = sealed ?: return
        launchSafely {
            for (p in sh.belfry.core.mail.MailThreads.peers(conv)) if (p != me) e.refresh(p)
            dm.retryKept(); dm.process()
        }
    }

    /** After a roster change ended "not done": upkeep again in 10 s, 1 min and 5 min (not the 6-hour timer). */
    fun sealedFollowUps() {
        if (sealed == null) return
        for (ms in longArrayOf(10_000, 60_000, 300_000)) scope.launch(Dispatchers.Default) {
            delay(ms)
            try { if (!stopped) { sealed?.upkeep(); encryption.refresh() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        }
    }

    /** This device's name for its record (§3.5): the session row's, else the platform's. */
    internal suspend fun sealedMyName(): String {
        val e = sealed
        val own = if (e == null) null else try { api().sessions(signer).firstOrNull { it.clientPubkey == e.device.pubkey }?.device } catch (x: Exception) { null }
        return sh.belfry.core.sealed.DeviceNames.myName(own, deviceName)
    }

    /**
     * Format 3 (SPEC 19.83): once per authenticated connection, my envelopes and messages since the last seen (less an
     * hour) read whole, paged: the live subscription's limit never hides older ones.
     */
    private suspend fun enc3CatchUp() {
        val r = mainRelay ?: return
        val kinds = listOf(sh.belfry.core.enc.Enc.KEY, sh.belfry.core.enc.Enc.MSG)
        val since = maxOf(0, latest { it.kind in kinds } - 3600)
        val got = Paging.all { f -> r.queryKnown(listOf(f), 8_000) }(Filters.of(kinds = kinds, since = since, tags = mapOf("p" to listOf(me))))
        addToLog(got.events)
        dm.process()
        // SPEC 19.85: asks and answers of my account and packs for me, whole (few, and an answer settles a card for good)
        if (enc3Devices != null) {
            val dev = Paging.all { f -> r.queryKnown(listOf(f), 8_000) }(Filters.of(kinds = listOf(sh.belfry.core.enc.Enc.PACK, sh.belfry.core.enc.Enc.ASK, sh.belfry.core.enc.Enc.ANSWER), tags = mapOf("p" to listOf(me))))
            addToLog(dev.events)
            enc3Devices?.offer(dev.events)
        }
    }

    /** Starts (or retries, after a late AUTH) the sealed side; then the upkeep. Never throws. */
    private suspend fun sealedStart() {
        val e = sealed ?: return
        e.trace("sealedStart mine=${e.mine} headNoKey=${e.inHeadWithoutKey}")
        if (e.onKeys == null) e.onKeys = { launchSafely { rereadWraps() } }
        // "It was me" on another of my devices (8563, §5.2 step 2b): the notice goes here too
        if (e.onConfirmed == null) e.onConfirmed = { launchSafely { encryption.refresh() } }
        // my chain gained a device that added itself (sealed.md §5.7): wraps again from 0 (onChain, right after, looks at its session)
        if (e.onJoin == null) e.onJoin = { lbs -> launchSafely { rereadWraps(lbs) } }
        // a correspondent's (or my own) chain moved: the wraps kept for it are tried again (§4; the review's 1)
        if (e.onChain == null) e.onChain = { who ->
            launchSafely { dm.retryKept(); dm.process(); resubscribeIfSealedGrew() }
            // my own head moved (a rotation): my device name record again under the new letterbox (re-review (16))
            if (who == me) { nameWritten = false; launchSafely { if (e.mine == sh.belfry.core.sealed.SealedEngine.Mine.IN_HEAD) writeNameOnce(e) } }
            // a device that added itself and is no live chat session is removed at once (sealed.md §5.7)
            if (who == me) launchSafely { e.checkJoiners(); encryption.refresh() }
        }
        // format 3 (§12): version 1 is read only: no first roster, no request, no upkeep, no name record
        if (v1Stopped()) { launchSafely { dm.process() }; return }
        if (e.mine == sh.belfry.core.sealed.SealedEngine.Mine.UNKNOWN && !e.firstRefused) {
            val own = try { api().sessions(signer).firstOrNull { it.clientPubkey == e.device.pubkey }?.device } catch (x: Exception) { null }
            e.start(sh.belfry.core.sealed.DeviceNames.myName(own, deviceName), deviceKind, link = linkSecret)
            if (e.mine != sh.belfry.core.sealed.SealedEngine.Mine.UNKNOWN) linkSecret = null
            if (e.mine == sh.belfry.core.sealed.SealedEngine.Mine.IN_HEAD) e.writeMyName(sh.belfry.core.sealed.DeviceNames.myName(own, deviceName), deviceKind)
        }
        // a waiting device (or one in the head without the key) asks again once per connection (N2, N4);
        // start() just asked when it was the one to decide WAITING
        if (e.mine == sh.belfry.core.sealed.SealedEngine.Mine.WAITING || e.inHeadWithoutKey) e.askAgain(linkSecret, force = true)
        if (e.mine == sh.belfry.core.sealed.SealedEngine.Mine.IN_HEAD) {
            writeNameOnce(e)
            // the post-AUTH flush first, then the partners' chains (N6)
            requestFlush()
            // one partner upkeep at a time, however many AUTHs (P6)
            if (upkeepRunning.compareAndSet(false, true)) launchSafely { try { delay(2_000); e.upkeep() } finally { upkeepRunning.set(false) } }
        }
        // a first roster made in this run: read once more 5 s later (a race lost late; re-review (7))
        if (e.createdFirst) { e.createdFirst = false; launchSafely { delay(5_000); e.askAgain(linkSecret, force = true) } }
        try { encryption.refresh() } catch (x: Exception) { }
        launchSafely { dm.process() }
    }

    /** Told when the community document was refreshed or the guest state changed (the app saves them). */
    @Volatile var onRecordChanged: ((Community, GuestInfo?) -> Unit)? = null

    private val logLock = Any()
    private val rebuildLock = Any()
    private val log = ArrayList<Event>()
    /** The ids in [log] (under [logLock]): the relays skip these before checking a signature. */
    private val logIds = HashSet<String>()
    @Volatile private var extraAdmins: Set<String> = emptySet()
    @Volatile private var bridge: String? = null
    @Volatile private var groupsKey: String? = null
    /** Deleted by absence (SPEC 19.30): what the groups relay said it has no metadata for. Stored. */
    @Volatile private var groupsAbsent: Map<String, Long> = emptyMap()
    @Volatile private var lastPresence = 0L
    /** Groups made on this device → when (never asked about in their first two minutes). */
    private val createdHere = HashMap<String, Long>()
    private var lastCreatedAt = 0L

    private val _world: MutableStateFlow<World>
    /** Everything the screens show (a new immutable snapshot after every change). */
    val world: StateFlow<World> get() = _world

    private val _status = MutableStateFlow(Status(localOnly = localOnly))
    val status: StateFlow<Status> = _status

    private val _guest = MutableStateFlow(guest)
    /** Not null while I am here as a guest of one channel. */
    val guest: StateFlow<GuestInfo?> = _guest

    /** Guest mode (SPEC 19.26): reading only; every write is refused here and opens the Join sheet. */
    val reading: Boolean get() = _guest.value?.reading == true

    /**
     * Told once when this guest became a member (a member link's code, a link used with the
     * account, the register saying so). The app opens the account again, as the web reloads: the
     * relays then see a member's key and send every channel. CHANNEL: where to land, when known.
     */
    @Volatile var onBecameMember: ((channel: String?) -> Unit)? = null
    @Volatile private var becameMemberTold = false

    private val _joinAsked = MutableStateFlow(false)
    /** Something that writes was tried in guest mode (or Join was tapped): the Join sheet is open. */
    val joinAsked: StateFlow<Boolean> = _joinAsked
    fun askToJoin() { if (reading) _joinAsked.value = true }
    fun closeJoin() { _joinAsked.value = false }

    /**
     * Reading in GROUP_ID, a topic this device reached through a topic card (SPEC 19.77): the one
     * place where a reading guest has a composer ([postGuestMessage]).
     */
    fun guestTopic(groupId: String): Boolean = _guest.value.let { GuestTopic.here(it?.reading == true, it?.channel, it?.card, groupId) }

    /** Every write goes through here first: in guest mode it is refused and the Join sheet opens. */
    private fun guard() {
        if (!reading) return
        _joinAsked.value = true
        throw ActionException(GuestMode.REFUSED, GuestMode.REFUSED_TEXT)
    }

    private val dm: DirectMessages
    /** Direct messages and e-mail. */
    val dms: StateFlow<DmSnapshot> get() = dm.snapshot

    private val readSync: ReadSync
    /** What I have read, on all my devices (SPEC 19.27): unread counts derive from it (`sh.belfry.core.read.Unread`). */
    val readState: StateFlow<ReadState> get() = readSync.state

    private val pinSync: PinSync
    /** The channels I pinned for myself, on all my devices (docs/one-shape.md §1; newest wins). */
    val pins: StateFlow<PinSync.Pins> get() = pinSync.state

    /** The one owner of this session's sockets: groups, main and the signer's (SPEC 19.50). */
    internal val connections = Connections(scope)
    private val groupsRelay: Relay? = if (localOnly) null else connections.add("groups", Relay(community.groupsRelay, http, scope))
    private val mainRelay: Relay? = if (localOnly) null else connections.add("main", Relay(community.mainRelay, http, scope))

    /** Each send's steps and timing, the last 20 (About Belfry → Diagnostics; SPEC 19.50). No content. */
    val trace = SendTrace({ Relay.nowMs() }, zone = zone)
    /** Sends not out yet: since when and what they are doing (by direct message id, else outbox local id). */
    val sending: StateFlow<Map<String, SendProgress>> get() = trace.progress

    init {
        val b = signer as? BunkerSigner
        if (b != null && !localOnly) {
            connections.add("signer", b.relay)
            b.onRequest = { m, ms -> if (ms >= SLOW_SIGNER_MS) trace.note("signer $m ${SendTrace.secs(ms)}") }
        }
    }
    /** The relay a target publishes to; none for [Target.API] (HTTP calls). It must stay none: every per-target
     *  loop below (callbacks, subscriptions, status) would otherwise set up the main relay a second time as "API",
     *  overwriting its handlers (since 0d1cc0f the main relay's EOSE came as API and the read state never published). */
    private fun relay(t: Target): Relay? = when (t) {
        Target.GROUPS -> groupsRelay
        Target.MAIN -> mainRelay
        Target.API -> null
    }
    /** Tests: what the main relay's client saw (drops handled / ignored / before open, sockets made). */
    internal val mainRelayStats: String get() = mainRelay?.let { "drops ${it.dropCounts.toList()}, sockets ${it.socketsMade}, up ${it.isUp}" } ?: "none"

    private sealed interface Incoming {
        class Ev(val e: Event) : Incoming
        /** [authed]: the relay had accepted our NIP-42 (or asks for none) when it said EOSE. */
        class Eose(val target: Target, val authed: Boolean = true) : Incoming
    }
    private val incoming = Channel<Incoming>(Channel.UNLIMITED)
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val flushWanted = Channel<Unit>(Channel.CONFLATED)
    // (declared before init, which starts its reader)
    private val draftWrites = Channel<Pair<String, String?>>(Channel.UNLIMITED) // store key → what to keep (null: forget)
    private val jobs = ArrayList<Job>()
    @Volatile private var started = false
    @Volatile private var stopped = false

    private val typingEngine = SessionTyping(scope, signer.pubkey, signer, clock, { if (localOnly) clock().toDouble() else System.currentTimeMillis() / 1000.0 }, { relay(it) }, { _world.value }, { !reading && !localOnly && !stopped }, { c -> sealed?.sealedSince?.containsKey(c) == true || encryptedHere(c) })

    /** Who is typing where (docs/typing.md; SPEC 19.51): for the line above the composer and the DM list. */
    val typing: StateFlow<sh.belfry.core.typing.TypingState> get() = typingEngine.state

    /** The device's "Show others when I'm typing" (the app keeps it, per device; on by default). */
    var sendTyping: Boolean
        get() = typingEngine.enabled
        set(v) { typingEngine.enabled = v }

    /** While a screen shows [group] (a channel or one of its threads): its typing events are asked for. Close when it goes. */
    fun watchTyping(group: String): AutoCloseable = typingEngine.watch(group)

    /** A keystroke in the composer of [place]; [restored] = a draft put back on open or an edit of an older message. */
    fun typed(place: sh.belfry.core.typing.TypingPlace, text: String, restored: Boolean = false) = typingEngine.typed(place, text, restored)

    /** The composer of [place] was emptied without sending. */
    fun typingCleared(place: sh.belfry.core.typing.TypingPlace) = typingEngine.cleared(place)

    /** A message went from [place]. */
    fun typingSent(place: sh.belfry.core.typing.TypingPlace) = typingEngine.sent(place)

    /** The demo and tests: PUBKEY types in PLACE now (shows for 6 s). */
    fun showTyping(place: sh.belfry.core.typing.TypingPlace, pubkey: String) = typingEngine.show(place, pubkey)
    @Volatile private var firstChannelTried = false
    private val enqueueLock = Mutex()
    private val flushLock = Mutex()
    @Volatile private var again = false
    // declared before init: the first world is built there
    /** The register's answers this session (key → member | agent | guest). */
    private val kinds = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Each channel's e-mail address as the belfry answered it (`POST /mail-alias`, SPEC 19.60); kept in the store. */
    private val mailAddresses = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val askedWhois: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** An old belfry without `/whois` (404): join and label events decide. */
    @Volatile private var noWhois = false
    @Volatile private var lastRecheck = 0L
    /** The live groups subscription asked for the whole history ([K_GROUPS_FULL] is set at its EOSE). */
    @Volatile private var groupsFullAsked = false

    private val uploaded = HashMap<String, String>() // blobKey → URL, so a retry does not upload twice
    private val demoFollows = HashMap<String, String>()

    init {
        val all = store.allEvents()
        log.addAll(all)
        all.mapTo(logIds) { it.id }
        extraAdmins = parseJsonOrNull(store.get(K_ADMINS) ?: "").arr()?.mapNotNull { it.str() }?.filter(Hex::isKey)?.toSet() ?: emptySet()
        bridge = store.get(K_BRIDGE)?.takeIf(Hex::isKey)
        groupsKey = store.get(K_GROUPS_KEY)?.takeIf(Hex::isKey)
        groupsAbsent = parseJsonOrNull(store.get(K_ABSENT) ?: "").obj()?.mapNotNull { (k, v) -> v.long()?.let { k to it } }?.toMap() ?: emptyMap()
        parseJsonOrNull(store.get(K_MAIL_ADDRESSES) ?: "").obj()?.forEach { (k, v) -> v.str()?.let { mailAddresses[k] = it } }
        lastCreatedAt = maxOf(
            all.filter { it.pubkey == me }.maxOfOrNull { it.createdAt } ?: 0,
            store.outbox().filter { it.signed == null }.maxOfOrNull { it.createdAt } ?: 0,
        )
        _world = MutableStateFlow(buildWorld())
        dm = DirectMessages(store, Host())
        dm.myAddressProvider = { _world.value.myAddress }
        dm.offer(all)
        readSync = ReadSync(store, me, clock, scope, ReadHost(), seedD = { dm.legacyMarks() })
        dm.setReadState(readSync.state.value)
        readSync.onChange = { dm.setReadState(it) }
        dm.onRead = { conv, at -> readSync.mark('d', conv, at) }
        pinSync = PinSync(store, me, clock, scope, PinHost())
        pinSync.offer(all)
        countOutbox()
        jobs += scope.launch(Dispatchers.Default) { for (u in dirty) { rebuild(); delay(REBUILD_MS) } }
        jobs += scope.launch(Dispatchers.Default) { intake() }
        jobs += scope.launch(Dispatchers.Default) { for (u in flushWanted) flush() }
        jobs += scope.launch(Dispatchers.IO) { writeDrafts() }
    }

    // ---- the world ---------------------------------------------------------------------------------

    private fun ctx(): WorldContext = WorldContext(
        me = me, domain = community.domain, communityName = community.name,
        communityAdmins = community.admins.toSet() + extraAdmins, groupsKey = community.groupsKey ?: groupsKey, bridgeKey = bridge, zone = zone,
        groupsRelay = community.groupsRelay, agents = community.agents, kinds = HashMap(kinds),
        groupsAbsent = groupsAbsent, placeNames = placeNames, belfryKey = community.belfryPubkey,
        mailAddresses = HashMap(mailAddresses),
    )

    /** The belfry said channel CHANNEL's address is ADDRESS (null: it can't say; the app works it out). */
    internal fun setMailAddress(channel: String, address: String?) {
        val was = if (address == null) mailAddresses.remove(channel) else mailAddresses.put(channel, address)
        if (was == address) return
        store.set(K_MAIL_ADDRESSES, kotlinx.serialization.json.JsonObject(mailAddresses.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
        markDirty()
    }

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
        // the register says this guest of the device is a member now (a member link, an admin's
        // decision, an accepted request): the guest state goes
        if (kinds[me] == "member" && _guest.value != null) becameMember(null)
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
        synchronized(logLock) { log.addAll(fresh); fresh.mapTo(logIds) { it.id } }
        dm.offer(fresh)
        readSync.offer(fresh)
        pinSync.offer(fresh)
        // a correspondent's (or my own) roster or anchor arrived live: that person's chain read again, debounced (N3)
        if (sealed != null) for (e in fresh) {
            val who = when {
                e.kind == sh.belfry.core.sealed.Roster.KIND -> e.tag("p")
                e.kind == 30078 && e.tag("d") == sh.belfry.core.sealed.Anchor.D -> e.pubkey
                else -> null
            } ?: continue
            sealedChainMoved(who)
        }
        // a key hand-over to this device (sealed §5.5)
        val device = sealed?.device?.pubkey
        if (device != null) for (w in fresh) if (w.kind == 1059 && w.hasTag("p", device)) launchSafely { sealed?.onDeviceWrap(w) }
        // format 3: device lists kept (regular events, never pruned) and this device's place on mine looked at again
        enc3?.let { e -> if (e.offer(fresh)) launchSafely { e.check() } }
        markDirty()
        if (fresh.any { it.kind == 1059 || it.kind == 4 || it.kind == sh.belfry.core.enc.Enc.MSG }) launchSafely { dm.process(); resubscribeIfSealedGrew(); encPartners() }
    }

    /** Background work that stop() cancels (the review's 9: a task asleep must not write after sign-out). */
    private fun launchSafely(block: suspend () -> Unit) {
        if (stopped) return
        val job = scope.launch(Dispatchers.Default) {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* next wake-up tries again */ }
        }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs.remove(job) } }
    }

    /** The people of my sealed conversations (their chains on the live subscription). */
    private fun sealedPeers(): List<String> = sealed?.sealedSince?.keys?.flatMap { sh.belfry.core.mail.MailThreads.peers(it) }?.filter { it != me }?.distinct().orEmpty()

    private suspend fun intake() {
        while (scope.isActive) {
            val first = incoming.receive()
            val batch = arrayListOf(first)
            while (batch.size < 1000) batch.add(incoming.tryReceive().getOrNull() ?: break)
            addToLog(batch.filterIsInstance<Incoming.Ev>().map { it.e })
            val groupsEose = batch.filterIsInstance<Incoming.Eose>().filter { it.target == Target.GROUPS }
            if (groupsEose.isNotEmpty()) onGroupsEose(groupsEose.any { it.authed })
            if (batch.any { it is Incoming.Eose && it.target == Target.MAIN }) { readSync.relayAnswered(); pinSync.relayAnswered() }
        }
    }

    private suspend fun onGroupsEose(authed: Boolean) {
        setStatus { it.copy(access = AccessState.IN) }
        // Only an answer given after NIP-42 is the member's whole view (chat 458704e): an earlier one
        // holds only public events, and taking its newest as "caught up" left out every older
        // members-only event (other channels' 39000s, an old agreement) on the next catch-up.
        if (authed) {
            if (groupsFullAsked) { groupsFullAsked = false; store.set(K_GROUPS_FULL, "1") }
            val kinds = KINDS_GROUPS.toSet()
            store.set(K_GROUPS_MARK, latest { it.kind in kinds }.toString())
            // absence counts only against the member's whole view, never a public-only answer
            launchSafely { checkPresence() }
            launchSafely { catchUpNewGroups() }
        }
        // A new belfry's groups relay has no channel yet: the first member in opens #general. Once.
        // (Not a guest in guest mode: reading leaves no trace.)
        if (firstChannelTried || reading) return
        val any = synchronized(logLock) { log.any { it.kind == 39000 } }
        if (any) return
        firstChannelTried = true
        send(Actions.createGeneral(community.name))
    }

    /**
     * SPEC 19.30: asks the groups relay whether it still has the channels and groups this device
     * knows (at most once a minute; never as a guest or in guest mode: the relay shows a guest only
     * their own channels). An answer counts only when it names #general.
     */
    suspend fun checkPresence(force: Boolean = false, extra: List<String> = emptyList()) {
        val r = groupsRelay ?: return
        if (reading || _world.value.amGuest) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPresence < 60_000) return
        lastPresence = now
        val w0 = _world.value
        val made = synchronized(createdHere) { HashMap(createdHere) }
        val ids = (w0.presenceCandidates() + extra).distinct().filter { w0.presenceAskable(it, clock(), made) }
        var absent = groupsAbsent
        var changed = false
        for (q in GroupPresence.questions(ids)) {
            // its metadata and its own 9007 / 9002 / 9008; only an answer that ends with EOSE counts
            val answer = r.queryUntilEose(listOf(
                buildJsonObject {
                    put("kinds", JsonArray(listOf(JsonPrimitive(39000))))
                    put("#d", JsonArray(q.map { JsonPrimitive(it) }))
                },
                buildJsonObject {
                    put("kinds", JsonArray(listOf(JsonPrimitive(9007), JsonPrimitive(9002), JsonPrimitive(9008))))
                    put("#h", JsonArray(q.filter { it != "general" }.map { JsonPrimitive(it) }))
                },
            )) ?: continue
            val next = _world.value.presenceAfter(q, answer, absent) ?: continue
            addToLog(answer)
            if (next != absent) { absent = next; changed = true }
        }
        if (!changed) return
        groupsAbsent = absent
        store.set(K_ABSENT, JsonObject(absent.mapValues { JsonPrimitive(it.value) }).toString())
        rebuild()
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
            r.onEvent = { _, e -> if (!typingEngine.offer(e)) incoming.trySend(Incoming.Ev(e)) } // typing: live only, never stored
            r.known = { id -> synchronized(logLock) { id in logIds } }
            r.onEose = { sub -> if (sub == SUB) { reopens[t.ordinal] = 0; refusals[t.ordinal] = 0; incoming.trySend(Incoming.Eose(t, r.isAuthenticated || r.authSigner == null || localOnly)) } }
            r.onClosed = { sub, reason ->
                if (t == Target.GROUPS) noteRefusal(reason)
                if (reason.startsWith("auth-required") || reason.startsWith("restricted")) lookRemoved() // SPEC 19.85
                if (sub == SUB) reopen(t, reason)
            }
            r.onAuthRefused = { reason ->
                if (t == Target.GROUPS) noteRefusal(reason)
                lookRemoved() // a relay refused my session: was this device removed? (SPEC 19.85)
            }
            r.onAuthenticated = {
                // sealed: first device / request again when AUTH lands late (64a07fa); chains before the flush
                if (t == Target.MAIN) sealed?.trace("auth (session)")
                if (t == Target.MAIN && sealed != null) launchSafely { sealedStart(); requestFlush() } else requestFlush()
                if (t == Target.MAIN && enc3 != null) launchSafely { enc3CatchUp() }
                if (t == Target.MAIN) lookRemoved(force = true) // SPEC 19.85: my list can be read whole now
            }
            r.onConnected = { requestFlush() }
            r.onDisconnect = {
                if (t == Target.MAIN) { sealed?.trace("disconnect (session)"); sealed?.connectionLost() } // knowledge needs a live connection (c233eec)
                r.subscribe(SUB, filters(t)) // catch up from the last event seen
            }
            r.subscribe(SUB, filters(t))
            jobs += scope.launch { r.state.collect { s -> setStatus { if (t == Target.GROUPS) it.copy(groups = s) else it.copy(main = s) } } }
            if (_status.value.online) r.keepConnected()
        }
        connections.start()
        typingEngine.start()
        jobs += scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(WATCHDOG_MS)
                if (_status.value.online) for (t in Target.entries) relay(t)?.let { if (!it.isUp) it.wake() }
                if (store.outbox().any { it.status != OutboxStatus.FAILED }) requestFlush()
            }
        }
        launchSafely { refreshCommunity() }
        // format 3 (SPEC 19.82): the lists kept in the store, then my place on my list, looked at every 5 s for the
        // first 70 s (the 60 s of §3) and every 30 s after
        enc3?.let { e ->
            e.onKeys = { launchSafely { dm.keysArrived() } }
            val d = enc3Devices // SPEC 19.85: its hooks on Enc3 before the first offer
            e.offer(synchronized(logLock) { log.filter { it.kind == sh.belfry.core.enc.Enc.LIST || it.kind == sh.belfry.core.enc.Enc.KEY || it.kind in sh.belfry.core.enc.Enc.PACK..sh.belfry.core.enc.Enc.ANSWER } })
            e.setFormat3(encFormat.state().format == 3)
            launchSafely { encPartners() }
            d?.refresh()
            launchSafely { d?.readNames() }
            lookRemoved(force = true) // the app starts
            jobs += scope.launch(Dispatchers.Default) {
                var n = 0
                while (isActive) {
                    delay(if (n++ < 14) 5_000L else 30_000L)
                    try { if (_status.value.online) e.tick() else e.check() } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { }
                    // SPEC 19.85: asked again (24 h, a new device of mine), packs waiting for whole lists; removed? every 5 minutes
                    try { if (_status.value.online) d?.tick() } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { }
                    if (_status.value.online && System.currentTimeMillis() - removedLookMs > 5 * 60_000L) lookRemoved(force = true)
                    // format 3: held messages decided again (a person's device came, my list arrived…)
                    try { if (_status.value.online && dm.hasHeld()) dm.process() } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { }
                }
            }
        }
        // sealed conversations: the 6-hour upkeep (gone sessions, ready, my unanchored roster)
        if (sealed != null) jobs += scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(SEALED_UPKEEP_MS)
                try { if (!v1Stopped() && sealed?.mine == sh.belfry.core.sealed.SealedEngine.Mine.IN_HEAD) sealed?.upkeep() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
            }
        }
        // requests from my new devices (the Allow prompt) and device lines: looked at every two minutes
        if (sealed != null) jobs += scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(sealedPollMs)
                try {
                    if (_status.value.online && !v1Stopped()) {
                        val e = sealed
                        when (e?.mine) {
                            sh.belfry.core.sealed.SealedEngine.Mine.UNKNOWN -> sealedStart()
                            sh.belfry.core.sealed.SealedEngine.Mine.WAITING -> { e.askAgain(linkSecret, force = false); encryption.refresh() } // every 24 h (§5.2); AUTH asks once per connection
                            sh.belfry.core.sealed.SealedEngine.Mine.IN_HEAD -> {
                                resubscribeIfSealedGrew()
                                // in the head but a rotation's hand-over missed: ask too (N4); my chain read first
                                e.refresh(me)
                                if (e.inHeadWithoutKey) e.askAgain(linkSecret, force = true)
                                e.checkJoiners()
                                writeNameOnce(e); encryption.refresh()
                            }
                            else -> {}
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
            }
        }
        // a guest on this device: is that still so? (a member link, an admin, an accepted request)
        if (_guest.value != null) launchSafely { refreshWhois(listOf(me)) }
        requestFlush()
    }

    private val reopens = IntArray(Target.entries.size)
    /** Refusals that can pass (a rate limit, the community relay's whitelist) since the last whole answer, per relay. */
    private val refusals = IntArray(Target.entries.size)

    /**
     * The relay ended the live subscription: without a new one nothing arrives until a restart
     * (web sync.ts, 2026-11-11). What happens is [Reopen.how] (SPEC 19.79): an ordinary close is
     * opened again while the socket lives, 2 s later (then 4, 8… up to a minute if the relay keeps
     * closing it); a rate limit, and the community relay's "restricted: pubkey not in whitelist",
     * after 2 s doubling to 2 minutes; a refusal about membership never (the groups relay's: the
     * register is asked, [noteRefusal]). `auth-required` is not handled here: the relay asks for
     * NIP-42 and the subscriptions are sent again after it.
     */
    private fun reopen(t: Target, reason: String) {
        val ms = when (Reopen.how(t == Target.MAIN, reason)) {
            Reopen.How.NEVER -> return
            Reopen.How.SOON -> Reopen.soonMs(reopens[t.ordinal].also { reopens[t.ordinal] = minOf(it + 1, 5) })
            Reopen.How.BACKED_OFF -> Reopen.backedOffMs(refusals[t.ordinal].also { refusals[t.ordinal] = minOf(it + 1, 6) })
        }
        scope.launch(Dispatchers.Default) {
            delay(ms)
            val r = relay(t) ?: return@launch
            if (!stopped && r.isUp && _status.value.access != AccessState.REFUSED) r.subscribe(SUB, filters(t))
        }
    }

    /**
     * Every socket (groups, main, the signer's) is asked at once; the dead are dropped and opened
     * again now; then the signer is said hello to ahead of the next send, and what waits goes.
     */
    private fun checkConnections(catchUp: Boolean = false) {
        launchSafely {
            connections.checkAll()
            if (catchUp) catchUpOnReturn()
            requestFlush()
            (signer as? BunkerSigner)?.warm()
        }
    }

    /** A wake-up: the app is back in the foreground. Checks every socket, reconnects now and sends what waits. */
    fun wake() {
        connections.foreground = true
        if (!started || stopped || localOnly) return
        if (_status.value.online) checkConnections(catchUp = true)
        launchSafely { dm.process() }
        lookRemoved() // back in the foreground (SPEC 19.85)
        requestFlush()
    }

    /**
     * Back in the foreground (SPEC 19.79; web af8e002, Xavier 2026-11-23): whatever the socket check
     * said, each relay whose socket lives gets a fresh subscription from the last event received
     * (the catch-up mark of [filters]; an hour's overlap, gift wraps three days), so what came while
     * the app slept arrives even when the socket survived and the relay dropped or never delivered
     * the live one. A dead socket was dropped by the check: it subscribes again when it reconnects.
     * Once per 2 s (a foreground, a network change and "Check again" can come together).
     */
    private fun catchUpOnReturn() {
        val now = Relay.nowMs()
        if (caughtUp && now - caughtUpAt < CATCH_UP_GAP_MS) return
        caughtUpAt = now
        caughtUp = true
        for (t in Target.entries) { val r = relay(t) ?: continue; if (r.isUp) r.subscribe(SUB, filters(t)) }
    }
    @Volatile private var caughtUpAt = 0L
    @Volatile private var caughtUp = false

    /** The app went to the background: quiet sockets are no longer asked (the system decides now). */
    fun background() { connections.foreground = false }

    /**
     * The device's connectivity changed (online or not; also a new network while online, Wi-Fi to
     * mobile: the old sockets are dead then). Offline drops the sockets (they are dead anyway).
     */
    fun setOnline(online: Boolean) {
        val was = _status.value.online
        if (was != online) setStatus { it.copy(online = online) }
        if (!started || stopped || localOnly) return
        if (!online) {
            if (was) for (t in Target.entries) relay(t)?.stop()
            return
        }
        if (!was) for (t in Target.entries) { val r = relay(t) ?: continue; r.subscribe(SUB, filters(t)); r.keepConnected() }
        trace.restart()
        checkConnections()
        requestFlush()
    }

    /**
     * "Retry" on a message that is not out after 30 s: every socket checked now, the backoff
     * forgotten, a fresh 30 s, and a pass at once.
     */
    suspend fun sendNow() {
        if (stopped) return
        retryStep = 0
        trace.restart()
        if (!localOnly && _status.value.online) {
            connections.checkAll()
            (signer as? BunkerSigner)?.warm()
        }
        flush()
    }

    /** What About Belfry → Diagnostics shows (copyable): each socket's state, then the last sends' timing. No content, no keys. */
    fun diagnostics(): String = buildString {
        append("Connections\n")
        if (localOnly) append("demo: no network\n")
        for (e in connections.all) {
            val q = e.relay.quietMs()
            append("${e.role}: ${e.relay.state.value.name.lowercase()}" + (if (q > 0) ", quiet ${SendTrace.secs(q)}" else "") + "\n")
        }
        append("online: ${_status.value.online}, foreground: ${connections.foreground}\n")
        if (caughtUp) append("caught up on return: ${SendTrace.secs(Relay.nowMs() - caughtUpAt)} ago\n")
        append("\nLast sends\n")
        val l = trace.lines()
        if (l.isEmpty()) append("none yet\n") else l.forEach { append(it).append('\n') }
    }

    /** Closes the connections and stops the background work. The outbox stays in the store. */
    fun stop() {
        stopped = true
        connections.stop()
        val all = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        all.forEach { it.cancel() } // outside the lock: a finished job removes itself
        (signer as? BunkerSigner)?.disconnect()
    }

    /** Ends this device's session with the belfry (best effort, 3 s; not when REVOKE is false: the belfry closed it), stops, and wipes everything stored. */
    suspend fun signOut(revoke: Boolean = true) {
        if (!localOnly && revoke) {
            val b = signer as? BunkerSigner
            if (b != null) withTimeoutOrNull(3_000) {
                try { api().revokeSession(signer, b.bunker.remotePubkey) } catch (e: Exception) { /* offline: an admin or the member can end it later */ }
            }
        }
        stop()
        sealedKeys?.wipe() // the letterbox secrets go with the session; a late write is refused
        enc3?.wipe() ?: enc3Keys?.wipe() // format 3's conversation keys too (the format cache is the device's: it stays)
        withContext(Dispatchers.IO) { store.wipe() }
    }

    /**
     * The relay's refusal alone never locks a member out: the register has the last word. Only a
     * refusal about membership counts ([Access.aboutMembership], SPEC 19.79): a rate limit, a
     * connection or a timeout in the relay's words changes nothing here ([reopen] tries again).
     */
    private fun noteRefusal(reason: String) {
        if (!Access.aboutMembership(reason) || _status.value.access == AccessState.REFUSED) return
        setStatus { it.copy(access = AccessState.RELAY_REFUSED) }
        launchSafely { confirmRefusal() }
    }

    private suspend fun confirmRefusal() {
        val a = registerAnswer()
        if (RegisterAnswer.fullScreen(a) && _status.value.access == AccessState.RELAY_REFUSED) setStatus { it.copy(access = AccessState.REFUSED) }
    }

    /** "Check again": reconnect, and ask the register again (a member let in since, or a wrong reading). */
    suspend fun checkAccess() {
        val a = registerAnswer()
        if (!RegisterAnswer.fullScreen(a) && _status.value.access == AccessState.REFUSED) setStatus { it.copy(access = AccessState.RELAY_REFUSED) }
        wake()
    }

    /** The register's word on my own key (POST /whois). */
    internal suspend fun registerAnswer(): RegisterAnswer {
        if (localOnly) return RegisterAnswer.MEMBER
        val got = try {
            api().whois(signer, listOf(me))
        } catch (e: ApiException) {
            return if (e.status == 401 || e.status == 403) RegisterAnswer.NO_ROLE else RegisterAnswer.NO_ANSWER
        } catch (e: Exception) {
            return RegisterAnswer.NO_ANSWER
        }
        return RegisterAnswer.of(got[me])
    }

    /**
     * A group this device learns of after its first whole answer (admitted to a closed one, a new
     * channel) has history older than the catch-up mark: asked once on its own, from 0 (the lead's
     * rule of 2026-11-19, from iOS to-do 587ba310). Marked as caught up only once its answer came.
     */
    private suspend fun catchUpNewGroups() {
        val r = groupsRelay ?: return
        val present = synchronized(logLock) { log.filter { it.kind == 39000 }.mapNotNull { it.tag("d") }.toSet() }
        val seen = store.get(K_GROUPS_SEEN)?.split(',')?.filter { it.isNotEmpty() }?.toMutableSet()
        if (seen == null) { store.set(K_GROUPS_SEEN, present.joinToString(",")); return }
        for (id in present - seen) {
            val got = r.query(listOf(Filters.of(kinds = KINDS_GROUPS, limit = 2000, tags = mapOf("h" to listOf(id)))), 10_000)
            // nothing back: maybe no answer in time; asked again after the next whole answer
            if (!r.isAuthenticated || got.isEmpty()) continue
            addToLog(got)
            seen.add(id)
            store.set(K_GROUPS_SEEN, seen.joinToString(","))
        }
    }

    private fun latest(pred: (Event) -> Boolean): Long = synchronized(logLock) {
        var at = 0L
        for (e in log) if (e.createdAt > at && pred(e)) at = e.createdAt
        at
    }

    private fun filters(t: Target): List<JsonObject> {
        if (t == Target.GROUPS) {
            val kinds = KINDS_GROUPS.toSet()
            // a member who has never had the whole groups relay on this device (a guest until now:
            // the relay showed them only their own channels) asks for everything once
            val full = !localOnly && _guest.value == null && store.get(K_GROUPS_FULL) == null
            if (full) groupsFullAsked = true
            // catch up from the newest event of the last answer given after NIP-42, never from the
            // log's newest (a public answer before AUTH would move it past what was never asked)
            val mark = store.get(K_GROUPS_MARK)?.toLongOrNull() ?: 0L
            return GroupFilters.of(KINDS_GROUPS, if (localOnly) latest { it.kind in kinds } else mark, full)
        }
        val kinds = KINDS_MAIN.toSet()
        val since = maxOf(0, latest { it.kind in kinds } - 3600)
        val wraps = maxOf(0, latest { it.kind == 1059 } - 3 * 86400)
        val old = maxOf(0, latest { it.kind == 4 } - 3600)
        val extras = maxOf(0, latest { it.kind == 30078 && it.hasTag("d", World.PROFILE_D) } - 3600)
        return listOf(
            Filters.of(kinds = KINDS_MAIN, since = since, limit = 2000),
            Filters.of(kinds = listOf(30078), since = extras, limit = 2000, tags = mapOf("d" to listOf(World.PROFILE_D))),
            // my read state (SPEC 19.27): one replaceable event, always the exact d
            Filters.of(kinds = listOf(ReadState.KIND), authors = listOf(me), limit = 5, tags = mapOf("d" to listOf(ReadState.READ_D))),
            // the admins' channel order with its pins for everyone (SPEC 19.34, docs/one-shape.md §1): published here
            Filters.of(kinds = listOf(30078), limit = 20, tags = mapOf("d" to listOf(World.ORDER_D))),
            // my own pins (docs/one-shape.md §1): the same pattern
            Filters.of(kinds = listOf(PinSync.KIND), authors = listOf(me), limit = 5, tags = mapOf("d" to listOf(sh.belfry.core.model.OneShape.PINS_D))),
            Filters.of(kinds = listOf(1059), since = wraps, limit = 300, tags = mapOf("p" to listOf(me))), // the relay's max limit
        ) + listOfNotNull(
            // sealed: key hand-overs come to this DEVICE's key (§5.5), not to the account
            sealed?.device?.pubkey?.let { Filters.of(kinds = listOf(1059), limit = 300, tags = mapOf("p" to listOf(it))) },
            // sealed: my correspondents' rosters and anchors on the live subscription (§4; the review's 1)
            sealedPeers().also { subscribedPeers = it.toSet() }.takeIf { it.isNotEmpty() && sealed != null }?.let { Filters.of(kinds = listOf(sh.belfry.core.sealed.Roster.KIND), limit = 300, tags = mapOf("p" to it)) },
            sealedPeers().takeIf { it.isNotEmpty() && sealed != null }?.let { Filters.of(kinds = listOf(30078), authors = it, limit = 300, tags = mapOf("d" to listOf(sh.belfry.core.sealed.Anchor.D))) },
        ) + enc3?.filters().orEmpty() + listOfNotNull(
            // format 3, people (SPEC 19.83; §6, §18.1): envelopes and messages for me, ordinary catch-up (§17.11)
            enc3?.let { Filters.of(kinds = listOf(sh.belfry.core.enc.Enc.KEY, sh.belfry.core.enc.Enc.MSG), since = maxOf(0, latest { it.kind == sh.belfry.core.enc.Enc.KEY || it.kind == sh.belfry.core.enc.Enc.MSG } - 3600), limit = 300, tags = mapOf("p" to listOf(me))) },
            // SPEC 19.85: packs to me, asks and answers of my account (live; the catch-up reads them whole)
            enc3?.let { Filters.of(kinds = listOf(sh.belfry.core.enc.Enc.PACK, sh.belfry.core.enc.Enc.ASK, sh.belfry.core.enc.Enc.ANSWER), limit = 300, tags = mapOf("p" to listOf(me))) },
        ) + listOf(
            Filters.of(kinds = listOf(4), since = old, limit = 500, tags = mapOf("p" to listOf(me))),
            Filters.of(kinds = listOf(4), authors = listOf(me), since = old, limit = 500),
        )
    }

    /** belfry.json again (admins, name) and the well-known keys; the cached copies are used offline. */
    suspend fun refreshCommunity() {
        if (localOnly) return
        readBelfryJson()
        try {
            Discovery.bridgeKey(community.domain, http)?.let { bridge = it; store.set(K_BRIDGE, it) }
            // belfry.json names it now (`groups_key`); nostr.json `_groups` for a belfry not yet updated
            if (community.groupsKey == null) Discovery.groupsKey(community.domain, http)?.let { groupsKey = it; store.set(K_GROUPS_KEY, it) }
        } catch (e: IOException) {
            // offline: the stored ones
        }
        markDirty()
    }

    /**
     * belfry.json asked again (no-cache; 304 when the copy here is the belfry's current one). A successful read feeds
     * this device's format cache (SPEC 19.82); a 304 on a copy that doesn't say its `encrypted` is asked again whole.
     * False: no answer (offline: what we have).
     */
    private suspend fun readBelfryJson(): Boolean {
        try {
            var etag = store.get(K_DISCOVERY_ETAG)
            for (attempt in 0 until 2) {
                when (val got = Discovery.revalidate(community.domain, http, etag)) {
                    is Revalidated.NotModified -> {
                        val doc = community.encrypted
                        if (doc == null) { etag = null; continue } // a copy from an older build: read it whole once
                        encRead(doc)
                    }
                    is Revalidated.Fresh -> {
                        if (got.community != community) {
                            community = got.community
                            onRecordChanged?.invoke(got.community, _guest.value)
                        }
                        if (got.etag != null) store.set(K_DISCOVERY_ETAG, got.etag) else store.remove(K_DISCOVERY_ETAG)
                        extraAdmins = emptySet()
                        store.remove(K_ADMINS)
                        got.community.encrypted?.let { encRead(it) }
                    }
                }
                return true
            }
        } catch (e: IOException) {
            // offline: what we have
        }
        return false
    }

    private fun encRead(doc: String) {
        val st = encFormat.record(doc, clock())
        enc3?.setFormat3(st.format == 3)
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
        guard()
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
        // guest mode: nothing is queued, but a plain message in a topic reached through a card (SPEC 19.77)
        val g = _guest.value
        val guestPost = drafts.size == 1 && drafts[0].let { GuestTopic.mayPost(g?.reading == true, g?.channel, g?.card, it.target, it.kind, it.tags, attachments.map { a -> a.mime }) }
        if (!guestPost) guard()
        // nothing is queued for an archived or deleted group, whatever a screen forgot to hide (SPEC 19.30)
        val locked = _world.value.lockedGroups
        if (drafts.any { GroupLock.refuses(it, locked) }) throw ActionException("archived", ARCHIVED_TEXT)
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
        guard()
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
        trace.forget(item.dm ?: item.localId)
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
                val queued = store.outbox().filter { !held(it) }
                if (queued.none { it.status == OutboxStatus.QUEUED }) break
                if (!localOnly && !_status.value.online) break
                // sealed wraps whose recipient's head moved are sealed again first (§9), under this lock; and
                // only once the community relay is authenticated (a refresh before AUTH learns nothing; the review's 8)
                val authed = mainRelay?.isAuthenticated == true
                val items = if (sealed == null) queued else dm.resealQueued(queued.filter { authed || !dm.isSealedQueued(it) }).also { again ->
                    val before = queued.associateBy { it.localId }
                    for (b in again) if (before[b.localId]?.signed?.id != b.signed?.id) store.putOutbox(b)
                }
                val before = items.associateBy { it.localId }
                val signedNow = HashMap<String, Event>()
                val handled = HashSet<String>()
                val result = Outbox.run(items, clock()) { item ->
                    val id = item.dm ?: item.localId
                    val label = SendTrace.label(item)
                    val outcome = sendOne(item, signedNow) { step -> trace.step(id, label, step) }
                    if (outcome is SendOutcome.Ok) { handled.add(item.localId); sent(item, outcome.event ?: item.signed) }
                    val cls = (outcome as? SendOutcome.Error)?.let { Outbox.classify(it.message, item, clock()) }
                    val said = when (cls) {
                        null, ErrorClass.DUPLICATE -> "OK"
                        ErrorClass.FAILED -> "refused"
                        ErrorClass.RETRY -> "retry (" + (outcome as SendOutcome.Error).message.substringBefore(':').take(24) + ")"
                    }
                    val others = item.dm != null && store.outbox().any { it.dm == item.dm && it.localId != item.localId && it.status == OutboxStatus.QUEUED }
                    trace.end(id, said, left = cls == ErrorClass.RETRY || others)
                    outcome
                }
                for (s in result.sent) if (s.item.localId !in handled) sent(s.item, signedNow[s.item.localId] ?: s.item.signed) // duplicates: out already
                for (left in result.items) if (before[left.localId] != left) store.putOutbox(left)
                for (f in result.failed) {
                    dm.onFailed(f)
                    // format 3: an envelope refused for good goes; the copies under its key make it again (envelopesFirst)
                    if (f.kind == sh.belfry.core.enc.Enc.KEY && f.dm == null && f.signed != null) store.dropOutbox(f.localId)
                }
                rebuild()
                if (result.sent.isNotEmpty()) retryStep = 0
                if (result.stalled.isNotEmpty()) { retrySoon(); break }
            } while (again)
        } finally {
            flushLock.unlock()
        }
    }

    // A send that failed for now (a dropped socket, a signer that was reconnecting) is tried again
    // within seconds, not at the next watchdog tick: 1 s, 2 s, 4 s, 8 s, 16 s, then every 30 s (SPEC 19.50).
    @Volatile private var retryStep = 0
    private val retryPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun retrySoon() {
        if (stopped || !retryPending.compareAndSet(false, true)) return
        val ms = Backoff.delayMs(retryStep + 1) // 1 s, 2 s, 4 s, 8 s, 16 s, then 30 s (SPEC 19.50)
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
        if (item.kind == sh.belfry.core.enc.Enc.KEY) item.signed?.let { enc3?.envelopeSent(it.id) } // format 3: confirmed out
        // a direct message's file stays on the device: it is what my message shows (never uploaded in clear)
        if (!localOnly && item.kind != DmFiles.KIND) item.attachments.forEach { store.dropBlob(it.blobKey); synchronized(uploaded) { uploaded.remove(it.blobKey) } }
        if (item.signed == null && event != null) {
            addToLog(listOf(event))
            if (localOnly) synthesizeRelayEvents(event)
        }
        dm.onSent(item)
        markDirty()
    }

    private suspend fun sendOne(item: OutboxItem, signedNow: MutableMap<String, Event>, step: (String) -> Unit = {}): SendOutcome {
        if (item.target == Target.API) { step(SendState.STEP_SIGN); return sendApi(item) }
        envelopesFirst(item)?.let { return it }
        if (item.kind == DmFiles.KIND && item.dm != null && item.signed == null) return sendDmFile(item, step)
        // format 3's device events (SPEC 19.82): signed here by the device key, dated now; never through the signer
        if (item.byDevice && item.signed == null) {
            val d = deviceKey() ?: return SendOutcome.Error("invalid: no device key")
            step(SendState.STEP_SIGN)
            val ev = d.signNow(EventTemplate(item.kind, item.content, item.tags, maxOf(clock(), item.createdAt)))
            signedNow[item.localId] = ev
            if (localOnly) return SendOutcome.Ok(ev)
            return publish(item.target, ev, step)
        }
        item.signed?.let { ev ->
            if (localOnly) return SendOutcome.Ok(ev)
            return publish(item.target, ev, step)
        }
        var content = item.content
        val tags = ArrayList<Tag>(item.tags)
        for (a in item.attachments) {
            val bytes = store.blob(a.blobKey) ?: continue
            step(SendState.STEP_UPLOAD)
            val url = if (localOnly) LOCAL_BLOB + a.blobKey else try {
                synchronized(uploaded) { uploaded[a.blobKey] } ?: api().upload(signer, bytes, a.name, a.mime) { sent, total -> step(SendState.uploading(sent, total)) }.also { u -> synchronized(uploaded) { uploaded[a.blobKey] = u } }
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
        step(SendState.STEP_SIGN)
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
        return publish(item.target, ev, step)
    }

    /**
     * A file for a direct message (vectors/dm-files.json): encrypted, uploaded and wrapped now
     * ([DirectMessages.prepareFile]); its wraps take its place in the outbox and go like any
     * message's. A refused upload (4xx but 401 / 408 / 429) fails it.
     */
    private suspend fun sendDmFile(item: OutboxItem, step: (String) -> Unit): SendOutcome {
        val wraps = try {
            dm.prepareFile(item, step)
        } catch (e: SignerTimeout) {
            setStatus { it.copy(signerTrouble = true) }
            return SendOutcome.Error("timeout: the signer did not answer")
        } catch (e: sh.belfry.core.dm.DirectMessages.FileWaits) {
            return SendOutcome.Error(e.message ?: "waiting") // not "invalid:": tried again
        } catch (e: IllegalStateException) {
            return SendOutcome.Error(e.message?.takeIf { it.startsWith("invalid:") } ?: "invalid: the file")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val said = e.message.orEmpty()
            return SendOutcome.Error(if (said.startsWith("upload failed")) said else "upload failed" + (said.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""))
        }
        val rid = dm.idOf(item.localId) ?: item.localId
        val key = dm.encKeyOf(rid)
        for (w in wraps) when {
            key != null && w.kind == sh.belfry.core.enc.Enc.KEY -> enqueueSigned(Target.MAIN, w, null) // format 3: the key's envelopes first
            key != null && w.kind == sh.belfry.core.enc.Enc.MSG -> enqueueEnc(w, rid, key)
            else -> enqueueSigned(Target.MAIN, w, rid)
        }
        return SendOutcome.Ok(null)
    }

    /**
     * A file of a direct message, decrypted (null: "This file can't be opened"). Mine not sent yet
     * comes from the device; another is downloaded once, checked (x, GCM, ox) and kept on the device
     * (the app's private store) by its ox.
     */
    suspend fun dmFile(d: Dm): ByteArray? = withContext(Dispatchers.IO) {
        val f = d.file ?: return@withContext null
        f.localBlob?.let { k -> store.blob(k)?.let { return@withContext it } }
        val ox = f.ox ?: return@withContext null
        store.blob("dmf:$ox")?.let { return@withContext it }
        val got = try {
            if (f.url.startsWith(LOCAL_BLOB)) store.blob(f.url.removePrefix(LOCAL_BLOB)) else fetchCapped(f.url, DmFiles.MAX.toLong() + 1024)
        } catch (e: Exception) { null } ?: return@withContext null
        val plain = DmFiles.open(f.tags, got) ?: return@withContext null
        store.putBlob("dmf:$ox", plain)
        plain
    }

    private fun fetchCapped(url: String, max: Long): ByteArray? =
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body ?: return null
            if (body.contentLength() > max) return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) return null
                }
            }
            out.toByteArray()
        }

    /** Sends files (pictures, documents, a recording) in a direct message, with an optional caption after them. */
    suspend fun sendDmFiles(conv: String, files: List<DmNewFile>, caption: String? = null) {
        guard()
        lastDmSentMs = System.currentTimeMillis()
        dmCall { dm.sendFiles(conv, files, caption) }
    }

    /** A queued API call (SPEC 19.37): NIP-98 signed now, POSTed; the answer's status decides. */
    private suspend fun sendApi(item: OutboxItem): SendOutcome {
        // the demo plays the belfry: it publishes what the call asked for (a 9002 as its groups key)
        if (localOnly) return SendOutcome.Ok(Demo.groups.signNow(EventTemplate(item.kind, item.content, item.tags, clock())))
        val path = item.path ?: return SendOutcome.Error("invalid: no path")
        val body = parseJsonOrNull(item.body ?: "{}") as? JsonObject ?: return SendOutcome.Error("invalid: body")
        return try {
            val (status, answer) = api().signedFetch(signer, community.apiBase + path, "POST", body)
            Outbox.httpOutcome(status, answer?.s("error"))
        } catch (e: ApiException) {
            SendOutcome.Error("timeout: the signer did not answer")
        } catch (e: IOException) {
            SendOutcome.Error("timeout: no connection")
        }
    }

    /**
     * Queues a call to the belfry's API (`POST <api base><path>` with BODY) in the outbox: kept
     * through restarts and offline, sent in order, signed when it goes. KIND + TAGS are how it
     * shows meanwhile (a 9002 shows its new name and description at once, SPEC 19.37).
     */
    internal suspend fun queueApi(path: String, body: JsonObject, kind: Int, tags: List<Tag>): String = withContext(Dispatchers.Default) {
        guard()
        val id = enqueueLock.withLock {
            lastCreatedAt = Outbox.nextCreatedAt(lastCreatedAt, clock())
            val item = OutboxItem(newLocalId(), Target.API, kind, "", tags, lastCreatedAt, path = path, body = body.toString())
            store.putOutbox(item)
            item.localId
        }
        rebuild()
        requestFlush()
        id
    }

    /** Try again every refused change to GROUP_ID's settings ([World.failedSettings]). */
    suspend fun retrySettings(groupId: String) {
        for (item in _world.value.failedSettings(groupId)) retry(item.localId)
    }

    /** Gives up on them: what the relay has shows again. */
    suspend fun discardSettings(groupId: String) {
        for (item in _world.value.failedSettings(groupId)) discard(item.localId)
    }

    private suspend fun publish(target: Target, ev: Event, step: (String) -> Unit = {}): SendOutcome {
        val r = relay(target) ?: return SendOutcome.Error("timeout: no relay")
        return try {
            // a quiet socket is asked first; no answer in time drops it (Relay, SPEC 19.50): its
            // onDisconnect subscribes again from the last event seen, it reconnects within a second
            r.publish(ev, PUBLISH_TIMEOUT_MS, step)
            SendOutcome.Ok(ev)
        } catch (e: RelayException) {
            if (e.reason.startsWith("restricted") && target == Target.GROUPS) noteRefusal(e.reason)
            if (e.reason == "timeout: the relay did not answer") trace.note("${target.key}: no answer in ${PUBLISH_TIMEOUT_MS / 1000}s, socket dropped")
            SendOutcome.Error(e.reason)
        } catch (e: SignerTimeout) {
            SendOutcome.Error("timeout: the signer did not answer")
        }
    }

    // In the demo there is no relay to turn 9007/9002 into 39000 metadata and member lists.
    private fun synthesizeRelayEvents(ev: Event) {
        val h = ev.tag("h") ?: return
        if (ev.kind != 9007 && ev.kind != 9002) return
        val meta = ev.tags.filter { it.isNotEmpty() && it[0] in setOf("name", "about", "picture", "closed", "open", "prompt", "private", "public") }
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

    /**
     * A reading guest's message in the topic they reached through a card (SPEC 19.77; the web's
     * `postGuestMessage`): kind 9 with `[["h", T]]` only, its photos as `imeta`. Anywhere else, or
     * with a file that is not a picture, it is refused like every guest-mode write (the Join sheet).
     */
    suspend fun postGuestMessage(groupId: String, text: String, files: List<NewAttachment> = emptyList()): String =
        send(GuestTopic.post(groupId, text), files)

    /**
     * Presses BUTTON on message M in GROUP_ID (SPEC 19.36): signed and published on the groups
     * relay at once, never through the outbox; kept in the log so the state survives a restart.
     * Throws [ActionException] `not_sent` when it can't be sent (the button unlocks), and the
     * guest-mode refusal (the Join sheet opens). The demo answers a `belfry:` action itself.
     */
    suspend fun pressButton(groupId: String, m: Message, button: MessageButton): Event = withContext(Dispatchers.Default) {
        guard()
        val d = Buttons.press(m.id, m.pubkey, groupId, button.id)
        val at = enqueueLock.withLock { Outbox.nextCreatedAt(lastCreatedAt, clock()).also { lastCreatedAt = it } }
        val ev = try {
            withTimeout(PUBLISH_TIMEOUT_MS) { signer.sign(EventTemplate(d.kind, d.content, d.tags + listOf(Actions.CLIENT_TAG), at)) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) throw ActionException("not_sent", Buttons.NOT_SENT) else throw e
        } catch (e: Exception) {
            throw ActionException("not_sent", Buttons.NOT_SENT)
        }
        if (!localOnly) {
            val out = publish(Target.GROUPS, ev)
            if (out is SendOutcome.Error && Outbox.classify(out.message) != ErrorClass.DUPLICATE) throw ActionException("not_sent", Buttons.NOT_SENT)
        }
        addToLog(listOf(ev))
        if (localOnly && (button.action == Buttons.WEBSITE_PUBLISH || button.action == Buttons.REQUEST_CLOSE)) {
            val key = Demo.groups
            val name = _world.value.realName(me).ifEmpty { "you" }
            val said = if (button.action == Buttons.REQUEST_CLOSE) "Closed by $name." else "Published by $name."
            scope.launch(Dispatchers.Default) {
                delay(DEMO_ANSWER_MS)
                if (stopped) return@launch
                addToLog(listOf(key.signNow(EventTemplate(1985, said, listOf(
                    listOf("L", Buttons.RESULT_NS), listOf("l", "ok", Buttons.RESULT_NS), listOf("e", m.id), listOf("press", ev.id),
                    listOf("p", me), listOf("h", groupId), listOf("closed", "1"),
                ), clock()))))
            }
        }
        ev
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
    suspend fun toggleReaction(groupId: String, messageId: String, author: String, emoji: String, kind: Int = 9) {
        val w = _world.value
        val mine = w.reactionsOf(groupId, messageId).firstOrNull { it.emoji == emoji }?.mine
        if (mine != null && w.isPending(mine.id)) { discard(mine.id); return }
        Actions.toggleReaction(groupId, messageId, author, emoji, mine, false, kind)?.let { send(it) }
    }

    /** Opening a channel tells the others I was there, at most every 6 hours per channel (SPEC 11). */
    suspend fun markSeen(groupId: String) {
        if (reading) return // guest mode: reading leaves no trace
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
        synchronized(createdHere) { createdHere[id] = clock() }
        send(Actions.createChannel(id, name.trim(), about.trim()))
        return id
    }

    /** A new pod under PARENT (a channel or a pod), ADD put in by me right after its 9007 (SPEC 19.59); returns its id. */
    suspend fun createPod(name: String, about: String, parent: String, access: PodAccess = PodAccess(), add: List<String> = emptyList()): String {
        val id = Actions.newPodId(name)
        synchronized(createdHere) { createdHere[id] = clock() }
        send(Actions.createPod(id, name.trim(), about.trim(), parent, access, add.filter { it != me }))
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
        send(Actions.saveExtras(tags, share, place, _world.value.myExtrasJson))
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
        guard()
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
    /** Cancels a message held undecided (sealed.md §6): its text, for the composer. */
    suspend fun cancelHeldDm(rid: String): String? = dm.cancelHeld(rid)

    /** A conversation's sealed messages, for Share history (§8). */
    fun sealedRumors(conv: String): List<sh.belfry.core.nostr.Event> = dm.sealedRumorsOf(conv)

    suspend fun sendDm(conv: String, text: String, subject: String? = null, root: String? = null): String { lastDmSentMs = System.currentTimeMillis(); return dmCall { dm.send(conv, text, subject, root = root) } }

    /** When this phone last sent a DM or e-mail (ms): a "dm" push right after it is my own copy (SPEC 19.19). */
    @Volatile var lastDmSentMs: Long = 0L
        private set

    // ---- a member link's last step: the name and username (POST /welcome, membership.md 2026-11-19) ----

    /** The name-and-username screen is due (a member link was just used on this device). */
    fun nameDue(): Boolean = !localOnly && store.get(K_NAME_DUE) == "1"
    fun clearNameDue() = store.remove(K_NAME_DUE)

    /** POST /welcome signed by my own key; SIGNED_IN (set, or nothing to pick) = to the chat. */
    suspend fun welcome(name: String, username: String): sh.belfry.core.model.WayIn.Outcome = withContext(Dispatchers.Default) {
        val body = buildJsonObject { put("name", JsonPrimitive(name.trim().take(100))); put("username", JsonPrimitive(username.trim().lowercase())) }
        val (status, obj) = try { api().signedFetch(signer, community.apiBase + "/welcome", "POST", body) } catch (e: Exception) {
            return@withContext sh.belfry.core.model.WayIn.Outcome(sh.belfry.core.model.WayIn.Screen.STAY, "No connection. Try again.")
        }
        sh.belfry.core.model.WayIn.next("welcome", status, obj, community.domain)
    }

    /** POST /username/check signed by my key: the line under "Pick a username:" and up to three suggestions. */
    suspend fun usernameCheck(username: String): Pair<String, List<String>>? = withContext(Dispatchers.Default) {
        val (status, obj) = try { api().signedFetch(signer, community.apiBase + "/username/check", "POST", buildJsonObject { put("username", JsonPrimitive(username.trim())) }) } catch (e: Exception) { return@withContext null }
        if (status != 200 || obj == null) null else sh.belfry.core.model.WayIn.usernameLine(obj, community.domain) to sh.belfry.core.model.WayIn.usernameOffers(obj)
    }

    /** A chip's tap under a direct message: add EMOJI, or take mine back (canvas home/conversation). */
    suspend fun toggleDmReaction(d: sh.belfry.core.dm.Dm, emoji: String) { lastDmSentMs = System.currentTimeMillis(); dmCall { dm.toggleReaction(d, emoji) } }

    /** Puts a refused direct message or e-mail back in the outbox. */
    suspend fun retryDm(rid: String) { lastDmSentMs = System.currentTimeMillis(); dmCall { dm.retry(rid) } }

    fun markRead(conv: String) = dm.markRead(conv)

    // ---- read state (SPEC 19.27) ---------------------------------------------------------------------

    /** The channel is on screen, scrolled to the newest message, app in the foreground: read up to its newest message. */
    fun markChannelRead(groupId: String) {
        Unread.channelMark(_world.value, groupId)?.let { readSync.mark('c', groupId, it) }
    }

    /** A thread is on screen: read up to its newest reply (the message itself when there is none). */
    fun markThreadRead(groupId: String, rootId: String) {
        Unread.threadMark(_world.value, groupId, rootId)?.let { readSync.mark('t', rootId, it) }
    }

    /** The @Mentions list was opened: seen up to its newest mention. */
    fun markMentionsSeen(at: Long) = readSync.mark('m', "", at)

    /** The app goes to the background: what waits is published now. */
    fun flushRead() = readSync.flush()

    // ---- pins (docs/one-shape.md §1) ------------------------------------------------------------------

    /** Pins [channel] at the top of my own menu, or unpins it. Guests, guest mode and the demo keep it on this device. */
    fun togglePin(channel: String) = pinSync.toggle(channel)

    /** A belfry admin pins [channel] for everyone, or unpins it: `pinned` in the channel-order event, every other key kept. */
    suspend fun setEveryonePin(channel: String, on: Boolean) {
        val w = _world.value
        val cur = w.everyonePins()
        val next = if (on) (if (channel in cur) cur else cur + channel) else cur.filter { it != channel }
        send(Actions.everyonePins(w.channelOrderContent, next))
    }

    private inner class PinHost : PinSync.Host {
        override fun shared(): Boolean = !localOnly && !reading && !stopped && !(_guest.value != null && _world.value.amGuest)
        override suspend fun encryptToSelf(text: String): String = signer.nip44Encrypt(me, text)
        override suspend fun decryptFromSelf(payload: String): String = signer.nip44Decrypt(me, payload)
        override suspend fun publish(content: String, minCreatedAt: Long) = withContext(Dispatchers.Default) {
            enqueueLock.withLock {
                // the newest list replaces one still waiting
                for (o in store.outbox()) if (o.kind == PinSync.KIND && o.status == OutboxStatus.QUEUED && o.tags.any { it.size >= 2 && it[0] == "d" && it[1] == sh.belfry.core.model.OneShape.PINS_D }) store.dropOutbox(o.localId)
                lastCreatedAt = Outbox.nextCreatedAt(maxOf(lastCreatedAt, minCreatedAt - 1), clock())
                store.putOutbox(OutboxItem(
                    localId = newLocalId(), target = Target.MAIN, kind = PinSync.KIND, content = content,
                    tags = listOf(listOf("d", sh.belfry.core.model.OneShape.PINS_D), Actions.CLIENT_TAG), createdAt = lastCreatedAt,
                ))
            }
            countOutbox()
            requestFlush()
        }
    }

    private inner class ReadHost : ReadSync.Host {
        override fun shared(): Boolean = !localOnly && !reading && !stopped && !(_guest.value != null && _world.value.amGuest)
        override suspend fun encryptToSelf(text: String): String = signer.nip44Encrypt(me, text)
        override suspend fun decryptFromSelf(payload: String): String = signer.nip44Decrypt(me, payload)
        override suspend fun publish(content: String, minCreatedAt: Long) = withContext(Dispatchers.Default) {
            enqueueLock.withLock {
                // a newer read state replaces one still waiting (only one counts on the relay anyway)
                for (o in store.outbox()) if (o.kind == ReadState.KIND && o.status == OutboxStatus.QUEUED && o.tags.any { it.size >= 2 && it[0] == "d" && it[1] == ReadState.READ_D }) store.dropOutbox(o.localId)
                lastCreatedAt = Outbox.nextCreatedAt(maxOf(lastCreatedAt, minCreatedAt - 1), clock())
                store.putOutbox(OutboxItem(
                    localId = newLocalId(), target = Target.MAIN, kind = ReadState.KIND, content = content,
                    tags = listOf(listOf("d", ReadState.READ_D), Actions.CLIENT_TAG), createdAt = lastCreatedAt,
                ))
            }
            countOutbox()
            requestFlush()
        }
    }

    /** Where a new conversation with INPUT goes (a name, name@domain, key, npub or e-mail address); null = not understood. */
    suspend fun convFor(input: String): String? {
        val w = _world.value
        return dm.convFor(input, w.people.map { (pk, name) -> DmCandidate(pk, name, w.profiles[pk]?.nip05) })
    }

    /**
     * Answers an e-mail card as the shared inbox channel (its admins only). Returns the email-id for
     * `dms.value.report(id)`. Reply all (docs/one-shape.md 4): [to] = the sender, [cc] = the others,
     * one `rcpt-to` each; [draftId] = the internal draft it sends. Once the bridge reports it
     * delivered, this device labels [threadId] (the thread's first card) `answered`, once.
     */
    suspend fun sendAsChannel(
        channel: String, to: String, subject: String, text: String, inReplyTo: String?, cardId: String, references: List<String> = emptyList(),
        cc: List<String> = emptyList(), draftId: String? = null, threadId: String = cardId,
    ): String = dmCall {
        guard()
        val w = _world.value
        val name = w.group(channel)?.name ?: channel
        // SPEC 19.33: from the channel's plain alias; References = the thread's other ids (last 10) then In-Reply-To
        val refs = (references.filter { it != inReplyTo }.takeLast(10) + listOfNotNull(inReplyTo)).joinToString(" ").ifEmpty { null }
        val id = dm.sendAsChannel(channel, name, to, subject, text, inReplyTo, cardId, w.mailLocal(channel), refs, cc, draftId)
        if (localOnly) {
            // the demo plays the bridge: the answer shows in the channel, with who it went to
            val key = groupsKey
            val copied = cc.filter { !it.equals(to, ignoreCase = true) }.distinct()
            // the names the card it answers gave (the bridge keeps them the same way)
            val card = w.all.firstOrNull { it.id == cardId }
            val names = HashMap<String, String>()
            if (card != null) {
                for (t in card.tags) if (t.size >= 3 && (t[0] == "to" || t[0] == "cc")) names[t[1].lowercase()] = t[2]
                card.tag("mail-from")?.let { a -> names[a.lowercase()] = card.tag("mail-name").orEmpty() }
            }
            fun named(a: String) = names[a.lowercase()].orEmpty()
            if (key != null) addToLog(listOf(Event("out-" + Random.hex(8), key, clock(), 9,
                listOf(listOf("h", channel), listOf("subject", subject), listOf("mail-to", to), listOf("to", to, named(to))) + copied.map { listOf("cc", it, named(it)) } +
                    listOf(listOf("sent-by", me), listOf("e", cardId, "", "reply"), listOf("client", "belfry-mail-out")) + listOfNotNull(draftId?.let { listOf("draft", it) }),
                text)))
        }
        answerOnDelivery(id, channel, threadId)
        id
    }

    private val answeredSent = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** docs/one-shape.md 4: after the delivery report says delivered, the thread's first card gets `answered` (once per e-mail). */
    private fun answerOnDelivery(emailId: String, channel: String, threadId: String) {
        scope.launch(Dispatchers.Default) {
            val r = withTimeoutOrNull(ANSWER_WAIT_MS) {
                dm.snapshot.first { it.report(emailId)?.status.let { s -> s == Report.DELIVERED || s == Report.FAILED } }.report(emailId)
            } ?: return@launch
            if (r.status != Report.DELIVERED || !answeredSent.add(emailId) || stopped) return@launch
            try { send(Actions.markAnswered(channel, threadId)) } catch (e: Exception) { /* guest mode or stopped: nothing to label */ }
        }
    }

    /** An e-mail's attachments with their bytes. */
    suspend fun attachmentsOf(d: Dm): List<MailPart> = withContext(Dispatchers.Default) { dm.attachmentsOf(d) }

    /** Asks the bridge again how my e-mails fared (also runs by itself after a send). */
    suspend fun checkReports() = try { dm.checkReports() } catch (e: Exception) { }

    private suspend fun <T> dmCall(block: suspend () -> T): T = withContext(Dispatchers.Default) {
        try { block() } catch (e: DmException) { throw ActionException("dm", e.message ?: "That did not work. Try again.") }
    }

    private inner class Host : DmHost {
        override val sealed: sh.belfry.core.sealed.SealedEngine? get() = this@Session.sealed
        override fun belfryKey(): String? = community.belfryPubkey
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
        override fun preparing(id: String) { if (!localOnly) trace.step(id, "DM", SendState.STEP_PREPARE) }
        override fun notPrepared(id: String) { trace.end(id, "not prepared", left = false) }
        override val isGuest: Boolean get() = _guest.value != null
        override fun putBlob(key: String, bytes: ByteArray) { store.putBlob(key, bytes) }
        override fun blob(key: String): ByteArray? = store.blob(key)
        override suspend fun enqueue(item: OutboxItem) {
            guard()
            store.putOutbox(item)
            countOutbox()
            requestFlush()
        }
        override suspend fun uploadSealed(bytes: ByteArray, progress: (Long, Long) -> Unit): String {
            // the demo plays the storage: the ciphertext stays on the device under a local address
            if (localOnly) { val k = "dmc:" + sha256Hex(bytes); progress(bytes.size.toLong(), bytes.size.toLong()); store.putBlob(k, bytes); return LOCAL_BLOB + k }
            return api().upload(signer, bytes, "file", "application/octet-stream", progress)
        }
        override fun renamed(from: String, to: String) { trace.rename(from, to) }
        override val enc3: Enc3? get() = this@Session.enc3
        override suspend fun encWrite(conv: String): sh.belfry.core.enc.EncWrite = encWriteDm(conv)
        override suspend fun enqueueEnc(event: Event, dm: String, key: String): String = this@Session.enqueueEnc(event, dm, key)
        override suspend fun dropOutbox(localId: String) { store.dropOutbox(localId); countOutbox() }
        override fun format3Since(): Long? = if (localOnly) null else encFormat.state().format3Since
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
        // "no answer" is not "nobody" (fourth review 1): convFor never turns a member into e-mail in clear on a failed lookup
        override suspend fun nip05(name: String): String? = when (val a = Discovery.nip05Answer(name, community.domain, http)) {
            is Discovery.Nip05Answer.Key -> a.pubkey
            Discovery.Nip05Answer.None -> null
            Discovery.Nip05Answer.Failed -> throw java.io.IOException("no NIP-05 answer")
        }
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

    /** Membership: roles, requests, the directory, the charter, a guest's request (engine/Members.kt). */
    val members: MembersService by lazy { MembersService(this) }
    /** Channels' e-mail addresses through the belfry (SPEC 19.60). */
    val mailAlias: MailAliasService by lazy { MailAliasService(this) }
    /** The printed card a group was started from, for its stewards (SPEC 19.76). */
    val groupCards: GroupCardService by lazy { GroupCardService(this) }

    /** Sign in to another app with this account through the belfry (engine/RemoteApps.kt, SPEC 19.47). */
    val remoteApps: RemoteAppsService by lazy { RemoteAppsService(this) }
    /** End-to-end encrypted conversations: screens only, the demo fills them (canvas "Messages · encryption"). */
    val encryption: EncryptionService by lazy { EncryptionService(this) }

    /** For the services: refuses a write in guest mode (opens the Join sheet). */
    internal fun checkWrite() = guard()

    /** For the services: belfry.json again after a change it reflects (admins, agents); best effort. */
    internal suspend fun refreshCommunityQuietly() { try { refreshCommunity() } catch (e: Exception) { } }

    /** For the services: build the world again now (after a local change that is not an event). */
    internal fun rebuildNow() = rebuild()

    /** For the services: the demo's own events, as if a relay had sent them. */
    internal fun addLocalEvents(events: List<Event>) = addToLog(events)

    /** WRITE: it changes something at the belfry (refused in guest mode). */
    internal suspend fun <T> apiCall(write: Boolean, block: suspend (BelfryApi) -> T): T { if (write) guard(); return call(block) }
    /** The zone times are set in on this device (a new version of an event keeps it). */
    internal val zoneId: String get() = zone.id
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
        guard()
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
        guard()
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
        guard()
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
        guard()
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
        if (joined.member) { becameMember(joined.channel); return joined }
        if (joined.guest && _guest.value == null) setGuest(GuestInfo(joined.channel, false, card = joined.card))
        relay(Target.GROUPS)?.let { it.subscribe(SUB, filters(Target.GROUPS)) } // the new channel's history
        return joined
    }

    /** The belfry just made a group for me (a printed card's claim, SPEC 19.72): the groups relay is asked again, for it and its history. */
    internal suspend fun groupsAgain() {
        if (localOnly) return
        try { relay(Target.GROUPS)?.let { it.subscribe(SUB, filters(Target.GROUPS)) } } catch (e: Exception) { }
    }

    /**
     * Join from guest mode, or save a guest account (SPEC 19.26): a 6-digit code goes by e-mail to
     * EMAIL. NAME: in guest mode (required there), else null. Throws [GuestJoinException].
     */
    suspend fun guestSave(email: String, name: String? = null) {
        val n = name?.let(GuestMode::cleanName)
        if (reading && n.isNullOrEmpty()) throw GuestJoinException("name_needed")
        if (!GuestMode.isEmail(email)) throw GuestJoinException("invalid")
        if (localOnly) { delay(300); return } // demo: no mail; the code is 123456
        guestCall { it.guestSave(signer, email.trim(), n) }
    }

    /**
     * The code from the e-mail: the account is saved under that address (and, in guest mode, that
     * name: the channel then gets the join line). Guest mode ends. Throws [GuestJoinException]
     * (`taken` with [GuestJoinException.moved]: sign in with that address instead).
     */
    /**
     * The guest's code. Returns a [GuestSignIn] when the address is another account's here and the
     * belfry opened a session for it on this phone's new key (canvas onboarding/guest-code v41):
     * the caller swaps this guest for that account. Null otherwise (saved, or a member now).
     */
    suspend fun guestVerify(email: String, code: String, name: String? = null, summary: String? = null): GuestSignIn? {
        val n = name?.let(GuestMode::cleanName)?.takeIf { it.isNotEmpty() }
        val g = _guest.value
        // SUMMARY (the e-mail summary, `weekly` or `monthly`) goes only when a topic card's reading guest
        // follows that topic with a name and an e-mail (SPEC 19.77); nowhere else, whatever a screen passed
        val sum = if (summary != null && n != null && g != null && GuestTopic.here(g.reading, g.channel, g.card, g.channel)) GuestTopic.summary(summary) else null
        summarySent = sum
        if (localOnly) { // demo: what the belfry would do
            delay(300)
            if (email.trim().lowercase() == GuestMode.DEMO_TAKEN) throw GuestJoinException("taken", moved = true, nip05 = "ana@${community.domain}")
            if (code != GuestMode.DEMO_CODE) throw GuestJoinException("wrong_code", triesLeft = 4)
            if (g?.reading == true && n != null) addToLog(GuestMode.demoJoined(g.channel, me, n, clock()))
            if (g?.member != null) { becameMember(g.channel); return null }
        } else {
            // a guest of this device: a new session key, the one an account's session would be bound to
            var key = if (g != null) LocalSigner.random() else null
            val saved = try {
                guestCall { it.guestVerify(signer, email.trim(), code.trim(), n, key?.pubkey, sum) }
            } catch (e: GuestJoinException) {
                // 422 invalid with a session key: the key was refused, the code is not spent: another key, the same code
                if (key == null || e.code != "invalid") throw e
                key = LocalSigner.random()
                guestCall { it.guestVerify(signer, email.trim(), code.trim(), n, key.pubkey, sum) }
            }
            saved.signedIn?.let { return it.copy(secret = key!!.secretHex) }
            // a member link: a member now (`"member": true`). Never a guest any more on this device
            if (GuestMode.afterVerify(saved.member, saved.joined) == GuestMode.AfterVerify.MEMBER) {
                // a member link (canvas onboarding/member-link-name): the name and username come next, once
                store.set(K_NAME_DUE, "1")
                becameMember(g?.channel); return null
            }
        }
        if (g == null) return null
        setGuest(g.copy(saved = true, reading = false))
        _joinAsked.value = false
        rebuild()
        // the co-host link waiting for this: now it makes them a host of the event
        val token = g.coHost ?: return null
        joinWithAccount(token)
        _guest.value?.let { setGuest(it.copy(coHost = null)) }
        return null
    }

    /**
     * This device's guest became a member: no guest record, the register's answer for me is
     * "member" (so [World.amGuest] is false at once), the whole groups relay is asked for again,
     * and the app is told (once) to open the account anew.
     */
    internal fun becameMember(channel: String?) {
        kinds[me] = "member"
        setGuest(null)
        _joinAsked.value = false
        store.remove(K_GROUPS_FULL)
        store.remove(K_GROUPS_MARK)
        store.remove(K_GROUPS_SEEN)
        rebuild()
        if (becameMemberTold) return
        becameMemberTold = true
        try { onBecameMember?.invoke(channel) } catch (e: Exception) { }
    }

    /**
     * Asks the register what I am. True: a member (or a member's agent); false: it answered and I
     * am a guest or hold no pass; null: no answer (offline, an old belfry, refused).
     */
    internal suspend fun registerSaysMember(): Boolean? {
        if (localOnly) return _guest.value == null && !_world.value.amGuest
        val got = try {
            api().whois(signer, listOf(me))
        } catch (e: ApiException) {
            // refused: this key holds no active pass at all (not a member)
            return if (e.status == 401 || e.status == 403) false else null
        } catch (e: Exception) {
            return null
        }
        val k = got[me]
        if (k != null) kinds[me] = k else kinds.remove(me)
        return k == "member" || k == "agent"
    }

    private suspend fun <T> guestCall(block: suspend (BelfryApi) -> T): T = withContext(Dispatchers.Default) {
        try {
            block(api())
        } catch (e: ApiException) {
            throw GuestJoinException(if (e.code in GuestMode.CODES) e.code else if (e.code == "network") "offline" else "failed", e.moved, e.nip05, e.triesLeft)
        } catch (e: IOException) {
            throw GuestJoinException("offline")
        }
    }

    /** The `summary` the last [guestVerify] sent (null: none): the demo and tests. */
    @Volatile var summarySent: String? = null
        private set

    /** The demo (and screenshots): this device as a guest of CHANNEL in guest mode. */
    fun demoGuestMode(channel: String, card: String? = null) {
        if (localOnly) setGuest(GuestInfo(channel, saved = false, reading = true, card = GuestTopic.card(card)))
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
        guard()
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
        guard()
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

    /** The channels I follow by e-mail (channel → `asap`, `weekly` or `monthly`) and the address they go to. */
    suspend fun follows(): Follows {
        if (localOnly) return Follows("you@example.org", HashMap(demoFollows))
        return call { api -> spaced(community.follows) { api.follows(signer) } }
    }

    /** CHOICE: `asap`, `weekly`, `monthly` or `stop`. Returns what is in force now (null = not following). */
    suspend fun setFollow(channel: String, choice: String): String? {
        guard()
        if (localOnly) {
            if (choice == "stop") demoFollows.remove(channel) else demoFollows[channel] = choice
            return demoFollows[channel]
        }
        return call { it.setFollow(signer, channel, choice) }
    }

    /** Changes my address here (`<username>@<domain>`) and puts it in my profile. Codes: `taken`, `invalid`, `not_member`, `own_key`. */
    suspend fun setUsername(username: String): String {
        guard()
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
        guard()
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
        /** Set once the groups relay sent everything it shows a member (see [GroupFilters]). */
        const val K_GROUPS_FULL = "groups-full-1"
        /** The groups relay's catch-up mark: the newest event this device had at an authenticated EOSE. */
        const val K_GROUPS_MARK = "groups-mark"
        const val K_NAME_DUE = "welcome-name-due"
        const val K_GROUPS_SEEN = "groups-seen"
        const val K_ADMINS = "extra-admins"
        const val K_ABSENT = "groups-absent"
        const val K_MAIL_ADDRESSES = "mail-addresses"
        const val ARCHIVED_TEXT = "This group is archived or deleted: nothing can be posted in it."
        const val K_DISCOVERY_ETAG = "belfry-json-etag"
        private const val RECHECK_MS = 30_000L
        private const val K_SEEN_SENT = "seen-sent:"
        private const val SUB = "main"
        private const val REBUILD_MS = 100L
        private const val WATCHDOG_MS = 15_000L
        private const val CATCH_UP_GAP_MS = 2_000L
        const val SEALED_UPKEEP_MS = 6 * 3600 * 1000L
        const val K_SEALED_STATE = "sealed-state"
        /** The session names of my head's devices as last read (device key → name): the waiting view offline. */
        const val K_SEALED_HEAD_NAMES = "sealed-head-names"
        /** Tests shorten it. */
        @Volatile var PUBLISH_TIMEOUT_MS = 15_000L
        /** A signer request at least this slow gets a line of its own in the send trace. */
        const val SLOW_SIGNER_MS = 1_000L
        /** How long the demo's belfry takes to answer a button. */
        @Volatile var DEMO_ANSWER_MS = 900L
        /** How long a reply sent as a channel waits for its delivery report before it is no longer labelled answered. */
        const val ANSWER_WAIT_MS = 24 * 3600 * 1000L
    }
}
