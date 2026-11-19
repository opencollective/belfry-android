package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.kit.MarkdownWithBox
import sh.belfry.core.engine.Demo

/** SPEC 19.44 (canvas channels/link-preview-end + -inline): names inside the text, a box at the end. */
class RefsShotsTest : DemoShots() {
    @Test fun inTheChannel() {
        demoShot("refs-channel") { ChannelScreen(Demo.RANDOM, onMenu = {}) }
        assertTrue(compose.onAllNodesWithTag("ref-box").fetchSemanticsNodes().isNotEmpty())
    }

    /** One message per state: a name inside, the box, only a reference, an unknown one (bar, then "#channel"). */
    @Test fun states() {
        val d = Demo.DOMAIN
        demoShot("refs-states") {
            Column(Modifier.padding(16.dp)) {
                MarkdownWithBox("Ask https://chat.$d/#/c/${Demo.POD_OPEN} about the seeds.", d)
                MarkdownWithBox("Seed talk carries on here https://chat.$d/#/c/${Demo.GARDEN}", d, Modifier.padding(top = 16.dp))
                MarkdownWithBox("https://chat.$d/#/c/${Demo.KITCHEN}", d, Modifier.padding(top = 16.dp))
                MarkdownWithBox("Somewhere new https://chat.$d/#/c/not-here-yet", d, Modifier.padding(top = 16.dp))
            }
        }
        compose.onNodeWithText("Seed talk carries on here").assertExists()
        assertTrue(compose.onAllNodesWithTag("ref-box").fetchSemanticsNodes().size >= 3)
    }
}
