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
        override fun saveKeys(keys: Map<String, String>) { saved = keys }
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
        assertTrue(conv(alice.pubkey) in b.first.sealedSince)
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
        relay.silent = false
        a.second.process()
        assertEquals(1 + 1, outbox(a.second).size, "decided: the old way for someone with no chain (and my copy)")
        assertNull(a.second.snapshot.value.thread(conv(carol)).single().heldSince)
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
}
