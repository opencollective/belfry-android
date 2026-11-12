package sh.belfry.app.ui.info

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

/** The channel's to-dos (tasklist.sh protocol, kinds 2100-2103): add, take, tick off, comment. */
@Composable
fun TasksTab(groupId: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val tasks = remember(world, groupId) { world.tasks(groupId) }
    var title by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }

    Row(verticalAlignment = Alignment.Bottom) {
        BelfryTextField(title, { title = it }, Modifier.weight(1f).testTag("todo-field"), placeholder = "Add a todo…")
        Spacer(Modifier.width(8.dp))
        BusyButton(
            "Add",
            onClick = {
                val t = title.trim()
                if (t.isNotEmpty()) {
                    session.send(Actions.addTask(groupId, t))
                    title = ""
                }
            },
            small = true, enabled = title.isNotBlank(), modifier = Modifier.testTag("todo-add"),
        )
    }
    Spacer(Modifier.height(8.dp))
    val todo = tasks.filter { !it.done }
    val done = tasks.filter { it.done }
    Hint("${todo.size} open · ${done.size} done")
    Spacer(Modifier.height(8.dp))
    if (tasks.isEmpty()) {
        EmptyState("No todos yet.", icon = BelfryIcons.Check)
    } else {
        ActionGroup {
            (todo + done).forEach { t ->
                TaskRow(groupId, t, open == t.id) { open = if (open == t.id) null else t.id }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun TaskRow(groupId: String, t: Task, open: Boolean, toggle: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val mine = t.assignee == world.me
    val check = rememberBusy()
    val now by rememberNow()
    val working = t.isWorking(now)
    var comment by remember(t.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(c.surface).alpha(if (t.pending) 0.6f else 1f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .padding(top = 1.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (t.done) c.accent else c.bg)
                    .border(1.5.dp, if (t.done) c.accent else if (t.assignee != null) c.orange else c.faint, CircleShape)
                    .clickable(enabled = !check.busy, role = Role.Checkbox) {
                        check.run { session.send(Actions.taskAction(groupId, t.id, if (t.done) "undone" else "done")) }
                    }
                    .semantics { contentDescription = if (t.done) "Mark as not done" else "Mark as done" },
                contentAlignment = Alignment.Center,
            ) {
                if (check.busy) Spinner(size = 12.dp)
                else if (t.done) Icon(BelfryIcons.Check, null, tint = c.onAccent, modifier = Modifier.size(15.dp))
                else if (working) WorkRing(t.since ?: now, now, size = 24.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = toggle)) {
                Text(
                    t.title, fontSize = 16.sp, lineHeight = 21.sp,
                    color = if (t.done) c.muted else c.text, textDecoration = if (t.done) TextDecoration.LineThrough else null,
                )
                val who = if (t.assignee == null) "Unassigned" else world.todoWho(t, now)
                Hint(who + (if (t.comments.isNotEmpty()) " · ${t.comments.size} " + (if (t.comments.size == 1) "comment" else "comments") else ""))
            }
        }
        if (open) {
            Column(Modifier.padding(start = 50.dp, end = 14.dp, bottom = 12.dp)) {
                t.comments.forEach { cm ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(world.displayName(cm.author) + "  ", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(cm.text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                ActionGroup {
                    BusyRow(
                        if (mine) "Unassign me" else if (t.assignee != null) "Take over" else "I'll take it",
                        { session.send(Actions.taskAction(groupId, t.id, if (mine) "unclaim" else "claim")) },
                        icon = BelfryIcons.User,
                    )
                    if (t.author == world.me) {
                        BusyRow("Delete", { session.send(Actions.taskAction(groupId, t.id, "delete")) }, icon = BelfryIcons.Trash, danger = true)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    BelfryTextField(comment, { comment = it }, Modifier.weight(1f), placeholder = "Comment…")
                    Spacer(Modifier.width(8.dp))
                    BusyIconButton(
                        BelfryIcons.Send, "Send the comment",
                        onClick = {
                            val text = comment.trim()
                            if (text.isNotEmpty()) {
                                session.send(Actions.commentTask(groupId, t.id, text))
                                comment = ""
                            }
                        },
                        enabled = comment.isNotBlank(),
                    )
                }
            }
        }
    }
}
