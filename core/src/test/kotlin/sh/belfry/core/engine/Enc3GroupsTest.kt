package sh.belfry.core.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.enc.Enc
import sh.belfry.core.enc.EncJson
import sh.belfry.core.enc.EncKeys
import sh.belfry.core.enc.EncMessages
import sh.belfry.core.enc.EncPlan
import sh.belfry.core.enc.EncText
import sh.belfry.core.enc.EncWrite
import sh.belfry.core.enc.EncWrites
import sh.belfry.core.enc.HoldReason
import sh.belfry.core.model.Buttons
import sh.belfry.core.model.GroupLine
import sh.belfry.core.model.GroupLines
import sh.belfry.core.model.MessageButton
import sh.belfry.core.model.World
import sh.belfry.core.model.WorldContext
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Tag
import sh.belfry.core.sealed.SealedEngine
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC 19.88: private groups in format 3, reading and writing, on fake community and groups relays (no network):
 * Alice, Bob and Carol, one phone each, the groups key as the belfry's sender, the group "pod-garden".
 */
class Enc3GroupsTest {
    private val belfrySecret = Keys.generateSecret()
    private val belfry = Enc.pub(belfrySecret)
    private val groupsSigner = LocalSigner.random()
    private val groupsKey = groupsSigner.pubkey
    private val relayKey = LocalSigner.random()
    private val alice = LocalSigner.random()
    private val bob = LocalSigner.random()
    private val carol = LocalSigner.random()
    private val alicePhone = LocalSigner.random()
    private val bobPhone = LocalSigner.random()
    private val carolPhone = LocalSigner.random()
    private val g = "pod-garden"

    private var nowMs = System.currentTimeMillis()
    private fun now() = nowMs / 1000

    /** The community relay (lists) and the groups relay (envelopes, messages, member lists). */
    private val main = ArrayList<Event>()
    private val groups = ArrayList<Event>()

    private fun list(account: String, n: Int, prev: Event?, devices: List<String>, at: Long = 1000L + n): Event {
        val tags = ArrayList<Tag>()
        tags += listOf("p", account); tags += listOf("n", "$n")
        if (prev != null) tags += listOf("prev", prev.id)
        devices.sorted().forEach { tags += listOf("device", it) }
        return Enc.signWith(belfrySecret, Enc.LIST, at, tags, "")
    }

    private fun members(vararg who: String, at: Long = now() - 10): Event =
        relayKey.signNow(EventTemplate(39002, "", listOf(listOf("d", g)) + who.map { listOf("p", it) }, at))

    private fun strings(el: kotlinx.serialization.json.JsonElement?): List<String>? = (el as? JsonArray)?.map { (it as JsonPrimitive).content }

    private fun matches(e: Event, f: JsonObject): Boolean {
        strings(f["kinds"])?.let { k -> if (e.kind.toString() !in k) return false }
        strings(f["authors"])?.let { a -> if (e.pubkey !in a) return false }
        for ((name, v) in f) if (name.startsWith("#")) {
            val vals = strings(v) ?: continue
            if (e.tags.none { it.size >= 2 && it[0] == name.drop(1) && it[1] in vals }) return false
        }
        (f["until"] as? JsonPrimitive)?.long?.let { if (e.createdAt > it) return false }
        return true
    }

    private val later = ArrayList<suspend () -> Unit>()
    private fun runLater() = runBlocking { var n = 0; while (later.isNotEmpty() && n++ < 200) later.removeAt(0)() }

    /** One device: its runtime over the fake relays. */
    private inner class Device(val account: LocalSigner, val device: LocalSigner) {
        var mainUp = true
        var groupsUp = true
        var groupsConn = 1
        var memberSet: Set<String>? = null
        /** SPEC 19.94: the groups relay's NIP-11 `self` as this device knows it (null: not read). */
        var relayKnown: String? = relayKey.pubkey
        var privates = setOf(g)
        val guests = HashSet<String>()
        val opened = ArrayList<Event>()
        val e3 = Enc3(device, account.pubkey, object : Enc3Ports {
            override fun belfryKey(): String? = belfry
            override fun groupsKey(): String? = groupsKey
            override fun connection(): Int? = if (mainUp) 1 else null
            override suspend fun query(filter: JsonObject, pageMs: Long): SealedEngine.Answer? = if (!mainUp) null else SealedEngine.Answer(main.filter { matches(it, filter) }, true)
            override fun addEvents(events: List<Event>) {}
            override suspend fun chatSessions(): Int? = 1
            override suspend fun enqueueByDevice(kind: Int, tags: List<Tag>, content: String) {}
            override fun resubscribe() {}
            override fun later(block: suspend () -> Unit) { later += block }
            override fun nowMs(): Long = nowMs
        }, null)
        val gr = Enc3Groups(e3, object : Enc3GroupPorts {
            override fun groupsKey(): String? = groupsKey
            override fun belfryKey(): String? = belfry
            override fun connection(): Int? = if (groupsUp) groupsConn else null
            override suspend fun query(filter: JsonObject, pageMs: Long): SealedEngine.Answer? = if (!groupsUp) null else SealedEngine.Answer(groups.filter { matches(it, filter) }, true)
            override fun addEvents(events: List<Event>) {}
            override fun privateMeta(h: String): Boolean = h in privates
            override fun members(h: String): Set<String>? = memberSet
            override fun relayKey(): String? = relayKnown
            override fun isGuest(account: String): Boolean = account in guests
            override fun opened(rumors: List<Event>) { opened += rumors }
            override fun later(block: suspend () -> Unit) { later += block }
            override fun nowMs(): Long = nowMs
        })
        val me get() = account.pubkey

        init { e3.offer(main.filter { it.kind == Enc.LIST }) }

        /** The session's decision (as Session.encWriteGroup): format 3, then the checks. */
        fun write(kind: Int = 9, tags: List<Tag> = listOf(listOf("h", g))): EncWrite = runBlocking {
            val w = EncWrites.group(EncWrites.Facts(false, false, 3), e3.everEncrypted(Enc3.groupConv(g)), g in privates, true, sh.belfry.core.enc.EncRules.clearInGroup(Event("", me, 0, kind, tags, ""), groupsKey))
            if (w is EncWrite.V3) gr.ready(g) else w
        }

        /** What Session.sendGroupEncrypted publishes: the envelopes (signed by my account), then the 8572. */
        fun send(text: String, kind: Int = 9, tags: List<Tag> = listOf(listOf("h", g)), mentions: List<String> = emptyList(), confirm: Boolean = true): Pair<GroupSealed, List<Event>> {
            // the checks alone (the table is its own test): a clear-list kind sealed here tests the reader's drop
            val w = runBlocking { gr.ready(g) }
            val plan = assertIs<EncWrite.V3>(w, "held: $w").plan as EncPlan.Group
            val s = gr.seal(g, EventTemplate(kind, text, tags, now()), plan, mentions)
            val out = s.before.map { account.signNow(it) } + account.signNow(s.message)
            if (confirm) {
                groups += out
                if (s.before.isNotEmpty()) gr.envelopesOut(s.key)
                gr.sent(out.last(), s.rumor)
                gr.offer(out)
            }
            return s to out
        }

        fun receive(events: List<Event> = groups.toList()): List<Event> {
            val before = opened.size
            gr.offer(events)
            runLater()
            return opened.drop(before)
        }
    }

