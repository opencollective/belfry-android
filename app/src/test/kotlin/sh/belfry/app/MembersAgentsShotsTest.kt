package sh.belfry.app

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.people.MembersScreen
import sh.belfry.app.ui.people.ProfileScreen
import sh.belfry.core.engine.Demo

/** canvas v93 (Xavier 2026-11-20): a member's agents fold under the member. Demo: Kofi and his agent Wren. */
class MembersAgentsShotsTest : DemoShots() {
    @Test fun folded() {
        demoShot("members-agents", act = {
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("members-search"))).performTextInput("kofi")
            compose.waitForIdle()
        }) { MembersScreen() }
        compose.onNodeWithText("+ 1 agent", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Wren", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun searchByAgent() {
        demoShot("members-search-agent", act = {
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("members-search"))).performTextInput("wre")
            compose.waitForIdle()
        }) { MembersScreen() }
        compose.onNodeWithTag("member-agent-match", useUnmergedTree = true).assertExists()
        assertTrue(compose.onAllNodesWithTag("member-row").fetchSemanticsNodes().size == 1)
    }

    @Test fun profileAgents() {
        demoShot("profile-agents") { ProfileScreen(Demo.kofi.pubkey) }
        compose.onNodeWithTag("profile-agents").assertExists()
        compose.onNodeWithText("Wren", useUnmergedTree = true).assertExists()
    }
}
