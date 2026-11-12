package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.net.ApiException
import sh.belfry.core.net.BelfryApi
import sh.belfry.core.net.BunkerSigner
import sh.belfry.core.net.BunkerUrl
import sh.belfry.core.net.Community
import sh.belfry.core.net.Discovery
import sh.belfry.core.net.InviteInfo
import sh.belfry.core.net.InviteLink
import sh.belfry.core.net.MemberAddress
import sh.belfry.core.net.NativeSignIn
import sh.belfry.core.net.SessionException
import sh.belfry.core.net.SessionFailure
import sh.belfry.core.net.SignIn
import sh.belfry.core.net.SignInException
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.SignerTimeout
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.unixNow
import sh.belfry.core.store.Store
import java.io.IOException
import java.time.ZoneId

/**
 * Secure storage for the few secrets (the app implements it with its Keystore-encrypted
 * `Secrets`): the saved session (bunker URL + this device's session key) and the keys of flows
 * in progress. Values are never logged.
 */
interface Vault {
    fun get(name: String): String?
    fun set(name: String, value: String)
    fun remove(name: String)
}

class MemoryVault : Vault {
    private val map = HashMap<String, String>()
    @Synchronized override fun get(name: String): String? = map[name]
    @Synchronized override fun set(name: String, value: String) { map[name] = value }
    @Synchronized override fun remove(name: String) { map.remove(name) }
}

/** What is kept between launches so the app opens offline. Lives in the [Vault]. */
data class SavedSession(
    val community: Community,
    /** Empty for the demo. */
    val bunkerUrl: String,
    /** This device's session key (the NIP-46 client key); the demo's own key in the demo. */
    val clientSecret: String,
    /** The member's public key. */
    val pubkey: String,
    val guest: GuestInfo? = null,
    val demo: Boolean = false,
) {
    fun toJsonString(): String = buildJsonObject {
        put("community", community.toJson())
        put("bunkerUrl", JsonPrimitive(bunkerUrl))
        put("clientSecret", JsonPrimitive(clientSecret))
        put("pubkey", JsonPrimitive(pubkey))
        if (guest != null) put("guest", buildJsonObject {
            put("channel", JsonPrimitive(guest.channel)); put("saved", JsonPrimitive(guest.saved))
            if (guest.coHost != null) put("cohost", JsonPrimitive(guest.coHost))
        })
        if (demo) put("demo", JsonPrimitive(true))
    }.toString()

    companion object {
        fun fromJsonString(text: String): SavedSession? {
            val o = parseJsonOrNull(text) as? JsonObject ?: return null
            val community = o["community"]?.let { Community.fromJsonString(it.toString()) } ?: return null
            val secret = o.s("clientSecret")?.takeIf(Hex::isKey) ?: return null
            val pubkey = o.s("pubkey")?.takeIf(Hex::isKey) ?: return null
            val g = o["guest"].obj()
            return SavedSession(
                community, o.s("bunkerUrl") ?: "", secret, pubkey,
                guest = g?.let { GuestInfo(it.s("channel") ?: "general", it["saved"].bool() ?: false, it.s("cohost")?.takeIf { t -> InviteLink.isToken(t) }) },
                demo = o["demo"].bool() ?: false,
            )
        }
    }
}

/** A failure before or while signing in. [message] is a fixed text, safe to show. */
class OnboardingException(val code: String, message: String) : Exception(message)

/** What a guest link leads to, for the join screen. */
data class Invitation(val link: InviteLink, val community: Community, val info: InviteInfo)

/**
 * Everything before a [Session] exists: finding the belfry, signing in (SPEC 2), joining through
 * a guest link (SPEC 19), reopening the saved session, the demo. Free of Android: the app gives
 * it a [Vault], its [Store] and an OkHttp client.
 */
