package sh.belfry.app.ui.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.ChannelGlyph
import sh.belfry.app.ui.kit.ChannelIcon
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Application

/** A pod: what it works on, who can join, who is in, and for its admins the requests to join. */
@Composable
fun PodScreen(id: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val g = world.group(id)
    if (g == null) {
        CardScreen("Pod") { EmptyState("This pod is not here (yet)", text = "It may still be syncing.", icon = BelfryIcons.Pod) }
        return
    }
    val inIt = world.me in g.members
    val applied = g.applications.any { it.pubkey == world.me }
    val admin = world.canAdmin(g)
    var asking by remember(id) { mutableStateOf(false) }
    var leaving by remember(id) { mutableStateOf(false) }
    var declining by remember(id) { mutableStateOf<Application?>(null) }
    val parent = g.parent?.let { world.group(it) }

    CardScreen(g.name, subtitle = (if (g.closed) "Closed pod" else "Pod") + " · " + CardFormat.members(g.members.size)) {
        PageColumn {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelIcon(ChannelGlyph.Pod, size = 44.dp)
                GapW(12)
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    if (parent != null) Muted("A working group inside ${parent.name}")
                }
            }
            if (g.about.isNotEmpty()) MarkdownText(g.about, rich = false)

            Panel {
                Text(if (g.closed) "Closed: apply, admins admit" else "Open: any member joins", fontSize = 15.sp, color = c.text)
                if (g.closed) {
                    Gap(4)
                    Muted("Question: " + (g.prompt?.takeIf { it.isNotBlank() } ?: "—"))
                }
                if (!inIt && g.closed && applied) {
                    Gap(6)
                    Badge("Request sent", Tone.Warm)
                }
            }

            ActionGroup {
                ActionRow("Open the pod's chat", onClick = { nav.openChannel(id) }, modifier = Modifier.testTag("pod-chat"), icon = BelfryIcons.Thread, chevron = true)
                when {
                    inIt -> Unit
                    !g.closed -> BusyRow("Join pod", onClick = { session.send(Actions.join(id)) }, modifier = Modifier.testTag("pod-join"), icon = BelfryIcons.Plus)
                    applied -> ActionRow("Request sent", onClick = null, icon = BelfryIcons.Clock, sub = "The pod’s admins will answer.", enabled = false)
                    else -> ActionRow("Apply to join", onClick = { asking = true }, modifier = Modifier.testTag("pod-apply"), icon = BelfryIcons.Plus, sub = "Answer the pod's question")
                }
            }

            if (admin && g.applications.isNotEmpty()) {
                SectionTitle("Requests to join")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (a in g.applications) {
                        Panel(Modifier.testTag("pod-application")) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.go(Routes.profile(a.pubkey)) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(world.face(a.pubkey), size = 30.dp)
                                GapW(10)
                                Text(world.displayName(a.pubkey), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!g.prompt.isNullOrBlank()) {
                                Gap(6)
                                Muted(g.prompt ?: "")
                            }
                            Gap(4)
                            Text(a.answer.ifEmpty { "(no answer)" }, fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
                            Gap(10)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BusyButton("Admit", onClick = { session.send(Actions.admit(id, a.pubkey)) }, modifier = Modifier.testTag("pod-admit"), small = true)
                                BusyButton("Decline", onClick = { declining = a }, kind = ButtonKind.Ghost, small = true)
                            }
                        }
                    }
                }
            }

            SectionTitle(CardFormat.members(g.members.size).replaceFirstChar { it.uppercase() })
            Column {
                for (pubkey in g.members.sortedBy { world.displayName(it).lowercase() }) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.go(Routes.profile(pubkey)) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(world.face(pubkey), size = 30.dp)
                        GapW(10)
                        Text(world.displayName(pubkey), Modifier.weight(1f), fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (pubkey in g.admins) Badge("admin")
                    }
                }
            }

            if (inIt) {
                ActionGroup {
                    ActionRow("Leave pod", onClick = { leaving = true }, modifier = Modifier.testTag("pod-leave"), icon = BelfryIcons.Logout, danger = true)
                }
            }
        }
    }

    if (asking) ApplySheet(g) { asking = false }
    if (leaving) {
        ConfirmSheet(
            title = "Leave ${g.name}?",
            confirmLabel = "Leave pod",
            onConfirm = {
                session.send(Actions.leave(id))
                leaving = false
            },
            onDismiss = { leaving = false },
            message = if (g.closed) "To come back you will have to apply again." else "You can join again whenever you like.",
        )
    }
    declining?.let { a ->
        ConfirmSheet(
            title = "Decline ${world.displayName(a.pubkey)}?",
            confirmLabel = "Decline",
            onConfirm = {
                session.send(Actions.decline(id, a.pubkey))
                declining = null
            },
            onDismiss = { declining = null },
            preview = { Text(a.answer.ifEmpty { "(no answer)" }, fontSize = 15.sp, lineHeight = 21.sp, color = c.text) },
        )
    }
}