    private lateinit var aliceList: Event
    private lateinit var bobList: Event
    private lateinit var carolList: Event

    private fun setUp(vararg memberKeys: String = arrayOf(alice.pubkey, bob.pubkey)): Pair<Device, Device> {
        aliceList = list(alice.pubkey, 1, null, listOf(alicePhone.pubkey))
        bobList = list(bob.pubkey, 1, null, listOf(bobPhone.pubkey))
        carolList = list(carol.pubkey, 1, null, listOf(carolPhone.pubkey))
        main += listOf(aliceList, bobList, carolList)
        groups += members(*memberKeys)
        val a = Device(alice, alicePhone)
        val b = Device(bob, bobPhone)
        return a to b
    }

    // ---- writing ----------------------------------------------------------------------------------------

    @Test fun envelopesBeforeTheMessageSignedByTheAccountSealedByTheDeviceMentionsAsP() {
        val (a, b) = setUp()
        val (s, out) = a.send("The seed order is in. nostr:" + sh.belfry.core.crypto.Bech32.npub(bob.pubkey), mentions = listOf(bob.pubkey))
        assertEquals(listOf(Enc.KEY, Enc.MSG), out.map { it.kind }, "the key's envelope goes first")
        val env = out[0]
        val msg = out[1]
        assertEquals(alice.pubkey, env.pubkey, "the envelope is signed by my account (through the signer)")
        assertEquals(listOf(g), Enc.values(env, "h"))
        assertEquals(s.key, Enc.one(env, "d"))
        assertEquals(alicePhone.pubkey, Enc.one(env, "maker"))
        assertEquals(alice.pubkey, msg.pubkey, "the 8572 is my account's")
        assertEquals(listOf(g), Enc.values(msg, "h"))
        assertEquals(s.key, Enc.one(msg, "d"))
        assertEquals(listOf(bob.pubkey), Enc.values(msg, "p"), "the outer p only for who is mentioned (§17.14)")
        val k = a.e3.key(s.key)!!
        assertEquals(alicePhone.pubkey, EncMessages.openGroupMessage(k.secret, msg).seal.pubkey, "the seal is the device's")
        assertFalse(msg.content.contains("seed"), "nothing of the text in clear")
        assertEquals(listOf(alicePhone.pubkey, bobPhone.pubkey).sorted(), EncKeys.entryKeys(listOf(env)), "an entry for every member's device")
        // Bob opens it into his log as the group's own event
        val got = b.receive(out).single()
        assertEquals(9, got.kind)
        assertEquals(alice.pubkey, got.pubkey)
        assertEquals("", got.sig, "a rumor: no signature")
        assertEquals(s.rumor.id, got.id)
        assertTrue(got.content.startsWith("The seed order is in."))
        assertTrue(b.e3.everEncrypted(Enc3.groupConv(g)))
        val held = b.e3.key(s.key)!!
        assertEquals(Enc3.groupConv(g), held.conv)
        assertFalse(held.mine)
        assertEquals(held.claimed, held.lists, "its entries add up: adoptable")
        assertEquals(s.rumor.id, b.gr.rumorFor(msg.id), "a push's outer id leads to the message")
    }

    @Test fun aKeyIsReusedThenANewOneWhenMembersOrListsChange() {
        val (a, _) = setUp()
        val (s1, _) = a.send("one")
        val (s2, out2) = a.send("two")
        assertEquals(s1.key, s2.key)
        assertEquals(listOf(Enc.MSG), out2.map { it.kind }, "no new envelope while nothing changed")
        // Carol joins: a new key, with her device
        groups += members(alice.pubkey, bob.pubkey, carol.pubkey, at = now())
        a.gr.forgetMembers(g)
        val (s3, out3) = a.send("three")
        assertNotEquals(s1.key, s3.key)
        assertTrue(carolPhone.pubkey in EncKeys.entryKeys(out3.filter { it.kind == Enc.KEY }))
        // Bob signs in on a laptop: his list changes, a new key again
        val laptop = LocalSigner.random()
        val bob2 = list(bob.pubkey, 2, bobList, listOf(bobPhone.pubkey, laptop.pubkey))
        main += bob2; a.e3.offer(listOf(bob2))
        val (s4, out4) = a.send("four")
        assertNotEquals(s3.key, s4.key)
        assertTrue(laptop.pubkey in EncKeys.entryKeys(out4.filter { it.kind == Enc.KEY }))
        // 31 days on: a new key, nothing else changed
        nowMs += 31L * 86_400_000
        assertNotEquals(s4.key, a.send("five").first.key)
    }

