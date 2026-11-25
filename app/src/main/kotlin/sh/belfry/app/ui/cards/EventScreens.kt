package sh.belfry.app.ui.cards

import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.Icons
import sh.belfry.core.model.Prices
import sh.belfry.core.model.waitingForPayment
import sh.belfry.core.model.mayCheckIn
import sh.belfry.core.model.onEventDays
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.kit.BackButton
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import sh.belfry.app.ui.kit.AvatarStack
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
import sh.belfry.core.model.eventPeopleList
import sh.belfry.core.model.eventRole
import sh.belfry.core.model.Rsvp
import sh.belfry.core.model.Ticket
import sh.belfry.core.model.World
import sh.belfry.core.model.eventByKey
import sh.belfry.core.model.eventPeople
import sh.belfry.core.model.eventUrl
import sh.belfry.core.model.eventsIn
import sh.belfry.core.model.isHost
import sh.belfry.core.model.showsMyTicket
import sh.belfry.core.model.isOpen
import sh.belfry.core.model.meetingPeople
import sh.belfry.core.model.myState
import sh.belfry.core.model.stateLabel
import sh.belfry.core.model.stillGoing
import sh.belfry.core.model.eventNeedsMe
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
            items(rows, key = { item ->
                when (item) {
                    is EventsItem.Day -> "day:" + item.label
                    is EventsItem.Row -> "event:" + item.e.coord
                    is EventsItem.Empty -> "<empty>"
                    is EventsItem.New -> "<new>"
                }
            }) { item ->
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
                if (e.meeting) { Badge("Meeting"); GapW(6) }
                Text(e.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Muted(CardFormat.dayAndWhen(world, e, LocalSession.current.now()) + (if (!e.location.isNullOrEmpty()) " · " + e.location else "") + (if (e.links.isNotEmpty()) " · online" else ""), maxLines = 1)
            Muted((if (s != null) "$s · " else "") + if (e.meeting) "${world.meetingPeople(e).size} people" else "${e.going} going", maxLines = 1)
        }
    }
}

/** An event's cover: a picture on the file server (when pictures load), or kept on the device (the demo). */
@Composable
internal fun EventCover(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val session = LocalSession.current
    val network = LocalNetworkImages.current
    if (url.startsWith(Session.LOCAL_BLOB)) {
        var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(url) { bitmap = withContext(Dispatchers.Default) { session.localBlob(url)?.let { Photos.decodeBytes(it) } } }
        bitmap?.let { Image(it, contentDescription = null, contentScale = contentScale, modifier = modifier.testTag("cover-loaded")) } ?: Box(modifier.background(Belfry.colors.surface2))
    } else if (url.startsWith("https://") && network) {
        AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = modifier)
    } else {
        Box(modifier.background(Belfry.colors.surface2))
    }
}

/**
 * One event (#/events/<link>): KEY is its coordinate or the last part of its URL. Ours when the
 * relay gave it to us, else the belfry's answer (a guest can't read the community relay).
 */
@Composable
fun EventScreen(key: String, ticket: String? = null) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val local = world.eventByKey(key)
    if (local != null) return EventView(local, null, ticket)
    val remote by session.events.remote.collectAsState()
    val isLink = !key.contains(':')
    LaunchedEffect(key) { if (isLink) session.events.fetch(key) }
    val got = remote[key]
    val api = got?.api
    if (api != null) return EventView(api.toCalEvent(world.ctx.zone, world.me), api, ticket)
    // a guest of this belfry (SPEC 19.71): the words of the signed-out screen and the two ways in
    val guest by session.guest.collectAsState()
    if (isLink && got?.error == "members_only" && (guest != null || world.amGuest)) {
        val accounts = sh.belfry.app.ui.nav.LocalAccounts.current
        val name = world.ctx.communityName.ifEmpty { session.community.name }
        CardScreen(name.ifBlank { "Event" }) {
            sh.belfry.app.ui.signin.MembersOnlyBody(name, onSignIn = { accounts.wayIn(key, false) }, onApply = { accounts.wayIn(key, true) }, modifier = Modifier.weight(1f))
        }
        return
    }
    val title = when {
        !isLink -> "This event is not here (yet)"
        got == null -> "Opening the event…"
        got.error == "members_only" -> "This event is for members."
        else -> "There is no such event here."
    }
    CardScreen("Event") { EmptyState(title, text = if (!isLink) "It may still be syncing." else null, icon = BelfryIcons.Calendar) }
}

