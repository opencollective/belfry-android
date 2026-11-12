package sh.belfry.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import sh.belfry.app.ui.kit.SheetContent
import sh.belfry.app.ui.nav.AccountsUi
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.shell.AccountsList
import sh.belfry.app.ui.shell.ChannelDrawer
import sh.belfry.app.ui.signin.SignInContent
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.AccountList
import sh.belfry.core.engine.AccountRecord
import sh.belfry.core.engine.Demo

/** Several accounts on one device (SPEC 19.24): the switcher behind "you" in the drawer. */
class AccountsShotsTest : DemoShots() {
    private val ana = AccountRecord(
        AccountList.id("enspiral.example", Demo.maya.pubkey), "a2", "enspiral.example", Demo.maya.pubkey,
        name = "Alex Morgan", address = "alex@enspiral.example", community = "Enspiral",
    )
    private val guest = AccountRecord(
        AccountList.id(Demo.DOMAIN, Demo.tomas.pubkey), "a3", Demo.DOMAIN, Demo.tomas.pubkey,
        name = "Alex (work)", community = Demo.NAME, guest = true,
    )

    private fun ui(switchTo: suspend (String) -> Unit = {}, add: () -> Unit = {}) =
        AccountsUi(others = listOf(ana, guest), unread = setOf(ana.id), switchTo = switchTo, add = add)

    @Test fun switcher() = demoShot("accounts-switcher") {
        SheetContent(title = "Accounts") { AccountsList(ui(), {}, {}) }
    }

    @Test fun switcherAlone() = demoShot("accounts-switcher-one") {
        SheetContent(title = "Accounts") { AccountsList(AccountsUi(), {}, {}) }
    }

    @Config(qualifiers = "w800dp-h1280dp-port-240dpi")
    @Test fun switcherOnATablet() = demoShot("accounts-switcher-tablet") {
        SheetContent(title = "Accounts") { AccountsList(ui(), {}, {}) }
    }

    /** The drawer's bottom shows a dot when another account has something new. */
    @Test fun drawerWithAnotherAccountUnread() = demoShot("drawer-accounts-dot") {
        CompositionLocalProvider(LocalAccounts provides ui()) { ChannelDrawer(Demo.GENERAL, onPick = {}, go = {}) }
    }

    @Test fun addAccount() = shot("signin-add-account") {
        SignInContent("", {}, null, false, false, {}, {}, {}, onScan = {}, onBack = {})
    }

    /** "You" opens the list; a row switches (every row locks meanwhile); Add and Me do what they say. */
    @Test fun theListSwitchesAddsAndOpensMe() {
        val asked = ArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        var added = 0
        val went = ArrayList<String>()
        compose.setContent {
            BelfryTheme {
                InDemo {
                    CompositionLocalProvider(LocalAccounts provides ui(switchTo = { asked.add(it); gate.await() }, add = { added++ })) {
                        ChannelDrawer(Demo.GENERAL, onPick = {}, go = { went.add(it) })
                    }
                }
            }
        }
        compose.onNodeWithTag("drawer-me").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Accounts").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithTag("account-other").fetchSemanticsNodes().size)
        // switch: asked once, and a second tap while it works does nothing
        compose.onAllNodesWithTag("account-other")[0].performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("account-other")[1].performClick()
        compose.onNodeWithTag("account-add").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ana.id), asked)
        assertEquals(0, added)
        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithTag("account-add").performClick()
        compose.waitForIdle()
        assertEquals(1, added)
        // the sheet closed; open it again for Me
        compose.onNodeWithTag("drawer-me").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("account-me").performClick()
        compose.waitForIdle()
        assertTrue(went.toString(), Routes.ME in went)
    }
}
