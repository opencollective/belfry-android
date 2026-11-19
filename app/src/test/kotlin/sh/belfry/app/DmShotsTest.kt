package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.core.engine.Demo

/** canvas home/conversation (2026-11-19): the person's face at the top, reactions under bubbles. */
class DmShotsTest : DemoShots() {
    private val conv get() = "n:" + Demo.maya.pubkey

    /** Maya's 🙏 under my message, my 👍 under hers; the header opens her profile. */
    @Test fun reactionsAndFace() {
        demoShot("conversation-reactions") { ConversationScreen(conv) }
        assertEquals(2, compose.onAllNodesWithTag("dm-chip").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("👍 1, from you").assertExists()
        compose.onNodeWithContentDescription("🙏 1, from Maya Okafor").assertExists()
    }

    /** A long-press opens the menu with the reactions row. */
    @Test fun longPressMenu() {
        demoScreenShot("conversation-menu", act = {
            compose.onAllNodesWithTag("dm-bubble").onFirst().performTouchInput { longClick() }
        }) { ConversationScreen(conv) }
        compose.onNodeWithContentDescription("More emojis").assertExists()
    }

    /** Tapping my chip takes my reaction back. */
    @Test fun tapTakesMineBack() {
        demoShot("conversation-reaction-taken-back", act = {
            compose.onNodeWithContentDescription("👍 1, from you").performClick()
            compose.waitUntil(5_000) { session.dms.value.thread(conv).all { d -> session.dms.value.reactionsOf(d.rid).none { it.mine != null } } }
        }) { ConversationScreen(conv) }
        assertTrue(session.dms.value.thread(conv).all { d -> session.dms.value.reactionsOf(d.rid).none { it.emoji == "👍" } })
    }
}
