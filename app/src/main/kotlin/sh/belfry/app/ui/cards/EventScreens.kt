package sh.belfry.app.ui.cards

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.Photos
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
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.Tabs
import sh.belfry.app.ui.kit.copyText
import sh.belfry.app.ui.kit.isSafeLink
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.kit.shareText
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.signin.openInBrowser
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.engine.Session
import sh.belfry.core.model.Actions
import sh.belfry.core.model.ApiEvent
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.EventPeople
import sh.belfry.core.model.EventStatus
import sh.belfry.core.model.EventsTab
import sh.belfry.core.model.MyState
import sh.belfry.core.model.Rsvp
import sh.belfry.core.model.Ticket
import sh.belfry.core.model.World
import sh.belfry.core.model.eventByKey
import sh.belfry.core.model.eventPeople
import sh.belfry.core.model.eventUrl
import sh.belfry.core.model.eventsIn
import sh.belfry.core.model.isHost
import sh.belfry.core.model.isOpen
import sh.belfry.core.model.myState
import sh.belfry.core.model.stateLabel
import sh.belfry.core.model.stillGoing
import sh.belfry.core.model.tickets

internal fun shownError(e: Throwable): String = (e as? ActionException)?.message ?: "That did not work. Try again."

private sealed interface EventsItem {
    data class Day(val label: String) : EventsItem
    data class Row(val e: CalEvent) : EventsItem
    data class Empty(val text: String) : EventsItem
    data object New : EventsItem
}

/** #/events: every event I can read (or one channel's cards): Upcoming / Mine / Hosting / Past. */
@Composable
fun EventsScreen(channel: String?, initialTab: Int = 0) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val now = session.now()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val g = channel?.let { world.group(it) }
    val which = EventsTab.entries[tab]
    val list = world.eventsIn(which, now, channel)
    val rows = ArrayList<EventsItem>()
    if (list.isEmpty()) rows.add(EventsItem.Empty(which.empty))
    var lastDay = ""
    for (e in list) {
        val day = ChatFormat.day(world, e.start, now)
        if (which != EventsTab.PAST && day != lastDay) rows.add(EventsItem.Day(day))
        lastDay = day
        rows.add(EventsItem.Row(e))
    }
    rows.add(EventsItem.New)
    val create = { nav.go(Routes.createEvent(Routes.NO_CHANNEL)) }

    CardScreen(
        "Events",
        subtitle = g?.let { "# " + it.name },
        actions = { IconAction(BelfryIcons.Plus, "New event", create) },
    ) {
        Tabs(EventsTab.entries.map { it.label }, tab, { tab = it }, Modifier.testTag("events-tabs"))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows) { item ->
                when (item) {
                    is EventsItem.Day -> SectionTitle(item.label, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp))
                    is EventsItem.Row -> EventRow(item.e)
                    is EventsItem.Empty -> Muted(item.text, Modifier.padding(horizontal = 16.dp, vertical = 28.dp))
                    is EventsItem.New -> Box(Modifier.padding(16.dp)) {
                        BusyButton("New event", onClick = { create() }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, icon = BelfryIcons.Plus)
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
    val c = Belfry.colors
    val s = world.stateLabel(e)
    Row(
        Modifier.fillMaxWidth().alpha(if (world.isPending(e.ev.id)) 0.6f else 1f).clickable { nav.go(Routes.event(e.coord)) }
            .padding(horizontal = 16.dp, vertical = 10.dp).testTag("event-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        e.cover?.let { cv -> EventCover(cv, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))) } ?: DateChip(world, e.start)
        GapW(12)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.status == EventStatus.CANCELLED) { Badge("Cancelled", Tone.Bad); GapW(6) }
                Text(e.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Muted(ChatFormat.day(world, e.start, LocalSession.current.now()) + " · " + CardFormat.whenText(world, e) + (if (!e.location.isNullOrEmpty()) " · " + e.location else "") + (if (e.links.isNotEmpty()) " · online" else ""), maxLines = 1)
            Muted((if (s != null) "$s · " else "") + "${e.going} going", maxLines = 1)
        }
    }
}

/** An event's cover: a picture on the file server (when pictures load), or kept on the device (the demo). */
@Composable
internal fun EventCover(url: String, modifier: Modifier = Modifier) {
    val session = LocalSession.current
    val network = LocalNetworkImages.current
    if (url.startsWith(Session.LOCAL_BLOB)) {
        val bitmap by produceState<ImageBitmap?>(null, url) { value = withContext(Dispatchers.Default) { session.localBlob(url)?.let { Photos.decodeBytes(it) } } }
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) } ?: Box(modifier.background(Belfry.colors.surface2))
    } else if (url.startsWith("https://") && network) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(Belfry.colors.surface2))
    }
}

