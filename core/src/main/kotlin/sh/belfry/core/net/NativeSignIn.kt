package sh.belfry.core.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import sh.belfry.core.crypto.Hex
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Nip98
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.unixNow
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a person types to sign in: `name@domain`, their address in their community. */
data class MemberAddress(val name: String, val domain: String) {
    override fun toString(): String = "$name@$domain"

    companion object {
        private val NAME = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

        /** Null unless RAW is one name, one `@`, and a hostname (trimmed, lower-cased). */
        fun parse(raw: String): MemberAddress? {
            val s = raw.trim().lowercase()
            val at = s.indexOf('@')
            if (at <= 0 || at != s.lastIndexOf('@')) return null
            val name = s.substring(0, at)
            val domain = s.substring(at + 1)
            if (!NAME.matches(name) || !Community.isDomain(domain)) return null
            return MemberAddress(name, domain)
        }
    }
}

/**
 * The reasons the native sign-in (SPEC 19.20) can stop for. The message is ours, chosen by the
 * belfry's `error` key: never the server's own text.
 */
enum class SessionFailure(val message: String) {
    UNKNOWN_MEMBER("No member has this address. Check the name before the @."),
    NO_EMAIL("This account has no e-mail address to send a code to. Ask an admin of your community."),
    INVALID("Type your address like name@belfry.sh."),
    TOO_MANY("Too many codes were asked for. Try again in half an hour."),
    UNAUTHORIZED("The belfry did not accept this request. Check the date and time of this phone, then try again."),
    WRONG_CODE("That code is not right."),
    EXPIRED("That code has expired. Ask for a new one."),
    OWN_KEY("You hold your own key: this app can’t sign you in yet."),
    MISMATCH("This address changed while you were signing in. Start again."),
    KEY_TAKEN("This phone’s sign-in key is linked to another account. Start again: a new one is made."),
    NOT_APPROVED("This app is not approved on this belfry yet. Ask an admin to approve it."),
    /** An older belfry without this API: the browser sign-in works there. */
    UNSUPPORTED("This belfry can’t sign you in here yet. Sign in with the browser instead."),
    NETWORK("No connection to the belfry. Check your connection and try again."),
    OTHER("Not signed in. Please try again."),
}

class SessionException(val failure: SessionFailure, val triesLeft: Int? = null) : Exception(failure.message) {
    /** The text to show: for a wrong code, with how many tries are left. */
    val display: String
        get() = when {
            failure != SessionFailure.WRONG_CODE || triesLeft == null -> failure.message
            triesLeft <= 0 -> "That code is not right. Ask for a new one."
            triesLeft == 1 -> "That code is not right. 1 try left."
            else -> "That code is not right. $triesLeft tries left."
        }
}

/** The belfry mailed a code. [to] is the masked address, only when it looks like one. */
data class CodeSent(val to: String?, val nip05: String?)

/** The right code: the NIP-46 session, bound to the key that signed the requests. */
data class SessionOpened(val bunkerUrl: String, val pubkey: String)

/**
 * Signing in without a browser (collective-belfry sessions.py): two calls signed (NIP-98) by this
 * device's session key, which is the NIP-46 client key afterwards.
 *
 *     POST <api base>/session         {"nip05","app","client"}  → {"state":"code_sent","to","nip05"}
 *     POST <api base>/session/verify  {"code"}                  → {"state":"open","bunker_url","pubkey",…}
 */
class NativeSignIn(private val community: Community, private val http: OkHttpClient, private val clock: () -> Long = ::unixNow) {

    suspend fun request(address: MemberAddress, sessionSecretHex: String): CodeSent {
        val body = buildJsonObject {
            put("nip05", JsonPrimitive(address.toString()))
            put("app", JsonPrimitive(SignIn.REDIRECT_URI))
            put("client", JsonPrimitive(SignIn.CLIENT_ID))
        }
        val (status, obj) = post("${community.apiBase}/session", body, sessionSecretHex)
        if (status == 200 && obj?.s("state") == "code_sent") {
            return CodeSent(obj.s("to")?.takeIf(::isMaskedAddress), obj.s("nip05")?.let(MemberAddress::parse)?.toString())
        }
        throw SessionException(failureOf(status, obj, verifying = false))
    }

