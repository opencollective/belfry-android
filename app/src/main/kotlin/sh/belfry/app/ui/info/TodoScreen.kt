package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconRow
import sh.belfry.app.ui.kit.RowItem
import sh.belfry.core.model.TodoLine
import sh.belfry.core.model.TodoShape
import sh.belfry.core.model.World
import sh.belfry.core.model.attachmentCounts
import sh.belfry.core.model.setTaskDue
import sh.belfry.core.model.taskEvent
import sh.belfry.core.model.todoDescription
import sh.belfry.core.model.todoLines
import sh.belfry.core.model.todoNeedsMe
import sh.belfry.core.model.todoSourceAuthor
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

/** The tick of a done to-do (canvas one-shape/todo-done): the same green on both themes. */
private val DONE_GREEN = Color(0xFF2E9E5B)

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
            // done: filled green AND a check mark (e-ink: the mark carries it, not the colour)
            Modifier.size(size).clip(CircleShape).background(if (t.done) DONE_GREEN else c.bg)
                .border(1.5.dp, if (t.done) DONE_GREEN else if (t.assignee != null) c.orange else c.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (check.busy) Spinner(size = 12.dp)
            else if (t.done) Icon(BelfryIcons.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
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
            val who = if (t.done) "Done" else world.todoWho(t, now) + (t.due?.let { " · Due " + TodoShape.dueLabel(it, world.ctx.zone, now) } ?: "")
            val comments = if (t.comments.isNotEmpty()) " · ${t.comments.size} " + (if (t.comments.size == 1) "comment" else "comments") else ""
            Text((sub?.let { "$it · " } ?: "") + who + comments, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.faint)
    }
}

/** How much of the to-do shows above its conversation (one shape: tiny, mid, full). */
enum class TodoLevel { Tiny, Mid, Full }

/**
 * One to-do in one shape (canvas one-shape/todo-*): the header (the tick + the title, then the
 * icon row: who is on it, comments, files, photos), the to-do itself above the conversation
 * (mid: its due date and two lines of its description; full: everything, with its rows), then the
 * conversation (comments and quiet lines) and a comment field. It opens mid while it needs me
 * (open and on me or on nobody), tiny after. Tap the title or drag the header to change levels.
 */
@Composable
fun TodoScreen(groupId: String, taskId: String, startLevel: TodoLevel? = null, startDueSheet: Boolean = false) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val t = world.tasks(groupId).firstOrNull { it.id == taskId }
    var picking by remember { mutableStateOf(false) }
    var dueSheet by remember { mutableStateOf(startDueSheet) }
    var level by rememberSaveable(taskId) { mutableStateOf(startLevel ?: if (t != null && world.todoNeedsMe(t)) TodoLevel.Mid else TodoLevel.Tiny) }
    Column(Modifier.fillMaxSize().background(c.bg).testTag("todo-screen")) {
        if (t == null) {
            TopBar("To-do", subtitle = world.group(groupId)?.name, navigation = { BackButton { nav.back() } })
            EmptyState("This to-do is gone", text = "It was deleted, or it hasn't arrived on this device yet.", icon = BelfryIcons.Check)
            return@Column
        }
        TodoHeader(groupId, t, level, onLevel = { level = it })
        if (level != TodoLevel.Tiny) TodoItself(groupId, t, level, onLevel = { level = it }, onAssign = { picking = true }, onDue = { dueSheet = true })
        Hairline()
        TodoConversation(groupId, t, Modifier.weight(1f))
        TodoComposer(groupId, t)
    }
    if (picking && t != null) AssignSheet(groupId, t, onDone = { picking = false })
    if (dueSheet && t != null) DueSheet(groupId, t, onDone = { dueSheet = false })
}

