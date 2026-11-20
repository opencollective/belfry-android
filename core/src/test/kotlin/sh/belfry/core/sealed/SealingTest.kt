package sh.belfry.core.sealed

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.net.LoginLink
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.LocalSigner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * sealed.md v1 (collective-belfry dbbcfe2), against the text until the shared vectors arrive
 * (vectors/sealed.json): chains, seal / open, key handover, requests, shared history, names.
 */
class SealingTest {
    private val account = LocalSigner.random()
    private val phone = LocalSigner.random()
    private val laptop = LocalSigner.random()
    private val tablet = LocalSigner.random()
    private val t0 = 1_790_000_000L

    private fun roster(by: LocalSigner, n: Int, prev: Event?, lb: String, devices: List<LocalSigner>, at: Long, ready: Boolean = false): Event =
        by.signNow(Roster.template(account.pubkey, n, prev?.id, lb, devices.map { it.pubkey }, ready, at))

    private fun anchor(root: Event, at: Long = t0): Event = account.signNow(Anchor.template(root.id, at))

    private val lb1 = KeyChain.newLetterbox()
    private val lb2 = KeyChain.newLetterbox()

    @Test fun chainGrowsAndShrinks() {
        val r1 = roster(phone, 1, null, lb1.first, listOf(phone), t0)
        val r2 = roster(phone, 2, r1, lb1.first, listOf(phone, laptop), t0 + 10, ready = true)
        val r3 = roster(laptop, 3, r2, lb2.first, listOf(laptop), t0 + 20)
        val c = Chain.of(account.pubkey, listOf(anchor(r1)), listOf(r3, r1, r2))!!
        assertEquals(3, c.rosters.size)
        assertEquals(lb2.first, c.head.lb)
        assertEquals(listOf(laptop.pubkey), c.devices)
        assertTrue(c.ready, "ready stays once it was there")
        assertEquals(phone.pubkey, c.addedFrom(laptop.pubkey))
        assertNull(c.addedFrom(phone.pubkey), "the root device was added by nobody else")
        // the dropped phone: accepted up to the roster that dropped it
        assertTrue(c.accepts(phone.pubkey, t0 + 20))
        assertFalse(c.accepts(phone.pubkey, t0 + 21))
        assertTrue(c.accepts(laptop.pubkey, t0 + 999))
        assertFalse(c.accepts(tablet.pubkey, t0))
        val changes = c.changesAfter(r1.id)
        assertEquals(listOf(RosterChange(laptop.pubkey, true, t0 + 10), RosterChange(phone.pubkey, false, t0 + 20)), changes)
    }

    @Test fun noAnchorNoChain() {
        val r1 = roster(phone, 1, null, lb1.first, listOf(phone), t0)
        assertNull(Chain.of(account.pubkey, emptyList(), listOf(r1)))
        // an anchor signed by someone else doesn't count
        val other = LocalSigner.random().signNow(Anchor.template(r1.id, t0))
        assertNull(Chain.of(account.pubkey, listOf(other), listOf(r1)))
    }

    @Test fun invalidRostersAreIgnored() {
        val r1 = roster(phone, 1, null, lb1.first, listOf(phone), t0)
        // a removal must change lb; keeping it is invalid
        val keepLb = roster(phone, 2, r1, lb1.first, listOf(laptop), t0 + 5)
        // an add must keep lb
        val newLbOnAdd = roster(phone, 2, r1, lb2.first, listOf(phone, laptop), t0 + 5)
        // signed by a device not in the previous roster
        val stranger = roster(tablet, 2, r1, lb1.first, listOf(phone, tablet), t0 + 5)
        // wrong n
        val skip = roster(phone, 3, r1, lb1.first, listOf(phone, laptop), t0 + 5)
        val c = Chain.of(account.pubkey, listOf(anchor(r1)), listOf(r1, keepLb, newLbOnAdd, stranger, skip))!!
        assertEquals(1, c.rosters.size)
        // a device twice, or 21 devices, doesn't parse
        val twice = phone.signNow(Roster.template(account.pubkey, 2, r1.id, lb1.first, listOf(phone.pubkey, laptop.pubkey), false, t0).let { t ->
            t.copy(tags = t.tags + listOf(listOf("device", laptop.pubkey)))
        })
        assertNull(Roster.parse(twice))
        // a removed letterbox can't come back
        val r2 = roster(phone, 2, r1, lb2.first, listOf(laptop), t0 + 5)
        val back = roster(laptop, 3, r2, lb1.first, listOf(tablet), t0 + 6)
        assertEquals(2, Chain.of(account.pubkey, listOf(anchor(r1)), listOf(r1, r2, back))!!.rosters.size)
    }

