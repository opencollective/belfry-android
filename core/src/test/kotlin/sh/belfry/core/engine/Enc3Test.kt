package sh.belfry.core.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import sh.belfry.core.crypto.Keys
import sh.belfry.core.enc.Devices
import sh.belfry.core.enc.Enc
import sh.belfry.core.enc.EncFormatState
import sh.belfry.core.enc.EncKeys
import sh.belfry.core.enc.EncPlan
import sh.belfry.core.enc.EncText
import sh.belfry.core.enc.EncWrite
import sh.belfry.core.enc.HoldReason
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Tag
import sh.belfry.core.outbox.Outbox
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.SendOutcome
import sh.belfry.core.outbox.Target
import sh.belfry.core.sealed.SealedEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC 19.82: the format cache, device lists, this device on its list, the key store, the reads marker. */
class Enc3Test {
    private val belfrySecret = Keys.generateSecret()
    private val belfry = Enc.pub(belfrySecret)
    private val groupsKey = "9".repeat(64)
    private val account = LocalSigner.random().pubkey
    private val device = LocalSigner.random()
    private val lina = LocalSigner.random().pubkey
    private val linaPhone = LocalSigner.random().pubkey
    private fun key() = LocalSigner.random().pubkey

    /** A list n of ACCOUNT, chained to PREV. */
    private fun list(account: String, n: Int, prev: Event?, devices: List<String>, at: Long = 1000L + n, readers: List<String> = emptyList()): Event {
        val tags = ArrayList<Tag>()
        tags += listOf("p", account); tags += listOf("n", "$n")
        if (prev != null) tags += listOf("prev", prev.id)
        devices.sorted().forEach { tags += listOf("device", it) }
        readers.forEach { tags += listOf("device", it, "reader") }
        return Enc.signWith(belfrySecret, Enc.LIST, at, tags, "")
    }

    /** The relay: what it holds, whether it answers, whether its answers are complete. */
    private inner class Fake : Enc3Ports {
        val relay = ArrayList<Event>()
        var up = true
        var complete = true
        var conn = 1
        var now = 0L
        var sessions: Int? = 1
        var belfryKnown = true
        val queued = ArrayList<Triple<Int, List<Tag>, String>>()
        val queries = ArrayList<JsonObject>()
        var resubscribed = 0
        val later = ArrayList<suspend () -> Unit>()
        override fun belfryKey(): String? = if (belfryKnown) belfry else null
        override fun groupsKey(): String? = groupsKey
        override fun connection(): Int? = if (up) conn else null
        override suspend fun query(filter: JsonObject, pageMs: Long): SealedEngine.Answer? {
            queries += filter
            if (!up) return null
            val ps = (filter["#p"] as JsonArray).map { (it as JsonPrimitive).content }.toSet()
            return SealedEngine.Answer(relay.filter { e -> e.kind == Enc.LIST && e.tags.any { it[0] == "p" && it[1] in ps } }, complete)
        }
        override fun addEvents(events: List<Event>) {}
        override suspend fun chatSessions(): Int? = sessions
        override suspend fun enqueueByDevice(kind: Int, tags: List<Tag>, content: String) { queued += Triple(kind, tags, content) }
        override fun resubscribe() { resubscribed++ }
        override fun later(block: suspend () -> Unit) { later += block }
        override fun nowMs(): Long = now
        fun runLater() = runBlocking { while (later.isNotEmpty()) later.removeAt(0)() }
    }

    private fun runtime(p: Fake, store: EncKeyStore? = null) = Enc3(device, account, p, store)

    // ---- the format cache ------------------------------------------------------------------------------

    @Test fun theFormatIsTheDevicesPerBelfry() {
        val vault = MemoryVault()
        EncFormatCache(vault, "Example.org").record(EncFormatState.DOC_3, 100)
        // another account, after a sign-out, on the same device: the same cache
        assertEquals(3, EncFormatCache(vault, "example.org").state().writeFormat(9_999_999))
        assertNull(EncFormatCache(vault, "other.org").state().format, "per belfry")
        EncFormatCache(vault, "example.org").record(EncFormatState.DOC_1, 200)
        assertEquals(3, EncFormatCache(vault, "example.org").state().format, "one way, persisted")
        // a sign-out removes the account's records, never this one
        assertTrue(vault.get(EncFormatCache.V_FORMAT + "example.org") != null)
    }

