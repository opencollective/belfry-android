package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import sh.belfry.core.FakeRelay
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.sealed.Anchor
import sh.belfry.core.sealed.KeyChain
import sh.belfry.core.sealed.Roster
import sh.belfry.core.sealed.Sealing
import sh.belfry.core.sealed.SealedEngine
import sh.belfry.core.store.MemoryStore
import java.io.IOException
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The sealed side wired into a Session (the lead's re-review N2–N4), on the WebSocket fake relay
 * (AUTH required) and a fake /me/sessions: two devices of one account, a correspondent.
 */
class SealedSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val host = chain.request().url.host
        if (host != "localhost" && host != "127.0.0.1" && host != "::1" && host != "[::1]") throw IOException("no network in tests")
        chain.proceed(chain.request())
    }).build()
    private val g = FakeRelay().also { it.expect(8) }
    private val m = FakeRelay(requireAuth = true).also { it.expect(8) }
    private val live = CopyOnWriteArrayList<String>() // client keys with a live chat session
    private val api = MockWebServer().also { srv ->
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                if (path.endsWith("/me/sessions")) {
                    val rows = live.joinToString(",") { k ->
                        """{"application_name":"Belfry for Android","device":"Phone","bunker_pubkey":"${"cc".repeat(32)}","created_at":"2026-11-21T00:00:00Z","last_activity":"2026-11-21T00:00:00Z","client_pubkey":"$k","chat":true}"""
                    }
                    return MockResponse().setBody("""{"sessions":[$rows]}""").setHeader("Content-Type", "application/json")
                }
                return MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        srv.start()
    }
    private val sessions = ArrayList<Session>()
    /** Moves the sessions' clock forward (a device asks at most once a minute). */
    @Volatile private var skew = 0L

    private val community: Community get() = Community.defaults("example.org").copy(
        groupsRelay = g.url, mainRelay = m.url, apiBase = api.url("/api").toString().trimEnd('/'),
    )

    private fun session(account: LocalSigner, device: LocalSigner): Session {
        live.add(device.pubkey)
        val s = Session(community, MemoryStore(), account, http, scope, false, { System.currentTimeMillis() / 1000 + skew }, null, ZoneOffset.UTC)
        s.keyVault = MemoryVault()
        s.deviceKeyOverride = device
        s.sealedPollMs = 300
        sessions.add(s)
        s.start()
        return s
    }

    @AfterTest
    fun tearDown() {
        sessions.forEach { it.stop() }
        scope.cancel()
        try { g.server.shutdown() } catch (e: Exception) { }
        try { m.server.shutdown() } catch (e: Exception) { }
        try { api.shutdown() } catch (e: Exception) { }
    }

    private suspend fun until(what: String, ms: Long = 15_000, cond: suspend () -> Boolean) {
        if (withTimeoutOrNull(ms) { while (!cond()) delay(50); true } != true) fail("timed out waiting for: $what")
    }

    private val alice = LocalSigner.random()
    private val phone = LocalSigner.random()
    private val laptop = LocalSigner.random()

    private fun requestsBy(device: LocalSigner) = m.published.count { it.kind == Sealing.REQUEST && it.pubkey == device.pubkey }

    /** N2: a waiting device publishes one request per start, not one at start and another at AUTH. */
    @Test fun oneRequestPerStart() = runBlocking<Unit> {
        val a = session(alice, phone)
        until("the phone makes the first roster") { a.sealed?.mine == SealedEngine.Mine.IN_HEAD }
        val b = session(alice, laptop)
        until("the laptop waits") { b.sealed?.mine == SealedEngine.Mine.WAITING }
        delay(2_000) // several polls and the post-AUTH steps
        assertEquals(1, requestsBy(laptop), "one 8562, not two")
        // a reconnect, past the once-a-minute limit: exactly one more, then none
        skew += 120
        m.dropAll()
        until("asked again after the reconnect", 20_000) { requestsBy(laptop) == 2 }
        delay(2_000)
        assertEquals(2, requestsBy(laptop), "one per connection")
    }

    /** N4: an IN_HEAD device that misses a rotation's hand-over asks for the keys. */
    @Test fun inHeadWithoutTheKeyAsks() = runBlocking<Unit> {
        val a = session(alice, phone)
        until("the phone makes the first roster") { a.sealed?.mine == SealedEngine.Mine.IN_HEAD }
        val b = session(alice, laptop)
        until("the laptop waits") { b.sealed?.mine == SealedEngine.Mine.WAITING }
        a.sealed!!.allow(laptop.pubkey)
        for (w in m.published.filter { it.kind == 1059 && it.tag("p") == laptop.pubkey }) b.sealed!!.onDeviceWrap(w)
        until("the laptop is in the head") { b.sealed?.mine == SealedEngine.Mine.IN_HEAD }
        val before = requestsBy(laptop)
        // a third device comes and goes: a new letterbox, whose hand-over the laptop never gets
        val tab = LocalSigner.random(); live.add(tab.pubkey)
        a.sealed!!.allow(tab.pubkey)
        // the rotation's hand-over to the laptop is lost: dropped the moment it is published, never served
        m.onPublished = { _, ev -> if (ev.kind == 1059 && ev.tag("p") == laptop.pubkey) m.stored.remove(ev) }
        a.sealed!!.remove(tab.pubkey, revoke = false)
        skew += 120 // past the once-a-minute limit
        until("the laptop asks again", 20_000) { requestsBy(laptop) > before }
        assertTrue(b.sealed!!.inHeadWithoutKey)
    }

    /**
     * N3: a correspondent becomes part of a sealed conversation through a real sealed message;
     * the live subscription is sent again and names them; their new roster arriving live moves the engine.
     */
    @Test fun liveRosterRefreshesThePerson() = runBlocking<Unit> {
        val a = session(alice, phone)
        until("the phone makes the first roster") { a.sealed?.mine == SealedEngine.Mine.IN_HEAD }
        until("Alice's chain is ready") { a.sealed?.chainOf(alice.pubkey)?.ready == true }
        // Bob's ready chain on the relay (his device, his account key)
        val bob = LocalSigner.random(); val bobPhone = LocalSigner.random(); val bobTab = LocalSigner.random()
        val (lb, _) = KeyChain.newLetterbox()
        val t = System.currentTimeMillis() / 1000 + skew
        val r1 = bobPhone.signNow(Roster.template(bob.pubkey, 1, null, lb, listOf(bobPhone.pubkey), true, t))
        val a1 = bob.signNow(Anchor.template(r1.id, r1.id, t))
        m.stored.add(r1); m.stored.add(a1)
        // Bob writes to Alice, sealed to her head: received live, the conversation becomes sealed
        val aliceLb = a.sealed!!.chainOf(alice.pubkey)!!.head.lb
        val rumor = sh.belfry.core.dm.GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "hello", t)
        val wrap = Sealing.seal(rumor, bobPhone, alice.pubkey, aliceLb, t)
        m.stored.add(wrap)
        for (ws in m.sockets) m.send(ws, "EVENT", "main", wrap)
        until("the conversation is sealed") { a.sealed!!.sealedSince.containsKey("n:" + bob.pubkey) }
        until("the subscription names Bob", 10_000) { m.reqTexts.any { it.contains("\"#p\"") && it.contains(bob.pubkey) && it.contains("8560") } }
        val r2 = bobPhone.signNow(Roster.template(bob.pubkey, 2, r1.id, lb, listOf(bobPhone.pubkey, bobTab.pubkey), true, t + 1))
        val a2 = bob.signNow(Anchor.template(r1.id, r2.id, t + 1))
        m.stored.add(r2); m.stored.add(a2)
        for (ws in m.sockets) { m.send(ws, "EVENT", "main", r2); m.send(ws, "EVENT", "main", a2) }
        until("Alice's phone follows Bob's new head") { a.sealed?.chainOf(bob.pubkey)?.head?.id == r2.id }
    }
}
