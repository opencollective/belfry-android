package sh.belfry.core.sealed

import kotlinx.coroutines.runBlocking
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine against a fake relay: two devices of one account (Alice's phone and laptop) and a
 * correspondent (Bob), as the lead asked after the web's run found a sender sealing to a stale
 * head and two devices overwriting each other's name record.
 */
class SealedEngineTest {
    /** The community relay: replaceable 30078 per (pubkey, d); SILENT = no complete answer (before AUTH, or no EOSE). */
    class FakeRelay {
        val events = ArrayList<Event>()
        var silent = false
        fun add(e: Event) {
            if (e.kind == 30078) events.removeAll { it.kind == 30078 && it.pubkey == e.pubkey && it.tag("d") == e.tag("d") }
            events.add(e)
        }
        fun query(q: Query): SealedEngine.Answer {
            if (silent) return SealedEngine.Answer(emptyList(), false)
            val out = events.filter { e ->
                e.kind in q.kinds &&
                    (q.authors == null || e.pubkey in q.authors) &&
                    (q.p == null || e.tags.any { it.size >= 2 && it[0] == "p" && it[1] in q.p }) &&
                    (q.d == null || e.tag("d") in q.d)
            }
            return SealedEngine.Answer(out, true)
        }
        fun to(device: String) = events.filter { it.kind == 1059 && it.tag("p") == device }
    }

    /** The belfry's session list per account. */
    class Server {
        val sessions = HashMap<String, MutableList<SessionRow>>()
        var odd = false
        fun live(account: String, device: String, name: String, chat: Boolean = true) {
            sessions.getOrPut(account) { ArrayList() }.add(SessionRow(device, chat, now, name, "Belfry for Android"))
        }
    }

    companion object { var now = 1_795_000_000L }

    private val relay = FakeRelay()
    private val server = Server()

    private inner class Ports(val account: LocalSigner) : SealedPorts {
        var saved: Map<String, String> = emptyMap()
        val revoked = ArrayList<String>()
        override suspend fun query(q: Query) = relay.query(q)
        override suspend fun publish(e: Event) = relay.add(e)
        override suspend fun signAsAccount(t: EventTemplate): Event = account.signNow(t)
        override suspend fun sessions(): List<SessionRow>? = if (server.odd) null else server.sessions[account.pubkey]?.toList() ?: emptyList()
        override suspend fun revoke(clientPubkey: String) { revoked.add(clientPubkey); server.sessions[account.pubkey]?.removeAll { it.clientPubkey == clientPubkey } }
        override fun loadKeys() = saved
        override fun saveKeys(keys: Map<String, String>) { saved = keys }
    }

    private val alice = LocalSigner.random()
    private val bob = LocalSigner.random()
    private val alicePhone = LocalSigner.random()
    private val aliceLaptop = LocalSigner.random()
    private val bobPhone = LocalSigner.random()

    private fun engine(account: LocalSigner, device: LocalSigner, guest: Boolean = false) =
        SealedEngine(Ports(account), device, account.pubkey, clock = { now }, guest = guest, removalJitter = 0)

    private fun conv(a: String, b: String) = "n:" + listOf(a, b).sorted().joinToString(",")

    /** Alice with phone and laptop both in her head, Bob with his phone, everyone ready. */
    private fun world(): Triple<SealedEngine, SealedEngine, SealedEngine> = runBlocking {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        server.live(bob.pubkey, bobPhone.pubkey, "Bob's phone")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        val laptop = engine(alice, aliceLaptop); laptop.start("Firefox on Linux", "computer")
        val req = relay.events.last { it.kind == Sealing.REQUEST }
        assertIs<SealedEngine.OnRequest.Ask>(phone.onRequest(req))
        phone.allow(aliceLaptop.pubkey)
        relay.to(aliceLaptop.pubkey).forEach { laptop.onDeviceWrap(it) }
        phone.checkReady()
        val b = engine(bob, bobPhone); b.start("Bob's phone", "phone"); b.checkReady()
        Triple(phone, laptop, b)
    }

    @Test fun firstDevice() = runBlocking {
        val phone = engine(alice, alicePhone)
        phone.start("Pixel 8", "phone")
        assertEquals(SealedEngine.Mine.IN_HEAD, phone.mine)
        val chain = assertNotNull(phone.refresh(alice.pubkey))
        assertEquals(listOf(alicePhone.pubkey), chain.devices)
        assertNotNull(phone.keys.secretFor(chain.head.lb))
        assertEquals("belfry:device:" + alicePhone.pubkey, relay.events.single { it.tag("d")?.startsWith("belfry:device:") == true }.tag("d"))
    }

    /** §5.1 (B): never a root from silence, never in a guest session. */
    @Test fun noRootFromSilenceOrGuest() = runBlocking {
        relay.silent = true
        val phone = engine(alice, alicePhone)
        phone.start("Pixel 8", "phone")
        assertEquals(SealedEngine.Mine.UNKNOWN, phone.mine)
        relay.silent = false
        assertTrue(relay.events.isEmpty())
        val guest = engine(alice, alicePhone, guest = true)
        guest.start("Pixel 8", "phone")
        assertTrue(relay.events.isEmpty())
    }

    @Test fun requestAllowHandover() = runBlocking {
        val (phone, laptop, _) = world()
        assertEquals(SealedEngine.Mine.IN_HEAD, laptop.mine)
        val head = assertNotNull(laptop.refresh(alice.pubkey))
        assertEquals(setOf(alicePhone.pubkey, aliceLaptop.pubkey), head.devices.toSet())
        assertNotNull(laptop.keys.secretFor(head.head.lb), "the laptop got the letterbox")
        // the anchor names the new head
        assertEquals(head.head.id, relay.events.single { it.kind == 30078 && it.tag("d") == Anchor.D }.tag("head"))
        assertTrue(phone.refresh(alice.pubkey)!!.ready)
    }

    @Test fun linkAllowsAtOnce() = runBlocking {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        val secret = phone.newLinkSecret()
        val laptop = engine(alice, aliceLaptop); laptop.start("Firefox on Linux", "computer", link = secret)
        assertEquals(SealedEngine.OnRequest.Allowed, phone.onRequest(relay.events.last { it.kind == Sealing.REQUEST }))
    }

    @Test fun requestFromNoLiveChatSessionIgnored() = runBlocking {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Notes", chat = false)
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        val laptop = engine(alice, aliceLaptop); laptop.start("x", "computer")
        assertEquals(SealedEngine.OnRequest.Ignore, phone.onRequest(relay.events.last { it.kind == Sealing.REQUEST }))
    }

    @Test fun allowRefusedAtTwenty() = runBlocking {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        repeat(19) { phone.allow(LocalSigner.random().pubkey) }
        assertEquals(20, phone.refresh(alice.pubkey)!!.devices.size)
        val before = relay.events.size
        kotlin.test.assertFailsWith<SealedEngine.TooMany> { phone.allow(LocalSigner.random().pubkey) }
        assertEquals(before, relay.events.size, "refused before any key goes out")
    }

    @Test fun sealedSendAndReceive() = runBlocking {
        val (phone, laptop, b) = world()
        val c = conv(alice.pubkey, bob.pubkey)
        val plan = assertIs<SealedEngine.Plan.Seal>(phone.plan(c, listOf(bob.pubkey)))
        assertEquals(setOf(alice.pubkey, bob.pubkey), plan.heads.keys)
        val rumor = GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "The key is under the blue pot.", now)
        val wraps = phone.seal(rumor, plan)
        val toBob = wraps.single { it.recipient == bob.pubkey }.wrap
        val got = b.receive(toBob, null) { c }
        assertEquals(Sealing.Verdict.ACCEPT, got.verdict)
        assertEquals(rumor.id, got.rumor!!.id)
        assertTrue(c in b.sealedSince, "accepting a sealed rumor marks its conversation sealed")
        // the self-copy opens on my other device
        val self = wraps.single { it.recipient == alice.pubkey }.wrap
        assertEquals(Sealing.Verdict.ACCEPT, laptop.receive(self, null) { c }.verdict)
    }

    /** ac5574b: never the old way on silence; only on a complete answer showing no ready chain. */
    @Test fun neverOldWayOnSilence() = runBlocking {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone"); phone.checkReady()
        val carol = LocalSigner.random().pubkey
        relay.silent = true
        assertIs<SealedEngine.Plan.Refuse>(phone.plan(conv(alice.pubkey, carol), listOf(carol)))
        relay.silent = false
        assertEquals(SealedEngine.Plan.OldWay, phone.plan(conv(alice.pubkey, carol), listOf(carol)))
    }

    /** A sealed conversation with no head to seal to: refused, never the old way. */
    @Test fun sealedWithNoHeadRefuses() = runBlocking {
        val (phone, _, _) = world()
        val carol = LocalSigner.random().pubkey
        val c = conv(alice.pubkey, carol)
        phone.sealedSince[c] = now
        assertIs<SealedEngine.Plan.Refuse>(phone.plan(c, listOf(carol)))
    }

    /** The web's bug: after a removal, the sender sealed to the stale head and the removed device read it. */
    @Test fun removalReachesTheSender() = runBlocking {
        val (phone, laptop, b) = world()
        val c = conv(alice.pubkey, bob.pubkey)
        val old = assertIs<SealedEngine.Plan.Seal>(b.plan(c, listOf(alice.pubkey))).heads[alice.pubkey]
        phone.remove(aliceLaptop.pubkey)
        val plan = assertIs<SealedEngine.Plan.Seal>(b.plan(c, listOf(alice.pubkey)))
        val new = plan.heads[alice.pubkey]
        assertTrue(new != old, "Bob asked again and seals to Alice's new letterbox")
        val rumor = GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "after the removal", now)
        val toAlice = b.seal(rumor, plan).single { it.recipient == alice.pubkey }.wrap
        assertEquals(Sealing.Verdict.LOCKED, laptop.receive(toAlice, null) { c }.verdict, "the removed laptop can't read it")
        assertEquals(Sealing.Verdict.ACCEPT, phone.receive(toAlice, null) { c }.verdict)
        assertEquals(listOf(alicePhone.pubkey), phone.chainOf(alice.pubkey)!!.devices)
        assertTrue(server.sessions[alice.pubkey]!!.none { it.clientPubkey == aliceLaptop.pubkey }, "a removal by the user revokes the session")
    }

    /** A queued reaction and a queued delete are sealed again when the recipient's head changed. */
    @Test fun queuedWrapsResealed() = runBlocking {
        val (phone, _, b) = world()
        val c = conv(alice.pubkey, bob.pubkey)
        val plan = assertIs<SealedEngine.Plan.Seal>(b.plan(c, listOf(alice.pubkey)))
        val react = GiftWrap.rumor(7, bob.pubkey, listOf(listOf("p", alice.pubkey), listOf("e", "aa".repeat(32))), "👍", now)
        val del = GiftWrap.rumor(5, bob.pubkey, listOf(listOf("p", alice.pubkey), listOf("e", "bb".repeat(32))), "", now)
        val queued = (b.seal(react, plan) + b.seal(del, plan)).filter { it.recipient == alice.pubkey }
        phone.remove(aliceLaptop.pubkey)
        b.refresh(alice.pubkey) // the peer's new roster arrives on Bob's subscription
        val again = queued.map { b.resealIfNeeded(it) }
        val head = b.chainOf(alice.pubkey)!!.head.lb
        assertTrue(again.all { it.lb == head && it.wrap.tag("lb") == head })
        assertTrue(again.all { phone.receive(it.wrap, null) { c }.verdict == Sealing.Verdict.ACCEPT })
    }

    /** The web's bug: a hand-over that arrives before my own chain is loaded was dropped. */
    @Test fun earlyHandoverKept() = runBlocking {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        phone.allow(aliceLaptop.pubkey)
        val laptop = engine(alice, aliceLaptop) // not started: its chain isn't loaded
        relay.to(aliceLaptop.pubkey).forEach { assertFalse(laptop.onDeviceWrap(it)) }
        assertEquals(1, laptop.pendingKeysCount)
        laptop.refresh(alice.pubkey)
        assertEquals(0, laptop.pendingKeysCount)
        assertNotNull(laptop.keys.secretFor(laptop.chainOf(alice.pubkey)!!.head.lb))
    }

    /** (E): an odd answer, or one without my own session, removes nobody. */
    @Test fun goneSessions() = runBlocking {
        val (phone, _, _) = world()
        server.odd = true
        phone.checkSessions()
        assertEquals(2, phone.refresh(alice.pubkey)!!.devices.size, "a missing sessions field is not \"no sessions\"")
        server.odd = false
        val all = server.sessions[alice.pubkey]!!.toList()
        server.sessions[alice.pubkey] = all.filter { it.clientPubkey == aliceLaptop.pubkey }.toMutableList()
        phone.checkSessions()
        assertEquals(2, phone.refresh(alice.pubkey)!!.devices.size, "my own session isn't in it: nobody removed")
        server.sessions[alice.pubkey] = all.filter { it.clientPubkey == alicePhone.pubkey }.toMutableList()
        phone.checkSessions()
        assertEquals(listOf(alicePhone.pubkey), phone.refresh(alice.pubkey)!!.devices)
    }

    /** Remove isn't offered on a device that is itself locked (not in the head). */
    @Test fun noRemoveFromALockedDevice() = runBlocking {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        val laptop = engine(alice, aliceLaptop); laptop.start("Firefox on Linux", "computer")
        assertEquals(SealedEngine.Mine.WAITING, laptop.mine)
        assertFalse(laptop.canRemove(alicePhone.pubkey))
        assertTrue(phone.canRemove(aliceLaptop.pubkey).not(), "the laptop isn't in the head yet")
        // and a locked device can't send in a sealed conversation
        laptop.sealedSince["n:x"] = now
        assertIs<SealedEngine.Plan.Refuse>(laptop.plan("n:x", listOf(bob.pubkey)))
    }

    /** A message from an author's device not in the chain known yet: kept, accepted once the new roster is seen. */
    @Test fun keepThenAccept() = runBlocking {
        val (phone, _, b) = world()
        val c = conv(alice.pubkey, bob.pubkey)
        phone.refresh(bob.pubkey) // Alice knows Bob's chain with his phone only
        val bobTablet = LocalSigner.random()
        server.live(bob.pubkey, bobTablet.pubkey, "Bob's tablet")
        b.allow(bobTablet.pubkey)
        val rumor = GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "from my tablet", now)
        val lb = phone.chainOf(alice.pubkey)!!.head.lb
        val wrap = Sealing.seal(rumor, bobTablet, alice.pubkey, lb, now)
        assertEquals(Sealing.Verdict.KEEP, phone.receive(wrap, null) { c }.verdict)
        assertEquals(1, phone.pendingCount)
        phone.refresh(bob.pubkey)
        assertEquals(0, phone.pendingCount)
        assertEquals(listOf(rumor.id), phone.later.map { it.id })
    }
}
