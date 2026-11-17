package sh.belfry.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Group
import sh.belfry.core.model.World
import sh.belfry.core.model.channelCounts
import sh.belfry.core.model.mayArchive

/** The words under a channel's name: "Members only · 6 active · 12 photos · 1 group" (words, not icons alone). */
fun channelSubtitle(world: World, groupId: String, now: Long): String {
    val n = world.channelCounts(groupId, now)
    return listOfNotNull(
        if (n.isPublic) "Public" else "Members only",
        "${n.active} active",
        if (n.photos > 0) "${n.photos} ${if (n.photos == 1) "photo" else "photos"}" else null,
        if (n.groups > 0) "${n.groups} ${if (n.groups == 1) "group" else "groups"}" else null,
    ).joinToString(" · ")
}

/** "Archived · read only" under the header, with Unarchive for those who may (SPEC 19.30). */
@Composable
fun ArchivedBar(g: Group) {
    val c = Belfry.colors
    val session = LocalSession.current
    val world = sh.belfry.app.ui.nav.LocalWorld.current
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().background(c.surface).testTag("archived-bar")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Archive, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Archived · read only", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f))
            if (world.mayArchive(g)) {
                BusyButton("Unarchive", onClick = { error = null; session.members.setArchived(g, false) }, kind = ButtonKind.Ghost, small = true, onError = { error = it.message })
            }
        }
        error?.let { Text(it, fontSize = 14.sp, color = c.danger, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) }
        Hairline()
    }
}

/** What any route to a deleted group shows: a plain screen with the way back (SPEC 19.30). */
@Composable
fun GroupGone(onBack: (() -> Unit)?, onMenu: (() -> Unit)?) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg).testTag("group-gone")) {
        TopBar(
            "Deleted",
            navigation = when {
                onBack != null -> ({ BackButton(onBack) })
                onMenu != null -> ({ sh.belfry.app.ui.kit.IconAction(sh.belfry.app.ui.kit.BelfryIcons.Menu, "The town", onMenu) })
                else -> ({ BackButton { nav.back() } })
            },
        )
        EmptyState("This group has been deleted", text = "Its messages are no longer on the belfry.", icon = Icons.Outlined.Archive)
    }
}

/** Archive G after a confirmation (its settings). */
@Composable
fun ArchiveConfirm(g: Group, onDone: () -> Unit, onDismiss: () -> Unit) {
    val session = LocalSession.current
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Archive ${g.name}?") },
        text = {
            Column {
                Text("It leaves the channel list and nobody can post in it. You can unarchive it later.")
                error?.let { Text(it, color = Belfry.colors.danger, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            BusyButton("Archive", onClick = { error = null; session.members.setArchived(g, true); onDone() }, kind = ButtonKind.Danger, small = true, onError = { error = it.message }, modifier = Modifier.testTag("archive-confirm"))
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Belfry.colors.text) } },
        containerColor = Belfry.colors.bg,
    )
}
