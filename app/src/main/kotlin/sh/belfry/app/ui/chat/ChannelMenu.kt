package sh.belfry.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.PodShape
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.channelCounts
import sh.belfry.core.model.isLocked
import sh.belfry.core.model.lineDay
import sh.belfry.core.model.pinned
import sh.belfry.core.model.showsRow

/**
 * The channel's pull-down (iOS 2026-11-17, web ChannelLine.tsx at r3): from the top, under the
 * header: the description, the channel's e-mail address, the pinned message not hidden, then a
 * menu of rows that each PUSH one of the channel's screens: Pinned, Events, To decide, To-dos,
 * Photos, Files, Groups, Topics, Members (Invite inside), Notifications, Log, Settings (its
 * admins). Never a list unfolded in place.
 */
@Composable
fun ChannelMenu(groupId: String, chat: ChatState, now: Long, onShowMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val g = world.group(groupId) ?: return
    val n = world.channelCounts(groupId, now)
    val go: (String) -> Unit = { chat.closeLine(); nav.go(it) }
    val place: (String) -> Unit = { go(Routes.channelPlace(groupId, it)) }
    var notify by remember { mutableStateOf(false) }
    if (notify) sh.belfry.app.ui.info.NotifySheet(groupId, onDismiss = { notify = false })
    Column(modifier.fillMaxWidth().background(c.bg).verticalScroll(rememberScrollState()).testTag("pull-down")) {
        if (g.about.isNotBlank()) {
            MarkdownText(g.about.trim(), fontSize = 16.sp, maxLines = 4, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("pull-about"))
        }
        if (!g.isEvent && !g.isProposal) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MailOutline, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(world.mailAddress(groupId), fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // the pinned message I haven't hidden, with Hide
        world.pinned(groupId).firstOrNull { it.id !in chat.hiddenPins }?.let { m ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { chat.closeLine(); onShowMessage(m.id) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Icon(Icons.Outlined.PushPin, contentDescription = null, tint = c.text, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (m.text.isNotEmpty()) MarkdownText(m.text, fontSize = 16.sp, maxLines = 4) else Text("A photo or file", fontSize = 16.sp, color = c.text)
                    Text("${world.displayName(m.pubkey)} · ${world.lineDay(m.at, now)}", fontSize = 13.sp, color = c.muted)
                }
                TextButton(onClick = { chat.hidePin(m.id) }) { Text("Hide", color = c.text, fontWeight = FontWeight.SemiBold) }
            }
        }
        Hairline(Modifier.padding(top = 4.dp))
        // rows with nothing in them are left out (like the iPhone); Members, Notifications, Log and Settings stay
        if (n.showsRow("pinned")) MenuRow(Icons.Outlined.PushPin, "Pinned", n.pins, "pd-pinned") { place(ChannelPlaceKey.PINNED) }
        if (!g.isEvent && n.showsRow("events")) MenuRow(BelfryIcons.Calendar, "Events", n.events, "pd-events") { go(Routes.events(groupId)) }
        if (!g.isEvent && !g.isProposal && n.showsRow("decide")) MenuRow(BelfryIcons.Proposal, "To decide", n.proposals, "pd-decide") { place(ChannelPlaceKey.DECIDE) }
        if (n.showsRow("todos")) MenuRow(MoreIcons.Todo, "To-dos", n.todos - n.todosDone, "pd-todos") { place(ChannelPlaceKey.TODOS) }
        if (n.showsRow("photos")) MenuRow(Icons.Outlined.Image, "Photos", n.photos, "pd-photos") { place(ChannelPlaceKey.PHOTOS) }
        if (n.showsRow("files")) MenuRow(Icons.Outlined.AttachFile, "Files", n.files, "pd-files") { go(Routes.files(groupId)) }
        if (!g.isEvent && !g.isProposal && n.showsRow("groups")) MenuRow(null, "Groups", n.groups, "pd-groups", pod = true) { place(ChannelPlaceKey.GROUPS) }
        if (n.showsRow("topics")) MenuRow(BelfryIcons.Hash, "Topics", n.topics, "pd-topics") { go(Routes.topics(groupId)) }
        MenuRow(BelfryIcons.Users, "Members", n.members, "pd-members") { place(ChannelPlaceKey.MEMBERS) }
        // the one row that opens a sheet (round 6): the channel stays behind it
        if (!world.isLocked(groupId)) MenuRow(Icons.Outlined.NotificationsNone, "Notifications", 0, "pd-notifications") { notify = true }
        MenuRow(Icons.Outlined.History, "Log", 0, "pd-log") { place(ChannelPlaceKey.LOG) }
        if (world.canAdmin(g)) MenuRow(Icons.Outlined.Settings, "Settings", 0, "pd-settings") { place(ChannelPlaceKey.SETTINGS) }
    }
}

@Composable
private fun MenuRow(icon: ImageVector?, title: String, count: Int, tag: String, pod: Boolean = false, onClick: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 12.dp, top = 13.dp, bottom = 13.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pod) PodShape(6, size = 22.dp, color = c.muted) else if (icon != null) Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, fontSize = 17.sp, color = c.text, modifier = Modifier.weight(1f))
        if (count > 0) Text(count.toString(), fontSize = 16.sp, color = c.muted)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.faint)
    }
}