    @Test fun lowerIdWins() {
        val r1 = roster(phone, 1, null, lb1.first, listOf(phone), t0)
        val a = roster(phone, 2, r1, lb1.first, listOf(phone, laptop), t0 + 5)
        val b = roster(phone, 2, r1, lb1.first, listOf(phone, tablet), t0 + 5)
        val win = listOf(a, b).minByOrNull { it.id }!!
        val c = Chain.of(account.pubkey, listOf(anchor(r1)), listOf(r1, b, a))!!
        assertEquals(win.id, c.head.id)
    }

    @Test fun sealAndOpen() {
        val lina = LocalSigner.random()
        val linaLb = KeyChain.newLetterbox()
        val rumor = GiftWrap.rumor(14, account.pubkey, listOf(listOf("p", lina.pubkey)), "The key is under the blue pot.", t0)
        val wraps = Sealing.sealAll(rumor, phone, mapOf(lina.pubkey to linaLb.first, account.pubkey to lb1.first), t0)
        assertEquals(2, wraps.size)
        val toLina = wraps.first { it.tag("p") == lina.pubkey }
        assertTrue(Sealing.isSealed(toLina))
        assertEquals(linaLb.first, toLina.tag("lb"))
        val opened = Sealing.open(toLina, KeyChain(mapOf(linaLb)))
        assertIs<Opening.Opened>(opened)
        assertEquals(rumor.id, opened.rumor.id)
        assertEquals(phone.pubkey, opened.sealPubkey, "the seal is the device's, not the account's")
        // no secret here: locked, the wrap is kept
        assertIs<Opening.Locked>(Sealing.open(toLina, KeyChain()))
        // the old kind
        val old = runBlocking { GiftWrap.wrap(rumor, lina.pubkey, account) }
        assertEquals(Opening.NotSealed, Sealing.open(old, KeyChain(mapOf(linaLb))))
        // a wrong secret for the lb is refused by the chain itself
        assertFalse(KeyChain().add(linaLb.first, lb1.second))
    }

    @Test fun handover() = runBlocking {
        val chain = KeyChain(mapOf(lb1, lb2))
        val wrap = Sealing.keysFor(phone, account.pubkey, tablet.pubkey, chain, t0)
        assertEquals(tablet.pubkey, wrap.tag("p"))
        val got = assertNotNull(Sealing.openKeys(wrap, tablet))
        assertEquals(phone.pubkey, got.giver)
        assertEquals(account.pubkey, got.account)
        assertEquals(chain.toMap(), got.keys)
        assertNull(Sealing.openKeys(wrap, laptop), "only the receiving device opens it")
    }

    @Test fun requestsAndLink() {
        val secret = "ab".repeat(32)
        val req = tablet.signNow(Sealing.request(account.pubkey, tablet.pubkey, secret, t0))
        assertEquals(tablet.pubkey to account.pubkey, Sealing.readRequest(req))
        assertTrue(Sealing.linkMatches(req, listOf("cd".repeat(32), secret)))
        assertFalse(Sealing.linkMatches(req, listOf("cd".repeat(32))))
        val plain = tablet.signNow(Sealing.request(account.pubkey, tablet.pubkey, null, t0))
        assertFalse(Sealing.linkMatches(plain, listOf(secret)))
    }

    @Test fun loginLinkKeepsTheSecret() {
        val s = "0f".repeat(32)
        val l = assertNotNull(LoginLink.parse("https://chat.riverside.example/login/sam#l=$s"))
        assertEquals(s, l.linkSecret)
        assertEquals("https://chat.riverside.example/login/sam#l=$s", l.url())
        assertNull(LoginLink.parse("https://chat.riverside.example/login/sam#l=" + "0F".repeat(32)))
        assertNull(LoginLink.parse("https://chat.riverside.example/login/sam#l=abc"))
        assertNull(LoginLink.parse("https://chat.riverside.example/login/sam#x"))
        assertNull(LoginLink.parse("https://chat.riverside.example/login/sam")!!.linkSecret)
    }

