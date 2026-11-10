package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Application
import sh.belfry.core.model.Group
import sh.belfry.core.model.MemberRow
import sh.belfry.core.model.People

/**
 * Who is in the channel: people asking to join a closed pod (its admins admit or decline), then
 * every member with their place, guest mark and labels. The web's map of members is this list
 * for now (each row shows the place).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MembersTab(g: Group) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val now = session.now()
    val rows = remember(world, g.id) { world.memberRows(g.id) }
    // who is a guest, an agent or a member: asked of the belfry again when the list opens
    LaunchedEffect(g.id) { session.recheckPeople(world.guestJoins(g.id).map { it.pubkey }) }
    val reviewing = if (g.isPod && world.canAdmin(g)) g.applications else emptyList()
    var declining by remember { mutableStateOf<Application?>(null) }
    var labelling by remember { mutableStateOf<MemberRow?>(null) }

    if (reviewing.isNotEmpty()) {
        Text("Asking to join · ${reviewing.size}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted, modifier = Modifier.padding(bottom = 6.dp))
        reviewing.forEach { a ->
            InfoCard(Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(world.face(a.pubkey), size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(world.displayName(a.pubkey), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(ChatFormat.short(world, a.at, now), fontSize = 12.sp, color = c.muted)
                }
                if (!g.prompt.isNullOrBlank()) Hint(g.prompt ?: "", Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 6.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.line))
                    Spacer(Modifier.width(10.dp))
                    Text(a.answer.ifBlank { "(no answer)" }, fontSize = 15.sp, lineHeight = 21.sp)
                }
                Spacer(Modifier.height(10.dp))
                ActionGroup {
                    BusyRow("Admit", { session.send(Actions.admit(g.id, a.pubkey)) }, icon = BelfryIcons.Check)
                    ActionRow("Decline", { declining = a }, icon = BelfryIcons.Close, danger = true)
                }
            }
        }
        SectionTitle("Members · ${g.members.size}")
    }

    if (rows.none { it.place?.lat != null }) {
        Row(Modifier.padding(bottom = 10.dp)) {
            Text("Nobody here shares their city yet. ", fontSize = 13.sp, color = c.muted)
            if (!world.amGuest) {
                Text("Add yours", fontSize = 13.sp, color = c.text, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { nav.go(Routes.profile(world.me)) })
            }
        }
    }

    if (rows.isEmpty()) {
        EmptyState("No members yet", icon = BelfryIcons.Users)
    } else {
        ActionGroup {
            rows.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().background(c.surface).clickable { nav.go(Routes.profile(r.pubkey)) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Avatar(world.face(r.pubkey), size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (r.isAdmin) {
                                Spacer(Modifier.width(8.dp))
                                Chip("admin", icon = BelfryIcons.Shield)
                            }
                        }
                        if (r.place != null) Hint(People.placeLabel(r.place))
                        val guest = r.guest
                        if (guest != null) {
                            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                guest.chip?.let {
                                    Chip(it)
                                    Spacer(Modifier.width(6.dp))
                                }
                                val by = guest.by
                                if (by != null) Text("invited by " + world.displayName(by), fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        FlowRow(
                            Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { labelling = r },
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            r.labels.forEach { Chip(it, selected = true) }
                            if (r.labels.isEmpty()) Text("Add a label", fontSize = 13.sp, color = c.faint)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))

    declining?.let { a ->
        ConfirmSheet(
            "Decline ${world.displayName(a.pubkey)}?", "Decline",
            onConfirm = { session.send(Actions.decline(g.id, a.pubkey)); declining = null },
            onDismiss = { declining = null },
            message = "They are told nothing; they can ask again.",
        )
    }
    labelling?.let { r ->
        BelfrySheet({ labelling = null }, title = "Labels for ${r.name}") {
            LabelEditor(g.id, r, vocab = rows.flatMap { it.labels }.distinct().sorted()) { labelling = null }
        }
    }
}

/** A member's labels in this channel (NIP-32): pick from the ones in use and the suggested ones, or add one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelEditor(groupId: String, row: MemberRow, vocab: List<String>, onClose: () -> Unit) {
    val session = LocalSession.current
    var sel by remember { mutableStateOf(row.labels) }
    var extra by remember { mutableStateOf("") }
    val options = (vocab + Actions.SUGGESTED_LABELS + sel).distinct()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { l ->
            Chip(l, selected = l in sel, onClick = { sel = if (l in sel) sel - l else sel + l })
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        BelfryTextField(extra, { extra = it }, Modifier.weight(1f), placeholder = "New label")
        Spacer(Modifier.width(8.dp))
        BusyButton(
            "Add",
            onClick = {
                val l = extra.trim().lowercase().take(32)
                if (l.isNotEmpty() && l !in sel) sel = sel + l
                extra = ""
            },
            kind = ButtonKind.Ghost, small = true, enabled = extra.isNotBlank(),
        )
    }
    Spacer(Modifier.height(16.dp))
    BusyButton(
        "Save labels", { session.send(Actions.setMemberLabels(groupId, row.pubkey, sel)); onClose() },
        modifier = Modifier.fillMaxWidth().testTag("labels-save"),
    )
}