class Onboarding(
    private val vault: Vault,
    private val store: Store,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::unixNow,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** belfry.json of a domain (a seam for tests). Throws IOException when there is none. */
    private val discovery: suspend (String) -> Community = { Discovery.fetch(it, http) },
) {
    /** Is there a saved session ([resume] will open it)? */
    val hasSession: Boolean get() = vault.get(V_SESSION) != null

    private fun saved(): SavedSession? = vault.get(V_SESSION)?.let(SavedSession::fromJsonString)
    private fun save(s: SavedSession) = vault.set(V_SESSION, s.toJsonString())

    // ---- finding the belfry ---------------------------------------------------------------------

    /** The community at DOMAIN (what the person typed). Throws [OnboardingException] `not_a_domain` / `not_found`. */
    suspend fun discover(domain: String): Community {
        val d = Community.cleanDomain(domain)
        if (!Community.isDomain(d)) throw OnboardingException("not_a_domain", "Type your community's address, like belfry.sh.")
        return try {
            discovery(d)
        } catch (e: IOException) {
            throw OnboardingException("not_found", "No belfry answered at this address. Check the address and your connection.")
        }
    }

    // ---- sign-in (OAuth code + PKCE → NIP-46) -----------------------------------------------------

    /**
     * Starts a sign-in: returns the address to open in a Custom Tab. The flow is kept in the vault
     * (the app may be killed while the browser is open). The answer comes back to
     * `sh.belfry.app://connect?code=…&state=…` (or `error=…`): pass those three to [finishSignIn].
     */
    fun beginSignIn(community: Community): String {
        val flow = SignIn.start(community)
        vault.set(V_FLOW, flow.toJsonString())
        return flow.authorizeUrl()
    }

    /** Is a sign-in waiting for its answer? */
    val signInPending: Boolean get() = vault.get(V_FLOW) != null

    /** The person closed the browser: forget the flow. */
    fun cancelSignIn() = vault.remove(V_FLOW)

    /**
     * The redirect came back. Exchanges the code, makes this device's session key, connects to the
     * signer and returns a ready [Session] (not started). Throws [OnboardingException] (codes:
     * `no_flow`, `cancelled`, `wrong_state`, `refused`, `not_approved`, `own_key`, `expired`,
     * `network`, `signer`, `unknown`).
     */
    suspend fun finishSignIn(code: String?, state: String?, error: String?): Session = withContext(Dispatchers.Default) {
        val flow = vault.get(V_FLOW)?.let(SignIn::fromJsonString) ?: throw OnboardingException("no_flow", "This sign-in did not start here. Please try again.")
        vault.remove(V_FLOW)
        val token = try {
            flow.finish(code, state, error, http)
        } catch (e: SignInException) {
            throw OnboardingException(e.failure.name.lowercase(), e.failure.message)
        }
        open(flow.community, token.bunkerUrl, Keys.generateSecretHex(), token.pubkey, null)
    }

    // ---- native sign-in (SPEC 19.20: name@domain → a code by e-mail → NIP-46) ----------------------

    /** A native sign-in waiting for its code (kept in the vault: reading the e-mail may kill the app). */
    val pendingCode: PendingCode?
        get() = nativeFlow()?.takeIf { it.sentAt > 0 && clock() - it.sentAt < CODE_SECONDS }?.let { PendingCode(it.address.toString(), it.to) }

    private fun nativeFlow(): NativeFlow? = vault.get(V_NATIVE)?.let(NativeFlow::fromJsonString)

    /**
     * Screen 1: ADDRESS is `name@domain`. Finds the belfry at that domain, makes this device's
     * session key (the same key for a resend or a second try) and asks the belfry to mail a code.
     * Throws [OnboardingException]: `not_an_address`, `no_belfry`, `browser` (an older belfry:
     * offer the browser sign-in), or a [SessionFailure]'s name in lower case.
     */
    suspend fun requestCode(address: String): PendingCode = withContext(Dispatchers.Default) {
        val a = MemberAddress.parse(address) ?: throw OnboardingException("not_an_address", "Type your address like name@belfry.sh.")
        val community = try {
            discovery(a.domain)
        } catch (e: IOException) {
            // the domain is what the person typed, checked as a hostname by MemberAddress.parse
            throw OnboardingException("no_belfry", "There is no belfry at ${a.domain}. Check the address and your connection.")
        }
        // belfry.json says where the apps sign in (`sign_in.sessions.api`); a belfry that does not
        // say so is an older one: the browser. (A 404 from one that says so means the same.)
        if (community.sessionApi == null) throw OnboardingException("browser", SessionFailure.UNSUPPORTED.message)
        val kept = nativeFlow()?.takeIf { it.address == a && it.bunkerUrl == null }
        val secret = kept?.secret ?: Keys.generateSecretHex()
        // kept before asking, so a retry after a lost answer signs with the same key
        vault.set(V_NATIVE, NativeFlow(community, a, secret, kept?.to, kept?.sentAt ?: 0).toJsonString())
        val sent = try {
            NativeSignIn(community, http, clock).request(a, secret)
        } catch (e: SessionException) {
            if (e.failure == SessionFailure.KEY_TAKEN) vault.remove(V_NATIVE)
            throw e.toOnboarding()
        }
        vault.set(V_NATIVE, NativeFlow(community, a, secret, sent.to, clock()).toJsonString())
        PendingCode(a.toString(), sent.to)
    }

    /** "Send a new code": the first request again, signed by the same key. */
    suspend fun resendCode(): PendingCode {
        val flow = nativeFlow() ?: throw OnboardingException("expired", SessionFailure.EXPIRED.message)
        return requestCode(flow.address.toString())
    }

    /** Back to screen 1, or the code expired: forget the request (the address is the screen's to keep). */
    fun cancelCode() = vault.remove(V_NATIVE)

    /**
     * Screen 2: the six digits. With the right code the belfry opens a NIP-46 session bound to
     * this device's session key; the app connects, checks the signer's key is the one the belfry
     * named, and returns a ready [Session] (not started). Throws [OnboardingException]:
     * `wrong_code` (message says how many tries are left), `expired` and `key_taken` (start again
     * from screen 1), `own_key`, `mismatch`, `not_approved`, `too_many`, `network`, `signer`
     * (the session is open, this phone could not reach the signer: the same digits again),
     * `signer_down` (the belfry's signer is not answering; the code is spent: a new code).
     */
    suspend fun verifyCode(code: String): Session = withContext(Dispatchers.Default) {
        var flow = nativeFlow() ?: throw OnboardingException("expired", SessionFailure.EXPIRED.message)
        if (flow.bunkerUrl == null || flow.pubkey == null) {
            val opened = try {
                NativeSignIn(flow.community, http, clock).verify(code, flow.secret)
            } catch (e: SessionException) {
                if (e.failure in ENDS_THE_REQUEST) vault.remove(V_NATIVE)
                throw e.toOnboarding()
            }
            // kept until the signer answered: the code is spent, a retry must not need another
            flow = flow.copy(bunkerUrl = opened.bunkerUrl, pubkey = opened.pubkey)
            vault.set(V_NATIVE, flow.toJsonString())
        }
        val s = try {
            open(flow.community, flow.bunkerUrl!!, flow.secret, null, null, expect = flow.pubkey)
        } catch (e: OnboardingException) {
            if (e.code == "mismatch") vault.remove(V_NATIVE)
            throw e
        }
        vault.remove(V_NATIVE)
        s
    }

    private fun SessionException.toOnboarding() =
        OnboardingException(if (failure == SessionFailure.UNSUPPORTED) "browser" else failure.name.lowercase(), display)

    /**
     * Connects to the signer with this device's key, saves the session and builds it over a clean
     * store. With EXPECT the signer is asked for its key (`get_public_key`), which must be that one.
     */
    private suspend fun open(community: Community, bunkerUrl: String, clientSecret: String, pubkey: String?, guest: GuestInfo?, expect: String? = null): Session {
        val bunker = BunkerUrl.parse(bunkerUrl) ?: throw OnboardingException("signer", "The belfry sent a signer link this app does not understand.")
        val signer = BunkerSigner(bunker, clientSecret, pubkey?.takeIf(Hex::isKey), http, scope)
        val me = try {
            signer.ensurePubkey()
        } catch (e: SignerTimeout) {
            signer.disconnect()
            throw OnboardingException("signer", "The belfry's signer did not answer. Check your connection and try again.")
        } catch (e: Exception) {
            signer.disconnect()
            throw OnboardingException("signer", "The belfry's signer refused this session. Please sign in again.")
        }
        if (expect != null && me != expect) {
            signer.disconnect()
            throw OnboardingException("mismatch", SessionFailure.MISMATCH.message)
        }
        val old = saved()
        if (old == null || old.pubkey != me || old.community.domain != community.domain) withContext(Dispatchers.IO) { store.wipe() }
        save(SavedSession(community, bunkerUrl, clientSecret, me, guest))
        return session(community, signer, guest, localOnly = false)
    }

    private fun session(community: Community, signer: sh.belfry.core.nostr.Signer, guest: GuestInfo?, localOnly: Boolean): Session {
        val s = Session(community, store, signer, http, scope, localOnly, clock, guest, zone)
        s.onRecordChanged = { c, g -> saved()?.let { save(it.copy(community = c, guest = g)) } }
        return s
    }

    // ---- guest links ----------------------------------------------------------------------------

    /**
     * A guest link the app was opened with (or that was scanned), kept while the person decides or
     * signs in first. Set it from `InviteLink.parse(uri)`; null clears it.
     */
    var pendingInvite: InviteLink?
        get() = vault.get(V_INVITE)?.let { v ->
            val parts = v.split(" ")
            if (parts.size == 2 && Community.isDomain(parts[0]) && InviteLink.isToken(parts[1])) InviteLink(parts[0], parts[1]) else null
        }
        set(value) { if (value == null) vault.remove(V_INVITE) else vault.set(V_INVITE, value.domain + " " + value.token) }

    /** What the link leads to (no sign-in needed). Throws [OnboardingException] (`not_found`, `network`, `http`). */
    suspend fun inviteInfo(link: InviteLink): Invitation = withContext(Dispatchers.Default) {
        val community = try { Discovery.fetch(link.domain, http) } catch (e: IOException) { Community.defaults(link.domain) }
        try {
            Invitation(link, community, BelfryApi(community, http).inviteInfo(link.token))
        } catch (e: ApiException) {
            throw OnboardingException(e.code, e.message ?: "The invite could not be read just now. Try again.")
        }
    }

    /**
     * Joins as a new guest with just a name: this device makes its session key, signs the request
     * with it, and the belfry answers with the account's session. ONE key per invite is kept in
     * the vault until the join succeeds, so a second tap or a retry gets the same account.
     * Returns a ready [Session] (not started); `session.guest.value.channel` is where to go.
     */
    suspend fun joinAsNew(invitation: Invitation, name: String): Session = withContext(Dispatchers.Default) {
        val token = invitation.link.token
        val kept = vault.get(V_JOIN_KEY)?.split(" ")?.takeIf { it.size == 2 && it[0] == token && Hex.isKey(it[1]) }?.get(1)
        val secret = kept ?: Keys.generateSecretHex().also { vault.set(V_JOIN_KEY, "$token $it") }
        val joined = try {
            BelfryApi(invitation.community, http).joinAsNew(token, name, secret)
        } catch (e: ApiException) {
            // this device's key is another account's in the signer: a fresh key next time
            if (e.code == "session_taken") vault.remove(V_JOIN_KEY)
            throw OnboardingException(e.code, e.message ?: "Joining did not work just now. Try again.")
        }
        val s = open(invitation.community, joined.bunkerUrl, secret, joined.pubkey, GuestInfo(joined.channel, false, if (joined.emailNeeded) token else null))
        vault.remove(V_JOIN_KEY)
        pendingInvite = null
        s
    }

    // ---- reopening --------------------------------------------------------------------------------

    /** The saved session, opened without the network (null when there is none). Not started. */
    suspend fun resume(): Session? = withContext(Dispatchers.Default) {
        val s = saved() ?: return@withContext null
        if (s.demo) {
            if (store.allEvents().isEmpty()) Demo.seed(store, clock(), LocalSigner(s.clientSecret))
            return@withContext session(s.community, LocalSigner(s.clientSecret), null, localOnly = true)
        }
        val bunker = BunkerUrl.parse(s.bunkerUrl) ?: return@withContext null
        session(s.community, BunkerSigner(bunker, s.clientSecret, s.pubkey, http, scope), s.guest, localOnly = false)
    }

    /**
     * The demo: a made-up community seeded on this device, nothing leaves it. NAME is the
     * person's name in it. Replaces whatever was stored.
     */
    suspend fun demo(name: String = Demo.DEFAULT_NAME): Session = withContext(Dispatchers.Default) {
        store.wipe()
        val me = Demo.me(name)
        Demo.seed(store, clock(), me, name)
        val community = Demo.community(me.pubkey)
        save(SavedSession(community, "", me.secretHex, me.pubkey, demo = true))
        session(community, me, null, localOnly = true)
    }

    /**
     * Signs out: ends the session with the belfry (best effort), wipes the store and forgets the
     * saved session. Returns the address that also ends the browser's session with the belfry
     * (open it in a Custom Tab if the person wants to switch accounts), null for the demo.
     */
    suspend fun signOut(session: Session): String? {
        val community = session.community
        val demo = session.localOnly
        session.signOut()
        vault.remove(V_SESSION)
        vault.remove(V_FLOW)
        vault.remove(V_JOIN_KEY)
        vault.remove(V_NATIVE)
        return if (demo) null else SignIn.logoutUrl(community)
    }

    companion object {
        const val V_SESSION = "session"
        const val V_FLOW = "signin-flow"
        const val V_INVITE = "pending-invite"
        const val V_JOIN_KEY = "join-key"
        const val V_NATIVE = "native-signin"
        /** The belfry keeps a request for 15 minutes (a code for 10): after that, screen 1 again. */
        const val CODE_SECONDS = 900L
        private val ENDS_THE_REQUEST = setOf(
            SessionFailure.EXPIRED, SessionFailure.KEY_TAKEN, SessionFailure.OWN_KEY, SessionFailure.MISMATCH, SessionFailure.NOT_APPROVED,
        )
    }
}