    @Test fun anotherMembersKeyIsAdoptedOnlyWhenItsEntriesAddUp() {
        val (a, b) = setUp()
        val (s1, out) = a.send("hi")
        b.receive(out)
        val (sb, outB) = b.send("hello back")
        assertEquals(s1.key, sb.key, "Bob sends with Alice's key: its entry set is his device set")
        assertEquals(listOf(Enc.MSG), outB.map { it.kind })
        // a stray envelope event of that d adds a device key: the entries no longer add up
        val stray = carol.signNow(EventTemplate(Enc.KEY, EncJson.stringify(JsonObject(mapOf(carolPhone.pubkey to JsonPrimitive("x")))), listOf(listOf("h", g), listOf("d", s1.key), listOf("maker", carolPhone.pubkey)), now()))
        groups += stray
        b.receive(listOf(stray))
        assertTrue(b.gr.cantCheck(g), "Can't check who can read this")
        val (sb2, outB2) = b.send("again")
        assertNotEquals(s1.key, sb2.key, "never adopted while it doesn't add up: Bob makes his own")
        assertEquals(Enc.KEY, outB2.first().kind)
        // and Alice's messages under it still open
        val (_, more) = a.send("still readable")
        assertEquals("still readable", b.receive(more).single().content)
    }

    @Test fun aServiceKeyIsNeverSentWithAndItsAnswerIsShown() {
        val (a, b) = setUp()
        val (_, out) = a.send("hi")
        b.receive(out)
        // the groups key answers into the group under a key it made for the group's devices
        val gk = EncKeys.newKey()
        val envT = EncKeys.groupEnvelopes(Hex.decode(groupsSigner.secretHex), gk.secret, g, listOf(alicePhone.pubkey, bobPhone.pubkey), "")
        val answer = Enc.withId(Event("", groupsKey, now(), 1985, listOf(listOf("L", Buttons.RESULT_NS), listOf("l", "ok", Buttons.RESULT_NS), listOf("e", "a".repeat(64)), listOf("press", "b".repeat(64)), listOf("h", g)), "Published."))
        val msg = EncMessages.groupMessage(Hex.decode(groupsSigner.secretHex), answer, gk.pub, g)
        val evs = envT.map { groupsSigner.signNow(it) } + groupsSigner.signNow(msg)
        groups += evs
        val got = b.receive(evs).single()
        assertEquals(groupsKey, got.pubkey)
        assertEquals(Buttons.RESULT_NS, Enc.one(got, "L"), "the service key's answer to a press comes through")
        assertTrue(b.e3.key(gk.pub)!!.service)
        // Bob's next message: never the groups key's key
        val (sb, _) = b.send("thanks")
        assertNotEquals(gk.pub, sb.key)
    }

    @Test fun aKeyWhoseMakerIsNotListedIsRefusedAndOneWhoseListsAreComingWaits() {
        val (a, b) = setUp()
        // a maker device that was never on Alice's lists
        val stranger = LocalSigner.random()
        val k = EncKeys.newKey()
        val env = alice.signNow(EncKeys.groupEnvelopes(Hex.decode(stranger.secretHex), k.secret, g, listOf(bobPhone.pubkey), "").single())
        val r = Enc.withId(Event("", alice.pubkey, now(), 9, listOf(listOf("h", g)), "not from her phone"))
        val msg = alice.signNow(EncMessages.groupMessage(Hex.decode(stranger.secretHex), r, k.pub, g))
        groups += listOf(env, msg)
        assertTrue(b.receive(listOf(env, msg)).isEmpty(), "the maker isn't on its account's list: no key")
        assertNull(b.e3.key(k.pub))
        // Carol's lists aren't on the relay yet: her key waits, and opens once they come
        main.remove(carolList)
        val c2 = Device(carol, carolPhone)
        assertTrue(c2.e3.chainOnly(carol.pubkey) == null)
        val k2 = EncKeys.newKey()
        val env2 = carol.signNow(EncKeys.groupEnvelopes(Hex.decode(carolPhone.secretHex), k2.secret, g, listOf(bobPhone.pubkey), "").single())
        val r2 = Enc.withId(Event("", carol.pubkey, now(), 9, listOf(listOf("h", g)), "from Carol"))
        val msg2 = carol.signNow(EncMessages.groupMessage(Hex.decode(carolPhone.secretHex), r2, k2.pub, g))
        groups += listOf(env2, msg2)
        val b2 = Device(bob, bobPhone) // a fresh runtime: Carol's lists unknown
        assertTrue(b2.receive(listOf(env2, msg2)).isEmpty(), "kept, never dropped, while her lists are coming")
        assertEquals(1, b2.gr.waiting(g).first, "Opening…")
        main += carolList
        b2.e3.offer(listOf(carolList))
        b2.gr.poke()
        runLater()
        assertEquals("from Carol", b2.opened.single().content)
        assertEquals(0, b2.gr.waiting(g).first)
    }

