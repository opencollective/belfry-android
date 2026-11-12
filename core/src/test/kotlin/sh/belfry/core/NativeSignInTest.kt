package sh.belfry.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import sh.belfry.core.crypto.B64
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.engine.MemoryVault
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.engine.OnboardingException
import sh.belfry.core.engine.SavedSession
import sh.belfry.core.engine.Session
import sh.belfry.core.net.Community
import sh.belfry.core.net.MemberAddress
import sh.belfry.core.net.NativeSignIn
import sh.belfry.core.net.SessionException
import sh.belfry.core.net.SessionFailure
import sh.belfry.core.net.encodeURIComponent
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str
import sh.belfry.core.store.MemoryStore
import java.io.IOException
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signing in without a browser (SPEC 19.20): name@domain → a code by e-mail → a NIP-46 session. */
class NativeSignInTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val server = MockWebServer()
    private val relays = ArrayList<FakeRelay>()
    private val sessions = ArrayList<Session>()

    @AfterTest
    fun tearDown() {
        sessions.forEach { try { it.stop() } catch (e: Exception) { } }
        scope.cancel()
        try { server.shutdown() } catch (e: Exception) { }
        relays.forEach { try { it.server.shutdown() } catch (e: Exception) { } }
    }

    private fun community(): Community = Community.defaults("example.org").copy(apiBase = server.url("/api/v1").toString().trimEnd('/'))
    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private fun auth(req: RecordedRequest, body: String): Event {
        val header = req.getHeader("Authorization")!!
        assertTrue(header.startsWith("Nostr "))
        val ev = Event.fromJsonString(String(B64.decode(header.removePrefix("Nostr ")), Charsets.UTF_8))!!
        assertTrue(ev.isValid())
        assertEquals(27235, ev.kind)
        assertEquals("POST", ev.tag("method"))
        assertEquals(req.requestUrl.toString(), ev.tag("u"))
        assertEquals(sha256Hex(body), ev.tag("payload"))
        return ev
    }

    @Test
    fun addresses() {
        assertEquals(MemberAddress("xavier", "belfry.sh"), MemberAddress.parse("  Xavier@Belfry.SH "))
        assertEquals("maya.o-k_1@chat.example.org", MemberAddress.parse("maya.o-k_1@chat.example.org").toString())
        for (bad in listOf("", "xavier", "belfry.sh", "@belfry.sh", "xavier@", "xavier@belfry", "x y@belfry.sh", "a@b@belfry.sh",
            "xavier@belfry.sh/path", "xavier@https://belfry.sh", "-x@belfry.sh", "x@bel fry.sh", "<b>@belfry.sh", "x@belfry.sh?x=1", "x".repeat(65) + "@belfry.sh")) {
            assertNull(MemberAddress.parse(bad), bad)
        }
    }

    @Test
    fun maskedAddressesAndCodes() {
        assertTrue(NativeSignIn.isMaskedAddress("x•••@example.org"))
        assertTrue(NativeSignIn.isMaskedAddress("M•••@mail.example.co.uk"))
        for (bad in listOf("", "your address", "xavier@example.org", "x•••@", "x•••@localhost", "x••@example.org", "xy•••@example.org",
            "•••@example.org", "x•••@example.org and more", "x•••@<b>.org", "x•••@example.org\n", " •••@example.org", "x•••@" + "a".repeat(300) + ".org")) {
            assertFalse(NativeSignIn.isMaskedAddress(bad), bad)
        }
        assertEquals("123456", NativeSignIn.digits("123456"))
        assertEquals("123456", NativeSignIn.digits("123 456"))
        assertEquals("123456", NativeSignIn.digits("Your code: 123456789"))
        assertEquals("12", NativeSignIn.digits("a1b2"))
        assertTrue(NativeSignIn.isCode("000000"))
        assertFalse(NativeSignIn.isCode("12345"))
        assertFalse(NativeSignIn.isCode("12345a"))
        assertFalse(NativeSignIn.isCode("١٢٣٤٥٦"))
        // signed events are accepted once: two in the same second never carry the same second
        val a = NativeSignIn.stamp(1000)
        val b = NativeSignIn.stamp(1000)
        assertTrue(b > a)
    }

    @Test
    fun errorKeysBecomeFixedMessages() {
        fun f(status: Int, body: String?, verifying: Boolean = false) =
            NativeSignIn.failureOf(status, body?.let { parseJsonOrNull(it) as? JsonObject }, verifying)
        assertEquals(SessionFailure.UNKNOWN_MEMBER, f(404, """{"error":"unknown","message":"No member is called <b>x</b> here."}"""))
        assertEquals(SessionFailure.NO_EMAIL, f(409, """{"error":"no_email"}"""))
        assertEquals(SessionFailure.INVALID, f(422, """{"error":"invalid"}"""))
        assertEquals(SessionFailure.TOO_MANY, f(429, """{"error":"too_many"}"""))
        assertEquals(SessionFailure.UNAUTHORIZED, f(401, """{"error":"unauthorized"}"""))
        assertEquals(SessionFailure.WRONG_CODE, f(422, """{"error":"wrong_code","tries_left":3}""", true))
        assertEquals(SessionFailure.EXPIRED, f(410, """{"error":"expired"}""", true))
        assertEquals(SessionFailure.OWN_KEY, f(409, """{"error":"own_key"}""", true))
        assertEquals(SessionFailure.MISMATCH, f(409, """{"error":"mismatch"}""", true))
        assertEquals(SessionFailure.KEY_TAKEN, f(409, """{"error":"key_taken"}""", true))
        assertEquals(SessionFailure.NOT_APPROVED, f(403, """{"error":"app_not_approved"}""", true))
        // an older belfry: the route does not exist (FastAPI's own 404, a proxy's page, nothing)
        assertEquals(SessionFailure.UNSUPPORTED, f(404, """{"detail":"Not Found"}"""))
        assertEquals(SessionFailure.UNSUPPORTED, f(404, null))
        assertEquals(SessionFailure.UNSUPPORTED, f(405, """{"detail":"Method Not Allowed"}"""))
        assertEquals(SessionFailure.TOO_MANY, f(429, null))
        assertEquals(SessionFailure.OTHER, f(500, null))
        assertEquals(SessionFailure.OTHER, f(400, """{"error":"<script>"}"""))
        // no message ever carries the server's text
        assertEquals("That code is not right. 3 tries left.", SessionException(SessionFailure.WRONG_CODE, 3).display)
        assertEquals("That code is not right. 1 try left.", SessionException(SessionFailure.WRONG_CODE, 1).display)
        assertEquals("That code is not right. Ask for a new one.", SessionException(SessionFailure.WRONG_CODE, 0).display)
        assertEquals("That code is not right.", SessionException(SessionFailure.WRONG_CODE).display)
    }

    @Test
    fun theTwoCalls() = runBlocking<Unit> {
        val flow = NativeSignIn(community(), http) { 1_800_000_000 }
        val key = LocalSigner.random()
        val address = MemberAddress.parse("maya@example.org")!!

        server.enqueue(json(200, """{"state":"code_sent","to":"m•••@mail.example.org","nip05":"maya@example.org"}"""))
        val sent = flow.request(address, key.secretHex)
        assertEquals("m•••@mail.example.org", sent.to)
        assertEquals("maya@example.org", sent.nip05)
        val r1 = server.takeRequest()
        assertEquals("/api/v1/session", r1.path)
        val b1 = r1.body.readUtf8()
        assertEquals("""{"nip05":"maya@example.org","app":"sh.belfry.app://connect","client":"belfry-android"}""", b1)
        val e1 = auth(r1, b1)
        assertEquals(key.pubkey, e1.pubkey)

        // anything but a well-formed mask is not shown
        server.enqueue(json(200, """{"state":"code_sent","to":"<b>maya</b>@example.org","nip05":"maya@example.org"}"""))
        assertNull(flow.request(address, key.secretHex).to)
        val e1b = auth(server.takeRequest(), b1)
        assertTrue(e1b.createdAt > e1.createdAt, "a resend in the same second is another event")
        assertTrue(e1b.id != e1.id)

        val bunker = "bunker://${"ab".repeat(32)}?relay=wss://bunker.example.org&secret=xyz"
        server.enqueue(json(200, """{"state":"open","bunker_url":"$bunker","pubkey":"${"cd".repeat(32)}","npub":"npub1x","nip05":"maya@example.org"}"""))
        val opened = flow.verify("123456", key.secretHex)
        assertEquals(bunker, opened.bunkerUrl)
        assertEquals("cd".repeat(32), opened.pubkey)
        val r2 = server.takeRequest()
        assertEquals("/api/v1/session/verify", r2.path)
        val b2 = r2.body.readUtf8()
        assertEquals("""{"code":"123456"}""", b2)
        assertEquals(key.pubkey, auth(r2, b2).pubkey)

        server.enqueue(json(422, """{"error":"wrong_code","message":"That code is not right (4 tries left).","tries_left":4}"""))
        val wrong = assertFailsWith<SessionException> { flow.verify("000000", key.secretHex) }
        assertEquals(SessionFailure.WRONG_CODE, wrong.failure)
        assertEquals(4, wrong.triesLeft)
        server.takeRequest()

        // not six digits: nothing is sent
        assertEquals(SessionFailure.WRONG_CODE, assertFailsWith<SessionException> { flow.verify("12345", key.secretHex) }.failure)
        assertEquals(0, server.requestCount - 4)

        // a 200 that is not a session is not a sign-in
        for (body in listOf("""{"state":"open","bunker_url":"https://evil.example","pubkey":"${"cd".repeat(32)}"}""", """{"state":"open","bunker_url":"$bunker"}""", """{"bunker_url":"$bunker","pubkey":"${"cd".repeat(32)}"}""", "<html>")) {
            server.enqueue(json(200, body))
            assertEquals(SessionFailure.OTHER, assertFailsWith<SessionException> { flow.verify("123456", key.secretHex) }.failure, body)
            server.takeRequest()
        }
        server.enqueue(json(200, """{"state":"open"}"""))
        assertEquals(SessionFailure.OTHER, assertFailsWith<SessionException> { flow.request(address, key.secretHex) }.failure)
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))
        assertEquals(SessionFailure.UNSUPPORTED, assertFailsWith<SessionException> { flow.request(address, key.secretHex) }.failure)
        server.takeRequest()
        server.shutdown()
        assertEquals(SessionFailure.NETWORK, assertFailsWith<SessionException> { flow.request(address, key.secretHex) }.failure)
    }

    /** The belfry's signer for one session: answers connect / get_public_key as MEMBER. */
    private fun signer(member: LocalSigner): String {
        val relay = FakeRelay().also { it.expect(4); relays.add(it) }
        val remote = LocalSigner.random()
        relay.onPublished = { ws: WebSocket, ev: Event ->
            if (ev.kind == 24133 && ev.tag("p") == remote.pubkey) {
                val req = parseJsonOrNull(remote.nip44DecryptNow(ev.pubkey, ev.content)) as JsonObject
                val result = when (req.s("method")) {
                    "connect" -> "ack"
                    "get_public_key" -> member.pubkey
                    else -> null
                }
                val body = buildJsonObject {
                    put("id", JsonPrimitive(req.s("id")!!))
                    if (result != null) put("result", JsonPrimitive(result)) else put("error", JsonPrimitive("no"))
                }.toString()
                relay.send(ws, "EVENT", "nip46", remote.signNow(EventTemplate(24133, remote.nip44EncryptNow(ev.pubkey, body), listOf(listOf("p", ev.pubkey)))))
            }
        }
        return "bunker://${remote.pubkey}?relay=${encodeURIComponent(relay.url)}&secret=s3cret"
    }

    private fun signedBy(req: RecordedRequest): String =
        Event.fromJsonString(String(B64.decode(req.getHeader("Authorization")!!.removePrefix("Nostr "))))!!.pubkey

    @Test
    fun theWholeFlow() = runBlocking<Unit> {
        val now = 1_800_000_000L
        val vault = MemoryVault()
        val asked = ArrayList<String>()
        fun onboarding() = Onboarding(vault, MemoryStore(), http, scope, { now }, ZoneOffset.UTC) { domain ->
            asked.add(domain)
            if (domain == "example.org") community() else throw IOException("no belfry.json")
        }
        val on = onboarding()
        assertNull(on.pendingCode)

        assertEquals("not_an_address", assertFailsWith<OnboardingException> { on.requestCode("example.org") }.code)
        val none = assertFailsWith<OnboardingException> { on.requestCode("maya@nowhere.example") }
        assertEquals("no_belfry", none.code)
        assertEquals("There is no belfry at nowhere.example. Check the address and your connection.", none.message)
        assertEquals(listOf("nowhere.example"), asked)

        server.enqueue(json(404, """{"error":"unknown","message":"No member is called nobody here."}"""))
        val unknown = assertFailsWith<OnboardingException> { on.requestCode("nobody@example.org") }
        assertEquals("unknown_member", unknown.code)
        assertFalse(unknown.message!!.contains("nobody"))
        server.takeRequest()
        assertNull(on.pendingCode)

        // an older belfry: the screen offers the browser
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))
        assertEquals("browser", assertFailsWith<OnboardingException> { on.requestCode("maya@example.org") }.code)
        val firstKey = signedBy(server.takeRequest())

        server.enqueue(json(200, """{"state":"code_sent","to":"m•••@mail.example.org","nip05":"maya@example.org"}"""))
        val pending = on.requestCode(" Maya@Example.org ")
        assertEquals("maya@example.org", pending.address)
        assertEquals("m•••@mail.example.org", pending.to)
        assertEquals(firstKey, signedBy(server.takeRequest()), "the same address keeps this device's session key")

        // the app was killed while the person read the e-mail: screen 2 comes back
        val again = onboarding()
        assertEquals(pending, again.pendingCode)

        // "Send a new code": the same key
        server.enqueue(json(200, """{"state":"code_sent","to":"m•••@mail.example.org","nip05":"maya@example.org"}"""))
        again.resendCode()
        assertEquals(firstKey, signedBy(server.takeRequest()))

        server.enqueue(json(422, """{"error":"wrong_code","tries_left":4}"""))
        val wrong = assertFailsWith<OnboardingException> { again.verifyCode("111111") }
        assertEquals("wrong_code", wrong.code)
        assertEquals("That code is not right. 4 tries left.", wrong.message)
        assertEquals(firstKey, signedBy(server.takeRequest()))
        assertNotNull(again.pendingCode)

        // the belfry names one key, its signer answers with another: no session
        val member = LocalSigner.random()
        server.enqueue(json(200, buildJsonObject {
            put("state", JsonPrimitive("open")); put("bunker_url", JsonPrimitive(signer(LocalSigner.random()))); put("pubkey", JsonPrimitive(member.pubkey))
        }.toString()))
        assertEquals("mismatch", assertFailsWith<OnboardingException> { again.verifyCode("222222") }.code)
        server.takeRequest()
        assertNull(again.pendingCode)
        assertFalse(again.hasSession)

        // start again: the right code, the signer is the member's
        server.enqueue(json(200, """{"state":"code_sent","to":"m•••@mail.example.org"}"""))
        again.requestCode("maya@example.org")
        val newKey = signedBy(server.takeRequest())
        assertTrue(newKey != firstKey)
        server.enqueue(json(200, buildJsonObject {
            put("state", JsonPrimitive("open")); put("bunker_url", JsonPrimitive(signer(member))); put("pubkey", JsonPrimitive(member.pubkey))
        }.toString()))
        val session = again.verifyCode("333333").also { sessions.add(it) }
        assertEquals(newKey, signedBy(server.takeRequest()))
        assertEquals(member.pubkey, session.me)
        assertTrue(again.hasSession)
        assertNull(again.pendingCode)
        assertNull(vault.get(Onboarding.V_NATIVE))
        val saved = SavedSession.fromJsonString(vault.get(Onboarding.V_SESSION)!!)!!
        assertEquals(member.pubkey, saved.pubkey)
        // the NIP-46 client key is the key that signed the two calls
        assertEquals(newKey, LocalSigner(saved.clientSecret).pubkey)
    }

    @Test
    fun requestsThatEnd() = runBlocking<Unit> {
        var now = 1_800_000_000L
        val vault = MemoryVault()
        val on = Onboarding(vault, MemoryStore(), http, scope, { now }, ZoneOffset.UTC) { community() }
        assertEquals("expired", assertFailsWith<OnboardingException> { on.verifyCode("123456") }.code)
        assertEquals(0, server.requestCount)

        server.enqueue(json(200, """{"state":"code_sent","to":"m•••@mail.example.org"}"""))
        on.requestCode("maya@example.org")
        val key = signedBy(server.takeRequest())
        // 15 minutes later the belfry has forgotten the request: screen 1 again
        now += 901
        assertNull(on.pendingCode)
        now -= 901

        server.enqueue(json(410, """{"error":"expired"}"""))
        assertEquals("expired", assertFailsWith<OnboardingException> { on.verifyCode("123456") }.code)
        server.takeRequest()
        assertNull(on.pendingCode)

        // this device's key is another account's in the signer: the next try makes a new one
        server.enqueue(json(200, """{"state":"code_sent"}"""))
        assertNull(on.requestCode("maya@example.org").to)
        val second = signedBy(server.takeRequest())
        server.enqueue(json(409, """{"error":"key_taken"}"""))
        assertEquals("key_taken", assertFailsWith<OnboardingException> { on.verifyCode("123456") }.code)
        server.takeRequest()
        server.enqueue(json(200, """{"state":"code_sent"}"""))
        on.requestCode("maya@example.org")
        assertTrue(signedBy(server.takeRequest()) != second)
        assertTrue(second != key)

        on.cancelCode()
        assertNull(on.pendingCode)
        assertNull(vault.get(Onboarding.V_NATIVE))
    }
}
