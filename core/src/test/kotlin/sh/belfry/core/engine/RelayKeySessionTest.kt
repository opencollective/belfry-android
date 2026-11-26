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
import sh.belfry.core.net.RelayInfo
import sh.belfry.core.net.RelayKeyCache
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.store.MemoryStore
import java.io.IOException
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** SPEC 19.94: the session reads the groups relay's NIP-11 `self` in the background, keeps it, and judges the metadata by it. */
class RelayKeySessionTest {
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
    fun theRelaysKeyIsReadKeptAndJudgesTheMetadata() = runBlocking<Unit> {
        val g = FakeRelay(requireAuth = true).also { it.expect(6); fakes.add(it) }
        val m = FakeRelay(requireAuth = true).also { it.expect(6); fakes.add(it) }
        val me = LocalSigner.random()
        val relayKey = LocalSigner.random()
        val mallory = LocalSigner.random()
        val gid = "pod-garden-00"
        val t0 = 1_800_000_000L
        val store = MemoryStore()
        store.addEvents(listOf(
            relayKey.signNow(EventTemplate(39000, "", listOf(listOf("d", gid), listOf("name", "Garden")), t0)),
            relayKey.signNow(EventTemplate(39002, "", listOf(listOf("d", gid), listOf("p", me.pubkey)), t0)),
            mallory.signNow(EventTemplate(39000, "", listOf(listOf("d", gid), listOf("name", "Hijacked")), t0 + 10)),
            mallory.signNow(EventTemplate(39002, "", listOf(listOf("d", gid), listOf("p", me.pubkey), listOf("p", mallory.pubkey)), t0 + 10)),
        ))
        val c = Community.defaults("example.org").copy(groupsRelay = g.url, mainRelay = m.url, blossom = "http://localhost:9")
        val s = Session(c, store, me, http, scope, false, { System.currentTimeMillis() / 1000 }, null, ZoneOffset.UTC).also { sessions.add(it) }
        assertFalse(s.world.value.groupMetaTrusted, "never read: not trusted")
        assertEquals("Hijacked", s.world.value.group(gid)?.name, "unknown: shown as before")
        var reads = 0
        s.relayInfoRead = { url -> reads++; assertEquals(g.url, url); RelayInfo.Read.Key(relayKey.pubkey) }
        s.start()
        until("the relay's key") { s.world.value.groupMetaTrusted }
        assertEquals("Garden", s.world.value.group(gid)?.name)
        assertEquals(setOf(me.pubkey), s.world.value.group(gid)?.members)
        assertEquals(1, reads)
        assertTrue(store.get(RelayKeyCache.K_PREFIX + g.url.trimEnd('/'))?.contains(relayKey.pubkey) == true, "kept in the store")
        // a restart knows it at once, before any read
        s.stop()
        val s2 = Session(c, store, me, http, scope, false, { System.currentTimeMillis() / 1000 }, null, ZoneOffset.UTC).also { sessions.add(it) }
        assertTrue(s2.world.value.groupMetaTrusted)
        assertEquals("Garden", s2.world.value.group(gid)?.name)
    }
}