    /**
     * §17.12 (the iOS review, 2026-11-26): a member publishes a decoy envelope with the same d = K, entries for my device and
     * a maker that was never listed; it arrives first. It is refused, and the real envelope is still tried: the message opens.
     */
    @Test fun aDecoyEnvelopeNeverHidesTheRealKey() {
        val (a, b) = setUp()
        val (s, out) = a.send("the real one")
        val k = a.e3.key(s.key)!!
        val stranger = LocalSigner.random()
        val decoy = carol.signNow(EncKeys.groupEnvelopes(Hex.decode(stranger.secretHex), k.secret, g, listOf(bobPhone.pubkey), "").single())
        assertTrue(b.receive(listOf(decoy)).isEmpty(), "the decoy alone opens nothing")
        assertNull(b.e3.key(s.key), "and declares nothing")
        val got = b.receive(out)
        assertEquals("the real one", got.single().content, "the real envelope was tried after the refused decoy")
        assertEquals(alicePhone.pubkey, b.e3.key(s.key)!!.maker)
    }

    @Test fun aKeyHeldForOneGroupNeverOpensAnother() {
        val (a, b) = setUp()
        val (s, out) = a.send("garden")
        b.receive(out)
        // the same K declared again for another group: its messages there don't open with it
        val other = "pod-other"
        b.privates = setOf(g, other)
        val k = a.e3.key(s.key)!!
        val env = alice.signNow(EncKeys.groupEnvelopes(Hex.decode(alicePhone.secretHex), k.secret, other, listOf(bobPhone.pubkey), "").single())
        val r = Enc.withId(Event("", alice.pubkey, now(), 9, listOf(listOf("h", other)), "elsewhere"))
        val msg = alice.signNow(EncMessages.groupMessage(Hex.decode(alicePhone.secretHex), r, k.pub, other))
        assertTrue(b.receive(listOf(env, msg)).isEmpty(), "declared once (§17.12): the first group stays")
        assertEquals(Enc3.groupConv(g), b.e3.key(s.key)!!.conv)
    }

    @Test fun clearListKindsAndBelfryLabelsFromInsideAreDroppedPinsPressesAndInboxComeThrough() {
        val (a, b) = setUp()
        fun one(kind: Int, tags: List<Tag>, content: String = ""): List<Event> = b.receive(a.send(content, kind, listOf(listOf("h", g)) + tags).second)
        assertTrue(one(9000, listOf(listOf("p", carol.pubkey))).isEmpty(), "membership only from the relay in clear")
        assertTrue(one(9002, listOf(listOf("open"))).isEmpty(), "metadata only from the relay")
        assertTrue(one(1985, listOf(listOf("L", "belfry/role"), listOf("l", "steward", "belfry/role"))).isEmpty(), "roles never from inside")
        assertTrue(one(1985, listOf(listOf("L", "belfry/vouch"), listOf("l", "vouch", "belfry/vouch"))).isEmpty())
        assertTrue(one(1985, listOf(listOf("L", Buttons.RESULT_NS), listOf("l", "ok", Buttons.RESULT_NS))).isEmpty(), "an answer counts only from a service key")
        assertEquals(1, one(1985, listOf(listOf("L", "belfry/pin"), listOf("l", "pinned", "belfry/pin"), listOf("e", "c".repeat(64)))).size, "pins come through")
        assertEquals(1, one(1985, listOf(listOf("L", "belfry/inbox"), listOf("l", "closed", "belfry/inbox"))).size)
        assertEquals(1, one(1985, listOf(listOf("L", "belfry/event-status"), listOf("l", "x", "belfry/event-status"))).size)
        assertEquals(1, one(1985, listOf(listOf("L", "belfry/proposal-status"), listOf("l", "x", "belfry/proposal-status"))).size)
        assertEquals(1, one(1985, listOf(listOf("L", Buttons.NS), listOf("l", "go", Buttons.NS), listOf("e", "d".repeat(64)))).size, "a press is a group event")
        assertEquals(1, one(7, listOf(listOf("e", "e".repeat(64))), "👍").size, "everything else comes through")
        assertEquals(1, one(1111, listOf(listOf("E", "e".repeat(64))), "a reply").size)
        assertEquals(1, one(1010, listOf(listOf("e", "e".repeat(64))), "an edit").size)
        assertEquals(1, one(5, listOf(listOf("e", "e".repeat(64)))).size)
        assertEquals(1, one(2100, emptyList(), "a to-do").size)
    }

    @Test fun clearEventsAfterTheFirstKeyNeverCountTheClearListDoes() {
        val (a, _) = setUp()
        val (s, _) = a.send("first")
        val first = a.gr.firstKeys(listOf(g))
        assertEquals(a.e3.key(s.key)!!.at, first[g])
        val at = first.getValue(g)
        val clear9 = bob.signNow(EventTemplate(9, "in clear", listOf(listOf("h", g)), at + 5))
        val old9 = bob.signNow(EventTemplate(9, "before", listOf(listOf("h", g)), at - 50))
        val join = relayKey.signNow(EventTemplate(9000, "", listOf(listOf("h", g), listOf("p", carol.pubkey)), at + 5))
        val seen = bob.signNow(EventTemplate(30078, "", listOf(listOf("d", "seen:$g"), listOf("h", g)), at + 5))
        val rumor = clear9.copy(sig = "")
        assertTrue(a.gr.refuses(clear9, first), "a clear message after the first key is ignored (§17.2)")
        assertFalse(a.gr.refuses(old9, first), "older history stays")
        assertFalse(a.gr.refuses(join, first), "§16.9's clear list stays clear")
        assertFalse(a.gr.refuses(seen, first))
        assertFalse(a.gr.refuses(rumor, first), "an opened rumor is the group's event")
        a.privates = emptySet()
        assertTrue(a.gr.firstKeys(listOf(g)).isEmpty(), "only a private group by its metadata")
    }

