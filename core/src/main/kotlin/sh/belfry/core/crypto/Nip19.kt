package sh.belfry.core.crypto

import java.io.ByteArrayOutputStream

/**
 * NIP-19 `naddr` (an addressable event's coordinate): TLV 0 = identifier (`d`, UTF-8), 1 = a relay
 * (UTF-8, any number), 2 = author (32 bytes), 3 = kind (u32 big-endian). Written in the order
 * 3, 2, 1, 0 like nostr-tools (the web chat), so both clients produce the same string; read in
 * any order. Vectors: vectors/channel-refs.json.
 */
object Nip19 {
    data class Addr(val kind: Int, val pubkey: String, val identifier: String, val relays: List<String> = emptyList())

    fun naddr(a: Addr): String {
        val out = ByteArrayOutputStream()
        fun tlv(type: Int, v: ByteArray) {
            require(v.size <= 255) { "TLV value too long" }
            out.write(type); out.write(v.size); out.write(v)
        }
        tlv(3, byteArrayOf((a.kind ushr 24).toByte(), (a.kind ushr 16).toByte(), (a.kind ushr 8).toByte(), a.kind.toByte()))
        tlv(2, Hex.decode(a.pubkey))
        for (r in a.relays) tlv(1, r.toByteArray(Charsets.UTF_8))
        tlv(0, a.identifier.toByteArray(Charsets.UTF_8))
        return Bech32.encode("naddr", out.toByteArray())
    }

    /** The coordinate an `naddr1…` holds, or null when it is not a valid naddr. */
    fun decodeNaddr(text: String): Addr? {
        val (hrp, data) = Bech32.decode(text) ?: return null
        if (hrp != "naddr") return null
        var identifier: String? = null
        var pubkey: String? = null
        var kind: Int? = null
        val relays = ArrayList<String>()
        var i = 0
        while (i < data.size) {
            if (i + 2 > data.size) return null
            val type = data[i].toInt() and 0xff
            val len = data[i + 1].toInt() and 0xff
            if (i + 2 + len > data.size) return null
            val v = data.copyOfRange(i + 2, i + 2 + len)
            when (type) {
                0 -> identifier = String(v, Charsets.UTF_8)
                1 -> relays.add(String(v, Charsets.UTF_8))
                2 -> { if (len != 32) return null; pubkey = Hex.encode(v) }
                3 -> { if (len != 4) return null; kind = ((v[0].toInt() and 0xff) shl 24) or ((v[1].toInt() and 0xff) shl 16) or ((v[2].toInt() and 0xff) shl 8) or (v[3].toInt() and 0xff) }
            }
            i += 2 + len
        }
        if (identifier == null || pubkey == null || kind == null) return null
        return Addr(kind, pubkey, identifier, relays)
    }
}
