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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import sh.belfry.core.FakeRelay
import sh.belfry.core.model.GuestMode
import sh.belfry.core.model.Share
import sh.belfry.core.net.Access
import sh.belfry.core.net.BelfryApi
import sh.belfry.core.net.Community
import sh.belfry.core.net.Reopen
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.outbox.ErrorClass
import sh.belfry.core.outbox.Outbox
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.SendOutcome
import sh.belfry.core.outbox.Target
import sh.belfry.core.store.MemoryStore
import sh.belfry.core.store.Store
import java.io.IOException
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SPEC 19.77 (a reading guest posts in a topic reached by a card, and only there) and SPEC 19.79
 * (the community relay's "not in whitelist", refusals that are not about membership, the return to
 * the foreground), against the demo and local fakes only.
 */
class CardsGuestEngineTest {
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

    private fun fake(): FakeRelay = FakeRelay(false).also { it.expect(8); fakes.add(it) }
    private fun community(g: FakeRelay, m: FakeRelay): Community =
        Community.defaults("example.org").copy(groupsRelay = g.url, mainRelay = m.url, blossom = "http://localhost:9")
    private fun session(c: Community, store: Store, clock: () -> Long = { System.currentTimeMillis() / 1000 }): Session =
        Session(c, store, LocalSigner.random(), http, scope, false, clock, null, ZoneOffset.UTC).also { sessions.add(it) }

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

    private suspend fun demo(): Session =
        Onboarding(MemoryVault(), MemoryStore(), http, scope, clock = { 1_800_000_000 }, zone = ZoneOffset.UTC).demo().also { sessions.add(it); it.settle() }

    // ---- SPEC 19.77 ------------------------------------------------------------------------------

    @Test
    fun aTopicCardsGuestPostsThereAndOnlyThere() = runBlocking<Unit> {
        val s = demo()
        s.demoGuestMode(Demo.GARDEN, card = "topic")
        assertTrue(s.reading)
        assertTrue(s.guestTopic(Demo.GARDEN))
        assertFalse(s.guestTopic(Demo.GENERAL))
        // everything of mine, sent or waiting (the demo's outbox sends by itself: never count the outbox alone)
        suspend fun mine(): Int { s.flush(); s.settle(); return s.world.value.all.count { it.pubkey == s.me } }
        val before = mine()

        // the composer's message: through the outbox like any other, kind 9 with [[h, T]] and the client tag only
        s.postGuestMessage(Demo.GARDEN, "  Spare cordless drill, ask me at no. 7 ")
        assertFalse(s.joinAsked.value, "the Join sheet does not open for it")
        assertEquals(before + 1, mine())
        val sent = s.world.value.messages(Demo.GARDEN).last { it.pubkey == s.me && it.text == "Spare cordless drill, ask me at no. 7" }
        assertEquals(9, sent.ev.kind)
        assertEquals(listOf(listOf("h", Demo.GARDEN), sh.belfry.core.model.Actions.CLIENT_TAG), sent.ev.tags)
        assertTrue(s.reading, "still a reading guest: nothing was asked of them")

        // its photos go with it; a file that is not a picture does not
        s.postGuestMessage(Demo.GARDEN, "", listOf(NewAttachment("drill.jpg", "image/jpeg", byteArrayOf(1, 2, 3))))
        assertEquals("guest_mode", assertFailsWith<ActionException> { s.postGuestMessage(Demo.GARDEN, "the manual", listOf(NewAttachment("manual.pdf", "application/pdf", byteArrayOf(1))) ) }.code)
        assertTrue(s.joinAsked.value)
        s.closeJoin()

        // everything else is refused as before and opens the Join sheet
        suspend fun refused(what: String, block: suspend () -> Unit) {
            val n = mine()
            val e = assertFailsWith<ActionException>(what) { block() }
            assertEquals(GuestMode.REFUSED, e.code, what)
            assertEquals(GuestMode.REFUSED_TEXT, e.message, what)
            assertTrue(s.joinAsked.value, what)
            assertEquals(n, mine(), what)
            s.closeJoin()
        }
        refused("another channel") { s.postGuestMessage(Demo.GENERAL, "hello") }
        refused("the usual composer elsewhere") { s.postMessage(Demo.GENERAL, "hello") }
        refused("a hashtag topic in the topic") { s.postMessage(Demo.GARDEN, "about #seeds") }
        val m = s.world.value.messages(Demo.GARDEN).first { it.pubkey != s.me }
        refused("a reaction") { s.toggleReaction(Demo.GARDEN, m.id, m.pubkey, "👍") }
        refused("a reply in a thread") { s.replyInThread(Demo.GARDEN, m.ev, "me too") }
        refused("two events at once") { s.send(listOf(sh.belfry.core.model.GuestTopic.post(Demo.GARDEN, "a"), sh.belfry.core.model.GuestTopic.post(Demo.GARDEN, "b"))) }
    }

