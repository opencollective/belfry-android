package sh.belfry.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Nip98
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str
import java.io.IOException

/**
 * The belfry said no (or could not be reached). [code] is the server's `error` key, or one of
 * ours: `network` (no connection), `http` (an answer without a known key). [message] is a fixed
 * text of this app chosen by that key: safe to show. The server's own free text is never kept.
 */
class ApiException(val code: String, val status: Int, message: String) : Exception(message)

data class GuestLink(val url: String, val agentUrl: String?, val days: Int, val people: Int, val linkDays: Int, val expiresAt: String?, val ownAgent: Boolean = false)

/** What `POST /channel-guests` did: a guest of another channel became this one's guest ([added]),
 *  was in it already, or is a member (who needs no guest pass: invite them with a message). */
data class ChannelGuest(val added: Boolean, val already: Boolean, val member: Boolean)

/** A member's own agent, as `POST /agents` lists it. */
data class MyAgent(val pubkey: String, val name: String, val since: String)

/** My agents and how many I may have. */
data class MyAgents(val agents: List<MyAgent>, val limit: Int) {
    val room: Int get() = limit - agents.size
}

/** What the join screen shows before anyone signs anything. All of it comes from the belfry's answer, not from the link. */
data class InviteInfo(
    val community: String,
    val channel: String,
    val channelName: String,
    val about: String,
    val inviter: String,
    /** open | used | expired | revoked */
    val state: String,
    val days: Int,
) {
    val isOpen: Boolean get() = state == "open"

    /** Why a closed invite cannot be used (fixed text), or null when it is open. */
    val closedMessage: String? get() = when (state) {
        "open" -> null
        "expired" -> "This invite has expired. Ask for a new one."
        "revoked" -> "This invite was withdrawn. Ask for a new one."
        else -> "This invite was used already. Ask for a new one."
    }
}

/** A new guest account: its NIP-46 session (bound to the key that signed the request). */
data class JoinedNew(
    val bunkerUrl: String,
    val channel: String,
    val pubkey: String?,
    /** A co-host link (an event's): a host needs a proven e-mail; after saving it, use the link again with the account. */
    val emailNeeded: Boolean = false,
)

/** An existing account used a guest link. */
data class JoinedAccount(val channel: String, val already: Boolean, val guest: Boolean)

data class Place(
    val id: String? = null,
    val name: String? = null,
    val region: String? = null,
    val country: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val near: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        if (id != null) put("id", JsonPrimitive(id))
        if (name != null) put("name", JsonPrimitive(name))
        if (region != null) put("region", JsonPrimitive(region))
        put("country", JsonPrimitive(country))
        if (lat != null) put("lat", JsonPrimitive(lat))
        if (lon != null) put("lon", JsonPrimitive(lon))
        if (near != null) put("near", JsonPrimitive(near))
    }

    companion object {
        fun fromJson(o: JsonObject): Place = Place(
            id = o.s("id"), name = o.s("name"), region = o.s("region"), country = o.s("country") ?: "",
            lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull, lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull, near = o.s("near"),
        )
    }
}

data class Places(val places: List<Place>, val attribution: String?)

/** E-mail follows of channels' announcements: channel id → `asap` | `monthly`. */
data class Follows(val email: String, val byChannel: Map<String, String>)

/** A session open on my key: an app or device that can sign as me. */
data class SessionInfo(
    val applicationName: String,
    val device: String?,
    val redirectOrigin: String,
    val bunkerPubkey: String,
    val createdAt: String,
    val lastActivity: String?,
)

/**
 * The belfry's HTTP API (`<api base>`, default `https://belfry.<domain>/api/v1`). Calls made as
 * the member carry a NIP-98 `Authorization` signed through the member's [Signer] (the bunker).
 * Every failure is an [ApiException].
 */
class BelfryApi(val community: Community, private val http: OkHttpClient) {
    private val base: String get() = community.apiBase

    // ---- plumbing -------------------------------------------------------------------------------

