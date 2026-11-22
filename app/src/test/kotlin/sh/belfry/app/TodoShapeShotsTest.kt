package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.info.DueSheet
import sh.belfry.app.ui.info.TodoLevel
import sh.belfry.app.ui.info.TodoScreen
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Task

/** The to-do in one shape (canvas one-shape/todo-conversation, -mid, -pulled, -done; SPEC 19.53). */
class TodoShapeShotsTest : DemoShots() {
    private fun task(group: String, title: String): Task = world.tasks(group).first { it.title == title }
    private val cable get() = task(Demo.KITCHEN, "Bring the extension cable")

    /** Folded (tiny): the two-line header, then the conversation with its quiet lines. */
    @Test fun conversation() {
        demoShot("todo-conversation") { TodoScreen(Demo.KITCHEN, cable.id, startLevel = TodoLevel.Tiny) }
        compose.onNodeWithTag("todo-mid").assertDoesNotExist()
        compose.onNodeWithTag("todo-line-due").assertExists()
    }

    /** On me and open: it opens mid (due date, two lines of the description, More). */
    @Test fun mid() {
        demoShot("todo-mid") { TodoScreen(Demo.KITCHEN, cable.id) }
        compose.onNodeWithTag("todo-mid").assertExists()
        compose.onNodeWithTag("todo-due").assertExists()
        compose.onNodeWithTag("todo-more").assertExists()
    }

    /** Pulled down: the to-do itself on top (due, description, the message it came from, its rows). */
    @Test fun pulled() {
        demoShot("todo-pulled") { TodoScreen(Demo.KITCHEN, cable.id, startLevel = TodoLevel.Full) }
        compose.onNodeWithTag("todo-full").assertExists()
        compose.onNodeWithTag("todo-source").assertExists()
    }

    /** More goes all the way down. */
    @Test fun moreOpensFull() {
        compose.setContent { BelfryTheme { InDemo { TodoScreen(Demo.KITCHEN, cable.id) } } }
        compose.onNodeWithTag("todo-more").performClick()
        compose.onNodeWithTag("todo-full").assertExists()
    }

    /** Done (by Sam): the tick filled with a check, the title struck, "Done <day>" in the row; it opens tiny. */
    @Test fun done() {
        val rice = task(Demo.KITCHEN, "Buy rice and lentils")
        assertTrue(rice.done)
        demoShot("todo-done") { TodoScreen(Demo.KITCHEN, rice.id) }
        compose.onNodeWithTag("todo-mid").assertDoesNotExist()
        compose.onNodeWithTag("todo-line-done").assertExists()
        compose.onNodeWithTag("row-item-lead").assertExists()
    }

    /** Nobody on it: "Unassigned" in grey; it opens mid (it needs me). */
    @Test fun unassigned() {
        val compost = task(Demo.GARDEN, "Empty the left compost bin")
        demoShot("todo-unassigned") { TodoScreen(Demo.GARDEN, compost.id) }
        compose.onNodeWithTag("todo-mid").assertExists()
    }

    /** The due date sheet: a real day and time (Material pickers), Save, Remove. */
    @Test fun dueSheet() {
        demoScreenShot("todo-due-sheet") { TodoScreen(Demo.KITCHEN, cable.id, startDueSheet = true) }
        compose.onNodeWithTag("due-save").assertExists()
        compose.onNodeWithTag("due-remove").assertExists()
    }

    /** Tapping the tick marks it done quietly: a line in the feed, then a second tap reopens it. */
    @Test fun tickDoneAndReopen() {
        started()
        val id = cable.id
        compose.setContent { BelfryTheme { InDemo { TodoScreen(Demo.KITCHEN, id) } } }
        compose.onNodeWithTag("todo-tick-$id").performClick()
        compose.waitUntil(10_000) { settle(); world.tasks(Demo.KITCHEN).first { it.id == id }.done }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("todo-line-done").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("todo-tick-$id").performClick()
        compose.waitUntil(10_000) { settle(); !world.tasks(Demo.KITCHEN).first { it.id == id }.done }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("todo-line-undone").fetchSemanticsNodes().isNotEmpty() }
    }

    /** The author sets a due date: a 1010 that keeps the to-do's tags, read back as its due, with a feed line. */
    @Test fun authorSetsDue() {
        started()
        runBlocking { session.send(Actions.addTask(Demo.KITCHEN, "Water the herbs")) }
        var mine: Task? = null
        repeat(200) {
            settle()
            val w = world
            val t = w.tasks(Demo.KITCHEN).firstOrNull { it.title == "Water the herbs" }
            if (t != null && !w.isPending(t.id)) { mine = t; return@repeat }
            Thread.sleep(25)
        }
        val t = mine ?: error("the to-do never left the demo outbox")
        compose.setContent { BelfryTheme { InDemo { DueSheet(Demo.KITCHEN, t, onDone = {}) } } }
        compose.onNodeWithTag("due-save").performClick()
        compose.waitUntil(10_000) { settle(); world.tasks(Demo.KITCHEN).first { it.id == t.id }.due != null }
        // tomorrow (Wed 11 Nov) at 9 AM, the sheet's default
        assertEquals(java.time.Instant.parse("2026-11-11T09:00:00Z").epochSecond, world.tasks(Demo.KITCHEN).first { it.id == t.id }.due)
    }
}