    @Test
    fun aGroupCardAnOrdinaryLinkAndAJoinedGuestGetNoGuestPost() = runBlocking<Unit> {
        // a group card: the Join bar, nothing is sent
        val group = demo()
        group.demoGuestMode(Demo.GARDEN, card = "group")
        assertFalse(group.guestTopic(Demo.GARDEN))
        assertEquals(GuestMode.REFUSED, assertFailsWith<ActionException> { group.postGuestMessage(Demo.GARDEN, "hello") }.code)
        assertTrue(group.joinAsked.value)

        // an ordinary guest link (no card)
        val link = demo()
        link.demoGuestMode(Demo.GARDEN)
        assertFalse(link.guestTopic(Demo.GARDEN))
        assertEquals(GuestMode.REFUSED, assertFailsWith<ActionException> { link.postGuestMessage(Demo.GARDEN, "hello") }.code)
        // anything that is not "group" or "topic" is no card
        link.demoGuestMode(Demo.GARDEN, card = "TOPIC")
        assertNull(link.guest.value?.card)

        // a topic card's guest who saved their account: an ordinary guest with the usual composer
        val saved = demo()
        saved.demoGuestMode(Demo.GARDEN, card = "topic")
        saved.guestVerify("ana@example.org", GuestMode.DEMO_CODE, "Ana")
        saved.settle()
        assertFalse(saved.reading)
        assertFalse(saved.guestTopic(Demo.GARDEN))
        saved.postMessage(Demo.GARDEN, "now with #seeds") // not refused
    }

    @Test
    fun theSummaryGoesOnlyWhenATopicCardsGuestFollowsIt() = runBlocking<Unit> {
        // a topic card's guest joining with a name: what was chosen (anything else than monthly is weekly)
        val monthly = demo()
        monthly.demoGuestMode(Demo.GARDEN, card = "topic")
        monthly.guestVerify("ana@example.org", GuestMode.DEMO_CODE, "Ana", summary = "monthly")
        assertEquals("monthly", monthly.summarySent)
        val weekly = demo()
        weekly.demoGuestMode(Demo.GARDEN, card = "topic")
        weekly.guestVerify("ana@example.org", GuestMode.DEMO_CODE, "Ana", summary = "weekly")
        assertEquals("weekly", weekly.summarySent)
        // nowhere else, whatever a screen passed: a group card, an ordinary link, a save without a name
        val group = demo()
        group.demoGuestMode(Demo.GARDEN, card = "group")
        group.guestVerify("ana@example.org", GuestMode.DEMO_CODE, "Ana", summary = "monthly")
        assertNull(group.summarySent)
        val link = demo()
        link.demoGuestMode(Demo.GARDEN)
        link.guestVerify("ana@example.org", GuestMode.DEMO_CODE, "Ana", summary = "monthly")
        assertNull(link.summarySent)
        val noName = demo()
        noName.demoGuestMode(Demo.GARDEN, card = "topic")
        try { noName.guestVerify("ana@example.org", GuestMode.DEMO_CODE, null, summary = "monthly") } catch (e: GuestJoinException) { /* a name may be asked for first */ }
        assertNull(noName.summarySent)
        // not asked: not sent
        val plain = demo()
        plain.demoGuestMode(Demo.GARDEN, card = "topic")
        plain.guestVerify("ana@example.org", GuestMode.DEMO_CODE, "Ana")
        assertNull(plain.summarySent)
        // a weekly follow in the demo
        val member = demo()
        assertEquals("weekly", member.setFollow(Demo.GENERAL, "weekly"))
        assertEquals(mapOf(Demo.GENERAL to "weekly"), member.follows().byChannel)
    }

