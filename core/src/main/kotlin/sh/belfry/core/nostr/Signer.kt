package sh.belfry.core.nostr

/**
 * Who signs for the member. Real sessions use the belfry's remote signer (NIP-46, BunkerSigner);
 * the demo and one-time gift-wrap keys sign locally (LocalSigner).
 */
interface Signer {
    /** The member's public key (hex). */
    val pubkey: String

    /** Signs the template as [pubkey]. `createdAt` 0 means "now". */
    suspend fun sign(template: EventTemplate): Event

    suspend fun nip44Encrypt(peer: String, plaintext: String): String
    suspend fun nip44Decrypt(peer: String, payload: String): String

    /** Old direct messages (kind 4), read only. */
    suspend fun nip04Decrypt(peer: String, payload: String): String
}

/** Thrown when the remote signer does not answer in time (SPEC 3: 20 s). */
class SignerTimeout(message: String = "The signer did not answer") : Exception(message)

/** Any other failure of a signer or of local crypto. */
class CryptoException(message: String) : Exception(message)
