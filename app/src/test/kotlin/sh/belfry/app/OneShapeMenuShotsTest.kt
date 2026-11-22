package sh.belfry.app

import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.DmThreadScreen
import sh.belfry.core.engine.Demo

/** One hold menu everywhere, DM replies as threads (docs/one-shape.md 3, canvas one-shape/dm-actions, dm-thread). */
class OneShapeMenuShotsTest : DemoShots() {
    private val conv get() = "n:" + Demo.maya.pubkey
    private val saturday get() = session.dms.value.inline(conv).first { it.text.startsWith("Can you open the workshop") }

    /** A channel message: reactions, Reply · Make a to-do, Copy text · Copy link · Pin, then the others. */
    @Test fun channelMenu() {
        val m = world.messages(Demo.KITCHEN).last { it.pubkey != session.me && it.text.isNotEmpty() }
        val chat = ChatState(Demo.KITCHEN).apply { menu(m) }
        demoScreenShot("oneshape-menu-channel") { ChannelScreen(Demo.KITCHEN, onMenu = {}, chat = chat) }
        compose.onNodeWithTag("menu-reply").assertExists()
        compose.onNodeWithText("Start a thread").assertDoesNotExist()
        compose.onNodeWithText("Make a to-do").assertExists()
        compose.onNodeWithText("Copy link").assertExists()
    }

    /** A direct message: reactions, Reply, Copy text (no Make a to-do, no Pin, no link for a DM yet). */
    @Test fun dmMenu() {
        demoScreenShot("oneshape-menu-dm", act = {
            compose.onAllNodesWithTag("dm-bubble").onFirst().performTouchInput { longClick() }
        }) { ConversationScreen(conv) }
        compose.onNodeWithTag("dm-menu-reply").assertExists()
        compose.onNodeWithText("Copy text").assertExists()
        compose.onNodeWithText("Make a to-do").assertDoesNotExist()
        compose.onNodeWithText("Copy link").assertDoesNotExist()
    }

    /** The conversation shows "2 replies" under Maya's message; the replies are not in line. */
    @Test fun conversationWithReplies() {
        demoShot("oneshape-dm-replies") { ConversationScreen(conv) }
        compose.onNodeWithText("2 replies").assertExists()
        compose.onNodeWithText("Yes, from 10. They bring their own stands.").assertDoesNotExist()
    }

    /** The thread: the message on top, its two replies, "Reply in the thread". */
    @Test fun dmThread() {
        val root = saturday.rid
        demoShot("oneshape-dm-thread") { DmThreadScreen(conv, root) }
        compose.onNodeWithTag("dm-thread-title").assertExists()
        compose.onNodeWithText("Yes, from 10. They bring their own stands.").assertExists()
        assertTrue(compose.onAllNodesWithText("2 replies").fetchSemanticsNodes().isNotEmpty())
    }

    /** A reply goes out through the DM send path with the root tag, and shows in the thread. */
    @Test fun replySends() {
        val root = saturday.rid
        runBlocking { session.sendDm(conv, "I'll bring the reel.", root = root) }
        val dms = session.dms.value
        assertEquals("I'll bring the reel.", dms.replies(conv, root).last().text)
        assertTrue(dms.inline(conv).none { it.text == "I'll bring the reel." })
        assertTrue(dms.thread(conv).any { it.text == "I'll bring the reel." && it.root == root })
    }
}
