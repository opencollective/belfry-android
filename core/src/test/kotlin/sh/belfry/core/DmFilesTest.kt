package sh.belfry.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.s
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** vectors/dm-files.json: the cipher, the rumors a sender makes, what a reader opens, bubbles. */
class DmFilesTest {
    private val v = Vectors.load("dm-files.json")
    private fun hex(s: String?) = Hex.decodeOrNull(s!!)!!
    private fun tags(a: kotlinx.serialization.json.JsonElement?) = (a as JsonArray).map { t -> (t as JsonArray).map { (it as JsonPrimitive).content } }

    @Test
    fun cipher() {
        for (c in v["crypto"].arr()!!.map { it.obj()!! }) {
            val ct = DmFiles.encrypt(hex(c.s("plain_hex")), c.s("key")!!, c.s("nonce")!!)
            assertEquals(c.s("ciphertext_hex"), Hex.encode(ct), c.s("note"))
            assertEquals(c.s("x"), sha256Hex(ct))
            assertEquals(c.s("ox"), sha256Hex(hex(c.s("plain_hex"))))
        }
    }

    @Test
    fun rumors() {
        val send = v["send"].obj()!!
        val cases = listOf(send["picture"].obj()!!, send["file"].obj()!!, send["audio"].obj()!!) + send["group"].obj()!!["files"].arr()!!.map { it.obj()!! }
        for (c in cases) {
            val r = c["rumor"].obj()!!
            val t = tags(r["tags"])
            fun tag(n: String) = t.firstOrNull { it[0] == n }?.get(1)
            val s = DmFiles.seal(hex(c.s("plain_hex")), tag("decryption-key")!!, tag("decryption-nonce")!!)
            assertEquals(c.s("ciphertext_hex"), Hex.encode(s.ciphertext))
            val built = DmFiles.tags(
                t.filter { it[0] == "p" }.map { it[1] }, tag("file-type")!!, s,
                dim = tag("dim"), blurhash = tag("blurhash"), thumbUrl = tag("thumb"), thumbNonce = tag("thumb-nonce"),
                duration = tag("duration")?.toInt(), group = tag("group"),
            )
            assertEquals(t, built)
            assertEquals(r.s("content"), "https://blossom.example.org/" + s.x)
            assertEquals(r.s("id"), Event.computeId(r.s("pubkey")!!, r["created_at"].long()!!, r["kind"].int()!!, t, r.s("content")!!))
        }
        val cap = send["group"].obj()!!["caption"].obj()!!
        assertEquals(cap.s("id"), Event.computeId(cap.s("pubkey")!!, cap["created_at"].long()!!, 14, tags(cap["tags"]), cap.s("content")!!))
    }

    @Test
    fun reading() {
        for (c in v["read"].arr()!!.map { it.obj()!! }) {
            val got = DmFiles.open(tags(c["tags"]), hex(c.s("downloaded_hex")), thumb = c["thumb"].bool() == true)
            val e = c["expect"].obj()!!
            if (e["ok"].bool() == true) {
                assertNotNull(got, c.s("note"))
                assertContentEquals(hex(e.s("plain_hex")), got, c.s("note"))
            } else {
                assertNull(got, c.s("note"))
            }
        }
        assertEquals("This file can't be opened", v.s("unreadable"))
        assertEquals(DmFiles.UNREADABLE, v.s("unreadable"))
    }

    @Test
    fun bubbles() {
        val b = v["bubbles"].obj()!!
        val items = b["rumors"].arr()!!.map { it.obj()!! }.map {
            DmFiles.Item(it.s("id")!!, it["kind"].int()!!, it.s("pubkey")!!, it["created_at"].long()!!, it.s("group"))
        }
        val got = DmFiles.bubbles(items).map { it.files to it.caption }
        val want = b["expect"].arr()!!.map { it.obj()!! }.map { o -> o["files"].arr()!!.map { (it as JsonPrimitive).content } to o.s("caption") }
        assertEquals(want, got)
    }
}
