package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.core.engine.Demo

/** canvas v109 messages/attach-*: pictures, a file and a recording in a direct message. */
class DmFilesShotsTest : DemoShots() {
    private val conv get() = "n:" + Demo.lena.pubkey

    /** Three pictures sent together (2 × 2, no bubble) with their caption, Lena's PDF and her recording. */
    @Test fun thread() {
        demoShot("dm-files-thread", act = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dm-picture-grid", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        }) { ConversationScreen(conv) }
        compose.onNodeWithTag("dm-file", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Garden plan 2027.pdf", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("The seedlings came up!").assertExists()
        compose.onNodeWithTag("dm-recording", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("0:07", useUnmergedTree = true).assertExists()
    }

    /** The ＋: Photos, File, Audio recording. */
    @Test fun plus() {
        demoScreenShot("dm-files-plus", act = {
            compose.onNodeWithContentDescription("Add to the conversation").performClick()
        }) { ConversationScreen(conv) }
        compose.onNodeWithText("Pictures from this device").assertExists()
        compose.onNodeWithText("A document or a video").assertExists()
        compose.onNodeWithTag("dm-plus-record").assertExists()
    }

    /** The conversation list reads the last line of a file message. */
    @Test fun listLine() {
        demoShot("dm-files-list") { MessagesScreen() }
        compose.onNodeWithText("Audio recording · 0:07", substring = true).assertExists()
    }
}
