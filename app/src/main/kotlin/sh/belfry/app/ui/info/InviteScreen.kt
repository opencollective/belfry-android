package sh.belfry.app.ui.info

import androidx.compose.runtime.LaunchedEffect
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.core.net.Role
import sh.belfry.core.net.JoinsAs
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.engine.Session
import sh.belfry.core.model.inChannel
import sh.belfry.core.model.inviteCandidates
import sh.belfry.core.model.isGuest
import sh.belfry.core.model.kindLabel
import sh.belfry.core.net.GuestLink
import sh.belfry.core.net.MyAgents

private val PEOPLE = listOf(1 to "One person", 50 to "Up to 50")
private val LINK_DAYS = listOf(1 to "1 day", 7 to "1 week", 30 to "1 month")

/**
 * Invite people to a channel (the web's InviteSheet, village-app 453684b):
 * - "Invite someone who is here already": a name finds members, guests and agents (marked as what
 *   they are); a member gets one direct message with the channel's link (and is admitted at once
 *   to a closed pod I administer), a guest of another channel is added as this one's guest.
 * - the channel's link (members open it directly) with QR code, copy and the system share sheet;
 *   any member can make it a guest link instead (one person and a day unless asked otherwise), or
 *   a link for "My own agent" (one agent that joins as a member and stays while I am one).
 *
 * [makeLink] (people, link days, agent) and [invite] are the belfry calls (tests pass slow ones).
 */
