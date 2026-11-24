package sh.belfry.core.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.dm.DirectMessages
import sh.belfry.core.dm.DmException
import sh.belfry.core.dm.DmHost
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.enc.Devices
import sh.belfry.core.enc.Enc
import sh.belfry.core.enc.EncKeys
import sh.belfry.core.enc.EncLists
import sh.belfry.core.enc.EncMessages
import sh.belfry.core.enc.EncText
import sh.belfry.core.enc.EncWrite
import sh.belfry.core.enc.EncWrites
import sh.belfry.core.enc.EnvelopeFor
import sh.belfry.core.enc.HoldReason
import sh.belfry.core.enc.KeyPerson
import sh.belfry.core.enc.Verdict
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.Tag
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.Target
import sh.belfry.core.sealed.SealedEngine
import sh.belfry.core.store.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC 19.83: people conversations in format 3, reading and writing, on a fake community relay (no network):
 * Alice (phone) and Bob (phone), the belfry key as the mail server, the outbox as the hosts' queues.
 */
class Enc3PeopleTest {
    private val belfrySecret = Keys.generateSecret()
    private val belfry = Enc.pub(belfrySecret)
    private val groupsKey = "9".repeat(64)
    private val alice = LocalSigner.random()
    private val bob = LocalSigner.random()
    private val carol = LocalSigner.random()
    private val alicePhone = LocalSigner.random()
    private val bobPhone = LocalSigner.random()

    /** One clock for everyone (milliseconds). Wraps and envelopes carry the wall clock: keep this near it. */
    private var nowMs = System.currentTimeMillis()

    private val relay = ArrayList<Event>()

    private fun list(account: String, n: Int, prev: Event?, devices: List<String>, at: Long = 1000L + n, readers: List<String> = emptyList()): Event {
        val tags = ArrayList<Tag>()
        tags += listOf("p", account); tags += listOf("n", "$n")
        if (prev != null) tags += listOf("prev", prev.id)
        devices.sorted().forEach { tags += listOf("device", it) }
        readers.forEach { tags += listOf("device", it, "reader") }
        return Enc.signWith(belfrySecret, Enc.LIST, at, tags, "")
    }

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

    /** The community relay as one device's runtime sees it. */
    private inner class Ports : Enc3Ports {
        var up = true
        var conn = 1
        val later = ArrayList<suspend () -> Unit>()
        override fun belfryKey(): String? = belfry
        override fun groupsKey(): String? = groupsKey
        override fun connection(): Int? = if (up) conn else null
        override suspend fun query(filter: JsonObject, pageMs: Long): SealedEngine.Answer? =
            if (!up) null else SealedEngine.Answer(relay.filter { matches(it, filter) }, true)
        override fun addEvents(events: List<Event>) {}
        override suspend fun chatSessions(): Int? = 1
        override suspend fun enqueueByDevice(kind: Int, tags: List<Tag>, content: String) {}
        override fun resubscribe() {}
        override fun later(block: suspend () -> Unit) { later += block }
        override fun nowMs(): Long = nowMs
        fun runLater() = runBlocking { while (later.isNotEmpty()) later.removeAt(0)() }
    }

    /** A signer that counts: format 3 never reaches it. */
    private class CountingSigner(val inner: LocalSigner) : Signer by inner {
        var calls = 0
        override suspend fun sign(template: EventTemplate): Event { calls++; return inner.sign(template) }
        override suspend fun nip44Encrypt(peer: String, plaintext: String): String { calls++; return inner.nip44Encrypt(peer, plaintext) }
        override suspend fun nip44Decrypt(peer: String, payload: String): String { calls++; return inner.nip44Decrypt(peer, payload) }
    }

