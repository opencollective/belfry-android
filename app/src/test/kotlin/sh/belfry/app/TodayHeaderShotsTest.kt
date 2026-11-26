package sh.belfry.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import sh.belfry.app.ui.AppRoot
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.shell.HomeScreen
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.core.engine.Demo

/**
 * Pictures of the Home canvas's boards (SPEC 19.90): home/today-belfry (light; -dark is
 * today-belfry-dark.png), home/today-belfry-tap, switch-1-drawer, switch-2-accounts, switch-3-today.
 * Run by hand: `gh workflow run ci.yml --ref <branch> -f shots='*TodayHeaderShotsTest*'`.
 */
class TodayHeaderShotsTest : DemoShots() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var app: TwoAccountsApp? = null

    @After fun tearDown() { app?.stop(); scope.cancel() }

    private fun shell(place: ShellState.Place = ShellState.Place.HOME, channel: String = Demo.GENERAL) =
        ShellState(channel).apply { this.place = place }

    /** home/today-belfry and home/today-belfry-dark. */
    @Test fun todayBelfry() = demoShot("today-belfry") {
        CompositionLocalProvider(LocalAccounts provides TodayFixture.ui()) { HomeScreen(shell()) }
    }

    /** home/today-belfry-tap: the menu dropped from the header. */
    @Test fun todayBelfryTap() = demoScreenShot("today-belfry-tap", act = { compose.onNodeWithTag("today-header").performClick() }) {
        CompositionLocalProvider(LocalAccounts provides TodayFixture.ui()) { HomeScreen(shell()) }
    }

    /** home/switch-1-drawer: in #Garden, ☰ opened the drawer; ⇅ is next to you at the bottom. */
    @Test fun switchDrawer() = demoShot("today-switch-1-drawer") {
        CompositionLocalProvider(LocalAccounts provides TodayFixture.ui()) { HomeScreen(shell(ShellState.Place.TOWN, Demo.GARDEN), drawerOpen = true) }
    }

    /** home/switch-2-accounts: ⇅ opened Accounts; the tapped row locks with a spinner. */
    @Test fun switchAccounts() {
        val never = CompletableDeferred<Unit>()
        demoScreenShot("today-switch-2-accounts", act = {
            compose.onNodeWithTag("drawer-switch").performClick()
            compose.waitForIdle()
            compose.onAllNodesWithTag("account-other")[0].performClick()
        }) {
            CompositionLocalProvider(LocalAccounts provides TodayFixture.ui(switchTo = { never.await() })) {
                HomeScreen(shell(ShellState.Place.TOWN, Demo.GARDEN), drawerOpen = true)
            }
        }
    }

    /** home/switch-3-today: after the switch (from Calendar), the other account's Today, its name and address at once. */
    @Test fun switchToday() {
        val a = TwoAccountsApp(scope).also { app = it }
        a.start()
        shot("today-switch-3-today", act = {
            compose.onNodeWithTag("place-calendar").performClick()
            compose.waitForIdle()
            kotlinx.coroutines.runBlocking { a.state.switchTo(a.second.accountId) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-header").fetchSemanticsNodes().isNotEmpty() }
        }) {
            CompositionLocalProvider(LocalNetworkImages provides false) { AppRoot(a.state) }
        }
    }
}
