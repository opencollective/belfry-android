package sh.belfry.core.sealed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import sh.belfry.core.Vectors
import sh.belfry.core.dm.DirectMessages
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmHost
import sh.belfry.core.dm.DmReader
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.engine.SessionTyping
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.model.Notes
import sh.belfry.core.model.World
import sh.belfry.core.model.WorldContext
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.s
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.Target
import sh.belfry.core.read.ReadState
import sh.belfry.core.store.MemoryStore
import sh.belfry.core.typing.Typing
import sh.belfry.core.typing.TypingPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Notes to myself (collective-belfry docs/one-shape.md "Notes to myself"; SPEC 19.66): the `notes`
 * section of vectors/sealed.json, and the rules on the engine test's fake relay.
 */
class NotesTest {
    private val relay = SealedEngineTest.FakeRelay()
    private var now = 1_795_010_000L

    private inner class Ports(val account: LocalSigner, var saved: Map<String, String> = emptyMap()) : SealedPorts {
        var state: String? = null
        override suspend fun query(q: Query) = relay.query(q)
        override suspend fun publish(e: Event): Boolean { if (relay.refuse(e)) return false; relay.add(e); return true }
        override suspend fun signAsAccount(t: EventTemplate): Event = account.signNow(t)
        override suspend fun sessions(): List<SessionRow>? = emptyList()
        override suspend fun revoke(clientPubkey: String) {}
        override fun loadKeys() = saved
        override fun saveKeys(keys: Map<String, String>): Boolean { saved = keys; return true }
        override fun loadState(): String? = state
        override fun saveState(json: String) { state = json }
    }

    /** A signer that counts its calls: a sealed note must never reach it. */
    private class CountingSigner(val inner: LocalSigner) : Signer by inner {
        var calls = 0
        override suspend fun sign(template: EventTemplate): Event { calls++; return inner.sign(template) }
        override suspend fun nip44Encrypt(peer: String, plaintext: String): String { calls++; return inner.nip44Encrypt(peer, plaintext) }
        override suspend fun nip44Decrypt(peer: String, payload: String): String { calls++; return inner.nip44Decrypt(peer, payload) }
    }

    private inner class Host(override val signer: CountingSigner, override val sealed: SealedEngine?) : DmHost {
        val queued = ArrayList<OutboxItem>()
        override val me: String get() = signer.pubkey
        override val domain = "belfry.example"
        override val blossom = "https://files.belfry.example"
        override val localOnly = false
        override fun now() = now
        override fun bridgeKey(): String? = null
        override fun realName(pubkey: String) = ""
        override fun outbox(): List<OutboxItem> = queued
        override suspend fun enqueueSigned(target: Target, event: Event, dm: String?): String {
            val it = OutboxItem("l${queued.size}", target, event.kind, event.content, event.tags, event.createdAt, signed = event, dm = dm)
            queued.add(it); return it.localId
        }
        override suspend fun requeue(items: List<OutboxItem>) {}
        override fun addLocalEvents(events: List<Event>) {}
        override suspend fun queryMain(filter: JsonObject): List<Event> = emptyList()
        override suspend fun fetchBytes(url: String): ByteArray = ByteArray(0)
        override suspend fun nip05(name: String): String? = null
        override fun later(delayMs: Long, block: suspend () -> Unit) {}
        val files = ArrayList<OutboxItem>()
        val blobs = HashMap<String, ByteArray>()
        override fun putBlob(key: String, bytes: ByteArray) { blobs[key] = bytes }
        override fun blob(key: String): ByteArray? = blobs[key]
        override suspend fun enqueue(item: OutboxItem) { files.add(item) }
        override suspend fun uploadSealed(bytes: ByteArray, progress: (Long, Long) -> Unit): String = "https://files.belfry.example/" + sh.belfry.core.crypto.sha256Hex(bytes)
    }

    private val alice = LocalSigner.random()
    private val bob = LocalSigner.random()
    private val phone = LocalSigner.random()
    private val bobPhone = LocalSigner.random()
    private val me get() = alice.pubkey
    private val notes get() = Notes.conv(me)

    private fun engine(account: LocalSigner = alice, device: LocalSigner = phone, ports: Ports = Ports(account)) =
        SealedEngine(ports, device, account.pubkey, clock = { now }, removalJitter = 0)

    private class Side(val eng: SealedEngine?, val host: NotesTest.Host, val dm: DirectMessages, val signer: CountingSigner)

    private fun side(eng: SealedEngine?, account: LocalSigner = alice): Side {
        val s = CountingSigner(account)
        val h = Host(s, eng)
        return Side(eng, h, DirectMessages(MemoryStore(), h), s)
    }