    private class Answer(val status: Int, val body: JsonObject?) {
        val ok: Boolean get() = status in 200..299
        val error: String get() = body?.s("error") ?: ""
    }

    private fun build(url: String, method: String, body: String?, authorization: String?): Request {
        val b = try { Request.Builder().url(url) } catch (e: IllegalArgumentException) { throw ApiException("http", 0, GENERIC) }
        if (authorization != null) b.header("Authorization", authorization)
        b.header("Accept", "application/json")
        when {
            body != null -> b.method(method, body.toRequestBody(JSON))
            method == "GET" -> b.get()
            else -> b.method(method, "".toRequestBody(null))
        }
        return b.build()
    }

    private suspend fun send(req: Request): Answer {
        val (status, text) = try { http.fetch(req) } catch (e: IOException) { throw ApiException("network", 0, NETWORK) }
        return Answer(status, parseJsonOrNull(text) as? JsonObject)
    }

    private suspend fun plain(url: String): Answer = send(build(url, "GET", null, null))

    /** A request signed by the member (NIP-98). BODY null = no body (GET), else JSON. */
    private suspend fun signed(signer: Signer, url: String, method: String = "GET", body: JsonObject? = null): Answer {
        val text = body?.toString()
        val auth = try {
            Nip98.header(signer, url, method, text)
        } catch (e: Exception) {
            throw ApiException("signer", 0, "Your signer did not answer. Check your connection and try again.")
        }
        return send(build(url, method, text, auth))
    }

    /** A signed request as a raw status + JSON body, for calls this class has no method for yet. */
    suspend fun signedFetch(signer: Signer, url: String, method: String = "GET", body: JsonObject? = null): Pair<Int, JsonObject?> {
        val a = signed(signer, url, method, body)
        return a.status to a.body
    }

    private fun fail(a: Answer, messages: Map<String, String>, fallback: String): Nothing {
        val code = a.error.ifEmpty { "http" }
        throw ApiException(code, a.status, messages[code] ?: COMMON[code] ?: fallback)
    }

    // ---- branding (branding.py) -------------------------------------------------------------------

    /** An admin sets the belfry's logo, cover and two colours ("" clears one). Returns them as saved. */
    suspend fun saveBranding(signer: Signer, b: Branding): Branding {
        val a = signed(signer, "$base/branding", "POST", b.toJson())
        if (!a.ok || a.body == null) fail(a, BRANDING_ERRORS, "The branding could not be saved just now.")
        return Branding.clean(a.body)
    }

    // ---- guest links (guests.py) ------------------------------------------------------------------

    /**
     * A guest link for a channel. PEOPLE: 1 or 50. LINK_DAYS: how long the link works, 1, 7 or 30.
     * Whoever uses it is in that channel only, for [GuestLink.days] days.
     */
    suspend fun createGuestLink(signer: Signer, channel: String, people: Int = 1, linkDays: Int = 1, agent: Boolean = false): GuestLink {
        val a = signed(signer, community.channelInvites, "POST", buildJsonObject {
            put("channel", JsonPrimitive(channel))
            put("people", JsonPrimitive(if (people == 50 && !agent) 50 else 1))
            put("link_days", JsonPrimitive(if (linkDays == 7 || linkDays == 30) linkDays else 1))
            // a link for one agent, which joins as my own (agents.py); its instructions are agent_url
            if (agent) put("agent", JsonPrimitive(true))
        })
        val b = a.body
        val url = b?.s("url")
        if (!a.ok || b == null || url == null || !url.startsWith("https://")) fail(a, INVITE_ERRORS, "The guest link could not be made.")
        return GuestLink(
            url = url,
            agentUrl = b.s("agent_url")?.takeIf { it.startsWith("https://") },
            days = b["days"].int() ?: 30,
            people = b["people"].int() ?: people,
            linkDays = b["link_days"].int() ?: linkDays,
            expiresAt = b.s("expires_at"),
            ownAgent = b["own_agent"].bool() ?: agent,
        )
    }