    @Test
    fun theCardIsReadFromTheBelfryAndKeptWithTheSession() = runBlocking<Unit> {
        val server = MockWebServer().also { it.start(); servers.add(it) }
        val base = server.url("/api/v1").toString()
        val c = Community.defaults("example.org").copy(apiBase = base, channelInvites = "$base/channel-invites")
        val api = BelfryApi(c, http)
        fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
        val token = "t".repeat(24)
        val bunker = "bunker://" + "ab".repeat(32) + "?relay=wss%3A%2F%2Fbunker.example.org"

        for ((sent, read) in listOf("\"card\":\"topic\"," to "topic", "\"card\":\"group\"," to "group", "\"card\":\"event\"," to null, "\"card\":7," to null, "" to null)) {
            server.enqueue(json("""{$sent"community":"Example","channel":"tool-library","channel_name":"tool-library","state":"open","days":30}"""))
            assertEquals(read, api.inviteInfo(token).card, sent)
            server.takeRequest()
            server.enqueue(json("""{$sent"bunker_url":"$bunker","channel":"tool-library","reading":true}"""))
            val joined = api.joinAsNew(token, null, LocalSigner.random().secretHex)
            assertEquals(read, joined.card, sent)
            assertTrue(joined.reading)
            server.takeRequest()
        }

        // following the topic: the summary goes in the body of /guest/save/verify, only when given
        val guest = LocalSigner.random()
        server.enqueue(json("""{"saved":true,"joined":true}"""))
        assertTrue(api.guestVerify(guest, "ana@example.org", "123456", "Ana Lima", summary = "monthly").joined)
        var sent = server.takeRequest()
        assertEquals("/api/v1/guest/save/verify", sent.path)
        assertEquals("""{"email":"ana@example.org","code":"123456","name":"Ana Lima","summary":"monthly"}""", sent.body.readUtf8())
        server.enqueue(json("""{"saved":true,"joined":true}"""))
        api.guestVerify(guest, "ana@example.org", "123456", "Ana Lima")
        sent = server.takeRequest()
        assertFalse(sent.body.readUtf8().contains("summary"))

        // an e-mail follow may be weekly (the server, 2026-11-23)
        val c2 = c.copy(follows = "$base/me/follows")
        val api2 = BelfryApi(c2, http)
        server.enqueue(json("""{"email":"ada@example.org","follows":[{"channel":"general","frequency":"weekly","state":"active"},{"channel":"garden","frequency":"daily","state":"active"}]}"""))
        assertEquals(mapOf("general" to "weekly"), api2.follows(guest).byChannel)
        server.takeRequest()
        server.enqueue(json("""{"channel":"general","frequency":"weekly","state":"active"}"""))
        assertEquals("weekly", api2.setFollow(guest, "general", "weekly"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"frequency\":\"weekly\""))

        // kept between launches (the app opens offline as the same guest)
        val key = LocalSigner.random()
        val kept = SavedSession(c, bunker, key.secretHex, key.pubkey, guest = GuestInfo("tool-library", saved = false, reading = true, card = "topic"))
        assertEquals("topic", SavedSession.fromJsonString(kept.toJsonString())?.guest?.card)
        val plain = SavedSession(c, bunker, key.secretHex, key.pubkey, guest = GuestInfo("general", saved = false, reading = true))
        assertFalse(plain.toJsonString().contains("\"card\""))
        assertNull(SavedSession.fromJsonString(plain.toJsonString())?.guest?.card)
        assertNull(SavedSession.fromJsonString(kept.toJsonString().replace("\"card\":\"topic\"", "\"card\":\"anything\""))?.guest?.card)
    }

    // ---- SPEC 19.79: the rules -------------------------------------------------------------------