    @Test fun aStaleOneIsReadAgain() {
        val c = EncFormatCache(MemoryVault(), "example.org")
        c.record(EncFormatState.DOC_1, 1000)
        assertEquals(1, c.state().writeFormat(1100))
        assertTrue(c.state().wantsRead(1400))
        c.record(EncFormatState.DOC_1, 1400)
        assertEquals(1, c.state().writeFormat(1500))
    }

    // ---- devices -----------------------------------------------------------------------------------------

    @Test fun threeAnswersNeverTwo() {
        val p = Fake()
        val e = runtime(p)
        assertEquals(Devices.Service, e.devices(belfry))
        assertEquals(Devices.Service, e.devices(groupsKey))
        assertEquals(Devices.Unknown, e.devices(lina), "no list: wait, never the account key")
        assertEquals(Devices.Unknown, e.devices(account))
        e.offer(listOf(list(lina, 1, null, listOf(linaPhone))))
        val d = assertIs<Devices.Listed>(e.devices(lina))
        assertEquals(listOf(linaPhone), d.chain.current.devices)
        // a list signed by another key never counts
        val forged = Enc.signWith(Keys.generateSecret(), Enc.LIST, 5, listOf(listOf("p", account), listOf("n", "1"), listOf("device", device.pubkey)), "")
        e.offer(listOf(forged))
        assertEquals(Devices.Unknown, e.devices(account))
        // no belfry key known: nothing is listed
        p.belfryKnown = false
        assertEquals(Devices.Unknown, e.devices(lina))
    }

    @Test fun gapsForksAndInvalidAbove() {
        val p = Fake()
        val e = runtime(p)
        val l1 = list(lina, 1, null, listOf(linaPhone))
        val l2 = list(lina, 2, l1, listOf(linaPhone, key()))
        val l3 = list(lina, 3, l2, listOf(linaPhone))
        e.offer(listOf(l1, l3))
        assertEquals(true, e.warnings(lina)?.gaps, "an older list missing")
        assertEquals(false, e.warnings(lina)?.holds, "a gap never blocks")
        assertEquals(l3.id, (e.devices(lina) as Devices.Listed).chain.current.id)
        e.offer(listOf(l2))
        assertEquals(false, e.warnings(lina)?.gaps)
        assertEquals(2, e.changes(lina).size)
        assertEquals(1, e.changes(lina)[0].added.size)
        assertEquals(1, e.changes(lina)[1].removed.size)
        // two lists with one n: a fork, for good
        e.offer(listOf(list(lina, 3, l2, listOf(key()), at = 2000)))
        assertEquals(true, e.warnings(lina)?.fork)
        // a newer signed list that isn't valid (a self entry beside a device): held (§18.5)
        val m1 = list(account, 1, null, listOf(device.pubkey))
        e.offer(listOf(m1))
        assertEquals(false, e.warnings(account)?.holds)
        e.offer(listOf(Enc.signWith(belfrySecret, Enc.LIST, 1100, listOf(listOf("p", account), listOf("n", "2"), listOf("prev", m1.id), listOf("device", account, "self"), listOf("device", device.pubkey)), "")))
        assertEquals(true, e.warnings(account)?.invalidAbove)
        e.check()
        assertTrue(e.state.value.myWarning)
    }

