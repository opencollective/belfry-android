package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.model.Message
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.preview.EncodedPicture
import sh.belfry.core.preview.LinkPreview
import sh.belfry.core.preview.LinkPreviews
import sh.belfry.core.preview.NetAnswer
import sh.belfry.core.preview.PictureEncoder
import sh.belfry.core.preview.PreviewMode
import sh.belfry.core.preview.PreviewNet
import sh.belfry.core.preview.PreviewPicture
import sh.belfry.core.store.MemoryStore
import java.net.InetAddress
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SPEC 19.87 in a demo session (the demo plays the outside web and Blossom, nothing leaves the
 * JVM): what the composer builds, and the tags each kind of message carries when it is sent.
 */
class PreviewSendTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun tearDown() { scope.cancel() }

    private val t0 = 1_795_000_000L

    /** Stands in for Android's re-encoding: a fixed small JPEG. */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(4000) { (it % 97).toByte() }
    private val encoder = PictureEncoder { _, _ -> EncodedPicture(jpeg, "image/jpeg", 600, 315) }

    private fun demo(): Session = runBlocking { Onboarding(MemoryVault(), MemoryStore(), OkHttpClient(), scope, { t0 }, ZoneOffset.UTC).demo().also { it.start() } }

    private suspend fun until(s: Session, what: String, cond: () -> Boolean) {
        var k = 0
        while (!cond()) { s.flush(); s.settle(); delay(20); if (++k > 400) fail(what) }
    }

    private suspend fun posted(s: Session, group: String, text: String, kind: Int = 9): Message {
        var m: Message? = null
        until(s, "the message never went out") {
            val w = s.world.value
            m = (if (kind == 9) w.messages(group) else w.allRepliesOf(group)).lastOrNull { it.pubkey == s.me && it.text == text && !w.isPending(it.id) }
            m != null
        }
        return m!!
    }

    private fun sh.belfry.core.model.World.allRepliesOf(group: String): List<Message> =
        messages(group).flatMap { threadReplies(group, it.id) }

    @Test fun aChannelMessageCarriesThePreviewAndItsPlainPicture() = runBlocking {
        val s = demo()
        val p = assertNotNull(s.linkPreviews.build(DemoPreviews.PICTURE, PreviewMode.PLAIN, encoder))
        assertEquals("How a street tool library lends 400 drills a year", p.title)
        assertEquals("ToolShare Journal", p.site)
        val pic = assertNotNull(p.picture)
        assertNull(pic.key, "a channel's picture is in the clear, like its photos")
        assertEquals(sha256Hex(jpeg), pic.x)
        val text = "This is what we could become: ${DemoPreviews.PICTURE}"
        s.postMessage(Demo.GENERAL, text, preview = p.tags())
        val m = posted(s, Demo.GENERAL, text)
        assertEquals(p.tags()[0], m.ev.tags.single { it[0] == "preview" })
        assertEquals(pic.imeta(), m.ev.tags.single { LinkPreviews.isPreviewImeta(it) })
        assertTrue(m.media.isEmpty(), "the preview's picture is not a photo of the message")
        assertEquals(p, s.linkPreviews.read(m.text, m.preview))
        assertContentEquals(jpeg, s.linkPreviews.picture(pic))
        s.stop()
    }

    @Test fun aThreadReply() = runBlocking {
        val s = demo()
        s.postMessage(Demo.GENERAL, "Ideas for the tool library?")
        val root = posted(s, Demo.GENERAL, "Ideas for the tool library?")
        val p = assertNotNull(s.linkPreviews.build(DemoPreviews.NO_PICTURE, PreviewMode.PLAIN, encoder))
        assertNull(p.picture)
        val text = "How to start one: ${DemoPreviews.NO_PICTURE}"
        s.replyInThread(Demo.GENERAL, root.ev, text, preview = p.tags())
        val r = posted(s, Demo.GENERAL, text, kind = 1111)
        assertEquals(1111, r.ev.kind)
        assertEquals(listOf(listOf("preview", DemoPreviews.NO_PICTURE, "How to start a tool library — ToolShare", "A shed, a list of who has what, and one evening a week: the five steps most street libraries took.", "")),
            LinkPreviews.wireTags(r.ev.tags))
        s.stop()
    }

    @Test fun anEditKeepsThePreviewAsItWas() = runBlocking {
        val s = demo()
        val p = s.linkPreviews.build(DemoPreviews.PICTURE, PreviewMode.PLAIN, encoder)!!
        val built = s.linkPreviews.built.get()
        s.postMessage(Demo.GENERAL, "Look ${DemoPreviews.PICTURE}", preview = p.tags())
        val m = posted(s, Demo.GENERAL, "Look ${DemoPreviews.PICTURE}")
        s.editMessage(Demo.GENERAL, m, "Look at this ${DemoPreviews.PICTURE}")
        var edited: Message? = null
        until(s, "the edit never showed") { edited = s.world.value.messages(Demo.GENERAL).firstOrNull { it.id == m.id && it.edited != null }; edited != null }
        val edit = s.world.value.ofKind(1010).last { it.tags.any { t -> t.size >= 2 && t[0] == "e" && t[1] == m.id } }
        assertEquals(p.tags(), LinkPreviews.wireTags(edit.tags), "the 1010 carries the same preview tags")
        assertTrue(p.picture!!.url !in edit.content, "the preview's picture is not written into the text")
        assertEquals("Look at this ${DemoPreviews.PICTURE}", edited!!.text)
        assertEquals(p, s.linkPreviews.read(edited!!.text, edited!!.preview))
        assertEquals(built, s.linkPreviews.built.get(), "nothing is fetched again for an edit")
        s.stop()
    }

    @Test fun previewNoneAndOneAtMost() = runBlocking {
        val s = demo()
        s.postMessage(Demo.GENERAL, "No box ${DemoPreviews.PICTURE}", preview = listOf(LinkPreviews.NO_PREVIEW))
        val m = posted(s, Demo.GENERAL, "No box ${DemoPreviews.PICTURE}")
        assertEquals(listOf(LinkPreviews.NO_PREVIEW), LinkPreviews.wireTags(m.ev.tags))
        assertNull(s.linkPreviews.read(m.text, m.preview))
        val a = LinkPreview("https://a.example/1", "A", "", "").tags()
        val b = LinkPreview("https://b.example/2", "B", "", "", PreviewPicture("https://blob.local/x", "image/jpeg", 1, 1, "0".repeat(64))).tags()
        assertEquals(listOf(a[0], b[1]), LinkPreviewService.onSend(a + b), "one preview tag and one picture, the first of each")
        assertEquals(listOf(LinkPreviews.NO_PREVIEW), LinkPreviewService.onSend(a + listOf(LinkPreviews.NO_PREVIEW)))
        assertTrue(LinkPreviewService.onSend(emptyList()).isEmpty())
        s.stop()
    }

    @Test fun nothingUsableAndAPageThatNeverAnswers() = runBlocking {
        val s = demo()
        assertNull(s.linkPreviews.build(DemoPreviews.NOTHING, PreviewMode.PLAIN, encoder))
        assertNull(s.linkPreviews.build("https://unknown.example/page", PreviewMode.PLAIN, encoder))
        val t = System.currentTimeMillis()
        assertNull(s.linkPreviews.build(DemoPreviews.SLOW, PreviewMode.PLAIN, encoder))
        assertTrue(System.currentTimeMillis() - t in 4_500..9_000, "given up after 5 s")
        s.stop()
    }

    @Test fun whereThePictureIsEncrypted() {
        assertEquals(PreviewMode.ENCRYPTED, LinkPreviewService.modeForDm())
        assertEquals(PreviewMode.ENCRYPTED, LinkPreviewService.modeFor(filesEncrypted = true))
        assertEquals(PreviewMode.PLAIN, LinkPreviewService.modeFor(filesEncrypted = false))
        val s = demo()
        assertEquals(PreviewMode.PLAIN, LinkPreviewService.modeForGroup(s.world.value, Demo.GENERAL))
        // a Private group's messages and files are in the clear on main until format 3 part 7 (the hook's flag)
        assertEquals(PreviewMode.PLAIN, LinkPreviewService.modeForGroup(s.world.value, Demo.POD_PRIVATE))
        assertEquals(PreviewMode.ENCRYPTED, LinkPreviewService.modeForGroup(s.world.value, Demo.POD_PRIVATE, groupFilesEncrypted = true))
        s.stop()
    }

    @Test fun aDirectMessageCarriesItInsideTheRumorWithAnEncryptedPicture() = runBlocking {
        val s = Session(Community.defaults("example.org"), MemoryStore(), LocalSigner.random(), OkHttpClient(), scope, true, { 1_800_000_000L }, null, ZoneOffset.UTC)
        s.start()
        val conv = "n:" + LocalSigner.random().pubkey
        val p = assertNotNull(s.linkPreviews.build(DemoPreviews.PICTURE, PreviewMode.ENCRYPTED, encoder))
        val pic = assertNotNull(p.picture)
        assertNotNull(pic.key); assertNotNull(pic.nonce)
        assertEquals(sha256Hex(jpeg), pic.ox)
        val text = "Did you see this? ${DemoPreviews.PICTURE}"
        s.sendDm(conv, text, preview = p.tags())
        until(s, "the DM never showed") { s.dms.value.thread(conv).any { it.text == text } }
        val d = s.dms.value.thread(conv).single { it.text == text }
        assertEquals(p.tags(), d.preview)
        assertEquals(p, s.linkPreviews.read(d.text, d.preview))
        // a reader without the sender's copy: downloaded (here the demo's storage), x checked, decrypted, ox checked
        val stored = s.store.blob(pic.url.removePrefix(Session.LOCAL_BLOB))!!
        assertEquals(pic.x, sha256Hex(stored))
        assertContentEquals(jpeg, DmFiles.open(listOf(listOf("encryption-algorithm", "aes-gcm"), listOf("decryption-key", pic.key!!), listOf("decryption-nonce", pic.nonce!!), listOf("x", pic.x), listOf("ox", pic.ox!!)), stored))
        s.store.dropBlob("ogpic:" + pic.x)
        assertContentEquals(jpeg, s.linkPreviews.picture(pic))
        // a picture whose stored bytes changed: no picture
        s.store.dropBlob("ogpic:" + pic.x)
        s.store.putBlob(pic.url.removePrefix(Session.LOCAL_BLOB), stored.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertNull(s.linkPreviews.picture(pic))
        // an e-mail never carries one
        s.stop()
    }

    @Test fun offlineNothingIsBuilt() = runBlocking {
        val s = Session(Community.defaults("example.org"), MemoryStore(), LocalSigner.random(), OkHttpClient(), scope, false, { 1_800_000_000L }, null, ZoneOffset.UTC)
        var asked = 0
        s.linkPreviews.net = object : PreviewNet {
            override suspend fun get(url: String, host: String, addresses: List<InetAddress>, accept: String, maxBytes: Int, timeoutMs: Long): NetAnswer? { asked++; return null }
        }
        s.linkPreviews.resolver = DemoPreviews.resolver
        s.setOnline(false)
        assertNull(s.linkPreviews.build(DemoPreviews.PICTURE, PreviewMode.PLAIN, encoder))
        assertEquals(0, asked, "offline: no request at all")
        s.stop()
    }

    @Test fun aReaderNeverFetchesFromElsewhere() = runBlocking {
        val s = Session(Community.defaults("example.org"), MemoryStore(), LocalSigner.random(), OkHttpClient(), scope, false, { 1_800_000_000L }, null, ZoneOffset.UTC)
        val elsewhere = PreviewPicture("https://toolshare.example/og.jpg", "image/jpeg", 1, 1, "0".repeat(64))
        assertNull(s.linkPreviews.picture(elsewhere))
        assertTrue(s.linkPreviews.downloaded.isEmpty(), "never asked")
        val tags = listOf(listOf("preview", "https://a.example/x", "T", "", ""), elsewhere.imeta())
        assertNull(s.linkPreviews.read("See https://a.example/x", tags)!!.picture, "and not even offered for display")
        s.stop()
    }
}