/** The sheets of an event's screen. */
internal enum class EventSheet { None, Menu, Take, Tickets, Guests, Qr, CoHost, Invite, Cancel, Delete }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventView(e: CalEvent, api: ApiEvent?, ticket: String? = null) {
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

    // One shape (SPEC 19.54; canvas one-shape/event-default, -tiny, -full): three states over the event's chat.
    // Mid (HALF) while it needs me (not answered, still going?, people asking); tiny (UP) once answered.
    // Every event opens mid: the details on top, its chat below (Xavier, 2026-11-22; a meeting
    // too). An older calendar event (no chat of its own) still opens whole.
    var level by rememberSaveable(e.coord) { mutableStateOf(if (e.isNew) EventLevel.HALF else EventLevel.FULL) }
    val full = level == EventLevel.FULL
    val viewer = rememberViewer(e)
    val mayCheck = viewer?.mayCheckIn ?: world.mayCheckIn(e)
    var ticketOpen by remember(e.coord) { mutableStateOf(false) }
    var people by rememberSaveable(e.coord) { mutableStateOf(false) }
    var cardOpen by remember(ticket) { mutableStateOf(true) }
    val (hostList, goingList, maybeList) = world.eventPeopleList(e)
    // a meeting: who made it and the co-hosts they named, never the belfry's admins or the door
    val hostsShown = if (e.meeting) hostList.filter { world.eventRole(e, it) == sh.belfry.core.model.EventRole.HOST || world.eventRole(e, it) == sh.belfry.core.model.EventRole.COHOST }
        else api?.hostedBy?.map { it.first }?.ifEmpty { null } ?: hostList.ifEmpty { e.hosts.toList() }
    val items = eventRowItems(e, st, if (e.meeting) world.meetingPeople(e).size else going)
    val onMore = { sheet = EventSheet.Menu }
    val onBack = { nav.back() }
    // scrolling the feed folds mid to tiny (one-shape/event-tiny)
    val fold = remember(e.coord) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (level == EventLevel.HALF && kotlin.math.abs(available.y) > 6f) level = EventLevel.UP
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    if (people) return EventPeopleScreen(e) { people = false }

