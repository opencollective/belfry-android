package sh.belfry.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.glyph
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.kit.icon
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.shell.LocalShell
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.app.ui.shell.rememberPlaceCounts
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.comingUp
import sh.belfry.core.model.charterToAgree
import sh.belfry.core.model.guestChannels
import sh.belfry.core.model.guestDaysLeft
import sh.belfry.core.model.myTodos
import sh.belfry.core.model.unreadPlaces
import sh.belfry.core.model.calendarList
import sh.belfry.core.model.CalendarList
import sh.belfry.core.read.Unread
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TODAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

/**
 * Home (SPEC 19.31; iOS HomeView): the date as the title and ☰; the To-dos, Mentions and Messages
 * tiles (always there, "Nothing new" at zero); what waits for me (requests, a new agreement);
 * the events coming up in the next 7 days I'm part of; the places with something unread. A guest's
 * Home: their request, the channels they were invited to, the public events.
 */
@Composable
fun HomePlace(onMenu: (() -> Unit)?, menuBadge: Int = 0, onCreate: (() -> Unit)? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val shell = LocalShell.current
    val dms = LocalDms.current
    val read by session.readState.collectAsState()
    val guestInfo by session.guest.collectAsState()
    val guest = world.amGuest || guestInfo?.reading == true
    val counts = rememberPlaceCounts()
    val now = session.now()
    val title = TODAY.format(Instant.ofEpochSecond(now).atZone(world.ctx.zone))
    Column(Modifier.fillMaxSize().background(c.bg).testTag("home")) {
        TopBar(
            title,
            navigation = if (onMenu != null) ({ IconAction(BelfryIcons.Menu, "The town", onMenu, badge = menuBadge) }) else null,
            actions = { if (onCreate != null && !guest) IconAction(BelfryIcons.Plus, "Create", onCreate) },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (guest) {
                guestHome(this, now)
                return@LazyColumn
            }
            item(key = "<tiles>") {
                val (onMe, open) = world.myTodos()
                val unreadConvs = dms.threads.filter { it.unread > 0 }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(
                        Icons.Outlined.TaskAlt, "To-dos", onMe.size,
                        when {
                            onMe.isNotEmpty() -> "on you" + if (open.isNotEmpty()) " · ${open.size} open" else ""
                            open.isNotEmpty() -> "${open.size} open"
                            else -> "Nothing new"
                        },
                        Modifier.weight(1f).testTag("tile-todos"),
                    ) { nav.go(Routes.MY_TODOS) }
                    Tile(Icons.Outlined.AlternateEmail, "Mentions", counts.mentions, if (counts.mentions > 0) "${counts.mentions} new" else "Nothing new", Modifier.weight(1f).testTag("tile-mentions")) { nav.go(Routes.MENTIONS) }
                    Tile(
                        Icons.Outlined.ChatBubbleOutline, "Messages", unreadConvs.size,
                        if (unreadConvs.isEmpty()) "Nothing new" else unreadConvs.take(2).joinToString(", ") { dms.convName(it.conv, world) } + if (unreadConvs.size > 2) " +${unreadConvs.size - 2}" else "",
                        Modifier.weight(1f).testTag("tile-messages"),
                    ) { nav.go(Routes.MESSAGES) }
                }
            }
            if (counts.requests > 0) item(key = "<requests>") {
                PlaceCard { NavRow(BelfryIcons.Users, "${counts.requests} asking to join", requestLine(session), { nav.go(Routes.MEMBERS) }, badge = counts.requests, modifier = Modifier.testTag("home-requests")) }
            }
            val toAgree = world.charterToAgree()
            if (toAgree.isNotEmpty()) item(key = "<charter>") {
                val n = toAgree.size
                PlaceCard { NavRow(Icons.Outlined.Description, "$n new ${if (n == 1) "agreement" else "agreements"} to read", "Charter: " + toAgree.first().title, { nav.go(Routes.CHARTER) }, badge = n) }
            }
            val coming = world.comingUp(now)
            item(key = "<coming-title>") { PlaceSection("Coming up", "Calendar") { shell?.place = ShellState.Place.CALENDAR } }
            if (coming.isEmpty()) {
                item(key = "<coming-none>") { Text("Nothing in the next 7 days.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
            } else item(key = "<coming>") {
                PlaceCard {
                    coming.forEachIndexed { i, e ->
                        if (i > 0) RowDivider()
                        EventRow(world, e, now) { nav.go(Routes.event(e.coord)) }
                    }
                }
            }
            val unread = world.unreadPlaces { Unread.channel(world, read, it, now) }
            if (unread.isNotEmpty()) {
                item(key = "<unread-title>") { PlaceSection("Unread") }
                item(key = "<unread>") {
                    PlaceCard {
                        unread.forEachIndexed { i, g ->
                            if (i > 0) RowDivider()
                            NavRow(g.glyph(world).icon(), g.name, null, { nav.openChannel(g.id) }, badge = Unread.channel(world, read, g.id, now))
                        }
                    }
                }
            }
            item(key = "<end>") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun requestLine(session: sh.belfry.core.engine.Session): String {
    val people = session.members.requests.value.map { it.name }.filter { it.isNotEmpty() }
    val agents = session.members.agentRequests.value.map { (it.name.ifEmpty { "An agent" }) + ", an agent" }
    return (agents + people).joinToString(" · ").ifEmpty { "Members, groups and agents" }
}

private fun guestHome(scope: androidx.compose.foundation.lazy.LazyListScope, now: Long) = with(scope) {
    item(key = "<guest-apply>") {
        val session = LocalSession.current
        val nav = LocalNav.current
        val world = LocalWorld.current
        val apply by session.members.apply.collectAsState()
        LaunchedEffect(session) { try { session.members.refreshApply() } catch (e: Exception) { } }
        PlaceCard {
            Column(Modifier.padding(16.dp)) {
                val line = apply?.line
                Text(if (line != null) "Your request" else "Become a member", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    line ?: ("You are a guest" + (world.guestDaysLeft(now)?.let { " · $it" } ?: "") + ". Members have the town, its events and groups."),
                    fontSize = 15.sp, color = Belfry.colors.muted,
                )
                if (apply?.application?.isOpen != true && apply?.application?.state != "accepted") {
                    Spacer(Modifier.height(12.dp))
                    BusyButton("Apply to become a member", onClick = { nav.go(Routes.APPLY) }, modifier = Modifier.fillMaxWidth().testTag("home-apply"))
                }
            }
        }
    }
    item(key = "<guest-channels>") {
        val world = LocalWorld.current
        val nav = LocalNav.current
        val mine = world.guestChannels()
        if (mine.isNotEmpty()) {
            PlaceSection("You were invited to")
            PlaceCard {
                mine.forEachIndexed { i, g ->
                    if (i > 0) RowDivider()
                    NavRow(g.glyph(world).icon(), g.name, g.about.takeIf { it.isNotEmpty() }, { nav.openChannel(g.id) })
                }
            }
        }
    }
    item(key = "<guest-events>") {
        val world = LocalWorld.current
        val nav = LocalNav.current
        val session = LocalSession.current
        val remote by session.members.publicEvents.collectAsState()
        LaunchedEffect(session) { try { session.members.refreshPublicEvents() } catch (e: Exception) { } }
        val list = world.calendarList(CalendarList.PUBLIC, now, remote.map { it.toCalEvent(world.ctx.zone, world.me) }).take(5)
        if (list.isNotEmpty()) {
            PlaceSection("Coming up")
            PlaceCard {
                list.forEachIndexed { i, e ->
                    if (i > 0) RowDivider()
                    EventRow(world, e, now) { nav.go(Routes.event(e.link)) }
                }
            }
        }
    }
}

/** A tile: an icon, the count, the title, a line ("Nothing new" at zero). Always there. */
@Composable
private fun Tile(icon: ImageVector, title: String, count: Int, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Belfry.colors
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).background(c.bg)
            .clickable(role = Role.Button, onClick = onClick).padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title: " + if (count > 0) "$count, $sub" else sub },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(if (count > 0) c.accent else c.surface), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = if (count > 0) c.onAccent else c.text, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            if (count > 0) Text(count.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, fontSize = 13.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

