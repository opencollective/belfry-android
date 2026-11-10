package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.BusyState
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.Tabs
import sh.belfry.app.ui.kit.isSafeLink
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.signin.openInBrowser
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.Rsvp

private sealed interface EventsItem {
    data class Day(val label: String) : EventsItem
    data class Row(val e: CalEvent) : EventsItem
    data class PastToggle(val count: Int, val open: Boolean) : EventsItem
    data class Empty(val text: String) : EventsItem
}

/** Upcoming events of one channel or of all, by day; Going / Maybe / All; the past folded away. */
@Composable
fun EventsScreen(channel: String?) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val now = session.now()
    var filter by rememberSaveable { mutableIntStateOf(2) }
    var showPast by rememberSaveable { mutableStateOf(false) }
    val g = channel?.let { world.group(it) }

    val soon = world.upcoming(channel, now).filter { e ->
        when (filter) {
            0 -> world.myRsvp(e) == Rsvp.ACCEPTED
            1 -> world.myRsvp(e) == Rsvp.TENTATIVE
            else -> true
        }
    }
    val upcomingIds = world.upcoming(channel, now).map { it.coord }.toSet()
    val past = if (filter == 2) world.calendar.filter { it.coord !in upcomingIds && (channel == null || it.channel == channel) }.sortedByDescending { it.start } else emptyList()

    val rows = ArrayList<EventsItem>()
    if (soon.isEmpty()) rows.add(EventsItem.Empty(listOf("You're not going to anything yet.", "No maybes.", "Nothing planned yet. Post the first event.")[filter]))
    var lastDay = ""
    for (e in soon) {
        val day = ChatFormat.day(world, e.start, now)
        if (day != lastDay) rows.add(EventsItem.Day(day))
        lastDay = day
        rows.add(EventsItem.Row(e))
    }
    if (past.isNotEmpty()) {
        rows.add(EventsItem.PastToggle(past.size, showPast))
        if (showPast) past.forEach { rows.add(EventsItem.Row(it)) }
    }

    CardScreen(
        "Events",
        subtitle = g?.let { "# " + it.name },
        actions = { IconAction(BelfryIcons.Plus, "New event", { nav.go(Routes.createEvent(channel ?: "general")) }) },
    ) {
        Tabs(listOf("Going", "Maybe", "All"), filter, { filter = it })
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows) { item ->
                when (item) {
                    is EventsItem.Day -> SectionTitle(item.label, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp))
                    is EventsItem.Row -> EventRow(item.e)
                    is EventsItem.Empty -> Muted(item.text, Modifier.padding(horizontal = 16.dp, vertical = 28.dp))
                    is EventsItem.PastToggle -> Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        ActionGroup {
                            ActionRow(
                                "Past events",
                                onClick = { showPast = !showPast },
                                modifier = Modifier.testTag("events-past"),
                                icon = BelfryIcons.Clock,
                                value = item.count.toString(),
                                trailing = { Icon(BelfryIcons.ChevronDown, contentDescription = if (item.open) "Hide" else "Show", tint = Belfry.colors.muted, modifier = Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: CalEvent) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val mine = world.myRsvp(e)
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().alpha(if (world.isPending(e.ev.id)) 0.6f else 1f).clickable { nav.go(Routes.event(e.coord)) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DateChip(world, e.start)
        GapW(12)
        Column(Modifier.weight(1f)) {
            Text(e.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Muted(CardFormat.whenText(world, e) + (if (!e.location.isNullOrEmpty()) " · " + e.location else "") + (if (e.links.isNotEmpty()) " · online" else ""), maxLines = 1)
            Muted((if (mine != null) mine.label + " · " else "") + "${e.going} going", maxLines = 1)
        }
    }
}

/** One event: when, where, the link to join online, the description, my answer, who comes. */
@Composable
fun EventScreen(coord: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val c = Belfry.colors
    val e = world.calEvent(coord)
    if (e == null) {
        CardScreen("Event") { EmptyState("This event is not here (yet)", text = "It may still be syncing.", icon = BelfryIcons.Calendar) }
        return
    }
    val now = session.now()
    val mine = world.myRsvp(e)
    // null: the details show until you answer, then fold to your answer
    var open by remember(coord) { mutableStateOf<Boolean?>(null) }
    var deleting by remember(coord) { mutableStateOf(false) }
    val busy = rememberBusy()
    val hasChat = world.group(e.groupId) != null

    CardScreen(e.title, subtitle = CardFormat.dayAndWhen(world, e, now)) {
        PageColumn {
            if (mine != null && open != true) {
                Panel(Modifier.testTag("event-answer"), onClick = { open = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnswerLine(mine, e.going, Modifier.weight(1f))
                        Muted("Details")
                        Icon(BelfryIcons.ChevronDown, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
                    }
                }
            } else {
                Panel(Modifier.testTag("event-details")) {
                    if (mine != null) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { open = false }, verticalAlignment = Alignment.CenterVertically) {
                            AnswerLine(mine, e.going, Modifier.weight(1f))
                            Muted("Hide")
                        }
                        Gap(8)
                    }
                    if (!e.location.isNullOrEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(BelfryIcons.MapPin, contentDescription = "Where", tint = c.muted, modifier = Modifier.size(16.dp))
                            GapW(6)
                            Text(e.location ?: "", fontSize = 15.sp, color = c.text)
                        }
                    }
                    for (link in e.links.filter { isSafeLink(it) }) {
                        Gap(8)
                        BusyButton("Join on " + Actions.meetingLabel(link), onClick = { openInBrowser(context, link) }, kind = ButtonKind.Ghost, icon = BelfryIcons.Globe, small = true)
                    }
                    if (e.description.isNotEmpty()) {
                        Gap(8)
                        MarkdownText(e.description, rich = false, color = c.muted)
                    }
                    Gap(6)
                    Muted("Posted by " + world.displayName(e.author) + (if (mine == null) " · ${e.going} going" else ""))
                    Gap(10)
                    RsvpControl(mine, busy) { s ->
                        session.rsvp(e, s)
                        open = null
                    }
                }
            }

            SectionTitle("People")
            val rows = e.rsvps.entries.sortedBy { it.value.ordinal }
            if (rows.isEmpty()) Muted("No answers yet.")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((pubkey, answer) in rows) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.go(Routes.profile(pubkey)) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(world.face(pubkey), size = 30.dp)
                        GapW(10)
                        Text(world.displayName(pubkey), Modifier.weight(1f), fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Badge(answer.label, if (answer == Rsvp.ACCEPTED) Tone.Good else Tone.Neutral)
                    }
                }
            }

            ActionGroup {
                if (hasChat) {
                    ActionRow("Open the event's chat", onClick = { nav.openChannel(e.groupId) }, modifier = Modifier.testTag("event-chat"), icon = BelfryIcons.Thread, chevron = true)
                } else {
                    BusyRow(
                        "Open a chat for it",
                        onClick = {
                            session.send(Actions.openEventChat(e))
                            nav.openChannel(e.groupId)
                        },
                        icon = BelfryIcons.Thread,
                        sub = "This event has no chat yet",
                    )
                }
                ActionRow("All events", onClick = { nav.go(Routes.events(e.channel)) }, icon = BelfryIcons.Calendar, chevron = true)
            }
            if (e.author == world.me) {
                ActionGroup {
                    ActionRow("Delete event", onClick = { deleting = true }, modifier = Modifier.testTag("event-delete"), icon = BelfryIcons.Trash, danger = true)
                }
            }
        }
    }

    if (deleting) {
        ConfirmSheet(
            title = "Delete this event?",
            confirmLabel = "Delete",
            onConfirm = {
                session.send(Actions.deleteEvent(e))
                deleting = false
                nav.back()
            },
            onDismiss = { deleting = false },
            message = "It disappears from everyone's calendar.",
            preview = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DateChip(world, e.start)
                    GapW(12)
                    Column {
                        Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        Muted(CardFormat.dayAndWhen(world, e, now))
                    }
                }
            },
        )
    }
}