    @Test fun editsDeletionsAndNewestPerAuthorAndDAreAppliedOnOpenedRumors() {
        val (a, b) = setUp()
        val (m, o1) = a.send("Thursday at 6")
        val got = b.receive(o1).single()
        val edit = b.receive(a.send("Thursday at 7", 1010, listOf(listOf("h", g), listOf("e", m.rumor.id))).second).single()
        val del = b.receive(a.send("", 5, listOf(listOf("h", g), listOf("e", "f".repeat(64)))).second).single()
        val s1 = b.receive(a.send("v1", 30078, listOf(listOf("h", g), listOf("d", "belfry:note"))).second).single()
        nowMs += 2000
        val s2 = b.receive(a.send("v2", 30078, listOf(listOf("h", g), listOf("d", "belfry:note"))).second).single()
        val w = World(listOf(got, edit, del, s1, s2), emptyList(), WorldContext(me = bob.pubkey, zone = ZoneOffset.UTC))
        assertEquals("Thursday at 7", w.messages(g).single { it.id == m.rumor.id }.text, "an edit inside an 8572 applies")
        assertTrue(w.all.none { it.id == s1.id } && w.all.any { it.id == s2.id }, "newest per author and d")
        // a deletion of the message by its author
        val gone = b.receive(a.send("", 5, listOf(listOf("h", g), listOf("e", m.rumor.id))).second).single()
        val w2 = World(listOf(got, edit, gone), emptyList(), WorldContext(me = bob.pubkey, zone = ZoneOffset.UTC))
        assertTrue(w2.messages(g).none { it.id == m.rumor.id })
        assertNotNull(del)
    }

    @Test fun noEntryForThisDeviceIsCantBeReadAfterAWholeRead() {
        val (a, _) = setUp()
        val (_, out) = a.send("before Carol")
        val c = Device(carol, carolPhone)
        // Carol has the message, not yet the envelope: asked by #h #d, none for her device
        assertTrue(c.receive(listOf(out.last())).isEmpty())
        assertEquals(0 to 1, c.gr.waiting(g), "Can't be read on this device")
    }

    @Test fun anAuthorWhoseSignerIsNoDeviceOfTheirsIsDropped() {
        val (a, b) = setUp()
        val (s, out) = a.send("hi")
        b.receive(out)
        // Bob's account writes a message sealed by Alice's phone: the seal isn't Bob's device
        val k = a.e3.key(s.key)!!
        val r = Enc.withId(Event("", bob.pubkey, now(), 9, listOf(listOf("h", g)), "forged"))
        val msg = bob.signNow(EncMessages.groupMessage(Hex.decode(alicePhone.secretHex), r, k.pub, g))
        val bobDev = Device(bob, bobPhone)
        bobDev.receive(out)
        runBlocking { bobDev.e3.readLists(listOf(bob.pubkey, alice.pubkey), 100) }
        assertTrue(bobDev.receive(listOf(msg)).isEmpty())
        assertEquals(0, bobDev.gr.waiting(g).first, "refused for good once the lists were read whole")
    }

    @Test fun envelopesNotConfirmedAreMadeAgainBeforeTheNextMessage() {
        val (a, _) = setUp()
        val (s1, out1) = a.send("one", confirm = false)
        assertEquals(Enc.KEY, out1.first().kind)
        val (s2, out2) = a.send("two", confirm = false)
        assertEquals(s1.key, s2.key, "the same key")
        assertEquals(listOf(Enc.KEY, Enc.MSG), out2.map { it.kind }, "its envelopes again, fresh, before the message")
        a.gr.envelopesOut(s1.key)
        val (_, out3) = a.send("three", confirm = false)
        assertEquals(listOf(Enc.MSG), out3.map { it.kind })
    }

    // ---- holds: never clear ---------------------------------------------------------------------------

    @Test fun eachHoldHasItsReasonAndWordsNeverClear() = runBlocking<Unit> {
        val (a, _) = setUp()
        // the group's members can't be read
        a.groupsUp = false
        assertEquals(HoldReason.GROUP_UNKNOWN, (a.write() as EncWrite.Hold).reason)
        a.groupsUp = true
        // I am not in its member list
        val outsider = Device(carol, carolPhone)
        assertEquals(HoldReason.GROUP_UNKNOWN, (outsider.write() as EncWrite.Hold).reason)
        // this device isn't on my list
        val newPhone = LocalSigner.random()
        val a2 = Device(alice, newPhone)
        nowMs += 61_000
        assertEquals(HoldReason.NOT_LISTED, (a2.write() as EncWrite.Hold).reason)
        // lists can't be read
        val a3 = Device(alice, alicePhone)
        a3.mainUp = false
        assertEquals(HoldReason.NO_LIST, (a3.write() as EncWrite.Hold).reason)
        // a fork in a member's lists
        val fork = list(bob.pubkey, 1, null, listOf(bobPhone.pubkey, LocalSigner.random().pubkey), at = 1500)
        main += fork
        val a4 = Device(alice, alicePhone)
        assertEquals(HoldReason.FORK, (a4.write() as EncWrite.Hold).reason)
        main.remove(fork)
        // the words, for a group
        assertEquals("Not sent: couldn’t check who can read this group", EncText.held(EncWrite.Hold(HoldReason.GROUP_UNKNOWN), { it }, group = true))
        assertEquals(EncText.GROUP_GUEST, EncText.held(EncWrite.Hold(HoldReason.GUEST), { it }, group = true))
    }