    /** One device: its runtime, its direct messages, its outbox, the session's decision (as Session.encWriteDm). */
    private inner class Device(val account: LocalSigner, val device: LocalSigner) : DmHost {
        val ports = Ports()
        val e3 = Enc3(device, account.pubkey, ports, null)
        override val signer = CountingSigner(account)
        val queued = ArrayList<OutboxItem>()
        var format: Int? = 3
        var guests: Set<String> = emptySet()
        var since3: Long? = 0
        override val me: String get() = account.pubkey
        override val domain = "belfry.example"
        override val blossom = "https://files.belfry.example"
        override val localOnly = false
        override fun now() = nowMs / 1000
        var bridge: String? = belfry
        override fun bridgeKey(): String? = bridge
        override fun belfryKey(): String? = belfry
        override fun realName(pubkey: String) = ""
        override fun outbox(): List<OutboxItem> = queued
        override suspend fun enqueueSigned(target: Target, event: Event, dm: String?): String {
            val it = OutboxItem("l${queued.size}", target, event.kind, event.content, event.tags, event.createdAt, signed = event, dm = dm)
            queued.add(it); return it.localId
        }
        override suspend fun enqueueEnc(event: Event, dm: String, key: String): String {
            val it = OutboxItem("l${queued.size}", Target.MAIN, event.kind, event.content, event.tags, event.createdAt, signed = event, dm = dm, encKey = key)
            queued.add(it); return it.localId
        }
        override suspend fun dropOutbox(localId: String) { queued.removeAll { it.localId == localId } }
        override suspend fun requeue(items: List<OutboxItem>) {}
        override fun addLocalEvents(events: List<Event>) {}
        override suspend fun queryMain(filter: JsonObject): List<Event> = emptyList()
        override suspend fun fetchBytes(url: String): ByteArray = ByteArray(0)
        override suspend fun nip05(name: String): String? = null
        override fun later(delayMs: Long, block: suspend () -> Unit) { ports.later += block }
        override val enc3: Enc3 get() = e3
        override fun format3Since(): Long? = if (format == 3) since3 else null
        override suspend fun encWrite(conv: String): EncWrite {
            val mail = conv.startsWith("m:")
            val peers = if (mail) listOf(belfry) else MailThreads.peers(conv)
            val ever = e3.everEncrypted(conv)
            var w = EncWrites.people(conv, EncWrites.Facts(false, false, format), ever, peers.any { it in guests })
            if (w is EncWrite.V3) w = e3.peopleReady(if (mail) Enc3.peopleConv(peers, me) else conv, peers + me)
            return w
        }
        val dm = DirectMessages(MemoryStore(), this)

        /** What the outbox would publish: every queued event onto the relay (the queue emptied). */
        fun publish(): List<Event> {
            val out = queued.mapNotNull { it.signed }
            relay += out
            queued.clear()
            return out
        }

        /** Events arriving live on this device's subscription. */
        fun receive(events: List<Event>) = runBlocking {
            e3.offer(events)
            dm.offer(events)
            ports.runLater()
            dm.process()
        }
        fun snapshot(): DmSnapshot = dm.snapshot.value
    }

    private val aliceList = list(alice.pubkey, 1, null, listOf(alicePhone.pubkey))
    private val bobList = list(bob.pubkey, 1, null, listOf(bobPhone.pubkey))

    private fun devices(lists: List<Event>, vararg more: Event): Pair<Device, Device> {
        relay += lists + more
        val a = Device(alice, alicePhone)
        val b = Device(bob, bobPhone)
        a.e3.offer(lists + more.toList()); b.e3.offer(lists + more.toList())
        return a to b
    }

    private fun conv(x: LocalSigner) = "n:${x.pubkey}"
    private fun kinds(events: List<Event>) = events.map { it.kind }

    // ---- writing ----------------------------------------------------------------------------------------

    @Test fun envelopesFirstThenOneCopyPerPersonMineSilentSealedByTheDevice() = runBlocking<Unit> {
        val (a, b) = devices(listOf(aliceList, bobList))
        a.dm.send(conv(bob), "The key is under the blue pot.")
        val out = a.queued.map { it.signed!! }
        assertEquals(listOf(Enc.KEY, Enc.KEY, Enc.MSG, Enc.MSG), kinds(out), "the key's envelopes are queued before the message that uses it")
        assertTrue(a.queued.filter { it.kind == Enc.KEY }.all { it.dm == null && it.encKey == null }, "envelopes go on their own")
        assertTrue(a.queued.filter { it.kind == Enc.MSG }.all { it.encKey != null && it.dm != null }, "copies wait for their key's envelopes")
        val mine = out.single { it.kind == Enc.MSG && it.tag("p") == alice.pubkey }
        val bobs = out.single { it.kind == Enc.MSG && it.tag("p") == bob.pubkey }
        assertTrue(mine.hasTag("silent", "1"), "my own copy is silent")
        assertFalse(bobs.hasTag("silent", "1"))
        assertNotEquals(mine.pubkey, bobs.pubkey, "each copy under its own one-time key")
        assertEquals(0, a.signer.calls, "nothing of format 3 goes through the signer")
        assertTrue(out.none { it.kind == 1059 }, "no version-1 or NIP-17 wrap on format 3")
        // the seal is the DEVICE's, in the message and in the envelope
        val k = a.e3.key(a.queued.first { it.kind == Enc.MSG }.encKey!!)!!
        assertEquals(alicePhone.pubkey, EncMessages.openPeopleMessage(k.secret, bobs).seal.pubkey)
        val env = out.single { it.kind == Enc.KEY && it.tag("p") == bob.pubkey }
        assertEquals(alicePhone.pubkey, EncKeys.openPeopleEnvelope(Hex.decode(bobPhone.secretHex), env)!!.seal.pubkey)
        val d = a.snapshot().thread(conv(bob)).single()
        assertTrue(d.enc && d.pending)
        // Bob reads it
        b.receive(a.publish())
        val got = b.snapshot().thread(conv(alice)).single()
        assertEquals("The key is under the blue pot.", got.text)
        assertTrue(got.enc && !got.mine)
        assertTrue(b.e3.everEncrypted(conv(alice)))
        assertEquals(0, b.signer.calls)
    }

