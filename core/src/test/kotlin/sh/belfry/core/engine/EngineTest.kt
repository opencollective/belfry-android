package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import sh.belfry.core.FakeRelay
import sh.belfry.core.Vectors
import sh.belfry.core.crypto.B64
import sh.belfry.core.dm.DmReader
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.dm.Report
import sh.belfry.core.model.Actions
import sh.belfry.core.model.GuestMode
import sh.belfry.core.model.Share
import sh.belfry.core.net.Community
import sh.belfry.core.net.InviteInfo
import sh.belfry.core.net.InviteLink
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.outbox.Outbox
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.store.MemoryStore
import sh.belfry.core.store.Store
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
import kotlin.test.fail

class EngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Only the local fakes may be reached: a test must never touch a real belfry. */
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val host = chain.request().url.host
        if (host != "localhost" && host != "127.0.0.1" && host != "::1" && host != "[::1]") throw IOException("no network in tests")
        chain.proceed(chain.request())
    }).build()
    private val fakes = ArrayList<FakeRelay>()
    private val servers = ArrayList<MockWebServer>()
    private val sessions = ArrayList<Session>()

    private fun fake(auth: Boolean = false): FakeRelay = FakeRelay(auth).also { it.expect(6); fakes.add(it) }
    private fun server(): MockWebServer = MockWebServer().also { it.start(); servers.add(it) }

    private fun community(g: FakeRelay, m: FakeRelay, blossom: String = "http://localhost:9"): Community =
        Community.defaults("example.org").copy(groupsRelay = g.url, mainRelay = m.url, blossom = blossom)

    private fun session(c: Community, store: Store, signer: LocalSigner, clock: () -> Long = { System.currentTimeMillis() / 1000 }): Session =
        Session(c, store, signer, http, scope, false, clock, null, ZoneOffset.UTC).also { sessions.add(it) }

    @AfterTest
    fun tearDown() {
        sessions.forEach { it.stop() }
        scope.cancel()
        fakes.forEach { try { it.server.shutdown() } catch (e: Exception) { /* sockets still open */ } }
        servers.forEach { try { it.shutdown() } catch (e: Exception) { } }
    }

    private suspend fun until(what: String, timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        val ok = withTimeoutOrNull(timeoutMs) { while (!cond()) delay(20); true }
        if (ok != true) fail("timed out waiting for: $what")
    }

    private fun texts(f: FakeRelay): List<String> = f.published.filter { it.kind == 9 }.map { it.content }

    @Test
    fun outboxSurvivesRestartAndCreatedAtGrows() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val store = MemoryStore()
        val me = LocalSigner.random()
        val c = community(g, m)
        val first = session(c, store, me, clock = { 1_800_000_000L })
        first.setOnline(false)
        first.start()
        first.postMessage("general", "one")
        first.postMessage("general", "two")
        first.postMessage("general", "three")
        val box = store.outbox()
        assertEquals(3, box.size)
        assertEquals(listOf(1_800_000_000L, 1_800_000_001L, 1_800_000_002L), box.map { it.createdAt })
        assertTrue(box.all { it.tags.last() == listOf("client", "belfry-android") })
        val shown = first.world.value.messages("general")
        assertEquals(listOf("one", "two", "three"), shown.map { it.text })
        assertTrue(shown.all { first.world.value.isPending(it.id) })
        assertEquals(3, first.status.value.queued)
        assertTrue(g.published.isEmpty())
        first.stop()

        // the app restarts: same store, a new session
        val second = session(c, store, me, clock = { 1_800_000_000L })
        assertEquals(3, second.world.value.messages("general").size)
        second.start()
        until("the three messages are out") { texts(g).size == 3 }
        assertEquals(listOf("one", "two", "three"), texts(g))
        until("the outbox is empty") { store.outbox().none { it.kind == 9 } }
        second.settle()
        val after = second.world.value.messages("general")
        assertEquals(3, after.size)
        assertFalse(after.any { second.world.value.isPending(it.id) })
        // a later message still gets a later time than everything this key wrote
        second.postMessage("general", "four")
        until("four is out") { texts(g).size == 4 }
        val times = g.published.filter { it.pubkey == me.pubkey }.map { it.createdAt }
        assertEquals(times.size, times.toSet().size, "created_at is strictly increasing per client")
    }

    @Test
    fun firstChannelIsCreatedOnceAndAccessIsIn() = runBlocking<Unit> {
        val g = fake(auth = true); val m = fake()
        val s = session(community(g, m), MemoryStore(), LocalSigner.random())
        assertEquals(AccessState.UNKNOWN, s.status.value.access)
        s.start()
        until("in") { s.status.value.access == AccessState.IN }
        until("general exists") { g.published.any { it.kind == 9002 } }
        assertEquals(1, g.published.count { it.kind == 9007 })
        assertEquals(listOf(listOf("h", "general"), listOf("name", "general"), listOf("about", "Everything about example.org. Say hi 👋"), listOf("client", "belfry-android")),
            g.published.first { it.kind == 9002 }.tags)
        // a reconnect brings a second EOSE: no second #general
        g.dropAll()
        until("reconnected") { g.opens >= 2 }
        delay(500)
        assertEquals(1, g.published.count { it.kind == 9007 })
    }

    @Test
    fun aStalledRelayKeepsItsOrderAndOtherRelaysContinue() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val store = MemoryStore()
        val s = session(community(g, m), store, LocalSigner.random())
        s.start()
        until("general exists") { g.published.any { it.kind == 9002 } && store.outbox().isEmpty() }
        g.refuse["B"] = "rate-limited: slow down"
        s.postMessage("general", "A")
        s.postMessage("general", "B")
        s.postMessage("general", "C")
        s.saveExtras(listOf("bikes"), Share.NONE, null) // goes to the community relay
        until("A is out, the profile too") { texts(g).contains("A") && m.published.any { it.kind == 30078 } }
        until("B was tried") { store.outbox().any { it.content == "B" && it.tries > 0 } }
        assertFalse(texts(g).contains("B"))
        assertFalse(texts(g).contains("C"), "C waits behind B: order is kept per relay")
        val b = store.outbox().first { it.content == "B" }
        assertEquals(OutboxStatus.QUEUED, b.status)
        assertEquals("rate-limited: slow down", b.error)
        g.refuse.clear()
        s.flush()
        until("B then C") { texts(g).takeLast(2) == listOf("B", "C") }
    }

    @Test
    fun permanentFailureThenRetryAndDuplicate() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val store = MemoryStore()
        val s = session(community(g, m), store, LocalSigner.random())
        s.start()
        until("general exists") { g.published.any { it.kind == 9002 } && store.outbox().isEmpty() }
        g.refuse["X"] = "blocked: not today"
        g.refuse["D"] = "duplicate: already have this event"
        val x = s.postMessage("general", "X")
        s.postMessage("general", "Y")
        s.postMessage("general", "D")
        until("Y is out although X was refused") { texts(g).contains("Y") }
        until("X failed, D gone") { store.outbox().size == 1 && store.outbox()[0].status == OutboxStatus.FAILED }
        val failed = store.outbox()[0]
        assertEquals(x, failed.localId)
        assertEquals("blocked: not today", failed.error)
        s.settle()
        assertEquals(1, s.status.value.failed)
        val w = s.world.value
        assertEquals(OutboxStatus.FAILED, w.pending(x)?.status)
        // the duplicate counts as sent: it is in the log, not pending
        val d = w.messages("general").first { it.text == "D" }
        assertFalse(w.isPending(d.id))
        // Retry
        g.refuse.clear()
        s.retry(x)
        until("X is out") { texts(g).contains("X") && store.outbox().isEmpty() }
    }

    @Test
    fun restrictedPublishMeansNoAccess() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val store = MemoryStore()
        val s = session(community(g, m), store, LocalSigner.random())
        s.start()
        until("in") { s.status.value.access == AccessState.IN && g.published.any { it.kind == 9002 } }
        g.refuse["R"] = "restricted: members only, get a pass"
        s.postMessage("general", "R")
        until("refused") { s.status.value.access == AccessState.REFUSED }
        assertNotNull(s.status.value.banner)
    }

    @Test
    fun attachmentsAreUploadedThenTagged() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val blossom = server()
        blossom.enqueue(MockResponse().setBody("{\"url\":\"https://files.example/abc.png\"}"))
        val store = MemoryStore()
        val me = LocalSigner.random()
        val s = session(community(g, m, blossom.url("/").toString().trimEnd('/')), store, me)
        s.start()
        until("general exists") { g.published.any { it.kind == 9002 } && store.outbox().isEmpty() }
        val bytes = byteArrayOf(1, 2, 3, 4)
        val id = s.postMessage("general", "Look", listOf(NewAttachment("photo.png", "image/png", bytes, 4, 2)))
        // before it is out, the picture is shown from the outbox
        val pending = s.world.value.pending(id)
        if (pending != null) assertEquals(bytes.toList(), s.blob(pending.attachments[0].blobKey)?.toList())
        until("out") { texts(g).any { it.startsWith("Look") } }
        val ev = g.published.first { it.kind == 9 && it.content.startsWith("Look") }
        assertEquals("Look\nhttps://files.example/abc.png", ev.content)
        val imeta = ev.tags.first { it[0] == "imeta" }
        assertEquals(listOf("imeta", "url https://files.example/abc.png", "m image/png", "size 4", "x 9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", "dim 4x2", "alt photo.png"), imeta)
        val req = blossom.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/upload", req.path)
        val auth = Event.fromJsonString(String(B64.decode(req.getHeader("Authorization")!!.removePrefix("Nostr "))))!!
        assertEquals(24242, auth.kind)
        assertEquals(me.pubkey, auth.pubkey)
        until("the blob is dropped") { store.outbox().isEmpty() }
        s.settle()
        assertEquals(1, s.world.value.messages("general").first { it.text == "Look" }.media.size)
    }

    @Test
    fun aFileTheStorageRefusesFailsItsMessageAndTheQueueGoesOn() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val blossom = server()
        blossom.enqueue(MockResponse().setResponseCode(413).setBody("{\"message\":\"too big\"}"))
        val store = MemoryStore()
        val s = session(community(g, m, blossom.url("/").toString().trimEnd('/')), store, LocalSigner.random())
        s.start()
        until("general exists") { g.published.any { it.kind == 9002 } && store.outbox().isEmpty() }
        val big = s.postMessage("general", "Huge", listOf(NewAttachment("huge.png", "image/png", byteArrayOf(9, 9, 9), 1, 1)))
        s.postMessage("general", "After")
        // the later message is not held back by the refused file
        until("After is out") { texts(g).contains("After") }
        until("Huge failed") { store.outbox().size == 1 && store.outbox()[0].status == OutboxStatus.FAILED }
        val failed = store.outbox()[0]
        assertEquals(big, failed.localId)
        assertEquals("upload failed (413)", failed.error)
        assertFalse(texts(g).any { it.startsWith("Huge") })
        s.settle()
        assertEquals("Not sent", Outbox.stateText(s.world.value.pending(big)!!))
        assertEquals(1, blossom.requestCount)
    }

    @Test
    fun directMessageRoundTrip() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val alice = LocalSigner.random(); val bob = LocalSigner.random()
        val c = community(g, m)
        val a = session(c, MemoryStore(), alice)
        a.start()
        val conv = a.sendDm("n:" + bob.pubkey, "Hi Bob, a private hello")
        assertEquals("n:" + bob.pubkey, conv)
        assertEquals(1, a.dms.value.thread(conv).size)
        until("both wraps are out") { m.published.count { it.kind == 1059 } == 2 }
        until("not pending any more") { !a.dms.value.thread(conv)[0].pending }
        assertEquals(setOf(alice.pubkey, bob.pubkey), m.published.filter { it.kind == 1059 }.map { it.tag("p") }.toSet())
        assertTrue(m.published.filter { it.kind == 1059 }.none { it.pubkey == alice.pubkey }, "wraps are signed by one-time keys")

        val b = session(c, MemoryStore(), bob)
        b.start()
        until("bob has it") { b.dms.value.threads.isNotEmpty() }
        val t = b.dms.value.threads.single()
        assertEquals("n:" + alice.pubkey, t.conv)
        assertEquals("Hi Bob, a private hello", t.last.text)
        assertFalse(t.last.mine)
        assertEquals(1, t.unread)
        assertEquals(1, b.dms.value.unreadTotal)
        b.markRead(t.conv)
        assertEquals(0, b.dms.value.unreadTotal)
        // convFor
        assertEquals("n:" + alice.pubkey, b.convFor(alice.pubkey))
        assertEquals("m:ana@elsewhere.example", b.convFor("Ana@Elsewhere.example"))
        assertNull(b.convFor("not an address"))
    }

    @Test
    fun emailThreadsRepliesAndDeliveryReports() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val bob = LocalSigner.random()
        val bridge = LocalSigner.random()
        val store = MemoryStore()
        store.set(Session.K_BRIDGE, bridge.pubkey)
        store.addEvents(listOf(bob.signNow(EventTemplate(0, "{\"name\":\"Bob\",\"nip05\":\"bob@example.org\"}", emptyList(), 1_700_000_000))))
        suspend fun mail(at: Long, subject: String, id: String, extra: String = ""): Event {
            val raw = "From: \"Ana\" <ana@example.org>\r\nTo: <bob@example.org>\r\nSubject: $subject\r\nMessage-ID: $id\r\n${extra}Content-Type: text/plain; charset=utf-8\r\n\r\nHi from e-mail\r\n"
            val rumor = GiftWrap.rumor(1301, bridge.pubkey, listOf(listOf("email-id", "in-$at"), listOf("mail-from", "ana@example.org"), listOf("p", bob.pubkey), listOf("subject", subject), listOf("belfry-auth", "pass")), raw, at)
            return GiftWrap.wrap(rumor, bob.pubkey, bridge)
        }
        m.stored.add(mail(1_800_000_000, "Hello", "<a1@example.org>"))
        m.stored.add(mail(1_800_000_100, "Something else entirely", "<a2@example.org>", "In-Reply-To: <a1@example.org>\r\n"))
        m.stored.add(mail(1_800_000_200, "Another subject", "<a3@example.org>"))
        val s = session(community(g, m), store, bob)
        s.start()
        until("three mails read") { s.dms.value.items.size == 3 }
        val snap = s.dms.value
        assertEquals(setOf("m:ana@example.org|hello", "m:ana@example.org|another%20subject"), snap.threads.map { it.conv }.toSet())
        val thread = snap.thread("m:ana@example.org|hello")
        assertEquals(2, thread.size)
        assertEquals("Hello", snap.threadName("m:ana@example.org|hello"))
        assertEquals("Ana", snap.convName("m:ana@example.org|hello", s.world.value))
        assertTrue(thread.all { it.verified == true && it.fromName == "Ana" && it.text == "Hi from e-mail" })

        // answering in the thread: Re: its subject, In-Reply-To its last mail
        val conv = s.sendDm("m:ana@example.org|hello", "See you **soon**")
        assertEquals("m:ana@example.org|hello", conv)
        until("wraps to the bridge and to me are out") { m.published.count { it.kind == 1059 } == 2 }
        val toBridge = m.published.first { it.kind == 1059 && it.tag("p") == bridge.pubkey }
        val rumor = GiftWrap.unwrap(toBridge, bridge)!!.rumor
        assertEquals(1301, rumor.kind)
        assertEquals(bob.pubkey, rumor.pubkey)
        assertEquals("bob@example.org", rumor.tag("mail-from"))
        assertEquals("ana@example.org", rumor.tag("rcpt-to"))
        assertEquals("Re: Something else entirely", rumor.tag("subject")) // Re: the last mail of the thread
        assertTrue(rumor.content.contains("In-Reply-To: <a2@example.org>"), rumor.content.take(400))
        assertTrue(rumor.content.contains("References: <a2@example.org>"))
        assertTrue(rumor.content.contains("From: \"Bob\" <bob@example.org>"))
        val emailId = rumor.tag("email-id")!!
        until("sent, waiting for the report") { s.dms.value.report(emailId)?.details?.startsWith("Sent") == true }
        assertEquals(3, s.dms.value.thread(conv).size)

        // the bridge reports
        val eph = LocalSigner.random()
        val body = buildJsonObject { put("status", JsonPrimitive("delivered")); put("smtp_response", JsonPrimitive("250 OK")) }.toString()
        m.stored.add(bridge.signNow(EventTemplate(7679, eph.nip44EncryptNow(bob.pubkey, body), listOf(listOf("r", emailId), listOf("ephemeral-pubkey", eph.pubkey), listOf("p", bob.pubkey)), 1_800_000_300)))
        s.checkReports()
        assertEquals(Report("delivered", "250 OK"), s.dms.value.report(emailId))
        assertEquals("Delivered", s.dms.value.stateLine(s.dms.value.thread(conv).first { it.mine }))

        // a new e-mail needs no thread yet and becomes its subject's thread
        val fresh = s.sendDm("m:carl@example.net", "First contact", "Tools for the shed")
        assertEquals("m:carl@example.net|tools%20for%20the%20shed", fresh)
    }

    @Test
    fun vectorMailConversation() {
        val v = Vectors.load("events.json")
        val case = v["gift_wraps"].arr()!!.map { it.obj()!! }.first { it.s("name") == "e-mail from the bridge to bob" }
        val bob = LocalSigner(case.s("recipient_secret")!!)
        val opened = runBlocking { GiftWrap.unwrap(Event.fromJson(case["wrap"]!!)!!, bob) }!!
        val d = DmReader.fromRumor(opened.rumor, bob.pubkey, case.s("bridge"), "https://blossom.example.org", emptyList())!!
        assertEquals(case["expect"].obj()!!.s("conv"), d.conv)
        assertEquals("ana@example.org", d.from)
        assertEquals("Ana", d.fromName)
        assertEquals("Hi from e-mail", d.text)
        assertEquals(true, d.verified)
        assertEquals("<a1@example.org>", d.messageId)
    }

    @Test
    fun guestJoinKeepsOneKeyPerInvite() = runBlocking<Unit> {
        val api = server()
        val token = "abcdefghijklmnopqrstuvwxyz012345"
        val c = Community.defaults("example.org").copy(channelInvites = api.url("/channel-invites").toString())
        val vault = MemoryVault()
        val on = Onboarding(vault, MemoryStore(), http, scope)
        val invitation = Invitation(InviteLink("example.org", token), c, InviteInfo("Example", "general", "general", "", "Maya", "open", 30))
        fun signerOf(header: String): String = Event.fromJsonString(String(B64.decode(header.removePrefix("Nostr "))))!!.pubkey

        api.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        assertFailsWith<OnboardingException> { on.joinAsNew(invitation, "Marcel") }
        val first = signerOf(api.takeRequest().getHeader("Authorization")!!)
        assertNotNull(vault.get(Onboarding.V_JOIN_KEY))

        // a second tap signs with the same key: the belfry answers with the same account
        api.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        assertFailsWith<OnboardingException> { on.joinAsNew(invitation, "Marcel") }
        val second = api.takeRequest()
        assertEquals(first, signerOf(second.getHeader("Authorization")!!))
        assertEquals("{\"name\":\"Marcel\"}", second.body.readUtf8())
        assertEquals("/channel-invites/$token/join", second.path)

        // 409: that key belongs to another account in the signer → once more with a fresh key, then give up
        api.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":\"client_key_taken\"}"))
        api.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":\"session_taken\"}"))
        val e = assertFailsWith<OnboardingException> { on.joinAsNew(invitation, "Marcel") }
        assertEquals("session_taken", e.code)
        assertEquals(first, signerOf(api.takeRequest().getHeader("Authorization")!!))
        val fresh = signerOf(api.takeRequest().getHeader("Authorization")!!)
        assertTrue(first != fresh)
        assertNull(vault.get(Onboarding.V_JOIN_KEY))
        api.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        assertFailsWith<OnboardingException> { on.joinAsNew(invitation, "Marcel") }
        assertTrue(first != signerOf(api.takeRequest().getHeader("Authorization")!!))
    }

    /** A belfry answering a guest link in guest mode (SPEC 19.26): by path, as the two calls race. */
    private fun guestModeBelfry(join: MockResponse, state: String = "open"): MockWebServer = server().also { srv ->
        srv.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path!!.endsWith("/join") -> join
                request.method == "GET" -> MockResponse().setBody("""{"community":"Example","channel":"garden","channel_name":"Garden","about":"","inviter":"Maya","state":"$state","days":30}""")
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
    }

    @Test
    fun guestModeOpensWithoutAName() = runBlocking<Unit> {
        val token = "abcdefghijklmnopqrstuvwxyz012345"
        val guestKey = "cd".repeat(32)
        val bunker = "bunker://${"ab".repeat(32)}?relay=wss://bunker.example.org&secret=xyz"
        val srv = guestModeBelfry(MockResponse().setBody("""{"channel":"garden","new":true,"guest":true,"reading":true,"bunker_url":"$bunker","pubkey":"$guestKey"}"""))
        val c = Community.defaults("example.org").copy(apiBase = srv.url("/api/v1").toString(), channelInvites = srv.url("/api/v1/channel-invites").toString())
        val vault = MemoryVault()
        val on = Onboarding(vault, MemoryStore(), http, scope, discovery = { c })
        var told: InviteInfo? = null
        val s = on.openInvite(InviteLink("example.org", token)) { told = it }.also { sessions.add(it) }
        assertEquals(guestKey, s.me) // the belfry said whose key: the signer was not asked
        assertEquals("garden", s.guest.value?.channel)
        assertTrue(s.reading)
        assertNull(vault.get(Onboarding.V_JOIN_KEY))
        assertNull(on.pendingInvite)
        val join = (1..srv.requestCount).map { srv.takeRequest() }.first { it.method == "POST" }
        assertEquals("{}", join.body.readUtf8())
        if (told != null) assertEquals("Garden", told!!.channelName)
        // kept with the saved session: reopened, still reading
        assertTrue(on.resume()!!.reading)

        // nothing is sent while reading: refused at the queue, and the Join sheet is asked for
        assertFalse(s.joinAsked.value)
        val refused = assertFailsWith<ActionException> { s.postMessage("garden", "hello") }
        assertEquals("guest_mode", refused.code)
        assertTrue(s.joinAsked.value)
        s.closeJoin()
        assertFailsWith<ActionException> { s.toggleReaction("garden", "ee".repeat(32), "ff".repeat(32), "👍") }
        assertFailsWith<ActionException> { s.createGuestLink("garden") }
        assertFailsWith<ActionException> { s.setFollow("garden", "asap") }
        s.markSeen("garden") // silently nothing
        assertTrue(s.store.outbox().isEmpty())

        // joining: name and e-mail are required in guest mode, checked before any request
        assertEquals("name_needed", assertFailsWith<GuestJoinException> { s.guestSave("ana@example.org", " ") }.code)
        assertEquals("invalid", assertFailsWith<GuestJoinException> { s.guestSave("ana", "Ana") }.code)
        srv.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/guest/save" -> MockResponse().setResponseCode(202).setBody("""{"sent":true}""")
                "/api/v1/guest/save/verify" ->
                    if (request.body.readUtf8().contains("\"code\":\"111111\"")) MockResponse().setResponseCode(422).setBody("""{"error":"wrong_code","message":"x"}""")
                    else MockResponse().setBody("""{"saved":true,"joined":true}""")
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        s.guestSave("ana@example.org", "  Ana   Lima ")
        assertEquals("wrong_code", assertFailsWith<GuestJoinException> { s.guestVerify("ana@example.org", "111111", "Ana Lima") }.code)
        assertTrue(s.reading)
        s.guestVerify("ana@example.org", "123456", "Ana Lima")
        assertFalse(s.reading)
        assertEquals(true, s.guest.value?.saved)
        assertFalse(on.resume()!!.reading)
    }

    @Test
    fun guestModeClosedLinkSaysWhy() = runBlocking<Unit> {
        val srv = guestModeBelfry(MockResponse().setResponseCode(410).setBody("""{"error":"closed","message":"<b>x</b>"}"""), state = "expired")
        val c = Community.defaults("example.org").copy(apiBase = srv.url("/api/v1").toString(), channelInvites = srv.url("/api/v1/channel-invites").toString())
        val on = Onboarding(MemoryVault(), MemoryStore(), http, scope, discovery = { c })
        val e = assertFailsWith<OnboardingException> { on.openInvite(InviteLink("example.org", "abcdefghijklmnopqrstuvwxyz012345")) }
        assertEquals("closed", e.code)
        assertEquals("This invite has expired. Ask for a new one.", e.message)
    }

    /** The demo answers like the belfry: 123456 is right, taken@example.org has an account (moved). */
    @Test
    fun guestModeInTheDemo() = runBlocking<Unit> {
        val on = Onboarding(MemoryVault(), MemoryStore(), http, scope, clock = { 1_800_000_000 }, zone = ZoneOffset.UTC)
        val s = on.demo().also { sessions.add(it) }
        s.demoGuestMode(Demo.GENERAL)
        assertTrue(s.reading)
        val taken = assertFailsWith<GuestJoinException> { s.guestVerify(GuestMode.DEMO_TAKEN, "123456", "Ana") }
        assertEquals("taken", taken.code)
        assertTrue(taken.moved)
        assertEquals("This address already has an account here.", taken.message)
        s.guestVerify("ana@example.org", GuestMode.DEMO_CODE, "Ana")
        s.settle()
        assertFalse(s.reading)
        // the join line now comes from the 9000 with the name
        assertTrue(s.world.value.guestJoins(Demo.GENERAL).any { it.pubkey == s.me })
    }

    @Test
    fun onboardingKeepsTheFlowAndTheInvite() = runBlocking<Unit> {
        val vault = MemoryVault()
        val on = Onboarding(vault, MemoryStore(), http, scope)
        assertFalse(on.hasSession)
        assertNull(on.resume())
        assertEquals("no_flow", assertFailsWith<OnboardingException> { on.finishSignIn("code", "state", null) }.code)
        val url = on.beginSignIn(Community.defaults("example.org"))
        assertTrue(url.startsWith("https://belfry.example.org/connect/authorize?"))
        assertTrue(url.contains("redirect_uri=sh.belfry.app%3A%2F%2Fconnect"))
        assertTrue(on.signInPending)
        // the redirect says the person cancelled: a fixed message, the flow is spent
        val state = Regex("[?&]state=([^&]+)").find(url)!!.groupValues[1]
        val e = assertFailsWith<OnboardingException> { on.finishSignIn(null, state, "access_denied") }
        assertEquals("Sign-in was cancelled.", e.message)
        assertFalse(on.signInPending)
        assertEquals("not_a_domain", assertFailsWith<OnboardingException> { on.discover("not a domain") }.code)
        assertEquals("not_found", assertFailsWith<OnboardingException> { on.discover("example.org") }.code)

        val link = InviteLink.parse("https://chat.example.org/join/abcdefghijklmnopqrstuvwxyz012345")!!
        on.pendingInvite = link
        assertEquals(link, Onboarding(vault, MemoryStore(), http, scope).pendingInvite)
        on.pendingInvite = null
        assertNull(on.pendingInvite)
    }

    @Test
    fun theDemoHasSomethingOnEveryScreen() = runBlocking<Unit> {
        val now = 1_794_000_000L // fixed, so the feed is the same every time
        val vault = MemoryVault()
        val store = MemoryStore()
        val on = Onboarding(vault, store, http, scope, { now }, ZoneOffset.UTC)
        val s = on.demo().also { sessions.add(it) }
        s.start()
        s.settle()
        val w = s.world.value
        assertTrue(s.localOnly)
        assertEquals(AccessState.IN, s.status.value.access)
        assertNull(s.status.value.banner)
        assertEquals(Demo.GENERAL, w.groups.first().id)
        assertTrue(w.groups.count { it.type.key == "channel" } >= 6, w.groups.map { it.id }.toString())
        assertEquals("general", w.group(Demo.GENERAL)?.name)
        assertTrue(w.canAdmin(Demo.GENERAL))
        assertTrue(w.isCommunityAdmin)
        val msgs = w.messages(Demo.GENERAL)
        assertTrue(msgs.size >= 15)
        assertTrue(msgs.any { it.edited != null }, "an edited message")
        assertTrue(msgs.any { it.media.isNotEmpty() }, "a photo")
        assertTrue(msgs.any { w.isAnnouncement(it, w.group(Demo.GENERAL)) }, "an announcement")
        assertTrue(msgs.any { it.created == "an event" } && msgs.any { it.created == "a pod" } && msgs.any { it.created == "a proposal" }, "cards")
        assertTrue(msgs.any { it.text.contains("nostr:npub1") }, "a mention")
        assertEquals(OutboxStatus.QUEUED, w.pending("local-demo-waiting")?.status)
        assertEquals(OutboxStatus.FAILED, w.pending("local-demo-refused")?.status)
        assertTrue(w.feedRows(Demo.GENERAL, null, emptySet()).isNotEmpty())
        assertTrue(w.feed(Demo.GENERAL).any { it is sh.belfry.core.model.FeedItem.Activity }, "a thread that resurfaces")
        assertTrue(w.feed(Demo.GENERAL).count { it is sh.belfry.core.model.FeedItem.Join } >= 3, "guest joins")
        assertTrue(w.threadsOf(Demo.GENERAL).values.any { it.count == 4 })
        assertTrue(w.reactions(Demo.GENERAL).isNotEmpty())
        val joins = w.guestJoins(Demo.GENERAL)
        assertTrue(joins.any { it.agent } && joins.any { it.unsaved } && joins.any { it.member } && joins.any { !it.unsaved && !it.member && !it.agent }, joins.toString())
        assertEquals(listOf("garden", "repairs"), w.pinnedTopics(Demo.GENERAL))
        assertTrue(w.hasAnnouncements(Demo.GENERAL))
        assertTrue(w.recentlyActive(Demo.GENERAL, now).size >= 8)
        assertEquals(1, w.dropLinks(Demo.GENERAL, now).size)
        assertEquals(2, w.pods(Demo.GENERAL).size)
        assertEquals(1, w.group(Demo.POD_CLOSED)?.applications?.size)
        assertTrue(w.group(Demo.POD_CLOSED)?.closed == true)
        assertEquals(3, w.calendar.size, w.calendar.map { it.title }.toString())
        val cal = w.calendar.single { !it.isNew }
        assertEquals("Spring dinner", cal.title)
        val swap = w.calendar.single { it.d == Demo.SWAP_D }
        assertEquals(listOf(Demo.GARDEN), swap.channels)
        assertEquals(Demo.SWAP_SLUG, swap.link)
        val repair = w.calendar.single { it.d == Demo.REPAIR_D }
        assertTrue(w.me in repair.hosts)
        assertEquals(listOf(Demo.GENERAL), repair.channels)
        assertEquals(3, cal.going)
        assertNotNull(w.nextEvent(Demo.GENERAL, now))
        val p = w.proposals.single()
        assertTrue(p.votingNow)
        assertEquals(mapOf("yes" to 3, "no" to 1, "abstain" to 1), p.tally)
        assertTrue(w.isInbox(w.group(Demo.HELLO)))
        assertEquals(1, w.inboxStates(Demo.HELLO).size)
        assertTrue(w.messages(Demo.HELLO).count { it.special is sh.belfry.core.model.Special.InboxCard } == 2)
        assertTrue(w.messages(Demo.GENERAL).any { it.special is sh.belfry.core.model.Special.MemberMail })
        assertTrue(w.extras.size >= 9)
        assertTrue(w.allTags.isNotEmpty())
        assertTrue(w.search("ladder").isNotEmpty())
        assertTrue(w.tasks(Demo.KITCHEN).isNotEmpty())
        assertEquals("alex.morgan@belfry.example", w.myAddress)

        val dms = s.dms.value
        assertEquals(5, dms.threads.size, dms.threads.map { it.conv }.toString())
        assertEquals(2, dms.unreadTotal)
        assertEquals(2, dms.threads.count { it.isMail })
        val mail = dms.threads.first { it.conv == "m:ana@example.org|visiting%20next%20week" }
        assertEquals(2, mail.count)
        assertEquals("Delivered", dms.stateLine(dms.thread(mail.conv).last()))
        assertEquals("Maya Okafor", dms.convName("n:" + Demo.maya.pubkey, w))

        // everything works offline: a message is "published" on the device
        val id = s.postMessage(Demo.GENERAL, "Hello from the demo")
        until("the demo publishes locally") { store.outbox().none { it.localId == id } }
        s.settle()
        val mine = s.world.value.messages(Demo.GENERAL).first { it.text == "Hello from the demo" }
        assertFalse(s.world.value.isPending(mine.id))
        assertNotNull(s.world.value.pending("local-demo-waiting"), "the seeded waiting item stays")
        // a new channel gets its metadata without a relay
        val ch = s.createChannel("Bike shed", "Pumps and patches")
        until("the channel shows") { s.settle(); s.world.value.group(ch)?.name == "Bike shed" }
        assertTrue(s.createGuestLink(Demo.GENERAL, 50, 7).url.startsWith("https://chat.belfry.example/join/"))
        assertEquals(1, s.searchPlaces("bru").places.size)
        assertEquals("monthly", s.setFollow(Demo.GENERAL, "monthly"))
        assertEquals(mapOf(Demo.GENERAL to "monthly"), s.follows().byChannel)
        s.sendDm("n:" + Demo.maya.pubkey, "Got it, thanks")
        assertEquals("Got it, thanks", s.dms.value.thread("n:" + Demo.maya.pubkey).last().text)

        // the demo reopens from the saved record
        val again = Onboarding(vault, store, http, scope, { now }, ZoneOffset.UTC).resume()
        assertNotNull(again)
        sessions.add(again)
        assertTrue(again.localOnly)
        assertEquals(s.me, again.me)
        // sign-out wipes
        assertNull(on.signOut(s))
        assertTrue(store.allEvents().isEmpty())
        assertFalse(on.hasSession)
    }

    @Test
    fun actionsUseTheClientTag() {
        assertEquals(listOf("client", "belfry-android"), Actions.CLIENT_TAG)
        assertTrue(Session.KINDS_GROUPS.contains(1010))
        val o: JsonObject? = parseJsonOrNull("{}").obj()
        assertNotNull(o)
    }
}
