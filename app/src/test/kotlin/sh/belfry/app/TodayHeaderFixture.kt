package sh.belfry.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import sh.belfry.app.ui.nav.AccountsUi
import sh.belfry.core.crypto.Keys
import sh.belfry.core.engine.AccountList
import sh.belfry.core.engine.AccountRecord
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.MemoryStores
import sh.belfry.core.engine.MemoryVault
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.engine.SavedSession
import sh.belfry.core.engine.Session
import sh.belfry.core.engine.Slots
import sh.belfry.core.net.Community
import java.io.IOException
import java.time.ZoneOffset
import java.util.Collections

/** The accounts of the Home canvas's boards (home/today-belfry-tap): Hillside Makers as a member, a guest account in the demo belfry. */
object TodayFixture {
    val hillside = AccountRecord(
        AccountList.id("hillsidemakers.example", Demo.sam.pubkey), "a2", "hillsidemakers.example", Demo.sam.pubkey,
        name = "Sam Carter", address = "sam@hillsidemakers.example", community = "Hillside Makers", role = "Member",
    )
    val guest = AccountRecord(
        AccountList.id(Demo.DOMAIN, Demo.tomas.pubkey), "a3", Demo.DOMAIN, Demo.tomas.pubkey,
        name = "Alex (repair café)", address = "repaircafe@${Demo.DOMAIN}", community = Demo.NAME, guest = true, role = "Guest",
    )

    fun ui(
        unread: Set<String> = setOf(hillside.id),
        switchTo: suspend (String) -> Unit = {},
        add: () -> Unit = {},
    ) = AccountsUi(others = listOf(hillside, guest), unread = unread, switchTo = switchTo, add = add)
}

/**
 * A whole app (AppState + Onboarding) with two demo accounts, the first one open, and a third
 * account in another belfry (hillside.example) that is never opened. Every HTTP call is refused
 * and its host written down in [asked], so a test can say nothing was asked of that belfry.
 */
class TwoAccountsApp(scope: CoroutineScope) {
    val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { asked.add(it.request().url.host); throw IOException("no network in tests") }).build()
    private val vault = MemoryVault()
    val onboarding = Onboarding(vault, MemoryStores(), http, scope, { DemoShots.NOW }, ZoneOffset.UTC).also { it.placeNames = true }
    val state = AppState(onboarding, scope)
    lateinit var first: Session
    lateinit var second: Session
    lateinit var otherBelfry: String

    fun start() = runBlocking {
        state.boot()
        until("signed out") { state.phase.value is Phase.SignedOut }
        state.demo()
        first = state.session.value!!
        second = onboarding.demo("Sam").also { it.stop() }
        onboarding.resume(first.accountId)!!.stop() // the list's "current" back to the open one
        // an account in another belfry, as a sign-in leaves it (never opened here)
        val key = Keys.generateSecretHex().let { sh.belfry.core.nostr.LocalSigner(it).pubkey }
        val (list, rec, _) = onboarding.accounts.signedIn("hillside.example", key)
        val saved = SavedSession(Community.defaults("hillside.example"), "bunker://${key}?relay=wss://bunker.hillside.example", Keys.generateSecretHex(), key)
        vault.set(Slots.key(Onboarding.V_SESSION, rec.slot), saved.toJsonString())
        vault.set(Onboarding.V_ACCOUNTS, list.select(first.accountId).toJsonString())
        otherBelfry = rec.id
        state.refreshAccounts()
        until("the list shows three") { state.accounts.value.list.accounts.size == 3 }
    }

    fun stop() { state.session.value?.stop() }

    fun until(what: String, test: () -> Boolean) {
        repeat(400) { if (test()) return; Thread.sleep(25) }
        throw AssertionError("never: $what")
    }
}
