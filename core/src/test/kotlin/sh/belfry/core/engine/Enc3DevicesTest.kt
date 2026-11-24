package sh.belfry.core.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.enc.AnswerTo
import sh.belfry.core.enc.AnswerWord
import sh.belfry.core.enc.Asked
import sh.belfry.core.enc.Enc
import sh.belfry.core.enc.EncDevice
import sh.belfry.core.enc.EncKeys
import sh.belfry.core.enc.EncLists
import sh.belfry.core.enc.EncPacks
import sh.belfry.core.enc.KeyPerson
import sh.belfry.core.enc.PackKey
import sh.belfry.core.enc.RemovedAnswer
import sh.belfry.core.net.SessionInfo
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Tag
import sh.belfry.core.sealed.SealedEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC 19.85 (format 3, part 5): asks, answers, key packs, the card, the warnings, the first screen and the removed
 * device's wipe, on a fake community relay. Alice has a phone (her first device), then a laptop, then a tablet.
 */
class Enc3DevicesTest {
    private val belfrySecret = Keys.generateSecret()
    private val belfry = Enc.pub(belfrySecret)
    private val alice = LocalSigner.random()
    private val bob = LocalSigner.random()
    private val phone = LocalSigner.random()
    private val laptop = LocalSigner.random()
    private val tablet = LocalSigner.random()
    private val stranger = LocalSigner.random()
    private val agent = LocalSigner.random()
    private var nowMs = System.currentTimeMillis()
    private val relay = ArrayList<Event>()

    private fun list(n: Int, prev: Event?, devices: List<String>, readers: List<String> = emptyList(), account: String = alice.pubkey): Event {
        val tags = ArrayList<Tag>()
        tags += listOf("p", account); tags += listOf("n", "$n")
        if (prev != null) tags += listOf("prev", prev.id)
        devices.forEach { tags += listOf("device", it) }
        readers.forEach { tags += listOf("device", it, "reader") }
        return Enc.signWith(belfrySecret, Enc.LIST, 1000L + 100L * n, tags, "")
    }

    private fun strings(el: kotlinx.serialization.json.JsonElement?): List<String>? = (el as? JsonArray)?.map { (it as JsonPrimitive).content }
    private fun matches(e: Event, f: JsonObject): Boolean {
        strings(f["kinds"])?.let { k -> if (e.kind.toString() !in k) return false }
        strings(f["authors"])?.let { a -> if (e.pubkey !in a) return false }
        for ((name, v) in f) if (name.startsWith("#")) {
            val vals = strings(v) ?: continue
            if (e.tags.none { it.size >= 2 && it[0] == name.drop(1) && it[1] in vals }) return false
        }
        return true
    }

    private inner class Ports : Enc3Ports {
        var up = true
        override fun belfryKey(): String? = belfry
        override fun groupsKey(): String? = null
        override fun connection(): Int? = if (up) 1 else null
        override suspend fun query(filter: JsonObject, pageMs: Long): SealedEngine.Answer? = if (!up) null else SealedEngine.Answer(relay.filter { matches(it, filter) }, true)
        override fun addEvents(events: List<Event>) {}
        override suspend fun chatSessions(): Int? = 1
        override suspend fun enqueueByDevice(kind: Int, tags: List<Tag>, content: String) {}
        override fun resubscribe() {}
        override fun later(block: suspend () -> Unit) {}
        override fun nowMs(): Long = nowMs
    }

    private inner class DevPorts : Enc3DevicePorts {
        val queued = ArrayList<Event>()
        val revoked = ArrayList<Triple<String, String?, String>>()
        var removed: RemovedAnswer? = null
        var v1: Map<String, String> = emptyMap()
        val v1Taken = LinkedHashMap<String, String>()
        var missingN = 0
        override suspend fun sessions(): List<SessionInfo>? = listOf(
            SessionInfo("Belfry", "Pixel 8", "", "b".repeat(64), "", null, phone.pubkey, true),
            SessionInfo("Belfry", "Chrome on Android", "", "c".repeat(64), "", null, laptop.pubkey, true),
        )
        override suspend fun revoke(device: String, bunker: String?, by: String) { revoked += Triple(device, bunker, by) }
        override suspend fun agentReads(agent: String, reads: Boolean) {}
        override suspend fun enqueueSigned(e: Event) { queued += e }
        override suspend fun removedAnswer(): RemovedAnswer? = removed
        override fun v1Secrets(): Map<String, String> = v1
        override fun takeV1(secrets: Map<String, String>) { v1Taken.putAll(secrets) }
        override fun missing(): Int = missingN
        override fun since3(): Long = 0
        override fun later(block: suspend () -> Unit) {}
    }

