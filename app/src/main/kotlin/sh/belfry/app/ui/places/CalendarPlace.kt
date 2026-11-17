package sh.belfry.app.ui.places

import androidx.compose.foundation.background
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
fun CalendarPlace() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val guestInfo by session.guest.collectAsState()
    val guest = world.amGuest || guestInfo?.reading == true
    val lists = if (guest) listOf(CalendarList.PUBLIC) else listOf(CalendarList.MINE, CalendarList.INVITED, CalendarList.DISCOVER)
    var tab by rememberSaveable { mutableStateOf(0) }
    var accounts by rememberSaveable { mutableStateOf(false) }
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
            actions = { if (!guest) IconAction(BelfryIcons.Plus, "New event", { nav.go(Routes.createEvent(Routes.NO_CHANNEL)) }) },
        )
        if (lists.size > 1) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.text) {
                lists.forEachIndexed { i, l ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(l.title, fontSize = 15.sp) }, selectedContentColor = c.text, unselectedContentColor = c.muted, modifier = Modifier.testTag("calendar-tab-$i"))
                }
            }
        }
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
                item(key = "<list>") {
                    Spacer(Modifier.height(6.dp))
                    PlaceCard {
                        events.forEachIndexed { i, e ->
                            if (i > 0) RowDivider()
                            EventRow(world, e, now) { nav.go(Routes.event(if (e.remote) e.link else e.coord)) }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (accounts) AccountsSheet(onDismiss = { accounts = false }, go = nav::go)
}

