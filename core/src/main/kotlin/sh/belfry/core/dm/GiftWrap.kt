package sh.belfry.core.dm

import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Random
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.Tag
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.unixNow
import sh.belfry.core.nostr.withId

/** NIP-59 gift wraps carrying NIP-17 messages (kind 14) and Nostr Mail (kind 1301). SPEC 10. */
object GiftWrap {
    /** Random up to two days in the past, as NIP-59 asks. */
    fun backdate(now: Long = unixNow()): Long = now - Random.int(2 * 86400)

    /** An unsigned rumor with its id. */
    fun rumor(kind: Int, pubkey: String, tags: List<Tag>, content: String, createdAt: Long = unixNow()): Event =
        Event("", pubkey, createdAt, kind, tags, content, "").withId()

    /** Seal (signed by SIGNER, the author) and wrap (signed by a one-time key) RUMOR for TO. */
    suspend fun wrap(rumor: Event, to: String, signer: Signer): Event {
        if (!Hex.isKey(to)) throw IllegalArgumentException("not a key")
        val sealed = signer.nip44Encrypt(to, rumor.copy(sig = "").toJsonString())
        val seal = signer.sign(EventTemplate(13, sealed, emptyList(), backdate()))
        val oneTime = LocalSigner.random()
        val content = oneTime.nip44EncryptNow(to, seal.toJsonString())
        return oneTime.signNow(EventTemplate(1059, content, listOf(listOf("p", to)), backdate()))
    }

    class Opened(val rumor: Event, val sealPubkey: String)

    /**
     * Wrap → seal (signature checked) → rumor. Null when the seal is not valid or the rumor claims
     * another author. Throws what the signer throws (a timeout means "try again later").
     */
    suspend fun unwrap(wrap: Event, signer: Signer): Opened? {
        val sealJson = signer.nip44Decrypt(wrap.pubkey, wrap.content)
        val seal = Event.fromJsonString(sealJson) ?: return null
        if (seal.kind != 13 || !seal.isValid()) return null
        val rumorJson = signer.nip44Decrypt(seal.pubkey, seal.content)
        var rumor = Event.fromJsonString(rumorJson) ?: return null
        if (rumor.pubkey != seal.pubkey) return null
        if (rumor.id.isEmpty()) rumor = rumor.withId()
        return Opened(rumor.copy(sig = ""), seal.pubkey)
    }
}
