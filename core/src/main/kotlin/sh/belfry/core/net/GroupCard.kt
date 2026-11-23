package sh.belfry.core.net

import kotlinx.serialization.json.JsonObject
import sh.belfry.core.crypto.Hex
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.s
import java.net.URLEncoder

// The printed card a group or topic was started from, for its stewards (SPEC 19.76;
// collective-belfry docs/cards.md §2.5, §2.6; web core/cards.ts `groupCard` / `retireCard`).
// Both NIP-98 signed by one of the group's stewards (a guest who started it from the card too:
// §4) or a belfry admin.
//   GET  <api>/groups/<group>/card → {id, kind, started_by: {name, pubkey}, joined_by_scan}
//        404 not_found: no card in use (none, retired, reset) · 403 not_allowed: not a steward
//   POST <api>/cards/<id>/retire   → {state: "retired"} (again on a retired card: the same)

/** [startedName] / [startedBy]: who claimed it, as the register knows them (the name may be ""; the key null). */
data class GroupCard(val id: String, val topic: Boolean, val startedName: String, val startedBy: String?, val joinedByScan: Int)

object GroupCards {
    /** A card's id: 6 of the capitals and digits a card is printed with (no O, I, L, 0, 1). */
    private val ID = Regex("^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$")

    /** The fixed words per error code (never the server's text). */
    val ERRORS = mapOf(
        "not_allowed" to "Only the stewards and the belfry’s admins can retire this card.",
        "not_found" to "This card is not there any more.",
        "unauthorized" to "Your session was not accepted. Sign in again.",
        "unsupported" to "This belfry has no printed cards.",
        "too_many" to "Too many tries. Wait a minute and try again.",
        "network" to "No connection to the belfry. Check your connection and try again.",
    )
    const val FALLBACK = "The card could not be retired. Try again."

    /** The answer of `GET /groups/<group>/card`; null when it is not a card's (no id a card can have). */
    fun parse(body: JsonObject?): GroupCard? {
        if (body == null) return null
        val id = body.s("id")?.uppercase()?.takeIf { ID.matches(it) } ?: return null
        val by = body["started_by"].obj()
        return GroupCard(
            id = id, topic = body.s("kind") == "topic",
            startedName = by?.s("name")?.trim().orEmpty(), startedBy = by?.s("pubkey")?.takeIf(Hex::isKey),
            joinedByScan = (body["joined_by_scan"].int() ?: 0).coerceAtLeast(0),
        )
    }

    /**
     * The card's address, what its QR holds (docs/cards.md §1): `https://chat.<domain>/g/<id>`, on the
     * chat's own host (CHAT_URL: belfry.json's chat address, whatever path it has).
     */
    fun address(chatUrl: String, id: String): String {
        val origin = try { java.net.URI(chatUrl).let { u -> if (u.scheme != null && u.host != null) u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else "") else null } } catch (e: Exception) { null }
        return (origin ?: chatUrl.trimEnd('/')) + "/g/" + id
    }

    /** "1 person joined by scanning it" / "8 people joined by scanning it". */
    fun joinedWords(n: Int): String = (if (n == 1) "1 person" else "$n people") + " joined by scanning it"

    fun error(status: Int, body: JsonObject?): ApiException {
        val code = body?.s("error")?.ifEmpty { null } ?: when (status) {
            401 -> "unauthorized"; 403 -> "not_allowed"; 404 -> "not_found"; 429 -> "too_many"; 0 -> "network"; else -> "http"
        }
        return ApiException(code, status, ERRORS[code] ?: FALLBACK)
    }
}

/** The calls (one belfry, signed by me). */
class GroupCardApi(private val api: BelfryApi, private val signer: Signer) {
    private val base: String get() = api.community.apiBase
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** GROUP's card; null when it has none in use, I may not see it, or the belfry has no cards. */
    suspend fun forGroup(group: String): GroupCard? {
        val (status, body) = api.signedFetch(signer, "$base/groups/${enc(group)}/card", "GET")
        if (status == 404 || status == 403 || status == 405 || (status == 409 && body?.s("error") == "unsupported")) return null
        if (status !in 200..299) throw GroupCards.error(status, body)
        return GroupCards.parse(body)
    }

    /** Retires card ID: its link stops letting people in; the group and its people stay. */
    suspend fun retire(id: String) {
        val (status, body) = api.signedFetch(signer, "$base/cards/${enc(id)}/retire", "POST", JsonObject(emptyMap()))
        if (status !in 200..299) throw GroupCards.error(status, body)
    }
}
