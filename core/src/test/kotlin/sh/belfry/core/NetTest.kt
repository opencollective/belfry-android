package sh.belfry.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import sh.belfry.core.crypto.B64
import sh.belfry.core.crypto.Nip04
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.net.Access
import sh.belfry.core.net.ApiException
import sh.belfry.core.net.Backoff
import sh.belfry.core.net.BelfryApi
import sh.belfry.core.net.BunkerSigner
import sh.belfry.core.net.BunkerUrl
import sh.belfry.core.net.Community
import sh.belfry.core.net.Discovery
import sh.belfry.core.net.Filters
import sh.belfry.core.net.InviteLink
import sh.belfry.core.net.Relay
import sh.belfry.core.net.RelayException
import sh.belfry.core.net.RelayState
import sh.belfry.core.net.SignIn
import sh.belfry.core.net.SignInException
import sh.belfry.core.net.SignInFailure
import sh.belfry.core.net.encodeURIComponent
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.SignerTimeout
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A tiny relay on MockWebServer: stores events, answers REQ / EVENT / AUTH; can play the remote signer. */
class FakeRelay(private val requireAuth: Boolean = false) : WebSocketListener() {
    val server = MockWebServer()
    val challenge = "challenge-1234"
    val reqs = CopyOnWriteArrayList<String>()
    val stored = CopyOnWriteArrayList<Event>()
    val published = CopyOnWriteArrayList<Event>()
    val sockets = CopyOnWriteArrayList<WebSocket>()
    @Volatile var authedAs: String? = null
    @Volatile var opens = 0
    /** content → refusal reason */
    val refuse = HashMap<String, String>()
    /** Handles an event itself (the remote signer); true = handled, no OK sent by the relay. */
    @Volatile var onPublished: ((WebSocket, Event) -> Unit)? = null

    val url: String get() = server.url("/").toString().replace("http://", "ws://")

    fun expect(connections: Int = 1) = repeat(connections) { server.enqueue(MockResponse().withWebSocketUpgrade(this)) }

    fun dropAll() = sockets.forEach { it.close(1001, "bye") }

    fun send(ws: WebSocket, vararg parts: Any) {
        ws.send(buildJsonArray {
            for (p in parts) when (p) {
                is String -> add(JsonPrimitive(p))
                is Boolean -> add(JsonPrimitive(p))
                is Event -> add(p.toJson())
                else -> error("unsupported")
            }
        }.toString())
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        opens++
        authedAs = null
        sockets.add(webSocket)
        if (requireAuth) send(webSocket, "AUTH", challenge)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val arr = parseJsonOrNull(text) as? JsonArray ?: return
        when (arr[0].str()) {
            "REQ" -> {
                val sub = arr[1].str()!!
                reqs.add(sub)
                if (requireAuth && authedAs == null) {
                    send(webSocket, "CLOSED", sub, "auth-required: sign in first")
                    return
                }
                // every filter of the REQ counts (by kinds only)
                val kindSets = arr.drop(2).mapNotNull { it as? JsonObject }.map { f -> f["kinds"].arr()?.mapNotNull { it.int() } }
                for (e in stored) if (kindSets.any { it == null || e.kind in it }) send(webSocket, "EVENT", sub, e)
                send(webSocket, "EOSE", sub)
            }
            "EVENT" -> {
                val ev = Event.fromJson(arr[1])!!
                if (requireAuth && authedAs == null) {
                    send(webSocket, "OK", ev.id, false, "auth-required: sign in first")
                    return
                }
                val why = refuse[ev.content]
                if (why != null) {
                    send(webSocket, "OK", ev.id, false, why)
                    return
                }
                published.add(ev)
                if (ev.kind != 24133) stored.add(ev)
                send(webSocket, "OK", ev.id, true, "")
                onPublished?.invoke(webSocket, ev)
            }
            "AUTH" -> {
                val ev = Event.fromJson(arr[1])!!
                val ok = ev.kind == 22242 && ev.isValid() && ev.tag("challenge") == challenge && ev.tag("relay") == url
                if (ok) authedAs = ev.pubkey
                send(webSocket, "OK", ev.id, ok, if (ok) "" else "restricted: not you")
            }
            else -> Unit
        }
    }
}

class RelayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient()
    private val fakes = ArrayList<FakeRelay>()

    private fun fake(auth: Boolean = false, connections: Int = 1): FakeRelay =
        FakeRelay(auth).also { it.expect(connections); fakes.add(it) }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fakes.forEach { try { it.server.shutdown() } catch (e: Exception) { /* sockets still open */ } }
        client.dispatcher.executorService.shutdown()
    }

    private suspend fun until(what: String, ms: Long = 5000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!check()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for: $what")
            delay(20)
        }
    }

    @Test
    fun authThenSubscriptionsAreSentAgain() = runBlocking {
        val f = fake(auth = true)
        val author = LocalSigner.random()
        val member = LocalSigner.random()
        val stored = author.signNow(EventTemplate(9, "hello", listOf(listOf("h", "general")), 1_800_000_000))
        f.stored.add(stored)
        // a forged event: the relay hands it over, the client must drop it
        f.stored.add(stored.copy(content = "forged"))

        val relay = Relay(f.url, client, scope)
        relay.authSigner = member
        val got = CopyOnWriteArrayList<Event>()
        val eose = CompletableDeferred<String>()
        val authenticated = CompletableDeferred<Unit>()
        relay.onEvent = { _, e -> got.add(e) }
        relay.onEose = { sub -> eose.complete(sub) }
        relay.onAuthenticated = { authenticated.complete(Unit) }
        relay.subscribe("feed", listOf(Filters.of(kinds = listOf(9), since = 5, limit = 10)))
        relay.connect()
        withTimeout(5000) { authenticated.await() }
        assertEquals("feed", withTimeout(5000) { eose.await() })
        assertEquals(member.pubkey, f.authedAs)
        assertEquals(listOf(stored), got.toList())
        assertTrue(f.reqs.count { it == "feed" } >= 2, "REQ sent on connect and again after AUTH")
        assertEquals(RelayState.UP, relay.state.value)

        // publish: accepted, then refused with the relay's reason
        val mine = member.signNow(EventTemplate(9, "hi", listOf(listOf("h", "general"))))
        relay.publish(mine)
        assertEquals(listOf(mine), f.published.toList())
        f.refuse["nope"] = "restricted: you are not a member"
        try {
            relay.publish(member.signNow(EventTemplate(9, "nope", emptyList())))
            fail("the relay refused: publish must throw")
        } catch (e: RelayException) {
            assertTrue(e.reason.startsWith("restricted:"))
            assertTrue(Access.isRefusal(e.reason))
        }

        // one-off query (forged one dropped here too)
        assertEquals(listOf(stored.id, mine.id), relay.query(listOf(Filters.of(kinds = listOf(9)))).map { it.id })
        relay.close()
        assertEquals(RelayState.DOWN, relay.state.value)
    }

    @Test
    fun publishRightAfterConnectWaitsForAuth() = runBlocking {
        val f = fake(auth = true)
        val member = LocalSigner.random()
        val relay = Relay(f.url, client, scope)
        relay.authSigner = member
        val ev = member.signNow(EventTemplate(9, "first", emptyList()))
        relay.publish(ev) // connects, may be told auth-required, signs in, publishes again
        assertEquals(listOf(ev.id), f.published.map { it.id })
        relay.close()
    }

    @Test
    fun noConnection() = runBlocking {
        val relay = Relay("ws://127.0.0.1:9/", client, scope)
        try {
            relay.publish(LocalSigner.random().signNow(EventTemplate(9, "x", emptyList())), timeoutMs = 2000)
            fail("nothing listens there")
        } catch (e: RelayException) {
            assertTrue(e.reason.startsWith("timeout:") || e.reason == "connection closed", e.reason)
        }
        assertTrue(relay.query(listOf(Filters.of(kinds = listOf(1))), 500).isEmpty())
    }

    @Test
    fun reconnectsAndSubscribesAgain() = runBlocking {
        val f = fake(connections = 2)
        val relay = Relay(f.url, client, scope)
        var drops = 0
        relay.onDisconnect = { drops++ }
        relay.subscribe("feed", listOf(Filters.of(kinds = listOf(9))))
        relay.keepConnected()
        until("first connection") { f.reqs.size == 1 && relay.isUp }
        f.dropAll()
        until("the drop is noticed") { drops == 1 }
        until("reconnected with the subscription", 8000) { f.opens == 2 && f.reqs.size == 2 && relay.isUp }
        relay.stop()
    }

    @Test
    fun backoffAndRefusals() {
        assertEquals(1600L, Backoff.delayMs(1))
        assertEquals(3200L, Backoff.delayMs(2))
        assertEquals(30_000L, Backoff.delayMs(6))
        assertEquals(30_000L, Backoff.delayMs(40))
        assertTrue(Access.isRefusal("restricted: no pass"))
        assertTrue(Access.isRefusal("blocked: gone"))
        assertTrue(Access.isRefusal("error: only members may read"))
        assertFalse(Access.isRefusal("rate-limited: slow down"))
        val filter = Filters.of(kinds = listOf(1059), tags = mapOf("p" to listOf("ab")), since = 3, limit = 7)
        assertEquals("{\"kinds\":[1059],\"#p\":[\"ab\"],\"since\":3,\"limit\":7}", filter.toString())
    }

    // ---- NIP-46 -----------------------------------------------------------------------------------

    private class FakeSigner(val relay: FakeRelay, val secret: String = "s3cret") {
        val remote = LocalSigner.random() // the bunker's own key
        val member = LocalSigner.random() // the key it signs with
        @Volatile var silent = false
        @Volatile var nip04Answers = false
        val methods = CopyOnWriteArrayList<String>()

        init {
            relay.onPublished = { ws, ev -> if (ev.kind == 24133 && ev.tag("p") == remote.pubkey) answer(ws, ev) }
        }

        val bunkerUrl: String get() = "bunker://${remote.pubkey}?relay=${encodeURIComponent(relay.url)}&secret=$secret"

        private fun answer(ws: WebSocket, ev: Event) {
            val req = parseJsonOrNull(remote.nip44DecryptNow(ev.pubkey, ev.content)) as JsonObject
            val id = req.s("id")!!
            val params = req["params"].arr()!!.map { it.str()!! }
            val method = req.s("method")!!
            methods.add(method)
            if (silent) return
            var error: String? = null
            val result: String? = when (method) {
                "connect" -> if (params[0] == remote.pubkey && params[1] == secret) "ack" else { error = "bad secret"; null }
                "get_public_key" -> member.pubkey
                "sign_event" -> {
                    val t = parseJsonOrNull(params[0]) as JsonObject
                    val tags = t["tags"].arr()!!.map { tag -> tag.arr()!!.map { it.str()!! } }
                    member.signNow(EventTemplate(t["kind"].int()!!, t.s("content")!!, tags, t["created_at"].long()!!)).toJsonString()
                }
                "nip44_encrypt" -> member.nip44EncryptNow(params[0], params[1])
                "nip44_decrypt" -> member.nip44DecryptNow(params[0], params[1])
                else -> { error = "unknown method"; null }
            }
            val body = buildJsonObject {
                put("id", JsonPrimitive(id))
                if (result != null) put("result", JsonPrimitive(result))
                if (error != null) put("error", JsonPrimitive(error))
            }.toString()
            val content = if (nip04Answers) Nip04.encrypt(remote.secretHex, ev.pubkey, body) else remote.nip44EncryptNow(ev.pubkey, body)
            relay.send(ws, "EVENT", "nip46", remote.signNow(EventTemplate(24133, content, listOf(listOf("p", ev.pubkey)))))
        }
    }

    @Test
    fun bunkerSignerRoundTrip() = runBlocking {
        val f = fake()
        val fs = FakeSigner(f)
        val parsed = BunkerUrl.parse(fs.bunkerUrl)
        assertNotNull(parsed)
        assertEquals(fs.remote.pubkey, parsed.remotePubkey)
        assertEquals(listOf(f.url), parsed.relays)
        assertEquals("s3cret", parsed.secret)

        val signer = BunkerSigner(parsed, LocalSigner.random().secretHex, null, client, scope, timeoutMs = 5000)
        assertEquals("", signer.pubkey)
        assertEquals(fs.member.pubkey, signer.ensurePubkey())
        assertEquals(fs.member.pubkey, signer.pubkey)
        assertEquals(listOf("connect", "get_public_key"), fs.methods.toList())

        val ev = signer.sign(EventTemplate(9, "signed far away \"é\"", listOf(listOf("h", "general")), 1_800_000_000))
        assertTrue(ev.isValid())
        assertEquals(fs.member.pubkey, ev.pubkey)
        assertEquals(1_800_000_000L, ev.createdAt)

        val peer = LocalSigner.random()
        val payload = signer.nip44Encrypt(peer.pubkey, "for your eyes")
        assertEquals("for your eyes", peer.nip44DecryptNow(fs.member.pubkey, payload))
        assertEquals("and back", signer.nip44Decrypt(peer.pubkey, peer.nip44EncryptNow(fs.member.pubkey, "and back")))

        // a signer that answers in NIP-04 is understood too
        fs.nip04Answers = true
        assertTrue(signer.sign(EventTemplate(9, "again", emptyList())).isValid())
        assertEquals(1, fs.methods.count { it == "connect" })
        signer.disconnect()
    }

    @Test
    fun bunkerSignerTimesOutAndStartsFresh() = runBlocking {
        val f = fake(connections = 3)
        val fs = FakeSigner(f)
        val signer = BunkerSigner(BunkerUrl.parse(fs.bunkerUrl)!!, LocalSigner.random().secretHex, fs.member.pubkey, client, scope, timeoutMs = 700)
        fs.silent = true
        try {
            signer.sign(EventTemplate(9, "lost", emptyList()))
            fail("the signer was silent")
        } catch (e: SignerTimeout) {
            // expected
        }
        fs.silent = false
        signer.timeoutMs = 5000
        assertTrue(signer.sign(EventTemplate(9, "found", emptyList())).isValid())
        // the silent request was asked once more on a fresh connection before giving up
        assertEquals(3, f.opens)
        signer.disconnect()
    }

    @Test
    fun bunkerUrls() {
        val key = "ab".repeat(32)
        assertNull(BunkerUrl.parse("https://example.org"))
        assertNull(BunkerUrl.parse("bunker://nothex?relay=wss://r.example"))
        assertNull(BunkerUrl.parse("bunker://$key?secret=x"))
        assertNull(BunkerUrl.parse("bunker://$key?relay=https://not-a-relay.example"))
        val b = BunkerUrl.parse("bunker://$key?relay=wss%3A%2F%2Fbunker.belfry.sh&relay=wss://two.example&secret=abc")!!
        assertEquals(listOf("wss://bunker.belfry.sh", "wss://two.example"), b.relays)
        assertEquals("abc", b.secret)
        assertNull(BunkerUrl.parse("bunker://$key?relay=wss://r.example")!!.secret)
    }
}