    private inner class Dev(val key: LocalSigner, store: EncKeyStore? = null) {
        val ports = Ports()
        val e3 = Enc3(key, alice.pubkey, ports, store)
        val dp = DevPorts()
        val d = Enc3Devices(e3, dp) { nowMs }
        /** These lists reach the relay and this device. */
        fun lists(vararg l: Event) { l.forEach { if (it !in relay) relay += it }; e3.offer(l.toList()); runBlocking { e3.readLists(listOf(alice.pubkey), 1000) }; e3.check() }
    }

    private val l1 = list(1, null, listOf(phone.pubkey))
    private val l2 = list(2, l1, listOf(phone.pubkey, laptop.pubkey))
    private val l3 = list(3, l2, listOf(phone.pubkey, laptop.pubkey, tablet.pubkey))

    // ---- asks (§7.3, §16.12) ---------------------------------------------------------------------------

    @Test fun anAskOnceAgainAfter24hAndOnANewSibling() {
        val a = Asked(1000, listOf("p", "l"), "x")
        assertTrue(EncDevice.mayAsk(null, 1000, listOf("p")))
        assertFalse(EncDevice.mayAsk(a, 1000 + 3600, listOf("p", "l")))
        assertTrue(EncDevice.mayAsk(a, 1000 + 86_400, listOf("p", "l")))
        assertTrue(EncDevice.mayAsk(a, 1000 + 60, listOf("p", "l", "t")), "another device of mine appeared")
        assertFalse(EncDevice.mayAsk(a, 1000 + 60, listOf("l")), "one gone is no reason")

        val lap = Dev(laptop)
        lap.lists(l1, l2)
        assertTrue(runBlocking { lap.d.ask() })
        val ask = lap.dp.queued.single()
        assertEquals(Enc.ASK, ask.kind)
        assertEquals(laptop.pubkey, ask.pubkey, "signed by the device key")
        assertEquals(listOf(listOf("p", alice.pubkey)), ask.tags)
        assertTrue(Enc.valid(ask))
        assertFalse(runBlocking { lap.d.ask() }, "once")
        // the periodic look asks again only after 24 h (and only while messages are missing)
        lap.dp.missingN = 2
        runBlocking { lap.d.tick() }
        assertEquals(1, lap.dp.queued.size)
        nowMs += 86_400_000L
        runBlocking { lap.d.tick() }
        assertEquals(2, lap.dp.queued.size)
        // a new device of mine on the list: again
        lap.lists(l3)
        runBlocking { lap.d.tick() }
        assertEquals(3, lap.dp.queued.size)
        assertNotNull(lap.d.state.value.askedAt)
    }

    // ---- the card, answers (§7.4, §7.7, §17.6, §17.7, §20) ---------------------------------------------