    @Test fun listsAreOnTheLiveSubscriptionAndReadOnThisConnection() = runBlocking {
        val p = Fake()
        val e = runtime(p)
        val f = e.filters().single()
        assertEquals(listOf(Enc.LIST), (f["kinds"] as JsonArray).map { (it as JsonPrimitive).content.toInt() })
        assertEquals(listOf(belfry), (f["authors"] as JsonArray).map { (it as JsonPrimitive).content })
        assertNull(f["since"], "lists are kept for ever")
        assertTrue(e.setPartners(listOf(lina, account)))
        assertFalse(e.setPartners(listOf(lina)))
        assertEquals(1, p.resubscribed)
        val stranger = key()
        e.want(listOf(stranger))
        assertEquals(2, p.resubscribed)
        val ps = (e.filters().single()["#p"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(setOf(account, lina, stranger), ps.toSet())
        p.runLater() // the wanted list read at once
        assertTrue(p.queries.isNotEmpty())
        e.want(listOf(stranger)) // again within 30 s: not asked again
        assertTrue(p.later.isEmpty())
        // a complete read counts on this connection only
        assertTrue(e.readLists(listOf(lina), 1000))
        assertTrue(e.readHere(lina))
        p.conn = 2
        assertFalse(e.readHere(lina))
        p.complete = false
        assertFalse(e.readLists(listOf(lina), 1000), "an answer that isn't known whole")
        assertFalse(e.readHere(lina))
        p.up = false
        assertFalse(e.readLists(listOf(lina), 1000))
    }

    // ---- this device on my list ------------------------------------------------------------------------

    @Test fun notOnMyListWithin60Seconds() = runBlocking {
        val p = Fake()
        val e = runtime(p)
        e.setFormat3(true)
        assertEquals(Here.NOT_YET, e.here())
        assertNull(e.state.value.line, "nothing said in the first minute")
        p.now = 59_999
        assertEquals(Here.NOT_YET, e.here())
        p.now = 60_000
        e.check()
        assertEquals(Here.NOT_LISTED, e.state.value.here)
        assertEquals(EncText.NOT_SET_UP, e.state.value.line)
        assertTrue(e.state.value.tryAgain)
        // senders hold
        assertEquals(EncWrite.Hold(HoldReason.NOT_LISTED), e.peopleReady("n:$account", listOf(account)))
        // Try again: the list read again; now there
        p.relay += list(account, 1, null, listOf(device.pubkey))
        e.tryAgain()
        assertEquals(Here.YES, e.state.value.here)
        assertNull(e.state.value.line)
    }

    @Test fun nothingIsSaidOnFormatOne() {
        val p = Fake()
        val e = runtime(p)
        p.now = 120_000
        e.check()
        assertEquals(Here.NOT_LISTED, e.state.value.here)
        assertNull(e.state.value.line, "belfry.sh is format 1: no lists, no line")
    }

    @Test fun theTwentyFirstDevice() = runBlocking {
        val p = Fake()
        val e = runtime(p)
        e.setFormat3(true)
        val twenty = (1..20).map { key() }
        p.relay += list(account, 1, null, twenty, readers = listOf(key()))
        p.sessions = 21
        e.tick()
        assertEquals(Here.FULL, e.state.value.here, "at once: twenty listed, twenty-one sessions")
        assertEquals(EncText.FULL, e.state.value.line)
        assertFalse(e.state.value.tryAgain)
        assertEquals(EncWrite.Hold(HoldReason.FULL), e.peopleReady("n:$account", listOf(account)))
        // a reader is outside the twenty and never this device
        val nineteen = (1..19).map { key() }
        val p2 = Fake().also { it.now = 61_000; it.sessions = 30 }
        val e2 = runtime(p2)
        e2.offer(listOf(list(account, 1, null, nineteen, readers = listOf(device.pubkey))))
        e2.tick()
        assertTrue(e2.here() != Here.YES && e2.here() != Here.FULL, "listed as a reader only is not listed as a device")
    }

    // ---- the reads marker ------------------------------------------------------------------------------

    @Test fun theReadsMarkerOncePerDevice() {
        val vault = MemoryVault()
        val p = Fake()
        val e = runtime(p, EncKeyStore(vault, EncKeyStore.V_KEYS, account))
        e.check()
        p.runLater()
        assertTrue(p.queued.isEmpty(), "only once on my list")
        e.offer(listOf(list(account, 1, null, listOf(device.pubkey))))
        e.check(); e.check()
        p.runLater()
        assertEquals(1, p.queued.size)
        val (kind, tags, content) = p.queued.single()
        assertEquals(30078, kind)
        assertEquals(listOf(listOf("d", "belfry:device:reads"), listOf("v", "3")), tags)
        assertEquals("", content)
        // the next start of the app: not again
        val p2 = Fake()
        val again = Enc3(device, account, p2, EncKeyStore(vault, EncKeyStore.V_KEYS, account))
        again.offer(listOf(list(account, 1, null, listOf(device.pubkey))))
        again.check(); p2.runLater()
        assertTrue(p2.queued.isEmpty())
    }

    @Test fun aDeviceItemNeedsNoSigner() = runBlocking {
        val byDevice = OutboxItem("d1", Target.MAIN, 30078, "", listOf(listOf("d", "belfry:device:reads")), 2, byDevice = true)
        val plain = OutboxItem("p1", Target.GROUPS, 9, "x", emptyList(), 1)
        val tried = ArrayList<String>()
        Outbox.run(listOf(plain, byDevice)) { item -> tried += item.localId; if (item.localId == "p1") SendOutcome.Error("timeout: the signer did not answer") else SendOutcome.Ok() }
        assertEquals(listOf("p1", "d1"), tried, "the signer being down holds back only what it signs")
        assertTrue(OutboxItem.fromJson(byDevice.toJson()).byDevice)
        assertFalse(OutboxItem.fromJson(plain.toJson()).byDevice)
    }

    // ---- the key store ---------------------------------------------------------------------------------

    private fun ck(K: String, conv: String, at: Long = 100, mine: Boolean = true, group: Boolean = false) =
        ConvKey(K, Keys.generateSecretHex(), conv, device.pubkey, at, mine, group = group, lists = "x")

    @Test fun declaredOnceKeptEncryptedPerAccountWipedAtSignOut() {
        val vault = MemoryVault()
        val name = Slots.key(EncKeyStore.V_KEYS, "a2")
        val e = runtime(Fake(), EncKeyStore(vault, name, account))
        val K = key()
        val first = ck(K, "n:$lina")
        assertTrue(e.putKey(first))
        assertFalse(e.putKey(ck(K, "n:${key()}")), "§17.12: a later declaration of K is ignored")
        assertEquals("n:$lina", e.key(K)?.conv)
        assertEquals(first.secret, e.keyByAlias(EncKeys.alias(K, account))?.secret)
        val older = ck(key(), "n:$lina", at = 50)
        e.putKey(older)
        assertEquals(listOf(K, older.pub), e.keysOf("n:$lina").map { it.pub }, "newest first")
        assertTrue(e.everEncrypted("n:$lina"))
        assertEquals(50, e.firstKeyAt("n:$lina"))
        val g = ck(key(), Enc3.groupConv("pod"), group = true)
        e.putKey(g)
        assertNull(e.keyByAlias(EncKeys.alias(g.pub, account)), "a group key has no alias")
        e.noteEncrypted("n:${key()}", 7)
        assertFalse(first.toString().contains(first.secret), "a secret never in a string")
        // kept, under the account's slot, for this account only
        assertTrue(vault.get(name) != null)
        val back = runtime(Fake(), EncKeyStore(vault, name, account))
        assertEquals(3, back.keyCount)
        assertEquals(first.secret, back.key(K)?.secret)
        assertTrue(back.everEncrypted("n:$lina"))
        assertEquals(0, runtime(Fake(), EncKeyStore(vault, name, lina)).keyCount, "another account's keys are never used")
        assertEquals(0, runtime(Fake(), EncKeyStore(vault, EncKeyStore.V_KEYS, account)).keyCount, "another slot")
        // sign-out: gone, and a late write refused
        back.wipe()
        assertNull(vault.get(name))
        assertEquals(0, back.keyCount)
        back.putKey(ck(key(), "n:$lina"))
        assertNull(vault.get(name), "a write after the wipe is refused")
    }

    // ---- the checks before a send ----------------------------------------------------------------------

    @Test fun peopleReady() = runBlocking {
        val p = Fake().also { it.now = 1 }
        val e = runtime(p)
        val conv = "n:$lina"
        p.relay += list(account, 1, null, listOf(device.pubkey))
        // Lina has no list at all: held, never her account key
        assertEquals(EncWrite.Hold(HoldReason.NO_LIST, lina), e.peopleReady(conv, listOf(lina)))
        // the relay doesn't answer: held
        p.up = false
        assertIs<EncWrite.Hold>(e.peopleReady(conv, listOf(lina)))
        p.up = true
        // an empty list: no device (§17.5)
        val l1 = list(lina, 1, null, emptyList())
        p.relay += l1
        assertEquals(EncWrite.Hold(HoldReason.NO_DEVICE, lina), e.peopleReady(conv, listOf(lina)))
        // a phone, and a reader of hers
        val reader = key()
        val l2 = list(lina, 2, l1, listOf(linaPhone), readers = listOf(reader))
        e.offer(listOf(l2)) // (the live subscription brings it: a list read whole on this connection isn't read again)
        val w = assertIs<EncWrite.V3>(e.peopleReady(conv, listOf(lina)))
        val plan = assertIs<EncPlan.People>(w.plan)
        assertEquals(listOf(linaPhone, reader), plan.per.single { it.account == lina }.devices)
        assertEquals(l2.id, plan.per.single { it.account == lina }.list)
        assertEquals(3, plan.total)
        // the belfry key as a person: its own device, no list
        val svc = assertIs<EncPlan.People>(assertIs<EncWrite.V3>(e.peopleReady("n:$belfry", listOf(belfry))).plan)
        assertEquals(listOf(belfry), svc.per.single { it.account == belfry }.devices)
        assertNull(svc.per.single { it.account == belfry }.list)
        // three people, one with no device: sent for the other (who won't get it is said)
        val sam = key()
        p.relay += list(sam, 1, null, emptyList())
        val three = assertIs<EncPlan.People>(assertIs<EncWrite.V3>(e.peopleReady(Enc3.peopleConv(listOf(lina, sam), account), listOf(lina, sam))).plan)
        assertEquals(listOf(sam), three.missing)
        // a fork holds
        e.offer(listOf(list(lina, 2, l1, listOf(key()), at = 5000)))
        assertEquals(EncWrite.Hold(HoldReason.FORK, lina), e.peopleReady(conv, listOf(lina)))
    }

    @Test fun overAHundredDevices() = runBlocking {
        val p = Fake().also { it.now = 1 }
        val e = runtime(p)
        p.relay += list(account, 1, null, listOf(device.pubkey))
        val people = (1..6).map { key() }
        for (x in people) p.relay += list(x, 1, null, (1..20).map { key() })
        val conv = Enc3.peopleConv(people, account)
        assertEquals(EncWrite.Clear, e.peopleReady(conv, people), "starts over 100: no lock")
        e.noteEncrypted(conv, 10)
        assertEquals(EncWrite.Hold(HoldReason.TOO_MANY), e.peopleReady(conv, people), "§17.2: once encrypted, never clear")
    }

    @Test fun groupReady() = runBlocking {
        val p = Fake().also { it.now = 1 }
        val e = runtime(p)
        p.relay += list(account, 1, null, listOf(device.pubkey))
        p.relay += list(lina, 1, null, listOf(linaPhone))
        val guest = key()
        p.relay += list(guest, 1, null, listOf(key()))
        val nobody = key() // no list at all: read once more, then skipped for this message (§23)
        assertEquals(EncWrite.Hold(HoldReason.GROUP_UNKNOWN), e.groupReady(listOf(lina), { false }), "not a member")
        val w = assertIs<EncWrite.V3>(e.groupReady(listOf(account, lina, guest, nobody, groupsKey), { it == guest }))
        val plan = assertIs<EncPlan.Group>(w.plan)
        assertEquals(setOf(device.pubkey, linaPhone, groupsKey), plan.devices.toSet())
        assertEquals(EncKeys.deviceSet(plan.devices), plan.set)
        assertEquals(setOf(account, lina), plan.lines.map { it.first }.toSet())
    }
}