/** What screen 2 of the native sign-in shows: the address typed and, when the belfry gave a well-formed one, the masked e-mail. */
data class PendingCode(val address: String, val to: String?)

/** The native sign-in in progress, in the [Vault]: this device's session key and, once the code was right, the session to connect to. */
internal data class NativeFlow(
    val community: Community,
    val address: MemberAddress,
    val secret: String,
    val to: String? = null,
    val sentAt: Long = 0,
    val bunkerUrl: String? = null,
    val pubkey: String? = null,
) {
    fun toJsonString(): String = buildJsonObject {
        put("community", community.toJson())
        put("address", JsonPrimitive(address.toString()))
        put("secret", JsonPrimitive(secret))
        if (to != null) put("to", JsonPrimitive(to))
        put("sentAt", JsonPrimitive(sentAt))
        if (bunkerUrl != null) put("bunkerUrl", JsonPrimitive(bunkerUrl))
        if (pubkey != null) put("pubkey", JsonPrimitive(pubkey))
    }.toString()

    companion object {
        fun fromJsonString(text: String): NativeFlow? {
            val o = parseJsonOrNull(text) as? JsonObject ?: return null
            return NativeFlow(
                community = o["community"]?.let { Community.fromJsonString(it.toString()) } ?: return null,
                address = o.s("address")?.let(MemberAddress::parse) ?: return null,
                secret = o.s("secret")?.takeIf(Hex::isKey) ?: return null,
                to = o.s("to")?.takeIf(NativeSignIn::isMaskedAddress),
                sentAt = (o["sentAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                bunkerUrl = o.s("bunkerUrl")?.takeIf { BunkerUrl.parse(it) != null },
                pubkey = o.s("pubkey")?.takeIf(Hex::isKey),
            )
        }
    }
}