/**
 * One event (#/events/<link>): KEY is its coordinate or the last part of its URL. Ours when the
 * relay gave it to us, else the belfry's answer (a guest can't read the community relay).
 */
@Composable
fun EventScreen(key: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val local = world.eventByKey(key)
    if (local != null) return EventView(local, null)
    val remote by session.events.remote.collectAsState()
    val isLink = !key.contains(':')
    LaunchedEffect(key) { if (isLink) session.events.fetch(key) }
    val got = remote[key]
    val api = got?.api
    if (api != null) return EventView(api.toCalEvent(world.ctx.zone, world.me), api)
    val title = when {
        !isLink -> "This event is not here (yet)"
        got == null -> "Opening the event…"
        got.error == "members_only" -> "This event is for members."
        else -> "There is no such event here."
    }
    CardScreen("Event") { EmptyState(title, text = if (!isLink) "It may still be syncing." else null, icon = BelfryIcons.Calendar) }
}

/** The sheets of an event's screen. */
internal enum class EventSheet { None, Menu, Take, Tickets, Guests, Qr, CoHost, Cancel, Delete }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventView(e: CalEvent, api: ApiEvent?) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val c = Belfry.colors
    val now = session.now()
    var sheet by remember(e.coord) { mutableStateOf(EventSheet.None) }
    var ticketFor by remember(e.coord) { mutableStateOf<Ticket?>(null) }
    val p = world.eventPeople(e)
    val tks = world.tickets(e, api)
    val st = world.myState(e, p, api)
    val host = world.isHost(e)
    val g = world.group(e.groupId)
    val open = world.isOpen(e, now)
    val plain = tks.size == 1 && tks[0].implicit
    val guest = world.amGuest || e.remote
    val asking = if (host) p.applications.size else 0
    val going = if (e.remote) e.going else e.goingKeys.size
    val inside = g != null && (!g.closed || world.me in g.members || world.me in g.admins || world.me in p.holders)
    val busy = rememberBusy()
    val pick = { t: Ticket -> ticketFor = t; sheet = EventSheet.Take }
    // RSVP in one tap where it can be (a member, the plain free ticket); else the ticket's sheet
    val take: suspend () -> Unit = {
        if (plain && !guest) session.events.rsvp(e, Rsvp.ACCEPTED)
        else if (tks.size == 1) pick(tks[0])
        else { ticketFor = null; sheet = EventSheet.Take }
    }
    val url = world.eventUrl(e)

    CardScreen(
        e.title,
        subtitle = if (e.isNew) "Event · " + world.ctx.communityName.ifEmpty { session.community.name } else CardFormat.dayAndWhen(world, e, now),
        actions = { IconAction(BelfryIcons.More, "More", { sheet = EventSheet.Menu }, Modifier.testTag("event-more")) },
    ) {
        PageColumn {
            e.cover?.let { cv -> EventCover(cv, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) }
            EventLine(e, p, st, asking, going, open, busy, onTake = take, onReview = { sheet = EventSheet.Guests })

            Panel(Modifier.testTag("event-details")) {
                Text(ChatFormat.day(world, e.start, now) + " · " + CardFormat.whenText(world, e) + endDay(world, e, now), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                if (!e.location.isNullOrEmpty()) {
                    Gap(6)
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
                if (e.links.isEmpty() && api?.online == true) { Gap(6); Muted("Online: the link shows once you have a place.") }
            }

            if (open) {
                Column {
                    SectionTitle("Your RSVP")
                    RsvpRow(e, p, tks, st, guest, busy, onPick = pick, onTake = take)
                }
            }

            Column {
                SectionTitle(if (e.hosts.size == 1) "Host" else "Hosts")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (h in e.hosts) {
                        val name = api?.hosts?.firstOrNull { it.first == h }?.second?.ifEmpty { null } ?: world.displayName(h)
                        Row(
                            Modifier.clip(CircleShape).border(1.dp, c.line, CircleShape).clickable(role = Role.Button) { nav.go(Routes.profile(h)) }.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(world.face(h), size = 24.dp)
                            GapW(6)
                            Text(name, fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            if (!plain) {
                Column {
                    SectionTitle("Tickets")
                    for (t in tks) TicketRow(t, p, onPick = if (open && st == MyState.NONE) ({ pick(t) }) else null)
                }
            }

            if (e.description.isNotEmpty()) MarkdownText(e.description, rich = false)

            if (e.isNew) {
                Column(Modifier.testTag("event-url")) {
                    SectionTitle("URL")
                    Text(url, fontSize = 13.sp, color = c.text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Gap(8)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BusyButton("Copy", onClick = { copyText(context, url, e.title) }, kind = ButtonKind.Ghost, small = true, icon = BelfryIcons.Copy)
                        BusyButton("Share…", onClick = { shareText(context, url, e.title) }, kind = ButtonKind.Ghost, small = true, icon = BelfryIcons.Share)
                        BusyButton("QR code", onClick = { sheet = EventSheet.Qr }, kind = ButtonKind.Ghost, small = true, icon = BelfryIcons.Qr)
                    }
                }
            }

            ActionGroup {
                if (host && e.isNew) {
                    ActionRow(
                        "Guest list", onClick = { sheet = EventSheet.Guests }, modifier = Modifier.testTag("event-guests"), icon = BelfryIcons.Users,
                        sub = "$going going" + if (asking > 0) " · $asking asking" else "", chevron = true,
                    )
                }
                when {
                    g != null && inside -> ActionRow("Open the event's chat", onClick = { nav.openChannel(e.groupId) }, modifier = Modifier.testTag("event-chat"), icon = BelfryIcons.Thread, sub = "Members, files, to-dos", chevron = true)
                    g == null && !e.isNew && !e.remote -> BusyRow(
                        "Open a chat for it",
                        onClick = { session.send(Actions.openEventChat(e)); nav.openChannel(e.groupId) },
                        icon = BelfryIcons.Thread, sub = "This event has no chat yet",
                    )
                    else -> ActionRow(
                        "The event's chat", onClick = null, icon = BelfryIcons.Thread, enabled = false,
                        sub = when {
                            st == MyState.APPLIED || st == MyState.WAITLIST -> "It opens once the hosts give you a place."
                            e.status == EventStatus.CANCELLED -> "This event is cancelled."
                            else -> "It opens once you have a place."
                        },
                    )
                }
                ActionRow("All events", onClick = { nav.go(Routes.events(null)) }, icon = BelfryIcons.Calendar, chevron = true)
            }
            if (host && e.isNew) {
                ActionGroup {
                    ActionRow("Edit the event", onClick = { nav.go(Routes.editEvent(e.coord)) }, modifier = Modifier.testTag("event-edit"), icon = BelfryIcons.Edit, chevron = true)
                }
            }
            if (!e.isNew && e.author == world.me) {
                ActionGroup { ActionRow("Delete event", onClick = { sheet = EventSheet.Delete }, modifier = Modifier.testTag("event-delete"), icon = BelfryIcons.Trash, danger = true) }
            }
        }
    }

    val close = { sheet = EventSheet.None; ticketFor = null }
    when (sheet) {
        EventSheet.None -> Unit
        EventSheet.Menu -> EventMenuSheet(e, host, onDismiss = close, open = { sheet = it })
        EventSheet.Take -> TakeSheet(e, tks, p, ticketFor, onPick = { ticketFor = it }, onDismiss = close)
        EventSheet.Tickets -> TicketsSheet(e, onDismiss = close)
        EventSheet.Guests -> GuestListSheet(e, onDismiss = close)
        EventSheet.Qr -> QrSheet(url, "URL of the event", onDismiss = close)
        EventSheet.CoHost -> CoHostSheet(e, onDismiss = close)
        EventSheet.Cancel -> CancelSheet(e, onDismiss = close)
        EventSheet.Delete -> ConfirmSheet(
            title = "Delete this event?",
            confirmLabel = "Delete",
            onConfirm = { session.events.delete(e); close(); nav.back() },
            onDismiss = close,
            message = if (e.isNew) "It disappears for everyone; its chat stays. To tell the guests, cancel it instead." else "It disappears from everyone's calendar.",
        )
    }
}

private fun endDay(world: World, e: CalEvent, now: Long): String {
    val end = e.end ?: return ""
    return if (world.dayOf(end) != world.dayOf(e.start)) " → " + ChatFormat.day(world, end, now) else ""
}

private fun lineDay(world: World, e: CalEvent, now: Long): String =
    ChatFormat.day(world, e.start, now) + if (e.allDay) "" else " " + CardFormat.whenText(world, e).substringBefore(" – ")

/** The event's one line (the channel header's pattern): what needs me, else when and where. */
@Composable
private fun EventLine(
    e: CalEvent, p: EventPeople, st: MyState, asking: Int, going: Int, open: Boolean, busy: BusyState,
    onTake: suspend () -> Unit, onReview: () -> Unit,
) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val c = Belfry.colors
    val now = session.now()
    val held = p.holders[world.me]
    Panel(Modifier.testTag("event-line")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(BelfryIcons.Calendar, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
            GapW(8)
            val (bold, rest) = when {
                e.status == EventStatus.CANCELLED -> "Cancelled" to (e.statusReason?.let { " · $it" } ?: "")
                world.stillGoing(e) -> "Changed" to " · still going?"
                asking > 0 -> "$asking ${if (asking == 1) "person asks" else "people ask"} to come" to " · $going going"
                st.line != null -> (st.line ?: "") to " · " + lineDay(world, e, now)
                else -> lineDay(world, e, now) to (if (!e.location.isNullOrEmpty()) " · " + e.location else if (e.links.isNotEmpty()) " · online" else "")
            }
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)); append(bold); pop(); append(rest)
                },
                Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            GapW(8)
            when {
                e.status != EventStatus.CANCELLED && world.stillGoing(e) -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BusyButton("Yes", onClick = { session.events.rsvp(e, Rsvp.ACCEPTED, held) }, small = true, busy = busy, modifier = Modifier.testTag("still-yes"))
                    BusyButton("No", onClick = { session.events.rsvp(e, Rsvp.DECLINED, held) }, small = true, kind = ButtonKind.Ghost, busy = busy)
                }
                e.status != EventStatus.CANCELLED && asking > 0 -> BusyButton("Review", onClick = { onReview() }, small = true, modifier = Modifier.testTag("line-review"))
                open && st == MyState.NONE -> BusyButton("RSVP", onClick = onTake, small = true, busy = busy, modifier = Modifier.testTag("line-rsvp"))
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(BelfryIcons.Users, contentDescription = "going", tint = c.muted, modifier = Modifier.size(16.dp))
                    GapW(4)
                    Text(going.toString(), fontSize = 13.sp, color = c.muted)
                }
            }
        }
    }
}

