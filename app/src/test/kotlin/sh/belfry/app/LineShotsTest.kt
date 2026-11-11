package sh.belfry.app

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.shell.HomeScreen
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.LineIcon
import sh.belfry.core.model.channelLine
import sh.belfry.core.model.isPinned
import sh.belfry.core.model.pinned
import sh.belfry.core.model.todosBySource

/** The one line under a channel's name, its pull-down, pinned messages, to-dos and pod shapes (demo mode). */
class LineShotsTest : DemoShots() {
    private val general = Demo.GENERAL

    /** What needs me first: a vote I have not cast. */
    @Test fun lineVote() = demoShot("line-vote") { ChannelScreen(general, onMenu = {}) }

    /** A pinned notice; a message with its to-do mark. */
    @Test fun linePin() = demoShot("line-pin") { ChannelScreen(Demo.GARDEN, onMenu = {}) }

    /** Nothing special: people active, and the to-dos count. */
    @Test fun linePeople() = demoShot("line-people") { ChannelScreen(Demo.KITCHEN, onMenu = {}) }

    /** A pod I administer with someone asking to join. */
    @Test fun linePodReview() = demoShot("line-pod-review") { ChannelScreen(Demo.POD_CLOSED, onMenu = {}) }

    @Test fun linePod() = demoShot("line-pod") { ChannelScreen(Demo.POD_OPEN, onMenu = {}) }

    @Test fun lineProposal() {
        val id = world.groups.first { it.isProposal }.id
        demoShot("line-proposal") { ChannelScreen(id, onMenu = {}) }
    }

    @Test fun lineTopic() {
        val chat = ChatState(general).apply { topic = "garden" }
        demoShot("line-topic") { ChannelScreen(general, onMenu = {}, chat = chat) }
    }

    @Test fun pullDown() {
        val chat = ChatState(general).apply { lineOpen = true }
        demoShot("pulldown") { ChannelScreen(general, onMenu = {}, chat = chat) }
    }

    @Test fun pullDownGarden() {
        val chat = ChatState(Demo.GARDEN).apply { lineOpen = true }
        demoShot("pulldown-garden") { ChannelScreen(Demo.GARDEN, onMenu = {}, chat = chat) }
    }

    @Test fun menuWithPinAndTodo() {
        val m = world.messages(Demo.KITCHEN).last { it.pubkey != session.me && it.text.isNotEmpty() }
        val chat = ChatState(Demo.KITCHEN).apply { menu(m) }
        demoScreenShot("message-menu-pin") { ChannelScreen(Demo.KITCHEN, onMenu = {}, chat = chat) }
    }

    @Test fun plusWithTodo() {
        val chat = ChatState(general).apply { plusOpen = true }
        demoScreenShot("composer-plus-todo") { ChannelScreen(general, onMenu = {}, chat = chat) }
    }

    @Test fun newTodo() {
        val chat = ChatState(general).apply { newTodo = true }
        demoScreenShot("todo-new") { ChannelScreen(general, onMenu = {}, chat = chat) }
    }

    @Test fun drawerWithPodShapes() {
        val shell = ShellState(general)
        demoShot("drawer-pods") { HomeScreen(shell, drawerOpen = true) }
    }

    @Test fun seedHasWhatTheLineNeeds() {
        assertEquals(LineIcon.PROPOSAL, world.channelLine(general, NOW).icon)
        assertEquals("Vote", world.channelLine(general, NOW).pill)
        assertEquals(LineIcon.PIN, world.channelLine(Demo.GARDEN, NOW).icon)
        assertEquals(LineIcon.PEOPLE, world.channelLine(Demo.KITCHEN, NOW).icon)
        assertEquals("Review", world.channelLine(Demo.POD_CLOSED, NOW).pill)
        assertTrue(world.todosBySource(Demo.GARDEN).isNotEmpty())
    }

    @Test
    fun pinningShowsThePinAndTheLine() {
        started()
        val text = "Dinner moves to Friday this week."
        val mine = myMessage(text, Demo.KITCHEN)
        val chat = ChatState(Demo.KITCHEN).apply { menu(mine) }
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(Demo.KITCHEN, chat = chat) } } }
        compose.onNodeWithTag("menu-pin").performClick()
        compose.waitUntil(10_000) { world.isPinned(Demo.KITCHEN, mine.id) && chat.acting == null }
        assertEquals(listOf(mine.id), world.pinned(Demo.KITCHEN).map { it.id })
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Pinned").fetchSemanticsNodes().isNotEmpty() }
        // the message itself and the line under the channel's name
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().size >= 2 }
    }

    @Test
    fun tickingAToDoFromItsMessageMarksItDoneOnce() {
        started()
        val (source, task) = world.todosBySource(Demo.GARDEN).entries.first().let { it.key to it.value }
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(Demo.GARDEN) } } }
        val chip = compose.onNodeWithTag("todo-" + task.id)
        // (The test harness lets the first tap finish before a second one, so the lock against a
        // double tap is BusyState's, checked in ShellBehaviourTest; here: one tap, one tick.)
        chip.performClick()
        compose.waitUntil(10_000) { world.todosBySource(Demo.GARDEN)[source]?.done == true }
        settle()
        val ticks = world.all.count { it.kind == 2101 && it.tag("e") == task.id }
        assertEquals("the to-do was ticked more than once", 1, ticks)
        // the mark before the reactions (proposal A) says so
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("To-do, done", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