@Composable
fun InviteScreen(
    id: String,
    startAsGuest: Boolean = false,
    makeLink: (suspend (who: JoinsAs, people: Int, linkDays: Int) -> GuestLink)? = null,
    invite: (suspend (pubkey: String) -> Session.Invited)? = null,
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val g = world.group(id)
    val label = channelLabel(g, id)
    val memberUrl = remember(id) { session.community.channelLink(id) }
    val roles by session.members.roles.collectAsState()
    val membership by session.members.membership.collectAsState()
    LaunchedEffect(Unit) {
        try { session.members.refreshMembership() } catch (e: Exception) { }
        if (world.isCommunityAdmin) try { session.members.refreshRoles() } catch (e: Exception) { }
    }
    // option C (docs/roles.md): a guest for a day / week / month, my own agent, or (stewards, and
    // members where the belfry lets them) a member with a role for a month, a year or for good
    val mayMembers = world.isCommunityAdmin || (membership.invite == "members" && membership.mayInviteMembers && !world.amGuest)

    var who by remember { mutableStateOf<JoinsAs?>(null) }
    var mine by remember { mutableStateOf<MyAgents?>(null) }
    LaunchedEffect(Unit) { mine = try { session.myAgents() } catch (e: ActionException) { null } }
    val room = mine?.room ?: 0
    var people by remember { mutableIntStateOf(1) }
    var linkDays by remember { mutableIntStateOf(1) }
    var link by remember { mutableStateOf<GuestLink?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var rolePicker by remember { mutableStateOf(false) }
    val asked = remember { intArrayOf(0) }

    fun fetch(w: JoinsAs, n: Int, days: Int) {
        val turn = ++asked[0] // a newer choice wins
        busy = true
        error = null
        link = null
        scope.launch {
            try {
                val made = if (makeLink != null) makeLink(w, w.people(n), days) else session.members.createInvite(id, w, n, days)
                if (turn == asked[0]) link = made
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActionException) {
                if (turn == asked[0]) error = e.message ?: "The link could not be made."
            } catch (e: Exception) {
                if (turn == asked[0]) error = "The link could not be made."
            } finally {
                if (turn == asked[0]) busy = false
            }
        }
    }

    fun pick(w: JoinsAs) {
        if (busy || w == who) return
        who = w
        fetch(w, people, linkDays)
    }

    LaunchedEffect(Unit) { if (startAsGuest) pick(JoinsAs.Guest(1)) }

    InfoPage("Invite to $label") {
        Text("Joins as", fontSize = 13.sp, color = c.muted)
        Spacer(Modifier.height(6.dp))
        ActionGroup {
            for ((days, tag) in listOf(1 to "invite-guest", 7 to "invite-guest-week", 30 to "invite-guest-month")) {
                val w = JoinsAs.Guest(days)
                val (title, sub) = w.line(label, session.community.name, roles)
                ChoiceRow(title, sub, who == w, BelfryIcons.User, enabled = !busy, tag = tag) { pick(w) }
            }
            val m = mine
            if (m != null && m.limit > 0) {
                val (title, sub) = JoinsAs.Agent.line(label, session.community.name, roles)
                ChoiceRow(
                    title,
                    if (room > 0 || who == JoinsAs.Agent) sub else "You have " + (if (m.limit == 1) "your agent" else "your ${m.limit} agents") + " already. Remove one from its profile to bring another.",
                    who == JoinsAs.Agent, BelfryIcons.Shield, enabled = !busy && (room > 0 || who == JoinsAs.Agent), tag = "invite-own-agent",
                ) { pick(JoinsAs.Agent) }
            }
            if (mayMembers) {
                val cur = who as? JoinsAs.Member
                val (title, sub) = (cur ?: JoinsAs.Member(roles.defaultRole, "permanent")).line(label, session.community.name, roles)
                Box {
                    ChoiceRow(if (cur != null) title else "A member", if (cur != null) sub else "Of ${session.community.name}, with a role", cur != null, BelfryIcons.Users, enabled = !busy, tag = "invite-member") { rolePicker = true }
                    DropdownMenu(expanded = rolePicker, onDismissRequest = { rolePicker = false }) {
                        val choices = roles.roles.filter { world.isCommunityAdmin || it.id == roles.defaultRole }
                        for (r in choices) for (ms in JoinsAs.MEMBERSHIPS) {
                            DropdownMenuItem(
                                text = { Text("${r.label} · ${JoinsAs.membershipWord(ms)}") },
                                leadingIcon = { Icon(BelfryIcons.User, contentDescription = null) },
                                onClick = { rolePicker = false; pick(JoinsAs.Member(r.id, ms)) },
                                modifier = Modifier.testTag("invite-role-${r.id}-$ms"),
                            )
                        }
                    }
                }
            }
        }
        val w = who
        if (w != null && w !is JoinsAs.Agent && !(w is JoinsAs.Member && w.role == Role.ADMIN)) {
            Spacer(Modifier.height(12.dp))
            Text("How many", fontSize = 13.sp, color = c.muted)
            Spacer(Modifier.height(6.dp))
            Segmented(
                PEOPLE.map { it.second }, PEOPLE.indexOfFirst { it.first == people },
                { i -> val n = PEOPLE[i].first; if (n != people) { people = n; fetch(w, n, linkDays) } },
                enabled = !busy,
            )
        }
        if (w != null) {
            Spacer(Modifier.height(10.dp))
            Text("Link works for", fontSize = 13.sp, color = c.muted)
            Spacer(Modifier.height(6.dp))
            Segmented(
                LINK_DAYS.map { it.second }, LINK_DAYS.indexOfFirst { it.first == linkDays },
                { i -> val d = LINK_DAYS[i].first; if (d != linkDays) { linkDays = d; fetch(w, people, d) } },
                enabled = !busy,
            )
        }
        Spacer(Modifier.height(18.dp))
        val made = link
        val pending = w != null && made == null
        LinkBox(
            // my agent reads its instructions (agent_url): that is the link to hand it
            url = if (w is JoinsAs.Agent) made?.agentUrl ?: made?.url ?: memberUrl else if (w != null) made?.url ?: memberUrl else memberUrl,
            shareTitle = label,
            waiting = pending, busy = busy, error = error,
            waitingText = "Making the link…",
            onRetry = { if (w != null) fetch(w, people, linkDays) },
        )
        Spacer(Modifier.height(14.dp))
        val within = when (linkDays) { 1 -> "a day"; 7 -> "a week"; else -> "a month" }
        Hint(
            when (w) {
                JoinsAs.Agent -> "Give this link to your agent, within $within: it reads the instructions there and joins with its own key. It works once."
                is JoinsAs.Guest -> (if (people == 1) "For one person" else "For up to 50 people") + ", within $within" +
                    ". They read $label at once and join with a name and an e-mail address; someone who has an account joins with it. Everyone here sees who invited them."
                is JoinsAs.Member -> "They join ${session.community.name} as a member, within $within, starting in $label."
                null -> "Members open $label with this link. For someone who isn't a member yet, pick how they join above."
            },
        )
        Spacer(Modifier.height(20.dp))
        Text("Or someone already in the community", fontSize = 15.sp, color = c.muted)
        Spacer(Modifier.height(8.dp))
        InviteSomeone(id, invite)
        Spacer(Modifier.height(24.dp))
    }
}

/** One choice of "Joins as": a list row with a check when chosen (Discord-style list, not stacked buttons). */
@Composable
private fun ChoiceRow(title: String, sub: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, tag: String, onClick: () -> Unit) {
    ActionRow(
        title, onClick = onClick, icon = icon, sub = sub, enabled = enabled,
        modifier = Modifier.testTag(tag).semantics { selected = on },
        trailing = if (on) ({ Icon(BelfryIcons.Check, contentDescription = "Chosen", tint = Belfry.colors.text, modifier = Modifier.size(20.dp)) }) else null,
    )
}

/** What the row says once an invite went out (the web's labels). */
private fun said(r: Session.Invited): String = when (r) {
    Session.Invited.ADMITTED -> "Added · link sent"
    Session.Invited.ASKED -> "Link sent · they apply"
    Session.Invited.SENT -> "Link sent"
    Session.Invited.GUEST -> "Added as a guest"
}

/** "Invite someone who is here already": a name, then one row per person found. */
@Composable
private fun InviteSomeone(id: String, invite: (suspend (String) -> Session.Invited)?) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val g = world.group(id)
    var q by remember { mutableStateOf("") }
    val done = remember { mutableStateMapOf<String, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    val found = world.inviteCandidates(q)
    BelfryTextField(
        q, { q = it; error = null }, label = "Invite someone already in the community", placeholder = "Type a name",
        modifier = Modifier.fillMaxWidth().testTag("invite-search"),
    )
    if (found.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        ActionGroup {
            found.forEachIndexed { i, pk ->
                if (i > 0) Hairline()
                val here = world.inChannel(g, pk)
                val guestHere = world.isGuest(pk)
                val state = (world.kindLabel(pk)?.let { "$it · " } ?: "") +
                    if (here) (if (g?.closed == true || guestHere) "Already in" else "Can already open it") else "Not in this channel"
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("invite-row"), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(world.face(pk), size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(world.displayName(pk), fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state, fontSize = 12.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    val ok = done[pk]
                    if (ok != null || (here && guestHere)) {
                        Text(ok ?: "", fontSize = 13.sp, color = c.muted)
                    } else {
                        BusyButton(
                            if (guestHere) "Add as a guest" else if (here) "Send the link" else "Invite",
                            onClick = { done[pk] = said(if (invite != null) invite(pk) else session.inviteMember(id, pk)) },
                            kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("invite-send"),
                            onError = { error = (it as? ActionException)?.message ?: "Could not invite them. Try again." },
                        )
                    }
                }
            }
        }
    } else if (q.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Hint("Nobody here by that name. For someone new, use a link below.")
    }
    error?.let { ErrorLine(it, Modifier.padding(top = 6.dp)) }
}
