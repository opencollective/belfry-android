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
            // a laptop / tablet list has no search field (web cbc57a3): search is in each channel's header
            if (!wide) item(key = "<search>") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(24.dp)).background(c.surface)
                        .clickable(role = Role.Button) { actions.go(Routes.search(null)) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Search the town", fontSize = 16.sp, color = c.muted)
                }
            }
            item(key = "<today>") {
                PlaceRow(Icons.Outlined.WbSunny, PlaceNames.TODAY, on = onHome, badge = counts.home(guest), tag = "drawer-today", onClick = actions.today)
            }
            if (wide) {
                item(key = "<calendar>") { PlaceRow(Icons.Outlined.CalendarMonth, PlaceNames.CALENDAR, on = shell.place == ShellState.Place.CALENDAR, onClick = { actions.place(ShellState.Place.CALENDAR) }) }
                if (!guest) item(key = "<groups>") { PlaceRow(Icons.Outlined.Groups, PlaceNames.GROUPS, on = shell.place == ShellState.Place.GROUPS, onClick = { actions.place(ShellState.Place.GROUPS) }) }
            }
            if (guest) guestRows(world, current, read, now, actions) else memberRows(world, current, read, now, counts, actions, session)
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
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val n = world.memberKeys().size
            val line = if (guest) listOfNotNull(PlaceNames.GUEST, world.guestDaysLeft(session.now())).joinToString(" · ") else "$n ${if (n == 1) "member" else "members"}"
            Text(line, fontSize = 13.sp, color = c.muted, maxLines = 1)
        }
        // a wide window has no bottom bar: Create is a plain ＋ here
        if (onCreate != null) IconAction(BelfryIcons.Plus, "Create", onCreate, modifier = Modifier.testTag("drawer-create"))
        if (!guest && world.isCommunityAdmin) {
            IconAction(Icons.Outlined.Settings, "Belfry settings", { actions.go(Routes.BELFRY_SETTINGS) }, modifier = Modifier.testTag("drawer-settings"))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.memberRows(
    world: World, current: String, read: sh.belfry.core.read.ReadState, now: Long, counts: PlaceCounts, actions: DrawerActions, session: sh.belfry.core.engine.Session,
) {
    item(key = "<town>") { Section(PlaceNames.TOWN) }
    world.group("general")?.let { g ->
        item(key = "general") { ChannelRow(world, g, g.id == current, Unread.channel(world, read, g.id, now), onClick = { actions.channel(g.id) }) }
    }
    item(key = "<members>") { PlaceRow(BelfryIcons.Users, PlaceNames.MEMBERS, badge = counts.requests, tag = "drawer-members", onClick = { actions.go(Routes.MEMBERS) }) }
    if (world.showsCharter() && (world.charterGroup != null || world.isCommunityAdmin)) {
        item(key = "<charter>") { PlaceRow(Icons.Outlined.Description, PlaceNames.CHARTER, badge = counts.toAgree, tag = "drawer-charter", onClick = { actions.go(Routes.CHARTER) }) }
    }
    item(key = "<categories>") { CategoryList(world, current, read, now, actions, session) }
    val archived = world.myArchived()
    if (archived.isNotEmpty()) {
        item(key = "<archived>") {
            Spacer(Modifier.height(8.dp))
            PlaceRow(Icons.Outlined.Archive, PlaceNames.ARCHIVED, muted = true, tag = "drawer-archived", onClick = { actions.go(Routes.ARCHIVED) })
        }
    }
}

/**
 * The categories and their channels as one block, so a steward's drag crosses categories
 * (a channel belongs to the header above it, SPEC 19.34). Every move publishes the order event.
 */
@Composable
private fun CategoryList(world: World, current: String, read: sh.belfry.core.read.ReadState, now: Long, actions: DrawerActions, session: sh.belfry.core.engine.Session) {
    val admin = world.isCommunityAdmin
    val all = world.channelCategories()
    // Community life shows (empty, with its ＋) to any member, so a member can make the first channel
    val shown = all.filter { it.channels.isNotEmpty() || (admin && it.id != ChannelCategory.BUILDING) || it.id == ChannelCategory.LIFE }
    val byId = world.groups.associateBy { it.id }
    val scope = rememberCoroutineScope()
    // drag (stewards): where each row is on screen, which row moves and by how much
    val centers = remember { mutableStateMapOf<String, Float>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableStateOf(0f) }
    val flat = Categories.flat(shown)
    Column(Modifier.fillMaxWidth()) {
        for (row in flat) {
            when (row) {
                is CategoryRow.Header -> {
                    val cat = shown.first { it.id == row.id }
                    val plus = admin || cat.id == ChannelCategory.LIFE
                    Box(Modifier.onGloballyPositioned { centers["h:" + row.id] = it.boundsInWindow().center.y }) {
                        Section(cat.name, plus = if (plus) ({ actions.go(Routes.createChannel(if (admin) cat.id else null)) }) else null)
                    }
                }
                is CategoryRow.Channel -> {
                    val g = byId[row.id] ?: continue
                    val key = "c:" + row.id
                    val moving = dragging == key
                    val drag = if (admin) Modifier.pointerInput(row.id, flat) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = key; dy = 0f },
                            onDragEnd = {
                                val from = centers[key]
                                if (from != null) {
                                    val at = from + dy
                                    val rest = flat.filter { codeOf(it) != key }
                                    // the row lands after the last row whose centre is above it
                                    val idx = rest.indexOfLast { (centers[codeOf(it)] ?: Float.MAX_VALUE) < at } + 1
                                    val moved = rest.toMutableList().apply { add(idx.coerceIn(0, size), row) }
                                    if (moved != flat) {
                                        val next = Categories.rebuild(all, moved)
                                        scope.launch { try { session.members.setChannelLayout(next) } catch (e: Exception) { } }
                                    }
                                }
                                dragging = null; dy = 0f
                            },
                            onDragCancel = { dragging = null; dy = 0f },
                        ) { change, amount -> change.consume(); dy += amount.y }
                    } else Modifier
                    Box(
                        Modifier.onGloballyPositioned { if (!moving) centers[key] = it.boundsInWindow().center.y }
                            .then(drag)
                            .graphicsLayer { if (moving) { translationY = dy; shadowElevation = 12f } },
                    ) {
                        ChannelRow(world, g, g.id == current, Unread.channel(world, read, g.id, now), moving = moving, onClick = { actions.channel(g.id) })
                    }
                }
            }
        }
        if (admin && shown.any { it.channels.isNotEmpty() }) {
            Text(
                "Hold a channel to move it.", fontSize = 12.sp, color = Belfry.colors.faint,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp),
            )
        }
    }
}