    @Test fun overAThousandDevicesIsRefusedNeverSentInClear() = runBlocking<Unit> {
        val many = (0 until 51).map { LocalSigner.random() }
        for (p in many) main += list(p.pubkey, 1, null, (0 until 20).map { LocalSigner.random().pubkey })
        val (a, _) = setUp(alice.pubkey, bob.pubkey, *many.map { it.pubkey }.toTypedArray())
        a.e3.offer(main.filter { it.kind == Enc.LIST })
        assertEquals(HoldReason.TOO_MANY, (a.write() as EncWrite.Hold).reason)
    }

    @Test fun aGuestMemberGetsNoEntryAMemberWithNoListIsSkipped() {
        val (a, _) = setUp(alice.pubkey, bob.pubkey, carol.pubkey)
        a.guests += carol.pubkey
        val dan = LocalSigner.random() // a member with no list at all, after two reads
        groups += members(alice.pubkey, bob.pubkey, carol.pubkey, dan.pubkey, at = now())
        a.gr.forgetMembers(g)
        val (_, out) = a.send("hi")
        val entries = EncKeys.entryKeys(out.filter { it.kind == Enc.KEY })
        assertFalse(carolPhone.pubkey in entries, "a guest member gets no entry (§25)")
        assertEquals(listOf(alicePhone.pubkey, bobPhone.pubkey).sorted(), entries)
    }

    @Test fun theWriteTableNeverGoesClearInAPrivateGroup() {
        val f3 = EncWrites.Facts(false, false, 3)
        assertIs<EncWrite.V3>(EncWrites.group(f3, ever = false, privateMeta = true, known = true, clearKind = false))
        assertIs<EncWrite.V3>(EncWrites.group(EncWrites.Facts(false, false, 1), ever = true, privateMeta = true, known = true, clearKind = false), "ever encrypted: encrypted whatever the format")
        assertEquals(HoldReason.FORMAT, (EncWrites.group(EncWrites.Facts(false, false, null), ever = false, privateMeta = true, known = true, clearKind = false) as EncWrite.Hold).reason)
        assertEquals(HoldReason.GROUP_UNKNOWN, (EncWrites.group(f3, ever = false, privateMeta = false, known = false, clearKind = false) as EncWrite.Hold).reason)
        assertEquals(HoldReason.GUEST, (EncWrites.group(EncWrites.Facts(false, true, 3), ever = false, privateMeta = true, known = true, clearKind = false) as EncWrite.Hold).reason, "a guest's session can't take part")
        assertEquals(EncWrite.Clear, EncWrites.group(f3, ever = true, privateMeta = true, known = true, clearKind = true), "§16.9's clear list stays clear")
    }

    // ---- buttons, files, lines -------------------------------------------------------------------------

    @Test fun aPressCopyToTheGroupsKeyCarriesTheMessageWithoutItsFileKeys() {
        val msg = Enc.withId(Event("", alice.pubkey, 1000, 9, listOf(
            listOf("h", g), listOf("button", "go", "Publish", "primary", "belfry:website-publish"),
            listOf("imeta", "url https://f.example/x", "m image/png", "x 00", "ox 11", "encryption-algorithm aes-gcm", "decryption-key " + "a".repeat(64), "decryption-nonce " + "b".repeat(24)),
        ), "see the picture"))
        val b = MessageButton("go", "Publish", "primary", "belfry:website-publish")
        val press = "c".repeat(64)
        val copy = Buttons.pressCopy(press, g, b, msg, groupsKey, bob.pubkey, 1100)
        assertEquals(1985, copy.kind)
        assertEquals(bob.pubkey, copy.pubkey)
        assertEquals(listOf(groupsKey), Enc.values(copy, "p"), "the groups key only, not the message's author")
        assertTrue(copy.tags.any { it == listOf("e", press, "", "press") })
        assertTrue(copy.tags.any { it == listOf("button", "go", "Publish", "primary", "belfry:website-publish") })
        assertEquals("9", Enc.one(copy, "k"))
        assertEquals(g, Enc.one(copy, "h"))
        assertEquals(listOf(Buttons.NS), Enc.values(copy, "L"))
        assertFalse(copy.content.contains("decryption-key") || copy.content.contains("decryption-nonce"), "the belfry never learns file keys")
        val inner = EncJson.event(EncJson.parse(copy.content)?.let { o -> JsonObject((o as JsonObject) + ("sig" to JsonPrimitive(""))) })
        assertEquals(msg.id, inner?.id, "the message as opened, its id kept")
        assertTrue(copy.content.contains("ox 11"))
        assertEquals(EncKeys.peopleId(listOf(bob.pubkey, groupsKey)), EncKeys.peopleOf(copy), "a people conversation with the groups key")
    }

    @Test fun aGroupFileIsSealedAndOpensFromItsImeta() {
        val plain = "a picture".toByteArray()
        val s = DmFiles.seal(plain)
        val tag = listOf("imeta", "url https://f.example/${s.x}", "m image/png", "size ${s.size}", "x ${s.x}", "ox ${s.ox}", "encryption-algorithm aes-gcm", "decryption-key ${s.key}", "decryption-nonce ${s.nonce}", "alt p.png")
        val e = Enc.withId(Event("", alice.pubkey, 1000, 9, listOf(listOf("h", g), tag), "https://f.example/${s.x}"))
        val w = World(listOf(e), emptyList(), WorldContext(me = bob.pubkey, zone = ZoneOffset.UTC))
        val media = w.messages(g).single().media.single()
        val sealed = assertNotNull(media.sealed)
        assertEquals(s.ox, sealed.ox)
        assertTrue(plain.contentEquals(DmFiles.open(sealed.tags(), s.ciphertext)!!))
        // encrypted but nothing to open it with: never shown as a plain file
        val broken = Enc.withId(Event("", alice.pubkey, 1001, 9, listOf(listOf("h", g), listOf("imeta", "url https://f.example/y", "m image/png", "encryption-algorithm aes-gcm")), "https://f.example/y"))
        assertTrue(World(listOf(broken), emptyList(), WorldContext(me = bob.pubkey, zone = ZoneOffset.UTC)).messages(g).single().media.isEmpty())
        assertTrue(sh.belfry.core.model.SealedMedia.opensHere("application/pdf"))
        assertFalse(sh.belfry.core.model.SealedMedia.opensHere("application/zip"), "anything else is a download")
    }