    /** The rumor inside a sealed wrap of the outbox, opened with the engine's own keys. */
    private fun opened(eng: SealedEngine, wrap: Event): Opening.Opened = assertIs<Opening.Opened>(Sealing.open(wrap, eng.keys))

    // ---- the conversation and its row ------------------------------------------------------------

    @Test fun theConversationIsTheDmIdOfMe() {
        assertEquals("n:$me", Notes.conv(me))
        assertTrue(Notes.isNotes(Notes.conv(me), me))
        assertFalse(Notes.isNotes("n:${bob.pubkey}", me))
        assertFalse(Notes.isNotes("n:", ""), "no account, no notes")
        // the id a rumor of mine with one p tag, my own, lands in
        val r = GiftWrap.rumor(14, me, Notes.pTags(notes, me, MailThreads.peers(notes)), "x", now)
        assertEquals(listOf(listOf("p", me)), r.tags)
        assertEquals(notes, DmReader.convOf(r, me, null))
        // any other conversation keeps its p tags: the others, never me
        assertEquals(listOf(listOf("p", bob.pubkey)), Notes.pTags("n:${bob.pubkey}", me, listOf(bob.pubkey, me)))
    }

    private fun dm(rid: String, conv: String, from: String, at: Long, text: String) = Dm(rid, conv, from, from == me, at, 14, text)

    @Test fun theRowIsAlwaysFirstAndNeverUnread() {
        // empty: the row is there, alone
        val empty = DmSnapshot(emptyList(), emptyMap(), ReadState.EMPTY, me, now)
        assertEquals(listOf(notes), empty.messagesRows.map { it.conv })
        assertTrue(empty.messagesRows[0].notes)
        assertNull(empty.messagesRows[0].thread)
        assertNull(empty.lastNote)
        // an old note, newer messages from others: still first; the others newest first
        val carol = LocalSigner.random().pubkey
        val items = listOf(
            dm("a", notes, me, now - 5000, "Bring the extension reel"),
            dm("b", "n:${bob.pubkey}", bob.pubkey, now - 100, "See you Sunday!"),
            dm("c", "n:$carol", carol, now - 50, "Thanks for the soup!"),
            // a copy of a note that did not come back as mine (it cannot, but the count must not depend on it)
            dm("d", notes, me, now - 10, "Where the shed key hangs").copy(mine = false),
        )
        val s = DmSnapshot(items, emptyMap(), ReadState.EMPTY, me, now)
        assertEquals(listOf(notes, "n:$carol", "n:${bob.pubkey}"), s.messagesRows.map { it.conv })
        assertEquals(listOf(true, false, false), s.messagesRows.map { it.notes })
        assertEquals(0, s.messagesRows[0].unread)
        assertEquals(0, s.threads.first { it.conv == notes }.unread)
        assertEquals(2, s.unreadTotal, "the two conversations, never Notes")
        assertEquals("Where the shed key hangs", s.lastNote?.text)
        // no other conversation is the notes row
        assertEquals(1, s.messagesRows.count { it.notes })
    }

    @Test fun theWords() {
        assertEquals("Notes to myself", Notes.TITLE)
        assertEquals("Only you can read these", Notes.EMPTY_PREVIEW)
        assertEquals("Encrypted for your own devices. Nobody else can read your notes, not the belfry's admins either.", Notes.line(sealed = true))
        assertEquals("Private to your account. Not end-to-end encrypted on this account.", Notes.line(sealed = false))
        assertFalse(Notes.line(sealed = false).contains("obody else"), "never the claim on an account that is not sealed")
        assertEquals("Note to myself", Notes.PLACEHOLDER)
    }

    // ---- no typing events ------------------------------------------------------------------------

    @Test fun noTypingEvents() {
        assertTrue(Typing.recipients(notes, me).isEmpty(), "nobody to tell")
        val world = World(emptyList(), ctx = WorldContext(me = me))
        val typing = SessionTyping(CoroutineScope(Dispatchers.Unconfined), me, alice, { now }, { now.toDouble() }, { null }, { world }, { true }, { false })
        assertFalse(typing.allowed(TypingPlace.dm(notes)), "not sealed either: still none")
        assertTrue(typing.allowed(TypingPlace.dm("n:${bob.pubkey}")))
        // and one that arrives is no place
        assertNull(Typing.placeOfRumor(Typing.dmRumor(me, listOf(me), now = now), me))
    }

    // ---- sealing ---------------------------------------------------------------------------------

