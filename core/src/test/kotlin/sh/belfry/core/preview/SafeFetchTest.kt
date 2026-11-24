package sh.belfry.core.preview

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.engine.DemoPreviews
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sender's fetches (SPEC 19.87) on a fake resolver and a fake HTTP layer: never a real page.
 * Every hop checked, the name resolved and every address judged before anything is asked,
 * redirects, time, type and size limits; then the builder's picture rules.
 */
class SafeFetchTest {
    private val public = InetAddress.getByName("93.184.215.14")

    /** Names → addresses; anything else unknown. Counts lookups. */
    private class FakeDns(val table: Map<String, List<String>>) : Resolver {
        val asked = ArrayList<String>()
        override suspend fun resolve(host: String): List<InetAddress> { asked.add(host); return table[host]?.map { InetAddress.getByName(it) } ?: emptyList() }
    }

    /** URL → answer; records every request with the addresses it was pinned to. */
    private class FakeNet(val pages: Map<String, NetAnswer>, val wait: Map<String, Long> = emptyMap()) : PreviewNet {
        val asked = ArrayList<Pair<String, List<InetAddress>>>()
        override suspend fun get(url: String, host: String, addresses: List<InetAddress>, accept: String, maxBytes: Int, timeoutMs: Long): NetAnswer? {
            asked.add(url to addresses)
            wait[url]?.let { delay(it) }
            val a = pages[url] ?: return NetAnswer(404)
            if (a.status != 200) return a
            val over = a.body.size > maxBytes
            return NetAnswer(a.status, a.location, a.contentType, a.contentLength, if (over) a.body.copyOf(maxBytes) else a.body, over)
        }
    }

    private fun html(s: String, type: String = "text/html; charset=utf-8") = NetAnswer(200, contentType = type, body = s.toByteArray())
    private fun redirect(to: String, code: Int = 302) = NetAnswer(code, location = to)
    private val page = "<title>Hello</title><meta property=\"og:description\" content=\"A page\">"

    private val dns = FakeDns(mapOf(
        "a.example" to listOf("93.184.215.14"), "b.example" to listOf("8.8.8.8"), "c.example" to listOf("1.1.1.1"), "d.example" to listOf("9.9.9.9"),
        "e.example" to listOf("8.8.4.4"),
        "inside.example" to listOf("10.0.0.5"), "meta.example" to listOf("169.254.169.254"), "loop.example" to listOf("127.0.0.1"),
        "mixed.example" to listOf("93.184.215.14", "192.168.1.10"), "six.example" to listOf("fd00::1"),
    ))

    @Test fun aPlainPage() = runBlocking {
        val net = FakeNet(mapOf("https://a.example/x" to html(page)))
        val r = assertIs<SafeFetch.Result.Ok>(SafeFetch(net, dns).page("https://a.example/x"))
        assertEquals("https://a.example/x", r.got.finalUrl)
        assertEquals(listOf(public), net.asked.single().second, "asked of exactly the address that was checked")
    }

    @Test fun refusedBeforeAnythingIsAsked() = runBlocking {
        val net = FakeNet(emptyMap())
        val f = SafeFetch(net, dns)
        for ((url, why) in listOf(
            "http://a.example/x" to SafeFetch.Why.URL, "https://a.example:8080/x" to SafeFetch.Why.URL, "https://u:p@a.example/" to SafeFetch.Why.URL,
            "https://10.0.0.5/" to SafeFetch.Why.URL, "https://[::1]/" to SafeFetch.Why.URL, "https://intranet/" to SafeFetch.Why.URL,
            "https://unknown.example/" to SafeFetch.Why.RESOLVE,
            "https://inside.example/" to SafeFetch.Why.ADDRESS, "https://meta.example/latest" to SafeFetch.Why.ADDRESS,
            "https://loop.example/" to SafeFetch.Why.ADDRESS, "https://six.example/" to SafeFetch.Why.ADDRESS,
            "https://mixed.example/" to SafeFetch.Why.ADDRESS,
        )) assertEquals(why, (f.page(url) as SafeFetch.Result.Refused).why, url)
        assertTrue(net.asked.isEmpty(), "nothing was asked: ${net.asked}")
        assertTrue("10.0.0.5" !in dns.asked, "an IP literal is never even looked up")
    }