    suspend fun verify(code: String, sessionSecretHex: String): SessionOpened {
        if (!isCode(code)) throw SessionException(SessionFailure.WRONG_CODE)
        val (status, obj) = post("${community.apiBase}/session/verify", buildJsonObject { put("code", JsonPrimitive(code)) }, sessionSecretHex)
        val bunker = obj?.s("bunker_url")
        val pubkey = obj?.s("pubkey")?.lowercase()
        if (status == 200 && obj?.s("state") == "open" && bunker != null && BunkerUrl.parse(bunker) != null && pubkey != null && Hex.isKey(pubkey)) {
            return SessionOpened(bunker, pubkey)
        }
        val failure = failureOf(status, obj, verifying = true)
        throw SessionException(failure, if (failure == SessionFailure.WRONG_CODE) obj?.get("tries_left").int()?.coerceIn(0, 9) else null)
    }

    private suspend fun post(url: String, body: JsonObject, secretHex: String): Pair<Int, JsonObject?> {
        val text = body.toString()
        val req = try {
            val signer = LocalSigner(secretHex)
            val auth = Nip98.authorization(signer.signNow(Nip98.template(url, "POST", text, stamp(clock()))))
            Request.Builder().url(url).header("Authorization", auth).header("Accept", "application/json").post(text.toRequestBody(JSON)).build()
        } catch (e: Exception) {
            throw SessionException(SessionFailure.OTHER)
        }
        // the shared client waits for ever (it holds the relays' sockets): a sign-in call must not
        val quick = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        val (status, answer) = try { quick.fetch(req) } catch (e: IOException) { throw SessionException(SessionFailure.NETWORK) }
        return status to (parseJsonOrNull(answer) as? JsonObject)
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val MASKED = Regex("^[^\\s@•]•••@([a-z0-9.-]+)$", RegexOption.IGNORE_CASE)
        private var lastStamp = 0L

        /**
         * `created_at` of a NIP-98 event: the belfry accepts each signed event once, so two calls
         * in the same second (a resend, a quick retry) must not carry the same second.
         */
        @Synchronized
        internal fun stamp(now: Long): Long {
            lastStamp = maxOf(lastStamp + 1, now)
            return lastStamp
        }

        fun isCode(s: String): Boolean = s.length == 6 && s.all { it in '0'..'9' }

        /** The six digits in what was typed or pasted ("123 456", "Your code: 123456"), else what digits there are (at most six). */
        fun digits(raw: String): String = raw.filter { it in '0'..'9' }.take(6)

        /**
         * Is this the belfry's masked address (`x•••@example.org`: one character, three dots, a
         * hostname)? Only then is it shown; anything else reads "your e-mail address".
         */
        fun isMaskedAddress(s: String): Boolean {
            if (s.length > 260) return false
            val m = MASKED.matchEntire(s) ?: return false
            return Community.isDomain(m.groupValues[1])
        }

        internal fun failureOf(status: Int, obj: JsonObject?, verifying: Boolean): SessionFailure = when (obj?.s("error")) {
            "unknown" -> SessionFailure.UNKNOWN_MEMBER
            "no_email" -> SessionFailure.NO_EMAIL
            "invalid", "app_address", "invalid_json" -> if (verifying) SessionFailure.OTHER else SessionFailure.INVALID
            "too_many" -> SessionFailure.TOO_MANY
            "unauthorized" -> SessionFailure.UNAUTHORIZED
            "wrong_code" -> SessionFailure.WRONG_CODE
            "expired" -> SessionFailure.EXPIRED
            "own_key" -> SessionFailure.OWN_KEY
            "mismatch" -> SessionFailure.MISMATCH
            "key_taken" -> SessionFailure.KEY_TAKEN
            "app_not_approved" -> SessionFailure.NOT_APPROVED
            null -> when (status) {
                // no `error` key: not this API's answer (an older belfry, or a proxy's page)
                404, 405, 501 -> SessionFailure.UNSUPPORTED
                429 -> SessionFailure.TOO_MANY
                401 -> SessionFailure.UNAUTHORIZED
                else -> SessionFailure.OTHER
            }
            else -> if (status == 429) SessionFailure.TOO_MANY else SessionFailure.OTHER
        }
    }
}