    @Test fun sealedWithoutReady() = runBlocking<Unit> {
        val eng = engine(); eng.start("Pixel 8", "phone") // the first device: a chain, no `ready` yet
        assertEquals(false, eng.chainOf(me)?.ready)
        val a = side(eng)
        a.dm.send(notes, "Bring the extension reel, 2 plugs")
        val wrap = a.host.queued.single().signed!! // the single wrap: the recipient's copy and mine are the same
        assertEquals(me, wrap.tag("p"))
        assertEquals(eng.chainOf(me)!!.head.lb, wrap.tag("lb"), "to my own head letterbox")
        assertEquals(0, a.signer.calls, "no signer call")
        val o = opened(eng, wrap)
        assertEquals(phone.pubkey, o.sealPubkey, "the seal is this device's")
        assertEquals(14, o.rumor.kind)
        assertEquals(me, o.rumor.pubkey)
        assertEquals(listOf(listOf("p", me)), o.rumor.tags, "exactly one p tag: my account")
        assertTrue(eng.sealedSince.containsKey(notes))
        // it comes back from the relay: the same note, once, never unread
        a.dm.offer(listOf(wrap)); a.dm.process()
        val snap = a.dm.snapshot.value
        assertEquals(listOf("Bring the extension reel, 2 plugs"), snap.thread(notes).map { it.text })
        assertEquals(0, snap.unreadTotal)
        assertEquals(0, a.signer.calls)
        // a reply, a reaction and its take-back are sealed the same way, each one wrap, one p tag
        val root = snap.thread(notes).single()
        a.dm.send(notes, "And the long ladder", root = root.rid)
        a.dm.react(root, "✅")
        a.dm.toggleReaction(root, "✅")
        val all = a.host.queued.map { it.signed!! }
        assertEquals(4, all.size)
        for (w in all) {
            assertNotNull(w.tag("lb"))
            assertEquals(listOf(me), opened(eng, w).rumor.tagValues("p"))
        }
        assertEquals(listOf(14, 14, 7, 5), all.map { opened(eng, it).rumor.kind })
        assertEquals(0, a.signer.calls)
    }

    /** `ready` is still what every other conversation waits for: the rule of §6 did not move. */
    @Test fun otherConversationsStillWaitForReady() = runBlocking<Unit> {
        val eng = engine(); eng.start("Pixel 8", "phone")
        val b = engine(bob, bobPhone); b.start("Bob's phone", "phone"); b.checkReady()
        assertEquals(true, b.chainOf(bob.pubkey)?.ready)
        assertEquals(SealedEngine.Plan.OldWay, eng.plan("n:${bob.pubkey}", listOf(bob.pubkey)), "my chain is not ready")
        // an e-mail thread of mine (no other participant either) is not Notes
        assertEquals(SealedEngine.Plan.OldWay, eng.plan("m:ana@example.org|visiting", emptyList()))
        assertIs<SealedEngine.Plan.Seal>(eng.plan(notes, emptyList()))
    }

    @Test fun aFileIsANoteToo() = runBlocking<Unit> {
        val eng = engine(); eng.start("Pixel 8", "phone")
        val a = side(eng)
        a.dm.sendFiles(notes, listOf(sh.belfry.core.dm.DmNewFile("shed.jpg", "image/jpeg", ByteArray(64) { 7 }, 4, 4)))
        val item = a.host.files.single()
        assertEquals(listOf(me), item.tags.filter { it[0] == "p" }.map { it[1] })
        val wraps = a.dm.prepareFile(item) {}
        val o = opened(eng, wraps.single())
        assertEquals(15, o.rumor.kind)
        assertEquals(listOf(me), o.rumor.tagValues("p"))
        assertEquals(0, a.signer.calls)
        assertEquals(notes, a.dm.snapshot.value.thread(notes).single().conv)
    }

    @Test fun noChainMakesOneAndNothingGoesUnsealed() = runBlocking<Unit> {
        val eng = engine()
        var made = 0
        eng.makeChain = { made++; eng.start("Pixel 8", "phone") }
        val a = side(eng)
        a.dm.send(notes, "The first note")
        assertEquals(1, made, "a definite no-chain answer: the first-device path, now")
        val chain = assertNotNull(eng.chainOf(me), "my chain is made")
        assertEquals(SealedEngine.Mine.IN_HEAD, eng.mine)
        assertTrue(relay.events.any { it.kind == 30078 && it.pubkey == me && it.tag("d") == Anchor.D }, "and anchored")
        val wrap = a.host.queued.single().signed!!
        assertEquals(chain.head.lb, wrap.tag("lb"))
        assertEquals(0, a.signer.calls, "nothing through the signer: nothing the old way")
        assertNull(a.dm.snapshot.value.thread(notes).single().heldSince)
    }

