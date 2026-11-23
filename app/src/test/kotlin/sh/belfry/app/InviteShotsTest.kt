package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.Test
import sh.belfry.app.ui.info.InviteScreen
import sh.belfry.core.engine.Demo

/** The invite sheet as on the web (inviting/sheet-from-channel, sheet-external, sheet-member, sheet-link). From Members: InviteSheetTest. */
class InviteShotsTest : DemoShots() {
    /** From a channel: people already in the community first, the link folded (none made yet). */
    @Test fun fromChannel() {
        demoShot("invite-sheet") { InviteScreen(Demo.GARDEN) }
        compose.onAllNodesWithTag("link-copy").fetchSemanticsNodes().let { check(it.isEmpty()) { "no link before the external part is opened" } }
    }

    @Test fun external() = demoShot("invite-sheet-external", act = {
        compose.onNodeWithTag("invite-external").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("invite-line").fetchSemanticsNodes().isNotEmpty() }
    }) { InviteScreen(Demo.GARDEN) }

    /** inviting/sheet-external-edit: Edit opens the options in place. */
    @Test fun externalEdit() = demoShot("invite-sheet-external-edit", act = {
        compose.onNodeWithTag("invite-external").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("https://", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("invite-edit").performClick()
    }) { InviteScreen(Demo.GARDEN) }

    @Test fun joinsAs() = demoShot("invite-sheet-member", act = {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("https://", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("invite-edit").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("invite-joins").performClick()
    }) { InviteScreen(Demo.GARDEN, startAsGuest = true) }

    @Test fun link() = demoShot("invite-sheet-link", act = {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("https://", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("invite-edit").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("invite-link").performClick()
    }) { InviteScreen(Demo.GARDEN, startAsGuest = true) }
}