private fun codeOf(r: CategoryRow): String = when (r) { is CategoryRow.Header -> "h:" + r.id; is CategoryRow.Channel -> "c:" + r.id }

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
private fun Section(title: String, plus: (() -> Unit)? = null) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, color = c.muted, modifier = Modifier.weight(1f))
        if (plus != null) IconAction(BelfryIcons.Plus, "New channel in $title", plus, tint = c.muted)
        else Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun PlaceRow(icon: ImageVector, title: String, onClick: () -> Unit, on: Boolean = false, badge: Int = 0, muted: Boolean = false, tag: String? = null) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().background(if (on) c.surface else c.bg).clickable(role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (muted) c.muted else c.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (muted) c.muted else c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge > 0) CountBadge(badge)
    }
}

@Composable
private fun ChannelRow(world: World, g: Group, on: Boolean, unread: Int, onClick: () -> Unit, moving: Boolean = false) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().background(if (on || moving) c.surface else c.bg).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp).semantics { if (unread > 0) contentDescription = "${g.name}, $unread new" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(g.glyph(world).icon(), contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        // unread (SPEC 19.27): the name stands out and a quiet count follows
        // a name not arrived: a grey bar for a few seconds, then the kind's word (SPEC 19.39)
        if (sh.belfry.app.ui.kit.rememberNameLoading(g.named)) Box(Modifier.weight(1f)) { sh.belfry.app.ui.kit.NameSkeleton() }
        else Text(g.name, fontSize = 16.sp, fontWeight = if (on || unread > 0) FontWeight.SemiBold else FontWeight.Normal, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (moving) Icon(Icons.Outlined.DragHandle, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
        else if (unread > 0) CountBadge(unread, color = c.surface2, onColor = c.muted)
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