    /** The chain takes a while (the session makes it): the note is held, shown as sending, sealed once anchored. */
    @Test fun heldAsSendingUntilTheChainIsAnchored() = runBlocking<Unit> {
        val eng = engine()
        var asked = 0
        eng.makeChain = { asked++ } // started, not done yet
        val a = side(eng)
        a.dm.send(notes, "The first note")
        assertEquals(1, asked)
        assertTrue(a.host.queued.isEmpty(), "nothing goes out, sealed or not")
        assertEquals(0, a.signer.calls)
        val held = a.dm.snapshot.value.thread(notes).single()
        assertNotNull(held.heldSince)
        assertEquals(DirectMessages.NOTES_CHAIN, held.heldWhy)
        assertEquals("Sending…", a.dm.snapshot.value.stateLine(held))
        // still nothing a minute later: the same words, never "can't reach the belfry"
        now += 120
        a.dm.process()
        assertTrue(a.host.queued.isEmpty())
        assertEquals("Sending…", a.dm.snapshot.value.let { s -> s.stateLine(s.thread(notes).single()) })
        // the chain is there and anchored: sealed at the next pass
        eng.makeChain = null
        eng.start("Pixel 8", "phone")
        a.dm.process()
        val wrap = a.host.queued.single().signed!!
        assertEquals(eng.chainOf(me)!!.head.lb, wrap.tag("lb"))
        assertNull(a.dm.snapshot.value.thread(notes).single().heldSince)
        assertEquals(0, a.signer.calls)
        assertEquals("The first note", opened(eng, wrap).rumor.content)
    }

    @Test fun anIncompleteAnswerMakesNoChainAndSendsNothing() = runBlocking<Unit> {
        val eng = engine()
        var asked = 0
        eng.makeChain = { asked++; eng.start("Pixel 8", "phone") }
        val a = side(eng)
        for (how in listOf("silent", "closing")) {
            relay.silent = how == "silent"; relay.closing = how == "closing"
            a.dm.send(notes, "A note ($how)")
        }
        assertEquals(0, asked, "no definite answer: the chain is not made")
        assertTrue(relay.events.isEmpty(), "no roster, no anchor")
        assertTrue(a.host.queued.isEmpty(), "and nothing sent, sealed or the old way")
        assertEquals(0, a.signer.calls)
        assertTrue(a.dm.snapshot.value.thread(notes).all { it.heldSince != null })
        // the first complete answer decides: the chain, then both notes sealed
        relay.silent = false; relay.closing = false
        a.dm.process()
        assertEquals(1, asked)
        assertEquals(2, a.host.queued.size)
        assertTrue(a.host.queued.all { it.signed!!.tag("lb") != null })
        assertEquals(0, a.signer.calls)
    }

    /** The relay refused my first roster: this device cannot make the chain. The note waits; it is not sent unsealed. */
    @Test fun aRefusedChainNeverFallsBack() = runBlocking<Unit> {
        val eng = engine()
        eng.makeChain = { eng.start("Pixel 8", "phone") }
        relay.refusing.add(Roster.KIND)
        val a = side(eng)
        a.dm.send(notes, "A note")
        assertTrue(a.host.queued.isEmpty())
        assertEquals(0, a.signer.calls)
        assertEquals(SealedEngine.Plan.Hold, eng.plan(notes, emptyList()))
    }

    @Test fun aDeviceOutsideMyHeadWaits() = runBlocking<Unit> {
        val first = engine(); first.start("Pixel 8", "phone")
        val tablet = LocalSigner.random()
        val eng = engine(device = tablet); eng.start("Galaxy Tab", "tablet")
        assertEquals(SealedEngine.Mine.WAITING, eng.mine)
        val a = side(eng)
        a.dm.send(notes, "From the tablet")
        assertTrue(a.host.queued.isEmpty(), "not sealed by a device outside the head, and never the old way")
        assertEquals(0, a.signer.calls)
        assertEquals(DirectMessages.LOCKED, a.dm.snapshot.value.thread(notes).single().heldWhy, "the usual waiting words")
        assertEquals(SealedEngine.Plan.Locked, eng.plan(notes, emptyList()))
    }