    @Test fun aKeyIsReusedUntilAListChangesOrItIs30DaysOld() = runBlocking<Unit> {
        val (a, _) = devices(listOf(aliceList, bobList))
        a.dm.send(conv(bob), "one")
        val k1 = a.queued.first { it.kind == Enc.MSG }.encKey!!
        a.queued.forEach { o -> if (o.kind == Enc.KEY) a.e3.envelopeSent(o.signed!!.id) }
        a.publish()
        a.dm.send(conv(bob), "two")
        assertEquals(listOf(Enc.MSG, Enc.MSG), kinds(a.queued.map { it.signed!! }), "the same key, no new envelopes")
        assertEquals(k1, a.queued.first().encKey)
        a.publish()
        // Bob signs in on a laptop: his list changes, a new key
        val bobLaptop = LocalSigner.random()
        val bob2 = list(bob.pubkey, 2, bobList, listOf(bobPhone.pubkey, bobLaptop.pubkey))
        relay += bob2; a.e3.offer(listOf(bob2))
        a.dm.send(conv(bob), "three")
        val k2 = a.queued.first { it.kind == Enc.MSG }.encKey!!
        assertNotEquals(k1, k2)
        val envBob = a.queued.single { it.kind == Enc.KEY && it.signed!!.tag("p") == bob.pubkey }.signed!!
        assertNotNull(EncKeys.openPeopleEnvelope(Hex.decode(bobLaptop.secretHex), envBob), "every device of every person")
        a.queued.forEach { o -> if (o.kind == Enc.KEY) a.e3.envelopeSent(o.signed!!.id) }
        a.publish()
        // 31 days on: a new key, the lists unchanged
        nowMs += 31L * 86_400_000
        a.dm.send(conv(bob), "four")
        assertNotEquals(k2, a.queued.first { it.kind == Enc.MSG }.encKey)
        assertTrue(a.queued.any { it.kind == Enc.KEY })
    }

    @Test fun envelopesNotConfirmedGoFirstAndAreMadeAgain() = runBlocking<Unit> {
        val (a, b) = devices(listOf(aliceList, bobList))
        a.dm.send(conv(bob), "hello")
        val K = a.queued.first { it.kind == Enc.MSG }.encKey!!
        val first = a.e3.unconfirmed(K)
        assertEquals(2, first.size)
        // queued: the copies wait
        assertEquals(Enc3.EnvGate.Wait(emptyList(), emptyList()), a.e3.envelopesFirst(K, a.queued))
        // Bob's envelope refused for good (its date, §18.1): it goes, the same key's envelopes are made again
        val refused = a.queued.first { it.kind == Enc.KEY && it.signed!!.tag("p") == bob.pubkey }
        a.queued[a.queued.indexOf(refused)] = refused.copy(status = OutboxStatus.FAILED)
        val g = assertIs<Enc3.EnvGate.Wait>(a.e3.envelopesFirst(K, a.queued))
        assertEquals(a.queued.filter { it.kind == Enc.KEY }.map { it.localId }.toSet(), g.drop.toSet())
        assertEquals(2, g.queue.size)
        assertEquals(g.queue.map { it.id }, a.e3.unconfirmed(K))
        assertTrue(first.none { it in a.e3.unconfirmed(K) })
        // confirmed: the copies go
        g.queue.forEach { a.e3.envelopeSent(it.id) }
        assertEquals(Enc3.EnvGate.Go, a.e3.envelopesFirst(K, a.queued))
        // Bob opens it with the rebuilt envelope (same K)
        relay += g.queue
        b.receive(g.queue + a.queued.filter { it.kind == Enc.MSG }.map { it.signed!! })
        assertEquals("hello", b.snapshot().thread(conv(alice)).single().text)
        // a key whose envelopes were lost from the outbox: the next message brings them again (same K)
        val (c, _) = devices(listOf(aliceList, bobList))
        c.dm.send(conv(bob), "x")
        val K2 = c.queued.first { it.kind == Enc.MSG }.encKey!!
        c.queued.clear()
        c.dm.send(conv(bob), "y")
        assertEquals(listOf(Enc.KEY, Enc.KEY, Enc.MSG, Enc.MSG), kinds(c.queued.map { it.signed!! }))
        assertEquals(K2, c.queued.last().encKey)
    }

