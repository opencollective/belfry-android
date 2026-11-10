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
        if (guest != null) put("guest", buildJsonObject { put("channel", JsonPrimitive(guest.channel)); put("saved", JsonPrimitive(guest.saved)) })
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
                guest = g?.let { GuestInfo(it.s("channel") ?: "general", it["saved"].bool() ?: false) },
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
            Discovery.fetch(d, http)
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

    /** Connects to the signer with this device's key, saves the session and builds it over a clean store. */
    private suspend fun open(community: Community, bunkerUrl: String, clientSecret: String, pubkey: String?, guest: GuestInfo?): Session {
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
        val s = open(invitation.community, joined.bunkerUrl, secret, joined.pubkey, GuestInfo(joined.channel, false))
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
        return if (demo) null else SignIn.logoutUrl(community)
    }

    companion object {
        const val V_SESSION = "session"
        const val V_FLOW = "signin-flow"
        const val V_INVITE = "pending-invite"
        const val V_JOIN_KEY = "join-key"
    }
}