    @Test fun anAccountThatCannotSeal() = runBlocking<Unit> {
        val a = side(null) // an agent, a member holding their own key, a guest: no device key, no engine
        a.dm.send(notes, "Private to my account")
        val wrap = a.host.queued.single().signed!!
        assertEquals(listOf(listOf("p", me), listOf("silent", "1")), wrap.tags, "a NIP-17 wrap to my own account: no lb; silent")
        assertFalse(Sealing.isSealed(wrap))
        assertTrue(a.signer.calls > 0, "through the signer, as old direct messages")
        val o = assertNotNull(GiftWrap.unwrap(wrap, alice))
        assertEquals(me, o.sealPubkey)
        assertEquals(14, o.rumor.kind)
        assertEquals(listOf(listOf("p", me)), o.rumor.tags)
        a.dm.offer(listOf(wrap)); a.dm.process()
        assertEquals(listOf("Private to my account"), a.dm.snapshot.value.thread(notes).map { it.text })
        assertEquals(0, a.dm.snapshot.value.unreadTotal)
        // a guest's engine (tests only: a guest session has none) follows the general rule, it makes no chain
        val g = SealedEngine(Ports(alice), phone, me, clock = { now }, guest = true, removalJitter = 0)
        g.makeChain = { throw AssertionError("a guest never makes a chain") }
        assertEquals(SealedEngine.Plan.OldWay, g.plan(notes, emptyList()))
    }

    /** Old unsealed notes stay readable; one that arrives the old way after sealing is marked like any old message. */
    @Test fun oldNotesStayAndLateOnesAreMarked() = runBlocking<Unit> {
        val before = side(null)
        before.dm.send(notes, "An old note")
        val oldWrap = before.host.queued.single().signed!!
        val eng = engine(); eng.start("Pixel 8", "phone")
        val a = side(eng)
        a.dm.offer(listOf(oldWrap)); a.dm.process()
        now += 10
        a.dm.send(notes, "A sealed note")
        now += 100
        before.dm.send(notes, "Written the old way, later")
        a.dm.offer(listOf(before.host.queued.last().signed!!)); a.dm.process()
        val t = a.dm.snapshot.value.thread(notes)
        assertEquals(listOf("An old note", "A sealed note", "Written the old way, later"), t.map { it.text })
        assertEquals(listOf(false, false, true), t.map { it.notSealed })
    }

    // ---- silent wraps (docs/sealed.md §4 "Silent wraps", SPEC 19.67) -----------------------------

    private fun silent(w: Event) = GiftWrap.isSilent(w)

    /** Alice and Bob, both ready, on their phones. */
    private fun both(): Pair<Side, Side> = runBlocking {
        val a = engine(); a.start("Pixel 8", "phone"); a.checkReady()
        val b = engine(bob, bobPhone); b.start("Bob's phone", "phone"); b.checkReady()
        side(a) to side(b, bob)
    }

    /** What this implementation makes, by the vectors' names ('carries'); the 8563 and the mail are in the engine's own tests. */
    private fun observed(): Map<String, Boolean> = runBlocking {
        val out = LinkedHashMap<String, Boolean>()
        val (a, b) = both()
        val withBob = "n:${bob.pubkey}"
        a.dm.send(withBob, "See you Sunday!")
        val sealed = a.host.queued.map { it.signed!! }
        assertEquals(2, sealed.size); assertTrue(sealed.all { Sealing.isSealed(it) })
        out["sealed message: the copy to myself"] = silent(sealed.single { it.tag("p") == me })
        out["sealed message: the wrap for the other person"] = silent(sealed.single { it.tag("p") == bob.pubkey })
        a.dm.send(notes, "A note")
        out["a sealed note to myself"] = silent(a.host.queued.last().signed!!.also { assertTrue(Sealing.isSealed(it)); assertEquals(me, it.tag("p")) })
        // the old way: an account with no device key
        val o = side(null)
        o.dm.send(withBob, "The old way")
        val old = o.host.queued.map { it.signed!! }
        assertEquals(2, old.size); assertTrue(old.none { Sealing.isSealed(it) })
        out["old-style message: the copy to myself"] = silent(old.single { it.tag("p") == me })
        out["old-style message: the wrap for the other person"] = silent(old.single { it.tag("p") == bob.pubkey })
        o.dm.send(notes, "An old note")
        out["an old-style note to myself"] = silent(o.host.queued.last().signed!!)
        // keys for one of my devices (§5.5)
        val tablet = LocalSigner.random().pubkey
        val keys = Sealing.keysFor(phone, me, tablet, a.eng!!.keys, now)
        assertTrue(keys.isNotEmpty() && keys.all { it.tag("p") == tablet })
        out["keys for one of my devices"] = keys.all { silent(it) }
        // history shared with Bob (§8)
        val before = relay.events.size
        a.eng.shareHistory(bob.pubkey, a.dm.sealedRumorsOf(withBob))
        val shares = relay.events.drop(before).filter { it.kind == 1059 }
        assertTrue(shares.isNotEmpty() && shares.all { it.tag("p") == bob.pubkey })
        out["shared history for someone else"] = shares.any { silent(it) }
        // and every one of them reads as before on the other side
        b.dm.offer(sealed); b.dm.process()
        assertEquals(listOf("See you Sunday!"), b.dm.snapshot.value.thread("n:$me").map { it.text })
        out
    }

