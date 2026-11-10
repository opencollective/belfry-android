package sh.belfry.app.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.chat.glyph
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Chip
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.SwitchRow
import sh.belfry.app.ui.kit.icon
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.crypto.Hex
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.People

/**
 * Someone's page, reached by tapping a name or a face anywhere. Yours has Edit; belfry admins
 * can make another member an admin here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(pubkey: String, startEditing: Boolean = false) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    if (!Hex.isKey(pubkey)) {
        Page("Profile") { EmptyState("This link doesn't point to a person.") }
        return
    }
    val mine = pubkey == world.me
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    if (mine && editing) {
        EditProfileScreen(onClose = { editing = false })
        return
    }
    val p = world.profiles[pubkey]
    val x = world.extras[pubkey]
    val about = world.aboutOf(pubkey)
    val joins = world.guestJoinsOf(pubkey)
    val guest = joins.firstOrNull()
    val noAdmin = world.noAdminReason(pubkey)
    val shared = world.sharedGroups(pubkey)
    val admin = world.isBelfryAdmin(pubkey)
    val name = p?.name?.takeIf { it.isNotEmpty() } ?: world.displayName(pubkey)
    var confirming by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Page(
        if (mine) "You" else world.displayName(pubkey),
        actions = {
            if (mine) {
                BusyButton("Edit", onClick = { editing = true }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.padding(end = 8.dp).testTag("profile-edit"))
            }
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(world.face(pubkey), size = 88.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val nip05 = p?.nip05
                if (!nip05.isNullOrEmpty()) Text(nip05, fontSize = 14.sp, color = c.muted)
                val chip = guest?.chip
                if (admin || chip != null) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (admin) Chip("admin", icon = BelfryIcons.Shield)
                        if (chip != null) Chip(chip)
                    }
                }
                val by = guest?.by
                if (guest != null && !by.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "invited by ${world.displayName(by)}", fontSize = 13.sp, color = c.muted,
                        modifier = Modifier.clickable(role = Role.Button) { nav.go(Routes.profile(by)) },
                    )
                }
            }
        }
        val place = x?.place
        if (place != null) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(BelfryIcons.MapPin, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(People.placeLabel(place), fontSize = 15.sp, color = c.text)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (about.isNotEmpty()) {
            MarkdownText(about, rich = false)
        } else if (mine) {
            Hint("Say a few words about yourself: tap Edit.")
        }
        if (x != null && x.tags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                x.tags.forEach { Chip("#$it") }
            }
        }
        if (!mine && guest == null) {
            Spacer(Modifier.height(16.dp))
            BusyButton("Message", onClick = { nav.go(Routes.conversation("n:$pubkey")) }, modifier = Modifier.fillMaxWidth(), icon = BelfryIcons.Mail)
        }
        if (shared.isNotEmpty()) {
            SectionTitle(if (mine) "Your channels" else "In")
            ActionGroup {
                shared.forEach { g ->
                    ActionRow(g.name, onClick = { nav.openChannel(g.id) }, icon = g.glyph().icon(), chevron = true)
                }
            }
        }
        if (world.isCommunityAdmin && !mine) {
            SectionTitle("Belfry")
            ActionGroup {
                SwitchRow(
                    "Admin of the belfry",
                    checked = admin,
                    onCheckedChange = { error = null; confirming = true },
                    modifier = Modifier.testTag("admin-switch"),
                    icon = BelfryIcons.Shield,
                    sub = if (noAdmin != null) {
                        null
                    } else if (guest != null) {
                        "A guest now: this makes them a member too. Manages members, every channel and the belfry's settings."
                    } else {
                        "Manages members, every channel and the belfry's settings."
                    },
                    enabled = noAdmin == null || admin,
                )
            }
            // Outside the dimmed row, so the reason stays readable.
            if (noAdmin != null) Hint(noAdmin, Modifier.padding(top = 8.dp))
            ErrorLine(error)
        }
    }
    if (confirming) {
        val who = world.displayName(pubkey)
        ConfirmSheet(
            title = if (admin) "Remove $who as an admin of the belfry?" else if (guest != null) "Make $who a member and an admin of the belfry?" else "Make $who an admin of the belfry?",
            confirmLabel = if (admin) "Remove" else "Make admin",
            onConfirm = {
                try {
                    session.setAdmin(pubkey, !admin)
                } catch (e: ActionException) {
                    error = e.shown()
                }
                confirming = false
            },
            onDismiss = { confirming = false },
            message = if (admin) {
                "They keep their membership."
            } else if (guest != null) {
                "They are a guest now. Admins manage members, every channel and the belfry's settings."
            } else {
                "Admins manage members, every channel and the belfry's settings."
            },
            danger = admin,
        )
    }
}
