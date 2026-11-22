package sh.belfry.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.CountBadge
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.PodIcon
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.shell.AccountsSheet
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Group
import sh.belfry.core.model.World
import sh.belfry.core.model.activePods
import sh.belfry.core.model.allPods
import sh.belfry.core.model.groupRequestsFor
import sh.belfry.core.model.myPods
import sh.belfry.core.read.Unread

private enum class GroupsFilter(val label: String) { MINE("My groups"), ACTIVE("Active"), ALL("All") }

/**
 * Groups (SPEC 19.31): my groups · active (mine with a message in the last 30 days) · all (every
 * listed group; not mine: "open to join" or "by application"). A tap pushes the group's page.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GroupsPlace(onMenu: (() -> Unit)? = null, menuBadge: Int = 0) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val read by session.readState.collectAsState()
    var filter by rememberSaveable { mutableStateOf(GroupsFilter.MINE) }
    var accounts by rememberSaveable { mutableStateOf(false) }
    val now = session.now()
    val list = when (filter) {
        GroupsFilter.MINE -> world.myPods()
        GroupsFilter.ACTIVE -> world.activePods(now)
        GroupsFilter.ALL -> world.allPods()
    }
    Column(Modifier.fillMaxSize().background(c.bg).testTag("groups")) {
        TopBar(
            "Groups",
            subtitle = world.ctx.communityName.ifEmpty { session.community.name },
            onTitleClick = { accounts = true },
            navigation = if (onMenu != null) ({ IconAction(BelfryIcons.Menu, "Menu", onMenu, badge = menuBadge) }) else null,
            actions = { IconAction(BelfryIcons.Plus, "New group", { nav.go(Routes.createPod("general")) }) },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in GroupsFilter.entries) {
                FilterChip(
                    selected = filter == f, onClick = { filter = f }, label = { Text(f.label, fontSize = 15.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = c.onAccent, labelColor = c.text),
                    modifier = Modifier.testTag("groups-" + f.name.lowercase()),
                )
            }
        }
        if (list.isEmpty()) {
            EmptyState(
                if (filter == GroupsFilter.ALL) "No groups yet" else "You're in no group yet",
                text = if (filter == GroupsFilter.ALL) "A group is a smaller circle inside the town: start one with ＋." else "See All for the groups you can join.",
                icon = BelfryIcons.Pod,
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item(key = "<list>") {
                    Spacer(Modifier.height(4.dp))
                    PlaceCard {
                        list.forEachIndexed { i, g ->
                            if (i > 0) RowDivider()
                            GroupRow(world, g, Unread.channel(world, read, g.id, now)) { nav.go(Routes.pod(g.id)) }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (accounts) AccountsSheet(onDismiss = { accounts = false }, go = nav::go)
}

@Composable
private fun GroupRow(world: World, g: Group, unread: Int, onClick: () -> Unit) {
    val c = Belfry.colors
    val mine = world.me in g.members || world.me in g.admins
    val n = g.members.size
    val state = when {
        mine && world.groupRequestsFor(g).isNotEmpty() -> "${world.groupRequestsFor(g).size} asking to join"
        mine -> world.group(g.parent ?: "")?.let { "in ${it.name}" }
        g.closed -> "by application"
        else -> "open to join"
    }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PodIcon(n, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (sh.belfry.app.ui.kit.rememberNameLoading(g.named)) sh.belfry.app.ui.kit.NameSkeleton(Modifier.padding(vertical = 3.dp))
            else Text(g.name, fontSize = 16.sp, fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull("$n ${if (n == 1) "member" else "members"}", state).joinToString(" · "), fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (unread > 0) { CountBadge(unread, color = c.surface2, onColor = c.muted); Spacer(Modifier.width(4.dp)) }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.faint)
    }
}
