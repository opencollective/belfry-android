package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Test
import sh.belfry.app.ui.info.InviteScreen
import sh.belfry.core.engine.Demo

/** The invite sheet as on the web (inviting/sheet-from-channel, sheet-external, sheet-member, sheet-link, sheet-from-members). */
class InviteShotsTest : DemoShots() {
    /** From a channel: people already in the community first, the link folded (none made yet). */
    @Test fun fromChannel() {
        demoShot("invite-sheet") { InviteScreen(Demo.GARDEN) }
        compose.onAllNodesWithTag("link-copy").fetchSemanticsNodes().let { check(it.isEmpty()) { "no link before the external part is opened" } }
    }

    @Test fun external() = demoShot("invite-sheet-external", act = {
        compose.onNodeWithTag("invite-external").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("invite-joins").fetchSemanticsNodes().isNotEmpty() }
    }) { InviteScreen(Demo.GARDEN) }

    @Test fun joinsAs() = demoShot("invite-sheet-member", act = {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("invite-joins").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("invite-joins").performClick()
    }) { InviteScreen(Demo.GARDEN, startAsGuest = true) }

    @Test fun link() = demoShot("invite-sheet-link", act = {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("invite-link").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("invite-link").performClick()
    }) { InviteScreen(Demo.GARDEN, startAsGuest = true) }

    /** From Members: "Invite to Riverside Commons", the link at once, no search by name. */
    @Test fun fromMembers() = demoShot("invite-sheet-from-members") { InviteScreen("general", toCommunity = true) }
}
