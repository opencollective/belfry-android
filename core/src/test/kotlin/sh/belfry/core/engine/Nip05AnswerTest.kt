package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import sh.belfry.core.dm.DmException
import sh.belfry.core.net.Community
import sh.belfry.core.net.Discovery
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.store.MemoryStore
import java.io.IOException
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The lead's fourth review, 1: a NIP-05 lookup tells "nobody by that name" from "no answer", and a
 * failed lookup of an address on this belfry never sends a direct message as e-mail in clear. Through
 * the real Discovery and a real Session; only the HTTP layer is fake.
 */
class Nip05AnswerTest {
    @Volatile private var mode = "key"
    private val key = "ab".repeat(32)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        if (req.url.host != "example.org" || req.url.encodedPath != "/.well-known/nostr.json") throw IOException("no network in tests")
        val (code, body) = when (mode) {
            "key" -> 200 to """{"names":{"lina":"$key"}}"""
            "none" -> 200 to """{"names":{}}"""
            "5xx" -> 503 to "{}"
            "404" -> 404 to "not found"
            "junk" -> 200 to "<html>maintenance</html>"
            "nonames" -> 200 to """{"relays":{}}"""
            else -> throw IOException("offline")
        }
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build()

    @AfterTest fun tearDown() { scope.cancel() }

    private val failures = listOf("5xx", "404", "junk", "nonames", "offline")

    @Test fun answers() = runBlocking<Unit> {
        mode = "key"
        assertEquals(Discovery.Nip05Answer.Key(key), Discovery.nip05Answer("lina", "example.org", http))
        assertEquals(key, Discovery.nip05("lina", "example.org", http))
        mode = "none"
        assertEquals(Discovery.Nip05Answer.None, Discovery.nip05Answer("lina", "example.org", http))
        for (m in failures) {
            mode = m
            assertEquals(Discovery.Nip05Answer.Failed, Discovery.nip05Answer("lina", "example.org", http), m)
        }
    }

    @Test fun convForNeverDowngradesOnNoAnswer() = runBlocking<Unit> {
        val s = Session(Community.defaults("example.org"), MemoryStore(), LocalSigner.random(), http, scope, false, { 1_800_000_000L }, null, ZoneOffset.UTC)
        mode = "key"
        assertEquals("n:$key", s.convFor("lina@example.org"))
        mode = "none"
        assertEquals("m:lina@example.org", s.convFor("lina@example.org"), "nobody by that name here: an e-mail address")
        for (m in failures) {
            mode = m
            assertFailsWith<DmException>(m) { s.convFor("lina@example.org") }
        }
        assertEquals("m:ana@other.org", s.convFor("ana@other.org"), "another domain is e-mail, no lookup")
    }
}
