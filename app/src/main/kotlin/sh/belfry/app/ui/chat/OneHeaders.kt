package sh.belfry.app.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import sh.belfry.app.push.AndroidPush
import sh.belfry.app.push.PushState
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.RowItem
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.people.rememberTurnOnNotifications
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.model.CardStatus
import sh.belfry.core.model.Message
import sh.belfry.core.model.World
import sh.belfry.core.model.channelCounts
import sh.belfry.core.push.NotificationSettings
import sh.belfry.core.push.PushLevel

// The one-shape headers' rows (docs/one-shape.md 2, canvas one-shape/anatomy): each item left out
// at zero, a spoken label for each. The header itself is kit's OneHeader.

private fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** A channel's or group's row: (✉ N open · 👤 N on you on a channel that takes e-mails) · 👥 · 📌 · ✓ · 📁 · 📅. */
fun channelRowItems(world: World, groupId: String, now: Long): List<RowItem> {
    val g = world.group(groupId) ?: return emptyList()
    val n = world.channelCounts(groupId, now)
    val out = ArrayList<RowItem>()
    if (world.isInbox(g)) {
        val threads = world.mailThreads(groupId).list.filter { it.state.status == CardStatus.OPEN }
        val mine = threads.count { it.state.assignee == world.me }
        if (threads.isNotEmpty()) {
            out.add(RowItem("lead", BelfryIcons.Mail, "${threads.size} open", count(threads.size, "open e-mail"), lead = true))
            // "on you" leaves first when the row is full (the owner's place in the drop order)
            if (mine > 0) out.add(RowItem("owner", BelfryIcons.User, "$mine on you", "$mine on you", lead = true))
        }
    }
    if (n.members > 0) out.add(RowItem("people", BelfryIcons.Users, "${n.members}", count(n.members, "person", "people")))
    if (n.pins > 0) out.add(RowItem("pinned", BelfryIcons.Pin, "${n.pins}", count(n.pins, "pinned message")))
    val open = n.todos - n.todosDone
    if (open > 0) out.add(RowItem("todos", MoreIcons.Todo, "$open", count(open, "open to-do")))
    if (n.files > 0) out.add(RowItem("files", Icons.Outlined.Folder, "${n.files}", count(n.files, "file")))
    if (n.events > 0) out.add(RowItem("events", BelfryIcons.Calendar, "${n.events}", count(n.events, "upcoming event")))
    return out
}

/** A direct conversation's row: ✓ · 📁 · 🖼, no people count (to-dos in DMs come with the next build). */
fun dmRowItems(todos: Int, files: Int, photos: Int): List<RowItem> = listOfNotNull(
    if (todos > 0) RowItem("todos", MoreIcons.Todo, "$todos", count(todos, "open to-do")) else null,
    if (files > 0) RowItem("files", Icons.Outlined.Folder, "$files", count(files, "file")) else null,
    if (photos > 0) RowItem("photos", BelfryIcons.Image, "$photos", count(photos, "photo")) else null,
)

/** A message thread's row: 👥 who took part · 💬 replies. */
fun threadRowItems(people: Int, replies: Int): List<RowItem> = listOfNotNull(
    if (people > 0) RowItem("people", BelfryIcons.Users, "$people", count(people, "person", "people")) else null,
    if (replies > 0) RowItem("comments", BelfryIcons.Thread, "$replies", count(replies, "reply", "replies")) else null,
)

/** A thread's title: the beginning of its first message, on one line (thread names: not in this version). */
fun threadTitle(world: World, root: Message?): String {
    root ?: return "Thread"
    val text = Markdown.text(Markdown.inline(world.activitySnippet(root))).replace(Regex("\\s+"), " ").trim()
    return text.ifEmpty { if (root.media.isNotEmpty()) "A photo" else "Thread" }
}

/**
 * The bell at a channel's top right (Xavier, 22 Nov): off by default; on = every message of this
 * channel is pushed (level `all`), off = back to the default. The state is the belfry's (read with
 * the notification settings); the bell locks with a spinner while it saves and goes back if the
 * belfry refuses. The glyph changes (outline / filled), not only its colour (e-ink).
 */
@Composable
fun ChannelBell(groupId: String, channelName: String, modifier: Modifier = Modifier, initial: NotificationSettings? = null) {
    val c = Belfry.colors
    val session = LocalSession.current
    val context = LocalContext.current
    var on by remember(groupId) { mutableStateOf(initial?.let { it.level(groupId) == PushLevel.ALL }) }
    LaunchedEffect(groupId) {
        if (on == null) try { on = session.notificationSettings().level(groupId) == PushLevel.ALL } catch (e: CancellationException) { throw e } catch (e: Exception) { }
    }
    val pushState by AndroidPush.state.collectAsState()
    val turnOn = rememberTurnOnNotifications()
    val busy = rememberBusy()
    val now = on == true
    val label = if (now) "Notifications for every message in $channelName" else "Notify me about every message in $channelName"
    IconButton(
        onClick = {
            busy.run({ }) {
                val want = !(on ?: false)
                on = want
                try {
                    val s = session.setNotifications(NotificationSettings.setLevel(groupId, if (want) PushLevel.ALL else PushLevel.DEFAULT))
                    on = s.level(groupId) == PushLevel.ALL
                    if (want && pushState == PushState.NEEDS_PERMISSION) turnOn()
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    on = !want
                }
            }
        },
        enabled = !busy.busy,
        modifier = modifier.testTag("channel-bell").semantics {
            role = Role.Switch
            contentDescription = label
            stateDescription = if (now) "On" else "Off"
        },
    ) {
        if (busy.busy) Spinner(size = 18.dp, color = c.text)
        else Icon(if (now) BelfryIcons.BellFilled else BelfryIcons.Bell, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
    }
    LaunchedEffect(Unit) { AndroidPush.refreshState(context, session) }
}
