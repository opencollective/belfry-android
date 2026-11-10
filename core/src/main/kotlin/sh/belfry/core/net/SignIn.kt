package sh.belfry.core.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import sh.belfry.core.crypto.B64
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Random
import sh.belfry.core.crypto.sha256
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import java.io.IOException
import java.util.concurrent.TimeUnit

object Pkce {
    fun verifier(): String = B64.url(Random.bytes(48))
    fun state(): String = B64.url(Random.bytes(24))
    fun challenge(verifier: String): String = B64.url(sha256(verifier.toByteArray(Charsets.US_ASCII)))
}

/** Fixed reasons a sign-in can stop for. The message is ours: never text from the URL or the server. */
enum class SignInFailure(val message: String) {
    CANCELLED("Sign-in was cancelled."),
    WRONG_STATE("This sign-in did not start here. Please try again."),
    REFUSED("Not signed in. Please try again."),
    NOT_APPROVED("This app is not approved on this belfry yet. Ask an admin to approve it."),
    OWN_KEY("You hold your own key: this belfry cannot sign for you in this app."),
    EXPIRED("This sign-in expired. Please start again."),
    NETWORK("No connection to the belfry. Check your connection and try again."),
    UNKNOWN("Not signed in. Please try again."),
}

class SignInException(val failure: SignInFailure) : Exception(failure.message)

/** What the token endpoint gives: the NIP-46 session and (usually) the member's key. */
data class SignInToken(val bunkerUrl: String, val pubkey: String?, val refreshToken: String? = null)

/**
 * SPEC 2: OAuth 2.0 code + PKCE against the belfry's /connect. [start] before opening the
 * browser (keep [toJsonString] until the app is called back), then [finish] with the values of
 * the callback `sh.belfry.app://connect?code=…&state=…` (or `error=…`).
 */
class SignIn(val community: Community, val state: String, val verifier: String) {
    /** Open this in a Custom Tab (the system browser session, not a WebView). */
    fun authorizeUrl(silent: Boolean = false): String {
        val b = community.authorize.toHttpUrlOrNull()?.newBuilder() ?: throw SignInException(SignInFailure.UNKNOWN)
        b.addQueryParameter("client_id", CLIENT_ID)
        b.addQueryParameter("redirect_uri", REDIRECT_URI)
        b.addQueryParameter("scope", SCOPE)
        b.addQueryParameter("state", state)
        b.addQueryParameter("code_challenge", Pkce.challenge(verifier))
        b.addQueryParameter("code_challenge_method", "S256")
        if (silent) b.addQueryParameter("prompt", "none")
        return b.build().toString()
    }

    /** The code from the callback's parameters, after checking state. Throws [SignInException]. */
    fun code(code: String?, state: String?, error: String?): String {
        if (state != this.state) throw SignInException(SignInFailure.WRONG_STATE)
        if (error != null) throw SignInException(if (error == "access_denied") SignInFailure.CANCELLED else SignInFailure.REFUSED)
        if (code.isNullOrEmpty()) throw SignInException(SignInFailure.REFUSED)
        return code
    }

    suspend fun exchange(code: String, http: OkHttpClient): SignInToken {
        val body = buildJsonObject {
            put("grant_type", JsonPrimitive("authorization_code"))
            put("code", JsonPrimitive(code))
            put("client_id", JsonPrimitive(CLIENT_ID))
            put("redirect_uri", JsonPrimitive(REDIRECT_URI))
            put("code_verifier", JsonPrimitive(verifier))
        }.toString()
        val req = try {
            Request.Builder().url(community.token).post(body.toRequestBody(JSON)).build()
        } catch (e: IllegalArgumentException) {
            throw SignInException(SignInFailure.UNKNOWN)
        }
        val (status, text) = try { http.fetch(req) } catch (e: IOException) { throw SignInException(SignInFailure.NETWORK) }
        val obj = parseJsonOrNull(text) as? JsonObject
        val bunker = obj?.s("bunker_url")
        if (status == 200 && bunker != null && BunkerUrl.parse(bunker) != null) {
            return SignInToken(bunker, obj.s("pubkey")?.takeIf(Hex::isKey), obj.s("refresh_token"))
        }
        throw SignInException(
            when (obj?.s("error")) {
                "app_not_approved" -> SignInFailure.NOT_APPROVED
                "own_key" -> SignInFailure.OWN_KEY
                "invalid_grant" -> SignInFailure.EXPIRED
                else -> SignInFailure.UNKNOWN
            },
        )
    }

    /** [code] then [exchange]. */
    suspend fun finish(code: String?, state: String?, error: String?, http: OkHttpClient): SignInToken =
        exchange(code(code, state, error), http)

    /** Kept while the browser is open (the app may be killed meanwhile). Holds no long-lived secret. */
    fun toJsonString(): String = buildJsonObject {
        put("community", community.toJson())
        put("state", JsonPrimitive(state))
        put("verifier", JsonPrimitive(verifier))
    }.toString()

    companion object {
        const val CLIENT_ID = "belfry-android"
        const val REDIRECT_URI = "sh.belfry.app://connect"
        const val REDIRECT_SCHEME = "sh.belfry.app"
        const val REDIRECT_HOST = "connect"
        const val SCOPE = "policy:belfry-event"
        private val JSON = "application/json".toMediaType()

        fun start(community: Community): SignIn = SignIn(community, Pkce.state(), Pkce.verifier())

        fun fromJsonString(text: String): SignIn? {
            val o = parseJsonOrNull(text) as? JsonObject ?: return null
            val c = o["community"]?.let { Community.fromJsonString(it.toString()) } ?: return null
            return SignIn(c, o.s("state") ?: return null, o.s("verifier") ?: return null)
        }

        /**
         * Sign-out, server side (SPEC 2): best effort, 3 s. TOKEN is the refresh token when the
         * sign-in gave one, else the bunker URL's secret is NOT sent: the call is skipped.
         */
        suspend fun revoke(community: Community, token: String?, http: OkHttpClient) {
            if (token.isNullOrEmpty()) return
            val quick = http.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
            val body = buildJsonObject {
                put("token", JsonPrimitive(token))
                put("client_id", JsonPrimitive(CLIENT_ID))
            }.toString()
            try {
                quick.fetch(Request.Builder().url(community.revoke).post(body.toRequestBody(JSON)).build())
            } catch (e: Exception) {
                // offline: nothing to do
            }
        }

        /** Ends the browser's session with the belfry (open in a Custom Tab), then comes back to the app. */
        fun logoutUrl(community: Community): String =
            community.logout + "?return_to=" + encodeURIComponent(REDIRECT_URI)
    }
}
