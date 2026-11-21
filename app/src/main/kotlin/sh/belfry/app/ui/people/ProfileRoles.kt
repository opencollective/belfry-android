package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.copyText
import sh.belfry.app.ui.kit.shareText
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.ChannelType
import sh.belfry.core.model.EventRole
import sh.belfry.core.model.GroupRole
import sh.belfry.core.model.PlaceNames
import sh.belfry.core.model.eventRole
import sh.belfry.core.model.groupRole
import sh.belfry.core.model.isAgent
import sh.belfry.core.model.isGuest
import sh.belfry.core.model.isLocked
import sh.belfry.core.net.JoinsAs
import sh.belfry.core.net.Role

/**
 * The person's role in the belfry (SPEC 19.31: Steward · Member · Guest, plus the belfry's own
 * roles): a picker for stewards, the word for everyone else. Saved with `POST /member-roles`
 * (a saved guest becomes a member with it).
 */
@Composable
fun RoleSection(pubkey: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val roles by session.members.roles.collectAsState()
    LaunchedEffect(Unit) { try { session.members.refreshRoles() } catch (e: Exception) { } }
    if (world.isAgent(pubkey)) return
    val guest = world.isGuest(pubkey)
    val current = when {
        world.isBelfryAdmin(pubkey) -> Role.ADMIN
        guest -> null
        else -> roles.members[pubkey] ?: roles.defaultRole
    }
    val label = current?.let { roles.role(it)?.label ?: if (it == Role.ADMIN) PlaceNames.STEWARD else PlaceNames.MEMBER } ?: PlaceNames.GUEST
    var open by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<sh.belfry.core.net.Role?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val steward = world.isCommunityAdmin && pubkey != world.me
    SectionTitle("Role")
    ActionGroup {
        Box {
            ActionRow(
                label, onClick = if (steward) ({ open = true }) else null, icon = BelfryIcons.Shield,
                sub = roles.role(current)?.description?.takeIf { it.isNotEmpty() } ?: if (guest) "In the channels they were invited to only" else null,
                chevron = steward, modifier = Modifier.testTag("role-row"),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (r in roles.roles) {
                    DropdownMenuItem(
                        text = { Text(r.label) },
                        leadingIcon = { Icon(if (r.id == current) BelfryIcons.Check else BelfryIcons.User, contentDescription = null) },
                        onClick = { open = false; if (r.id != current) asking = r },
                        modifier = Modifier.testTag("role-pick-${r.id}"),
                    )
                }
            }
        }
    }
    error?.let { Hint(it, Modifier.padding(top = 6.dp)) }
    asking?.let { r ->
        val who = world.displayName(pubkey)
        ConfirmSheet(
            title = if (guest) "Make $who a member as ${r.label}?" else "Make $who ${r.label}?",
            confirmLabel = "Make ${r.label}",
            onConfirm = {
                try { session.members.setMemberRole(pubkey, r.id); error = null } catch (e: ActionException) { error = e.message }
                asking = null
            },
            onDismiss = { asking = null },
            message = if (r.id == Role.ADMIN) "Stewards run the belfry: members, every channel, the settings, the charter." else null,
            danger = false,
        )
    }
}

/**
 * "In <group>" on a profile opened from a channel's, group's or event's people (SPEC 19.29): the
 * role there, a picker (Member / Admin; Attendee / Co-host / Door) for that group's admins and
 * belfry admins, saved with `/group-roles`, and "Remove from <group>". The host is never changed.
 */
@Composable
fun InGroupSection(pubkey: String, groupId: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val g = world.group(groupId) ?: return
    if (pubkey !in g.members && pubkey !in g.admins) return
    val event = if (g.type == ChannelType.EVENT) world.calEventFor(groupId) else null
    val may = (world.canAdmin(g)) && !world.isLocked(groupId)
    val role = world.groupRole(groupId, pubkey)
    val host = event != null && event.creator == pubkey
    val word = if (event != null) world.eventRole(event, pubkey).label else if (role == GroupRole.ADMIN) PlaceNames.STEWARD else PlaceNames.MEMBER
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val choices: List<Pair<String, GroupRole>> = if (event != null) EventRole.entries.filter { it != EventRole.HOST }.map { it.label to it.groupRole }
    else listOf(PlaceNames.MEMBER to GroupRole.MEMBER, PlaceNames.STEWARD to GroupRole.ADMIN)
    SectionTitle("In ${g.name}")
    ActionGroup {
        Box {
            ActionRow(word, onClick = if (may && !host) ({ open = true }) else null, icon = BelfryIcons.Users, chevron = may && !host, modifier = Modifier.testTag("in-group-role"))
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((label, r) in choices) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { Icon(if (label == word) BelfryIcons.Check else BelfryIcons.User, contentDescription = null) },
                        onClick = {
                            open = false
                            if (label != word) scope.launch {
                                try { session.members.setGroupRole(groupId, pubkey, r); error = null } catch (e: ActionException) { error = e.message ?: "The role could not be changed." }
                            }
                        },
                    )
                }
            }
        }
        if (may && !host && pubkey != world.me) {
            ActionRow("Remove from ${g.name}", onClick = { removing = true }, icon = BelfryIcons.Close, danger = true, modifier = Modifier.testTag("in-group-remove"))
        }
    }
    error?.let { Hint(it, Modifier.padding(top = 6.dp)) }
    if (removing) {
        ConfirmSheet(
            title = "Remove ${world.displayName(pubkey)} from ${g.name}?", confirmLabel = "Remove",
            onConfirm = { try { session.members.removeFromGroup(groupId, pubkey) } catch (e: ActionException) { error = e.message }; removing = false },
            onDismiss = { removing = false },
        )
    }
}

