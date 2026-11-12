package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import sh.belfry.core.crypto.Keys
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.Target
import sh.belfry.core.store.MemoryStore
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Several accounts on one device (SPEC 19.24): the list, the first account's migration, switching, sign-out. */
class AccountsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val opened = ArrayList<Session>()
    private val now = 1_800_000_000L

    @AfterTest fun tearDown() { opened.forEach { it.stop() }; scope.cancel() }

    private fun key(n: Int) = LocalSigner(n.toString(16).padStart(64, '0'))
    private val a = key(1).pubkey
    private val b = key(2).pubkey
    private val c = key(3).pubkey

    private fun saved(domain: String, n: Int) = SavedSession(
        Community.defaults(domain), "bunker://${key(100 + n).pubkey}?relay=wss://bunker.$domain&secret=s$n",
        Keys.generateSecretHex(), key(n).pubkey,
    )

    private fun onboarding(vault: Vault, stores: StoreProvider) = Onboarding(vault, stores, http, scope, { now }, ZoneOffset.UTC)

    // ---- the list ---------------------------------------------------------------------------------

    @Test
    fun theFirstAccountTakesTheOldNamesAndOthersTheirOwn() {
        var list = AccountList.EMPTY
        val (l1, r1, new1) = list.signedIn("belfry.sh", a)
        assertTrue(new1)
        assertEquals("", r1.slot)
        assertEquals("$a@belfry.sh", r1.id)
        val (l2, r2, _) = l1.signedIn("Enspiral.EU", b)
        assertEquals("a2", r2.slot)
        assertEquals("enspiral.eu", r2.domain)
        val (l3, r3, _) = l2.signedIn("belfry.sh", c)
        assertEquals("a3", r3.slot)
        assertEquals(r3.id, l3.current)
        list = l3
        assertEquals(listOf("", "a2", "a3"), list.accounts.map { it.slot })
        // signing in again to an account that is here: the same slot, nothing new, it becomes current
        val (l4, r4, new4) = list.signedIn("belfry.sh", a)
        assertFalse(new4)
        assertEquals("", r4.slot)
        assertEquals(3, l4.accounts.size)
        assertEquals(r1.id, l4.current)
        // a numbered slot is not given twice, the first slot is taken again when free
        val gone = l4.without(r2.id)
        assertEquals("a4", gone.signedIn("example.org", b).second.slot)
        assertEquals("", l4.without(r1.id).signedIn("example.org", b).second.slot)
        assertEquals("session", Slots.key("session", ""))
        assertEquals("session@a2", Slots.key("session", "a2"))
        assertEquals("belfry.db", Slots.database(""))
        assertEquals("belfry-a2.db", Slots.database("a2"))
    }

    @Test
    fun signingOutOpensTheNextOne() {
        val l = AccountList.EMPTY.signedIn("belfry.sh", a).first.signedIn("enspiral.eu", b).first.signedIn("belfry.sh", c).first
        val ids = l.accounts.map { it.id }
        // the current one (the last) leaves: the one before it
        assertEquals(ids[1], l.without(ids[2]).current)
        // the first leaves while current: the one after it
        assertEquals(ids[1], l.select(ids[0]).without(ids[0]).current)
        // another one leaves: the current stays
        assertEquals(ids[2], l.without(ids[0]).current)
        // the last one leaves: nobody
        val one = AccountList.EMPTY.signedIn("belfry.sh", a).first
        assertNull(one.without(one.current!!).current)
        assertEquals(l, l.without("nobody@nowhere"))
        assertEquals(l, l.select("nobody@nowhere"))
    }

    @Test
    fun aLinkFindsItsAccounts() {
        val l = AccountList.EMPTY.signedIn("belfry.sh", a).first.signedIn("enspiral.eu", b).first.signedIn("belfry.sh", c).first
        assertEquals(LinkAccounts.None, l.forLink("example.org"))
        assertEquals(b, (l.forLink("ENSPIRAL.eu") as LinkAccounts.One).account.pubkey)
        val several = l.forLink("belfry.sh") as LinkAccounts.Several
        assertEquals(listOf(c, a), several.accounts.map { it.pubkey }, "the current one first")
        // the demo is never a link's account
        val demo = AccountList.EMPTY.signedIn(Demo.DOMAIN, a, demo = true).first
        assertEquals(LinkAccounts.None, demo.forLink(Demo.DOMAIN))
    }

    @Test
    fun oneAccountPerBelfryRegistersForPush() {
        val l = AccountList.EMPTY.signedIn("belfry.sh", a).first.signedIn("enspiral.eu", b).first.signedIn("belfry.sh", c).first
            .signedIn(Demo.DOMAIN, key(9).pubkey, demo = true).first
        // current = the demo: per belfry the first account
        assertEquals(listOf(a, b), l.pushOwners().map { it.pubkey })
        // current = c on belfry.sh: c for belfry.sh, and last
        assertEquals(listOf(b, c), l.select("$c@belfry.sh").pushOwners().map { it.pubkey })
    }

    @Test
    fun theListSurvivesStorageAndDamage() {
        val l = AccountList.EMPTY.signedIn("belfry.sh", a).first.signedIn("enspiral.eu", b, guest = true).first
            .described("$b@enspiral.eu", "Ana", "ana@enspiral.eu", "Enspiral", "https://x.example/p.jpg", true)
        assertEquals(l, AccountList.fromJsonString(l.toJsonString()))
        assertEquals("ana@enspiral.eu", l.currentAccount!!.line)
        assertEquals("belfry.sh", l.accounts[0].line)
        assertNull(AccountList.fromJsonString("not json"))
        assertNull(AccountList.fromJsonString("{}"))
        // a damaged entry, a doubled one, a current that is not there
        val odd = """{"current":"x","next":1,"accounts":[{"slot":"a7","domain":"belfry.sh","pubkey":"$a"},{"slot":"a7","domain":"b.sh","pubkey":"$b"},
            {"slot":"../x","domain":"belfry.sh","pubkey":"$c"},{"slot":"a2","domain":"belfry.sh","pubkey":"zz"},{"slot":"a9","domain":"belfry.sh","pubkey":"$a"}]}"""
        val read = AccountList.fromJsonString(odd)!!
        assertEquals(listOf("$a@belfry.sh"), read.accounts.map { it.id })
        assertEquals("$a@belfry.sh", read.current)
        assertEquals(8, read.nextSlot)
    }

    // ---- the device (made-up domains: a sign-out tries to reach the belfry) ---------------------------

    /** A device from before accounts: one saved session under the old name, its store with a queued message. */
    @Test
    fun theOldSingleAccountIsMigratedInPlace() = runBlocking<Unit> {
        val vault = MemoryVault()
        val first = MemoryStore()
        val old = saved("one.example", 1)
        vault.set(Onboarding.V_SESSION, old.toJsonString())
        vault.set("push-key", "the-old-push-key")
        first.putOutbox(OutboxItem("local-waiting", Target.GROUPS, 9, "written offline", listOf(listOf("h", "general")), now - 60))
        first.set("drafts", "kept")
        val stores = MemoryStores(first)
        val on = onboarding(vault, stores)

        assertTrue(on.hasSession)
        val list = on.accounts
        assertEquals(1, list.accounts.size)
        val rec = list.currentAccount!!
        assertEquals("", rec.slot)
        assertEquals("$a@one.example", rec.id)
        // nothing moved, nothing was signed out
        assertEquals(old.toJsonString(), vault.get(Onboarding.V_SESSION))
        assertEquals("the-old-push-key", vault.get(Slots.key("push-key", rec.slot)))
        val s = on.resume()!!.also { opened.add(it) }
        assertEquals(a, s.me)
        assertEquals(rec.id, s.accountId)
        assertSame(first, s.store)
        assertEquals("kept", s.store.get("drafts"))
        assertNotNull(s.world.value.pending("local-waiting"), "the queued message is still queued")
        assertEquals(1, s.status.value.queued)
        // reading the list again (another launch) changes nothing
        assertEquals(list, onboarding(vault, stores).accounts)
    }

    @Test
    fun eachAccountHasItsOwnStoreAndSwitchingKeepsBoth() = runBlocking<Unit> {
        val vault = MemoryVault()
        val stores = MemoryStores()
        // three accounts as three sign-ins would leave them
        var list = AccountList.EMPTY
        for ((domain, n) in listOf("one.example" to 1, "two.example" to 2, "one.example" to 3)) {
            val (l, rec, _) = list.signedIn(domain, key(n).pubkey)
            list = l
            vault.set(Slots.key(Onboarding.V_SESSION, rec.slot), saved(domain, n).toJsonString())
            stores.open(rec.slot).putOutbox(OutboxItem("local-$n", Target.GROUPS, 9, "from $n", listOf(listOf("h", "general")), now - n))
        }
        vault.set(Onboarding.V_ACCOUNTS, list.toJsonString())
        val on = onboarding(vault, stores)

        val third = on.resume()!!.also { opened.add(it) }
        assertEquals(c, third.me)
        assertEquals("a3", third.slot)
        assertNotNull(third.world.value.pending("local-3"))
        assertNull(third.world.value.pending("local-1"))
        third.stop()

        // switch to the first: its own store, its own queued message; the third's is untouched
        val firstS = on.resume("$a@one.example")!!.also { opened.add(it) }
        assertEquals(a, firstS.me)
        assertEquals("$a@one.example", on.accounts.current)
        assertNotNull(firstS.world.value.pending("local-1"))
        assertEquals(listOf("local-3"), stores.open("a3").outbox().map { it.localId })

        // an account that is not there: nothing opens, the current stays
        assertNull(on.resume("$a@three.example"))
        assertEquals("$a@one.example", on.accounts.current)

        // what the switcher shows
        on.describe("$b@two.example", "Ana", "ana@two.example", "Enspiral", "", false)
        assertEquals("Ana", on.accounts["$b@two.example"]!!.name)

        // signing out of the first: only its things go, the next account is the current one
        on.signOut(firstS)
        assertNull(vault.get(Onboarding.V_SESSION))
        assertTrue(stores.open("").outbox().isEmpty())
        assertEquals(listOf("local-2"), stores.open("a2").outbox().map { it.localId })
        assertEquals(listOf("local-3"), stores.open("a3").outbox().map { it.localId })
        assertEquals("$b@two.example", on.accounts.current)
        val second = on.resume()!!.also { opened.add(it) }
        assertEquals(b, second.me)
        assertNotNull(second.world.value.pending("local-2"))
    }

    @Test
    fun anAccountWithoutItsSessionLeavesTheList() = runBlocking<Unit> {
        val vault = MemoryVault()
        val stores = MemoryStores()
        val l1 = AccountList.EMPTY.signedIn("one.example", a).first
        val l2 = l1.signedIn("two.example", b).first
        vault.set(Onboarding.V_SESSION, saved("one.example", 1).toJsonString())
        // the second account's session is missing (a damaged vault entry); it is the current one
        vault.set(Onboarding.V_ACCOUNTS, l2.toJsonString())
        val on = onboarding(vault, stores)
        val s = on.resume()!!.also { opened.add(it) }
        assertEquals(a, s.me)
        assertEquals(listOf("$a@one.example"), on.accounts.accounts.map { it.id })
    }

    @Test
    fun theDemoIsAnAccountNextToARealOne() = runBlocking<Unit> {
        val vault = MemoryVault()
        val stores = MemoryStores()
        vault.set(Onboarding.V_SESSION, saved("one.example", 1).toJsonString())
        stores.open("").set("mine", "1")
        val on = onboarding(vault, stores)
        val demo = on.demo().also { opened.add(it) }
        assertTrue(demo.localOnly)
        assertEquals("a2", demo.slot)
        assertEquals(2, on.accounts.accounts.size)
        assertTrue(on.accounts.currentAccount!!.demo)
        assertEquals("1", stores.open("").get("mine"), "the real account's store is not touched")
        assertNull(on.signOut(demo))
        assertEquals("$a@one.example", on.accounts.current)
        assertEquals(a, on.resume()!!.also { opened.add(it) }.me)
    }
}
