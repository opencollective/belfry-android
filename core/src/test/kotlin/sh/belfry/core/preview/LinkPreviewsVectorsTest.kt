package sh.belfry.core.preview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import sh.belfry.core.Vectors
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.nostr.s
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** vectors/link-previews.json (SPEC 19.87; written here by tools/link_previews_vectors.py): every case of every section. */
class LinkPreviewsVectorsTest {
    private val v = Vectors.load("link-previews.json")
    private val domain = v.s("domain")!!
    private val files = v.s("files")!!
    private val blossom = v.s("blossom")!!

    private fun cases(name: String) = (v[name] as JsonArray).map { it as JsonObject }
    private fun tags(e: JsonElement?): List<List<String>> = (e as JsonArray).map { t -> (t as JsonArray).map { it.jsonPrimitive.content } }
    private fun str(e: JsonElement?): String? = if (e == null || e is JsonNull) null else e.jsonPrimitive.content

    @Test fun sectionsAndConstants() {
        assertEquals(listOf("_", "constants", "domain", "files", "blossom", "links", "html", "charsets", "urls", "addresses", "pictures", "tags", "read"), v.keys.toList())
        val k = v["constants"] as JsonObject
        assertEquals(LinkPreviews.TITLE_MAX, k["title_max"]!!.jsonPrimitive.int)
        assertEquals(LinkPreviews.DESCRIPTION_MAX, k["description_max"]!!.jsonPrimitive.int)
        assertEquals(LinkPreviews.SITE_MAX, k["site_max"]!!.jsonPrimitive.int)
        assertEquals(LinkPreviews.PAGE_MAX, k["page_max_bytes"]!!.jsonPrimitive.int)
        assertEquals(LinkPreviews.PICTURE_IN_MAX, k["picture_in_max_bytes"]!!.jsonPrimitive.int)
        assertEquals(LinkPreviews.PICTURE_OUT_MAX, k["picture_out_max_bytes"]!!.jsonPrimitive.int)
        assertEquals(LinkPreviews.PICTURE_SIDE, k["picture_side_max"]!!.jsonPrimitive.int)
        assertEquals(LinkPreviews.REDIRECTS, k["redirects_max"]!!.jsonPrimitive.int)
        assertEquals(LinkPreviews.TIMEOUT_MS.toInt(), k["timeout_ms"]!!.jsonPrimitive.int)
        assertEquals(LinkPreviews.NO_PREVIEW, (k["no_preview_tag"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test fun links() {
        val all = cases("links")
        assertEquals(24, all.size)
        for (c in all) assertEquals(str(c["link"]), LinkPreviews.outsideLink(c.s("text")!!, tags(c["tags"]), domain, files), c.s("name"))
    }

    @Test fun html() {
        val all = cases("html")
        assertEquals(24, all.size)
        for (c in all) {
            val n = c.s("name")
            val m = PageMeta.parse(c.s("html")!!, c.s("final_url")!!)
            assertEquals(c.s("title"), m.title, "$n: title")
            assertEquals(c.s("description"), m.description, "$n: description")
            assertEquals(c.s("site"), m.site, "$n: site")
            assertEquals(str(c["image"]), m.image, "$n: image")
        }
    }

    @Test fun charsets() {
        val all = cases("charsets")
        assertEquals(4, all.size)
        for (c in all) {
            val html = PageMeta.decode(Hex.decode(c.s("bytes")!!), c.s("content_type"))
            assertEquals(c.s("title"), PageMeta.parse(html, "https://a.example/").title, c.s("name"))
        }
    }

    @Test fun urls() {
        val all = cases("urls")
        assertEquals(21, all.size)
        for (c in all) assertEquals(c["ok"]!!.jsonPrimitive.boolean, FetchRules.url(c.s("url")!!) != null, c.s("url"))
    }

    @Test fun addresses() {
        val all = cases("addresses")
        assertEquals(42, all.size)
        for (c in all) assertEquals(c["ok"]!!.jsonPrimitive.boolean, FetchRules.allowed(InetAddress.getByName(c.s("address")!!)), c.s("address"))
    }

    @Test fun pictures() {
        val all = cases("pictures")
        assertEquals(10, all.size)
        for (c in all) assertEquals(str(c["type"]), PreviewImages.kind(Hex.decode(c.s("hex")!!)), c.s("name"))
    }

    @Test fun tagsOnTheWire() {
        val all = cases("tags")
        assertEquals(3, all.size)
        for (c in all) {
            val n = c.s("name")
            val p = c["preview"] as JsonObject
            val pic = c["picture"] as? JsonObject
            val picture = pic?.let {
                val plain = Hex.decode(it.s("plain")!!)
                val key = it.s("key")
                if (key == null) PreviewPicture(it.s("url")!!, it.s("mime")!!, it["width"]!!.jsonPrimitive.int, it["height"]!!.jsonPrimitive.int, sha256Hex(plain))
                else {
                    val sealed = DmFiles.seal(plain, key, it.s("nonce")!!)
                    assertEquals(it.s("ciphertext"), Hex.encode(sealed.ciphertext), "$n: the ciphertext")
                    val pp = PreviewPicture(it.s("url")!!, it.s("mime")!!, it["width"]!!.jsonPrimitive.int, it["height"]!!.jsonPrimitive.int, sealed.x, sealed.ox, sealed.key, sealed.nonce)
                    assertContentEquals(plain, pp.open(sealed.ciphertext), "$n: opened again")
                    pp
                }
            }
            val lp = LinkPreview(p.s("url")!!, p.s("title")!!, p.s("description")!!, p.s("site")!!, picture)
            assertEquals(tags(c["tags"]), lp.tags(), "$n: the tags")
            // and read back as they were sent
            val back = LinkPreviews.read("See ${lp.url}", lp.tags(), blossom)
            assertEquals(lp, back, "$n: read back")
        }
    }

    @Test fun read() {
        val all = cases("read")
        assertEquals(23, all.size)
        for (c in all) {
            val n = c.s("name")
            val got = LinkPreviews.read(c.s("text")!!, tags(c["tags"]), blossom)
            val want = c["shown"] as? JsonObject
            if (want == null) { assertNull(got, n); continue }
            assertNotNull(got, n)
            assertEquals(want.s("url"), got.url, "$n: url")
            assertEquals(want.s("title"), got.title, "$n: title")
            assertEquals(want.s("description"), got.description, "$n: description")
            assertEquals(want.s("site"), got.site, "$n: site")
            assertEquals(want.s("site_line"), got.siteLine, "$n: site line")
            val wp = want["picture"] as? JsonObject
            if (wp == null) { assertNull(got.picture, "$n: no picture"); continue }
            val gp = assertNotNull(got.picture, "$n: a picture")
            assertEquals(wp.s("url"), gp.url, n)
            assertEquals(wp.s("mime"), gp.mime, n)
            assertEquals((wp["width"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int, gp.width, n)
            assertEquals((wp["height"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int, gp.height, n)
            assertEquals(wp["encrypted"]!!.jsonPrimitive.boolean, gp.encrypted, n)
        }
        assertTrue(all.any { it["shown"] is JsonNull })
    }

    /** The file is pinned: web and iOS copy it byte for byte (change it only with the generator, then this). */
    @Test fun pinned() {
        assertEquals(PINNED, sha256Hex(Vectors.file("link-previews.json").readBytes()))
    }

    companion object {
        const val PINNED = "bc7192106aabde0bf296afa267a5d16d1bb0b5891123b187cc6b0b4c8bafa39b"
    }
}