/**
 * The "Agents" section of a member's profile (canvas v93 home/profile-agents): one row per agent
 * of theirs (face, name), each opening that agent's profile. On my own profile the "Add an agent"
 * row comes last (SPEC 19.31; members only, not guests or agents): a one-agent link on the
 * Townsquare and one line to paste to the agent; stewards (no limit) see "Add another agent".
 */
@Composable
fun AgentsSection(pubkey: String, mine: Boolean) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = sh.belfry.app.ui.nav.LocalNav.current
    val agents = world.ctx.agents.filterValues { it == pubkey }.keys.sortedBy { world.realName(it).lowercase() }
    val canAdd = mine && !world.amGuest && !world.isAgent(world.me)
    if (agents.isEmpty() && !canAdd) return
    var room by remember { mutableStateOf<Int?>(null) }
    if (canAdd) LaunchedEffect(Unit) { room = try { session.myAgents().room } catch (e: ActionException) { null } }
    var adding by remember { mutableStateOf(false) }
    SectionTitle("Agents")
    ActionGroup(Modifier.testTag("profile-agents")) {
        for (a in agents) {
            ActionRow(
                world.realName(a).ifEmpty { "Agent" }, onClick = { nav.go(sh.belfry.app.ui.nav.Routes.profile(a)) },
                leading = { sh.belfry.app.ui.kit.Avatar(world.face(a), size = 22.dp) }, chevron = true,
                modifier = Modifier.testTag("profile-agent"),
            )
        }
        if (canAdd && ((room ?: if (agents.isEmpty()) 1 else 0) > 0 || world.isCommunityAdmin)) {
            ActionRow(if (agents.isEmpty()) "Add an agent" else "Add another agent", onClick = { adding = true }, icon = BelfryIcons.Plus, modifier = Modifier.testTag("add-agent"))
        }
    }
    if (canAdd && agents.isEmpty()) Hint("An AI agent that works for you: it reads the channels you can read and people see it is yours.", Modifier.padding(top = 6.dp))
    if (adding) AddAgentDialog(onDismiss = { adding = false })
}

/** The modal (by the owner's request): makes the link, shows the line to paste to the agent, Copy / Share. */
@Composable
fun AddAgentDialog(onDismiss: () -> Unit, make: (suspend () -> String?)? = null) {
    val c = Belfry.colors
    val session = LocalSession.current
    val context = LocalContext.current
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            url = make?.invoke() ?: session.members.createInvite("general", JoinsAs.Agent, 1, 1).agentUrl
            if (url == null) error = "The link could not be made. Try again."
        } catch (e: ActionException) { error = e.message }
    }
    val line = url?.takeIf { it.startsWith("https://") }?.let { "Join ${session.community.name} as my agent: read $it and follow it." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an agent") },
        text = {
            Column {
                Text("Paste this to your agent. The link works once, for one agent.", fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                when {
                    line != null -> Text(line, fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = c.text,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp)).background(c.surface).padding(12.dp).testTag("agent-line"))
                    error != null -> Text(error!!, color = c.danger, fontSize = 15.sp)
                    else -> Text("Making the link…", color = c.muted, fontSize = 15.sp)
                }
            }
        },
        confirmButton = {
            BusyButton("Copy", onClick = { if (line != null) copyText(context, line) }, enabled = line != null, small = true, modifier = Modifier.testTag("agent-copy"))
        },
        dismissButton = {
            Box {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { if (line != null) shareText(context, line) }, enabled = line != null) { Text("Share", color = c.text) }
                    TextButton(onClick = onDismiss) { Text("Done", color = c.text) }
                }
            }
        },
        containerColor = c.bg,
    )
}