    @Test fun theCardOnAnOlderDeviceAndAnswersOnlyFromOlderListedDevices() {
        val ph = Dev(phone)
        ph.lists(l1) // the first list this device sees: no card for what it had
        assertTrue(ph.d.state.value.cards.isEmpty())
        ph.lists(l2)
        val card = ph.d.state.value.cards.single()
        assertEquals(laptop.pubkey, card.device)
        assertFalse(card.gone)
        // the laptop sees no card for the phone (only older devices answer)
        val lap = Dev(laptop)
        lap.lists(l1, l2)
        assertTrue(lap.d.state.value.cards.isEmpty())

        val c3 = EncLists.chainOf(listOf(l1, l2, l3), belfry, alice.pubkey)!!
        val sk = { s: LocalSigner -> Hex.decode(s.secretHex) }
        // the phone answers about the laptop: counts; the laptop about the phone: never (younger); a stranger: never
        val byPhone = EncPacks.answer(sk(phone), alice.pubkey, AnswerTo.Device(laptop.pubkey), AnswerWord.SHARED)
        val byLaptop = EncPacks.answer(sk(laptop), alice.pubkey, AnswerTo.Device(phone.pubkey), AnswerWord.REMOVED)
        val byStranger = EncPacks.answer(sk(stranger), alice.pubkey, AnswerTo.Device(tablet.pubkey), AnswerWord.SHARED)
        val got = EncDevice.answers(listOf(byPhone, byLaptop, byStranger), alice.pubkey, c3, emptyList())
        assertEquals(setOf(laptop.pubkey), got.keys)
        assertEquals(phone.pubkey, got[laptop.pubkey]!!.by)
        // an answer to an ask: about the asking device
        val ask = EncPacks.ask(sk(tablet), alice.pubkey)
        val toAsk = EncPacks.answer(sk(laptop), alice.pubkey, AnswerTo.Ask(ask.id), AnswerWord.SHARED)
        val asks = EncDevice.asks(listOf(ask), alice.pubkey, c3)
        assertEquals(tablet.pubkey, EncDevice.answers(listOf(toAsk), alice.pubkey, c3, asks.values).keys.single())
        // a reader never answers (nor packs)
        val withReader = list(4, l3, listOf(phone.pubkey, laptop.pubkey, tablet.pubkey), readers = listOf(agent.pubkey))
        val c4 = EncLists.chainOf(listOf(l1, l2, l3, withReader), belfry, alice.pubkey)!!
        val byReader = EncPacks.answer(sk(agent), alice.pubkey, AnswerTo.Device(tablet.pubkey), AnswerWord.SHARED)
        assertTrue(EncDevice.answers(listOf(byReader), alice.pubkey, c4, emptyList()).isEmpty())

        // answered once for all: on the laptop, the tablet's card goes once the phone answered it
        lap.lists(l3)
        assertEquals(tablet.pubkey, lap.d.state.value.cards.single().device)
        lap.d.offer(listOf(EncPacks.answer(sk(phone), alice.pubkey, AnswerTo.Device(tablet.pubkey), AnswerWord.SHARED)))
        assertTrue(lap.d.state.value.cards.isEmpty())
        assertTrue(lap.d.state.value.notices.single().contains("shared earlier messages with"), "card-answered: said once")
    }

    @Test fun aDeviceAddedAndRemovedWhileAwayIsANoticeUntilOk() {
        val lx = list(2, l1, listOf(phone.pubkey, stranger.pubkey))
        val ly = list(3, lx, listOf(phone.pubkey))
        val ph = Dev(phone)
        ph.lists(l1)
        ph.lists(lx, ly)
        val g = ph.d.state.value.cards.single()
        assertTrue(g.gone)
        assertEquals(ly.createdAt, g.goneAt)
        ph.d.goneOk(stranger.pubkey)
        assertTrue(ph.d.state.value.cards.isEmpty())
    }

    // ---- key packs (§7.5, §16.3, §17.6, §18.3) ----------------------------------------------------------

    @Test fun shareBuildsPacksWithV1SecretsAndTheNewDeviceTakesThem() {
        val ph = Dev(phone)
        ph.lists(l1); ph.lists(l2)
        // the phone holds a people key (with Bob), a group key and a version-1 letterbox secret
        val kp = EncKeys.newKey()
        val rumor = EncKeys.keyRumor(phone.pubkey, kp.secret, listOf(KeyPerson(alice.pubkey, l1.id), KeyPerson(bob.pubkey)), 2000)
        ph.e3.putKey(ConvKey(kp.pub, kp.secret, Enc3.peopleConv(listOf(alice.pubkey, bob.pubkey), alice.pubkey), phone.pubkey, 2000, mine = true, people = listOf(alice.pubkey, bob.pubkey).sorted(), rumor = rumor))
        val gk = EncKeys.newKey()
        ph.e3.putKey(ConvKey(gk.pub, gk.secret, Enc3.groupConv("garden"), phone.pubkey, 2100, mine = true, group = true))
        val lbSecret = Keys.generateSecretHex()
        val lb = Keys.publicKeyHex(lbSecret)
        ph.dp.v1 = mapOf(lb to lbSecret)
        runBlocking { ph.d.readNames(force = true) }
        val card = ph.d.state.value.cards.single()
        assertEquals("Chrome on Android", card.name)
        var steps = 0
        runBlocking { ph.d.share(card) { _, _ -> steps++ } }
        val packs = ph.dp.queued.filter { it.kind == Enc.PACK }
        assertTrue(packs.isNotEmpty() && steps == packs.size)
        assertTrue(packs.all { Enc.one(it, "d") == laptop.pubkey && Enc.one(it, "p") == alice.pubkey && it.pubkey != phone.pubkey }, "a one-time author, to the new device")
        val answer = ph.dp.queued.last()
        assertEquals(Enc.ANSWER, answer.kind)
        assertEquals(phone.pubkey, answer.pubkey)
        assertEquals("shared", Enc.one(answer, "answer"))
        assertTrue(ph.d.state.value.cards.isEmpty(), "answered here")

        // the laptop already holds the people key, declared for another conversation: never replaced
        val lap = Dev(laptop)
        lap.lists(l1, l2)
        lap.e3.putKey(ConvKey(kp.pub, kp.secret, "n:" + "e".repeat(64), laptop.pubkey, 1, mine = false))
        lap.d.offer(packs)
        assertTrue(runBlocking { lap.d.takePacks() })
        assertEquals("n:" + "e".repeat(64), lap.e3.key(kp.pub)!!.conv)
        val g = assertNotNull(lap.e3.key(gk.pub))
        assertTrue(g.group && !g.mine, "a group key from a pack reads, never sends")
        assertEquals(lbSecret, lap.dp.v1Taken[lb])
        assertEquals(phone.pubkey, lap.d.state.value.sharedBy?.key)

        // a fresh laptop takes the people key itself (mine false: never adopted for sending)
        val lap2 = Dev(laptop)
        lap2.lists(l1, l2)
        lap2.d.offer(packs)
        runBlocking { lap2.d.takePacks() }
        val pk = assertNotNull(lap2.e3.key(kp.pub))
        assertFalse(pk.mine)
        assertEquals(Enc3.peopleConv(listOf(alice.pubkey, bob.pubkey), alice.pubkey), pk.conv)
    }