/** Going / Maybe / Can't go (the plain RSVP), or the ticket I hold, or my application. */
@Composable
private fun RsvpRow(e: CalEvent, p: EventPeople, tks: List<Ticket>, st: MyState, guest: Boolean, busy: BusyState, onPick: (Ticket) -> Unit, onTake: suspend () -> Unit) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val held = p.holders[world.me]
    val plain = tks.size == 1 && tks[0].implicit && !guest
    when {
        st == MyState.APPLIED || st == MyState.WAITLIST -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(st.line ?: "", Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp, color = Belfry.colors.text)
            GapW(8)
            BusyButton("Withdraw", onClick = { session.events.withdraw(e) }, small = true, kind = ButtonKind.Ghost, modifier = Modifier.testTag("withdraw"))
        }
        st == MyState.DECLINED -> Muted((st.line ?: "") + ".")
        !plain && held == null && st == MyState.NONE -> BusyButton(
            if (tks.size == 1) (if (tks[0].closed) "Apply" else "RSVP") else "Pick a ticket",
            onClick = { if (tks.size == 1) onPick(tks[0]) else onTake() },
            modifier = Modifier.fillMaxWidth().testTag("rsvp-take"), busy = busy,
        )
        else -> {
            val mine = e.rsvps[world.me] ?: if (held != null) Rsvp.ACCEPTED else null
            RsvpControl(mine, busy) { s -> session.events.rsvp(e, s, held) }
        }
    }
}

