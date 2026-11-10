package sh.belfry.core.crypto

import fr.acinq.secp256k1.Secp256k1
import sh.belfry.core.nostr.CryptoException

/**
 * secp256k1 through fr.acinq.secp256k1-kmp: x-only public keys, BIP-340 Schnorr signatures and
 * the ECDH shared point (the unhashed X coordinate, as NIP-44 and NIP-04 want).
 */
object Keys {
    /** A fresh secret key (32 bytes). */
    fun generateSecret(): ByteArray {
        while (true) {
            val d = Random.bytes(32)
            if (isSecret(d)) return d
        }
    }

    fun generateSecretHex(): String = Hex.encode(generateSecret())

    fun isSecret(secret: ByteArray): Boolean =
        secret.size == 32 && try { Secp256k1.secKeyVerify(secret) } catch (e: Throwable) { false }

    /** The x-only public key (32 bytes) of a secret key. */
    fun publicKey(secret: ByteArray): ByteArray {
        if (!isSecret(secret)) throw CryptoException("invalid secret key")
        val full = try { Secp256k1.pubkeyCreate(secret) } catch (e: Throwable) { throw CryptoException("invalid secret key") }
        return full.copyOfRange(1, 33)
    }

    fun publicKeyHex(secretHex: String): String =
        Hex.encode(publicKey(Hex.decodeOrNull(secretHex) ?: throw CryptoException("bad hex")))

    /** BIP-340 signature (64 bytes) of a 32-byte message (an event id), with fresh auxiliary randomness. */
    fun sign(message: ByteArray, secret: ByteArray): ByteArray {
        if (message.size != 32) throw CryptoException("message must be 32 bytes")
        if (!isSecret(secret)) throw CryptoException("invalid secret key")
        return try {
            Secp256k1.signSchnorr(message, secret, Random.bytes(32))
        } catch (e: Throwable) {
            throw CryptoException("could not sign")
        }
    }

    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || message.size != 32 || publicKey.size != 32) return false
        return try { Secp256k1.verifySchnorr(signature, message, publicKey) } catch (e: Throwable) { false }
    }

    /** The X coordinate of secret × peer (peer given x-only, even Y assumed as in BIP-340). */
    fun sharedX(secret: ByteArray, publicKey: ByteArray): ByteArray {
        if (publicKey.size != 32) throw CryptoException("bad key length")
        if (!isSecret(secret)) throw CryptoException("invalid secret key")
        val point = try {
            Secp256k1.pubKeyTweakMul(byteArrayOf(2) + publicKey, secret)
        } catch (e: Throwable) {
            throw CryptoException("invalid public key")
        }
        if (point.size != 33 && point.size != 65) throw CryptoException("unexpected shared point size")
        return point.copyOfRange(1, 33)
    }
}
