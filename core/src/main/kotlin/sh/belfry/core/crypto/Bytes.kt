package sh.belfry.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Hex, hashes, base64, random bytes. No secp256k1 here (see Keys.kt). */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0f])
        }
        return sb.toString()
    }

    /** Null when the text is not valid hex of even length. */
    fun decodeOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun decode(hex: String): ByteArray = decodeOrNull(hex) ?: throw IllegalArgumentException("not hex")

    /** A 64-character lowercase hex string (a key or an event id). */
    fun isKey(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
}

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
fun sha256Hex(data: ByteArray): String = Hex.encode(sha256(data))
fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

object B64 {
    fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun decodeOrNull(s: String): ByteArray? = try { decode(s) } catch (e: IllegalArgumentException) { null }
    /** base64url without padding (PKCE, state). */
    fun url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

object Random {
    private val rng = SecureRandom()
    fun bytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun hex(n: Int): String = Hex.encode(bytes(n))
    /** 0 <= result < bound */
    fun int(bound: Int): Int = rng.nextInt(bound)
}