    @Test fun silentOnlyOnWrapsToMyself() {
        val got = observed()
        assertEquals(
            mapOf(
                "sealed message: the copy to myself" to true, "sealed message: the wrap for the other person" to false,
                "a sealed note to myself" to true,
                "old-style message: the copy to myself" to true, "old-style message: the wrap for the other person" to false,
                "an old-style note to myself" to true,
                "keys for one of my devices" to true, "shared history for someone else" to false,
            ),
            got,
        )
    }

    /** A queued self-copy sealed again after my head moved keeps the tag; the other side's never gets it. */
    @Test fun silentSurvivesAReseal() = runBlocking<Unit> {
        val (a, _) = both()
        val eng = a.eng!!
        eng.refresh(bob.pubkey)
        val rumor = GiftWrap.rumor(14, me, listOf(listOf("p", bob.pubkey)), "x", now)
        for (who in listOf(me, bob.pubkey)) {
            val q = SealedEngine.Queued(rumor, who, sh.belfry.core.crypto.Hex.encode(ByteArray(32) { 3 }), Sealing.seal(rumor, phone, who, eng.chainOf(who)!!.head.lb, now))
            val again = eng.resealIfNeeded(q)
            assertEquals(eng.chainOf(who)!!.head.lb, again.wrap.tag("lb"), "sealed again to the head")
            assertEquals(who == me, silent(again.wrap))
        }
    }

    /** The tag is on the wrap only: seals and rumors are what they were. */
    @Test fun silentIsOnTheWrapOnly() = runBlocking<Unit> {
        val eng = engine(); eng.start("Pixel 8", "phone")
        val a = side(eng)
        a.dm.send(notes, "A note")
        val w = a.host.queued.single().signed!!
        assertEquals(listOf(listOf("p", me), listOf("lb", eng.chainOf(me)!!.head.lb), listOf("silent", "1")), w.tags)
        val o = opened(eng, w)
        assertEquals(listOf(listOf("p", me)), o.rumor.tags)
        val old = side(null)
        old.dm.send(notes, "An old note")
        val ow = old.host.queued.single().signed!!
        assertEquals(listOf(listOf("p", me)), assertNotNull(GiftWrap.unwrap(ow, alice)).rumor.tags)
    }

    private val sv get() = v["silent"] as JsonObject

    @Test fun vectorsSilentCarries() {
        val all = (sv["carries"] as JsonArray).map { it as JsonObject }
        val got = observed()
        // made by the engine's own paths and checked there: SealedEngineTest.itWasMeOnceForAll, DirectMessagesSealedTest (mail)
        val elsewhere = mapOf("an 8563" to true, "a mail for the belfry" to false)
        assertEquals((got.keys + elsewhere.keys).toSet(), all.map { it.s("what")!! }.toSet())
        for (c in all) {
            val what = c.s("what")!!
            assertEquals((c["expected"] as JsonObject)["silent"]!!.jsonPrimitive.boolean, got[what] ?: elsewhere.getValue(what), what)
        }
    }

    @Test fun vectorsSilentTags() {
        assertEquals(strs(sv["tag"]), GiftWrap.SILENT)
        val all = (sv["tags"] as JsonArray).map { it as JsonObject }
        assertTrue(all.size >= 12)
        for (c in all) {
            val tags = (c["tags"] as JsonArray).map { strs(it) }
            val wrap = Event("", "", 0, 1059, tags, "", "")
            assertEquals((c["expected"] as JsonObject)["silent"]!!.jsonPrimitive.boolean, GiftWrap.isSilent(wrap), c.s("name"))
        }
        // never read on anything but a wrap
        assertFalse(GiftWrap.isSilent(Event("", "", 0, 14, listOf(GiftWrap.SILENT), "", "")))
    }