/** Two lines: ‹ · the tick + the title; then the icon row. Tap or drag it to show more or less. */
@Composable
private fun TodoHeader(groupId: String, t: Task, level: TodoLevel, onLevel: (TodoLevel) -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val now by rememberNow()
    var drag by remember { mutableStateOf(0f) }
    val dragState = rememberDraggableState { drag += it }
    val more = level != TodoLevel.Full
    Column(
        Modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)
            .draggable(dragState, Orientation.Vertical, onDragStarted = { drag = 0f }, onDragStopped = {
                if (drag > 40f) onLevel(if (level == TodoLevel.Tiny) TodoLevel.Mid else TodoLevel.Full)
                else if (drag < -40f) onLevel(if (level == TodoLevel.Full) TodoLevel.Mid else TodoLevel.Tiny)
            }),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 12.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton { nav.back() }
            TodoTick(groupId, t)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = if (more) "Show the to-do" else "Show less") {
                        onLevel(if (more) TodoLevel.Full else TodoLevel.Tiny)
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .testTag("todo-header"),
            ) {
                Text(
                    t.title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (t.done) c.muted else c.text, textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    modifier = Modifier.testTag("todo-title"),
                )
                Spacer(Modifier.height(3.dp))
                IconRow(todoRow(world, t, now), Modifier.testTag("todo-who"))
            }
        }
        // the pull handle: decorative for the eye, a real control for touch and screen readers
        Box(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (more) "Show more" else "Show less") {
                onLevel(when (level) { TodoLevel.Tiny -> TodoLevel.Mid; TodoLevel.Mid -> TodoLevel.Full; TodoLevel.Full -> TodoLevel.Tiny })
            }.padding(top = 2.dp, bottom = 6.dp).testTag("todo-handle"),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) }
    }
}

/** The header's icon row: ✓ Done <day> (when done) · 👤 who is on it, or Unassigned · 💬 · 📁 · 🖼 (each left out at 0). */
private fun todoRow(world: World, t: Task, now: Long): List<RowItem> {
    val out = ArrayList<RowItem>()
    if (t.done) {
        val day = t.doneAt?.let { TodoShape.dayLabel(it, world.ctx.zone, now) }
        out.add(RowItem("lead", BelfryIcons.Check, if (day != null) "Done $day" else "Done", if (day != null) "Done on $day" else "Done", strong = true))
    }
    val who = t.assignee
    out.add(
        if (who == null) RowItem("people", BelfryIcons.User, "Unassigned", "Nobody is on it")
        else RowItem("people", BelfryIcons.User, if (who == world.me) "You" else world.displayName(who), "Assigned to " + (if (who == world.me) "you" else world.displayName(who)), strong = !t.done),
    )
    val n = t.comments.size
    if (n > 0) out.add(RowItem("comments", BelfryIcons.Thread, "$n", if (n == 1) "1 comment" else "$n comments"))
    val (photos, files) = t.attachmentCounts()
    if (files > 0) out.add(RowItem("files", BelfryIcons.Upload, "$files", if (files == 1) "1 file" else "$files files"))
    if (photos > 0) out.add(RowItem("photos", BelfryIcons.Image, "$photos", if (photos == 1) "1 photo" else "$photos photos"))
    return out
}