    private val notListed = "restricted: pubkey not in whitelist"

    @Test
    fun onlyAnAnswerAboutMembershipCounts() {
        for (r in listOf("restricted: no pass", "blocked: gone", "restricted: members only, get a pass", "restricted: you are not a member", notListed, "this relay is invite only", "not allowed here"))
            assertTrue(Access.aboutMembership(r), r)
        for (r in listOf(
            "restricted: connection rate limit exceeded", "rate-limited: slow down", "restricted: too many requests", "blocked: try again later",
            "error: only members may read", "restricted: connection closed", "timeout: sign-in to the relay", "error: not now", "", "auth-required: sign in first",
            "restricted: server busy", "restricted: overloaded", "restricted: network unreachable", "restricted: timed out",
        )) assertFalse(Access.aboutMembership(r), r)
        assertTrue(Access.rateLimited("restricted: connection rate limit exceeded"))
        assertTrue(Access.rateLimited("rate-limited: slow down"))
        assertTrue(Access.rateLimited("error: Too Many requests"))
        assertFalse(Access.rateLimited(notListed))
        assertFalse(Access.rateLimited("restricted: no pass"))
    }

    @Test
    fun aClosedSubscriptionIsOpenedAgainOrNot() {
        val main = true; val groups = false
        // the community relay's whitelist: backed off, exactly for that reason
        assertEquals(Reopen.How.BACKED_OFF, Reopen.how(main, notListed))
        assertEquals(Reopen.How.BACKED_OFF, Reopen.how(main, "Restricted: pubkey not in Whitelist"))
        // the groups relay saying the same: a refusal about membership, as before (the register is asked, nothing is opened again)
        assertEquals(Reopen.How.NEVER, Reopen.how(groups, notListed))
        // every other restricted stays as it is, on both relays
        for (r in listOf("restricted: no pass", "blocked: gone", "restricted: members only", "blocked: not on the whitelist")) {
            assertEquals(Reopen.How.NEVER, Reopen.how(groups, r), r)
        }
        assertEquals(Reopen.How.NEVER, Reopen.how(main, "restricted: no pass"))
        assertEquals(Reopen.How.NEVER, Reopen.how(main, "blocked: gone"))
        assertEquals(Reopen.How.SOON, Reopen.how(main, "whitelist: restricted"), "the reason must start with restricted: this is an ordinary close")
        // NIP-42 runs and sends the subscriptions again
        assertEquals(Reopen.How.NEVER, Reopen.how(main, "auth-required: sign in first"))
        assertEquals(Reopen.How.NEVER, Reopen.how(groups, "auth-required: sign in first"))
        // a limit is never a refusal about membership: again, backed off
        for (r in listOf("restricted: connection rate limit exceeded", "rate-limited: slow down", "error: too many subscriptions", "error: only members may read")) {
            assertEquals(Reopen.How.BACKED_OFF, Reopen.how(main, r), r)
            assertEquals(Reopen.How.BACKED_OFF, Reopen.how(groups, r), r)
        }
        // an ordinary close: soon
        for (r in listOf("error: not now", "", "closed by the relay", "shutting down")) {
            assertEquals(Reopen.How.SOON, Reopen.how(main, r), r)
            assertEquals(Reopen.How.SOON, Reopen.how(groups, r), r)
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (0..6).map { Reopen.soonMs(it) })
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 120_000L, 120_000L), (0..7).map { Reopen.backedOffMs(it) })
    }

    @Test
    fun aSendTheCommunityRelayDoesNotListYetIsTriedAgainForTenMinutes() = runBlocking<Unit> {
        fun item(target: Target, listedAt: Long = 0) = OutboxItem("a", target, 30078, "", emptyList(), 1_000, notListedAt = listedAt)
        val now = 5_000L
        // without the item, and for every other relay or reason: as before
        assertEquals(ErrorClass.FAILED, Outbox.classify(notListed))
        assertEquals(ErrorClass.FAILED, Outbox.classify(notListed, item(Target.GROUPS), now))
        assertEquals(ErrorClass.FAILED, Outbox.classify(notListed, item(Target.API), now))
        assertEquals(ErrorClass.FAILED, Outbox.classify("restricted: no pass", item(Target.MAIN), now))
        assertEquals(ErrorClass.FAILED, Outbox.classify("blocked: pubkey not in whitelist", item(Target.MAIN), now))
        assertEquals(ErrorClass.FAILED, Outbox.classify("invalid: whitelist", item(Target.MAIN), now))
        assertEquals(ErrorClass.DUPLICATE, Outbox.classify("duplicate: have it", item(Target.MAIN), now))
        assertEquals(ErrorClass.RETRY, Outbox.classify("rate-limited: slow down", item(Target.MAIN), now))
        // the community relay: a retry from the first refusal, for 10 minutes
        assertEquals(ErrorClass.RETRY, Outbox.classify(notListed, item(Target.MAIN), now))
        assertEquals(ErrorClass.RETRY, Outbox.classify("RESTRICTED: Pubkey not in Whitelist", item(Target.MAIN), now))
        assertEquals(ErrorClass.RETRY, Outbox.classify(notListed, item(Target.MAIN, listedAt = now - 599), now))
        assertEquals(ErrorClass.FAILED, Outbox.classify(notListed, item(Target.MAIN, listedAt = now - 600), now))
        assertEquals(ErrorClass.FAILED, Outbox.classify(notListed, item(Target.MAIN, listedAt = now - 4_000), now))

        // one pass: kept queued, its 10 minutes start, its lane waits; another relay's item goes
        val wrap = item(Target.MAIN)
        val other = OutboxItem("g", Target.GROUPS, 9, "hello", listOf(listOf("h", "general")), 1_001)
        var r = Outbox.run(listOf(wrap, other), now) { if (it.localId == "a") SendOutcome.Error(notListed) else SendOutcome.Ok() }
        assertEquals(listOf("g"), r.sent.map { it.item.localId })
        assertTrue(r.failed.isEmpty())
        var left = r.items.single()
        assertEquals(OutboxStatus.QUEUED, left.status)
        assertEquals(1, left.tries)
        assertEquals(now, left.notListedAt)
        assertEquals(notListed, left.error)
        assertEquals(setOf(Target.MAIN), r.stalled, "run again soon")
        // kept across a restart
        assertEquals(now, OutboxItem.fromJson(left.toJson()).notListedAt)
        assertEquals(0, OutboxItem.fromJson(other.toJson()).notListedAt)
        // refused again 5 minutes later: the first time stays the mark
        r = Outbox.run(listOf(left), now + 300) { SendOutcome.Error(notListed) }
        left = r.items.single()
        assertEquals(OutboxStatus.QUEUED, left.status)
        assertEquals(now, left.notListedAt)
        // listed meanwhile: it goes
        assertEquals(listOf("a"), Outbox.run(listOf(left), now + 400) { SendOutcome.Ok() }.sent.map { it.item.localId })
        // still refused after 10 minutes: refused for good, with the relay's reason kept for the log
        r = Outbox.run(listOf(left), now + 600) { SendOutcome.Error(notListed) }
        assertEquals(OutboxStatus.FAILED, r.failed.single().status)
        assertEquals(notListed, r.failed.single().error)
        // the groups relay saying the same thing: refused at once, as before
        r = Outbox.run(listOf(other), now) { SendOutcome.Error(notListed) }
        assertEquals(OutboxStatus.FAILED, r.failed.single().status)
        assertEquals(0, r.failed.single().notListedAt)
    }

    // ---- SPEC 19.79: against local fake relays ---------------------------------------------------

    @Test
    fun theCommunityRelaysWhitelistRefusalIsTriedAgainAndTheGroupsRelaysIsNot() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val store = MemoryStore()
        val s = session(community(g, m), store)
        s.start()
        until("in") { s.status.value.access == AccessState.IN && g.published.any { it.kind == 9002 } && store.outbox().isEmpty() }
        m.refuseEvent = { notListed }
        s.saveExtras(listOf("bikes"), Share.NONE, null) // goes to the community relay
        until("tried and kept") { store.outbox().any { it.target == Target.MAIN && it.kind == 30078 && it.tries > 0 } }
        val kept = store.outbox().first { it.target == Target.MAIN && it.kind == 30078 }
        assertEquals(OutboxStatus.QUEUED, kept.status)
        assertEquals(notListed, kept.error)
        assertTrue(kept.notListedAt > 0)
        assertEquals(AccessState.IN, s.status.value.access, "the community relay's word changes nothing about access")
        // listed within seconds: the same item goes out
        m.refuseEvent = null
        s.flush()
        until("out") { m.published.any { it.kind == 30078 } && store.outbox().none { it.localId == kept.localId } }

        // the groups relay refusing with those words: refused for good, the line (never the screen without the register)
        g.refuse["W"] = notListed
        s.postMessage("general", "W")
        until("refused") { store.outbox().any { it.content == "W" && it.status == OutboxStatus.FAILED } }
        until("the relay's refusal is noted") { s.status.value.access == AccessState.RELAY_REFUSED }
    }

    @Test
    fun aSubscriptionTheCommunityRelayClosesForTheWhitelistIsOpenedAgain() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        m.closeReqs = true
        m.closeReason = notListed
        val s = session(community(g, m), MemoryStore())
        s.start()
        until("asked once") { m.reqs.count { it == "main" } >= 1 }
        val first = m.reqs.count { it == "main" }
        // 2 s later it asks again (before this rule: never)
        until("asked again", 8_000) { m.reqs.count { it == "main" } > first }
        assertEquals(1, m.opens, "on the same socket")
        assertTrue(s.status.value.access != AccessState.REFUSED && s.status.value.access != AccessState.RELAY_REFUSED, "no refusal line for the community relay")
    }

    @Test
    fun anyOtherRestrictedCloseOfTheCommunityRelayStaysClosed() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        m.closeReqs = true
        m.closeReason = "restricted: no pass"
        val s = session(community(g, m), MemoryStore())
        s.start()
        until("asked once") { m.reqs.count { it == "main" } >= 1 }
        val first = m.reqs.count { it == "main" }
        delay(3_000)
        assertEquals(first, m.reqs.count { it == "main" }, "as before: not opened again")
    }

    @Test
    fun aRateLimitOnTheGroupsRelayIsNoRefusal() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val store = MemoryStore()
        val s = session(community(g, m), store)
        s.start()
        until("in") { s.status.value.access == AccessState.IN && g.published.any { it.kind == 9002 } && store.outbox().isEmpty() }
        // "restricted: connection rate limit exceeded" (Xavier, 2026-11-23): tried again, and never "the relay refused you"
        g.refuse["R"] = "restricted: connection rate limit exceeded"
        s.postMessage("general", "R")
        until("the send was refused (the outbox's own rule for restricted: is unchanged)") { store.outbox().any { it.content == "R" && it.status == OutboxStatus.FAILED } }
        delay(400)
        assertEquals(AccessState.IN, s.status.value.access)
    }

    @Test
    fun backInTheForegroundEachRelayIsAskedAgainFromTheLastEvent() = runBlocking<Unit> {
        val g = fake(); val m = fake()
        val store = MemoryStore()
        val s = session(community(g, m), store)
        s.start()
        until("in") { s.status.value.access == AccessState.IN && g.published.any { it.kind == 9002 } && store.outbox().isEmpty() }
        until("both asked") { g.reqs.contains("main") && m.reqs.contains("main") }
        delay(300)
        val gBefore = g.reqs.count { it == "main" }
        val mBefore = m.reqs.count { it == "main" }
        val opens = g.opens + m.opens
        s.background()
        s.wake()
        until("a fresh subscription on each living socket") { g.reqs.count { it == "main" } > gBefore && m.reqs.count { it == "main" } > mBefore }
        assertEquals(opens, g.opens + m.opens, "the sockets were alive: none was opened again")
        assertTrue(s.diagnostics().contains("caught up on return"))
        // at once again: once per 2 s
        val gAfter = g.reqs.count { it == "main" }
        s.wake()
        delay(500)
        assertEquals(gAfter, g.reqs.count { it == "main" })
    }
}
