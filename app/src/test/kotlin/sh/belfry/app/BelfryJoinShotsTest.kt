package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.nav.Routes
import sh.belfry.core.engine.Demo

/** one-shape/townsquare-join (canvas v183, SPEC 19.59): someone joins the belfry, a line in Townsquare. Demo: Rosa and Maya's agent Pip. */
class BelfryJoinShotsTest : DemoShots() {
    @Test fun townsquareLines() {
        demoShot("townsquare-join") { ChannelScreen(Demo.GENERAL, onMenu = {}) }
        compose.onNodeWithText("Rosa Lindgren joined Riverside Commons", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Pip, Maya Okafor's agent, joined Riverside Commons", useUnmergedTree = true).assertExists()
        assertEquals(2, compose.onAllNodesWithTag("belfry-join", useUnmergedTree = true).fetchSemanticsNodes().size)
        // the line opens the person's profile
        compose.onNodeWithText("Rosa Lindgren joined Riverside Commons", useUnmergedTree = true).performClick()
        assertTrue(nav.went.toString(), Routes.profile(Demo.rosa.pubkey) in nav.went)
    }
}
