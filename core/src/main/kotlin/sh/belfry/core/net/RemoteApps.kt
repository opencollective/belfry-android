package sh.belfry.core.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.s

/**
 * Sign in to another app (SPEC 19.47; the lead's contract of 2026-11-20): the phone scans or is
 * given an app's `nostrconnect://` code, shows what the belfry says about it, and on Connect the
 * belfry's server becomes that app's NIP-46 remote signer. The phone answers nothing itself and
 * holds no key for the app. The code holds a secret: it is sent as it was read, never logged,
 * never shown, never kept.
 */
object RemoteAppCode {
    private const val PREFIX = "nostrconnect://"
    private val WS = Regex("\\s")

    /** The code to send, or null when TEXT isn't one (only the prefix is checked: the server validates the rest). */
    fun accept(text: String): String? {
        val t = text.trim()
        if (t.length <= PREFIX.length || t.length > 8000 || !t.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return null
        if (WS.containsMatchIn(t)) return null
        return t
    }
}

/** What the belfry reads from an app's code. Name, url, host and image are the app's own claim, not checked. */
data class RemoteAppPreview(
    val name: String?,
    val url: String?,
    val host: String?,
    val image: String?,
    val asksSign: Boolean,
    val asksDecrypt: Boolean,
    /** Already connected to this account. */
    val known: Boolean,
) {
    companion object {
        fun parse(o: JsonObject): RemoteAppPreview {
            val asks = o["asks"].obj()
            return RemoteAppPreview(
                name = o.s("name")?.trim()?.ifEmpty { null }?.take(80), url = o.s("url")?.ifEmpty { null }, host = o.s("host")?.trim()?.ifEmpty { null }?.take(253),
                image = o.s("image")?.ifEmpty { null }, asksSign = asks?.get("sign").bool() ?: true, asksDecrypt = asks?.get("decrypt").bool() ?: false,
                known = o["known"].bool() ?: false,
            )
        }
    }
}

/** An app this account is signed in to through the belfry. Times in Unix seconds (null when not said). */
data class RemoteApp(
    val id: String,
    val name: String?,
    val url: String?,
    val host: String?,
    val image: String?,
    val decrypt: Boolean,
    val created: Long?,
    val used: Long?,
    val expires: Long?,
) {
    /** What it is called here: its name, else its host, else "An app". Never a key. */
    val label: String get() = name ?: host ?: "An app"

    companion object {
        fun parse(o: JsonObject): RemoteApp? {
            val id = (o.s("id") ?: (o["id"] as? JsonPrimitive)?.longOrNull?.toString())?.takeIf { it.isNotEmpty() && it.length <= 100 } ?: return null
            return RemoteApp(
                id = id, name = o.s("name")?.trim()?.ifEmpty { null }?.take(80), url = o.s("url")?.ifEmpty { null },
                host = o.s("host")?.trim()?.ifEmpty { null }?.take(253), image = o.s("image")?.ifEmpty { null },
                decrypt = o["decrypt"].bool() ?: false, created = time(o["created"]), used = time(o["used"]), expires = time(o["expires"]),
            )
        }

        /** Unix seconds from a number (seconds, or milliseconds when too large) or an ISO 8601 date. */
        internal fun time(v: JsonElement?): Long? {
            val p = v as? JsonPrimitive ?: return null
            p.longOrNull?.let { return if (it > 100_000_000_000L) it / 1000 else it }
            val s = p.content.trim().ifEmpty { return null }
            return try { java.time.OffsetDateTime.parse(s).toEpochSecond() } catch (e: Exception) {
                try { java.time.Instant.parse(s).epochSecond } catch (e2: Exception) {
                    try { java.time.LocalDateTime.parse(s).toEpochSecond(java.time.ZoneOffset.UTC) } catch (e3: Exception) { null }
                }
            }
        }
    }
}

/** `<api>/remote-apps…`, NIP-98 signed by the account's key. Errors are fixed texts chosen by the server's `error` code. */
class RemoteAppsApi(private val api: BelfryApi, private val signer: Signer) {
    private val base: String get() = api.community.apiBase + "/remote-apps"

    private fun fail(status: Int, body: JsonObject?, fallback: String): Nothing {
        val code = body?.s("error")?.ifEmpty { null } ?: if (status == 0) "network" else "http"
        val text = if (code == "invalid_uri") body?.s("reason")?.let { INVALID[it] } ?: ERRORS.getValue("invalid_uri") else ERRORS[code] ?: fallback
        throw ApiException(code, status, text)
    }

    suspend fun preview(code: String): RemoteAppPreview {
        val (status, b) = api.signedFetch(signer, "$base/preview", "POST", buildJsonObject { put("uri", JsonPrimitive(code)) })
        if (status !in 200..299 || b == null) fail(status, b, "The code could not be read. Try again.")
        return RemoteAppPreview.parse(b)
    }

    suspend fun connect(code: String, decrypt: Boolean): RemoteApp? {
        val (status, b) = api.signedFetch(signer, base, "POST", buildJsonObject { put("uri", JsonPrimitive(code)); put("decrypt", JsonPrimitive(decrypt)) })
        if (status !in 200..299 || b == null) fail(status, b, "That did not work. Show a new code and try again.")
        return b["app"].obj()?.let { RemoteApp.parse(it) }
    }

    suspend fun list(): List<RemoteApp> {
        val (status, b) = api.signedFetch(signer, base, "GET", null)
        if (status !in 200..299 || b == null) fail(status, b, "Your connected apps could not be loaded. Try again.")
        return b["apps"].arr()?.mapNotNull { it.obj()?.let(RemoteApp::parse) } ?: emptyList()
    }

    suspend fun disconnect(id: String) {
        val (status, b) = api.signedFetch(signer, "$base/${java.net.URLEncoder.encode(id, "UTF-8")}/disconnect", "POST", JsonObject(emptyMap()))
        if (status !in 200..299) fail(status, b, "The app could not be disconnected. Try again.")
    }

    companion object {
        val ERRORS = mapOf(
            "invalid_uri" to "That isn't a code this app can use. Show a new code in the app and scan it again.",
            "guest" to "Join as a member first: guests can't sign in to other apps.",
            "agent" to "An agent can't sign in to other apps.",
            "own_key" to "That code belongs to this account itself.",
            "key_taken" to "That app is already connected to another account. Disconnect it there first.",
            "limit" to "You have 10 connected apps. Disconnect one first.",
            "unsupported" to "That app asks for something the belfry can't do yet.",
            "too_many" to "Too many tries. Wait a minute and try again.",
            "unreachable" to "Couldn't reach the app. Show a new code and try again.",
            "signer" to "The belfry's signer isn't answering. Try again in a moment.",
            "network" to "No connection to the belfry. Check your connection and try again.",
        )
        val INVALID = mapOf(
            "bad_scheme" to "That isn't an app sign-in code.",
            "bad_key" to "That code is damaged. Show a new code in the app.",
            "no_relay" to "That code doesn't say where to answer the app. Show a new code in the app.",
            "bad_relay" to "That code doesn't say where to answer the app. Show a new code in the app.",
            "no_secret" to "That code is missing its secret. Show a new code in the app.",
        )
    }
}