    @Test fun packsFromAYoungerOrUnlistedDeviceOrWithGapsAreRefused() {
        val sk = { s: LocalSigner -> Hex.decode(s.secretHex) }
        val k = EncKeys.newKey()
        val keys = listOf<PackKey>(PackKey.Group(k.secret, k.pub, "g"))
        // from the laptop (younger) to the phone, and from a stranger: nothing
        val ph = Dev(phone)
        ph.lists(l1, l2)
        ph.d.offer(EncPacks.packs(sk(laptop), alice.pubkey, phone.pubkey, keys) + EncPacks.packs(sk(stranger), alice.pubkey, phone.pubkey, keys))
        runBlocking { ph.d.takePacks() }
        assertNull(ph.e3.key(k.pub))
        // to the tablet with list 2 missing (gaps): kept, not judged; judged once the chain is whole
        relay.clear()
        val tab = Dev(tablet)
        tab.lists(l1, l3)
        tab.d.offer(EncPacks.packs(sk(phone), alice.pubkey, tablet.pubkey, keys))
        assertFalse(runBlocking { tab.d.takePacks() })
        assertNull(tab.e3.key(k.pub))
        tab.lists(l2)
        assertTrue(runBlocking { tab.d.takePacks() })
        assertNotNull(tab.e3.key(k.pub))
    }

    // ---- the removed device (§16.6, §26) ----------------------------------------------------------------

    @Test fun theWipeNeedsBothConditions() {
        val c = EncLists.chainOf(listOf(l1, l2), belfry, alice.pubkey)
        assertFalse(EncDevice.shouldWipe(RemovedAnswer(true), true, c, laptop.pubkey), "still on my list")
        assertTrue(EncDevice.shouldWipe(RemovedAnswer(true), true, c, tablet.pubkey))
        assertFalse(EncDevice.shouldWipe(RemovedAnswer(false), true, c, tablet.pubkey), "the belfry didn't say removed")
        assertFalse(EncDevice.shouldWipe(null, true, c, tablet.pubkey), "no answer (offline)")
        assertFalse(EncDevice.shouldWipe(RemovedAnswer(true), false, c, tablet.pubkey), "my list not read whole here")
        assertFalse(EncDevice.shouldWipe(RemovedAnswer(true), true, null, tablet.pubkey), "no list at all")

        val tab = Dev(tablet)
        tab.lists(l1, l2, l3)
        tab.dp.removed = RemovedAnswer(true, 5000, "Pixel 8")
        runBlocking { tab.d.checkRemoved(force = true) }
        assertNull(tab.d.removed.value, "the belfry says removed, my list still has me: no wipe")
        // the list without the tablet, but offline: no wipe
        val l4 = list(4, l3, listOf(phone.pubkey, laptop.pubkey))
        relay += l4
        tab.ports.up = false
        runBlocking { tab.d.checkRemoved(force = true) }
        assertNull(tab.d.removed.value)
        tab.ports.up = true
        tab.dp.removed = RemovedAnswer(false)
        runBlocking { tab.d.checkRemoved(force = true) }
        assertNull(tab.d.removed.value, "off my list, the belfry says not removed: no wipe")
        tab.dp.removed = RemovedAnswer(true, 5000, "Pixel 8")
        runBlocking { tab.d.checkRemoved(force = true) }
        assertEquals("Pixel 8", tab.d.removed.value?.by)
        assertEquals(5000L, tab.d.removed.value?.at)
    }

