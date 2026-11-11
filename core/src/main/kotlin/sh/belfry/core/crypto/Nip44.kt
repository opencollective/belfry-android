package sh.belfry.core.crypto

import sh.belfry.core.nostr.CryptoException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-44 v2: secp256k1 ECDH, HKDF-SHA256, ChaCha20, HMAC-SHA256, padding. Checked against the
 * official vectors (vectors/nip44.json).
 */
object Nip44 {
    const val MIN_PLAINTEXT = 1
    const val MAX_PLAINTEXT = 65535

    class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        var out = ByteArray(0)
        var t = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            out += t
            counter++
        }
        return out.copyOf(length)
    }

    fun conversationKey(secret: ByteArray, publicKey: ByteArray): ByteArray {
        val x = Keys.sharedX(secret, publicKey)
        return hmacSha256("nip44-v2".toByteArray(Charsets.UTF_8), x) // HKDF-extract(salt, ikm)
    }

    fun conversationKey(secretHex: String, publicKeyHex: String): ByteArray {
        val s = Hex.decodeOrNull(secretHex) ?: throw CryptoException("bad hex")
        val p = Hex.decodeOrNull(publicKeyHex) ?: throw CryptoException("bad hex")
        return conversationKey(s, p)
    }

    fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        if (conversationKey.size != 32) throw CryptoException("invalid conversation key length")
        if (nonce.size != 32) throw CryptoException("invalid nonce length")
        val k = hkdfExpand(conversationKey, nonce, 76)
        return MessageKeys(k.copyOfRange(0, 32), k.copyOfRange(32, 44), k.copyOfRange(44, 76))
    }

    fun calcPaddedLen(len: Int): Int {
        if (len <= 32) return 32
        var nextPower = 1
        while (nextPower < len) nextPower = nextPower shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    private fun pad(plaintext: String): ByteArray {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        if (bytes.size < MIN_PLAINTEXT || bytes.size > MAX_PLAINTEXT) throw CryptoException("invalid plaintext length")
        val out = ByteArray(2 + calcPaddedLen(bytes.size))
        out[0] = (bytes.size ushr 8).toByte()
        out[1] = (bytes.size and 0xff).toByte()
        System.arraycopy(bytes, 0, out, 2, bytes.size)
        return out
    }

    private fun unpad(p: ByteArray): String {
        if (p.size < 2) throw CryptoException("invalid padding")
        val len = ((p[0].toInt() and 0xff) shl 8) or (p[1].toInt() and 0xff)
        if (len < MIN_PLAINTEXT || len > MAX_PLAINTEXT || p.size != 2 + calcPaddedLen(len) || len + 2 > p.size) {
            throw CryptoException("invalid padding")
        }
        return String(p, 2, len, Charsets.UTF_8)
    }

    /** NONCE is random unless a test fixes it. */
    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = Random.bytes(32)): String {
        val keys = messageKeys(conversationKey, nonce)
        val padded = pad(plaintext)
        val ciphertext = ChaCha20.xor(keys.chachaKey, keys.chachaNonce, padded)
        val mac = hmacSha256(keys.hmacKey, nonce + ciphertext)
        return B64.encode(byteArrayOf(2) + nonce + ciphertext + mac)
    }

    /**
     * A random 32-byte key used directly as the conversation key, without ECDH (push payloads:
     * the phone makes the key and registers it with the belfry, docs/push.md §4). Same cipher.
     */
    fun encryptWithKey(plaintext: String, key: ByteArray, nonce: ByteArray = Random.bytes(32)): String {
        if (key.size != 32) throw CryptoException("a conversation key is 32 bytes")
        return encrypt(plaintext, key, nonce)
    }

    fun decryptWithKey(payload: String, key: ByteArray): String {
        if (key.size != 32) throw CryptoException("a conversation key is 32 bytes")
        return decrypt(payload, key)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        if (payload.isEmpty() || payload.startsWith("#")) throw CryptoException("unknown version")
        val plen = payload.toByteArray(Charsets.UTF_8).size
        if (plen < 132 || plen > 87472) throw CryptoException("invalid payload size")
        val d = B64.decodeOrNull(payload) ?: throw CryptoException("invalid base64")
        if (d.size < 99 || d.size > 65603) throw CryptoException("invalid data size")
        if (d[0].toInt() != 2) throw CryptoException("unknown version")
        val nonce = d.copyOfRange(1, 33)
        val ciphertext = d.copyOfRange(33, d.size - 32)
        val mac = d.copyOfRange(d.size - 32, d.size)
        val keys = messageKeys(conversationKey, nonce)
        val expected = hmacSha256(keys.hmacKey, nonce + ciphertext)
        if (!MessageDigest.isEqual(expected, mac)) throw CryptoException("invalid MAC")
        return unpad(ChaCha20.xor(keys.chachaKey, keys.chachaNonce, ciphertext))
    }

    /** Encrypts as SECRET for PEER (both hex). */
    fun encryptFor(secretHex: String, peerHex: String, plaintext: String): String =
        encrypt(plaintext, conversationKey(secretHex, peerHex))

    fun decryptFrom(secretHex: String, peerHex: String, payload: String): String =
        decrypt(payload, conversationKey(secretHex, peerHex))
}

/** ChaCha20 (RFC 8439), counter starting at 0, as NIP-44 uses it (hand-written: Android's JCE has it only from API 28). */
object ChaCha20 {
    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    private fun word(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

    fun xor(key: ByteArray, nonce: ByteArray, data: ByteArray, counter: Int = 0): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val state = IntArray(16)
        state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = word(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = word(nonce, i * 4)
        val out = data.copyOf()
        val block = ByteArray(64)
        var offset = 0
        while (offset < out.size) {
            val x = state.copyOf()
            for (round in 0 until 10) {
                quarter(x, 0, 4, 8, 12); quarter(x, 1, 5, 9, 13); quarter(x, 2, 6, 10, 14); quarter(x, 3, 7, 11, 15)
                quarter(x, 0, 5, 10, 15); quarter(x, 1, 6, 11, 12); quarter(x, 2, 7, 8, 13); quarter(x, 3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val v = x[i] + state[i]
                block[i * 4] = (v and 0xff).toByte()
                block[i * 4 + 1] = ((v ushr 8) and 0xff).toByte()
                block[i * 4 + 2] = ((v ushr 16) and 0xff).toByte()
                block[i * 4 + 3] = ((v ushr 24) and 0xff).toByte()
            }
            val n = minOf(64, out.size - offset)
            for (i in 0 until n) out[offset + i] = (out[offset + i].toInt() xor block[i].toInt()).toByte()
            offset += 64
            state[12] = state[12] + 1
        }
        return out
    }
}