    /**
     * Someone who is here already as another channel's guest becomes a guest of CHANNEL too
     * (guests.py `/channel-guests`). Codes: invalid, unsupported, guest, agent, no_channel,
     * not_allowed, unknown.
     */
    suspend fun addChannelGuest(signer: Signer, channel: String, pubkey: String): ChannelGuest {
        val a = signed(signer, "$base/channel-guests", "POST", buildJsonObject {
            put("channel", JsonPrimitive(channel))
            put("pubkey", JsonPrimitive(pubkey))
        })
        val b = a.body
        if (!a.ok || b == null) fail(a, CHANNEL_GUEST_ERRORS, "Could not add them. Try again.")
        return ChannelGuest(b["added"].bool() == true, b["already"].bool() == true, b["member"].bool() == true)
    }

    // ---- push notifications (push.py, docs/push.md) ----------------------------------------------------

    /**
     * Registers this phone for push: its FCM [token], its device key (base64 of 32 bytes) and a
     * name. The same token again replaces the old row. Returns the device id. Codes: invalid,
     * unsupported (no push on this belfry: 404 → `http`).
     */
    suspend fun addPushDevice(signer: Signer, token: String, keyB64: String, name: String, env: String = "production"): String {
        val a = signed(signer, "$base/push/devices", "POST", buildJsonObject {
            put("add", buildJsonObject {
                put("platform", JsonPrimitive("fcm")); put("token", JsonPrimitive(token)); put("env", JsonPrimitive(env))
                put("key", JsonPrimitive(keyB64)); put("app", JsonPrimitive("belfry-android")); put("name", JsonPrimitive(name.take(60)))
            })
        })
        val id = a.body?.s("id")
        if (!a.ok || id == null) fail(a, PUSH_ERRORS, "This phone could not be registered for notifications.")
        return id
    }

    /** Stops pushes to this token (sign-out, before the session is revoked). */
    suspend fun removePushDevice(signer: Signer, token: String) {
        val a = signed(signer, "$base/push/devices", "POST", buildJsonObject { put("remove", JsonPrimitive(token)) })
        if (!a.ok) fail(a, PUSH_ERRORS, "Could not stop notifications to this phone.")
    }

    /** My notification settings; with [set], merged first. */
    suspend fun notifications(signer: Signer, set: JsonObject? = null): sh.belfry.core.push.NotificationSettings {
        val a = signed(signer, "$base/notifications", "POST", if (set == null) JsonObject(emptyMap()) else buildJsonObject { put("set", set) })
        if (!a.ok || a.body == null) fail(a, PUSH_ERRORS, "Could not read your notification settings.")
        return sh.belfry.core.push.NotificationSettings.parse(a.body)
    }

    // ---- who a key is (whois.py) ---------------------------------------------------------------------

    /**
     * What the register says each key is: "member", "agent" (a member's own) or "guest"; keys with
     * no active pass are left out. At most 500 keys per call (larger lists are split here). An old
     * belfry answers 404 (code `http`, status 404). Codes: not_here (I hold no pass), invalid.
     */
    suspend fun whois(signer: Signer, pubkeys: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (chunk in pubkeys.filter(Hex::isKey).distinct().chunked(WHOIS_MAX)) {
            val a = signed(signer, "$base/whois", "POST", buildJsonObject {
                put("pubkeys", kotlinx.serialization.json.JsonArray(chunk.map { JsonPrimitive(it) }))
            })
            val people = a.body?.get("people").obj()
            if (!a.ok || people == null) fail(a, WHOIS_ERRORS, "Could not ask the belfry who people are.")
            for ((k, v) in people) {
                val kind = v.str() ?: continue
                if (Hex.isKey(k) && kind in KINDS) out[k] = kind
            }
        }
        return out
    }

    // ---- a member's own agents (agents.py) ----------------------------------------------------------