    @Test fun notesToMyselfAreOnePerson() = runBlocking<Unit> {
        val (a, _) = devices(listOf(aliceList))
        a.dm.send("n:${alice.pubkey}", "milk")
        val out = a.queued.map { it.signed!! }
        assertEquals(listOf(Enc.KEY, Enc.MSG), kinds(out))
        assertTrue(out.all { it.tag("p") == alice.pubkey })
        assertTrue(out.last().hasTag("silent", "1"))
        // my own copy comes back: opened, in Notes
        a.receive(a.publish())
        assertEquals("milk", a.snapshot().thread("n:${alice.pubkey}").single().text)
    }

    @Test fun holdsKeepTheTextAndSayWhyNeverClear() = runBlocking<Unit> {
        val (a, _) = devices(listOf(aliceList, bobList))
        // Carol has no list: held, never her account key
        a.dm.send(conv(carol), "for Carol")
        val d = a.snapshot().thread(conv(carol)).single()
        assertNotNull(d.heldSince)
        assertEquals(EncWrite.Hold(HoldReason.NO_LIST, carol.pubkey), DmSnapshot.enc3Hold(d))
        assertEquals(EncText.held(EncWrite.Hold(HoldReason.NO_LIST, carol.pubkey), { "Carol" }), a.snapshot().stateLine(d) { "Carol" })
        assertTrue(a.queued.isEmpty(), "nothing queued")
        // an empty list: no device
        val c1 = list(carol.pubkey, 1, null, emptyList())
        relay += c1; a.e3.offer(listOf(c1))
        a.dm.retryHeldNow()
        assertEquals(EncWrite.Hold(HoldReason.NO_DEVICE, carol.pubkey), DmSnapshot.enc3Hold(a.snapshot().thread(conv(carol)).single()))
        assertEquals("Carol isn’t signed in on any device · It will be sent when they are", a.snapshot().stateLine(a.snapshot().thread(conv(carol)).single()) { "Carol" })
        // she signs in: Try again sends it
        val carolPhone = LocalSigner.random()
        val c2 = list(carol.pubkey, 2, c1, listOf(carolPhone.pubkey))
        relay += c2; a.e3.offer(listOf(c2))
        a.dm.retryHeldNow()
        val sent = a.snapshot().thread(conv(carol)).single()
        assertNull(sent.heldSince)
        assertTrue(sent.enc)
        assertEquals(listOf(Enc.KEY, Enc.KEY, Enc.MSG, Enc.MSG), kinds(a.queued.map { it.signed!! }))
        assertEquals(0, a.signer.calls)
    }

    @Test fun aPersonWithNoDeviceWontGetIt() = runBlocking<Unit> {
        val c1 = list(carol.pubkey, 1, null, emptyList())
        val (a, _) = devices(listOf(aliceList, bobList, c1))
        val group = Enc3.peopleConv(listOf(bob.pubkey, carol.pubkey), alice.pubkey)
        a.dm.send(group, "dinner?")
        val d = a.snapshot().thread(group).single()
        assertNull(d.heldSince)
        assertEquals(listOf(carol.pubkey), d.notTo)
        assertEquals("Carol won’t get this", EncText.wontGet(listOf("Carol")))
        val copies = a.queued.filter { it.kind == Enc.MSG }.map { it.signed!!.tag("p") }.toSet()
        assertEquals(setOf(alice.pubkey, bob.pubkey), copies)
    }