    @Test fun sharedHistory() {
        val rumors = (1..450).map { GiftWrap.rumor(14, account.pubkey, listOf(listOf("p", phone.pubkey)), "message $it", t0 + it) }
        val shares = Sealing.shareRumors(account.pubkey, phone.pubkey, rumors, t0)
        assertEquals(3, shares.size, "at most 200 rumors each")
        val back = shares.flatMap { Sealing.sharedRumors(it) }
        assertEquals(rumors.map { it.id }, back.map { it.id })
        val mid = (1..10).map { GiftWrap.rumor(14, account.pubkey, emptyList(), "x".repeat(15_000), t0 + it) }
        val midShares = Sealing.shareRumors(account.pubkey, phone.pubkey, mid, t0)
        assertTrue(midShares.size >= 4)
        assertTrue(midShares.all { Sealing.fits(it) }, "each 8564 can itself be sealed")
        assertEquals(10, midShares.sumOf { Sealing.sharedRumors(it).size })
        // one rumor larger than the limit alone is left out
        val huge = GiftWrap.rumor(14, account.pubkey, emptyList(), "x".repeat(41_000), t0)
        assertEquals(0, Sealing.shareRumors(account.pubkey, phone.pubkey, listOf(huge), t0).size)
    }

    @Test fun sizeLimit() {
        val lina = LocalSigner.random().pubkey
        val ok = GiftWrap.rumor(14, account.pubkey, emptyList(), "x".repeat(39_000), t0)
        assertTrue(Sealing.open(Sealing.seal(ok, phone, lina, lb1.first, t0), KeyChain(mapOf(lb1))) is Opening.Opened)
        val tooBig = GiftWrap.rumor(14, account.pubkey, emptyList(), "x".repeat(40_001), t0)
        kotlin.test.assertFailsWith<Sealing.TooLarge> { Sealing.seal(tooBig, phone, lina, lb1.first, t0) }
    }

    @Test fun openerComputesTheId() {
        val lina = LocalSigner.random().pubkey
        val rumor = GiftWrap.rumor(14, account.pubkey, emptyList(), "hi", t0)
        val forged = rumor.copy(id = "00".repeat(32), sig = "ff".repeat(64))
        val opened = Sealing.open(Sealing.seal(forged, phone, lina, lb1.first, t0), KeyChain(mapOf(lb1)))
        assertIs<Opening.Opened>(opened)
        assertEquals(rumor.id, opened.rumor.id)
        assertEquals("", opened.rumor.sig)
    }

    @Test fun deviceNames() {
        val keys = KeyChain(mapOf(lb1))
        val e1 = account.signNow(DeviceNames.template(phone.pubkey, DeviceNames.Entry("Pixel 8", "phone", t0), lb1.first, lb1.second, t0))
        // the laptop writes its own event in the same second: neither drops the other (1390386)
        val e2 = account.signNow(DeviceNames.template(laptop.pubkey, DeviceNames.Entry("Firefox on Linux", "computer", t0), lb1.first, lb1.second, t0))
        val names = DeviceNames.all(listOf(e1, e2), account.pubkey, keys)
        assertEquals("Pixel 8", names[phone.pubkey]!!.name)
        assertEquals("computer", names[laptop.pubkey]!!.kind)
        // a newer event of the same device replaces its name
        val e3 = account.signNow(DeviceNames.template(phone.pubkey, DeviceNames.Entry("Sam's Pixel", "phone", t0), lb1.first, lb1.second, t0 + 5))
        assertEquals("Sam's Pixel", DeviceNames.all(listOf(e3, e1, e2), account.pubkey, keys)[phone.pubkey]!!.name)
        assertEquals("belfry:device:" + phone.pubkey, e1.tag("d"))
        assertTrue(DeviceNames.all(listOf(e1), account.pubkey, KeyChain()).isEmpty(), "other people never read it")
        // signed by someone else than the account: ignored
        val forged = LocalSigner.random().signNow(DeviceNames.template(phone.pubkey, DeviceNames.Entry("x", "phone", t0), lb1.first, lb1.second, t0))
        assertTrue(DeviceNames.all(listOf(forged), account.pubkey, keys).isEmpty())
    }

    @Test fun whenSealed() {
        val r1 = roster(phone, 1, null, lb1.first, listOf(phone), t0, ready = true)
        val c = Chain.of(account.pubkey, listOf(anchor(r1)), listOf(r1))
        val lina = LocalSigner.random().pubkey
        assertTrue(Sealing.canSeal(listOf(account.pubkey), mapOf(account.pubkey to c)))
        assertFalse(Sealing.canSeal(listOf(account.pubkey, lina), mapOf(account.pubkey to c, lina to null)))
        assertTrue(Sealing.readyFor(listOf(phone.pubkey, laptop.pubkey), listOf(phone.pubkey)))
        assertFalse(Sealing.readyFor(listOf(phone.pubkey), listOf(phone.pubkey, laptop.pubkey)))
    }
}
