package sh.belfry.core.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import sh.belfry.core.Vectors
import sh.belfry.core.crypto.B64
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.net.BelfryApi
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.preview.PreviewLoader.Known
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Previews of outside pages (SPEC 19.80; vectors/link-previews.json; docs/link-previews.md). */
class LinkPreviewsTest {
    private val v = Vectors.load("link-previews.json")
    private val domain = v["domain"]!!.jsonPrimitive.content
    private val files = v["files"]!!.jsonPrimitive.content
    private val origin = LinkPreviews.belfryOrigin(domain)
    private fun j(s: String) = parseJsonOrNull(s) as JsonObject

    @Test fun constants() {
        val c = v["constants"]!!.jsonObject
        assertEquals(LinkPreviews.FOUND_SECONDS, c["found_seconds"]!!.jsonPrimitive.long)
        assertEquals(LinkPreviews.NONE_SECONDS, c["none_seconds"]!!.jsonPrimitive.long)
        assertEquals(LinkPreviews.NO_PREVIEW, c["no_preview_tag"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("https://belfry.belfry.example", origin)
    }

    @Test fun whichLink() {
        for (c in v["links"]!!.jsonArray.map { it.jsonObject }) {
            val name = c["name"]!!.jsonPrimitive.content
            val text = c["text"]!!.jsonPrimitive.content
            val tags = c["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
            val want = c["link"]!!.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            assertEquals(want, LinkPreviews.outsideLink(text, tags, domain, files), name)
            assertEquals(want, LinkPreviews.linkFor(PreviewPlace.CHANNEL, text, tags, domain, files), name)
            // a private message, Notes, a sealed conversation: never, whatever the text
            assertNull(LinkPreviews.linkFor(PreviewPlace.PRIVATE, text, tags, domain, files), name)
        }
    }

    @Test fun skippedLinksAndNoDomain() {
        assertEquals("https://b.example/2", LinkPreviews.outsideLink("https://a.example/1 https://b.example/2", emptyList(), domain, skip = listOf("https://a.example/1")))
        // no domain known: still only https, still not files
        assertEquals("https://a.example/1", LinkPreviews.outsideLink("https://a.example/1", emptyList(), ""))
        assertTrue(LinkPreviews.noPreview(listOf(listOf("preview", "none"))))
        assertFalse(LinkPreviews.noPreview(listOf(listOf("preview"), listOf("t", "none"))))
    }

    @Test fun pictureOnlyFromTheBelfry() {
        for (c in v["images"]!!.jsonArray.map { it.jsonObject }) {
            val image = c["image"]!!.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val shown = c["shown"]!!.jsonPrimitive.content == "true"
            assertEquals(shown, LinkPreviews.imageFrom(image, origin) != null, c["name"]!!.jsonPrimitive.content)
        }
        assertNull(LinkPreviews.imageFrom("https://belfry.belfry.example/x.png", ""))
    }

    @Test fun theAnswer() {
        val url = "https://www.toolshare.example/stories/street-library"
        val p = LinkPreviews.parse(url, j("""{"url":"https://toolshare.example/after","title":"  Bread &amp; Butter\n ","description":"Fresh  bread","site_name":"The Bakery",
            "image":"https://belfry.belfry.example/api/v1/link-preview/image/${"a".repeat(64)}","image_width":1200,"image_height":630}"""), origin)!!
        // the link as written is what opens; the host is shown without www.
        assertEquals(url, p.url)
        assertEquals("toolshare.example", p.host)
        assertEquals("Bread &amp; Butter", p.title) // plain text as given: never decoded or rendered as HTML here
        assertEquals("Fresh bread", p.description)
        assertEquals("The Bakery · toolshare.example", p.siteLine)
        assertTrue(p.hasPicture)
        assertEquals(1200, p.imageWidth)
        assertEquals(1200f / 630f, p.ratio)
        // a picture anywhere else: the preview without it, and no size kept
        val q = LinkPreviews.parse(url, j("""{"title":"T","description":null,"site_name":null,"image":"https://toolshare.example/og.png","image_width":1200,"image_height":630}"""), origin)!!
        assertNull(q.image)
        assertNull(q.imageWidth)
        assertFalse(q.hasPicture)
        assertEquals("toolshare.example", q.siteLine)
        assertEquals("", q.description)
        // a site named like its host is said once
        assertEquals("toolshare.example", LinkPreviews.parse(url, j("""{"title":"T","site_name":"toolshare.example"}"""), origin)!!.siteLine)
        // neither a title nor a description: nothing
        assertNull(LinkPreviews.parse(url, j("""{"title":null,"description":"  ","site_name":"X","image":null}"""), origin))
        assertNull(LinkPreviews.parse(url, null, origin))
        // limits
        val long = LinkPreviews.parse(url, j("""{"title":"${"t".repeat(500)}","description":"${"d".repeat(500)}","site_name":"${"s".repeat(500)}"}"""), origin)!!
        assertEquals(200, long.title.length)
        assertEquals(300, long.description.length)
        assertEquals(100, long.site.length)
        // sizes that are not sizes
        assertNull(LinkPreviews.parse(url, j("""{"title":"T","image":"https://belfry.belfry.example/i","image_width":0,"image_height":630}"""), origin)!!.imageWidth)
    }

    @Test fun thePicturesBox() {
        assertEquals(2f, LinkPreviews.ratio(null, null))
        assertEquals(2f, LinkPreviews.ratio(1200, null))
        assertEquals(1.5f, LinkPreviews.ratio(800, 800))
        assertEquals(2.4f, LinkPreviews.ratio(3000, 500))
        assertEquals(1.6f, LinkPreviews.ratio(1600, 1000))
    }

    @Test fun whereItIsAsked() {
        val api = "https://belfry.belfry.example/api/v1"
        assertEquals(
            "https://belfry.belfry.example/api/v1/link-preview?url=https%3A%2F%2Fa.example%2Fx%3Fq%3D1%262%20%C3%A9%23f",
            LinkPreviews.endpoint(api, domain, "https://a.example/x?q=1&2 é#f"),
        )
        // an API that is not on https://belfry.<domain>: no previews at all
        assertNull(LinkPreviews.endpoint("https://api.other.example/api/v1", domain, "https://a.example/x"))
        assertNull(LinkPreviews.endpoint("http://belfry.belfry.example/api/v1", domain, "https://a.example/x"))
        assertNull(LinkPreviews.endpoint("https://belfry.example/api/v1", domain, "https://a.example/x"))
        assertNull(LinkPreviews.endpoint(api, "", "https://a.example/x"))
        assertNotNull(LinkPreviews.endpoint(Community.defaults("belfry.sh").apiBase, "belfry.sh", "https://a.example/x"))
    }

    @Test fun howLongAnAnswerIsKept() {
        assertTrue(LinkPreviews.fresh(true, 1000, 1000 + 86_399))
        assertFalse(LinkPreviews.fresh(true, 1000, 1000 + 86_400))
        assertTrue(LinkPreviews.fresh(false, 1000, 1000 + 3_599))
        assertFalse(LinkPreviews.fresh(false, 1000, 1000 + 3_600))
        // the clock went back: asked again rather than kept for ever
        assertFalse(LinkPreviews.fresh(true, 1000, 999))
    }

    private val found = LinkPreview("https://a.example/x", "T", "D", "S", "a.example", "https://belfry.belfry.example/i", 1200, 630)

    @Test fun theKeptAnswers() {
        val c = PreviewCache()
        c.put(found.url, found, 1000)
        c.put("https://b.example/none", null, 1000)
        assertEquals(found, c.get(found.url, 2000)!!.preview)
        assertNull(c.get("https://b.example/none", 2000)!!.preview)
        assertNull(c.get("https://b.example/none", 1000 + 3600))
        assertNull(c.get(found.url, 1000 + 86_400))
        assertNull(c.get("https://c.example/never", 1000))
        // the stored copy, read back
        val back = PreviewCache.parse(c.toJsonString(), origin)
        assertEquals(found, back.get(found.url, 2000)!!.preview)
        assertNull(back.get("https://b.example/none", 2000)!!.preview)
        // a stored picture that is not this belfry's is dropped (another origin now, or a tampered value)
        val other = PreviewCache.parse(c.toJsonString(), "https://belfry.other.example").get(found.url, 2000)!!.preview!!
        assertNull(other.image)
        assertNull(other.imageWidth)
        // rubbish is an empty cache
        assertEquals(0, PreviewCache.parse("not json", origin).size)
        assertEquals(0, PreviewCache.parse(null, origin).size)
        // never more than KEPT
        val big = PreviewCache()
        for (i in 0 until LinkPreviews.KEPT + 50) big.put("https://a.example/$i", null, 5000L + i)
        assertEquals(LinkPreviews.KEPT, big.size)
        assertNull(big.get("https://a.example/0", 5300))
        assertNotNull(big.get("https://a.example/${LinkPreviews.KEPT + 49}", 5300))
    }

    // ---- asking the belfry, gently ----

    private class Belfry(val origin: String) {
        var now = 10_000L
        var online = true
        var stored: String? = null
        val asked = ArrayList<String>()
        var answer: suspend (String) -> Pair<Int, JsonObject?> = { 404 to null }
        fun loader() = PreviewLoader(origin, { now }, { online }, { stored }, { stored = it }) { url -> synchronized(asked) { asked.add(url) }; answer(url) }
    }

    private val ok = j("""{"url":"https://a.example/x","title":"T","description":"D","site_name":"S","image":null}""")

    @Test fun oneRequestPerLinkAndTheAnswerIsKept() = runBlocking<Unit> {
        val b = Belfry(origin)
        b.answer = { 200 to ok }
        val l = b.loader()
        assertEquals(Known.Unknown, l.known("https://a.example/x"))
        assertEquals("T", l.get("https://a.example/x")!!.title)
        assertEquals("T", l.get("https://a.example/x")!!.title)
        assertEquals(1, b.asked.size)
        assertTrue(l.known("https://a.example/x") is Known.Found)
        // kept on the device: a new loader (the app started again) asks nothing
        val again = b.loader()
        assertTrue(again.known("https://a.example/x") is Known.Found)
        assertEquals("T", again.get("https://a.example/x")!!.title)
        assertEquals(1, b.asked.size)
        // a day later it is asked again
        b.now += LinkPreviews.FOUND_SECONDS
        assertEquals(Known.Unknown, again.known("https://a.example/x"))
        again.get("https://a.example/x")
        assertEquals(2, b.asked.size)
    }

    @Test fun nothingToShowIsKeptAnHour() = runBlocking<Unit> {
        val b = Belfry(origin)
        val l = b.loader()
        for (status in listOf(404, 422)) {
            val url = "https://a.example/$status"
            b.answer = { status to j("""{"error":"none"}""") }
            assertNull(l.get(url))
            assertNull(l.get(url))
            assertEquals(Known.None, l.known(url))
            assertEquals(1, b.asked.count { it == url })
        }
        // a 200 with no title and no description is nothing too
        b.answer = { 200 to j("""{"title":null,"description":null}""") }
        assertNull(l.get("https://a.example/empty"))
        assertEquals(Known.None, l.known("https://a.example/empty"))
        b.now += LinkPreviews.NONE_SECONDS
        assertEquals(Known.Unknown, l.known("https://a.example/404"))
        l.get("https://a.example/404")
        assertEquals(2, b.asked.count { it == "https://a.example/404" })
    }

    @Test fun twoRowsWithTheSameLinkAskOnce() = runBlocking<Unit> {
        val b = Belfry(origin)
        val gate = CompletableDeferred<Unit>()
        b.answer = { gate.await(); 200 to ok }
        val l = b.loader()
        coroutineScope {
            val rows = (1..5).map { async { l.get("https://a.example/x") } }
            delay(100)
            gate.complete(Unit)
            assertTrue(rows.awaitAll().all { it?.title == "T" })
        }
        assertEquals(1, b.asked.size)
    }

    @Test fun neverMoreThanAFewAtOnce() = runBlocking<Unit> {
        val b = Belfry(origin)
        val running = AtomicInteger()
        val most = AtomicInteger()
        b.answer = {
            val n = running.incrementAndGet()
            most.updateAndGet { m -> maxOf(m, n) }
            delay(30)
            running.decrementAndGet()
            404 to null
        }
        val l = b.loader()
        coroutineScope { (1..12).map { i -> async(kotlinx.coroutines.Dispatchers.Default) { l.get("https://a.example/$i") } }.awaitAll() }
        assertEquals(12, b.asked.size)
        assertTrue(most.get() <= LinkPreviews.AT_ONCE, "at once: ${most.get()}")
    }

    @Test fun tooManyBacksOffAndIsNotRetriedInALoop() = runBlocking<Unit> {
        for (status in listOf(429, 503)) {
            val b = Belfry(origin)
            b.answer = { status to j("""{"error":"too_many"}""") }
            val l = b.loader()
            assertNull(l.get("https://a.example/1"))
            assertEquals(1, b.asked.size)
            // nothing at all is asked while it backs off: not this link, not another
            repeat(20) { assertNull(l.get("https://a.example/1")); assertNull(l.get("https://a.example/2")) }
            assertEquals(Known.None, l.known("https://a.example/2"))
            assertEquals(1, b.asked.size)
            assertEquals(b.now + LinkPreviews.BACKOFF_SECONDS, l.paused)
            // not kept as an answer
            assertNull(b.stored)
            // after the wait one more is asked; refused again, the wait doubles
            b.now += LinkPreviews.BACKOFF_SECONDS
            assertEquals(Known.Unknown, l.known("https://a.example/2"))
            assertNull(l.get("https://a.example/2"))
            assertEquals(2, b.asked.size)
            assertEquals(b.now + 2 * LinkPreviews.BACKOFF_SECONDS, l.paused)
            b.now += LinkPreviews.BACKOFF_SECONDS
            assertNull(l.get("https://a.example/1"))
            assertEquals(2, b.asked.size)
            // answered at last: the wait is short again
            b.now += LinkPreviews.BACKOFF_SECONDS
            b.answer = { 200 to ok }
            assertNotNull(l.get("https://a.example/1"))
            assertEquals(3, b.asked.size)
        }
    }

    @Test fun thoseWaitingTheirTurnDoNotAskAfterA429() = runBlocking<Unit> {
        val b = Belfry(origin)
        b.answer = { delay(50); 429 to null }
        val l = b.loader()
        coroutineScope { (1..10).map { i -> async(kotlinx.coroutines.Dispatchers.Default) { l.get("https://a.example/$i") } }.awaitAll() }
        // only those already on their way (at most AT_ONCE) reached the belfry
        assertTrue(b.asked.size <= LinkPreviews.AT_ONCE, "asked ${b.asked.size}")
    }

    @Test fun nothingIsAskedOffline() = runBlocking<Unit> {
        val b = Belfry(origin)
        b.answer = { 200 to ok }
        b.online = false
        val l = b.loader()
        assertEquals(Known.None, l.known("https://a.example/x"))
        assertNull(l.get("https://a.example/x"))
        assertEquals(0, b.asked.size)
        // what was kept still shows offline
        b.online = true
        assertNotNull(l.get("https://a.example/x"))
        b.online = false
        assertTrue(l.known("https://a.example/x") is Known.Found)
        assertNotNull(l.get("https://a.example/x"))
        assertEquals(1, b.asked.size)
    }

    @Test fun aFailureShowsNothingAndWaits() = runBlocking<Unit> {
        val b = Belfry(origin)
        val l = b.loader()
        // no connection to the belfry
        b.answer = { throw java.io.IOException("down") }
        assertNull(l.get("https://a.example/1"))
        assertNull(l.get("https://a.example/1"))
        assertEquals(1, b.asked.size)
        // another link may still be asked
        b.answer = { 500 to null }
        assertNull(l.get("https://a.example/2"))
        assertEquals(2, b.asked.size)
        // no pass: nothing more is asked for a while
        b.answer = { 403 to j("""{"error":"no_pass"}""") }
        assertNull(l.get("https://a.example/3"))
        assertNull(l.get("https://a.example/4"))
        assertEquals(3, b.asked.size)
        assertNull(b.stored)
        b.now += LinkPreviews.FAILED_SECONDS
        b.answer = { 200 to ok }
        assertNotNull(l.get("https://a.example/1"))
        assertEquals(4, b.asked.size)
    }

    @Test fun aPictureFromElsewhereIsNeverKept() = runBlocking<Unit> {
        val b = Belfry(origin)
        b.answer = { 200 to j("""{"title":"T","image":"https://tracker.example/pixel.png","image_width":1,"image_height":1}""") }
        val l = b.loader()
        val p = l.get("https://a.example/x")!!
        assertNull(p.image)
        assertFalse(b.stored!!.contains("tracker.example"))
    }

    /** The request itself: a GET signed with NIP-98 whose `u` is the whole address, query included; a redirect is not followed. */
    @Test fun theSignedRequest() = runBlocking<Unit> {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(ok.toString()))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere").toString()))
            val base = server.url("/api/v1").toString().trimEnd('/')
            val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            val api = BelfryApi(Community.defaults("example.org").copy(apiBase = base), http)
            val signer = LocalSigner(sha256Hex("link-previews test"))
            val url = base + "/link-preview?url=" + java.net.URLEncoder.encode("https://a.example/x?q=1&r=2", "UTF-8")
            val (status, body) = api.signedFetch(signer, url)
            assertEquals(200, status)
            assertEquals("T", LinkPreviews.parse("https://a.example/x?q=1&r=2", body, origin)!!.title)
            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("https://a.example/x?q=1&r=2", req.requestUrl!!.queryParameter("url"))
            val ev = Event.fromJsonString(String(B64.decode(req.getHeader("Authorization")!!.removePrefix("Nostr ")), Charsets.UTF_8))!!
            assertTrue(ev.isValid())
            assertEquals(27235, ev.kind)
            assertEquals("GET", ev.tag("method"))
            assertEquals(url, ev.tag("u"))
            assertTrue(ev.tag("u")!!.contains("?url=https%3A%2F%2Fa.example"))
            // a redirect is an answer (a failure), never followed
            val (redirected, _) = api.signedFetch(signer, base + "/link-preview?url=https%3A%2F%2Fb.example%2F")
            assertEquals(302, redirected)
            server.takeRequest()
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