    @Test fun redirectsAreCheckedEachTimeAndAtMostThree() = runBlocking {
        val net = FakeNet(mapOf(
            "https://a.example/1" to redirect("https://b.example/2", 301),
            "https://b.example/2" to redirect("/3", 307), // relative: against the hop it came from
            "https://b.example/3" to redirect("https://c.example/4", 308),
            "https://c.example/4" to html(page),
        ))
        val r = assertIs<SafeFetch.Result.Ok>(SafeFetch(net, dns).page("https://a.example/1"))
        assertEquals("https://c.example/4", r.got.finalUrl)
        assertEquals(4, net.asked.size)

        val four = FakeNet(mapOf(
            "https://a.example/1" to redirect("https://b.example/2"), "https://b.example/2" to redirect("https://c.example/3"),
            "https://c.example/3" to redirect("https://d.example/4"), "https://d.example/4" to redirect("https://e.example/5"),
            "https://e.example/5" to html(page),
        ))
        assertEquals(SafeFetch.Why.REDIRECTS, (SafeFetch(four, dns).page("https://a.example/1") as SafeFetch.Result.Refused).why)
        assertEquals(4, four.asked.size, "the fifth address is never asked")

        // a redirect to a private address, to http, to a raw IP: refused at that hop
        for (to in listOf("https://inside.example/", "http://b.example/", "https://127.0.0.1/", "https://meta.example/")) {
            val n = FakeNet(mapOf("https://a.example/r" to redirect(to)))
            assertIs<SafeFetch.Result.Refused>(SafeFetch(n, dns).page("https://a.example/r"), to)
            assertEquals(1, n.asked.size, to)
        }
        // a redirect with no Location
        assertEquals(SafeFetch.Why.STATUS, (SafeFetch(FakeNet(mapOf("https://a.example/r" to NetAnswer(302))), dns).page("https://a.example/r") as SafeFetch.Result.Refused).why)
    }

