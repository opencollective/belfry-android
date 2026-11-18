package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyIconButton
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.LinkText
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.kit.WorkRing
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.kit.rememberNow
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Task
import sh.belfry.core.model.todoAssignees
import sh.belfry.core.model.todoWho

// To-dos (tasklist.sh kinds 2100-2103, SPEC 19.40): every row in a list is the tick box (its own
// tap target), the title and who is on it, and a chevron; the row opens the to-do's own screen,
// where it is claimed, given to someone, taken off them, discussed and deleted.

/** The round box that ticks a to-do off, or opens it again (locks while it is being sent). */
@Composable
fun TodoTick(groupId: String, t: Task, size: androidx.compose.ui.unit.Dp = 24.dp) {
    val c = Belfry.colors
    val session = LocalSession.current
    val check = rememberBusy()
    val now by rememberNow()
    Box(Modifier.size(44.dp).clickable(enabled = !check.busy, role = Role.Checkbox) {
        check.run { session.send(Actions.taskAction(groupId, t.id, if (t.done) "undone" else "done")) }
    }.semantics { contentDescription = if (t.done) "Mark as not done" else "Mark as done" }.testTag("todo-tick-" + t.id), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).clip(CircleShape).background(if (t.done) c.accent else c.bg)
                .border(1.5.dp, if (t.done) c.accent else if (t.assignee != null) c.orange else c.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (check.busy) Spinner(size = 12.dp)
            else if (t.done) Icon(BelfryIcons.Check, null, tint = c.onAccent, modifier = Modifier.size(15.dp))
            else if (t.isWorking(now)) WorkRing(t.since ?: now, now, size = size)
        }
    }
}

/** A to-do in a list: tick box · title and who · ›. The row (not the box) opens the to-do. */
@Composable
fun TodoListRow(groupId: String, t: Task, sub: String? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val now by rememberNow()
    Row(
        Modifier.fillMaxWidth().background(c.surface).alpha(if (t.pending) 0.6f else 1f)
            .clickable(role = Role.Button, onClickLabel = "Open the to-do") { nav.go(Routes.todo(groupId, t.id)) }
            .padding(start = 4.dp, end = 10.dp, top = 2.dp, bottom = 2.dp)
            .testTag("todo-row-" + t.id),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TodoTick(groupId, t)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                t.title, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (t.done) c.muted else c.text, textDecoration = if (t.done) TextDecoration.LineThrough else null,
            )
            val who = if (t.done) "Done" else world.todoWho(t, now)
            val comments = if (t.comments.isNotEmpty()) " · ${t.comments.size} " + (if (t.comments.size == 1) "comment" else "comments") else ""
            Text((sub?.let { "$it · " } ?: "") + who + comments, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.faint)
    }
}

/** One to-do: tick it, claim it, give it to someone, take them off it, talk about it, delete it. */
@Composable
fun TodoScreen(groupId: String, taskId: String) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val t = world.tasks(groupId).firstOrNull { it.id == taskId }
    val now by rememberNow()
    var picking by remember { mutableStateOf(false) }
    var comment by remember(taskId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(c.bg).testTag("todo-screen")) {
        TopBar("To-do", subtitle = world.group(groupId)?.name, navigation = { BackButton { nav.back() } })
        if (t == null) {
            EmptyState("This to-do is gone", text = "It was deleted, or it hasn't arrived on this device yet.", icon = BelfryIcons.Check)
            return@Column
        }
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                TodoTick(groupId, t, size = 26.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    LinkText(
                        t.title, fontSize = 20.sp, lineHeight = 26.sp,
                        color = if (t.done) c.muted else c.text, textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (if (t.done) "Done · " else "") + (if (t.assignee == null) "Nobody on it yet" else world.todoWho(t, now)),
                        fontSize = 15.sp, color = c.muted, modifier = Modifier.testTag("todo-who"),
                    )
                    Text("Made by ${world.displayName(t.author)}", fontSize = 13.sp, color = c.faint)
                }
            }
            Spacer(Modifier.height(16.dp))
            val mine = t.assignee == world.me
            ActionGroup {
                if (!mine) BusyRow(
                    if (t.assignee == null) "Claim" else "Take it over", { session.send(Actions.assignTask(groupId, t.id, world.me, world.me)) },
                    icon = BelfryIcons.User, modifier = Modifier.testTag("todo-claim"), onError = { error = "That did not work. Try again." },
                )
                ActionRow("Assign to…", onClick = { picking = true }, icon = BelfryIcons.Users, chevron = true, modifier = Modifier.testTag("todo-assign"))
                val who = t.assignee
                if (who != null) BusyRow(
                    if (mine) "Unassign me" else "Unassign ${world.displayName(who)}", { session.send(Actions.unassignTask(groupId, t.id, who, world.me)) },
                    icon = BelfryIcons.Back, modifier = Modifier.testTag("todo-unassign"), onError = { error = "That did not work. Try again." },
                )
                val source = t.source
                if (source != null) ActionRow("Open the message it came from", onClick = { nav.go(Routes.thread(groupId, source)) }, icon = BelfryIcons.Thread, chevron = true)
                if (t.author == world.me) BusyRow(
                    "Delete the to-do", { session.send(Actions.taskAction(groupId, t.id, "delete")); nav.back() },
                    icon = BelfryIcons.Trash, danger = true, modifier = Modifier.testTag("todo-delete"), onError = { error = "That did not work. Try again." },
                )
            }
            error?.let { Text(it, color = c.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("Discussion", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Spacer(Modifier.height(6.dp))
            if (t.comments.isEmpty()) Text("No comments yet.", fontSize = 15.sp, color = c.muted)
            for (cm in t.comments) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Avatar(cm.author, world.displayName(cm.author), picture = world.profiles[cm.author]?.picture, size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(world.displayName(cm.author), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        LinkText(cm.text, fontSize = 15.sp, lineHeight = 20.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BelfryTextField(comment, { comment = it.take(2000) }, Modifier.weight(1f).testTag("todo-comment"), placeholder = "Write a comment…", singleLine = false)
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
            Spacer(Modifier.height(24.dp))
        }
    }
    if (picking && t != null) AssignSheet(groupId, t, onDone = { picking = false })
}

/** "Assign to…": the people the to-do can go to, me first (a tap gives it and closes the sheet). */
@Composable
private fun AssignSheet(groupId: String, t: Task, onDone: () -> Unit) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val people = remember(world, groupId) { world.todoAssignees(groupId) }
    val busy = rememberBusy()
    BelfrySheet(onDismiss = { if (!busy.busy) onDone() }, title = "Assign to", modifier = Modifier.testTag("todo-assign-sheet")) {
        ActionGroup {
            for (pk in people) {
                val name = if (pk == world.me) "Me" else world.displayName(pk)
                BusyRow(
                    name, { session.send(Actions.assignTask(groupId, t.id, pk, world.me)); onDone() },
                    icon = BelfryIcons.User, sub = if (pk == t.assignee) "On it now" else null, busy = busy,
                    enabled = pk != t.assignee, modifier = Modifier.testTag("todo-assign-" + pk.take(8)),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