@Composable
private fun AnswerLine(mine: Rsvp, going: Int, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (mine == Rsvp.ACCEPTED) {
            Icon(BelfryIcons.Check, contentDescription = null, tint = c.text, modifier = Modifier.size(16.dp))
            GapW(6)
        }
        Text(mine.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Text(" · $going going", fontSize = 15.sp, color = c.muted)
    }
}

/**
 * Going / Maybe / Can't go as one control: the three share a lock, so a tap disables all of them
 * until the answer is saved (no double answers).
 */
@Composable
internal fun RsvpControl(mine: Rsvp?, busy: BusyState, onAnswer: suspend (Rsvp) -> Unit) {
    val c = Belfry.colors
    var tapped by remember { mutableStateOf<Rsvp?>(null) }
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in Rsvp.entries) {
            val on = mine == s
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(if (on) c.accent else c.bg)
                    .border(1.dp, if (on) c.accent else c.line, shape)
                    .alpha(if (busy.busy && tapped != s) 0.5f else 1f)
                    .clickable(enabled = !busy.busy, role = Role.Button) {
                        tapped = s
                        busy.run { onAnswer(s) }
                    }
                    .testTag("rsvp-" + s.key)
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (busy.busy && tapped == s) {
                    Spinner(color = if (on) c.onAccent else c.muted)
                } else {
                    Text(s.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) c.onAccent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
