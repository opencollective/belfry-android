package sh.belfry.app.ui.places

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.belfry.app.ui.chat.NewTodoContent
import sh.belfry.app.ui.chat.glyph
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.icon
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.core.model.isLocked
import sh.belfry.core.model.townChannels

/**
 * Create (SPEC 19.31): Message, Meeting, Event, To-do, Group, Proposal into a chosen channel (the
 * one on screen in Town, else the Townsquare). A Material bottom sheet with a list; the forms open
 * as screens (a to-do in the same sheet).
 */
@Composable
fun CreateSheet(shell: ShellState, onDismiss: () -> Unit) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val choices = world.townChannels().filter { !world.isLocked(it.id) }
    val start = if (shell.place == ShellState.Place.TOWN && choices.any { it.id == shell.channel }) shell.channel else "general"
    var channel by remember { mutableStateOf(start) }
    var picking by remember { mutableStateOf(false) }
    var todo by remember { mutableStateOf(false) }
    val g = world.group(channel)
    BelfrySheet(onDismiss = onDismiss, title = if (todo) "New to-do" else "Create", modifier = Modifier.testTag("create-sheet")) {
        if (todo) {
            NewTodoContent(channel, onDone = onDismiss)
            return@BelfrySheet
        }
        ActionGroup {
            Box {
                ActionRow(
                    "In " + (g?.name ?: channel), onClick = { picking = true }, icon = g?.glyph(world)?.icon() ?: BelfryIcons.Hash,
                    sub = "Where it goes", chevron = true, modifier = Modifier.testTag("create-channel-pick"),
                )
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    for (x in choices) {
                        DropdownMenuItem(
                            text = { Text(x.name) },
                            leadingIcon = { Icon(x.glyph(world).icon(), contentDescription = null) },
                            onClick = { channel = x.id; picking = false },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ActionGroup {
            // Navigation v2 (nav2/channel): a message is a new conversation in Messages; a group is the ＋ on GROUPS
            ActionRow("Message", onClick = { onDismiss(); nav.go(Routes.NEW_CONVERSATION) }, icon = Icons.Outlined.ChatBubbleOutline, sub = "To a member, or anyone by e-mail", modifier = Modifier.testTag("create-message"))
            // §26 (SPEC 19.88): not in a private group yet
            val noExtras = sh.belfry.app.ui.nav.LocalSession.current.privateNow(channel)
            val no = sh.belfry.core.model.GroupEncText.NO_EXTRAS
            if (!world.amGuest) {
                ActionRow("Meeting", onClick = { onDismiss(); nav.go(Routes.createMeeting(channel)) }, icon = BelfryIcons.Users, sub = if (noExtras) no else "A topic, people, a time", enabled = !noExtras, modifier = Modifier.testTag("create-meeting"))
            }
            ActionRow("Event", onClick = { onDismiss(); nav.go(Routes.createEvent(channel)) }, icon = BelfryIcons.Calendar, sub = if (noExtras) no else "With tickets, open to members or to all", enabled = !noExtras, modifier = Modifier.testTag("create-event"))
            ActionRow("To-do", onClick = { todo = true }, icon = MoreIcons.Todo, sub = "Something to get done, on the channel's list", modifier = Modifier.testTag("create-todo"))
            ActionRow("Proposal", onClick = { onDismiss(); nav.go(Routes.createProposal(channel)) }, icon = BelfryIcons.Proposal, sub = if (noExtras) no else "Discuss it, then vote", enabled = !noExtras, modifier = Modifier.testTag("create-proposal"))
        }
        Spacer(Modifier.height(8.dp))
    }
}
