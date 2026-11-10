package sh.belfry.core.crypto

/** Bech32 (BIP-173) for npub / nsec / note (NIP-19, simple forms only). */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if (((b ushr i) and 1) == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code ushr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (v in data) {
            if (v < 0 || (v ushr from) != 0) return null
            acc = (acc shl from) or v
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out
    }

    fun encode(hrp: String, bytes: ByteArray): String {
        val data = convertBits(bytes.map { it.toInt() and 0xff }, 8, 5, true)!!
        val values = hrpExpand(hrp) + data + List(6) { 0 }
        val mod = polymod(values) xor 1
        val checksum = (0 until 6).map { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** (hrp, bytes) or null when the text is not valid bech32. */
    fun decode(text: String): Pair<String, ByteArray>? {
        if (text != text.lowercase() && text != text.uppercase()) return null
        val s = text.lowercase()
        val pos = s.lastIndexOf('1')
        if (pos < 1 || pos + 7 > s.length) return null
        val hrp = s.substring(0, pos)
        val data = ArrayList<Int>()
        for (c in s.substring(pos + 1)) {
            val i = CHARSET.indexOf(c)
            if (i < 0) return null
            data.add(i)
        }
        if (polymod(hrpExpand(hrp) + data) != 1) return null
        val bytes = convertBits(data.dropLast(6), 5, 8, false) ?: return null
        return hrp to ByteArray(bytes.size) { bytes[it].toByte() }
    }

    fun npub(pubkeyHex: String): String = encode("npub", Hex.decode(pubkeyHex))

    /** The hex key of an npub, or null. */
    fun npubToHex(npub: String): String? {
        val (hrp, bytes) = decode(npub) ?: return null
        if (hrp != "npub" || bytes.size != 32) return null
        return Hex.encode(bytes)
    }
}