    @Test fun notListedHereAndForksHold() = runBlocking<Unit> {
        // my device isn't on my list (60 s on: the clock moves)
        val other = list(alice.pubkey, 1, null, listOf(LocalSigner.random().pubkey))
        relay += listOf(other, bobList)
        val a = Device(alice, alicePhone)
        a.e3.offer(listOf(other, bobList))
        nowMs += 61_000
        a.dm.send(conv(bob), "hi")
        assertEquals(HoldReason.NOT_LISTED, DmSnapshot.enc3Hold(a.snapshot().thread(conv(bob)).single())?.reason)
        // Bob's lists forked: held
        relay.clear()
        val (b2, _) = devices(listOf(aliceList, bobList), list(bob.pubkey, 1, null, listOf(LocalSigner.random().pubkey), at = 1007))
        b2.dm.send(conv(bob), "hi")
        assertEquals(EncWrite.Hold(HoldReason.FORK, bob.pubkey), DmSnapshot.enc3Hold(b2.snapshot().thread(conv(bob)).single()))
        assertTrue(a.queued.isEmpty() && b2.queued.isEmpty())
    }

    @Test fun onceEncryptedNeverClearAndReactionsOnHoldSayIt() = runBlocking<Unit> {
        val (a, _) = devices(listOf(aliceList, bobList))
        a.dm.send(conv(bob), "first")
        a.publish()
        // Bob is now a guest here (§25): never NIP-17 into a conversation that was encrypted
        a.guests = setOf(bob.pubkey)
        a.dm.send(conv(bob), "second")
        val d = a.snapshot().thread(conv(bob)).last()
        assertEquals(HoldReason.GUEST, DmSnapshot.enc3Hold(d)?.reason)
        assertTrue(a.queued.isEmpty())
        assertEquals(0, a.signer.calls)
        // a reaction has no bubble to wait in: refused with the words
        assertFailsWith<DmException> { a.dm.react(a.snapshot().thread(conv(bob)).first(), "👍") }
        // the format unknown (a stale 1): held, never version 1
        a.guests = emptySet()
        a.format = null
        a.dm.send(conv(carol), "?")
        assertEquals(HoldReason.FORMAT, DmSnapshot.enc3Hold(a.snapshot().thread(conv(carol)).single())?.reason)
        // encrypted here before: encrypted whatever the format says (§17.2)
        a.dm.send(conv(bob), "third")
        assertTrue(a.queued.isNotEmpty() && a.queued.all { it.kind == Enc.MSG || it.kind == Enc.KEY })
    }

    @Test fun formatOneIsUnchanged() = runBlocking<Unit> {
        val (a, _) = devices(listOf(aliceList, bobList))
        a.format = 1
        a.dm.send(conv(bob), "old way")
        assertEquals(setOf(1059), a.queued.map { it.kind }.toSet(), "format 1: NIP-17 (no sealed engine here), exactly as before")
        assertTrue(a.signer.calls > 0)
        assertFalse(a.e3.everEncrypted(conv(bob)))
    }

    // ---- reading -----------------------------------------------------------------------------------------

    @Test fun aKeyWaitsForItsMakersListAndNeverIsMarkedSeen() = runBlocking<Unit> {
        // Bob doesn't have Alice's list yet
        relay += listOf(aliceList, bobList)
        val a = Device(alice, alicePhone); a.e3.offer(listOf(aliceList, bobList))
        val b = Device(bob, bobPhone); b.e3.offer(listOf(bobList))
        a.dm.send(conv(bob), "wait for it")
        val out = a.publish()
        relay.remove(aliceList) // the relay doesn't serve it either (yet)
        b.receive(out)
        assertTrue(b.snapshot().thread(conv(alice)).isEmpty())
        assertEquals(1, b.snapshot().openingCount, "no key yet: Opening")
        // her list arrives: the key is taken, the message opens
        relay += aliceList
        b.e3.offer(listOf(aliceList))
        b.ports.runLater()
        b.dm.process()
        assertEquals("wait for it", b.snapshot().thread(conv(alice)).single().text)
        assertEquals(0, b.snapshot().openingCount)
    }