class DiscoveryTest {
    private fun doc(name: String): String = javaClass.getResource("/discovery/$name.json")!!.readText(Charsets.UTF_8)

    @Test
    fun defaultsFollowTheSpecTable() {
        val c = Community.parse(" Example.ORG ", null as JsonObject?)
        assertEquals("example.org", c.domain)
        assertEquals("example.org", c.name)
        assertEquals("wss://groups-relay.example.org", c.groupsRelay)
        assertEquals("wss://relay.example.org", c.mainRelay)
        assertEquals(listOf("wss://bunker.example.org"), c.signerRelays)
        assertEquals("https://blossom.example.org", c.blossom)
        assertEquals("https://belfry.example.org/api/v1", c.apiBase)
        assertEquals("https://belfry.example.org/api/v1/channel-invites", c.channelInvites)
        assertEquals("https://belfry.example.org/connect/authorize", c.authorize)
        assertEquals("https://belfry.example.org/connect/token", c.token)
        assertEquals("https://belfry.example.org/connect/logout", c.logout)
        assertEquals(emptyList(), c.admins)
        assertEquals("https://example.org/chat/", c.chatUrl)
        assertEquals("https://example.org/chat/#/c/caf%C3%A9%20club", c.channelLink("café club"))
    }

    @Test
    fun liveDocuments() {
        for (domain in listOf("belfry.sh", "enspiral.eu")) {
            val c = Community.parse(domain, doc(domain))
            assertEquals(domain, c.domain)
            assertTrue(c.name.isNotEmpty() && c.name != domain, "the community's own name")
            assertEquals("wss://groups-relay.$domain", c.groupsRelay)
            assertEquals("wss://relay.$domain", c.mainRelay)
            assertEquals(listOf("wss://bunker.$domain"), c.signerRelays)
            assertEquals("https://blossom.$domain", c.blossom)
            assertEquals("https://belfry.$domain/api/v1", c.apiBase)
            assertEquals("https://belfry.$domain/api/v1/channel-invites", c.channelInvites)
            assertEquals("https://belfry.$domain/api/v1/me/follows", c.follows)
            assertEquals("https://belfry.$domain/connect/authorize", c.authorize)
            assertEquals("https://belfry.$domain/connect/token", c.token)
            assertEquals("https://belfry.$domain/connect/revoke", c.revoke) // not in the document: the fallback
            assertEquals("https://chat.$domain/", c.chatUrl)
            assertTrue(c.admins.isNotEmpty() && c.admins.all { it.length == 64 })
            assertNotNull(c.belfryPubkey)
            // the offline cache gives the same thing back
            assertEquals(c, Community.fromJsonString(c.toJsonString()))
        }
    }