    @Test fun vectorsSilentWrapsOpenAsBefore() = runBlocking<Unit> {
        val account = LocalSigner((keys[sv.s("account")!!] as JsonObject).s("secret")!!)
        val kc = KeyChain().also { it.add(pub(sv.s("letterbox")!!), sv.s("letterbox_secret")!!) }
        val all = (sv["wraps"] as JsonArray).map { it as JsonObject }
        assertTrue(all.size >= 4)
        for (c in all) {
            val name = c.s("name")!!
            val e = c["expected"] as JsonObject
            val wrap = ev(c["wrap"] as JsonObject)
            assertTrue(wrap.isValid(), name)
            assertEquals(e["silent"]!!.jsonPrimitive.boolean, GiftWrap.isSilent(wrap), name)
            val want = (e["rumor"] as JsonObject).s("id")
            if (c["sealed"]!!.jsonPrimitive.boolean) {
                val o = assertIs<Opening.Opened>(Sealing.open(wrap, kc), name)
                assertEquals(want, o.rumor.id, name); assertEquals(e.s("seal_pubkey"), o.sealPubkey, name)
            } else {
                assertEquals(Opening.NotSealed, Sealing.open(wrap, kc), name)
                val o = assertNotNull(GiftWrap.unwrap(wrap, account), name)
                assertEquals(want, o.rumor.id, name); assertEquals(e.s("seal_pubkey"), o.sealPubkey, name)
            }
        }
        // and through the whole reading path: the sealed one lands in Notes once, the old-style one too
        relay.events.clear()
        val root = ((v["notes"] as JsonObject)["wrap"] as JsonObject)["chain"] as JsonObject
        relay.add(ev(root["anchor"] as JsonObject)); evs(root["rosters"]).forEach { relay.add(it) }
        val device = LocalSigner((keys["alice_phone"] as JsonObject).s("secret")!!)
        val eng = engine(account, device, Ports(account, mapOf(pub(sv.s("letterbox")!!) to sv.s("letterbox_secret")!!)))
        eng.start("Pixel 8", "phone")
        val a = side(eng, account)
        a.dm.offer(all.map { ev(it["wrap"] as JsonObject) }); a.dm.process()
        assertEquals(listOf("Where the shed key hangs"), a.dm.snapshot.value.thread(Notes.conv(account.pubkey)).map { it.text })
    }

    // ---- vectors/sealed.json "notes" -------------------------------------------------------------

    private val v = Vectors.load("sealed.json")
    private val n = v["notes"] as JsonObject
    private val keys = v["keys"] as JsonObject
    private fun pub(name: String) = (keys[name] as JsonObject).s("pubkey")!!
    private fun secretOf(pubkey: String) = keys.values.map { it as JsonObject }.first { it.s("pubkey") == pubkey }.s("secret")!!
    private fun ev(o: JsonObject): Event = Event.fromJson(o)!!
    private fun evs(a: Any?): List<Event> = (a as? JsonArray)?.map { ev(it as JsonObject) } ?: emptyList()
    private fun strs(a: Any?) = (a as JsonArray).map { it.jsonPrimitive.content }
    private fun rumorOf(o: JsonObject): Event =
        GiftWrap.rumor(o["kind"]!!.jsonPrimitive.int, o.s("pubkey")!!, ev(JsonObject(o + mapOf("sig" to JsonPrimitive("")))).tags, o.s("content")!!, o["created_at"]!!.jsonPrimitive.long)

    @Test fun vectorsRumors() {
        val account = n.s("account")!!
        val c = n["conversation"] as JsonObject
        assertEquals(listOf(account), strs(c["participants"]))
        assertEquals(c.s("android_id"), Notes.conv(account))
        val all = (n["rumors"] as JsonArray).map { it as JsonObject }
        assertTrue(all.size >= 9)
        for (k in all) {
            val name = k.s("name")!!
            val r = k["rumor"] as JsonObject
            val rumor = rumorOf(r)
            assertEquals(r.s("id"), rumor.id, name)
            val e = k["expected"] as JsonObject
            assertEquals(strs(e["participants"]), (listOf(rumor.pubkey) + rumor.tagValues("p")).toSortedSet().toList(), name)
            val conv = DmReader.convOf(rumor, account, null)
            assertEquals(e["notes"]!!.jsonPrimitive.boolean, Notes.isNotes(conv, account), name)
        }
        // what this implementation writes for a note is the first case's rumor, to the letter
        val first = all.first()["rumor"] as JsonObject
        val conv = Notes.conv(account)
        val mine = GiftWrap.rumor(14, account, Notes.pTags(conv, account, MailThreads.peers(conv)), first.s("content")!!, first["created_at"]!!.jsonPrimitive.long)
        assertEquals(first.s("id"), mine.id)
        assertEquals(first.toString(), mine.copy(sig = "").toJsonString(), "serialised as the vectors are")
    }