    @Test fun onlyHtmlAndOnly200() = runBlocking {
        for (type in listOf("application/json", "text/plain", "image/png", null, "application/xhtml+xml")) {
            val n = FakeNet(mapOf("https://a.example/x" to NetAnswer(200, contentType = type, body = page.toByteArray())))
            assertEquals(SafeFetch.Why.TYPE, (SafeFetch(n, dns).page("https://a.example/x") as SafeFetch.Result.Refused).why, "$type")
        }
        assertIs<SafeFetch.Result.Ok>(SafeFetch(FakeNet(mapOf("https://a.example/x" to html(page, "TEXT/HTML")))), dns).page("https://a.example/x"))
        for (code in listOf(204, 304, 403, 404, 500)) {
            val n = FakeNet(mapOf("https://a.example/x" to NetAnswer(code)))
            assertEquals(SafeFetch.Why.STATUS, (SafeFetch(n, dns).page("https://a.example/x") as SafeFetch.Result.Refused).why, "$code")
        }
    }

    @Test fun aLongPageIsReadToItsFirst512kB() = runBlocking {
        val big = "<title>Big</title>" + "x".repeat(700_000) + "<meta property=\"og:title\" content=\"Too far\">"
        val n = FakeNet(mapOf("https://a.example/x" to html(big)))
        val r = assertIs<SafeFetch.Result.Ok>(SafeFetch(n, dns).page("https://a.example/x"))
        assertEquals(LinkPreviews.PAGE_MAX, r.got.answer.body.size)
        val b = PreviewBuilder(SafeFetch(n, dns), null, { _, _ -> error("no upload") }, "https://blossom.example")
        assertEquals("Big", b.build("https://a.example/x", PreviewMode.PLAIN)!!.title, "what is past 512 kB is never read")
    }

    @Test fun fiveSecondsForAll() = runTest {
        val n = FakeNet(
            mapOf("https://a.example/1" to redirect("https://b.example/2"), "https://b.example/2" to html(page)),
            wait = mapOf("https://a.example/1" to 3_000L, "https://b.example/2" to 3_000L),
        )
        assertEquals(SafeFetch.Why.TIMEOUT, (SafeFetch(n, dns).page("https://a.example/1") as SafeFetch.Result.Refused).why)
        val quick = FakeNet(mapOf("https://a.example/1" to html(page)), wait = mapOf("https://a.example/1" to 4_900L))
        assertIs<SafeFetch.Result.Ok>(SafeFetch(quick, dns).page("https://a.example/1"))
    }

    @Test fun picturesBySizeAndFirstBytes() = runBlocking {
        val png = DemoPreviews.picture
        fun one(a: NetAnswer) = SafeFetch(FakeNet(mapOf("https://a.example/p" to a)), dns).picture("https://a.example/p")
        assertIs<SafeFetch.Result.Ok>(one(NetAnswer(200, contentType = "application/octet-stream", body = png)), "the type by the bytes, not the header")
        assertEquals(SafeFetch.Why.TYPE, (one(NetAnswer(200, contentType = "image/png", body = "<svg/>".toByteArray())) as SafeFetch.Result.Refused).why)
        val tooBig = ByteArray(LinkPreviews.PICTURE_IN_MAX + 1).also { png.copyInto(it) }
        assertEquals(SafeFetch.Why.SIZE, (one(NetAnswer(200, contentType = "image/png", body = tooBig)) as SafeFetch.Result.Refused).why)
        assertEquals(SafeFetch.Why.SIZE, (one(NetAnswer(200, contentType = "image/png", contentLength = 6_000_000, body = png)) as SafeFetch.Result.Refused).why)
        val atMost = ByteArray(LinkPreviews.PICTURE_IN_MAX).also { png.copyInto(it) }
        assertIs<SafeFetch.Result.Ok>(one(NetAnswer(200, contentType = "image/png", body = atMost)))
    }

    // ---- the builder ----------------------------------------------------------------------------

    private val og = """<title>T</title><meta property="og:title" content="Street library"><meta property="og:description" content="Drills for all">
        <meta property="og:site_name" content="ToolShare"><meta property="og:image" content="/og.png">"""

    private fun jpeg(size: Int, w: Int = 1200, h: Int = 630) = EncodedPicture(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(size - 3) { 7 }, "image/jpeg", w, h)

    private class Uploads(val origin: String = "https://blossom.belfry.example") {
        val got = ArrayList<Pair<ByteArray, String>>()
        val up: suspend (ByteArray, String) -> String = { b, m -> got.add(b to m); "$origin/" + sha256Hex(b) }
    }

    private fun builder(encoder: PictureEncoder?, up: Uploads, kn: (() -> Pair<String, String>)? = null, pages: Map<String, NetAnswer>? = null): PreviewBuilder {
        val net = FakeNet(pages ?: mapOf("https://a.example/s" to html(og), "https://a.example/og.png" to NetAnswer(200, contentType = "image/png", body = DemoPreviews.picture)))
        return PreviewBuilder(SafeFetch(net, dns), encoder, up.up, "https://blossom.belfry.example", keyNonce = kn)
    }

    @Test fun aPlainPicture() = runBlocking {
        val up = Uploads()
        val enc = jpeg(40_000)
        var given: String? = null
        val p = builder({ b, m -> given = m; assertContentEquals(DemoPreviews.picture, b); enc }, up).build("https://a.example/s", PreviewMode.PLAIN)!!
        assertEquals("image/png", given, "the encoder is told what the first bytes say")
        assertEquals("Street library", p.title); assertEquals("Drills for all", p.description); assertEquals("ToolShare", p.site)
        val pic = assertNotNull(p.picture)
        assertEquals(enc.bytes.toList(), up.got.single().first.toList(), "the re-encoded picture is what is uploaded")
        assertEquals("image/jpeg", up.got.single().second)
        assertEquals(sha256Hex(enc.bytes), pic.x)
        assertNull(pic.key)
        assertEquals(listOf("imeta", "url ${pic.url}", "m image/jpeg", "dim 1200x630", "x ${pic.x}", "purpose preview"), pic.imeta())
    }

    @Test fun anEncryptedPicture() = runBlocking {
        val up = Uploads()
        val enc = jpeg(50_000)
        val p = builder({ _, _ -> enc }, up, { "c3".repeat(32) to "d4".repeat(12) }).build("https://a.example/s", PreviewMode.ENCRYPTED)!!
        val pic = assertNotNull(p.picture)
        val (ct, mime) = up.got.single()
        assertEquals("application/octet-stream", mime, "the ciphertext goes as a file of a direct message does")
        assertContentEquals(DmFiles.seal(enc.bytes, "c3".repeat(32), "d4".repeat(12)).ciphertext, ct)
        assertEquals(sha256Hex(ct), pic.x); assertEquals(sha256Hex(enc.bytes), pic.ox)
        assertEquals("image/jpeg", pic.mime, "m is the plain type")
        assertTrue(pic.imeta().containsAll(listOf("encryption-algorithm aes-gcm", "decryption-key ${"c3".repeat(32)}", "decryption-nonce ${"d4".repeat(12)}", "purpose preview")))
        assertContentEquals(enc.bytes, pic.open(ct), "a reader opens it")
        assertNull(pic.open(ct.copyOf().also { it[5] = (it[5] + 1).toByte() }), "a changed byte fails x")
        assertNull(pic.copy(x = sha256Hex(ct.copyOf().also { it[5] = (it[5] + 1).toByte() })).open(ct.copyOf().also { it[5] = (it[5] + 1).toByte() }), "and GCM")
    }

    @Test fun whatTheEncoderGivesIsCheckedAgain() = runBlocking {
        for ((name, e) in listOf(
            "over 300 kB" to jpeg(LinkPreviews.PICTURE_OUT_MAX + 1),
            "over 1200 px" to jpeg(10_000, 1600, 900),
            "a PNG" to EncodedPicture(DemoPreviews.picture, "image/png", 600, 315),
            "says JPEG, isn't" to EncodedPicture(ByteArray(100), "image/jpeg", 10, 10),
        )) {
            val up = Uploads()
            val b = builder({ _, _ -> e }, up)
            val p = b.build("https://a.example/s", PreviewMode.PLAIN)!!
            assertNull(p.picture, name)
            assertTrue(up.got.isEmpty(), "$name: nothing uploaded")
        }
        // exactly at the limits: kept
        assertNotNull(builder({ _, _ -> jpeg(LinkPreviews.PICTURE_OUT_MAX, 1200, 1200) }, Uploads()).build("https://a.example/s", PreviewMode.PLAIN)!!.picture)
    }

    @Test fun aPictureThatFailsLeavesThePreview() = runBlocking {
        val noPic = mapOf("https://a.example/s" to html(og)) // the picture's address answers 404
        assertNull(builder({ _, _ -> jpeg(1000) }, Uploads(), pages = noPic).build("https://a.example/s", PreviewMode.PLAIN)!!.picture)
        val inside = mapOf("https://a.example/s" to html(og.replace("/og.png", "https://inside.example/og.png")))
        assertNull(builder({ _, _ -> jpeg(1000) }, Uploads(), pages = inside).build("https://a.example/s", PreviewMode.PLAIN)!!.picture, "a private address for the picture")
        assertNull(builder({ _, _ -> null }, Uploads()).build("https://a.example/s", PreviewMode.PLAIN)!!.picture, "can't re-encode")
        val elsewhere = Uploads("https://cdn.elsewhere.example")
        assertNull(builder({ _, _ -> jpeg(1000) }, elsewhere).build("https://a.example/s", PreviewMode.PLAIN)!!.picture, "Blossom answered an address elsewhere")
        val failing: suspend (ByteArray, String) -> String = { _, _ -> throw java.io.IOException("upload failed (500)") }
        val net = FakeNet(mapOf("https://a.example/s" to html(og), "https://a.example/og.png" to NetAnswer(200, body = DemoPreviews.picture)))
        assertNull(PreviewBuilder(SafeFetch(net, dns), { _, _ -> jpeg(1000) }, failing, "https://blossom.belfry.example").build("https://a.example/s", PreviewMode.PLAIN)!!.picture)
    }

    @Test fun nothingUsableIsNoPreview() = runBlocking {
        val pages = mapOf("https://a.example/s" to html("<meta property=\"og:image\" content=\"/og.png\"><body>Hi</body>"), "https://a.example/og.png" to NetAnswer(200, body = DemoPreviews.picture))
        val up = Uploads()
        assertNull(builder({ _, _ -> jpeg(1000) }, up, pages = pages).build("https://a.example/s", PreviewMode.PLAIN))
        assertTrue(up.got.isEmpty(), "no picture is fetched or uploaded for a preview that won't be")
        assertNull(builder(null, Uploads(), pages = mapOf("https://a.example/s" to NetAnswer(500))).build("https://a.example/s", PreviewMode.PLAIN))
    }

    @Test fun theLinkStaysAsWritten() = runBlocking {
        val pages = mapOf("https://a.example/s" to redirect("https://b.example/final"), "https://b.example/final" to html(og), "https://b.example/og.png" to NetAnswer(200, body = DemoPreviews.picture))
        val p = builder({ _, _ -> jpeg(1000) }, Uploads(), pages = pages).build("https://a.example/s", PreviewMode.PLAIN)!!
        assertEquals("https://a.example/s", p.url, "the tag carries the link as written, not where it ended")
        assertNotNull(p.picture, "the relative picture was taken against the final address")
    }
}
