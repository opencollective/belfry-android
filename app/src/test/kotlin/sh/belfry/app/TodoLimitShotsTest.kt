package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.NewTodoContent
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.core.engine.Demo

/** canvas todos/new-todo-limit (2026-11-19): 300 characters, never cut; "n of 300" from 250. */
class TodoLimitShotsTest : DemoShots() {
    private val board = "Mend the greenhouse door: the bottom hinge is loose and the frame rubs on the right. " +
        "Take the long screws from the tool library, plane the frame where it rubs, and check that it closes on its own. " +
        "If the hinge is cracked, buy two new ones at the hardware shop on Mill Street and keep the receipt for Ana."

    /** Over: the extra tinted, the line under it, Add off; the text stays as typed. */
    @Test fun over() {
        val text = board
        demoScreenShot("todo-limit-over") {
            ChannelScreen(Demo.GARDEN, onMenu = {})
            BelfrySheet(onDismiss = {}, title = "New to-do") { NewTodoContent(Demo.GARDEN, onDone = {}, initial = text) }
        }
        val over = text.length - 300
        compose.onNodeWithText("$over characters too long. A to-do holds 300; put the rest in a comment on it.").assertExists()
        compose.onNodeWithTag("todo-add-button").assertIsNotEnabled()
        compose.onNodeWithText(text).assertExists()
    }

    /** Near: "n of 300" under the field on the right; Add on. */
    @Test fun near() {
        val text = board.take(268)
        demoShot("todo-limit-near") {
            Column(Modifier.padding(16.dp)) { NewTodoContent(Demo.GARDEN, onDone = {}, initial = text) }
        }
        compose.onNodeWithText("268 of 300").assertExists()
        compose.onNodeWithTag("todo-add-button").assertIsEnabled()
    }
}