    @Test fun vectorsWrap() {
        val w = n["wrap"] as JsonObject
        val e = w["expected"] as JsonObject
        val ch = w["chain"] as JsonObject
        val chain = Chain.of(n.s("account")!!, listOf(ev(ch["anchor"] as JsonObject)), evs(ch["rosters"]))!!
        assertEquals(e["chain_ready"]!!.jsonPrimitive.boolean, chain.ready)
        val wrap = ev(w["wrap"] as JsonObject)
        assertEquals(n.s("account"), wrap.tag("p"))
        assertEquals(chain.head.lb, wrap.tag("lb"), "to my own head letterbox")
        val o = assertIs<Opening.Opened>(Sealing.open(wrap, KeyChain().also { it.add(pub(w.s("letterbox")!!), w.s("letterbox_secret")!!) }))
        assertEquals(e.s("seal_pubkey"), o.sealPubkey)
        assertEquals((e["rumor"] as JsonObject).s("id"), o.rumor.id)
        assertEquals(listOf(listOf("p", n.s("account")!!)), o.rumor.tags)
        val verdict = Sealing.verdict(o, chain, firstSeen = wrap.createdAt, now = wrap.createdAt + 10, myLetterboxes = chain.letterboxes.toSet())
        assertEquals(e["accepted"]!!.jsonPrimitive.boolean, verdict == Sealing.Verdict.ACCEPT, "accepted without ready")
        assertEquals(1, e["wraps"]!!.jsonPrimitive.int)
    }

    @Test fun vectorsPlans() = runBlocking<Unit> {
        val account = LocalSigner(secretOf(n.s("account")!!))
        val conv = Notes.conv(account.pubkey)
        val lbs = listOf("alice_lb1", "alice_lb2", "alice_lb3").associate { pub(it) to (keys[it] as JsonObject).s("secret")!! }
        val all = (n["plans"] as JsonArray).map { it as JsonObject }
        assertTrue(all.size >= 6)
        for (k in all) {
            val name = k.s("name")!!
            val e = k["expected"] as JsonObject
            val decision = e.s("decision")!!
            relay.events.clear()
            relay.silent = k.s("answer") != "complete"
            (k["chain"] as? JsonObject)?.let { c -> relay.add(ev(c["anchor"] as JsonObject)); evs(c["rosters"]).forEach { relay.add(it) } }
            val before = relay.events.count { it.kind == Roster.KIND }
            if (!k["can_seal"]!!.jsonPrimitive.boolean) {
                val a = side(null, account)
                a.dm.send(conv, "x")
                assertEquals("old_way", decision, name)
                assertEquals(e["wraps"]!!.jsonPrimitive.int, a.host.queued.size, name)
                assertTrue(a.host.queued.none { Sealing.isSealed(it.signed!!) }, name)
                continue
            }
            val device = LocalSigner(secretOf(k.s("device")!!))
            val chainHere = k["chain"] is JsonObject
            // a device of the chain holds its letterbox secrets; one outside it holds none
            val inChain = chainHere && evs((k["chain"] as JsonObject)["rosters"]).any { r -> r.tags.any { it.size >= 2 && it[0] == "device" && it[1] == device.pubkey } }
            val eng = engine(account, device, Ports(account, if (inChain) lbs else emptyMap()))
            var made = 0
            eng.makeChain = { made++; eng.start("Pixel 8", "phone") }
            if (chainHere) eng.start("Pixel 8", "phone")
            val a = side(eng, account)
            a.dm.send(conv, "x")
            val wraps = a.host.queued.map { it.signed!! }
            assertEquals(false, e["old_way"]!!.jsonPrimitive.boolean, name)
            assertTrue(wraps.all { Sealing.isSealed(it) }, "$name: never the old way")
            assertEquals(0, a.signer.calls, "$name: no signer call")
            assertEquals(e["makes_chain"]!!.jsonPrimitive.boolean, made > 0, name)
            val held = a.dm.snapshot.value.thread(conv).single()
            when (decision) {
                "seal" -> {
                    assertEquals(e["wraps"]!!.jsonPrimitive.int, wraps.size, name)
                    assertEquals(e.s("lb"), wraps.single().tag("lb"), name)
                    assertEquals(account.pubkey, wraps.single().tag("p"), name)
                    assertEquals(before, relay.events.count { it.kind == Roster.KIND }, "$name: no roster is made")
                }
                "wait_for_device" -> { assertTrue(wraps.isEmpty(), name); assertEquals(DirectMessages.LOCKED, held.heldWhy, name) }
                "hold" -> { assertTrue(wraps.isEmpty(), name); assertNotNull(held.heldSince, name); assertTrue(relay.events.isEmpty(), "$name: no chain made") }
                "make_chain_then_seal" -> {
                    // here the chain is anchored within the send, so the note is sealed at once (else it is held: see above)
                    assertNotNull(eng.chainOf(account.pubkey), name)
                    assertEquals(e["wraps_once_anchored"]!!.jsonPrimitive.int, wraps.size, name)
                    assertEquals(eng.chainOf(account.pubkey)!!.head.lb, wraps.single().tag("lb"), name)
                }
                else -> throw AssertionError("$name: unknown decision $decision")
            }
        }
        relay.silent = false
    }
}