    @Test fun canReadFromHereAndNoLongerComeFromOneKeysEntriesToTheNext() {
        val (a, b) = setUp()
        val (_, o1) = a.send("one")
        b.receive(o1)
        nowMs += 5000
        groups += members(alice.pubkey, bob.pubkey, carol.pubkey, at = now())
        a.gr.forgetMembers(g)
        val (s2, o2) = a.send("two")
        b.receive(o2)
        val changes = b.gr.keyChanges(g, listOf(alice.pubkey, bob.pubkey, carol.pubkey))
        assertEquals(listOf(carol.pubkey), changes.single().added)
        assertEquals(s2.key, changes.single().key)
        val lines = GroupLines.of(World(emptyList(), emptyList(), WorldContext(me = bob.pubkey, zone = ZoneOffset.UTC)), g, changes)
        assertEquals(GroupLine.Kind.CAN_READ, lines.single().kind)
        // with the relay's 9000 in that time: the joined line says it, once
        val join = relayKey.signNow(EventTemplate(9000, "", listOf(listOf("h", g), listOf("p", carol.pubkey)), changes.single().at))
        val w = World(listOf(join), emptyList(), WorldContext(me = bob.pubkey, zone = ZoneOffset.UTC))
        assertEquals(listOf(GroupLine.Kind.JOINED), GroupLines.of(w, g, changes).map { it.kind })
        // Carol removed: a key without her device
        nowMs += 5000
        groups += members(alice.pubkey, bob.pubkey, at = now())
        a.gr.forgetMembers(g)
        val (s3, o3) = a.send("three")
        b.receive(o3)
        // the members are those of the first key again: that key's entry set is exactly theirs, so it is the one used
        // (Carol never had it); her devices have no entry in it
        assertFalse(carolPhone.pubkey in b.gr.entriesOf(g, s3.key))
        assertEquals(listOf(alicePhone.pubkey, bobPhone.pubkey).sorted(), b.gr.entriesOf(g, s3.key))
        // a list change makes a new key without her: "Carol can no longer read" from the key before
        val laptop = LocalSigner.random()
        val bob2 = list(bob.pubkey, 2, bobList, listOf(bobPhone.pubkey, laptop.pubkey))
        main += bob2; a.e3.offer(listOf(bob2)); b.e3.offer(listOf(bob2))
        nowMs += 5000
        b.receive(a.send("four").second)
        assertEquals(listOf(carol.pubkey), b.gr.keyChanges(g, listOf(alice.pubkey, bob.pubkey, carol.pubkey)).last().removed)
    }

    @Test fun aLinkPreviewTravelsInsideTheEncryptedRumorItsPictureSealedLikeAFile() {
        val (a, b) = setUp()
        // SPEC 19.87 + 19.88: the group's file decision makes the preview's picture encrypted
        assertEquals(sh.belfry.core.preview.PreviewMode.ENCRYPTED, LinkPreviewService.modeForGroup(World(emptyList(), emptyList(), WorldContext(me = alice.pubkey)), g, groupFilesEncrypted = true))
        val pic = DmFiles.seal("a picture".toByteArray())
        val preview = sh.belfry.core.preview.LinkPreview(
            "https://example.org/garden", "Seed swap", "Bring seeds", "example.org",
            sh.belfry.core.preview.PreviewPicture("https://files.example/${pic.x}", "image/webp", 600, 315, pic.x, pic.ox, pic.key, pic.nonce),
        )
        val tags = listOf(listOf("h", g)) + preview.tags()
        val imeta = tags.single { sh.belfry.core.preview.LinkPreviews.isPreviewImeta(it) }
        assertTrue(imeta.any { it == "decryption-key ${pic.key}" } && imeta.any { it == "ox ${pic.ox}" } && imeta.any { it.startsWith("encryption-algorithm") })
        val (_, out) = a.send("https://example.org/garden", 9, tags)
        val msg = out.last()
        assertTrue(msg.tags.none { it.firstOrNull() == "preview" || it.firstOrNull() == "imeta" }, "nothing of the preview outside the 8572")
        assertFalse(msg.content.contains("Seed swap") || msg.content.contains(pic.key))
        val got = b.receive(out).single()
        assertEquals(preview.tags(), got.tags.filter { it.firstOrNull() == "preview" || sh.belfry.core.preview.LinkPreviews.isPreviewImeta(it) }, "the preview's tags inside the rumor")
        // the press copy to the groups key never carries its key either
        val copy = Buttons.pressCopy("c".repeat(64), g, MessageButton("go", "Go", "primary", "belfry:website-publish"), got, groupsKey, bob.pubkey, now())
        assertFalse(copy.content.contains(pic.key) || copy.content.contains(pic.nonce))
    }

    // ---- SPEC 19.94: only the groups relay's own 39002 decides who gets the key ------------------------------

