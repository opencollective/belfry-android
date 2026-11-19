package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The web's way in (collective-belfry docs/membership.md "Applying from the chat (verified)" and
 * "A name and a username"; canvas onboarding/connect, login, login-unknown, apply-*, member-link-name;
 * `vectors/way-in.json` from belfry-ios). Every call is signed (NIP-98) by this phone's applicant key
 * for that belfry. [next] says what the app shows after an answer; its words are the app's own,
 * never the server's text.
 */
object WayIn {
    enum class Screen { CODE, SIGNED_IN, PENDING, UNKNOWN, DECLINED, START, LOG_IN, SIGN_IN_INSTEAD, WITHOUT_PROOF, STAY, OLDER }

    /** A session on the account, bound to the applicant key (store it like any sign-in). */
    data class Signed(val bunkerUrl: String, val pubkey: String)

    data class Outcome(
        val screen: Screen,
        val message: String? = null,
        val clearsCode: Boolean = false,
        val suggestions: List<String> = emptyList(),
        val proof: String? = null,
        /** The address said ("Signed in as …", Log in filled in): only name@<this domain>. */
        val nip05: String? = null,
        /** /apply/status's own words back: name, username, email, motivation. */
        val pending: Map<String, String>? = null,
        val signed: Signed? = null,
    )

    const val TRY_AGAIN = "That did not work. Try again."
    const val CHARS = "Use lowercase letters, digits and dashes, 3 to 30 characters."
    const val RESERVED = "That name is kept for the belfry."

    private val USERNAME = Regex("^[a-z0-9](?:[a-z0-9._-]{0,28}[a-z0-9])?$")
    private val BUNKER = Regex("^bunker://[0-9a-f]{64}(\\?.*)?$")
    private val KEY = Regex("^[0-9a-f]{64}$")

    fun isUsername(s: String): Boolean = USERNAME.matches(s)

    private fun JsonObject?.str(k: String): String? = (this?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject?.bool(k: String): Boolean? = (this?.get(k) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject?.int(k: String): Int? = (this?.get(k) as? JsonPrimitive)?.intOrNull

    /** name@DOMAIN only, else null. */
    fun address(nip05: String?, domain: String): String? =
        nip05?.trim()?.lowercase()?.takeIf { it.endsWith("@$domain") && isUsername(it.substringBefore('@')) && it.count { c -> c == '@' } == 1 }

    fun suggestions(body: JsonObject?): List<String> =
        (body?.get("suggestions") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.filter(::isUsername)?.take(3) ?: emptyList()

    private fun signed(body: JsonObject?, domain: String): Outcome {
        val bunker = body.str("bunker_url")
        val pubkey = body.str("pubkey")
        if (bunker == null || !BUNKER.matches(bunker) || pubkey == null || !KEY.matches(pubkey)) return Outcome(Screen.STAY, TRY_AGAIN)
        return Outcome(Screen.SIGNED_IN, nip05 = address(body.str("nip05"), domain), signed = Signed(bunker, pubkey))
    }

    fun next(call: String, status: Int, body: JsonObject?, domain: String): Outcome {
        val error = body.str("error")
        if (error == null && (status == 404 || status == 405)) return Outcome(Screen.OLDER)
        if (status == 202 && body.bool("sent") == true) return Outcome(Screen.CODE)
        if (status in 200..299 && error == null) {
            if (call == "welcome") {
                return if (body.str("username") != null || body.str("nip05") != null) Outcome(Screen.SIGNED_IN, nip05 = address(body.str("nip05"), domain)) else Outcome(Screen.STAY, TRY_AGAIN)
            }
            return when (body.str("state")) {
                "signed_in" -> signed(body, domain)
                "approved" -> if (body.bool("signed_in") == true) signed(body, domain) else Outcome(Screen.LOG_IN, nip05 = address(body.str("nip05"), domain))
                "pending" -> {
                    val back = listOf("name", "username", "email", "motivation").mapNotNull { k -> body.str(k)?.let { k to it } }.toMap()
                    Outcome(Screen.PENDING, pending = back.ifEmpty { null })
                }
                "unknown" -> body.str("proof")?.takeIf { it.isNotEmpty() }?.let { Outcome(Screen.UNKNOWN, proof = it) } ?: Outcome(Screen.STAY, TRY_AGAIN)
                "none" -> Outcome(Screen.START)
                "declined" -> Outcome(Screen.DECLINED)
                else -> Outcome(Screen.STAY, TRY_AGAIN)
            }
        }
        return when (error) {
            "unsupported" -> Outcome(Screen.OLDER)
            "invalid" -> Outcome(Screen.STAY, "That doesn’t look like an e-mail address.")
            "too_many" -> Outcome(Screen.STAY, "Too many codes for now. Try again in half an hour.")
            "wrong_code" -> {
                val left = body.int("tries_left")
                Outcome(Screen.STAY, when {
                    left == null -> "That code is not right."
                    left <= 0 -> "That code is not right. Send a new one."
                    left == 1 -> "That code is not right. 1 try left."
                    else -> "That code is not right. $left tries left."
                }, clearsCode = true)
            }
            "expired" -> Outcome(Screen.STAY, "That code has expired. Send a new one.", clearsCode = true)
            "taken" -> Outcome(Screen.SIGN_IN_INSTEAD, nip05 = address(body.str("nip05"), domain))
            "key_taken" -> Outcome(Screen.STAY, "That did not work. Send a new code and try again.", clearsCode = true)
            "signer" -> Outcome(Screen.STAY, "The belfry’s signer is not answering. Send a new code and try again.", clearsCode = true)
            "proof" -> Outcome(Screen.WITHOUT_PROOF)
            "missing" -> Outcome(Screen.STAY, "Please give your name and a few words on why you would like to join.")
            "unticked" -> Outcome(Screen.STAY, "Please tick each agreement of the charter: joining means agreeing to them.")
            "username_invalid" -> Outcome(Screen.STAY, if (body.str("reason") == "reserved") RESERVED else CHARS)
            "username_taken" -> Outcome(Screen.STAY, "This username is taken.", suggestions = suggestions(body))
            "already_set" -> Outcome(Screen.STAY, "Your username is set already. You change it from your profile.")
            "not_member" -> Outcome(Screen.STAY, "Only members pick a username here.")
            else -> Outcome(Screen.STAY, TRY_AGAIN)
        }
    }

    /** The line under "Pick a username:" for an answer of POST /username/check. */
    fun usernameLine(body: JsonObject, domain: String): String {
        val name = body.str("username") ?: ""
        val first = suggestions(body).firstOrNull()
        return when (body.str("state")) {
            "available" -> "$name@$domain is available"
            "taken" -> if (first != null) "Taken. Try $first" else "Taken. Try another one."
            "invalid" -> if (body.str("reason") == "reserved") RESERVED + (first?.let { " Try $it" } ?: "") else CHARS
            else -> ""
        }
    }
}
