package sh.belfry.app.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import sh.belfry.app.ui.kit.shareText
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
import androidx.compose.runtime.LaunchedEffect
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
import sh.belfry.core.model.agentOwner
import sh.belfry.core.model.isGuest

/**
 * Someone's page, reached by tapping a name or a face anywhere. Yours has Edit; belfry admins
 * can make another member an admin here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(pubkey: String, startEditing: Boolean = false, group: String? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    if (!Hex.isKey(pubkey)) {
        Page("Profile") { EmptyState("This link doesn't point to a person.") }
        return
    }
    val mine = pubkey == world.me
    // guest, agent or member: the belfry's register is asked again when a profile opens
    LaunchedEffect(pubkey) { session.recheckPeople(listOf(pubkey)) }
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    if (mine && editing) {
        EditProfileScreen(onClose = { editing = false })
        return
    }
    val p = world.profiles[pubkey]
    val x = world.extras[pubkey]
    val about = world.aboutOf(pubkey)
    val joins = world.guestJoinsOf(pubkey)
    // a member's own agent (a member while its member is one), or a guest agent that can become mine
    val owner = world.agentOwner(pubkey)
    val guest = joins.firstOrNull()?.takeIf { owner == null }
    val guestAgent = owner == null && joins.any { it.agent }
    val canOwn = guestAgent && !world.isGuest(world.me) && world.agentOwner(world.me) == null && (world.isCommunityAdmin || joins.any { it.by == world.me })
    val canDrop = owner != null && (owner == world.me || world.isCommunityAdmin)
    val noAdmin = world.noAdminReason(pubkey)
    val shared = world.sharedGroups(pubkey)
    val admin = world.isBelfryAdmin(pubkey)
    val name = p?.name?.takeIf { it.isNotEmpty() } ?: world.displayName(pubkey)
    var confirming by rememberSaveable { mutableStateOf(false) }
    var dropping by rememberSaveable { mutableStateOf(false) }
    var agentError by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Page(
        if (mine) "You" else world.displayName(pubkey),
        actions = {
            if (mine) {
                BusyButton("Edit", onClick = { editing = true }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.padding(end = 8.dp).testTag("profile-edit"))
            }
        },
    ) {
        // a contact card (board home/profile v68, web c3fcaab): face, name and address centred, then
        // Message · E-mail · Share; Tags; Role; then In <group>; Channels in common last
        val nip05 = p?.nip05?.takeIf { it.contains('@') }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(world.face(pubkey), size = 88.dp)
            Spacer(Modifier.height(10.dp))
            Text(name, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (nip05 != null) Text(nip05, fontSize = 15.sp, color = c.muted)
            // a guest agent keeps its "agent" chip; a guest's own line says Guest (canvas v68)
            val agentChip = owner != null || guest?.agent == true
            if (agentChip) {
                Spacer(Modifier.height(6.dp))
                Chip("agent")
            }
            if (owner != null) {
                Text(
                    if (owner == world.me) "your agent" else "agent of " + world.displayName(owner), fontSize = 13.sp, color = c.muted,
                    modifier = Modifier.padding(top = 4.dp).clickable(role = Role.Button) { nav.go(Routes.profile(owner)) }.testTag("agent-owner"),
                )
            }
            // a guest (canvas home/profile-guest v68): "Guest in #<channel>", then "invited by <name>"
            // underlined, its own tap target to the inviter's profile; "(not saved)" while unsaved
            if (guest != null) {
                val where = world.group(guest.group)?.takeIf { it.named }?.let { " in #${it.name}" } ?: ""
                Text("Guest$where" + if (guest.unsaved) " (not saved)" else "", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp).testTag("guest-line"))
                val by = guest.by
                if (!by.isNullOrEmpty()) {
                    Text(
                        "invited by " + if (by == world.me) "you" else world.displayName(by), fontSize = 15.sp, color = c.muted,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        modifier = Modifier.padding(top = 2.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { nav.go(Routes.profile(by)) }.padding(horizontal = 6.dp, vertical = 6.dp).testTag("guest-invited-by"),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            val context = androidx.compose.ui.platform.LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // a guest can't get direct messages: Share only (canvas v67)
                if (!mine && guest == null) CardAction(BelfryIcons.Thread, "Message", "profile-message") { nav.go(Routes.conversation("n:$pubkey")) }
                if (!mine && guest == null && nip05 != null) CardAction(BelfryIcons.Mail, "E-mail", "profile-email") {
                    try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:" + nip05)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                }
                // their address (else their key, handed to the share sheet, never shown)
                CardAction(BelfryIcons.Share, "Share", "profile-share") { shareText(context, nip05 ?: npubOf(pubkey), name) }
            }
        }
        // Tags right under the buttons, above Role, member or guest (canvas v68); read-only here, none = no section
        if (x != null && x.tags.isNotEmpty()) {
            SectionTitle("Tags")
            FlowRow(Modifier.testTag("profile-tags"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                x.tags.forEach { Chip("#$it") }
            }
        }
        // the belfry's role right below (Steward · Member · Guest and its own roles)
        if (owner == null) RoleSection(pubkey)
        // canvas v93: the member's own agents under Role; on my own profile "Add an agent" comes last
        if (owner == null) AgentsSection(pubkey, mine)
        // opened from a channel's, group's or event's people: the role there (SPEC 19.29)
        if (group != null) InGroupSection(pubkey, group)
        val place = x?.place
        if (place != null || about.isNotEmpty() || mine) Spacer(Modifier.height(14.dp))
        if (place != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(BelfryIcons.MapPin, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(People.placeLabel(place), fontSize = 15.sp, color = c.text)
            }
            Spacer(Modifier.height(10.dp))
        }
        if (about.isNotEmpty()) {
            MarkdownText(about, rich = false)
        } else if (mine) {
            Hint("Say a few words about yourself: tap Edit.")
        }
        if (shared.isNotEmpty()) {
            SectionTitle(if (mine) "Your channels" else "Channels in common")
            ActionGroup {
                shared.forEach { g ->
                    ActionRow(g.name, onClick = { nav.openChannel(g.id) }, icon = g.glyph().icon(), chevron = true)
                }
            }
        }
        if (canOwn || canDrop) {
            SectionTitle("Agent")
            if (canOwn) {
                Hint("It is a guest for 30 days now. As your agent it becomes a member and stays for as long as you are one: it reads every open channel and people can message it. Everyone sees it is yours.")
                Spacer(Modifier.height(10.dp))
                BusyButton(
                    "Make it my agent",
                    onClick = { agentError = null; try { session.setMyAgent(pubkey, true) } catch (e: ActionException) { agentError = e.shown() } },
                    modifier = Modifier.fillMaxWidth().testTag("agent-make-mine"), icon = BelfryIcons.User,
                )
            }
            if (canDrop) {
                val whose = if (owner == world.me) "you are" else "${world.displayName(owner!!)} is"
                Hint("A member for as long as $whose one. Removing it takes it out of the belfry and every channel.")
                Spacer(Modifier.height(10.dp))
                BusyButton(
                    "Remove this agent…", onClick = { agentError = null; dropping = true }, kind = ButtonKind.Ghost,
                    modifier = Modifier.fillMaxWidth().testTag("agent-remove"),
                )
            }
            ErrorLine(agentError)
        }
    }
    if (dropping) {
        val who = world.displayName(pubkey)
        ConfirmSheet(
            title = "Remove $who?",
            confirmLabel = "Remove $who",
            onConfirm = {
                try {
                    session.setMyAgent(pubkey, false)
                } catch (e: ActionException) {
                    agentError = e.shown()
                }
                dropping = false
            },
            onDismiss = { dropping = false },
            message = "It leaves the belfry and every channel. You can bring an agent again with a link from a channel's Invite.",
            danger = true,
        )
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

/** One of the contact card's three actions: a rounded square with its icon, the word under it. */
@Composable
private fun CardAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val c = Belfry.colors
    Column(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 56.dp, height = 40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp, color = c.text)
    }
}