    @Test
    fun oddDocuments() {
        val c = Community.parse(
            "example.org",
            """{"name":"  ","admins":["nothex","${"cd".repeat(32)}",7],"access":{"admins":["${"ef".repeat(32)}","${"cd".repeat(32)}"]},
               "relays":{"groups":"https://wrong.example","signer":[]},"api":{"base":"https://api.example.org/v9/"},
               "apps":[{"name":"chat","url":"https://talk.example.org"}],"blossom":"javascript:alert(1)"}""",
        )
        assertEquals("example.org", c.name)
        assertEquals(listOf("cd".repeat(32), "ef".repeat(32)), c.admins)
        assertEquals("wss://groups-relay.example.org", c.groupsRelay)
        assertEquals(listOf("wss://bunker.example.org"), c.signerRelays)
        assertEquals("https://api.example.org/v9", c.apiBase)
        assertEquals("https://api.example.org/v9/channel-invites", c.channelInvites)
        assertEquals("https://talk.example.org/", c.chatUrl)
        assertEquals("https://blossom.example.org", c.blossom)
        assertEquals("example.org", Community.parse("example.org", "<!doctype html>").name)
    }

    @Test
    fun domainsAndInviteLinks() {
        assertTrue(Community.isDomain("belfry.sh"))
        assertTrue(Community.isDomain("my-town.example.org"))
        assertFalse(Community.isDomain("localhost"))
        assertFalse(Community.isDomain("bad domain.org"))
        assertFalse(Community.isDomain("-x.org"))
        val token = "AbCdEfGhIjKlMnOpQrStUv_-12"
        assertEquals(InviteLink("belfry.sh", token), InviteLink.parse("https://chat.belfry.sh/join/$token"))
        assertEquals(InviteLink("enspiral.eu", token), InviteLink.parse("https://chat.enspiral.eu/#/join/$token"))
        assertNull(InviteLink.parse("https://chat.belfry.sh/join/short"))
        assertNull(InviteLink.parse("https://belfry.sh/join/$token"))
        assertNull(InviteLink.parse("http://chat.belfry.sh/join/$token"))
        assertNull(InviteLink.parse("https://chat.belfry.sh/join/$token?name=Mallory"))
    }

