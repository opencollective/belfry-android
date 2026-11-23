package sh.belfry.core.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.model.CardError
import sh.belfry.core.model.CardFailed
import sh.belfry.core.model.CardForm
import sh.belfry.core.model.CardInfo
import sh.belfry.core.model.CardKind
import sh.belfry.core.model.Cards
import sh.belfry.core.model.HashtagState
import sh.belfry.core.model.WayIn
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.s
import java.io.IOException

/** A visitor's right code: the session on the account (bound to the key that signed), and the group made. */
data class CardClaimed(val signed: WayIn.Signed, val group: String)

/**
 * Printed cards on the wire (collective-belfry docs/cards.md §2; SPEC 19.72–19.74), under
 * `<api>/cards`. Every refusal is a [CardFailed] whose code picks the app's own words; the
 * server's `message` is never read. No connection = `OFFLINE`.
 */
class CardsApi(private val api: BelfryApi) {
    private val base: String get() = api.community.apiBase + "/cards"

    private suspend fun <T> wire(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw CardFailed(if (e.code == "network") CardError.OFFLINE else if (e.code == "signer") CardError.SIGNER else CardError.FAILED)
    } catch (e: IOException) {
        throw CardFailed(CardError.OFFLINE)
    }

    /** `GET /cards/<id>` (no authentication). An id that is not one is never asked: unknown. */
    suspend fun info(id: String): CardInfo {
        if (!Cards.isId(id)) return CardInfo.Unknown
        val (status, body) = wire { api.plainFetch("$base/$id") }
        if (status == 404) return CardInfo.Unknown
        if (status !in 200..299) throw Cards.failure(status, body)
        return Cards.infoOf(body ?: return CardInfo.Unknown)
    }

    /** `GET /cards/hashtag/<name>`: is it free for a new topic? Never "free" when the belfry could not check. */
    suspend fun hashtag(name: String): HashtagState {
        val h = Cards.hashtagSlug(name)
        if (h.length < 2) return HashtagState.INVALID
        val (status, body) = try { wire { api.plainFetch("$base/hashtag/${encodeURIComponent(h)}") } } catch (e: CardFailed) { return HashtagState.UNKNOWN }
        return Cards.hashtagAnswer(status, body)
    }

    /** Signed in (a member, or a guest with a saved account): `POST /cards/<id>/claim`, NIP-98 by the account. The group's id. */
    suspend fun claim(signer: Signer, id: String, kind: CardKind, f: CardForm): String {
        val d = post(signer, "$id/claim", Cards.claimBody(kind, f))
        return d.s("group")?.takeIf(Cards::isGroup) ?: throw CardFailed(CardError.FAILED)
    }

    /** A visitor: the code goes to EMAIL. Signed by this phone's applicant key (it becomes the session's). */
    suspend fun claimStart(id: String, email: String, secretHex: String) {
        post(LocalSigner(secretHex), "$id/claim-start", buildJsonObject { put("email", JsonPrimitive(email.trim())) })
    }

    /** The code: the group or topic is made now, and the answer is a session on the key that signed. */
    suspend fun claimVerify(id: String, kind: CardKind, f: CardForm, code: String, secretHex: String): CardClaimed {
        val d = post(LocalSigner(secretHex), "$id/claim-verify", Cards.verifyBody(kind, f, code))
        val group = d.s("group")?.takeIf(Cards::isGroup)
        val bunker = d.s("bunker_url")?.takeIf { it.length <= 2000 && BUNKER.containsMatchIn(it) && BunkerUrl.parse(it) != null }
        val pubkey = d.s("pubkey")?.takeIf { KEY.matches(it) }
        if (d["signed_in"].bool() != true || group == null || bunker == null || pubkey == null) throw CardFailed(CardError.FAILED)
        return CardClaimed(WayIn.Signed(bunker, pubkey), group)
    }

    private suspend fun post(signer: Signer, path: String, body: JsonObject): JsonObject {
        val (status, d) = wire { api.signedFetch(signer, "$base/$path", "POST", body) }
        if (status !in 200..299) throw Cards.failure(status, d)
        return d ?: JsonObject(emptyMap())
    }

    companion object {
        private val BUNKER = Regex("^bunker://[0-9a-f]{64}(\\?|$)")
        private val KEY = Regex("^[0-9a-f]{64}$")
    }
}
