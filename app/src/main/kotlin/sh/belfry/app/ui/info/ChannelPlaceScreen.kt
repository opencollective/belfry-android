package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ArchiveConfirm
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.PodIcon
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.ProposalStatus
import sh.belfry.core.model.canPin
import sh.belfry.core.model.isLocked
import sh.belfry.core.model.mayArchive
import sh.belfry.core.model.pinMessage
import sh.belfry.core.model.pinned

/**
 * One of a channel's screens, pushed from its pull-down (the tabbed channel info is gone): Pinned,
 * To decide, To-dos, Photos, Groups, Members (with Invite), Log, Settings.
 */
@Composable
fun ChannelPlaceScreen(id: String, key: String) {
    if (key == ChannelPlaceKey.EMAILS) return sh.belfry.app.ui.chat.EmailsScreen(id)
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val g = world.group(id)
    val title = when (key) {
        ChannelPlaceKey.PINNED -> "Pinned"
        ChannelPlaceKey.DECIDE -> "To decide"
        ChannelPlaceKey.TODOS -> "To-dos"
        ChannelPlaceKey.PHOTOS -> "Photos"
        ChannelPlaceKey.GROUPS -> "Groups"
        ChannelPlaceKey.MEMBERS -> "Members"
        ChannelPlaceKey.LOG -> "Log"
        else -> if (g?.isPod == true) "Group settings" else "Settings"
    }
    Column(Modifier.fillMaxSize().background(c.bg).testTag("place-$key")) {
        TopBar(title, subtitle = g?.name, navigation = { BackButton { nav.back() } })
        if (g == null) {
            EmptyState("This channel is not here", icon = BelfryIcons.Hash)
            return@Column
        }
        if (key == ChannelPlaceKey.LOG) {
            LogTab(g, Modifier.weight(1f).fillMaxWidth().navigationBarsPadding())
            return@Column
        }
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (key) {
                ChannelPlaceKey.PINNED -> PinnedList(id)
                ChannelPlaceKey.DECIDE -> DecideList(id)
                ChannelPlaceKey.TODOS -> TasksTab(id)
                ChannelPlaceKey.PHOTOS -> FilesTab(id)
                ChannelPlaceKey.GROUPS -> GroupsList(id)
                ChannelPlaceKey.MEMBERS -> {
                    if (!world.isLocked(id) && !world.amGuest) {
                        BusyButton("Invite someone", onClick = { nav.go(Routes.invite(id)) }, modifier = Modifier.fillMaxWidth().testTag("members-invite"), icon = BelfryIcons.Users)
                        Spacer(Modifier.height(12.dp))
                    }
                    MembersTab(g)
                }
                else -> SettingsPlace(g)
            }
        }
    }
}

@Composable
private fun PinnedList(id: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val list = world.pinned(id)
    if (list.isEmpty()) {
        EmptyState("Nothing pinned", text = "Admins pin a message from its menu.", icon = Icons.Outlined.PushPin)
        return
    }
    for (m in list) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.openChannel(id, m.id) }.padding(vertical = 10.dp)) {
            Icon(Icons.Outlined.PushPin, contentDescription = null, tint = c.text, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (m.text.isNotEmpty()) MarkdownText(m.text, fontSize = 16.sp, maxLines = 8) else Text("A photo or file", fontSize = 16.sp, color = c.text)
                Text("${world.displayName(m.pubkey)} · ${ChatFormat.day(world, m.at, session.now())}", fontSize = 13.sp, color = c.muted)
            }
            if (world.canPin(id) && !world.isLocked(id)) {
                BusyButton("Unpin", onClick = { session.send(Actions.pinMessage(id, m.id, false)) }, kind = ButtonKind.Ghost, small = true)
            }
        }
    }
}

@Composable
private fun DecideList(id: String) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val open = setOf(ProposalStatus.DRAFT, ProposalStatus.FEEDBACK, ProposalStatus.VOTING)
    val list = world.proposalsIn(id).sortedByDescending { it.status in open }
    if (!world.isLocked(id) && !world.amGuest) {
        BusyButton("New proposal", onClick = { nav.go(Routes.createProposal(id)) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, icon = BelfryIcons.Plus)
        Spacer(Modifier.height(8.dp))
    }
    if (list.isEmpty()) {
        EmptyState("Nothing to decide", icon = BelfryIcons.Proposal)
        return
    }
    ActionGroup {
        for (p in list) ActionRow(p.title, onClick = { nav.go(Routes.proposal(p.groupId)) }, icon = BelfryIcons.Proposal, sub = "${p.status.label} · version ${p.version}", chevron = true)
    }
    Spacer(Modifier.height(4.dp))
    Text("Open proposals first.", fontSize = 13.sp, color = c.muted)
}

@Composable
private fun GroupsList(id: String) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val list = world.pods(id)
    if (!world.isLocked(id) && !world.amGuest) {
        BusyButton("New group", onClick = { nav.go(Routes.createPod(id)) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, icon = BelfryIcons.Plus)
        Spacer(Modifier.height(8.dp))
    }
    if (list.isEmpty()) {
        EmptyState("No groups here yet", icon = BelfryIcons.Pod)
        return
    }
    for (p in list) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.go(Routes.pod(p.id)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PodIcon(p.members.size, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontSize = 16.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.members.size} ${if (p.members.size == 1) "member" else "members"} · " + if (p.closed) "by application" else "open to join", fontSize = 13.sp, color = c.muted)
            }
        }
    }
}

@Composable
private fun SettingsPlace(g: sh.belfry.core.model.Group) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    var confirm by remember { mutableStateOf(false) }
    // Navigation v2 (nav2/channel-pin): In your menu, Notifications, then (admins) About
    sh.belfry.app.ui.shell.ChannelPinSettings(g)
    if (!g.archived && world.canAdmin(g)) {
        Text("About", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = Belfry.colors.muted, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
        ChannelSettingsForm(g, onClose = { nav.back() })
    }
    if (world.mayArchive(g) && !g.archived) {
        // under Save, quiet and centred (owner's feedback 2026-11-17): a text button and its one line
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("settings-archive")) {
                Text(if (g.isPod) "Archive this group" else "Archive this channel", fontSize = 15.sp, color = Belfry.colors.danger)
            }
            Text(
                "It leaves the lists and becomes read only. You can unarchive it.",
                fontSize = 13.sp, color = Belfry.colors.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    if (g.archived) Text("Archived: unarchive it from the bar under its name to change its settings.", fontSize = 15.sp, color = Belfry.colors.muted)
    if (confirm) ArchiveConfirm(g, onDone = { confirm = false; nav.back() }, onDismiss = { confirm = false })
}