    @Test
    fun nip05Names() {
        val names = parseJsonOrNull("""{"_smtp":"${"AB".repeat(32)}","Xavier":"${"cd".repeat(32)}","bad":"zz"}""") as JsonObject
        assertEquals("ab".repeat(32), Discovery.pick(names, "_smtp"))
        assertEquals("cd".repeat(32), Discovery.pick(names, "xavier"))
        assertNull(Discovery.pick(names, "bad"))
        assertNull(Discovery.pick(names, "nobody"))
    }
}

class SignInAndApiTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun community(): Community {
        val base = server.url("/api/v1").toString()
        return Community.defaults("example.org").copy(
            apiBase = base, channelInvites = "$base/channel-invites", follows = "$base/me/follows",
            token = server.url("/connect/token").toString(), blossom = server.url("/blossom").toString(),
        )
    }

    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private fun authEvent(header: String?): Event {
        assertNotNull(header)
        assertTrue(header.startsWith("Nostr "))
        val ev = Event.fromJson(parseJsonOrNull(String(B64.decode(header.removePrefix("Nostr ")), Charsets.UTF_8))!!)!!
        assertTrue(ev.isValid())
        assertEquals(27235, ev.kind)
        return ev
    }

    @Test
    fun authorizeUrlAndCallback() {
        val flow = SignIn.start(Community.defaults("example.org"))
        val url = flow.authorizeUrl()
        assertTrue(url.startsWith("https://belfry.example.org/connect/authorize?client_id=belfry-android&redirect_uri=sh.belfry.app%3A%2F%2Fconnect&scope=policy%3Abelfry-event&state="), url)
        assertTrue(url.endsWith("&code_challenge_method=S256"), url)
        assertTrue(flow.authorizeUrl(silent = true).endsWith("&prompt=none"))
        // kept across a process death
        val again = SignIn.fromJsonString(flow.toJsonString())!!
        assertEquals(flow.state, again.state)
        assertEquals(flow.verifier, again.verifier)
        assertEquals(flow.authorizeUrl(), again.authorizeUrl())

        assertEquals("abc", flow.code("abc", flow.state, null))
        fun failure(code: String?, state: String?, error: String?): SignInFailure =
            try { flow.code(code, state, error); fail("must throw") } catch (e: SignInException) { e.failure }
        assertEquals(SignInFailure.WRONG_STATE, failure("abc", "someone-else", null))
        assertEquals(SignInFailure.WRONG_STATE, failure("abc", null, null))
        assertEquals(SignInFailure.CANCELLED, failure(null, flow.state, "access_denied"))
        assertEquals(SignInFailure.REFUSED, failure(null, flow.state, "<b>text from the URL</b>"))
        assertEquals(SignInFailure.REFUSED, failure("", flow.state, null))
        assertFalse(SignInFailure.REFUSED.message.contains("<b>"))
    }

    @Test
    fun tokenExchange() = runBlocking {
        val flow = SignIn.start(community())
        val bunker = "bunker://${"ab".repeat(32)}?relay=wss://bunker.example.org&secret=xyz"
        server.enqueue(json(200, """{"bunker_url":"$bunker","token_type":"bunker","pubkey":"${"cd".repeat(32)}","npub":"npub1x"}"""))
        val token = flow.finish("the-code", flow.state, null, http)
        assertEquals(bunker, token.bunkerUrl)
        assertEquals("cd".repeat(32), token.pubkey)
        val sent = parseJsonOrNull(server.takeRequest().body.readUtf8()) as JsonObject
        assertEquals("authorization_code", sent.s("grant_type"))
        assertEquals("the-code", sent.s("code"))
        assertEquals("belfry-android", sent.s("client_id"))
        assertEquals("sh.belfry.app://connect", sent.s("redirect_uri"))
        assertEquals(flow.verifier, sent.s("code_verifier"))

        for ((error, failure) in listOf(
            "app_not_approved" to SignInFailure.NOT_APPROVED, "own_key" to SignInFailure.OWN_KEY,
            "invalid_grant" to SignInFailure.EXPIRED, "<script>" to SignInFailure.UNKNOWN,
        )) {
            server.enqueue(json(400, buildJsonObject { put("error", JsonPrimitive(error)) }.toString()))
            try {
                flow.exchange("c", http)
                fail("must throw")
            } catch (e: SignInException) {
                assertEquals(failure, e.failure)
            }
            server.takeRequest()
        }
        // a 200 without a usable bunker URL is not a sign-in
        server.enqueue(json(200, """{"bunker_url":"https://evil.example"}"""))
        try { flow.exchange("c", http); fail("must throw") } catch (e: SignInException) { assertEquals(SignInFailure.UNKNOWN, e.failure) }
    }

    @Test
    fun guestLinks() = runBlocking {
        val api = BelfryApi(community(), http)
        val member = LocalSigner.random()
        val token = "AbCdEfGhIjKlMnOpQrStUv_-12"

        // making one
        server.enqueue(json(200, """{"url":"https://chat.example.org/join/$token","agent_url":"https://belfry.example.org/invite/$token.md","days":30,"people":50,"link_days":7,"expires_at":"2026-11-17"}"""))
        val link = api.createGuestLink(member, "general", people = 50, linkDays = 7)
        assertEquals("https://chat.example.org/join/$token", link.url)
        assertEquals(50, link.people)
        assertEquals(7, link.linkDays)
        var req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/channel-invites", req.path)
        var body = req.body.readUtf8()
        assertEquals("""{"channel":"general","people":50,"link_days":7}""", body)
        var auth = authEvent(req.getHeader("Authorization"))
        assertEquals(member.pubkey, auth.pubkey)
        assertEquals(listOf(listOf("u", community().channelInvites), listOf("method", "POST"), listOf("payload", sha256Hex(body))), auth.tags)

        server.enqueue(json(403, """{"error":"guest","message":"<i>server text</i>"}"""))
        try {
            api.createGuestLink(member, "general")
            fail("must throw")
        } catch (e: ApiException) {
            assertEquals("guest", e.code)
            assertEquals(403, e.status)
            assertEquals("Guests can’t invite guests.", e.message)
        }
        server.takeRequest()

        // reading one
        server.enqueue(json(200, """{"community":"Example","channel":"garden-1a2b","channel_name":"Garden","about":"Plants","inviter":"Ada","state":"open","days":30}"""))
        val info = api.inviteInfo(token)
        assertEquals("Garden", info.channelName)
        assertEquals("Ada", info.inviter)
        assertTrue(info.isOpen)
        assertNull(info.closedMessage)
        req = server.takeRequest()
        assertEquals("/api/v1/channel-invites/$token", req.path)
        assertNull(req.getHeader("Authorization"))
        server.enqueue(json(404, """{"error":"not_found","message":"No such invite."}"""))
        try { api.inviteInfo(token); fail("must throw") } catch (e: ApiException) { assertEquals("not_found", e.code) }
        server.takeRequest()
        server.enqueue(json(200, """{"community":"Example","channel":"garden-1a2b","state":"expired","days":30}"""))
        assertEquals("This invite has expired. Ask for a new one.", api.inviteInfo(token).closedMessage)
        server.takeRequest()

        // a new guest: signed by this device's own fresh session key
        val session = LocalSigner.random()
        val bunker = "bunker://${"ab".repeat(32)}?relay=wss://bunker.example.org&secret=xyz"
        server.enqueue(json(200, """{"channel":"garden-1a2b","new":true,"bunker_url":"$bunker","pubkey":"${"cd".repeat(32)}"}"""))
        val joined = api.joinAsNew(token, "  Grace  ", session.secretHex)
        assertEquals(bunker, joined.bunkerUrl)
        assertEquals("garden-1a2b", joined.channel)
        req = server.takeRequest()
        assertEquals("/api/v1/channel-invites/$token/join", req.path)
        body = req.body.readUtf8()
        assertEquals("""{"name":"Grace"}""", body)
        auth = authEvent(req.getHeader("Authorization"))
        assertEquals(session.pubkey, auth.pubkey)
        assertEquals(sha256Hex(body), auth.tag("payload"))

        server.enqueue(json(409, """{"error":"session_taken","message":"This session belongs to another account."}"""))
        try { api.joinAsNew(token, "Grace", session.secretHex); fail("must throw") } catch (e: ApiException) { assertEquals("session_taken", e.code) }
        server.takeRequest()
        server.enqueue(json(410, """{"error":"closed","message":"whatever"}"""))
        try { api.joinAsNew(token, "Grace", session.secretHex); fail("must throw") } catch (e: ApiException) {
            assertEquals("closed", e.code)
            assertEquals("This invite is closed. Ask for a new one.", e.message)
        }
        server.takeRequest()

        // an existing account
        server.enqueue(json(200, """{"channel":"garden-1a2b","pubkey":"${member.pubkey}","new":false,"guest":false,"already":true}"""))
        val mine = api.joinWithAccount(member, token)
        assertEquals("garden-1a2b", mine.channel)
        assertTrue(mine.already)
        req = server.takeRequest()
        assertEquals("{}", req.body.readUtf8())
        assertEquals(member.pubkey, authEvent(req.getHeader("Authorization")).pubkey)
    }

    @Test
    fun placesAdminsFollows() = runBlocking {
        val api = BelfryApi(community(), http)
        val member = LocalSigner.random()

        assertTrue(api.searchPlaces("b").places.isEmpty()) // too short: no request
        server.enqueue(json(200, """{"places":[{"id":"gn:2800866","name":"Brussels","region":"Brussels Capital","country":"BE","lat":50.85,"lon":4.35}],"attribution":"GeoNames, CC BY 4.0"}"""))
        val found = api.searchPlaces("Brux elles")
        assertEquals("Brussels", found.places[0].name)
        assertEquals(50.85, found.places[0].lat)
        assertEquals("GeoNames, CC BY 4.0", found.attribution)
        assertEquals("/api/v1/places?q=Brux%20elles&limit=8", server.takeRequest().path)

        for ((code, text) in listOf(
            "guest_not_saved" to "This guest has no e-mail yet. Once they save their account, you can make them an admin.",
            "agent" to "An agent cannot be an admin.",
            "not_member" to "Only members can be admins.",
            "last_admin" to "A belfry needs at least one admin.",
        )) {
            server.enqueue(json(400, buildJsonObject { put("error", JsonPrimitive(code)); put("message", JsonPrimitive("server text")) }.toString()))
            try {
                api.setAdmin(member, "ab".repeat(32), true)
                fail("must throw")
            } catch (e: ApiException) {
                assertEquals(code, e.code)
                assertEquals(text, e.message)
            }
            assertEquals("""{"pubkey":"${"ab".repeat(32)}","admin":true}""", server.takeRequest().body.readUtf8())
        }
        server.enqueue(json(200, """{"ok":true}"""))
        api.setAdmin(member, "ab".repeat(32), false)
        server.takeRequest()

        server.enqueue(json(200, """{"email":"ada@example.org","follows":[{"channel":"general","frequency":"monthly","state":"active"},{"channel":"old","frequency":"asap","state":"stopped"}]}"""))
        val follows = api.follows(member)
        assertEquals("ada@example.org", follows.email)
        assertEquals(mapOf("general" to "monthly"), follows.byChannel)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        val auth = authEvent(req.getHeader("Authorization"))
        assertEquals(listOf(listOf("u", community().follows), listOf("method", "GET")), auth.tags)
        server.enqueue(json(200, """{"channel":"general","frequency":"asap","state":"active"}"""))
        assertEquals("asap", api.setFollow(member, "general", "asap"))
        assertEquals("""{"channel":"general","frequency":"asap"}""", server.takeRequest().body.readUtf8())
        server.enqueue(json(200, """{"channel":"general","state":"stopped"}"""))
        assertNull(api.setFollow(member, "general", "stop"))
        server.takeRequest()

        // no connection
        server.shutdown()
        try { api.follows(member); fail("must throw") } catch (e: ApiException) { assertEquals("network", e.code) }
    }

    @Test
    fun upload() = runBlocking {
        val api = BelfryApi(community(), http)
        val member = LocalSigner.random()
        val bytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(json(200, """{"url":"https://blossom.example.org/${sha256Hex(bytes)}.png","sha256":"${sha256Hex(bytes)}"}"""))
        assertEquals("https://blossom.example.org/${sha256Hex(bytes)}.png", api.upload(member, bytes, "dot.png", "image/png"))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/blossom/upload", req.path)
        assertEquals("image/png", req.getHeader("Content-Type"))
        val ev = Event.fromJson(parseJsonOrNull(String(B64.decode(req.getHeader("Authorization")!!.removePrefix("Nostr ")), Charsets.UTF_8))!!)!!
        assertTrue(ev.isValid())
        assertEquals(24242, ev.kind)
        assertEquals("Upload dot.png", ev.content)
        assertEquals(sha256Hex(bytes), ev.tag("x"))
        assertEquals("upload", ev.tag("t"))
    }

    @Test
    fun whoisAsksInBatchesAndKeepsOnlyKnownKinds() = runBlocking<Unit> {
        val me = LocalSigner.random()
        val a = "aa".repeat(32); val b = "bb".repeat(32)
        server.enqueue(json(200, """{"people":{"$a":"guest","$b":"member","cc":"member"}}"""))
        val api = BelfryApi(community(), http)
        assertEquals(mapOf(a to "guest", b to "member"), api.whois(me, listOf(a, b, a, "not-a-key")))
        val req = server.takeRequest()
        assertEquals("/api/v1/whois", req.path)
        assertEquals("POST", req.method)
        assertEquals("""{"pubkeys":["$a","$b"]}""", req.body.readUtf8())
        authEvent(req.getHeader("Authorization"))
        // 501 keys: two calls
        val many = (0 until 501).map { it.toString(16).padStart(64, '0') }
        server.enqueue(json(200, """{"people":{}}"""))
        server.enqueue(json(200, """{"people":{}}"""))
        assertEquals(emptyMap(), api.whois(me, many))
        assertEquals(3, server.requestCount)
        // an old belfry: 404
        server.enqueue(json(404, """{"detail":"Not Found"}"""))
        val e = assertFailsWith<ApiException> { api.whois(me, listOf(a)) }
        assertEquals(404, e.status)
        server.enqueue(json(403, """{"error":"not_here","message":"free text"}"""))
        val f = assertFailsWith<ApiException> { api.whois(me, listOf(a)) }
        assertEquals("not_here", f.code)
        assertFalse(f.message!!.contains("free text"))
    }

    @Test
    fun makeItMyAgentSaysWhyItIsRefused() = runBlocking<Unit> {
        val me = LocalSigner.random()
        val api = BelfryApi(community(), http)
        for ((code, text) in listOf(
            "taken" to "member’s agent already", "not_inviter" to "Only the member who invited", "limit" to "remove one first",
            "not_guest_agent" to "joined as a guest", "unknown" to "Nobody here", "refused" to "can’t become yours",
        )) {
            server.enqueue(json(409, """{"error":"$code","message":"server text"}"""))
            val e = assertFailsWith<ApiException> { api.setMyAgent(me, "aa".repeat(32), true) }
            assertEquals(code, e.code)
            assertTrue(e.message!!.contains(text), e.message)
        }
    }
}
