package sh.belfry.core.sealed

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import sh.belfry.core.dm.DirectMessages
import sh.belfry.core.dm.DmHost
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Signer
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.Target
import sh.belfry.core.store.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wiring of sealed conversations into direct messages (sealed.md §4, §6, §8, §9), on the
 * engine test's fake relay: Alice (phone) and Bob (phone), both ready.
 */
class DirectMessagesSealedTest {
    private val relay = SealedEngineTest.FakeRelay()
    private val server = SealedEngineTest.Server()
    private var now = 1_795_000_000L

    private inner class Ports(val account: LocalSigner) : SealedPorts {
        var saved: Map<String, String> = emptyMap()
        override suspend fun query(q: Query) = relay.query(q)
        override suspend fun publish(e: Event): Boolean { relay.add(e); return true }
        override suspend fun signAsAccount(t: EventTemplate): Event = account.signNow(t)
        override suspend fun sessions(): List<SessionRow>? = server.sessions[account.pubkey]?.toList() ?: emptyList()
        override suspend fun revoke(clientPubkey: String) { server.sessions[account.pubkey]?.removeAll { it.clientPubkey == clientPubkey } }
        override fun loadKeys() = saved
        override fun saveKeys(keys: Map<String, String>): Boolean { saved = keys; return true }
    }

