package sh.belfry.core.sealed

import kotlinx.coroutines.async
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
        val events = java.util.concurrent.CopyOnWriteArrayList<Event>()
        var silent = false
        /** The relay answers CLOSED (no answer, sealed.md §5.1). */
        var closing = false
        /** The next N anchor queries see no anchor (a relay that hasn't got it yet). */
        var hideAnchors = 0
        /** Kinds the relay refuses (OK false). */
        val refusing = HashSet<Int>()
        fun refuse(e: Event) = e.kind in refusing
        /** Replaceable 30078 per (pubkey, d): the newer stays; the same second keeps the lower id (NIP-01). */
        @Synchronized fun add(e: Event) {
            if (e.kind == 30078) {
                val standing = events.firstOrNull { it.kind == 30078 && it.pubkey == e.pubkey && it.tag("d") == e.tag("d") }
                if (standing != null) {
                    if (standing.createdAt > e.createdAt || (standing.createdAt == e.createdAt && standing.id < e.id)) return
                    events.remove(standing)
                }
            }
            events.add(e)
        }
        fun query(q: Query): SealedEngine.Answer {
            if (silent) return SealedEngine.Answer(emptyList(), false)
            if (q.kinds.contains(30078) && q.d?.contains(Anchor.D) == true && hideAnchors > 0) { hideAnchors--; return SealedEngine.Answer(emptyList(), true) }
            val out = events.filter { e ->
                e.kind in q.kinds &&
                    (q.authors == null || e.pubkey in q.authors) &&
                    (q.p == null || e.tags.any { it.size >= 2 && it[0] == "p" && it[1] in q.p }) &&
                    (q.d == null || e.tag("d") in q.d)
            }
            return SealedEngine.Answer(out, !closing)
        }
        /** A page as the community relay gives it: newest first, `until` inclusive, at most 300 (its max limit). */
        fun page(filter: kotlinx.serialization.json.JsonObject): Pair<List<Event>, Boolean> {
            val kinds = (filter["kinds"] as kotlinx.serialization.json.JsonArray).map { it.toString().toInt() }
            val until = (filter["until"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLong() ?: Long.MAX_VALUE
            val limit = minOf((filter["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toInt() ?: 300, 300)
            return events.filter { it.kind in kinds && it.createdAt <= until }.sortedByDescending { it.createdAt }.take(limit) to !closing
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
        override suspend fun query(q: Query): SealedEngine.Answer { kotlinx.coroutines.yield(); return relay.query(q) } // a suspension point: real interleaving
        override suspend fun publish(e: Event): Boolean { if (relay.refuse(e)) return false; relay.add(e); return true }
        override suspend fun signAsAccount(t: EventTemplate): Event = if (signerDown) throw IllegalStateException("signer") else account.signNow(t)
        override suspend fun sessions(): List<SessionRow>? = if (server.odd) null else server.sessions[account.pubkey]?.toList() ?: emptyList()
        override suspend fun revoke(clientPubkey: String) { if (server.failRevoke) throw IllegalStateException("revoke"); revoked.add(clientPubkey); server.sessions[account.pubkey]?.removeAll { it.clientPubkey == clientPubkey } }
        override fun loadKeys() = saved
        var diskFull = false
        override fun saveKeys(keys: Map<String, String>): Boolean { if (diskFull) return false; saved = keys; return true }
        var state: String? = null
        override fun loadState(): String? = state
        override fun saveState(json: String) { state = json }
        val notes = HashMap<String, String>()
        override fun loadNote(name: String): String? = notes[name]
        override fun saveNote(name: String, value: String) { notes[name] = value }
    }

    private val alice = LocalSigner.random()
    private val bob = LocalSigner.random()
    private val alicePhone = LocalSigner.random()
    private val aliceLaptop = LocalSigner.random()
    private val bobPhone = LocalSigner.random()

    private val portsOf = HashMap<String, Ports>()

    /** One store per device: a second engine for the same device is that device after a restart. */
    private fun engine(account: LocalSigner, device: LocalSigner, guest: Boolean = false) =
        SealedEngine(portsOf.getOrPut(device.pubkey) { Ports(account) }, device, account.pubkey, clock = { now }, guest = guest, removalJitter = 0)

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
        assertTrue(b.sealedSince.containsKey(c), "accepting a sealed rumor marks its conversation sealed")
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
        kotlin.test.assertFails { laptop.remove(alicePhone.pubkey) } // the engine refuses too, not only the screen
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
        // the review's 1: a stale chain is read again on receipt, not only when Alice writes
        assertEquals(Sealing.Verdict.ACCEPT, phone.receive(wrap, null) { c }.verdict)
        assertEquals(0, phone.pendingCount)
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
        now += 10 // its request comes after the roster that added it and after the lost hand-over
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

    /** 6e0bc8e: a wrap to an lb not of my chain is kept while my chain isn't current here; dropped once it is. */
    @Test fun foreignLbKeptUntilCurrent() = runBlocking<Unit> {
        val (phone, _, _) = world()
        val stranger = KeyChain.newLetterbox().first
        val rumor = GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "x", now)
        val wrap = Sealing.seal(rumor, bobPhone, alice.pubkey, stranger, now)
        phone.connectionLost()
        relay.silent = true // the re-read of my chain doesn't complete: no knowledge, keep
        assertEquals(Sealing.Verdict.LOCKED, phone.receive(wrap, null) { "c" }.verdict, "kept: my chain isn't current on this connection")
        relay.silent = false
        phone.refresh(alice.pubkey) // current now: the lb is not mine
        now += 61 // past the once-a-minute re-read limit (a throttled read keeps, P3)
        assertEquals(Sealing.Verdict.DROP, phone.receive(wrap, null) { "c" }.verdict)
    }

    /** "Done" only from a read-back: the relay refusing the roster only, the anchor only, or both. */
    @Test fun refusedRosterOrAnchor() = runBlocking<Unit> {
        for (refused in listOf(setOf(Roster.KIND), setOf(30078), setOf(Roster.KIND, 30078))) {
            relay.events.clear(); server.sessions.clear(); portsOf.clear() // each round a device with nothing stored
            server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
            server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
            val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
            relay.refusing.addAll(refused)
            kotlin.test.assertFailsWith<SealedEngine.NotDone>("refused $refused") { phone.allow(aliceLaptop.pubkey) }
            relay.refusing.clear()
            assertTrue(relay.to(aliceLaptop.pubkey).isEmpty(), "no keys when refused $refused")
            val anchor = relay.events.single { it.kind == 30078 && it.pubkey == alice.pubkey && it.tag("d") == Anchor.D }
            assertEquals(anchor.tag("root"), anchor.tag("head"))
        }
    }

    /** f28220c: an older anchor pointing back to a roster with a since-removed device doesn't make it "already allowed". */
    @Test fun olderAnchorDoesNotAllow() = runBlocking<Unit> {
        val (phone, _, _) = world()
        val withLaptop = phone.refresh(alice.pubkey)!!
        phone.remove(aliceLaptop.pubkey, revoke = false) // the laptop is out, its session still live
        // the server republishes an anchor naming the old roster (the laptop in it)
        now += 5
        relay.add(alice.signNow(Anchor.template(withLaptop.root.id, withLaptop.head.id, now)))
        val before = relay.to(aliceLaptop.pubkey).size
        now += 5
        val req = aliceLaptop.signNow(Sealing.request(alice.pubkey, aliceLaptop.pubkey, null, now))
        val r = phone.onRequest(req)
        assertTrue(r != SealedEngine.OnRequest.Allowed, "never allowed at once by an older anchor")
        assertEquals(before, relay.to(aliceLaptop.pubkey).size, "no keys handed over")
    }

    /** f28220c: a request no newer than my last hand-over to that device gets nothing again. */
    @Test fun handoverOnlyForNewerRequests() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        now += 10
        phone.allow(aliceLaptop.pubkey)
        relay.events.removeAll { it.kind == 1059 && it.tag("p") == aliceLaptop.pubkey }
        now += 10
        val req = aliceLaptop.signNow(Sealing.request(alice.pubkey, aliceLaptop.pubkey, null, now))
        assertEquals(SealedEngine.OnRequest.Allowed, phone.onRequest(req))
        assertEquals(SealedEngine.OnRequest.Ignore, phone.onRequest(req), "the same request again: nothing")
    }

    /**
     * 2a1b30e: the server anchors a removed device's old sibling roster (with a device of its own).
     * A switch: followed, treated as a fresh start; this phone isn't in it any more; the new device gets nothing.
     */
    @Test fun anchoredSiblingIsASwitch() = runBlocking<Unit> {
        val (phone, _, b) = world()
        val chain = phone.refresh(alice.pubkey)!! // seen anchored
        b.refresh(alice.pubkey)
        val attacker = LocalSigner.random()
        server.live(alice.pubkey, attacker.pubkey, "?")
        // the laptop (dropped later) signs a sibling of the head's parent... here: a sibling of the head
        val parent = chain.rosters[chain.rosters.size - 2]
        val sibling = aliceLaptop.signNow(Roster.template(alice.pubkey, parent.n + 1, parent.id, KeyChain.newLetterbox().first, listOf(aliceLaptop.pubkey, attacker.pubkey), true, now + 1))
        relay.add(sibling)
        now += 5
        relay.add(alice.signNow(Anchor.template(chain.root.id, sibling.id, now))) // the server's signature
        val after = phone.refresh(alice.pubkey)!!
        assertEquals(sibling.id, after.head.id, "followed")
        assertTrue(phone.resetAt.containsKey(alice.pubkey), "treated like a fresh start")
        assertEquals(SealedEngine.Mine.WAITING, phone.mine, "this phone isn't in the new head")
        b.refresh(alice.pubkey)
        assertTrue(b.resetAt.containsKey(alice.pubkey), "Bob sees the amber notice for Alice")
        val req = attacker.signNow(Sealing.request(alice.pubkey, attacker.pubkey, null, now + 10))
        assertTrue(phone.onRequest(req) != SealedEngine.OnRequest.Allowed)
        assertTrue(relay.to(attacker.pubkey).isEmpty(), "no keys for the attacker's device")
    }

    /** Pins are durable (§3.4): after a restart a new root still gives the amber notice, an older anchor is still ignored. */
    @Test fun pinsSurviveRestart() = runBlocking<Unit> {
        val (_, _, b) = world()
        val alices = b.refresh(alice.pubkey)!!
        val restarted = engine(bob, bobPhone) // the app started again: pins come from storage
        assertEquals(alices.head.id, restarted.chainOf(alice.pubkey)!!.head.id)
        // the relay forgets Alice's rosters and the server republishes an anchor naming an ancestor
        relay.events.removeAll { it.kind == Roster.KIND }
        now += 5
        relay.add(alice.signNow(Anchor.template(alices.root.id, alices.rosters[1].id, now)))
        assertEquals(alices.head.id, restarted.refresh(alice.pubkey)!!.head.id, "an older anchor is ignored after a restart too")
        assertTrue(!restarted.resetAt.containsKey(alice.pubkey))
        // Alice starts fresh: a new root
        val phone2 = LocalSigner.random()
        val root2 = phone2.signNow(Roster.template(alice.pubkey, 1, null, KeyChain.newLetterbox().first, listOf(phone2.pubkey), true, now + 1))
        relay.add(root2)
        now += 5
        relay.add(alice.signNow(Anchor.template(root2.id, root2.id, now)))
        val again = engine(bob, bobPhone)
        again.refresh(alice.pubkey)
        assertTrue(again.resetAt.containsKey(alice.pubkey), "a replaced root after a restart: the amber notice")
    }

    /** §8 from the amber line: Bob shares the conversation's messages with Alice's new head. */
    @Test fun shareHistoryAfterAFreshStart() = runBlocking<Unit> {
        val (_, _, b) = world()
        b.refresh(alice.pubkey)
        val phone2 = LocalSigner.random()
        val (lb2, secret2) = KeyChain.newLetterbox()
        val root2 = phone2.signNow(Roster.template(alice.pubkey, 1, null, lb2, listOf(phone2.pubkey), true, now + 1))
        relay.add(root2)
        now += 5
        relay.add(alice.signNow(Anchor.template(root2.id, root2.id, now)))
        b.refresh(alice.pubkey)
        assertTrue(b.resetAt.containsKey(alice.pubkey))
        val rumors = listOf(
            GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "first", now - 100),
            GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "second", now - 50),
        )
        b.shareHistory(alice.pubkey, rumors)
        assertTrue((b.sharedAfter[alice.pubkey] ?: 0) >= b.resetAt[alice.pubkey]!!)
        val wrap = relay.events.last { it.kind == 1059 && it.tag("lb") == lb2 }
        val opened = Sealing.open(wrap, KeyChain(mapOf(lb2 to secret2))) as Opening.Opened
        val chain = Chain.of(bob.pubkey, relay.events.filter { it.kind == 30078 }, relay.events.filter { it.kind == Roster.KIND })
        assertEquals(rumors.map { it.id }, Sealing.acceptShared(opened.rumor, opened.sealPubkey, chain, alice.pubkey).map { it.id })
    }

    /** The lead after 2a1b30e: a pinned head never seen anchored (a provisional tail) moves silently. */
    @Test fun provisionalTailMovesSilently() = runBlocking<Unit> {
        val (_, _, b) = world()
        val chain = b.refresh(alice.pubkey)!!
        // a roster past the anchored end (published, not anchored): Bob follows it provisionally
        val tail = alicePhone.signNow(Roster.template(alice.pubkey, chain.head.n + 1, chain.head.id, chain.head.lb, chain.devices, true, now + 1))
        relay.add(tail)
        assertEquals(tail.id, b.refresh(alice.pubkey)!!.head.id)
        // then the anchor names a sibling of that tail: not a switch, no amber notice
        val sibling = aliceLaptop.signNow(Roster.template(alice.pubkey, chain.head.n + 1, chain.head.id, chain.head.lb, chain.devices + aliceTablet().pubkey, true, now + 2))
        relay.add(sibling)
        now += 5
        relay.add(alice.signNow(Anchor.template(chain.root.id, sibling.id, now)))
        assertEquals(sibling.id, b.refresh(alice.pubkey)!!.head.id)
        assertTrue(!b.resetAt.containsKey(alice.pubkey))
    }

    private val tablet = LocalSigner.random()
    private fun aliceTablet() = tablet

    /** sealed.md 52808c2: after a switch a rotation's new secret goes only to trusted devices; Allow by hand gives keys. */
    @Test fun rotationAfterASwitchOnlyToTrusted() = runBlocking<Unit> {
        val (phone, _, _) = world()
        val chain = phone.refresh(alice.pubkey)!!
        val x = LocalSigner.random(); val y = LocalSigner.random()
        server.live(alice.pubkey, x.pubkey, "Newcomer"); server.live(alice.pubkey, y.pubkey, "Kept")
        // the server anchors a sibling branch (signed by the laptop) with two devices this phone never held
        val parent = chain.rosters[chain.rosters.size - 2]
        val sibling = aliceLaptop.signNow(Roster.template(alice.pubkey, parent.n + 1, parent.id, parent.lb, parent.devices + x.pubkey + y.pubkey, true, now + 1))
        relay.add(sibling)
        now += 5
        relay.add(alice.signNow(Anchor.template(chain.root.id, sibling.id, now)))
        assertEquals(sibling.id, phone.refresh(alice.pubkey)!!.head.id)
        assertTrue(phone.resetAt.containsKey(alice.pubkey), "a switch")
        assertEquals(SealedEngine.Mine.IN_HEAD, phone.mine)
        // Not me on the newcomer: dropped, a new letterbox
        phone.remove(x.pubkey)
        val newLb = phone.chainOf(alice.pubkey)!!.head.lb
        assertTrue(relay.to(y.pubkey).isEmpty(), "the merely-kept untrusted device gets nothing")
        assertTrue(relay.to(aliceLaptop.pubkey).isNotEmpty(), "the trusted laptop gets the new secret")
        // it asks again: still nothing without a prompt
        now += 10
        val req = y.signNow(Sealing.request(alice.pubkey, y.pubkey, null, now))
        assertTrue(phone.onRequest(req) is SealedEngine.OnRequest.Ask)
        assertTrue(relay.to(y.pubkey).isEmpty())
        // Allow by hand: keys
        phone.allow(y.pubkey)
        val yWraps = relay.to(y.pubkey)
        assertTrue(yWraps.isNotEmpty())
        val got = Sealing.openKeys(yWraps.last(), y)!!
        assertTrue(newLb in got.keys)
    }

    /** The review's 6: anchors are dated after the standing one; Allow lands even within one second. */
    @Test fun anchorsDatedAfterTheStandingOne() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone") // the clock stands still: same second
        phone.allow(aliceLaptop.pubkey)
        val anchor = relay.events.single { it.kind == 30078 && it.pubkey == alice.pubkey && it.tag("d") == Anchor.D }
        assertEquals(phone.chainOf(alice.pubkey)!!.head.id, anchor.tag("head"))
        assertTrue(anchor.createdAt > now, "after the root's anchor")
    }

    /** The review's 7: two devices at first sign-in; the loser of the anchor reads back and asks. */
    @Test fun firstRootRace() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val a = engine(alice, alicePhone); a.start("Pixel 8", "phone")
        relay.hideAnchors = 2 // the laptop's start doesn't see the phone's anchor yet
        val b = engine(alice, aliceLaptop); b.start("Firefox on Linux", "computer")
        val anchor = relay.events.single { it.kind == 30078 && it.pubkey == alice.pubkey && it.tag("d") == Anchor.D }
        val bRoot = relay.events.firstOrNull { it.kind == Roster.KIND && it.pubkey == aliceLaptop.pubkey && it.tag("n") == "1" }
        if (anchor.tag("root") == bRoot?.id) assertEquals(SealedEngine.Mine.IN_HEAD, b.mine)
        else assertEquals(SealedEngine.Mine.WAITING, b.mine, "lost the anchor: asks like a new device")
    }

    /** The review's 15: a key that can't be written for good: nothing published. */
    @Test fun keyNotSavedNothingPublished() = runBlocking<Unit> {
        val (phone, _, _) = world()
        val head = phone.chainOf(alice.pubkey)!!.head.id
        portsOf[alicePhone.pubkey]!!.diskFull = true
        kotlin.test.assertFailsWith<SealedEngine.NotDone> { phone.remove(aliceLaptop.pubkey, revoke = false) }
        portsOf[alicePhone.pubkey]!!.diskFull = false
        assertEquals(head, phone.refresh(alice.pubkey)!!.head.id)
    }

    /** The review's 2: a wrap to my new letterbox reaches a device without its key: kept, opened when the keys come. */
    @Test fun dropThenKeys() = runBlocking<Unit> {
        val (phone, laptop, b) = world()
        val tablet = LocalSigner.random()
        server.live(alice.pubkey, tablet.pubkey, "Tab")
        phone.allow(tablet.pubkey)
        val before = relay.to(aliceLaptop.pubkey).map { it.id }.toSet()
        phone.remove(tablet.pubkey) // a new letterbox; its hand-over to the laptop is lost
        val lost = relay.to(aliceLaptop.pubkey).filter { it.id !in before }
        relay.events.removeAll(lost.toSet())
        val c = "n:" + listOf(alice.pubkey, bob.pubkey).sorted().joinToString(",")
        val plan = b.plan(c, listOf(alice.pubkey)) as SealedEngine.Plan.Seal
        val rumor = GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "to the new letterbox", now)
        val toAlice = b.seal(rumor, plan).single { it.recipient == alice.pubkey }.wrap
        val got = laptop.receive(toAlice, null) { c }
        assertEquals(Sealing.Verdict.LOCKED, got.verdict, "an lb of my chain without its secret: never dropped")
        // not stored (fourth review 2b): the app reads its wraps again from 0 when the keys come
        var told = false
        laptop.onKeys = { told = true }
        lost.forEach { relay.add(it); laptop.onDeviceWrap(it) }
        assertTrue(told, "keys arrived: the app is told to read its wraps again")
        assertEquals(Sealing.Verdict.ACCEPT, laptop.receive(toAlice, null) { c }.verdict, "opened once the keys came")
    }

    /** The review's 9: engine state touched from many coroutines at once (every relay call suspends). */
    @Test fun concurrency() = runBlocking<Unit> {
        val (phone, _, b) = world()
        val c = "n:" + listOf(alice.pubkey, bob.pubkey).sorted().joinToString(",")
        val plan = b.plan(c, listOf(alice.pubkey)) as SealedEngine.Plan.Seal
        val wraps = (1..40).map { i -> b.seal(GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "m$i", now + i), plan).single { it.recipient == alice.pubkey }.wrap }
        val results = (wraps.map { w -> async(kotlinx.coroutines.Dispatchers.Default) { phone.receive(w, null) { c }.verdict } } +
            (1..20).map { async(kotlinx.coroutines.Dispatchers.Default) { phone.refresh(bob.pubkey); phone.saveState(); Sealing.Verdict.ACCEPT } }).map { it.await() }
        phone.refresh(bob.pubkey) // anything kept while Bob's chain was being read is taken now
        // a message that came while Bob's chain was being read waits (the back-off) and is accepted a moment later
        assertEquals(40, results.take(40).count { it == Sealing.Verdict.ACCEPT } + phone.later.size, "every message accepted, now or later; no lost state")
        assertTrue(phone.sealedSince.containsKey(c))
        assertEquals(0, phone.pendingCount)
        // the saved state is whole: a restart reads it back
        assertEquals(phone.chainOf(bob.pubkey)!!.head.id, engine(alice, alicePhone).chainOf(bob.pubkey)!!.head.id)
    }

    /** Third review P1: a wrap to MY letterbox whose secret I lack is kept however long; it opens when the keys come. */
    @Test fun myLetterboxKeptBeyond30Days() = runBlocking<Unit> {
        val (phone, laptop, b) = world()
        val tablet = LocalSigner.random()
        server.live(alice.pubkey, tablet.pubkey, "Tab")
        phone.allow(tablet.pubkey)
        val before = relay.to(aliceLaptop.pubkey).map { it.id }.toSet()
        phone.remove(tablet.pubkey)
        val lost = relay.to(aliceLaptop.pubkey).filter { it.id !in before }
        relay.events.removeAll(lost.toSet())
        val c = "n:" + listOf(alice.pubkey, bob.pubkey).sorted().joinToString(",")
        val plan = b.plan(c, listOf(alice.pubkey)) as SealedEngine.Plan.Seal
        val rumor = GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "five weeks ago", now)
        val wrap = b.seal(rumor, plan).single { it.recipient == alice.pubkey }.wrap
        val first = laptop.receive(wrap, null) { c }
        assertEquals(Sealing.Verdict.LOCKED, first.verdict)
        assertTrue(!first.stored, "to my letterbox without its secret: not stored, never marked seen (fourth review 2b)")
        now += 35 * 86400L
        assertEquals(Sealing.Verdict.LOCKED, laptop.receive(wrap, null) { c }.verdict, "still locked, never dropped, after 35 days")
        lost.forEach { relay.add(it); laptop.onDeviceWrap(it) }
        // the app reads its wraps again from 0 when keys arrive: then it opens
        assertEquals(Sealing.Verdict.ACCEPT, laptop.receive(wrap, null) { c }.verdict)
    }

    /** Third review P3: when the own-chain re-read is throttled, a wrap to an unknown lb is kept, not judged on an older read. */
    @Test fun throttledReReadKeeps() = runBlocking<Unit> {
        val (phone, laptop, b) = world()
        val stranger = KeyChain.newLetterbox().first
        val junk = Sealing.seal(GiftWrap.rumor(14, bob.pubkey, emptyList(), "junk", now), bobPhone, alice.pubkey, stranger, now)
        assertEquals(Sealing.Verdict.DROP, laptop.receive(junk, null) { "c" }.verdict, "a fresh read: not my lb")
        // within the minute: a rotation the laptop missed, and a wrap to the new letterbox
        val tablet = LocalSigner.random(); server.live(alice.pubkey, tablet.pubkey, "Tab")
        phone.allow(tablet.pubkey); phone.remove(tablet.pubkey)
        relay.events.removeAll(relay.to(aliceLaptop.pubkey).toSet())
        val c = "n:" + listOf(alice.pubkey, bob.pubkey).sorted().joinToString(",")
        val plan = b.plan(c, listOf(alice.pubkey)) as SealedEngine.Plan.Seal
        val toNew = b.seal(GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "new lb", now), plan).single { it.recipient == alice.pubkey }.wrap
        assertEquals(Sealing.Verdict.LOCKED, laptop.receive(toNew, null) { c }.verdict, "throttled: kept, not dropped")
    }

    /** Third review P2: a passing Ignore isn't remembered; the next look asks. A linked request never flashes an Ask. */
    @Test fun answerRequestsPassingAndLinked() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        val laptop = engine(alice, aliceLaptop)
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        laptop.start("Firefox on Linux", "computer")
        val req = relay.events.last { it.kind == Sealing.REQUEST && it.pubkey == aliceLaptop.pubkey }
        server.odd = true // the session list can't be read: a passing Ignore
        assertEquals(SealedEngine.OnRequest.Ignore, phone.answerRequests(listOf(req)).single().second)
        server.odd = false
        now += 31 // past the cache
        assertTrue(phone.answerRequests(listOf(req)).single().second is SealedEngine.OnRequest.Ask, "asked again, not remembered")
        // a linked request (older) and an unlinked newer one from another device: the linked one first, no Ask for it
        val tab = LocalSigner.random(); server.live(alice.pubkey, tab.pubkey, "Tab")
        val secret = phone.newLinkSecret()
        val linked = tab.signNow(Sealing.request(alice.pubkey, tab.pubkey, secret, now))
        val unlinked = tab.signNow(Sealing.request(alice.pubkey, tab.pubkey, null, now + 1))
        val answers = phone.answerRequests(listOf(unlinked, linked))
        assertEquals(linked.id, answers.first().first.id, "the linked request first")
        assertTrue(answers.none { it.second is SealedEngine.OnRequest.Ask }, "no Ask flashes for a device the link allows")
    }

    /** Fourth review 2a: a foreign-lb wrap kept while throttled is judged by the next complete read of my chain that began after it. */
    @Test fun keptForeignWrapJudgedByALaterRead() = runBlocking<Unit> {
        val (phone, laptop, _) = world()
        val stranger = KeyChain.newLetterbox().first
        val one = Sealing.seal(GiftWrap.rumor(14, bob.pubkey, emptyList(), "one", now), bobPhone, alice.pubkey, stranger, now)
        assertEquals(Sealing.Verdict.DROP, laptop.receive(one, null) { "c" }.verdict, "its own fresh read judges it")
        now += 1
        val two = Sealing.seal(GiftWrap.rumor(14, bob.pubkey, emptyList(), "two", now), bobPhone, alice.pubkey, stranger, now)
        val kept = laptop.receive(two, null) { "c" }
        assertEquals(Sealing.Verdict.LOCKED, kept.verdict, "throttled: kept")
        assertTrue(kept.stored)
        now += 5
        laptop.refresh(alice.pubkey) // any complete read of my chain that began after it was first seen
        val (seen, _) = laptop.drainSettled()
        assertTrue(two.id in seen, "dropped by the later read and marked seen")
    }

    /** Fourth review 2c: at most MAX_KEPT kept wraps; the oldest are let go unseen. */
    @Test fun keptWrapsAreCapped() = runBlocking<Unit> {
        val (_, laptop, _) = world()
        val stranger = KeyChain.newLetterbox().first
        // the first one spends the minute's re-read; the rest are kept while throttled
        laptop.receive(Sealing.seal(GiftWrap.rumor(14, bob.pubkey, emptyList(), "x", now), bobPhone, alice.pubkey, stranger, now), null) { "c" }
        val ids = ArrayList<String>()
        repeat(SealedEngine.MAX_KEPT + 3) { i ->
            val w = Sealing.seal(GiftWrap.rumor(14, bob.pubkey, emptyList(), "w$i", now), bobPhone, alice.pubkey, stranger, now)
            ids.add(w.id)
            laptop.receive(w, null) { "c" }
        }
        val (seen, letGo) = laptop.drainSettled()
        assertEquals(3, letGo.size, "the oldest three let go")
        assertTrue(seen.isEmpty(), "never marked seen")
    }

    /** Fourth review 4: after Not me, the same request doesn't prompt again from the cached session list. */
    @Test fun notMeDoesNotPromptAgain() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        val stranger = LocalSigner.random(); server.live(alice.pubkey, stranger.pubkey, "Unknown laptop")
        val req = stranger.signNow(Sealing.request(alice.pubkey, stranger.pubkey, null, now))
        assertTrue(phone.answerRequests(listOf(req)).single().second is SealedEngine.OnRequest.Ask)
        phone.notMe(stranger.pubkey)
        assertEquals(SealedEngine.OnRequest.Ignore, phone.answerRequests(listOf(req)).single().second, "no prompt again")
    }

    /**
     * §5.4 Start fresh: the laptop is not in the head and has no other device. A new root (n = 1,
     * the laptop) and the anchor replaced; the old phone, coming back, finds itself out and asks.
     */
    @Test fun startFresh() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        val oldHead = assertNotNull(phone.refresh(alice.pubkey))
        val laptop = engine(alice, aliceLaptop); laptop.start("Firefox on Linux", "computer")
        assertEquals(SealedEngine.Mine.WAITING, laptop.mine)
        now += 5
        laptop.startFresh("Firefox on Linux", "computer")
        assertEquals(SealedEngine.Mine.IN_HEAD, laptop.mine)
        val fresh = assertNotNull(laptop.refresh(alice.pubkey))
        assertEquals(listOf(aliceLaptop.pubkey), fresh.devices)
        assertTrue(fresh.root.id != oldHead.root.id, "a new root")
        assertNotNull(laptop.keys.secretFor(fresh.head.lb))
        // the phone reads the new anchor: it is out, and asks like any new device
        now += 5
        val seen = assertNotNull(phone.refresh(alice.pubkey))
        assertEquals(fresh.root.id, seen.root.id)
        assertEquals(SealedEngine.Mine.WAITING, phone.mine)
        assertTrue(phone.resetAt.containsKey(alice.pubkey))
    }

    /** No complete answer on this connection: Start fresh does nothing and says so. */
    @Test fun startFreshNeedsACompleteAnswer() = runBlocking<Unit> {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(alice.pubkey, aliceLaptop.pubkey, "Firefox on Linux")
        val phone = engine(alice, alicePhone); phone.start("Pixel 8", "phone")
        val laptop = engine(alice, aliceLaptop); laptop.start("Firefox on Linux", "computer")
        val before = relay.events.size
        relay.silent = true
        laptop.connectionLost()
        kotlin.test.assertFailsWith<SealedEngine.FreshNotDone> { laptop.startFresh("Firefox on Linux", "computer") }
        relay.silent = false
        assertEquals(before, relay.events.size, "nothing published")
        assertEquals(SealedEngine.Mine.WAITING, laptop.mine)
    }
}