    @Test fun aNewDeviceOfTheAuthorWaitsThenOpensAndAStrangerIsDropped() = runBlocking<Unit> {
        val (a, b) = devices(listOf(aliceList, bobList))
        a.dm.send(conv(bob), "hello")
        b.receive(a.publish())
        val K = b.e3.keysOf(conv(alice)).single()
        // a message under that key sealed by Alice's new tablet, whose list Bob hasn't got yet
        val tablet = LocalSigner.random()
        val rumor = GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "from my tablet", nowMs / 1000)
        val m = EncMessages.peopleMessage(Hex.decode(tablet.secretHex), rumor, K.pub, listOf(bob.pubkey), alice.pubkey).single()
        b.receive(listOf(m))
        val placeholder = b.snapshot().thread(conv(alice)).last()
        assertTrue(placeholder.opening, "its key is here: Opening this message… in the conversation")
        // the list that adds the tablet arrives
        val a2 = list(alice.pubkey, 2, aliceList, listOf(alicePhone.pubkey, tablet.pubkey), at = nowMs / 1000 - 60)
        relay += a2; b.e3.offer(listOf(a2))
        b.dm.process()
        assertEquals(listOf("hello", "from my tablet"), b.snapshot().thread(conv(alice)).map { it.text })
        // a stranger's seal in Alice's name: kept while her list may be behind, dropped after a complete read
        val stranger = LocalSigner.random()
        val r2 = GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "not Alice", nowMs / 1000)
        val forged = EncMessages.peopleMessage(Hex.decode(stranger.secretHex), r2, K.pub, listOf(bob.pubkey), alice.pubkey).single()
        b.receive(listOf(forged))
        assertTrue(b.e3.readLists(listOf(alice.pubkey), 1000))
        b.dm.process()
        assertTrue(b.snapshot().thread(conv(alice)).none { it.opening || it.text == "not Alice" }, "dropped")
    }

    @Test fun aReaderNeverSignsAsItsOwner() = runBlocking<Unit> {
        val agent = LocalSigner.random()
        val bobWithAgent = list(bob.pubkey, 1, null, listOf(bobPhone.pubkey), readers = listOf(agent.pubkey))
        val (a, b) = devices(listOf(aliceList, bobWithAgent))
        b.dm.send(conv(alice), "hi Alice")
        a.receive(b.publish())
        val K = a.e3.keysOf(conv(bob)).single()
        // the agent got the key as a reader; a seal of its own as Bob is refused
        val r = GiftWrap.rumor(14, bob.pubkey, listOf(listOf("p", alice.pubkey)), "the agent as Bob", nowMs / 1000)
        val forged = EncMessages.peopleMessage(Hex.decode(agent.secretHex), r, K.pub, listOf(alice.pubkey), bob.pubkey).single()
        assertEquals(Enc3.Opened3.Drop, a.e3.open(forged))
        a.receive(listOf(forged))
        assertEquals(listOf("hi Alice"), a.snapshot().thread(conv(bob)).map { it.text })
    }

    @Test fun junkEnvelopesNeverBlock() = runBlocking<Unit> {
        val (a, b) = devices(listOf(aliceList, bobList))
        a.dm.send(conv(bob), "found it")
        val out = a.publish()
        val good = out.single { it.kind == Enc.KEY && it.tag("p") == bob.pubkey }
        val alias = good.tag("d")!!
        relay.remove(good)
        // junk under the same alias: not JSON, an entry for this device that doesn't open, someone else's entry
        val junk = listOf(
            Enc.signWith(Keys.generateSecret(), Enc.KEY, nowMs / 1000, listOf(listOf("p", bob.pubkey), listOf("d", alias)), "{not json"),
            Enc.signWith(Keys.generateSecret(), Enc.KEY, nowMs / 1000, listOf(listOf("p", bob.pubkey), listOf("d", alias)), "{\"${bobPhone.pubkey}\":\"garbage\"}"),
            Enc.signWith(Keys.generateSecret(), Enc.KEY, nowMs / 1000, listOf(listOf("p", bob.pubkey), listOf("d", alias)), "{\"${"7".repeat(64)}\":\"x\"}"),
        )
        relay += junk
        b.receive(junk + out.filter { it.kind == Enc.MSG && it.tag("p") == bob.pubkey })
        assertTrue(b.snapshot().thread(conv(alice)).isEmpty(), "junk only so far")
        // the good one is on the relay: asked by #d
        relay += good
        nowMs += 61_000
        b.dm.process()
        b.ports.runLater()
        b.dm.process()
        assertEquals("found it", b.snapshot().thread(conv(alice)).single().text)
    }

    @Test fun noEntryForThisDeviceIsCantBeRead() = runBlocking<Unit> {
        // Alice knows Bob only with his laptop: this phone of Bob's gets no entry
        val laptop = LocalSigner.random()
        val bobLaptopOnly = list(bob.pubkey, 1, null, listOf(laptop.pubkey))
        relay += listOf(aliceList, bobLaptopOnly)
        val a = Device(alice, alicePhone); a.e3.offer(listOf(aliceList, bobLaptopOnly))
        val b = Device(bob, bobPhone); b.e3.offer(listOf(aliceList, bobLaptopOnly))
        a.dm.send(conv(bob), "for the laptop")
        val out = a.publish()
        b.receive(out.filter { it.tag("p") == bob.pubkey })
        b.ports.runLater() // the #d read: envelopes found, none for this device
        b.dm.process()
        assertEquals(1, b.snapshot().unreadableCount)
        assertEquals("1 message can’t be read on this device", EncText.unreadable(1))
        assertTrue(b.snapshot().thread(conv(alice)).isEmpty())
    }

    @Test fun plainNip17FromAListedAccountIsDroppedOnFormat3() = runBlocking<Unit> {
        val (a, b) = devices(listOf(aliceList, bobList))
        val r = GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "old app", nowMs / 1000)
        val w = GiftWrap.wrap(r, bob.pubkey, alice)
        b.receive(listOf(w))
        assertTrue(b.snapshot().thread(conv(alice)).isEmpty(), "Alice has a device list: her plain NIP-17 is dropped")
        // from an account without a list it still shows (unencrypted, no lock)
        val r2 = GiftWrap.rumor(14, carol.pubkey, listOf(listOf("p", bob.pubkey)), "no list", nowMs / 1000)
        b.receive(listOf(GiftWrap.wrap(r2, bob.pubkey, carol)))
        assertEquals("no list", b.snapshot().thread(conv(carol)).single().text)
        // on format 1 nothing is dropped
        val c = Device(bob, bobPhone).also { it.format = 1; it.e3.offer(listOf(aliceList, bobList)) }
        c.receive(listOf(GiftWrap.wrap(GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "format 1", nowMs / 1000), bob.pubkey, alice)))
        assertEquals("format 1", c.snapshot().thread(conv(alice)).single().text)
        assertEquals(0, a.queued.size)
    }

    @Test fun clearAfterTheFirstKeyIsMarkedAndNeverActs() = runBlocking<Unit> {
        val (a, b) = devices(listOf(aliceList, bobList))
        b.format = 1 // so that a plain wrap is not dropped (the rule above); the conversation's key is what counts
        val before = GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "before", nowMs / 1000 - 3600)
        b.receive(listOf(GiftWrap.wrap(before, bob.pubkey, alice)))
        // a plain one dated after the key, arriving before the key is known here: judged again once it is
        val lateEarly = GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "late, early", nowMs / 1000 + 30)
        b.receive(listOf(GiftWrap.wrap(lateEarly, bob.pubkey, alice)))
        a.dm.send(conv(bob), "encrypted")
        b.receive(a.publish())
        val first = b.e3.firstKeyAt(conv(alice))!!
        val enc = b.snapshot().thread(conv(alice)).single { it.text == "encrypted" }
        // after the key: a plain message shows marked, a plain reaction doesn't count
        val late = GiftWrap.rumor(14, alice.pubkey, listOf(listOf("p", bob.pubkey)), "late", first + 60)
        val react = GiftWrap.rumor(7, alice.pubkey, listOf(listOf("e", enc.rid, "", alice.pubkey), listOf("p", alice.pubkey), listOf("k", "14")), "👎", first + 60)
        b.receive(listOf(GiftWrap.wrap(late, bob.pubkey, alice), GiftWrap.wrap(react, bob.pubkey, alice)))
        val t = b.snapshot().thread(conv(alice)).associateBy { it.text }
        assertFalse(t.getValue("before").clear, "older history is history")
        assertTrue(t.getValue("late").clear)
        assertTrue(t.getValue("late, early").clear, "judged again when the key arrived")
        assertFalse(t.getValue("encrypted").clear)
        assertTrue(b.snapshot().reactionsOf(enc.rid).isEmpty(), "a clear reaction never acts")
    }

    // ---- e-mail (§10) ----------------------------------------------------------------------------------

    @Test fun mailOutAddressesTheBelfryKeyAsOneMorePerson() = runBlocking<Unit> {
        val (a, _) = devices(listOf(aliceList))
        a.bridge = "8".repeat(64) // the old bridge key: never used on format 3
        a.dm.myAddressProvider = { "alice@belfry.example" }
        val thread = a.dm.send("m:someone@example.org", "Dear someone", subject = "Hello")
        val out = a.queued.map { it.signed!! }
        assertEquals(setOf(Enc.KEY, Enc.MSG), out.map { it.kind }.toSet(), "no gift wrap to the bridge")
        assertEquals(setOf(alice.pubkey, belfry), out.filter { it.kind == Enc.MSG }.map { it.tag("p") }.toSet())
        assertTrue(out.single { it.kind == Enc.MSG && it.tag("p") == alice.pubkey }.hasTag("silent", "1"))
        val d = a.snapshot().thread(thread).single()
        assertTrue(d.enc && d.isMail)
        // the mail server's side (§27): its entry is its own key; the key and the message judged as an app does
        val env = out.single { it.kind == Enc.KEY && it.tag("p") == belfry }
        val opened = EncKeys.openPeopleEnvelope(belfrySecret, env)!!
        val chain = EncLists.chainOf(listOf(aliceList), belfry, alice.pubkey)
        val devicesOf = { acct: String -> Devices.of(acct, belfry, groupsKey, if (acct == alice.pubkey) chain else null) }
        assertEquals(Verdict.OK, EncKeys.acceptKeyRumor(opened.rumor, belfry, devicesOf, nowMs / 1000))
        val msg = out.single { it.kind == Enc.MSG && it.tag("p") == belfry }
        val o = EncMessages.openPeopleMessage(opened.rumor.content, msg)
        assertEquals(1301, o.rumor.kind)
        assertEquals("someone@example.org", o.rumor.tag("rcpt-to"))
        assertTrue(o.rumor.hasTag("p", belfry))
        assertEquals(Verdict.OK, EncMessages.acceptPeopleMessage(msg, o.seal, o.rumor, EncKeys.peopleId(listOf(alice.pubkey, belfry)), Devices.Listed(chain!!)))
        assertEquals(0, a.signer.calls)
    }

    @Test fun mailInIsOpenedAndItsKeyNeverSentWith() = runBlocking<Unit> {
        val (a, _) = devices(listOf(aliceList))
        // the belfry key, as a sender (service: its own device), makes a key for {belfry, Alice}
        val kp = EncKeys.newKey()
        val kr = EncKeys.keyRumor(belfry, kp.secret, listOf(KeyPerson(belfry), KeyPerson(alice.pubkey, aliceList.id)), nowMs / 1000)
        val envs = EncKeys.peopleEnvelopes(belfrySecret, kr, listOf(EnvelopeFor(alice.pubkey, listOf(alicePhone.pubkey))))
        val raw = "From: \"Sam\" <sam@example.org>\r\nTo: alice@belfry.example\r\nSubject: Garden\r\nMessage-ID: <1@example.org>\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nThe tomatoes are in.\r\n"
        val rumor = GiftWrap.rumor(1301, belfry, listOf(listOf("p", alice.pubkey), listOf("email-id", "e1"), listOf("mail-from", "sam@example.org"), listOf("subject", "Garden"), listOf("belfry-auth", "pass")), raw, nowMs / 1000)
        val msg = EncMessages.peopleMessage(belfrySecret, rumor, kp.pub, listOf(alice.pubkey), belfry).single()
        relay += envs
        a.receive(envs + msg)
        val d = a.snapshot().threads.single().last
        assertTrue(d.isMail && d.enc)
        assertEquals("sam@example.org", d.from)
        assertEquals(true, d.verified)
        assertTrue(d.conv.startsWith("m:sam@example.org"))
        assertTrue(d.text.contains("tomatoes"))
        val held = a.e3.key(kp.pub)!!
        assertTrue(held.service && !held.mine)
        // Alice answers by e-mail: her own key, never the belfry's
        a.dm.myAddressProvider = { "alice@belfry.example" }
        a.dm.send(d.conv, "Lovely")
        val mine = a.queued.first { it.kind == Enc.MSG }.encKey!!
        assertNotEquals(kp.pub, mine)
    }

    // ---- version 1 writes nothing on format 3 ------------------------------------------------------------

    @Test fun versionOnePortsWriteNothingOnFormat3() = runBlocking<Unit> {
        var on = false
        var signed = 0
        val ports = SessionSealedPorts({ null }, { t -> signed++; alice.signNow(t) }, { emptyList() }, { }, SealedKeyStore(MemoryVault(), "k", alice.pubkey), v1Writes = { on })
        assertFalse(ports.publish(alice.signNow(EventTemplate(30078, "", emptyList(), 1))))
        assertFailsWith<java.io.IOException> { ports.signAsAccount(EventTemplate(30078, "", emptyList(), 1)) }
        assertEquals(0, signed)
        on = true
        ports.signAsAccount(EventTemplate(30078, "", emptyList(), 1))
        assertEquals(1, signed)
    }
}