    /**
     * BODY `{}` = my agents; `{pubkey, mine: true}` = a guest agent becomes mine; `{pubkey, mine:
     * false}` = my agent is removed. Codes: not_member, unknown, taken, not_guest_agent,
     * not_inviter, limit (refused on older belfries), not_agent, not_yours, invalid, unsupported.
     */
    private suspend fun agentsCall(signer: Signer, body: JsonObject): MyAgents {
        val a = signed(signer, "$base/agents", "POST", body)
        val b = a.body
        if (!a.ok || b == null) fail(a, AGENT_ERRORS, "Could not do that. Try again.")
        val list = b["agents"].arr()?.mapNotNull { it.obj() }?.mapNotNull { o ->
            o.s("pubkey")?.takeIf(Hex::isKey)?.let { MyAgent(it, o.s("name") ?: "", o.s("since") ?: "") }
        } ?: emptyList()
        return MyAgents(list, b["limit"].int() ?: 0)
    }

    suspend fun myAgents(signer: Signer): MyAgents = agentsCall(signer, JsonObject(emptyMap()))

    suspend fun setMyAgent(signer: Signer, pubkey: String, mine: Boolean): MyAgents = agentsCall(signer, buildJsonObject {
        put("pubkey", JsonPrimitive(pubkey))
        put("mine", JsonPrimitive(mine))
    })

    /** What a guest link leads to (no sign-in needed). */
    suspend fun inviteInfo(token: String): InviteInfo {
        if (!InviteLink.isToken(token)) throw ApiException("not_found", 404, INVITE_UNKNOWN)
        val a = plain("${community.channelInvites}/$token")
        if (a.status == 404) throw ApiException("not_found", 404, INVITE_UNKNOWN)
        val b = a.body
        val channel = b?.s("channel")
        if (!a.ok || b == null || channel == null) throw ApiException(a.error.ifEmpty { "http" }, a.status, "The invite could not be read just now. Try again.")
        return InviteInfo(
            community = (b.s("community") ?: "").take(100),
            channel = channel,
            channelName = (b.s("channel_name") ?: channel).take(80),
            about = (b.s("about") ?: "").take(300),
            inviter = (b.s("inviter") ?: "").take(100),
            state = b.s("state") ?: "used",
            days = b["days"].int() ?: 30,
        )
    }

    /**
     * A new guest: this device's fresh session key signs the request and sends a name; the belfry
     * makes the account and answers with its session, bound to that key. Keep ONE session key per
     * invite until this succeeds (the server is idempotent per key): a retry must sign with the
     * same key. [ApiException.code] `session_taken` (409) means that key belongs to another
     * account: make a new key and try again.
     */
    suspend fun joinAsNew(token: String, name: String, sessionSecretHex: String): JoinedNew {
        val url = "${community.channelInvites}/$token/join"
        val text = buildJsonObject { put("name", JsonPrimitive(name.trim().take(100))) }.toString()
        val auth = try {
            Nip98.headerNow(LocalSigner(sessionSecretHex), url, "POST", text)
        } catch (e: Exception) {
            throw ApiException("signer", 0, GENERIC)
        }
        val a = send(build(url, "POST", text, auth))
        val b = a.body
        val bunker = b?.s("bunker_url")
        if (!a.ok || b == null || bunker == null || BunkerUrl.parse(bunker) == null) {
            if (a.status == 409 && a.error !in JOIN_ERRORS) throw ApiException("session_taken", 409, "Joining did not work just now. Try again.")
            fail(a, JOIN_ERRORS, "Joining did not work just now. Try again.")
        }
        return JoinedNew(bunker, b.s("channel") ?: "general", b.s("pubkey")?.takeIf(Hex::isKey), b["email_needed"].bool() ?: false)
    }

