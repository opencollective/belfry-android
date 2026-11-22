package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import sh.belfry.core.FakeRelay
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.dm.Report
import sh.belfry.core.model.World
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.store.MemoryStore
import java.io.IOException
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** docs/one-shape.md 4 on the wire: Reply all's kind 1301, and `answered` once the bridge says delivered. */
class MailReplyAllTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val host = chain.request().url.host
        if (host != "localhost" && host != "127.0.0.1" && host != "::1" && host != "[::1]") throw IOException("no network in tests")
        chain.proceed(chain.request())
    }).build()
    private val fakes = ArrayList<FakeRelay>()
    private val sessions = ArrayList<Session>()

    private fun fake(): FakeRelay = FakeRelay(false).also { it.expect(6); fakes.add(it) }

    @AfterTest
    fun tearDown() {
        sessions.forEach { it.stop() }
        scope.cancel()
        fakes.forEach { try { it.server.shutdown() } catch (e: Exception) { } }
    }

    private suspend fun until(what: String, timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        val ok = withTimeoutOrNull(timeoutMs) { while (!cond()) delay(20); true }
        if (ok != true) fail("timed out waiting for: $what")
    }

    @Test
    fun replyAllRecipientsThenAnsweredAfterDelivery() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val me = LocalSigner.random()
        val bridge = LocalSigner.random()
        val store = MemoryStore()
        store.set(Session.K_BRIDGE, bridge.pubkey)
        val c = Community.defaults("example.org").copy(groupsRelay = g.url, mainRelay = m.url, blossom = "http://localhost:9")
        val s = Session(c, store, me, http, scope, false, { System.currentTimeMillis() / 1000 }, null, ZoneOffset.UTC).also { sessions.add(it) }
        s.start()

        val root = "f".repeat(64)
        val card = "e".repeat(64)
        val id = s.sendAsChannel(
            "hello", "lou@example.com", "Re: Park cleaning", "Yes, 12 rakes.", "<p2@example.com>", card, listOf("<p1@example.com>", "<p2@example.com>"),
            cc = listOf("dev@example.com", "park@example.org", "Lou@example.com"), draftId = "d".repeat(64), threadId = root,
        )
        until("the wrap to the bridge is out") { m.published.any { it.kind == 1059 && it.tag("p") == bridge.pubkey } }
        val rumor = GiftWrap.unwrap(m.published.first { it.kind == 1059 && it.tag("p") == bridge.pubkey }, bridge)!!.rumor
        assertEquals(1301, rumor.kind)
        assertEquals(id, rumor.tag("email-id"))
        // one rcpt-to per person, the sender first, no duplicates
        assertEquals(listOf("lou@example.com", "dev@example.com", "park@example.org"), rumor.tags.filter { it[0] == "rcpt-to" }.map { it[1] })
        assertEquals("d".repeat(64), rumor.tag("draft"))
        assertEquals(card, rumor.tag("e"))
        assertTrue(rumor.content.contains("\r\nTo: <lou@example.com>\r\n"), rumor.content.take(600))
        assertTrue(rumor.content.contains("\r\nCc: <dev@example.com>, <park@example.org>\r\n"), rumor.content.take(600))
        assertTrue(rumor.content.contains("In-Reply-To: <p2@example.com>"))
        assertTrue(rumor.content.contains("References: <p1@example.com> <p2@example.com>"))

        // not delivered yet: no label
        delay(300)
        assertTrue(g.published.none { it.kind == 1985 })

        // the bridge reports delivered: this device labels the thread's first card answered, once
        val eph = LocalSigner.random()
        val body = buildJsonObject { put("status", JsonPrimitive("delivered")) }.toString()
        m.stored.add(bridge.signNow(EventTemplate(7679, eph.nip44EncryptNow(me.pubkey, body), listOf(listOf("r", id), listOf("ephemeral-pubkey", eph.pubkey), listOf("p", me.pubkey)), System.currentTimeMillis() / 1000)))
        s.checkReports()
        assertEquals(Report.DELIVERED, s.dms.value.report(id)?.status)
        until("the answered label is out") { g.published.any { it.kind == 1985 } }
        val label = g.published.single { it.kind == 1985 }
        assertEquals(root, label.tag("e"))
        assertEquals("hello", label.tag("h"))
        assertTrue(label.tags.any { it.size >= 3 && it[0] == "l" && it[1] == "answered" && it[2] == World.INBOX_NS }, label.tags.toString())
        s.checkReports()
        delay(500)
        assertEquals(1, g.published.count { it.kind == 1985 })
    }
}
