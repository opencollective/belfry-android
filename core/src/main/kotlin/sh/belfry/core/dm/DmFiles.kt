package sh.belfry.core.dm

import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Random
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.nostr.Tag
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Files, pictures and recordings in direct messages (the lead's contract 2026-11-21,
 * `vectors/dm-files.json`): one NIP-17 kind 15 rumor per file, the file AES-256-GCM encrypted on
 * the device (fresh 32-byte key, 12-byte nonce, the 16-byte tag appended), the ciphertext on the
 * belfry's Blossom. Pure: no network, no storage.
 */
object DmFiles {
    const val KIND = 15
    const val ALGORITHM = "aes-gcm"
    const val UNREADABLE = "This file can't be opened"

    /** A file sealed for upload: what the rumor's tags carry. */
    class Sealed(val key: String, val nonce: String, val ciphertext: ByteArray, val x: String, val ox: String, val size: Long)

    fun encrypt(plain: ByteArray, keyHex: String, nonceHex: String): ByteArray = cipher(Cipher.ENCRYPT_MODE, keyHex, nonceHex).doFinal(plain)

    /** Seals PLAIN with a fresh key and nonce (KEY_HEX / NONCE_HEX: tests only). */
    fun seal(plain: ByteArray, keyHex: String = Random.hex(32), nonceHex: String = Random.hex(12)): Sealed {
        val ct = encrypt(plain, keyHex, nonceHex)
        return Sealed(keyHex, nonceHex, ct, sha256Hex(ct), sha256Hex(plain), plain.size.toLong())
    }

    private fun cipher(mode: Int, keyHex: String, nonceHex: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(Hex.decodeOrNull(keyHex) ?: error("key"), "AES"), GCMParameterSpec(128, Hex.decodeOrNull(nonceHex) ?: error("nonce")))
        }

    /** The rumor's tags, in the contract's order. */
    fun tags(
        others: List<String>, mime: String, s: Sealed,
        dim: String? = null, blurhash: String? = null, thumbUrl: String? = null, thumbNonce: String? = null,
        duration: Int? = null, group: String? = null,
    ): List<Tag> = buildList {
        others.forEach { add(listOf("p", it)) }
        add(listOf("file-type", mime))
        add(listOf("encryption-algorithm", ALGORITHM))
        add(listOf("decryption-key", s.key))
        add(listOf("decryption-nonce", s.nonce))
        add(listOf("x", s.x))
        add(listOf("ox", s.ox))
        add(listOf("size", s.size.toString()))
        dim?.let { add(listOf("dim", it)) }
        blurhash?.let { add(listOf("blurhash", it)) }
        if (thumbUrl != null && thumbNonce != null) { add(listOf("thumb", thumbUrl)); add(listOf("thumb-nonce", thumbNonce)) }
        duration?.let { add(listOf("duration", it.toString())) }
        group?.let { add(listOf("group", it)) }
    }

    /** A fresh group tag for several pictures sent together. */
    fun newGroup(): String = Random.hex(8)

    private fun List<Tag>.tag(name: String): String? = firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    private fun isHex(s: String, bytes: Int) = s.length == bytes * 2 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * The plain file from what was downloaded, or null ([UNREADABLE]): x checked on the
     * ciphertext, then GCM's tag, then ox on the plain bytes. THUMB: the thumbnail (the file's key,
     * `thumb-nonce`; no x / ox for it, GCM's tag is the check).
     */
    fun open(tags: List<Tag>, downloaded: ByteArray, thumb: Boolean = false): ByteArray? {
        if (tags.tag("encryption-algorithm") != ALGORITHM) return null
        val key = tags.tag("decryption-key")?.takeIf { isHex(it, 32) } ?: return null
        val nonce = tags.tag(if (thumb) "thumb-nonce" else "decryption-nonce")?.takeIf { isHex(it, 12) } ?: return null
        if (!thumb) {
            val x = tags.tag("x") ?: return null
            tags.tag("ox") ?: return null
            if (sha256Hex(downloaded) != x) return null
        }
        val plain = try { cipher(Cipher.DECRYPT_MODE, key, nonce).doFinal(downloaded) } catch (e: Exception) { return null }
        if (!thumb && sha256Hex(plain) != tags.tag("ox")) return null
        return plain
    }

    /** What [bubbles] needs of a rumor. */
    data class Item(val id: String, val kind: Int, val pubkey: String, val createdAt: Long, val group: String?)

    /** One bubble: files (kind 15, in order) and a caption (a kind 14), either may be missing. */
    data class Bubble(val files: List<String>, val caption: String?, val at: Long)

    /**
     * A conversation's rumors (kinds 14 and 15) as bubbles in time order: the same author's
     * rumors with the same group are one bubble, files by created_at (ties: id), the caption under
     * them; a bubble's time is its first rumor's.
     */
    fun bubbles(items: List<Item>): List<Bubble> {
        val sorted = items.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val byGroup = LinkedHashMap<String, MutableList<Item>>()
        val out = ArrayList<Pair<Long, Any>>()
        for (it in sorted) {
            val g = it.group
            if (g == null) { out.add(it.createdAt to it); continue }
            val k = it.pubkey + "/" + g
            val list = byGroup[k]
            if (list == null) { val l = mutableListOf(it); byGroup[k] = l; out.add(it.createdAt to l) } else list.add(it)
        }
        return out.map { (at, v) ->
            @Suppress("UNCHECKED_CAST")
            val list = if (v is Item) listOf(v) else v as List<Item>
            Bubble(list.filter { it.kind == KIND }.map { it.id }, list.lastOrNull { it.kind == 14 }?.id, at)
        }
    }
}
