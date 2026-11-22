package sh.belfry.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import sh.belfry.core.model.OneShape
import sh.belfry.core.model.myPods
import sh.belfry.core.model.townList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import sh.belfry.app.BuildConfig
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.chat.glyph
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ChannelIcon
import sh.belfry.app.ui.kit.CountBadge
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.icon
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Categories
import sh.belfry.core.model.CategoryRow
import sh.belfry.core.model.ChannelCategory
import sh.belfry.core.model.Group
import sh.belfry.core.model.PlaceNames
import sh.belfry.core.model.World
import sh.belfry.core.model.channelCategories
import sh.belfry.core.model.charterGroup
import sh.belfry.core.model.guestChannels
import sh.belfry.core.model.guestDaysLeft
import sh.belfry.core.model.memberKeys
import sh.belfry.core.model.myArchived
import sh.belfry.core.model.showsCharter
import sh.belfry.core.read.Unread

/** What the drawer can open. */
class DrawerActions(
    /** A channel of the town: shown in Town (never pushed). */
    val channel: (String) -> Unit,
    /** Today = Home. */
    val today: () -> Unit,
    /** Another place (a wide screen has no bottom bar: Calendar and Groups are rows here). */
    val place: (ShellState.Place) -> Unit,
    /** A screen pushed over the places (Members, the Charter, Archived, settings, Me). */
    val go: (String) -> Unit,
)

/**
 * The Town drawer (SPEC 19.31, 19.34; iOS TownDrawer): the belfry's name and the gear (stewards),
 * search, Today, The town (Townsquare, Members with their requests, the Charter), the categories
 * with a ＋ on their header, Archived, and me with the account switcher at the bottom. Every row
 * opens a screen. Stewards move channels with a long press and a drag (the order event is
 * published at once). A guest sees Apply, the channels they were invited to and what is open to
 * everyone. [wide]: the permanent column of a tablet (no bottom bar), so Calendar and Groups are rows.
 */