/** The to-do itself, above its conversation: due date, description (mid: two lines + More), the message it came from, its rows. */
@Composable
private fun TodoItself(groupId: String, t: Task, level: TodoLevel, onLevel: (TodoLevel) -> Unit, onAssign: () -> Unit, onDue: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val now by rememberNow()
    val full = level == TodoLevel.Full
    val mineToEdit = t.author == world.me
    var error by remember { mutableStateOf<String?>(null) }
    val description = remember(world, t.id) { world.todoDescription(groupId, t) }
    val body = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    Column(
        Modifier.fillMaxWidth().heightIn(max = if (full) 520.dp else 220.dp).verticalScroll(rememberScrollState())
            .padding(bottom = 10.dp).testTag(if (full) "todo-full" else "todo-mid"),
    ) {
        if (full) Text("The to-do", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = body.padding(bottom = 6.dp))
        val due = t.due
        if (due != null || (full && mineToEdit)) {
            val label = if (due != null) "Due " + TodoShape.dueLabel(due, world.ctx.zone, now) else "Add a due date"
            Row(
                body.then(if (mineToEdit) Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = if (due != null) "Change the due date" else "Set a due date", onClick = onDue) else Modifier)
                    .padding(vertical = 4.dp).testTag("todo-due"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(BelfryIcons.Calendar, null, tint = c.muted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 14.sp, color = c.muted)
            }
        }
        if (description != null) {
            LinkText(
                description, modifier = body.padding(top = 4.dp).testTag("todo-description"), fontSize = 15.sp, lineHeight = 21.sp,
                maxLines = if (full) Int.MAX_VALUE else 2,
            )
        }
        if (!full) {
            Text(
                "More", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text, textDecoration = TextDecoration.Underline,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp).clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClickLabel = "Show the whole to-do") { onLevel(TodoLevel.Full) }
                    .padding(horizontal = 4.dp, vertical = 6.dp).testTag("todo-more"),
            )
            return@Column
        }
        val source = t.source
        if (source != null) {
            val from = world.todoSourceAuthor(groupId, t)
            Text(
                (if (from != null) "From ${if (from == world.me) "your" else world.displayName(from) + "'s"} message" else "From a message") + " ›",
                fontSize = 13.sp, color = c.muted,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp).clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Link, onClickLabel = "Open the message it came from") { nav.go(Routes.thread(groupId, source)) }
                    .padding(horizontal = 4.dp, vertical = 6.dp).testTag("todo-source"),
            )
        }
        Spacer(Modifier.height(10.dp))
        val mine = t.assignee == world.me
        ActionGroup(body) {
            if (!mine) BusyRow(
                if (t.assignee == null) "Claim" else "Take it over", { session.send(Actions.assignTask(groupId, t.id, world.me, world.me)) },
                icon = BelfryIcons.User, modifier = Modifier.testTag("todo-claim"), onError = { error = "That did not work. Try again." },
            )
            ActionRow("Assign to…", onClick = onAssign, icon = BelfryIcons.Users, chevron = true, modifier = Modifier.testTag("todo-assign"))
            val who = t.assignee
            if (who != null) BusyRow(
                if (mine) "Unassign me" else "Unassign ${world.displayName(who)}", { session.send(Actions.unassignTask(groupId, t.id, who, world.me)) },
                icon = BelfryIcons.Back, modifier = Modifier.testTag("todo-unassign"), onError = { error = "That did not work. Try again." },
            )
            if (mineToEdit) ActionRow(if (t.due == null) "Set a due date" else "Change the due date", onClick = onDue, icon = BelfryIcons.Calendar, chevron = true, modifier = Modifier.testTag("todo-due-row"))
            if (mineToEdit) BusyRow(
                "Delete the to-do", { session.send(Actions.taskAction(groupId, t.id, "delete")); nav.back() },
                icon = BelfryIcons.Trash, danger = true, modifier = Modifier.testTag("todo-delete"), onError = { error = "That did not work. Try again." },
            )
        }
        error?.let { Text(it, color = c.danger, fontSize = 14.sp, modifier = body.padding(top = 8.dp)) }
    }
}

/** The conversation about the to-do: its comments, with its activity woven in as quiet lines, by day. */
@Composable
private fun TodoConversation(groupId: String, t: Task, modifier: Modifier) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val now by rememberNow()
    val lines = remember(world, t.id, now / 3600) { world.todoLines(groupId, t.id, now) }
    // the rows: a day label before each new day, then the lines (a day label is a String, a line a TodoLine)
    val rows: List<Any> = remember(lines) {
        val out = ArrayList<Any>()
        var lastDay: java.time.LocalDate? = null
        for (l in lines) {
            val d = world.dayOf(l.at)
            if (d != lastDay) { lastDay = d; out.add(ChatFormat.day(world, l.at, now)) }
            out.add(l)
        }
        out
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (rows.size - 1).coerceAtLeast(0))
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) state.scrollToItem(rows.size - 1) }
    LazyColumn(modifier.fillMaxWidth().testTag("todo-feed"), state = state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        for ((i, r) in rows.withIndex()) {
            if (r is TodoLine) item(key = r.id + r.what) { if (r.comment != null) TodoComment(r) else TodoQuietLine(r) }
            else item(key = "day-$i") {
                Text(r.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
        }
        if (lines.size <= 1) item(key = "empty") {
            Text("No comments yet. Say something about it below.", fontSize = 14.sp, color = c.muted, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun TodoQuietLine(l: TodoLine) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val icon = when (l.what) {
        "due" -> BelfryIcons.Calendar
        "done" -> BelfryIcons.Check
        "undone" -> BelfryIcons.Retry
        "claim", "assign", "unassign", "unclaim" -> BelfryIcons.User
        else -> BelfryIcons.Check
    }
    val name = world.displayName(l.who)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("todo-line-" + l.what), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(name) }
                append(" " + (l.words ?: ""))
            },
            fontSize = 14.sp, lineHeight = 19.sp, color = c.muted, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(ChatFormat.time(world, l.at), fontSize = 12.sp, color = c.faint)
    }
}

@Composable
private fun TodoComment(l: TodoLine) {
    val c = Belfry.colors
    val world = LocalWorld.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("todo-comment-row"), verticalAlignment = Alignment.Top) {
        Avatar(l.who, world.displayName(l.who), picture = world.profiles[l.who]?.picture, size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(world.displayName(l.who), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(ChatFormat.time(world, l.at), fontSize = 12.sp, color = c.faint)
            }
            LinkText(l.comment ?: "", fontSize = 15.sp, lineHeight = 20.sp)
        }
    }
}

