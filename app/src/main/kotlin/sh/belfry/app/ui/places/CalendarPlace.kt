package sh.belfry.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.core.model.onDay
import sh.belfry.core.model.eventStatus
import sh.belfry.core.model.isHost
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.shell.AccountsSheet
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.CalendarList
import sh.belfry.core.model.calendarList

/**
 * Calendar (SPEC 19.31): My calendar (hosting, going, maybe, applied) · Invited (a meeting I was
 * asked to and haven't answered) · Discover (the rest). A guest: the public events. Rows say the
 * state in words; a tap opens the event. The belfry's name on top opens the account switcher.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CalendarPlace(onMenu: (() -> Unit)? = null, menuBadge: Int = 0) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val guestInfo by session.guest.collectAsState()
    val guest = world.amGuest || guestInfo?.reading == true
    val lists = if (guest) listOf(CalendarList.PUBLIC) else listOf(CalendarList.MINE, CalendarList.INVITED, CalendarList.DISCOVER)
    var tab by rememberSaveable { mutableStateOf(0) }
    var accounts by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val now = session.now()
    val remote by session.members.publicEvents.collectAsState()
    if (guest) LaunchedEffect(session) { try { session.members.refreshPublicEvents() } catch (e: Exception) { } }
    val list = lists[tab.coerceIn(0, lists.size - 1)]
    val events = world.calendarList(list, now, remote.map { it.toCalEvent(world.ctx.zone, world.me) })
    Column(Modifier.fillMaxSize().background(c.bg).testTag("calendar")) {
        TopBar(
            "Calendar",
            subtitle = world.ctx.communityName.ifEmpty { session.community.name },
            onTitleClick = { accounts = true },
            navigation = if (onMenu != null) ({ IconAction(BelfryIcons.Menu, "Menu", onMenu, badge = menuBadge) }) else null,
            actions = { if (!guest) IconAction(BelfryIcons.Plus, "New", { adding = true }, Modifier.testTag("calendar-add")) },
        )
        if (lists.size > 1) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.text) {
                lists.forEachIndexed { i, l ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(l.title, fontSize = 15.sp) }, selectedContentColor = c.text, unselectedContentColor = c.muted, modifier = Modifier.testTag("calendar-tab-$i"))
                }
            }
        }
        // events/calendar: the week, a day picked (today first), then that day and what comes later
        val today = world.dayOf(now)
        var picked by rememberSaveable { mutableStateOf(today.toEpochDay()) }
        val day = java.time.LocalDate.ofEpochDay(picked)
        WeekStrip(day, today, events.map { world.dayOf(it.start) }.toSet()) { picked = it.toEpochDay() }
        Hairline()
        // an event of several days (or going on since an earlier day) shows on each of its days
        val onDay = events.filter { world.onDay(it, day) }
        val later = events.filter { world.dayOf(it.start) > day }
        if (events.isEmpty()) {
            EmptyState(
                when (list) {
                    CalendarList.MINE -> "Nothing planned for you yet"
                    CalendarList.INVITED -> "No invitations waiting"
                    CalendarList.DISCOVER -> "Nothing else coming up"
                    CalendarList.PUBLIC -> "No public events coming up"
                },
                icon = BelfryIcons.Calendar,
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item(key = "<day>") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                        Text(if (day == today) "Today" else DAY_TITLE.format(day), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f))
                        Text(WEEKDAY.format(day), fontSize = 14.sp, color = c.muted)
                    }
                    if (onDay.isEmpty()) Text("Nothing on this day.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    else PlaceCard {
                        onDay.forEachIndexed { i, e ->
                            if (i > 0) RowDivider()
                            TimeEventRow(world, e, now, withDay = false) { nav.go(Routes.event(if (e.remote) e.link else e.coord)) }
                        }
                    }
                }
                if (later.isNotEmpty()) item(key = "<later>") {
                    Text("Later", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp))
                    PlaceCard {
                        later.forEachIndexed { i, e ->
                            if (i > 0) RowDivider()
                            TimeEventRow(world, e, now, withDay = true) { nav.go(Routes.event(if (e.remote) e.link else e.coord)) }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (accounts) AccountsSheet(onDismiss = { accounts = false }, go = nav::go)
    // the ＋: a public event or a meeting (Xavier, 2026-11-22)
    if (adding) sh.belfry.app.ui.kit.BelfrySheet(onDismiss = { adding = false }, title = "New", modifier = Modifier.testTag("calendar-new")) {
        sh.belfry.app.ui.kit.ActionGroup {
            sh.belfry.app.ui.kit.ActionRow(
                "Event", onClick = { adding = false; nav.go(Routes.createEvent(Routes.NO_CHANNEL)) }, icon = BelfryIcons.Calendar,
                sub = "Public or for members, with its own page and tickets", modifier = Modifier.testTag("calendar-new-event"),
            )
            sh.belfry.app.ui.kit.ActionRow(
                "Meeting", onClick = { adding = false; nav.go(Routes.createMeeting(Routes.NO_CHANNEL)) }, icon = BelfryIcons.Users,
                sub = "A topic, people, a time; only for those invited", modifier = Modifier.testTag("calendar-new-meeting"),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}


private val MONTH_YEAR = java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.ENGLISH)
private val DAY_TITLE = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.ENGLISH)
private val WEEKDAY = java.time.format.DateTimeFormatter.ofPattern("EEEE d", java.util.Locale.ENGLISH)
private val SHORT_DAY = java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.ENGLISH)

/** The week of DAY (Monday first) with ‹ ›: the picked day filled, today underlined, a dot under days with events. */
@Composable
private fun WeekStrip(day: java.time.LocalDate, today: java.time.LocalDate, busy: Set<java.time.LocalDate>, onPick: (java.time.LocalDate) -> Unit) {
    val c = Belfry.colors
    val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("week-strip")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(MONTH_YEAR.format(day), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconAction(BelfryIcons.Back, "Week before", { onPick(day.minusWeeks(1)) })
            IconAction(BelfryIcons.Chevron, "Week after", { onPick(day.plusWeeks(1)) })
        }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0..6) {
                val d = monday.plusDays(i.toLong())
                val on = d == day
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).semantics { selected = on }.clickable(role = Role.Tab) { onPick(d) }.padding(vertical = 6.dp)
                        .testTag("week-day-$i"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(SHORT_DAY.format(d).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(36.dp).clip(CircleShape).then(if (on) Modifier.background(c.text) else Modifier), contentAlignment = Alignment.Center) {
                        Text(d.dayOfMonth.toString(), fontSize = 17.sp, fontWeight = if (d == today || on) FontWeight.Bold else FontWeight.Medium, color = if (on) c.bg else c.text,
                            textDecoration = if (d == today && !on) androidx.compose.ui.text.style.TextDecoration.Underline else null)
                    }
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (d in busy) c.text else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
    }
}

/** An event as the calendar's board shows it: start and end on the left, a bar, the title and where / my state. */
@Composable
private fun TimeEventRow(world: sh.belfry.core.model.World, e: sh.belfry.core.model.CalEvent, now: Long, withDay: Boolean, onClick: () -> Unit) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(64.dp)) {
            if (withDay) Text(SHORT_DAY.format(world.dayOf(e.start)), fontSize = 14.sp, color = c.text)
            Text(if (e.allDay) "All day" else ChatFormat.time(world, e.start), fontSize = 14.sp, color = c.text)
            if (!withDay && !e.allDay) e.end?.let { Text(ChatFormat.time(world, it), fontSize = 14.sp, color = c.muted) }
        }
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (world.isHost(e)) c.orange else c.text))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(e.location?.ifEmpty { null }, world.eventStatus(e, now)).joinToString(" · "), fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(BelfryIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
    }
}