@Composable
fun ChannelDrawer(shell: ShellState, actions: DrawerActions, modifier: Modifier = Modifier, wide: Boolean = false) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val read by session.readState.collectAsState()
    val guestInfo by session.guest.collectAsState()
    val guest = world.amGuest || guestInfo?.reading == true
    val counts = rememberPlaceCounts()
    val now = session.now()
    val pins by session.pins.collectAsState()
    val townList = remember(world, pins, now / 3600) { world.townList(pins.c, now) }
    var accountsOpen by remember { mutableStateOf(false) }
    val onHome = shell.place == ShellState.Place.HOME
    val current = if (shell.place == ShellState.Place.TOWN) shell.channel else ""
    Column(modifier.fillMaxSize().background(c.bg)) {
        DrawerHeader(world, guest, actions, onCreate = if (wide && !guest) ({ shell.creating = true }) else null)
        Hairline()
        val brand = session.branding
        val pictures = androidx.compose.ui.platform.LocalInspectionMode.current.not() && sh.belfry.app.ui.kit.LocalNetworkImages.current
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("town-drawer"), state = rememberLazyListState()) {
            if (brand.cover.isNotEmpty() && pictures) item(key = "<cover>") {
                coil.compose.AsyncImage(
                    model = brand.cover, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
            // the places (nav2/drawer): Today · Messages · Mentions · Calendar (a wide column adds Groups: it has no bar)
            item(key = "<today>") {
                PlaceRow(Icons.Outlined.WbSunny, PlaceNames.TODAY, on = onHome, tag = "drawer-today", onClick = actions.today)
            }
            if (!guest) {
                item(key = "<messages>") {
                    PlaceRow(Icons.Outlined.ChatBubbleOutline, PlaceNames.MESSAGES, on = shell.place == ShellState.Place.MESSAGES, badge = counts.dmConvs, quietBadge = true, tag = "drawer-messages", onClick = { actions.place(ShellState.Place.MESSAGES) })
                }
                item(key = "<mentions>") {
                    PlaceRow(Icons.Outlined.AlternateEmail, PlaceNames.MENTIONS, badge = counts.mentions, tag = "drawer-mentions", onClick = { actions.go(Routes.MENTIONS) })
                }
            }
            item(key = "<calendar>") { PlaceRow(Icons.Outlined.CalendarMonth, PlaceNames.CALENDAR, on = shell.place == ShellState.Place.CALENDAR, tag = "drawer-calendar", onClick = { actions.place(ShellState.Place.CALENDAR) }) }
            if (wide && !guest) item(key = "<groups-place>") { PlaceRow(Icons.Outlined.Groups, PlaceNames.GROUPS, on = shell.place == ShellState.Place.GROUPS, onClick = { actions.place(ShellState.Place.GROUPS) }) }
            if (guest) guestRows(world, current, read, now, actions) else memberRows(world, current, read, now, counts, actions, townList)
            if (BuildConfig.DEBUG) {
                item(key = "<kit>") {
                    Text("Kit gallery (debug)", fontSize = 13.sp, color = c.faint, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { actions.go(Routes.KIT) }.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
        Hairline()
        YouRow(world, guest, now, onMe = { actions.go(Routes.ME) }, onAccounts = { accountsOpen = true })
    }
    // ⇅ opens the accounts on this device (switch, add)
    if (accountsOpen) AccountsSheet(onDismiss = { accountsOpen = false }, go = actions.go)
}

@Composable
private fun DrawerHeader(world: World, guest: Boolean, actions: DrawerActions, onCreate: (() -> Unit)? = null) {
    val c = Belfry.colors
    val session = LocalSession.current
    val name = world.ctx.communityName.ifEmpty { session.community.name }
    val brand = session.branding
    val pictures = androidx.compose.ui.platform.LocalInspectionMode.current.not() && sh.belfry.app.ui.kit.LocalNetworkImages.current
    val logo = brand.logo.takeIf { it.isNotEmpty() && pictures }
    Row(
        Modifier.fillMaxWidth().background(c.bg).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 10.dp)
            .windowInsetsPadding(WindowInsets.statusBars),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (logo != null) {
            coil.compose.AsyncImage(
                model = logo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(10.dp))
        } else {
            // the belfry's crest without a logo: its initial in a square (nav2/drawer)
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(c.text), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.bg)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val n = world.memberKeys().size
            val line = if (guest) listOfNotNull(PlaceNames.GUEST, world.guestDaysLeft(session.now())).joinToString(" · ") else "$n ${if (n == 1) "member" else "members"}"
            Text(line, fontSize = 13.sp, color = c.muted, maxLines = 1)
        }
        // search (nav2/drawer's header)
        IconAction(BelfryIcons.Search, "Search", { actions.go(Routes.search()) }, modifier = Modifier.testTag("drawer-search"))
        // a wide window has no bottom bar: Create is a plain ＋ here
        if (onCreate != null) IconAction(BelfryIcons.Plus, "Create", onCreate, modifier = Modifier.testTag("drawer-create"))
        if (!guest && world.isCommunityAdmin) {
            IconAction(Icons.Outlined.Settings, "Belfry settings", { actions.go(Routes.BELFRY_SETTINGS) }, modifier = Modifier.testTag("drawer-settings"))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.memberRows(
    world: World, current: String, read: sh.belfry.core.read.ReadState, now: Long, counts: PlaceCounts, actions: DrawerActions, list: OneShape.TownList,
) {
    // GROUPS (nav2/drawer): the groups I'm in, ＋ = a new group (anyone)
    val pods = world.myPods()
    item(key = "<groups>") { Section("Groups", plus = { actions.go(Routes.createPod(OneShape.MAIN)) }, plusLabel = "New group", tag = "drawer-groups-plus") }
    for (g in pods) item(key = "g:" + g.id) {
        ChannelRow(world, g, g.id == current, Unread.channel(world, read, g.id, now), onClick = { actions.go(Routes.pod(g.id)) })
    }
    // CHANNELS: pinned (the main channel, everyone's, then mine), then recent; ＋ = Add a channel
    item(key = "<channels>") { Section("Channels", plus = { actions.go(Routes.ADD_CHANNEL) }, plusLabel = "Add a channel", tag = "drawer-add-channel") }
    val pinned = list.pinned.toSet()
    for (id in list.list) {
        val g = world.group(id) ?: continue
        item(key = "c:$id") {
            ChannelRow(world, g, id == current, Unread.channel(world, read, id, now), pinned = id in pinned, onClick = { actions.channel(id) })
        }
    }
    // what the town had in this menu before Navigation v2: quiet rows under the channels
    item(key = "<town-rows>") { Spacer(Modifier.height(8.dp)) }
    item(key = "<members>") { PlaceRow(BelfryIcons.Users, PlaceNames.MEMBERS, badge = counts.requests, muted = true, tag = "drawer-members", onClick = { actions.go(Routes.MEMBERS) }) }
    if (world.showsCharter() && (world.charterGroup != null || world.isCommunityAdmin)) {
        item(key = "<charter>") { PlaceRow(Icons.Outlined.Description, PlaceNames.CHARTER, badge = counts.toAgree, muted = true, tag = "drawer-charter", onClick = { actions.go(Routes.CHARTER) }) }
    }
    if (world.myArchived().isNotEmpty()) {
        item(key = "<archived>") { PlaceRow(Icons.Outlined.Archive, PlaceNames.ARCHIVED, muted = true, tag = "drawer-archived", onClick = { actions.go(Routes.ARCHIVED) }) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.guestRows(
    world: World, current: String, read: sh.belfry.core.read.ReadState, now: Long, actions: DrawerActions,
) {
    item(key = "<apply>") {
        val session = LocalSession.current
        val apply by session.members.apply.collectAsState()
        val sent = apply?.application?.isOpen == true
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            BusyButton(if (sent) "Request sent" else "Apply to become a member", onClick = { actions.go(Routes.APPLY) }, modifier = Modifier.fillMaxWidth().testTag("drawer-apply"), enabled = !sent)
        }
    }
    val mine = world.guestChannels()
    if (mine.isNotEmpty()) {
        item(key = "<invited>") { Section("You were invited to") }
        for (g in mine) item(key = g.id) { ChannelRow(world, g, g.id == current, Unread.channel(world, read, g.id, now), onClick = { actions.channel(g.id) }) }
    }
    val mineIds = mine.map { it.id }.toSet()
    val open = world.groups.filter { it.type == sh.belfry.core.model.ChannelType.CHANNEL && world.isPublicChannel(it) && it.id !in mineIds }
    item(key = "<open>") { Section("Open to everyone") }
    item(key = "<charter>") { PlaceRow(Icons.Outlined.Description, PlaceNames.CHARTER, onClick = { actions.go(Routes.CHARTER) }) }
    for (g in open) item(key = g.id) { ChannelRow(world, g, g.id == current, 0, onClick = { actions.channel(g.id) }) }
    item(key = "<guest-note>") {
        Text(
            "Read-only for guests. Members also have the ${PlaceNames.defaults["general"]}, its events, groups and more.",
            fontSize = 13.sp, color = Belfry.colors.muted, modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Section(title: String, plus: (() -> Unit)? = null, plusLabel: String = "New channel in $title", tag: String? = null) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, color = c.muted, modifier = Modifier.weight(1f))
        if (plus != null) IconAction(BelfryIcons.Plus, plusLabel, plus, tint = c.muted, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
        else Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun PlaceRow(icon: ImageVector, title: String, onClick: () -> Unit, on: Boolean = false, badge: Int = 0, muted: Boolean = false, tag: String? = null, quietBadge: Boolean = false) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().background(if (on) c.surface else c.bg).clickable(role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { if (badge > 0) contentDescription = "$title, $badge new" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (muted) c.muted else c.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        // new things: bold and a count (never colour alone: e-ink)
        Text(title, fontSize = 16.sp, fontWeight = if (on || (quietBadge && badge > 0)) FontWeight.SemiBold else FontWeight.Normal, color = if (muted) c.muted else c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge > 0) { if (quietBadge) CountBadge(badge, color = c.surface2, onColor = c.text) else CountBadge(badge) }
    }
}

@Composable
private fun ChannelRow(world: World, g: Group, on: Boolean, unread: Int, onClick: () -> Unit, moving: Boolean = false, pinned: Boolean = false) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().background(if (on || moving) c.surface else c.bg).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp).semantics { if (unread > 0) contentDescription = "${g.name}, $unread new" + if (pinned) ", pinned" else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(g.glyph(world).icon(), contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        // unread (SPEC 19.27): the name stands out and a quiet count follows
        // a name not arrived: a grey bar for a few seconds, then the kind's word (SPEC 19.39)
        if (sh.belfry.app.ui.kit.rememberNameLoading(g.named)) Box(Modifier.weight(1f)) { sh.belfry.app.ui.kit.NameSkeleton() }
        else Text(g.name, fontSize = 16.sp, fontWeight = if (on || unread > 0) FontWeight.SemiBold else FontWeight.Normal, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (pinned) {
            Icon(BelfryIcons.Pin, contentDescription = null, tint = c.muted, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
        if (moving) Icon(Icons.Outlined.DragHandle, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
        else if (unread > 0) { Spacer(Modifier.width(6.dp)); CountBadge(unread, color = c.surface2, onColor = c.text) }
    }
}

/** Me: face, name, address (or "Guest · 7 days left"); a tap pushes Me; ⇅ opens the accounts. */
@Composable
private fun YouRow(world: World, guest: Boolean, now: Long, onMe: () -> Unit, onAccounts: () -> Unit) {
    val c = Belfry.colors
    val session = LocalSession.current
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Me") { onMe() }
                .padding(horizontal = 12.dp, vertical = 10.dp).testTag("drawer-me"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(world.face(world.me), size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(world.realName(world.me).ifEmpty { "You" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    guest -> listOfNotNull(PlaceNames.GUEST, world.guestDaysLeft(now)).joinToString(" · ")
                    session.localOnly -> "Demo: nothing leaves this device"
                    else -> world.myAddress ?: session.community.domain
                }
                Text(sub, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // something came for another account of this device
        val accounts = LocalAccounts.current
        Box {
            IconAction(Icons.Outlined.UnfoldMore, "Switch account or belfry", onAccounts, modifier = Modifier.testTag("drawer-switch"))
            if (accounts.others.any { it.id in accounts.unread }) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(10.dp).clip(CircleShape).background(c.badge))
            }
        }
    }
}