@Composable
internal fun TicketRow(t: Ticket, p: EventPeople, onPick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = Belfry.colors
    val left = p.placesLeft(t)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("ticket-" + t.id), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (t.implicit) "RSVP" else t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Muted((if (t.closed) "By application" else "Free") + (if (t.members) " · Members only" else "") + (if (left != null) " · $left left" else ""))
            if (t.summary.isNotEmpty()) Muted(t.summary)
        }
        if (onPick != null) {
            GapW(8)
            BusyButton(if (left == 0) "Waitlist" else if (t.closed) "Apply" else "RSVP", onClick = { onPick() }, small = true)
        }
        trailing?.invoke()
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

/** A person in the guest list: face, name, badges, a line under. */
@Composable
internal fun PersonRow(pubkey: String, sub: String?, host: Boolean, guest: Boolean, size: Dp = 34.dp, trailing: @Composable () -> Unit = {}) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Profile") { nav.go(Routes.profile(pubkey)) }) { Avatar(world.face(pubkey), size = size) }
        GapW(10)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(world.displayName(pubkey), Modifier.weight(1f, fill = false), fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (host) { GapW(6); Badge("Host") }
                if (guest) { GapW(6); Badge("Guest") }
            }
            if (!sub.isNullOrEmpty()) Muted(sub, maxLines = 3)
        }
        trailing()
    }
}
