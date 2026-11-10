package sh.belfry.core.nostr

import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.crypto.Nip04
import sh.belfry.core.crypto.Nip44

/** Unix seconds, now. */
fun unixNow(): Long = System.currentTimeMillis() / 1000

/** The id the event should have (sha256 of its NIP-01 serialization). */
fun Event.computedId(): String = Event.computeId(pubkey, createdAt, kind, tags, content)

/** The same event with its id filled in. */
fun Event.withId(): Event = copy(id = computedId())

/** True when the id matches the content and the signature is the author's (BIP-340). */
fun Event.isValid(): Boolean {
    if (!Hex.isKey(id) || !Hex.isKey(pubkey) || sig.length != 128) return false
    if (id != computedId()) return false
    val s = Hex.decodeOrNull(sig) ?: return false
    return Keys.verify(s, Hex.decode(id), Hex.decode(pubkey))
}

/** Alias of [isValid]. */
fun Event.verify(): Boolean = isValid()

/** The unsigned event (with its id) this template makes for an author. `createdAt` 0 = now. */
fun EventTemplate.toEvent(pubkey: String): Event {
    val at = if (createdAt > 0) createdAt else unixNow()
    return Event(Event.computeId(pubkey, at, kind, tags, content), pubkey, at, kind, tags, content, "")
}

/** Signs with a key held on this device: the demo, one-time gift-wrap keys, the NIP-46 client key. */
class LocalSigner(val secretHex: String) : Signer {
    private val secret: ByteArray = Hex.decodeOrNull(secretHex) ?: throw CryptoException("bad hex")
    override val pubkey: String = Hex.encode(Keys.publicKey(secret))

    fun signNow(template: EventTemplate): Event {
        val e = template.toEvent(pubkey)
        return e.copy(sig = Hex.encode(Keys.sign(Hex.decode(e.id), secret)))
    }

    override suspend fun sign(template: EventTemplate): Event = signNow(template)

    fun nip44EncryptNow(peer: String, plaintext: String): String = Nip44.encryptFor(secretHex, peer, plaintext)
    fun nip44DecryptNow(peer: String, payload: String): String = Nip44.decryptFrom(secretHex, peer, payload)

    override suspend fun nip44Encrypt(peer: String, plaintext: String): String = nip44EncryptNow(peer, plaintext)
    override suspend fun nip44Decrypt(peer: String, payload: String): String = nip44DecryptNow(peer, payload)
    override suspend fun nip04Decrypt(peer: String, payload: String): String = Nip04.decrypt(secretHex, peer, payload)

    companion object {
        fun random(): LocalSigner = LocalSigner(Keys.generateSecretHex())
    }
}