    @Test fun theRemovedAnswerIsReadStrictly() {
        assertNull(RemovedAnswer.parse(null))
        assertNull(RemovedAnswer.parse(buildJsonObject { put("removed", JsonPrimitive("true")) }), "a string is not true")
        assertEquals(RemovedAnswer(false), RemovedAnswer.parse(buildJsonObject { put("removed", JsonPrimitive(false)) }))
        val r = RemovedAnswer.parse(buildJsonObject { put("removed", JsonPrimitive(true)); put("at", JsonPrimitive("2026-11-24T09:52:00Z")); put("by_name", JsonPrimitive("  Safari on iPhone ")) })!!
        assertTrue(r.removed); assertEquals("Safari on iPhone", r.byName); assertEquals(1795513920L, r.at)
    }

    @Test fun removeRevokesWithByThenAnswers() {
        val ph = Dev(phone)
        ph.lists(l1); ph.lists(l2)
        runBlocking { ph.d.readNames(force = true) }
        runBlocking { ph.d.remove(ph.d.state.value.cards.single()) }
        assertEquals(Triple(laptop.pubkey, "c".repeat(64), phone.pubkey), ph.dp.revoked.single())
        assertEquals("removed", Enc.one(ph.dp.queued.single(), "answer"))
        assertEquals(laptop.pubkey, Enc.one(ph.dp.queued.single(), "device"))
    }

    // ---- the first screen, the warnings, the only device (§17.7, §18.3) ---------------------------------

    @Test fun theFirstScreenListsTheOthersOnceAndWarningsStay() {
        val l4 = list(4, l3, listOf(phone.pubkey, laptop.pubkey, tablet.pubkey), readers = listOf(agent.pubkey))
        val vault = MemoryVault()
        val tab = Dev(tablet, EncKeyStore(vault, "enc-keys", alice.pubkey))
        tab.lists(l1, l2, l3, l4)
        val rows = tab.d.state.value.firstScreen
        assertEquals(listOf(phone.pubkey, laptop.pubkey, agent.pubkey), rows.map { it.device })
        assertEquals(l1.createdAt, rows[0].addedAt)
        assertTrue(rows[2].reader)
        tab.d.firstScreenOk()
        assertTrue(tab.d.state.value.firstScreen.isEmpty())
        // §17.7: an entry for a key never on my lists → the warning that stays, across restarts
        tab.e3.strangeEntry(stranger.pubkey)
        assertTrue(tab.d.state.value.strange)
        val again = Dev(tablet, EncKeyStore(vault, "enc-keys", alice.pubkey))
        again.lists(l1, l2, l3, l4)
        assertTrue(again.d.state.value.strange)
        assertTrue(again.d.state.value.firstScreen.isEmpty(), "once per device")
        // a fork: warned, and still warned when the fork is no longer in view
        val fork = list(4, l3, listOf(phone.pubkey, tablet.pubkey))
        again.lists(fork)
        assertTrue(again.d.state.value.fork)
        relay.remove(fork) // the fork no longer in view
        val third = Dev(tablet, EncKeyStore(vault, "enc-keys", alice.pubkey))
        third.lists(l1, l2, l3, l4)
        assertTrue(third.d.state.value.fork)
    }

    @Test fun theOnlyDeviceWithKeys() {
        val ph = Dev(phone)
        ph.lists(l1)
        assertFalse(ph.d.state.value.onlyDevice, "no keys: nothing is lost")
        val k = EncKeys.newKey()
        ph.e3.putKey(ConvKey(k.pub, k.secret, "n:" + bob.pubkey, phone.pubkey, 1, mine = true))
        ph.e3.check()
        assertTrue(ph.d.state.value.onlyDevice)
        assertTrue(ph.d.state.value.older.isEmpty())
    }
}
