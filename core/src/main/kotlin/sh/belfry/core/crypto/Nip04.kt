package sh.belfry.core.crypto

import sh.belfry.core.nostr.CryptoException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** NIP-04 (old direct messages and some signers' answers): AES-256-CBC with the ECDH X as key. */
object Nip04 {
    fun decrypt(secretHex: String, peerHex: String, payload: String): String {
        val parts = payload.split("?iv=")
        if (parts.size != 2) throw CryptoException("not a NIP-04 payload")
        val data = B64.decodeOrNull(parts[0]) ?: throw CryptoException("invalid base64")
        val iv = B64.decodeOrNull(parts[1]) ?: throw CryptoException("invalid base64")
        if (iv.size != 16) throw CryptoException("invalid iv")
        val secret = Hex.decodeOrNull(secretHex) ?: throw CryptoException("bad hex")
        val peer = Hex.decodeOrNull(peerHex) ?: throw CryptoException("bad hex")
        val key = Keys.sharedX(secret, peer)
        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(c.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            throw CryptoException("could not decrypt")
        }
    }

    /** Only tests and fake signers write NIP-04. */
    fun encrypt(secretHex: String, peerHex: String, plaintext: String): String {
        val key = Keys.sharedX(Hex.decode(secretHex), Hex.decode(peerHex))
        val iv = Random.bytes(16)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return B64.encode(c.doFinal(plaintext.toByteArray(Charsets.UTF_8))) + "?iv=" + B64.encode(iv)
    }
}