    /** A signer that counts its calls: sealed messages must never reach it. */
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
        var bridge: String? = null
        override fun bridgeKey(): String? = bridge
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
        var nip05Down = false
        override suspend fun nip05(name: String): String? = if (nip05Down) throw java.io.IOException("offline") else null
        override fun later(delayMs: Long, block: suspend () -> Unit) {}
        // files (vectors/dm-files.json): the item the outbox would hold, the bytes, an upload that works
        val files = ArrayList<OutboxItem>()
        val blobs = HashMap<String, ByteArray>()
        override fun putBlob(key: String, bytes: ByteArray) { blobs[key] = bytes }
        override fun blob(key: String): ByteArray? = blobs[key]
        override suspend fun enqueue(item: OutboxItem) { files.add(item) }
        override suspend fun uploadSealed(bytes: ByteArray, progress: (Long, Long) -> Unit): String = "https://files.belfry.example/" + sh.belfry.core.crypto.sha256Hex(bytes)
    }

    private val alice = LocalSigner.random()
    private val bob = LocalSigner.random()
    private val alicePhone = LocalSigner.random()
    private val bobPhone = LocalSigner.random()

    private fun engine(account: LocalSigner, device: LocalSigner) = SealedEngine(Ports(account), device, account.pubkey, clock = { now }, removalJitter = 0)

    private val hosts = HashMap<DirectMessages, Host>()
    private fun dmOf(h: Host) = DirectMessages(MemoryStore(), h).also { hosts[it] = h }

    private fun ready(): Pair<Triple<SealedEngine, DirectMessages, CountingSigner>, Triple<SealedEngine, DirectMessages, CountingSigner>> = runBlocking {
        server.live(alice.pubkey, alicePhone.pubkey, "Pixel 8")
        server.live(bob.pubkey, bobPhone.pubkey, "Bob's phone")
        val a = engine(alice, alicePhone); a.start("Pixel 8", "phone"); a.checkReady()
        val b = engine(bob, bobPhone); b.start("Bob's phone", "phone"); b.checkReady()
        val sa = CountingSigner(alice); val sb = CountingSigner(bob)
        Pair(Triple(a, dmOf(Host(sa, a)), sa), Triple(b, dmOf(Host(sb, b)), sb))
    }

    private fun conv(x: String) = "n:$x"
    private fun outbox(dm: DirectMessages): List<OutboxItem> = hosts.getValue(dm).queued

    @Test fun sealedSendNeverTheSigner() = runBlocking<Unit> {
        val (a, _) = ready()
        a.second.send(conv(bob.pubkey), "The key is under the blue pot.")
        val wraps = outbox(a.second)
        assertEquals(2, wraps.size, "one to Bob's head, one to mine")
        assertTrue(wraps.all { it.signed!!.tag("lb") != null })
        assertEquals(0, a.third.calls, "no signer call for a sealed message")
    }

    @Test fun receiveSealed() = runBlocking<Unit> {
        val (a, b) = ready()
        a.second.send(conv(bob.pubkey), "hello Bob")
        val toBob = outbox(a.second).first { it.signed!!.tag("p") == bob.pubkey }.signed!!
        b.second.offer(listOf(toBob))
        b.second.process()
        assertEquals("hello Bob", b.second.snapshot.value.thread(conv(alice.pubkey)).single().text)
        assertEquals(0, b.third.calls)
        assertTrue(b.first.sealedSince.containsKey(conv(alice.pubkey)))
    }

    /** A reply (docs/one-shape.md 3) is sealed like any 14: two wraps, no signer; Bob sees it under its root. */
    @Test fun sealedReplyGoesUnderItsRoot() = runBlocking<Unit> {
        val (a, b) = ready()
        a.second.send(conv(bob.pubkey), "Could you bring the extension cable?")
        val root = a.second.snapshot.value.thread(conv(bob.pubkey)).single().rid
        a.second.send(conv(bob.pubkey), "The reel, please.", root = root)
        val wraps = outbox(a.second)
        assertEquals(4, wraps.size, "two wraps a message, as before")
        assertTrue(wraps.all { it.signed!!.tag("lb") != null })
        assertEquals(0, a.third.calls)
        for (w in wraps.filter { it.signed!!.tag("p") == bob.pubkey }) b.second.offer(listOf(w.signed!!))
        b.second.process()
        val snap = b.second.snapshot.value
        assertEquals(listOf("Could you bring the extension cable?"), snap.inline(conv(alice.pubkey)).map { it.text })
        assertEquals(listOf("The reel, please."), snap.replies(conv(alice.pubkey), root).map { it.text })
        assertEquals(0, b.third.calls)
    }

    /**
     * Xavier's mail to himself (2026-11-22): a device of mine not in my head gets a wrap to my letterbox it
     * can't open. No conversation shows it, so the snapshot counts it (Messages' waiting line, the push
     * that stays); the count survives a restart and a wrap seen twice counts once.
     */
    @Test fun lockedWrapIsCounted() = runBlocking<Unit> {
        val (a, _) = ready()
        val aliceTablet = LocalSigner.random()
        server.live(alice.pubkey, aliceTablet.pubkey, "Tablet")
        val tablet = engine(alice, aliceTablet)
        tablet.start("Tablet", "tablet")
        assertEquals(SealedEngine.Mine.WAITING, tablet.mine, "not allowed yet")
        a.second.send(conv(bob.pubkey), "Gate code is 4417.")
        val toMe = outbox(a.second).first { it.signed!!.tag("p") == alice.pubkey }.signed!!
        val store = MemoryStore()
        val host = Host(CountingSigner(alice), tablet)
        val dm = DirectMessages(store, host).also { hosts[it] = host }
        dm.offer(listOf(toMe)); dm.process()
        assertTrue(dm.snapshot.value.items.isEmpty(), "nothing it can show")
        assertEquals(1, dm.snapshot.value.lockedCount)
        assertEquals(0, host.signer.calls, "never the signer")
        dm.offer(listOf(toMe)); dm.process()
        assertEquals(1, dm.snapshot.value.lockedCount, "the same wrap once")
        val again = DirectMessages(store, host)
        assertEquals(1, again.snapshot.value.lockedCount, "kept across a restart, from the first snapshot")
        assertTrue(!sh.belfry.core.push.Push.dmsRead(0, again.snapshot.value.lockedCount), "its push stays")
        assertTrue(sh.belfry.core.push.Push.dmsRead(0, 0))
    }

    /** No engine yet: a sealed wrap is kept, never handed to the signer. */
    @Test fun sealedWrapKeptWithoutEngine() = runBlocking<Unit> {
        val (a, _) = ready()
        a.second.send(conv(bob.pubkey), "x")
        val toBob = outbox(a.second).first { it.signed!!.tag("p") == bob.pubkey }.signed!!
        val sb = CountingSigner(bob)
        val dm = dmOf(Host(sb, null))
        dm.offer(listOf(toBob)); dm.process()
        assertTrue(dm.snapshot.value.items.isEmpty())
        assertEquals(0, sb.calls)
    }

    /** Can't tell: held, nothing sent; decided at the first complete answer; cancel returns the text. */
    @Test fun heldThenDecided() = runBlocking<Unit> {
        val (a, _) = ready()
        val carol = LocalSigner.random().pubkey
        relay.silent = true
        a.second.send(conv(carol), "Are you coming?")
        assertTrue(outbox(a.second).isEmpty(), "never the old way on silence")
        assertNotNull(a.second.snapshot.value.thread(conv(carol)).single().heldSince)
        val d0 = a.second.snapshot.value.thread(conv(carol)).single()
        assertEquals("Saved · waiting for the connection", a.second.snapshot.value.stateLine(d0))
        relay.silent = false
        a.second.process()
        assertEquals(1 + 1, outbox(a.second).size, "decided: the old way for someone with no chain (and my copy)")
        assertNull(a.second.snapshot.value.thread(conv(carol)).single().heldSince)
        // the connection drops: what the relay said no longer counts (knowledge needs a live connection)
        a.first.connectionLost()
        relay.silent = true
        a.second.send(conv(carol), "Second thought")
        val rid = a.second.snapshot.value.thread(conv(carol)).last().rid
        assertEquals("Second thought", a.second.cancelHeld(rid))
        relay.silent = false
    }

    /** §6: an old-style reaction after sealing is ignored; an old-style message is marked "not sealed". */
    @Test fun oldStyleAfterSealing() = runBlocking<Unit> {
        val (a, b) = ready()
        a.second.send(conv(bob.pubkey), "sealed one")
        val toBob = outbox(a.second).first { it.signed!!.tag("p") == bob.pubkey }.signed!!
        b.second.offer(listOf(toBob)); b.second.process()
        now += 60
        val old = GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "from an old app", now)
        val react = GiftWrap.rumor(7, alice.pubkey, listOf(listOf("e", old.id), listOf("p", bob.pubkey)), "👍", now)
        b.second.offer(listOf(GiftWrap.wrap(old, bob.pubkey, alice), GiftWrap.wrap(react, bob.pubkey, alice)))
        b.second.process()
        val t = b.second.snapshot.value.thread(conv(alice.pubkey))
        assertTrue(t.single { it.text == "from an old app" }.notSealed)
        assertTrue(b.second.snapshot.value.reactionsOf(old.id).isEmpty())
    }

    /** §9: a queued wrap is sealed again when the recipient's head moved. */
    @Test fun resealBeforeFlush() = runBlocking<Unit> {
        val (a, b) = ready()
        a.second.send(conv(bob.pubkey), "queued")
        val before = outbox(a.second).first { it.signed!!.tag("p") == bob.pubkey }
        val bobTablet = LocalSigner.random()
        server.live(bob.pubkey, bobTablet.pubkey, "Bob's tablet")
        b.first.allow(bobTablet.pubkey)
        b.first.remove(bobTablet.pubkey) // a removal: a new letterbox for Bob
        val after = a.second.resealQueued(outbox(a.second)).first { it.signed!!.tag("p") == bob.pubkey }
        assertTrue(after.signed!!.tag("lb") != before.signed!!.tag("lb"))
        assertEquals(b.first.chainOf(bob.pubkey)!!.head.lb, after.signed!!.tag("lb"))
    }

    /** 6e0bc8e: the re-seal check works after a restart with wraps still queued (the queue is kept in the store). */
    @Test fun resealAfterRestart() = runBlocking<Unit> {
        val (a, b) = ready()
        val store = MemoryStore()
        val host = Host(a.third, a.first)
        val dm1 = DirectMessages(store, host).also { hosts[it] = host }
        dm1.send(conv(bob.pubkey), "queued before a restart")
        val before = host.queued.first { it.signed!!.tag("p") == bob.pubkey }
        val bobTablet = LocalSigner.random()
        server.live(bob.pubkey, bobTablet.pubkey, "Bob's tablet")
        b.first.allow(bobTablet.pubkey)
        b.first.remove(bobTablet.pubkey)
        val dm2 = DirectMessages(store, host) // the app started again
        val after = dm2.resealQueued(host.queued).first { it.signed!!.tag("p") == bob.pubkey }
        assertTrue(after.signed!!.tag("lb") != before.signed!!.tag("lb"))
    }

    /** canvas v99: a message too long to lock, sent from the box, is refused with the line; no bubble. */
    @Test fun tooLongToLock() = runBlocking<Unit> {
        val (a, _) = ready()
        val e = kotlin.test.assertFailsWith<sh.belfry.core.dm.DmException> { a.second.send(conv(bob.pubkey), "x".repeat(41_000)) }
        assertEquals("Not sent: the message is too long to lock. Shorten it and send again.", e.message)
        assertTrue(a.second.snapshot.value.thread(conv(bob.pubkey)).isEmpty(), "no bubble: the text stays in the box")
        assertTrue(outbox(a.second).isEmpty())
    }

    /** The review's 3: mail follows the same plan: sealed by the device, never the signer; held while the device can't tell. */
    @Test fun mailNeverTheSigner() = runBlocking<Unit> {
        val (a, _) = ready()
        val host = hosts.getValue(a.second)
        host.bridge = LocalSigner.random().pubkey
        a.second.myAddressProvider = { "alex@belfry.example" }
        a.second.send("m:ana@example.org", "Hello Ana", subject = "Visit")
        assertEquals(0, a.third.calls, "no signer call for mail on a ready chain")
        assertTrue(host.queued.any { it.signed!!.tag("p") == host.bridge && it.signed!!.tag("lb") == null }, "to the mail key")
        // silent wraps (sealed.md §4): the wrap for the belfry's mail key never, my own copy always
        assertTrue(host.queued.filter { it.signed!!.tag("p") == host.bridge }.none { GiftWrap.isSilent(it.signed!!) })
        assertTrue(host.queued.filter { it.signed!!.tag("p") == alice.pubkey }.let { own -> own.isNotEmpty() && own.all { GiftWrap.isSilent(it.signed!!) } })
        assertTrue(host.queued.any { it.signed!!.tag("lb") != null }, "the self-copy sealed to my head")
        // a device whose state is unknown (start not done): held, never the signer
        val sa = CountingSigner(alice)
        val cold = Host(sa, engine(alice, LocalSigner.random()))
        cold.bridge = host.bridge
        val dm = dmOf(cold).also { it.myAddressProvider = { "alex@belfry.example" } }
        try { dm.send("m:ana@example.org", "Second", subject = "Visit") } catch (e: sh.belfry.core.dm.DmException) { }
        assertEquals(0, sa.calls)
        assertTrue(cold.queued.isEmpty())
    }

    /** N1: a held mail is decided like a direct one (only my own chain): sealed by the device, never the signer. */
    @Test fun heldMailDecidedLikeADirectOne() = runBlocking<Unit> {
        val (a, _) = ready()
        // the same phone after a restart: its keys, not yet its chain (start not done)
        val ports = Ports(alice).also { it.saved = a.first.keys.toMap() }
        val e2 = SealedEngine(ports, alicePhone, alice.pubkey, clock = { now }, removalJitter = 0)
        val sa = CountingSigner(alice)
        val host = Host(sa, e2).also { it.bridge = LocalSigner.random().pubkey }
        val dm = dmOf(host).also { it.myAddressProvider = { "alex@belfry.example" } }
        relay.silent = true
        dm.send("m:ana@example.org", "Held first", subject = "Visit")
        assertTrue(host.queued.isEmpty(), "can't tell yet: held")
        relay.silent = false
        e2.start("Pixel 8", "phone")
        assertEquals(SealedEngine.Mine.IN_HEAD, e2.mine)
        dm.process() // retryHeld: decided with no participants, like a direct mail
        assertEquals(0, sa.calls, "never the signer")
        assertTrue(host.queued.any { it.signed!!.tag("p") == host.bridge && it.signed!!.tag("lb") == null }, "sealed by the device to the mail key")
        assertTrue(host.queued.any { it.signed!!.tag("lb") != null }, "the self-copy sealed to my head")
    }

    /** Third review P5: a failed lookup of an address on this belfry never turns a direct message into e-mail in clear. */
    @Test fun ownDomainLookupFailureRefuses() = runBlocking<Unit> {
        val (a, _) = ready()
        val host = hosts.getValue(a.second)
        host.nip05Down = true
        kotlin.test.assertFailsWith<sh.belfry.core.dm.DmException> { a.second.convFor("lina@belfry.example", emptyList()) }
        host.nip05Down = false
        assertEquals("m:ana@example.org", a.second.convFor("ana@example.org", emptyList()), "another domain is e-mail")
    }

    /** A file (kind 15) in a sealed conversation is sealed by the device like a kind 14, never the signer (0.7.6). */
    @Test fun sealedFileNeverTheSigner() = runBlocking<Unit> {
        val (a, b) = ready()
        a.second.send(conv(bob.pubkey), "first, sealed")
        hosts.getValue(a.second).queued.clear()
        val callsBefore = a.third.calls
        a.second.sendFiles(conv(bob.pubkey), listOf(sh.belfry.core.dm.DmNewFile("plan.pdf", "application/pdf", ByteArray(300) { it.toByte() })))
        val item = hosts.getValue(a.second).files.single()
        val wraps = a.second.prepareFile(item) {}
        assertEquals(2, wraps.size, "one to Bob's head, one to mine")
        assertTrue(wraps.all { Sealing.isSealed(it) }, "sealed wraps (lb), not the old kind")
        assertEquals(callsBefore, a.third.calls, "no signer call for a sealed file")
        // Bob's device opens it as a file message
        b.second.offer(listOf(wraps.first { it.tag("p") == bob.pubkey }))
        b.second.process()
        val got = b.second.snapshot.value.thread(conv(alice.pubkey)).last()
        assertEquals(sh.belfry.core.dm.DmFiles.KIND, got.kind)
        assertNotNull(got.file)
        assertEquals(0, b.third.calls)
    }

    /** Not sealed: the file goes the old way as before. */
    @Test fun unsealedFileTheOldWay() = runBlocking<Unit> {
        val (a, _) = ready()
        val carol = LocalSigner.random()
        a.second.sendFiles(conv(carol.pubkey), listOf(sh.belfry.core.dm.DmNewFile("a.txt", "text/plain", "hi".toByteArray())))
        val wraps = a.second.prepareFile(hosts.getValue(a.second).files.single()) {}
        assertTrue(wraps.none { Sealing.isSealed(it) })
    }
}
