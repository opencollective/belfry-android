package sh.belfry.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.AppRoot
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.places.HomePlace
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.TodayHeader

/**
 * Today's header and its accounts menu (SPEC 19.90; Home canvas home/today-belfry-tap,
 * switch-1-drawer … switch-3-today): what the menu lists, what its rows do, where a switch lands,
 * and that the news mark comes only from what this phone already knows.
 */
class TodayHeaderTest : DemoShots() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var app: TwoAccountsApp? = null

    @After fun tearDown() { app?.stop(); scope.cancel() }

    private fun today(ui: sh.belfry.app.ui.nav.AccountsUi) {
        compose.setContent {
            BelfryTheme { InDemo { CompositionLocalProvider(LocalAccounts provides ui) { HomePlace(onMenu = {}) } } }
        }
        compose.waitForIdle()
    }

    @Test fun theHeaderNamesTheBelfryAndMyAddress() {
        today(TodayFixture.ui())
        compose.onNodeWithTag("today-header-name").assertTextEquals(Demo.NAME)
        compose.onNodeWithTag("today-header-address").assertTextEquals(world.myAddress!!)
        // the date is the content's first line, in the board's words
        compose.onNodeWithTag("home-date").assertTextEquals(TodayHeader.date(DemoShots.NOW, world.ctx.zone))
        assertEquals("Tuesday, November 10", TodayHeader.date(DemoShots.NOW, world.ctx.zone))
    }

    @Test fun theMenuListsEveryAccountWithTheOpenOneChecked() {
        today(TodayFixture.ui())
        compose.onNodeWithTag("today-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("today-accounts-menu").assertIsDisplayed()
        // the open one: first, checked, "address · role"
        compose.onNode(hasTestTag("today-account-check") and hasAnyAncestor(hasTestTag("today-account-current"))).assertExists()
        assertEquals(2, compose.onAllNodesWithTag("today-account-other").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("today-account-check").fetchSemanticsNodes().size)
        compose.onNodeWithTag("today-account-add").assertExists()
        compose.onNodeWithTag("today-accounts").assertExists()
        assertEquals("sam@hillsidemakers.example · Member", TodayFixture.hillside.menuLine)
        assertEquals("repaircafe@${Demo.DOMAIN} · Guest", TodayFixture.guest.menuLine)
    }

    @Test fun aRowSwitchesOnceAndLocksTheOthers() {
        val asked = ArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        var added = 0
        today(TodayFixture.ui(switchTo = { asked.add(it); gate.await() }, add = { added++ }))
        compose.onNodeWithTag("today-header").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("today-account-other")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("today-account-spinner").assertExists()
        // every row is locked meanwhile
        compose.onAllNodesWithTag("today-account-other")[1].performClick()
        compose.onNodeWithTag("today-account-add").performClick()
        compose.waitForIdle()
        assertEquals(listOf(TodayFixture.hillside.id), asked)
        assertEquals(0, added)
        gate.complete(Unit)
    }

    @Test fun addAnAccountOpensTheSignIn() {
        var added = 0
        today(TodayFixture.ui(add = { added++ }))
        compose.onNodeWithTag("today-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("today-account-add").performClick()
        compose.waitForIdle()
        assertEquals(1, added)
        // the menu closed
        assertEquals(0, compose.onAllNodesWithTag("today-accounts-menu").fetchSemanticsNodes().size)
    }

    @Test fun accountsOpensTheAccountsList() {
        today(TodayFixture.ui())
        compose.onNodeWithTag("today-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("today-accounts").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("account-add").assertExists()
        compose.onNodeWithTag("account-me").assertExists()
    }

    @Test fun theNewsMarkFollowsOnlyWhatThePhoneKnows() {
        today(TodayFixture.ui(unread = emptySet()))
        compose.onNodeWithTag("today-header").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("today-account-news").fetchSemanticsNodes().size)
    }

    @Test fun theNewsMarkIsOnTheAccountThePhoneKnowsHasNews() {
        today(TodayFixture.ui(unread = setOf(TodayFixture.hillside.id)))
        compose.onNodeWithTag("today-header").performClick()
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithTag("today-account-news").fetchSemanticsNodes().size)
        compose.onNode(hasTestTag("today-account-news") and hasAnyAncestor(hasTestTag("today-account-other"))).assertExists()
    }

    // ---- the whole app: two demo accounts and one in another belfry --------------------------------

    private fun wholeApp(): TwoAccountsApp {
        val a = TwoAccountsApp(scope).also { app = it }
        a.start()
        compose.setContent { BelfryTheme { CompositionLocalProvider(LocalNetworkImages provides false) { AppRoot(a.state) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-header").fetchSemanticsNodes().isNotEmpty() }
        return a
    }

    private fun headerAddress(): String =
        compose.onNodeWithTag("today-header-address").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")

    /** home/switch-1 … switch-3: switching from another place (here Calendar) lands on that belfry's Today. */
    @Test fun aSwitchLandsOnToday() {
        val a = wholeApp()
        compose.onNodeWithTag("place-calendar").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("today-header").fetchSemanticsNodes().size)
        // what the drawer's switcher does (AccountsList → AccountsUi.switchTo → AppState.switchTo)
        kotlinx.coroutines.runBlocking { assertTrue(a.state.switchTo(a.second.accountId)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home").assertExists()
        val sam = a.state.session.value!!
        assertEquals(a.second.accountId, sam.accountId)
        compose.waitUntil(10_000) { headerAddress() == (sam.world.value.myAddress ?: sam.community.domain) }
    }

    /** home/today-belfry-tap: a row of the menu switches, and the app shows that account's Today. */
    @Test fun theMenuSwitchesToThatAccountsToday() {
        val a = wholeApp()
        val before = headerAddress()
        compose.onNodeWithTag("today-header").performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-account-other").fetchSemanticsNodes().size == 2 }
        // the demo account "Sam" is the first other one (the list's order)
        compose.onAllNodesWithTag("today-account-other")[0].performClick()
        a.until("switched") { a.state.session.value?.accountId == a.second.accountId }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("today-header").fetchSemanticsNodes().isNotEmpty() && headerAddress() != before
        }
        compose.onNodeWithTag("home").assertExists()
    }

    /** No polling: Today and its menu ask nothing of a belfry whose account is not open; the mark is the push code's set. */
    @Test fun theMenuAsksNothingOfTheOtherBelfries() {
        val a = wholeApp()
        a.state.unreadAccounts = { setOf(a.otherBelfry) }
        a.state.refreshAccounts()
        a.until("the mark is known") { a.otherBelfry in a.state.accounts.value.unread }
        compose.onNodeWithTag("today-header").performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-account-news").fetchSemanticsNodes().size == 1 }
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue(a.asked.toString(), a.asked.none { it.endsWith("hillside.example") })
    }
}
