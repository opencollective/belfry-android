package sh.belfry.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.core.crypto.Keys
import sh.belfry.core.engine.AccountList
import sh.belfry.core.engine.MemoryStores
import sh.belfry.core.engine.MemoryVault
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.engine.SavedSession
import sh.belfry.core.engine.Slots
import sh.belfry.core.net.Community
import sh.belfry.core.net.InviteLink
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.push.PushOpen
import java.io.IOException
import java.time.ZoneOffset

/**
 * The app's state with several accounts (SPEC 19.24), on the plain JVM: switching, signing out of
 * one, where a guest link and a tapped notification go. No network: the made-up domains do not
 * exist and every HTTP call is refused.
 */
class AppStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { throw IOException("no network in tests") }).build()
    private val vault = MemoryVault()
    private val stores = MemoryStores()
    private val onboarding = Onboarding(vault, stores, http, scope, { 1_800_000_000L }, ZoneOffset.UTC)
    private val state = AppState(onboarding, scope)

    @After fun tearDown() { state.session.value?.stop(); scope.cancel() }

    private fun until(what: String, test: () -> Boolean) {
        repeat(400) { if (test()) return; Thread.sleep(25) }
        throw AssertionError("never: $what")
    }

    private fun key(n: Int) = LocalSigner(n.toString(16).padStart(64, '0')).pubkey
    private fun id(n: Int, domain: String) = AccountList.id(domain, key(n))
    private fun link(domain: String) = InviteLink(domain, "abcdefghijklmnopqrstuvwxyz012345")

    /** Three accounts as three sign-ins would leave them: 1 and 3 in one.example, 2 in two.example (open). */
    private fun threeAccounts() {
        var list = AccountList.EMPTY
        for ((domain, n) in listOf("one.example" to 1, "two.example" to 2, "one.example" to 3)) {
            val (l, rec, _) = list.signedIn(domain, key(n))
            list = l
            val saved = SavedSession(Community.defaults(domain), "bunker://${key(100 + n)}?relay=wss://bunker.$domain", Keys.generateSecretHex(), key(n))
            vault.set(Slots.key(Onboarding.V_SESSION, rec.slot), saved.toJsonString())
        }
        vault.set(Onboarding.V_ACCOUNTS, list.select(id(2, "two.example")).toJsonString())
    }

    @Test
    fun addingSwitchingAndSigningOut() = runBlocking<Unit> {
        state.boot()
        until("signed out") { state.phase.value is Phase.SignedOut }
        state.demo()
        val first = state.session.value!!
        until("the list shows one") { state.accounts.value.list.accounts.size == 1 }
        // add an account: the sign-in screen over the app, the open account untouched; back returns
        state.addAccount()
        assertEquals(Adding.SignIn, state.adding.value)
        assertTrue(state.phase.value is Phase.SignedIn)
        assertTrue(state.session.value === first)
        state.cancelAdd()
        assertNull(state.adding.value)
        // a second account (another demo identity): it opens, the first stays on the device
        state.addAccount()
        val second = onboarding.demo("Sam").also { it.stop() }
        onboarding.resume(first.accountId)!!.stop() // (the list's "current" back to the open one, as the app has it)
        assertEquals(2, onboarding.accounts.accounts.size)
        assertTrue(state.switchTo(second.accountId))
        assertNull(state.adding.value)
        assertEquals(second.accountId, state.session.value!!.accountId)
        assertEquals(second.accountId, onboarding.accounts.current)
        // and back
        assertTrue(state.switchTo(first.accountId))
        assertEquals(first.me, state.session.value!!.me)
        assertTrue(state.switchTo(first.accountId))
        assertTrue(!state.switchTo("nobody@nowhere.example"))
        assertEquals(first.accountId, state.session.value!!.accountId)
        // signing out leaves the other account open, not the sign-in screen
        assertNull(state.signOut())
        until("the other account is open") { (state.phase.value as? Phase.SignedIn)?.session?.accountId == second.accountId }
        assertEquals(listOf(second.accountId), onboarding.accounts.accounts.map { it.id })
        // the last one: signed out
        state.signOut()
        until("signed out") { state.phase.value is Phase.SignedOut }
        assertTrue(onboarding.accounts.accounts.isEmpty())
    }

    @Test
    fun aGuestLinkFindsItsAccount() = runBlocking<Unit> {
        threeAccounts()
        state.boot()
        until("the open account") { state.session.value?.accountId == id(2, "two.example") }

        // its own belfry, one account there: offered to the open account
        state.onInviteLink(link("two.example"))
        until("offered") { state.joinOffer.value == link("two.example") }
        state.forgetInvite()
        assertNull(state.joinOffer.value)

        // a belfry with two accounts here: ask which, then that account opens and is offered the link
        state.onInviteLink(link("one.example"))
        until("asked which") { state.chooseFor.value == link("one.example") }
        assertNull(state.joinOffer.value)
        state.chooseAccount(id(3, "one.example"))
        until("the picked account is open and offered") { state.session.value?.accountId == id(3, "one.example") && state.joinOffer.value == link("one.example") }
        assertNull(state.chooseFor.value)
        state.forgetInvite()

        // one account in that belfry, not the open one: it opens by itself
        state.onInviteLink(link("two.example"))
        until("switched and offered") { state.session.value?.accountId == id(2, "two.example") && state.joinOffer.value == link("two.example") }
        state.forgetInvite()

        // a belfry with no account here: the join flow adds one, over the app
        state.onInviteLink(link("three.example"))
        until("joining over the app") { state.adding.value == Adding.Join(link("three.example")) }
        assertTrue(state.phase.value is Phase.SignedIn)
        state.signInInstead()
        assertEquals(Adding.SignIn, state.adding.value)
        state.cancelAdd()
        assertNull(state.adding.value)
        assertEquals(id(2, "two.example"), state.session.value?.accountId)
    }

    @Test
    fun aTappedNotificationOpensItsAccount() = runBlocking<Unit> {
        threeAccounts()
        // tapped before the app started: boot opens that account
        state.onPushOpen(PushOpen.Dms, id(1, "one.example"))
        state.boot()
        until("its account") { state.session.value?.accountId == id(1, "one.example") }
        assertEquals(PushOpen.Dms, state.pushOpen.value)
        state.forgetPushOpen()
        // tapped while another account is open: switch, then open
        state.onPushOpen(PushOpen.Channel("general", null), id(3, "one.example"))
        until("switched") { state.session.value?.accountId == id(3, "one.example") && state.pushOpen.value == PushOpen.Channel("general", null) }
        state.forgetPushOpen()
        // the open account's own, or an old notification without an account: just open
        state.onPushOpen(PushOpen.Home, id(3, "one.example"))
        assertEquals(PushOpen.Home, state.pushOpen.value)
        state.onPushOpen(PushOpen.Dms)
        assertEquals(PushOpen.Dms, state.pushOpen.value)
    }
}