    @Composable
    fun Faces() {
        if (!e.meeting && (goingList.isNotEmpty() || maybeList.isNotEmpty())) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "See who is coming") { people = true }
                .padding(vertical = 4.dp).testTag("event-faces"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarStack((hostList + goingList).distinct().map { world.face(it) }, size = 28.dp, max = 5)
            GapW(8)
            Text("${goingList.size} going" + if (maybeList.isNotEmpty()) " · ${maybeList.size} maybe" else "", fontSize = 15.sp, color = c.text, modifier = Modifier.weight(1f))
            Icon(BelfryIcons.Chevron, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
    }

    @Composable
    fun Answer() {
        // what needs me beyond the answer (cancelled, still going?, people asking)
        if (e.status == EventStatus.CANCELLED || world.stillGoing(e) || asking > 0)
            EventLine(e, p, st, asking, if (e.meeting) world.meetingPeople(e).size else going, open, busy, onTake = take, onReview = { sheet = EventSheet.Guests })
        // Going / Maybe / Can't go always there while it is open (my answer marked), else my answer in words
        if (open && !plain && st == MyState.NONE) Column(Modifier.testTag("event-tickets")) {
            // no ticket yet: the tickets themselves, each with its button (not one "Pick a ticket")
            SectionTitle("Tickets")
            for (t in tks) TicketRow(t, p, onPick = { pick(t) })
        }
        else if (open) RsvpRow(e, p, tks, st, guest, busy, onPick = pick, onTake = take)
        else (if (st == MyState.APPLIED && world.waitingForPayment(e, p)) "Waiting for the host to confirm your payment" else st.line)?.let { line ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("event-mine")) {
                Icon(BelfryIcons.Check, contentDescription = null, tint = c.text, modifier = Modifier.size(18.dp))
                GapW(8)
                Text(line, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
        }
        // my ticket and, on the event's days, checking people in (SPEC 19.29): one wide button each.
        // A meeting has no ticket: its host checks people in from the list.
        val code = viewer?.ticketUrl
        if (e.meeting) return
        // hosts never: their own event is no ticket to show (Xavier, 2026-11-24)
        if (code != null && world.showsMyTicket(e)) BusyButton("Show my ticket", onClick = { ticketOpen = true }, icon = BelfryIcons.Qr, modifier = Modifier.fillMaxWidth().testTag("event-ticket"))
        if (mayCheck && world.onEventDays(e, now)) {
            BusyButton("Check people in", onClick = { nav.go(Routes.checkIn(e.coord)) }, kind = ButtonKind.Ghost, icon = BelfryIcons.Qr, modifier = Modifier.fillMaxWidth().testTag("event-checkin"))
        }
    }

    @Composable
    fun When() {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("event-when")) {
            Icon(BelfryIcons.Calendar, contentDescription = "When", tint = c.text, modifier = Modifier.size(18.dp))
            GapW(8)
            Text(CardFormat.dayAndWhen(world, e, now), fontSize = 16.sp, color = c.text)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).testTag("event-screen-${level.name.lowercase()}")) {
        // tiny: two header lines, the title then the icon row (my answer leads); a tap opens mid
        if (level == EventLevel.UP) EventTinyHeader(e, items, onBack, onMore, onTap = { level = EventLevel.HALF })
        // mid and full share one top (the cover with ‹ ••• on it, or the usual header, then the title):
        // pulling down only lets more show under it
        else Column(
            (if (full) Modifier.weight(1f) else Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.68f).dp)).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("event-top-scroll"),
        ) {
                val cover = e.cover
                if (cover != null) EventCoverTop(cover, onBack, onMore)
                else TopBar("", navigation = { BackButton(onBack) }, actions = { IconAction(BelfryIcons.More, "More", onMore, Modifier.testTag("event-more")) })
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("event-details"), verticalArrangement = Arrangement.spacedBy(if (full) 14.dp else 10.dp)) {
                    if (ticket != null && mayCheck && cardOpen) TicketLinkCard(e, ticket, onClose = { cardOpen = false })
                    Text(e.title, fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.testTag("event-title"))
                    if (!full) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        When()
                        EventWhere(e)
                        if (e.links.isEmpty() && api?.online == true) Muted("Online: the link shows once you have a place.")
                    }
                    EventHostedBy(e, hostsShown, api)
                    Faces()
                    if (e.meeting && !e.remote) MeetingFaces(e) { level = EventLevel.FULL }
                    if (e.description.isNotEmpty()) {
                        Text(
                            sh.belfry.core.markdown.Markdown.plain(e.description), fontSize = 15.sp, lineHeight = 21.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Show everything") { level = EventLevel.FULL },
                        )
                        Text(
                            "More", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { level = EventLevel.FULL }.padding(vertical = 4.dp).testTag("event-more-details"),
                        )
                    }
                    Answer()
                    } else {
            // full (one-shape/event-full): where (with Directions), hosts, the whole description, who comes, to-dos and files, the answer
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                When()
                EventWhere(e, directions = true)
                if (e.links.isEmpty() && api?.online == true) Muted("Online: the link shows once you have a place.")
            }
            EventHostedBy(e, hostsShown, api)
            if (e.description.isNotEmpty()) MarkdownText(e.description, rich = false)
            Faces()
            EventContentsLine(e)
            Answer()
            // a meeting: who is invited, what they answered, who was there (hosts check people in)
            if (e.meeting && !e.remote) MeetingPeople(e, host)

            // (while I have none and it is open, the tickets are in the answer above, with their buttons)
            if (!plain && !(open && st == MyState.NONE)) {
                Column {
                    SectionTitle("Tickets")
                    for (t in tks) TicketRow(t, p, onPick = null)
                }
            }

            // canvas v113 events/event-full-address: the address, then Copy link · Share… (the QR code is in the event's menu)
            if (e.isNew) {
                Column(Modifier.testTag("event-url")) {
                    SectionTitle("Address")
                    // the whole address on one line: it scrolls sideways when it is longer than the screen (Xavier's word)
                    Text(
                        url, fontSize = 14.sp, color = c.muted, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1, softWrap = false,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("event-address"),
                    )
                    Gap(10)
                    sh.belfry.app.ui.kit.CopyShareButtons(url, e.title, copyTag = "event-copy", shareTag = "event-share", copyLabel = "Copy", shareLabel = "Share") {
                        BusyButton(
                            "QR", onClick = { sheet = EventSheet.Qr }, kind = ButtonKind.Ghost, icon = BelfryIcons.Qr,
                            modifier = Modifier.weight(1f).height(sh.belfry.app.ui.kit.COPY_SHARE_HEIGHT).testTag("event-qr"),
                        )
                    }
                }
            }

            if (host && e.isNew) {
                Column {
                    SectionTitle("For hosts")
                    ActionGroup {
                        ActionRow(
                            "Guest list", onClick = { sheet = EventSheet.Guests }, modifier = Modifier.testTag("event-guests"), icon = BelfryIcons.Users,
                            sub = "$going going" + if (asking > 0) " · $asking asking" else "", chevron = true,
                        )
                        ActionRow("Edit the event", onClick = { nav.go(Routes.editEvent(e.coord)) }, modifier = Modifier.testTag("event-edit"), icon = BelfryIcons.Edit, chevron = true)
                    }
                }
            }

            ActionGroup {
                when {
                    g != null && inside -> ActionRow("Open the event's chat", onClick = { level = EventLevel.HALF }, modifier = Modifier.testTag("event-chat"), icon = BelfryIcons.Thread, sub = "Members, files, todos", chevron = true)
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
            if (!e.isNew && e.author == world.me) {
                ActionGroup { ActionRow("Delete event", onClick = { sheet = EventSheet.Delete }, modifier = Modifier.testTag("event-delete"), icon = BelfryIcons.Trash, danger = true) }
            }
        }
                }
        }
        // the handle: a drag up / down moves a level; so do the arrows at its ends (up: full → mid →
        // tiny, down: tiny → mid → full), which need no gesture; a tap on the bar goes on to the next
        Row(
            Modifier.fillMaxWidth().height(40.dp)
                .pointerInput(Unit) {
                    var moved = 0f
                    detectVerticalDragGestures(onDragStart = { moved = 0f }, onDragEnd = {
                        if (moved < -16f) level = level.up() else if (moved > 16f) level = level.down()
                    }) { _, dy -> moved += dy }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                if (level != EventLevel.UP) IconAction(BelfryIcons.ChevronUp, "Show less of the event", { level = level.up() }, Modifier.testTag("event-less"))
            }
            Box(
                Modifier.weight(1f).height(40.dp)
                    .clickable(role = Role.Button, onClickLabel = "Show more or less of the event") { level = level.next() }
                    .testTag("event-handle"),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) }
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                if (level != EventLevel.FULL) IconAction(BelfryIcons.ChevronDown, "Show more of the event", { level = level.down() }, Modifier.testTag("event-more-level"))
            }
        }
        Hairline()
        // the conversation continues below (mid and tiny)
        if (!full) {
            if (inside && g != null) Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(fold)) {
                sh.belfry.app.ui.chat.ChannelScreen(e.groupId, embedded = true, emptyContent = if (e.isNew) ({ EventQuickMenu(e, host, open = { sheet = it }) }) else null)
            }
            else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (e.description.isNotEmpty()) MarkdownText(e.description, rich = false)
                else Muted("Get a place to see the event's chat, photos and people.")
            }
        }
    }
    if (ticketOpen) viewer?.ticketUrl?.let { TicketSheet(e, it, viewer.checkedIn) { ticketOpen = false } }

    val close = { sheet = EventSheet.None; ticketFor = null }
    when (sheet) {
        EventSheet.None -> Unit
        EventSheet.Menu -> EventMenuSheet(e, host, onDismiss = close, open = { sheet = it })
        EventSheet.Take -> TakeSheet(e, tks, p, ticketFor, onPick = { ticketFor = it }, onDismiss = close)
        EventSheet.Tickets -> TicketsSheet(e, onDismiss = close)
        EventSheet.Guests -> GuestListSheet(e, onDismiss = close)
        EventSheet.Qr -> QrSheet(url, "URL of the event", onDismiss = close)
        EventSheet.CoHost -> CoHostSheet(e, onDismiss = close)
        EventSheet.Invite -> EventInviteSheet(e, onDismiss = close)
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
            val price = Prices.line(t.cost, LocalWorld.current.ctx.zone)
            if (t.cost.priced) Text(price, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.testTag("ticket-price"))
            Muted((if (t.cost.priced) (if (t.closed) "By application" else null) else if (t.closed) "By application" else "Free")?.let { it + (if (t.members) " · Members only" else "") + (if (left != null) " · $left left" else "") }
                ?: listOfNotNull(if (t.members) "Members only" else null, if (left != null) "$left left" else null).joinToString(" · "))
            if (t.summary.isNotEmpty()) Muted(t.summary)
        }
        val checkout = t.checkoutUrl
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        if (onPick != null && checkout != null && left != 0) {
            GapW(8)
            // bought on the community's checkout page, in the browser (canvas events/get-tickets v38)
            BusyButton(
                "Buy", onClick = { try { uri.openUri(checkout) } catch (e: Exception) { } }, small = true,
                icon = Icons.AutoMirrored.Outlined.OpenInNew, modifier = Modifier.testTag("ticket-buy-" + t.id),
            )
        } else if (onPick != null) {
            GapW(8)
            // a priced ticket is asked for, never taken; not on sale now: the button is off
            BusyButton(
                when { left == 0 -> "Waitlist"; t.cost.priced -> "Ask"; t.closed -> "Apply"; else -> "RSVP" },
                onClick = { onPick() }, small = true, enabled = t.cost.onSale, kind = if (t.cost.priced && !t.closed || !t.asks) ButtonKind.Primary else ButtonKind.Ghost,
                modifier = Modifier.testTag("ticket-take-" + t.id),
            )
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
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(if (on) c.accent else c.bg)
                    .border(1.dp, if (on) c.accent else c.line, shape)
                    .alpha(if (busy.busy && tapped != s) 0.5f else 1f)
                    .semantics { selected = on }
                    .clickable(enabled = !busy.busy, role = Role.Button) {
                        // the answer I gave already: nothing to send again
                        if (mine != s) {
                            tapped = s
                            busy.run { onAnswer(s) }
                        }
                    }
                    .testTag("rsvp-" + s.key)
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (busy.busy && tapped == s) {
                    Spinner(color = if (on) c.onAccent else c.muted)
                } else {
                    // my answer: filled, a tick and bold (e-ink: never the fill alone)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (on) { Icon(BelfryIcons.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(16.dp)); GapW(4) }
                        Text(s.label, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) c.onAccent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
