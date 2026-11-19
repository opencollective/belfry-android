package sh.belfry.app.ui.info

import sh.belfry.app.ui.kit.TodoTextField
import sh.belfry.core.model.TodoLimit
import sh.belfry.app.ui.kit.LinkText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Task
import sh.belfry.core.model.todoWho
import androidx.compose.runtime.getValue

/** The channel's to-dos (tasklist.sh protocol, kinds 2100-2103): add and tick off here; a row opens the to-do. */
@Composable
fun TasksTab(groupId: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val tasks = remember(world, groupId) { world.tasks(groupId) }
    var title by remember { mutableStateOf("") }

    Row(verticalAlignment = Alignment.Bottom) {
        TodoTextField(title, { title = it }, Modifier.weight(1f).testTag("todo-field"), placeholder = "Add a to-do…", maxLines = 4)
        Spacer(Modifier.width(8.dp))
        BusyButton(
            "Add",
            onClick = {
                val t = title.trim()
                if (TodoLimit.fits(t)) {
                    session.send(Actions.addTask(groupId, t))
                    title = ""
                }
            },
            small = true, enabled = TodoLimit.fits(title), modifier = Modifier.testTag("todo-add"),
        )
    }
    Spacer(Modifier.height(8.dp))
    val todo = tasks.filter { !it.done }
    val done = tasks.filter { it.done }
    Hint("${todo.size} open · ${done.size} done")
    Spacer(Modifier.height(8.dp))
    if (tasks.isEmpty()) {
        EmptyState("No to-dos yet.", icon = BelfryIcons.Check)
    } else {
        ActionGroup {
            // tick box · title · ›: the row opens the to-do (claim, assign, discuss: TodoScreen)
            (todo + done).forEach { t -> TodoListRow(groupId, t) }
        }
    }
    Spacer(Modifier.height(24.dp))
}
