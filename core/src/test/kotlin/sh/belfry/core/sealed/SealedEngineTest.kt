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
        /** Kinds the relay refuses (OK false). */
        val refusing = HashSet<Int>()
        fun refuse(e: Event) = e.kind in refusing
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
        var failRevoke = false
        fun live(account: String, device: String, name: String, chat: Boolean = true) {
            sessions.getOrPut(account) { ArrayList() }.add(SessionRow(device, chat, now, name, "Belfry for Android"))
        }
    }

    companion object { var now = 1_795_000_000L }

    private val relay = FakeRelay()
    private var signerDown = false
    private val server = Server()

    private inner class Ports(val account: LocalSigner) : SealedPorts {
        var saved: Map<String, String> = emptyMap()
        val revoked = ArrayList<String>()
        override suspend fun query(q: Query) = relay.query(q)
        override suspend fun publish(e: Event): Boolean { if (relay.refuse(e)) return false; relay.add(e); return true }
        override suspend fun signAsAccount(t: EventTemplate): Event = if (signerDown) throw IllegalStateException("signer") else account.signNow(t)
        override suspend fun sessions(): List<SessionRow>? = if (server.odd) null else server.sessions[account.pubkey]?.toList() ?: emptyList()
        override suspend fun revoke(clientPubkey: String) { if (server.failRevoke) throw IllegalStateException("revoke"); revoked.add(clientPubkey); server.sessions[account.pubkey]?.removeAll { it.clientPubkey == clientPubkey } }
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

    @Test fun firstDevice() = runBlocking<Unit> {
        val phone = engine(alice, alicePhone)
        phone.start("Pixel 8", "phone")
        assertEquals(SealedEngine.Mine.IN_HEAD, phone.mine)
        val chain = assertNotNull(phone.refresh(alice.pubkey))
        assertEquals(listOf(alicePhone.pubkey), chain.devices)
        assertNotNull(phone.keys.secretFor(chain.head.lb))
        assertEquals("belfry:device:" + alicePhone.pubkey, relay.events.single { it.tag("d")?.startsWith("belfry:device:") == true }.tag("d"))
    }

    /** §5.1 (B): never a root from silence, never in a guest session. */
    @Test fun noRootFromSilenceOrGuest() = runBlocking<Unit> {
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

    @Test fun requestAllowHandover() = runBlocking<Unit> {
        val (phone, laptop, _) = world()
        assertEquals(SealedEngine.Mine.IN_HEAD, laptop.mine)
        val head = assertNotNull(laptop.refresh(alice.pubkey))
        assertEquals(setOf(alicePhone.pubkey, aliceLaptop.pubkey), head.devices.toSet())
        assertNotNull(laptop.keys.secretFor(head.head.lb), "the laptop got the letterbox")
        // the anchor names the new head
        assertEquals(head.head.id, relay.events.single { it.kind == 30078 && it.pubkey == alice.pubkey && it.tag("d") == Anchor.D }.tag("head"))
        assertTrue(phone.refresh(alice.pubkey)!!.ready)
    }

    @Test fun linkAllowsAtOnce() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        val secret = phone.newLinkSecret()
        val laptop = engine(alice, aliceLaptop); laptop.start("Firefox on Linux", "computer", link = secret)
        assertEquals(SealedEngine.OnRequest.Allowed, phone.onRequest(relay.events.last { it.kind == Sealing.REQUEST }))
    }

    @Test fun requestFromNoLiveChatSessionIgnored() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Notes", chat = false)
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        val laptop = engine(alice, aliceLaptop); laptop.start("x", "computer")
        assertEquals(SealedEngine.OnRequest.Ignore, phone.onRequest(relay.events.last { it.kind == Sealing.REQUEST }))
    }

    @Test fun allowRefusedAtTwenty() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        repeat(19) { val d = LocalSigner.random().pubkey; server.live(alice.pubkey, d, "Device $it"); phone.allow(d) }
        assertEquals(20, phone.refresh(alice.pubkey)!!.devices.size)
        val before = relay.events.size
        val extra = LocalSigner.random().pubkey
        server.live(alice.pubkey, extra, "One too many")
        kotlin.test.assertFailsWith<SealedEngine.TooMany> { phone.allow(extra) }
        assertEquals(before, relay.events.size, "refused before any key goes out")
    }

    @Test fun sealedSendAndReceive() = runBlocking<Unit> {
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
    @Test fun neverOldWayOnSilence() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone"); phone.checkReady()
        val carol = LocalSigner.random().pubkey
        relay.silent = true
        assertEquals(SealedEngine.Plan.Hold, phone.plan(conv(alice.pubkey, carol), listOf(carol)), "held undecided, never the old way")
        relay.silent = false
        assertEquals(SealedEngine.Plan.OldWay, phone.plan(conv(alice.pubkey, carol), listOf(carol)))
    }

    /** A sealed conversation with no head to seal to: refused, never the old way. */
    @Test fun sealedWithNoHeadRefuses() = runBlocking<Unit> {
        val (phone, _, _) = world()
        val carol = LocalSigner.random().pubkey
        val c = conv(alice.pubkey, carol)
        phone.sealedSince[c] = now
        assertIs<SealedEngine.Plan.Refuse>(phone.plan(c, listOf(carol)))
    }

    /** The web's bug: after a removal, the sender sealed to the stale head and the removed device read it. */
    @Test fun removalReachesTheSender() = runBlocking<Unit> {
        val (phone, laptop, b) = world()
        val c = conv(alice.pubkey, bob.pubkey)
        val old = assertIs<SealedEngine.Plan.Seal>(b.plan(c, listOf(alice.pubkey))).heads[alice.pubkey]
        phone.remove(aliceLaptop.pubkey)
        val plan = assertIs<SealedEngine.Plan.Seal>(b.plan(c, listOf(alice.pubkey)))
        val new = plan.heads[alice.pubkey]
        assertTrue(new != old, "Bob asked again and seals to Alice's new letterbox")
        val rumor = GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "after the removal", now)
        val toAlice = b.seal(rumor, plan).single { it.recipient == alice.pubkey }.wrap
        val v = laptop.receive(toAlice, null) { c }.verdict
        assertTrue(v == Sealing.Verdict.LOCKED || v == Sealing.Verdict.DROP, "the removed laptop can't read it ($v)")
        assertEquals(Sealing.Verdict.ACCEPT, phone.receive(toAlice, null) { c }.verdict)
        assertEquals(listOf(alicePhone.pubkey), phone.chainOf(alice.pubkey)!!.devices)
        assertTrue(server.sessions[alice.pubkey]!!.none { it.clientPubkey == aliceLaptop.pubkey }, "a removal by the user revokes the session")
    }

    /** A queued reaction and a queued delete are sealed again when the recipient's head changed. */
    @Test fun queuedWrapsResealed() = runBlocking<Unit> {
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
    @Test fun earlyHandoverKept() = runBlocking<Unit> {
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
    @Test fun goneSessions() = runBlocking<Unit> {
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
    @Test fun noRemoveFromALockedDevice() = runBlocking<Unit> {
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
    @Test fun keepThenAccept() = runBlocking<Unit> {
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

    /** 294c94f (5): Remove revokes first and changes nothing when that fails. */
    @Test fun removeRevokesFirst() = runBlocking<Unit> {
        val (phone, _, _) = world()
        val head = phone.refresh(alice.pubkey)!!.head.id
        server.failRevoke = true
        kotlin.test.assertFails { phone.remove(aliceLaptop.pubkey) }
        assertEquals(head, phone.refresh(alice.pubkey)!!.head.id, "no roster change")
    }

    /** 294c94f (6): when the anchor can't be signed, neither the roster nor the anchor goes. */
    @Test fun noRosterWithoutAnchor() = runBlocking<Unit> {
        val (phone, _, _) = world()
        val before = relay.events.count { it.kind == Roster.KIND }
        signerDown = true
        kotlin.test.assertFails { phone.remove(aliceLaptop.pubkey, revoke = false) }
        assertEquals(before, relay.events.count { it.kind == Roster.KIND })
    }

    /** 294c94f (1): a wrap to a letterbox that isn't one of my chain's is dropped, not "locked". */
    @Test fun foreignLetterboxDropped() = runBlocking<Unit> {
        val (phone, _, b) = world()
        val stranger = KeyChain.newLetterbox().first
        val rumor = GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "x", now)
        val wrap = Sealing.seal(rumor, bobPhone, alice.pubkey, stranger, now)
        assertEquals(Sealing.Verdict.DROP, phone.receive(wrap, null) { "c" }.verdict)
        assertEquals(0, phone.pendingCount)
    }

    /** 294c94f (13): Allow re-checks that the session is still live. */
    @Test fun allowNeedsALiveSession() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        kotlin.test.assertFails { phone.allow(aliceLaptop.pubkey) }
        assertEquals(1, phone.refresh(alice.pubkey)!!.devices.size)
    }

    /** 64a07fa: knowledge of "no ready chain" expires after 10 minutes. */
    @Test fun knowledgeExpires() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone"); phone.checkReady()
        val carol = LocalSigner.random().pubkey
        assertEquals(SealedEngine.Plan.OldWay, phone.plan(conv(alice.pubkey, carol), listOf(carol)))
        relay.silent = true
        now += 601
        assertEquals(SealedEngine.Plan.Hold, phone.plan(conv(alice.pubkey, carol), listOf(carol)))
        relay.silent = false
    }

    /** 64a07fa: Allow is not done when the relay refuses the anchor, and no key went out. */
    @Test fun allowNotDoneWhenRefused() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        relay.refusing.add(30078)
        kotlin.test.assertFailsWith<SealedEngine.NotDone> { phone.allow(aliceLaptop.pubkey) }
        relay.refusing.clear()
        assertTrue(relay.to(aliceLaptop.pubkey).isEmpty(), "never keys for a device in no roster")
        val anchor = relay.events.single { it.kind == 30078 && it.pubkey == alice.pubkey && it.tag("d") == Anchor.D }
        assertEquals(anchor.tag("root"), anchor.tag("head"), "the anchor still names the root: the change isn't done")
    }

    /** c233eec §5.5: a device of the head without the head's secret asks again; a head device answers at once. */
    @Test fun lostHandover() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        phone.allow(aliceLaptop.pubkey)
        relay.events.removeAll { it.kind == 1059 && it.tag("p") == aliceLaptop.pubkey } // the hand-over is lost
        val laptop = engine(alice, aliceLaptop); laptop.start("Firefox on Linux", "computer")
        assertEquals(SealedEngine.Mine.WAITING, laptop.mine)
        val req = relay.events.last { it.kind == Sealing.REQUEST }
        assertEquals(SealedEngine.OnRequest.Allowed, phone.onRequest(req), "no prompt for a device already in the head")
        relay.to(aliceLaptop.pubkey).forEach { laptop.onDeviceWrap(it) }
        assertEquals(SealedEngine.Mine.IN_HEAD, laptop.mine)
    }

    /** c233eec §5.6: when the session list can't be read, a rotation does nothing. */
    @Test fun noRotationWithoutSessions() = runBlocking<Unit> {
        val (phone, _, _) = world()
        val head = phone.refresh(alice.pubkey)!!.head.id
        server.odd = true
        kotlin.test.assertFailsWith<SealedEngine.NotDone> { phone.remove(aliceLaptop.pubkey, revoke = false) }
        server.odd = false
        assertEquals(head, phone.refresh(alice.pubkey)!!.head.id)
    }

    /** c233eec §3.2: a roster of mine past the anchored end is anchored at the next upkeep (and start). */
    @Test fun ownTailAnchored() = runBlocking<Unit> {
        val (phone, _, _) = world()
        val chain = phone.refresh(alice.pubkey)!!
        val r = alicePhone.signNow(Roster.template(alice.pubkey, chain.head.n + 1, chain.head.id, chain.head.lb, chain.devices, true, now))
        relay.add(r) // published, its anchor lost
        phone.upkeep()
        val anchor = relay.events.single { it.kind == 30078 && it.pubkey == alice.pubkey && it.tag("d") == Anchor.D }
        assertEquals(r.id, anchor.tag("head"))
    }

    /** c233eec: a refused first roster reuses one key and stops trying. */
    @Test fun refusedFirstRoster() = runBlocking<Unit> {
        relay.refusing.add(Roster.KIND)
        val phone = engine(alice, alicePhone)
        phone.start("Pixel 8", "phone")
        assertTrue(phone.firstRefused)
        phone.start("Pixel 8", "phone")
        assertEquals(1, phone.keys.size, "one pending key, not a new one per try")
        relay.refusing.clear()
    }

    /** c233eec: knowledge is cleared when the connection drops. */
    @Test fun knowledgeClearedOnDrop() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone"); phone.checkReady()
        val carol = LocalSigner.random().pubkey
        assertEquals(SealedEngine.Plan.OldWay, phone.plan(conv(alice.pubkey, carol), listOf(carol)))
        phone.connectionLost()
        relay.silent = true
        assertEquals(SealedEngine.Plan.Hold, phone.plan(conv(alice.pubkey, carol), listOf(carol)))
        relay.silent = false
    }
}