    /** Someone signed in uses a guest link: their account gets the channel (no second account). */
    suspend fun joinWithAccount(signer: Signer, token: String): JoinedAccount {
        val a = signed(signer, "${community.channelInvites}/$token/join", "POST", buildJsonObject { })
        val b = a.body
        val channel = b?.s("channel")
        if (!a.ok || b == null || channel == null) {
            fail(a, JOIN_ERRORS + ("name_needed" to "This account can’t use invites."), "The invite could not be used just now.")
        }
        return JoinedAccount(channel, b["already"].bool() ?: false, b["guest"].bool() ?: false)
    }

    /** A guest saves their account: a 6-digit code goes to this address. */
    suspend fun guestSave(signer: Signer, email: String) {
        val a = signed(signer, "$base/guest/save", "POST", buildJsonObject { put("email", JsonPrimitive(email.trim())) })
        if (!a.ok) fail(a, GUEST_ERRORS, "That did not work. Try again.")
    }

    suspend fun guestVerify(signer: Signer, email: String, code: String) {
        val a = signed(signer, "$base/guest/save/verify", "POST", buildJsonObject {
            put("email", JsonPrimitive(email.trim()))
            put("code", JsonPrimitive(code.trim()))
        })
        if (!a.ok) fail(a, GUEST_ERRORS, "That did not work. Try again.")
    }

    // ---- places (places.py) -----------------------------------------------------------------------

    /** Cities matching the start of a name (no sign-in). Fewer than 2 characters: nothing. */
    suspend fun searchPlaces(query: String, limit: Int = 8): Places {
        val q = query.trim()
        if (q.length < 2) return Places(emptyList(), null)
        val a = plain("$base/places?q=${encodeURIComponent(q)}&limit=$limit")
        val b = a.body
        if (!a.ok || b == null) throw ApiException(a.error.ifEmpty { "http" }, a.status, "The list of places is not available right now.")
        return Places(b["places"].arr()?.mapNotNull { el -> el.obj()?.let { Place.fromJson(it) } } ?: emptyList(), b.s("attribution"))
    }

    /** A place the list doesn't have: its name, its country code, and the id of the nearest listed city. */
    suspend fun addPlace(signer: Signer, name: String, country: String, nearId: String): Place {
        val a = signed(signer, "$base/places", "POST", buildJsonObject {
            put("name", JsonPrimitive(name))
            put("country", JsonPrimitive(country))
            put("near", JsonPrimitive(nearId))
        })
        val place = a.body?.get("place").obj()
        if (!a.ok || place == null) fail(a, PLACE_ERRORS, "Could not add the place. Try again.")
        return Place.fromJson(place)
    }

    // ---- admins of the belfry (admins.py) -----------------------------------------------------------

    /** Gives or takes the admin role. Codes: not_admin, not_member, guest_not_saved, agent, last_admin. */
    suspend fun setAdmin(signer: Signer, pubkey: String, admin: Boolean) {
        val a = signed(signer, "$base/admins", "POST", buildJsonObject {
            put("pubkey", JsonPrimitive(pubkey))
            put("admin", JsonPrimitive(admin))
        })
        if (!a.ok) fail(a, ADMIN_ERRORS, "Could not change it. Try again.")
    }

    // ---- following announcements by e-mail (followers.py) --------------------------------------------

    suspend fun follows(signer: Signer): Follows {
        val a = signed(signer, community.follows)
        val b = a.body
        if (!a.ok || b == null) fail(a, emptyMap(), "Your follows could not be read just now.")
        val map = LinkedHashMap<String, String>()
        for (f in b["follows"].arr() ?: emptyList()) {
            val o = f.obj() ?: continue
            val frequency = o.s("frequency") ?: continue
            val channel = o.s("channel") ?: continue
            if (o.s("state") == "active" && (frequency == "asap" || frequency == "monthly")) map[channel] = frequency
        }
        return Follows(b.s("email") ?: "", map)
    }