    @Test fun aMemberSigned39002NeverDecidesWhoGetsTheKey() {
        val (a, _) = setUp()
        // Bob publishes a newer "member list" of his own that adds Carol: never counts, newest or not
        groups += bob.signNow(EventTemplate(39002, "", listOf(listOf("d", g), listOf("p", alice.pubkey), listOf("p", bob.pubkey), listOf("p", carol.pubkey)), now() + 5))
        val plan = assertIs<EncPlan.Group>(assertIs<EncWrite.V3>(a.write()).plan)
        assertFalse(carolPhone.pubkey in plan.devices, "Carol is only in the member-signed list: no entry for her")
        assertEquals(setOf(alicePhone.pubkey, bobPhone.pubkey), plan.devices.toSet(), "the relay-signed list")
        // the relay's own newer list does count
        groups += members(alice.pubkey, bob.pubkey, carol.pubkey, at = now() + 6)
        a.gr.forgetMembers(g)
        val plan2 = assertIs<EncPlan.Group>(assertIs<EncWrite.V3>(a.write()).plan)
        assertTrue(carolPhone.pubkey in plan2.devices)
    }

    @Test fun onlyMemberSigned39002sHold() {
        aliceList = list(alice.pubkey, 1, null, listOf(alicePhone.pubkey))
        bobList = list(bob.pubkey, 1, null, listOf(bobPhone.pubkey))
        main += listOf(aliceList, bobList)
        groups += alice.signNow(EventTemplate(39002, "", listOf(listOf("d", g), listOf("p", alice.pubkey), listOf("p", bob.pubkey)), now()))
        val a = Device(alice, alicePhone)
        assertEquals(EncWrite.Hold(HoldReason.GROUP_UNKNOWN), a.write(), "no list by the relay's key: held, never clear")
    }

    @Test fun theRelayKeyUnknownHoldsEvenAGroupEverEncryptedHere() {
        val (a, _) = setUp()
        a.send("one") // a key of the group is held here now (ever)
        a.relayKnown = null
        assertEquals(EncWrite.Hold(HoldReason.GROUP_UNKNOWN), runBlocking { a.gr.ready(g) }, "NIP-11 not read: held")
        assertEquals(EncWrite.Hold(HoldReason.GROUP_UNKNOWN), a.write())
        // another key than the one that signed the lists: the same
        a.relayKnown = LocalSigner.random().pubkey
        assertEquals(EncWrite.Hold(HoldReason.GROUP_UNKNOWN), a.write())
        a.relayKnown = relayKey.pubkey
        assertIs<EncWrite.V3>(a.write())
    }

    @Test fun untrustedMetadataIsUnknownForTheTable() {
        val f3 = EncWrites.Facts(false, false, 3)
        val t = sh.belfry.core.enc.GroupTrust
        // shown private, but not trusted: held, never clear
        assertEquals(EncWrite.Hold(HoldReason.GROUP_UNKNOWN), t.write(f3, ever = false, trusted = false, privateMeta = true, known = true, clearKind = false))
        // shown public, not trusted: held too (a forged "public" must not make a private group's write plain)
        assertEquals(EncWrite.Hold(HoldReason.GROUP_UNKNOWN), t.write(f3, ever = false, trusted = false, privateMeta = false, known = true, clearKind = false))
        // ever encrypted here: still V3 (its list then needs the relay's 39002: [Enc3Groups.ready])
        assertIs<EncWrite.V3>(t.write(f3, ever = true, trusted = false, privateMeta = false, known = false, clearKind = false))
        // trusted: the table as before
        assertIs<EncWrite.V3>(t.write(f3, ever = false, trusted = true, privateMeta = true, known = true, clearKind = false))
        assertEquals(EncWrite.Clear, t.write(f3, ever = false, trusted = true, privateMeta = false, known = true, clearKind = false))
        // format 1 is unchanged (no group encryption there)
        assertEquals(EncWrite.V1, t.write(EncWrites.Facts(false, false, 1), ever = false, trusted = false, privateMeta = true, known = true, clearKind = false))
        // format unknown: held
        assertIs<EncWrite.Hold>(t.write(EncWrites.Facts(false, false, null), ever = false, trusted = false, privateMeta = false, known = true, clearKind = false))
    }

    @Test fun lineWords() {
        val me = bob.pubkey
        val name = { k: String -> if (k == carol.pubkey) "Chloé Martin" else if (k == alice.pubkey) "Ana Silva" else "Ben" }
        val t = sh.belfry.core.model.GroupEncText
        assertEquals("Chloé Martin joined", t.title(GroupLine(GroupLine.Kind.JOINED, carol.pubkey, alice.pubkey, 1), me, name, "Garden committee"))
        assertEquals("You joined Garden committee", t.title(GroupLine(GroupLine.Kind.JOINED, me, alice.pubkey, 1), me, name, "Garden committee"))
        assertEquals("Ana removed Chloé Martin", t.title(GroupLine(GroupLine.Kind.REMOVED, carol.pubkey, alice.pubkey, 1), me, name, "Garden committee"))
        assertEquals("Ana removed you from Garden committee", t.title(GroupLine(GroupLine.Kind.REMOVED, me, alice.pubkey, 1), me, name, "Garden committee"))
        assertEquals("They see messages from now on.", t.small(GroupLine(GroupLine.Kind.JOINED, carol.pubkey, null, 1), me))
        assertEquals("New messages no longer reach their devices.", t.small(GroupLine(GroupLine.Kind.REMOVED, carol.pubkey, null, 1), me))
        assertTrue(t.hasInfo(GroupLine(GroupLine.Kind.REMOVED, carol.pubkey, alice.pubkey, 1), me))
        assertFalse(t.hasInfo(GroupLine(GroupLine.Kind.REMOVED, me, alice.pubkey, 1), me))
        assertEquals(
            "When Ana removed Chloé, a new key was made for Garden committee. Messages sent from now on are encrypted with it, and Chloé’s devices never get it, so they can’t read them.",
            t.info(GroupLine(GroupLine.Kind.REMOVED, carol.pubkey, alice.pubkey, 1), me, name, "Garden committee")[0],
        )
    }
}
