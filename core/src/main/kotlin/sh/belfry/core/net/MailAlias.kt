package sh.belfry.core.net

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.s

// A channel's e-mail address (SPEC 19.60; collective-belfry docs/mail.md "A channel's address",
// Belfry fd21c0e): `POST <api>/mail-alias`, NIP-98 signed.
//   {"channel": id}                → MailAliasInfo (any member)
//   {"channel": id, "check": "x"}  → AliasCheck (the channel's admins, the live check)
//   {"channel": id, "alias": "x"|null} → MailAliasInfo (sets / clears it: the belfry's admins, `may_set`)

/** The channel's address as the belfry answers it. [custom]: set by an admin (else automatic); [maySet]: I may change it. */
data class MailAliasInfo(val channel: String, val alias: String, val address: String, val custom: Boolean, val maySet: Boolean) {
    fun toJson(): JsonObject = buildJsonObject {
        put("channel", JsonPrimitive(channel)); put("alias", JsonPrimitive(alias)); put("address", JsonPrimitive(address))
        put("custom", JsonPrimitive(custom)); put("may_set", JsonPrimitive(maySet))
    }
}

/** What the live check says about a left part. */
enum class AliasState(val key: String, val words: String) {
    AVAILABLE("available", "✓ Available"),
    TAKEN("taken", "Taken by another channel or person"),
    INVALID("invalid", "Not a valid address"),
    RESERVED("reserved", "Reserved");

    companion object {
        fun of(key: String?): AliasState? = entries.firstOrNull { it.key == key }
    }
}

data class AliasCheck(val state: AliasState, val alias: String)

object MailAlias {
    /** The fixed words per error code (never the server's text). */
    val ERRORS = mapOf(
        "not_allowed" to "Only the belfry's admins can change a channel's address.",
        "taken" to "That address is taken.",
        "reserved" to "That address is reserved.",
        "archived" to "The channel is archived: unarchive it first.",
        "invalid" to "That is not a valid address.",
        "signer" to "The belfry could not save it just now. Try again in a moment.",
        "not_found" to "This channel is not on the belfry.",
    )
    const val FALLBACK = "The address could not be saved. Try again."

    fun parseInfo(body: JsonObject?): MailAliasInfo? {
        if (body == null) return null
        val channel = body.s("channel") ?: return null
        val address = body.s("address")?.takeIf { it.contains('@') } ?: return null
        val alias = body.s("alias") ?: address.substringBefore('@')
        return MailAliasInfo(channel, alias, address, body["custom"].bool() ?: false, body["may_set"].bool() ?: false)
    }

    fun parseCheck(body: JsonObject?): AliasCheck? {
        if (body == null) return null
        val state = AliasState.of(body.s("state")) ?: return null
        return AliasCheck(state, body.s("alias") ?: "")
    }

    /** An older belfry without the endpoint: 404, or 409 `unsupported`. The app works the address out itself then. */
    fun unsupported(status: Int, body: JsonObject?): Boolean = status == 404 || status == 405 || (status == 409 && body?.s("error") == "unsupported")

    /** The error for a refused call: its code, and the fixed words for it. */
    fun error(status: Int, body: JsonObject?): ApiException {
        val code = body?.s("error")?.ifEmpty { null } ?: when (status) {
            403 -> "not_allowed"; 422 -> "invalid"; 503 -> "signer"; 0 -> "network"; else -> "http"
        }
        return ApiException(code, status, ERRORS[code] ?: FALLBACK)
    }
}

/** The calls (one belfry, signed by me). */
class MailAliasApi(private val api: BelfryApi, private val signer: Signer) {
    private val url: String get() = api.community.apiBase + "/mail-alias"

    /** The channel's address; null on an older belfry (work it out locally). */
    suspend fun info(channel: String): MailAliasInfo? {
        val (status, body) = api.signedFetch(signer, url, "POST", buildJsonObject { put("channel", JsonPrimitive(channel)) })
        if (MailAlias.unsupported(status, body)) return null
        if (status !in 200..299) throw MailAlias.error(status, body)
        return MailAlias.parseInfo(body) ?: throw ApiException("http", status, MailAlias.FALLBACK)
    }

    /** The live check of a left part (the channel's admins). */
    suspend fun check(channel: String, alias: String): AliasCheck {
        val (status, body) = api.signedFetch(signer, url, "POST", buildJsonObject { put("channel", JsonPrimitive(channel)); put("check", JsonPrimitive(alias)) })
        if (status !in 200..299) throw MailAlias.error(status, body)
        return MailAlias.parseCheck(body) ?: throw ApiException("http", status, "Could not check the address.")
    }

    /** Set the left part, or (null) go back to the automatic address. The belfry's admins only. */
    suspend fun set(channel: String, alias: String?): MailAliasInfo {
        val (status, body) = api.signedFetch(signer, url, "POST", buildJsonObject {
            put("channel", JsonPrimitive(channel)); put("alias", alias?.let { JsonPrimitive(it) } ?: JsonNull)
        })
        if (status !in 200..299) throw MailAlias.error(status, body)
        return MailAlias.parseInfo(body) ?: throw ApiException("http", status, MailAlias.FALLBACK)
    }
}