    /** CHOICE: `asap`, `monthly` or `stop`. Returns the frequency now in force, null when not following. */
    suspend fun setFollow(signer: Signer, channel: String, choice: String): String? {
        val a = signed(signer, community.follows, "POST", buildJsonObject {
            put("channel", JsonPrimitive(channel))
            put("frequency", JsonPrimitive(choice))
        })
        if (!a.ok) fail(a, emptyMap(), "Not saved. Try again.")
        val b = a.body ?: return null
        val frequency = b.s("frequency") ?: return null
        return if (b.s("state") == "active" && (frequency == "asap" || frequency == "monthly")) frequency else null
    }

    // ---- my address and my sessions -----------------------------------------------------------------

    /** Change my address here (`<username>@<domain>`). Returns the full address. Codes: taken, invalid, not_member, own_key. */
    suspend fun setUsername(signer: Signer, username: String): String {
        val a = signed(signer, "$base/username", "POST", buildJsonObject { put("username", JsonPrimitive(username)) })
        val nip05 = a.body?.s("nip05")
        if (!a.ok || nip05 == null) fail(a, USERNAME_ERRORS, "Could not change the username. Try again.")
        return nip05
    }

    suspend fun sessions(signer: Signer): List<SessionInfo> {
        val a = signed(signer, "$base/me/sessions")
        val b = a.body
        if (!a.ok || b == null) fail(a, emptyMap(), "Your sessions could not be read just now.")
        return b["sessions"].arr()?.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            SessionInfo(
                applicationName = (o.s("application_name") ?: "").take(120),
                device = o.s("device")?.take(120),
                redirectOrigin = o.s("redirect_origin") ?: "",
                bunkerPubkey = o.s("bunker_pubkey") ?: return@mapNotNull null,
                createdAt = o.s("created_at") ?: "",
                lastActivity = o.s("last_activity"),
            )
        } ?: emptyList()
    }

    suspend fun revokeSession(signer: Signer, bunkerPubkey: String) {
        val a = signed(signer, "$base/me/sessions/revoke", "POST", buildJsonObject { put("bunker_pubkey", JsonPrimitive(bunkerPubkey)) })
        if (!a.ok) fail(a, emptyMap(), "Not ended. Try again.")
    }

    /** Admins: make the site a message links to (an nsite ref) the community's website. */
    suspend fun publishWebsite(signer: Signer, ref: String, messageId: String) {
        val a = signed(signer, "$base/website/publish", "POST", buildJsonObject {
            put("ref", JsonPrimitive(ref))
            put("message", JsonPrimitive(messageId))
        })
        if (!a.ok) fail(a, emptyMap(), "The website could not be published just now.")
    }

    // ---- files (Blossom BUD-02, SPEC 5) ---------------------------------------------------------------

    /** Uploads the bytes and returns their URL. Throws IOException (the outbox retries those). */
    suspend fun upload(signer: Signer, bytes: ByteArray, name: String, mime: String): String {
        val auth = Nip98.authorization(signer.sign(Nip98.blossomUpload(name, sha256Hex(bytes))))
        val req = Request.Builder().url(community.blossom + "/upload")
            .header("Authorization", auth)
            .put(bytes.toRequestBody(mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: ""
                if (!res.isSuccessful) throw IOException("upload failed (${res.code})")
                val url = (parseJsonOrNull(text) as? JsonObject)?.s("url")
                if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) throw IOException("upload failed (no url)")
                url
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val GENERIC = "That did not work. Try again."
        private const val NETWORK = "No connection to the belfry."
        private const val INVITE_UNKNOWN = "This invite link is not known here. Ask for a new one."

        private val COMMON = mapOf(
            "unauthorized" to "Your session was not accepted. Sign in again.",
            "no_pass" to "Only members can do this.",
            "invalid_json" to GENERIC,
        )
        private val BRANDING_ERRORS = mapOf(
            "not_admin" to "Only the belfry’s admins change its branding.",
            "invalid" to "The belfry did not accept these values: a picture’s address, and colours like #2563eb.",
            "no_key" to "This belfry has no key of its own yet: its branding can’t be saved.",
            "unsupported" to "This belfry has no branding settings yet.",
        )
        private val INVITE_ERRORS = mapOf(
            "invalid" to "This channel can’t have guest links.",
            "unsupported" to "This belfry does not have guest links.",
            "guest" to "Guests can’t invite guests.",
            "no_channel" to "This channel is not known to the belfry yet.",
            "not_allowed" to "You can’t invite guests here.",
            "refused" to "The belfry refused to make a guest link.",
            "agent" to "Agents can’t invite.",
        )
        private val CHANNEL_GUEST_ERRORS = mapOf(
            "invalid" to "Could not add them. Try again.",
            "unsupported" to "This belfry does not have guests.",
            "guest" to "Guests can’t invite guests.",
            "agent" to "Agents can’t invite.",
            "no_channel" to "This channel is not known to the belfry yet.",
            "not_allowed" to "You can’t invite guests here.",
            "unknown" to "This person is not in the belfry any more.",
        )
        private val PUSH_ERRORS = mapOf(
            "invalid" to "The belfry did not accept this phone's details.",
            "not_member" to "Only people of this belfry get notifications.",
            "no_pass" to "Only people of this belfry get notifications.",
            "http" to "This belfry does not send notifications yet.",
        )
        const val WHOIS_MAX = 500
        private val KINDS = setOf("member", "agent", "guest")
        private val WHOIS_ERRORS = mapOf(
            "not_here" to "Only people of this belfry can see who is who.",
            "invalid" to GENERIC,
        )
        private val AGENT_ERRORS = mapOf(
            // agents.py / register.py make_agent and _agent_room (belfry-sh a6c9a69)
            "not_member" to "Only members can bring an agent of their own.",
            "unknown" to "Nobody here has that key.",
            "taken" to "This agent is a member’s agent already.",
            "not_guest_agent" to "Only an agent that joined as a guest can become yours.",
            "not_inviter" to "Only the member who invited this agent can make it theirs.",
            "limit" to "You have as many agents as you can have: remove one first.",
            // older belfries say only this
            "refused" to "This agent can’t become yours. Only the member who invited it can make it theirs, within their limit.",
            "not_agent" to "This is not a member’s agent.",
            "not_yours" to "Only its member or an admin removes an agent.",
            "invalid" to "Could not do that. Try again.",
            "unsupported" to "This belfry does not have agents.",
        )
        private val JOIN_ERRORS = mapOf(
            "name_needed" to "Tell us your name.",
            "closed" to "This invite is closed. Ask for a new one.",
            "unsupported" to "This belfry does not have guest links.",
            "relay" to "The channel could not be reached just now. Try again.",
            "failed" to "Joining did not work just now. Try again.",
        )
        private val GUEST_ERRORS = mapOf(
            "invalid" to "That is not an e-mail address.",
            "too_many" to "Too many codes for now. Try again in half an hour.",
            "wrong_code" to "That code is not right.",
            "expired" to "That code expired. Send a new one.",
            "taken" to "This address belongs to a member already: sign in with it instead.",
            "not_guest" to "This account is not a guest account.",
        )
        private val PLACE_ERRORS = mapOf(
            "bad_country" to "That country code is not known.",
            "invalid" to "Check the name, the country and the nearest city.",
            "not_member" to "Only members add places.",
            "unsupported" to "This belfry can’t add places.",
        )
        private val ADMIN_ERRORS = mapOf(
            "not_member" to "Only members can be admins.",
            "guest_not_saved" to "This guest has no e-mail yet. Once they save their account, you can make them an admin.",
            "agent" to "An agent cannot be an admin.",
            "last_admin" to "A belfry needs at least one admin.",
            "not_admin" to "Only admins of the belfry can do this.",
        )
        private val USERNAME_ERRORS = mapOf(
            "taken" to "That username is taken.",
            "invalid" to "A username is 1 to 30 lowercase letters, digits, dots, dashes or underscores.",
            "not_member" to "Only members have an address here.",
            "own_key" to "You hold your own key: set your address in your own profile.",
        )
    }
}
