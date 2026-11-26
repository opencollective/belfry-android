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
import sh.belfry.core.FakeRelay
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

/**
 * SPEC 19.91 / 19.92 (web fa38d50, 9640d99, 8343fd6): every version of an event on screen is asked by `#d`, any author,
 * no `since`, on a signed-in socket; the live catch-up only asks for what is newer than this device's last sync, so a
 * co-host's version dated before it never came (Xavier, OSV chaosscon2027, 25 Nov).
 */
class EventVersionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Only the local fakes may be reached: a test must never touch a real belfry. */
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val host = chain.request().url.host
        if (host != "localhost" && host != "127.0.0.1" && host != "::1" && host != "[::1]") throw IOException("no network in tests")
        chain.proceed(chain.request())
    }).build()
    private val fakes = ArrayList<FakeRelay>()
    private val sessions = ArrayList<Session>()

    @AfterTest
    fun tearDown() {
        sessions.forEach { it.stop() }
        scope.cancel()
        fakes.forEach { try { it.server.shutdown() } catch (e: Exception) { /* sockets still open */ } }
    }

    private suspend fun until(what: String, timeoutMs: Long = 15_000, cond: suspend () -> Boolean) {
        val ok = withTimeoutOrNull(timeoutMs) { while (!cond()) delay(20); true }
        if (ok != true) fail("timed out waiting for: $what")
    }

    @Test
    fun aCoHostsOlderVersionComesByItsD() = runBlocking<Unit> {
        val g = FakeRelay(requireAuth = true).also { it.expect(6); fakes.add(it) }
        // the main relay: filters like a real relay (since, #d, authors), and its challenge comes late (rnostr)
        val m = FakeRelay(requireAuth = true, strict = true, authDelayMs = 400).also { it.expect(6); fakes.add(it) }
        val me = LocalSigner.random()
        val alice = LocalSigner.random()
        val bob = LocalSigner.random()
        val relayKey = LocalSigner.random()
        val d = "2027-abc123"
        val gid = "ev-$d"
        val t0 = 1_800_000_000L
        g.stored.add(alice.signNow(EventTemplate(9007, "", listOf(listOf("h", gid)), t0)))
        g.stored.add(relayKey.signNow(EventTemplate(39000, "", listOf(listOf("d", gid), listOf("name", "Garden day")), t0)))
        g.stored.add(relayKey.signNow(EventTemplate(39001, "", listOf(listOf("d", gid), listOf("p", alice.pubkey, "admin"), listOf("p", bob.pubkey, "admin")), t0)))
        fun version(by: LocalSigner, title: String, at: Long) = by.signNow(EventTemplate(
            31923, "", listOf(listOf("d", d), listOf("title", title), listOf("start", (t0 + 86_400).toString()), listOf("h", gid), listOf("visibility", "members")), at,
        ))
        val first = version(alice, "Garden day", t0)
        val coHost = version(bob, "Garden day, new date", t0 + 50)
        val store = MemoryStore()
        // this device synced long after the co-host's version was made: its catch-up starts after it
        store.addEvents(listOf(first, alice.signNow(EventTemplate(0, "{\"name\":\"Alice\"}", emptyList(), t0 + 100_000))))
        m.stored.add(first)
        m.stored.add(coHost)
        val c = Community.defaults("example.org").copy(groupsRelay = g.url, mainRelay = m.url, blossom = "http://localhost:9")
        val s = Session(c, store, me, http, scope, false, { System.currentTimeMillis() / 1000 }, null, ZoneOffset.UTC).also { sessions.add(it) }
        s.start()
        until("the event group's admins") { s.world.value.group(gid)?.admins?.contains(bob.pubkey) == true }
        delay(1_500)
        assertEquals("Garden day", s.world.value.calendar.single().title, "the catch-up never brings the older co-host version")
        s.askVersions(listOf(d, "not-an-event-id"))
        until("the co-host's version") { s.world.value.calendar.singleOrNull()?.title == "Garden day, new date" }
        assertEquals(bob.pubkey, s.world.value.calendar.single().ev.pubkey)
        assertEquals("31923:${alice.pubkey}:$d", s.world.value.calendar.single().coord, "the coordinate stays the creator's")
        val asked = m.reqTexts.filter { it.contains("\"#d\":[\"$d\"]") }
        assertTrue(asked.isNotEmpty() && asked.none { it.contains("\"authors\"") || it.contains("\"since\"") }, "by #d, any author, no since: $asked")
        assertEquals(listOf(listOf(d)), s.versionAsks.toList(), "asked once, only real event ids")
        // asked again: nothing new goes out (once per session)
        s.askVersions(listOf(d))
        delay(300)
        assertEquals(1, s.versionAsks.size)
    }
}