/** "Comment": the to-do's own composer (a kind 2103 through the outbox). */
@Composable
private fun TodoComposer(groupId: String, t: Task) {
    val c = Belfry.colors
    val session = LocalSession.current
    var comment by rememberSaveable(t.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().imePadding()) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BelfryTextField(comment, { comment = it.take(2000) }, Modifier.weight(1f).testTag("todo-comment"), placeholder = "Comment", singleLine = false, maxLines = 5)
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
                modifier = Modifier.testTag("todo-send"),
            )
        }
    }
}

/**
 * The due date (its author only): a real date and time (Material's pickers), saved as the author's
 * kind 1010 edit of the to-do with every other tag kept; "Remove the due date" sends one without.
 */
@Composable
internal fun DueSheet(groupId: String, t: Task, onDone: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val now by rememberNow()
    val zone = world.ctx.zone
    val start = remember(t.id) {
        t.due?.let { java.time.Instant.ofEpochSecond(it).atZone(zone).toLocalDateTime() }
            ?: java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate().plusDays(1).atTime(9, 0)
    }
    var date by remember(t.id) { mutableStateOf(start.toLocalDate()) }
    var time by remember(t.id) { mutableStateOf(start.toLocalTime()) }
    var pick by remember { mutableStateOf(0) } // 1 date, 2 time
    var error by remember { mutableStateOf<String?>(null) }
    val busy = rememberBusy()
    val chosen = date.atTime(time).atZone(zone).toEpochSecond()
    BelfrySheet(onDismiss = { if (!busy.busy) onDone() }, title = if (t.due == null) "Set a due date" else "Change the due date", modifier = Modifier.testTag("todo-due-sheet")) {
        ActionGroup {
            ActionRow("Day", onClick = { pick = 1 }, icon = BelfryIcons.Calendar, value = TodoShape.dayLabel(chosen, zone, now), chevron = true, modifier = Modifier.testTag("due-date"))
            ActionRow("Time", onClick = { pick = 2 }, icon = BelfryIcons.Clock, value = TodoShape.dueLabel(chosen, zone, now).substringAfter(", "), chevron = true, modifier = Modifier.testTag("due-time"))
        }
        Spacer(Modifier.height(10.dp))
        Text("Due " + TodoShape.dueLabel(chosen, zone, now), fontSize = 14.sp, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp).testTag("due-preview"))
        Spacer(Modifier.height(12.dp))
        val ev = world.taskEvent(groupId, t.id)
        BusyButton(
            "Save", {
                val e = ev ?: return@BusyButton
                session.send(Actions.setTaskDue(e, t.title, chosen)); onDone()
            },
            modifier = Modifier.fillMaxWidth().testTag("due-save"), enabled = ev != null && chosen != t.due, busy = busy,
            onError = { error = "That did not work. Try again." },
        )
        if (t.due != null) {
            Spacer(Modifier.height(12.dp))
            ActionGroup {
                BusyRow(
                    "Remove the due date", { val e = ev ?: return@BusyRow; session.send(Actions.setTaskDue(e, t.title, null)); onDone() },
                    icon = BelfryIcons.Close, danger = true, busy = busy, enabled = ev != null, modifier = Modifier.testTag("due-remove"),
                    onError = { error = "That did not work. Try again." },
                )
            }
        }
        if (ev == null) Text("This to-do hasn't arrived on this device yet.", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        error?.let { Text(it, color = c.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
    }
    if (pick == 1) sh.belfry.app.ui.cards.PickDate(date, onDismiss = { pick = 0 }) { date = it }
    if (pick == 2) sh.belfry.app.ui.cards.PickTime(time, onDismiss = { pick = 0 }) { time = it }
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
