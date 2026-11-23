package sh.belfry.app.ui.info

import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
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
 * From the belfry's Members the invitation is a sheet: [InviteToCommunitySheet].
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
    var editing by remember { mutableStateOf(false) }
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

    // inviting/sheet-from-channel: people already here first; the link folded under "Or invite an
    // external person" and made only when that is opened (no link before).
    var external by remember { mutableStateOf(startAsGuest) }
    var panel by remember { mutableStateOf(InvitePanel.MAIN) }
    LaunchedEffect(external) {
        if (external && who == null) pick(if (mayMembers) JoinsAs.Member(roles.defaultRole, "permanent") else JoinsAs.Guest(1))
    }
    val community = session.community.name
    val w = who
    val within = when (linkDays) { 1 -> "a day"; 7 -> "a week"; else -> "a month" }

    when (panel) {
        // inviting/sheet-member: who the link makes them
        InvitePanel.JOINS -> {
            BackHandler { panel = InvitePanel.MAIN }
            InfoSubPage("Joins as", onBack = { panel = InvitePanel.MAIN }) {
                Hint("Guest of $label")
                Spacer(Modifier.height(6.dp))
                ActionGroup {
                    for ((days, tag) in listOf(1 to "invite-guest", 7 to "invite-guest-week", 30 to "invite-guest-month")) {
                        val x = JoinsAs.Guest(days)
                        ChoiceRow("For " + (when (days) { 1 -> "a day"; 7 -> "a week"; else -> "a month" }), x.line(label, community, roles).second, who == x, BelfryIcons.User, enabled = !busy, tag = tag) { pick(x); panel = InvitePanel.MAIN }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val m = mine
                if (m != null && m.limit > 0) {
                    ActionGroup {
                        ChoiceRow(
                            "My own agent",
                            if (room > 0 || who == JoinsAs.Agent) "A member for as long as you are one" else "You have " + (if (m.limit == 1) "your agent" else "your ${m.limit} agents") + " already. Remove one from its profile to bring another.",
                            who == JoinsAs.Agent, BelfryIcons.Shield, enabled = !busy && (room > 0 || who == JoinsAs.Agent), tag = "invite-own-agent",
                        ) { pick(JoinsAs.Agent); panel = InvitePanel.MAIN }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (mayMembers) {
                    val cur = who as? JoinsAs.Member
                    val term = cur?.membership ?: "permanent"
                    Hint("Member of $community")
                    Spacer(Modifier.height(6.dp))
                    Segmented(JoinsAs.MEMBERSHIPS.map { JoinsAs.membershipWord(it).replaceFirstChar { ch -> ch.uppercase() } }, JoinsAs.MEMBERSHIPS.indexOf(term).coerceAtLeast(0),
                        { k -> pick(JoinsAs.Member(cur?.role ?: roles.defaultRole, JoinsAs.MEMBERSHIPS[k])) }, enabled = !busy, modifier = Modifier.testTag("invite-term"))
                    Spacer(Modifier.height(8.dp))
                    ActionGroup {
                        val choices = roles.roles.filter { world.isCommunityAdmin || it.id == roles.defaultRole }
                        for (r in choices) ChoiceRow(r.label, r.description, cur?.role == r.id, BelfryIcons.Users, enabled = !busy, tag = "invite-role-${r.id}") { pick(JoinsAs.Member(r.id, term)); panel = InvitePanel.MAIN }
                    }
                    Hint("Member choices are for Stewards. A member may invite members only when Membership settings allow it, and then with the default role only.", Modifier.padding(top = 6.dp))
                }
            }
        }
        // inviting/sheet-link: how many people, for how long
        InvitePanel.LINK -> {
            BackHandler { panel = InvitePanel.MAIN }
            InfoSubPage("Link", onBack = { panel = InvitePanel.MAIN }) {
                val single = w is JoinsAs.Agent || (w is JoinsAs.Member && w.role == Role.ADMIN)
                Hint("How many people")
                Spacer(Modifier.height(6.dp))
                ActionGroup {
                    for ((n, words) in listOf(1 to "One person", 50 to "Up to 50 people")) {
                        ChoiceRow(words, "", people == n, BelfryIcons.Users, enabled = !busy && (n == 1 || !single), tag = "invite-people-$n") {
                            if (n != people && w != null) { people = n; fetch(w, n, linkDays) }; panel = InvitePanel.MAIN
                        }
                    }
                }
                Hint("Up to 50 is off only for a Steward link or an agent link: those work once.", Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                Hint("The link can be opened for")
                Spacer(Modifier.height(6.dp))
                ActionGroup {
                    for ((d, words) in LINK_DAYS) ChoiceRow(words, "", linkDays == d, BelfryIcons.Clock, enabled = !busy, tag = "invite-days-$d") {
                        if (d != linkDays && w != null) { linkDays = d; fetch(w, people, d) }; panel = InvitePanel.MAIN
                    }
                }
                Hint("How long they stay is chosen under \u201cJoins as\u201d. Picking a row goes back and makes a new link.", Modifier.padding(top = 6.dp))
            }
        }
        InvitePanel.MAIN -> InfoPage("Invite to $label") {
            InviteSomeone(id, invite) // its field says "Invite someone already in the community"
            Hint("They get the link to $label as a direct message. A guest of another channel is added here as a guest.", Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            ActionGroup {
                ActionRow(
                    "Or invite an external person", onClick = { external = !external }, icon = BelfryIcons.Qr,
                    sub = if (external) null else "A link or a QR code for someone who is not in $community yet",
                    modifier = Modifier.testTag("invite-external").semantics { selected = external },
                    trailing = { Icon(if (external) BelfryIcons.ChevronUp else BelfryIcons.ChevronDown, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp)) },
                )
            }
            if (!external) Hint("No link is made until you open this.", Modifier.padding(top = 6.dp))
            if (external) {
                Spacer(Modifier.height(14.dp))
                val made = link
                LinkBox(
                    // my agent reads its instructions (agent_url): that is the link to hand it
                    url = if (w is JoinsAs.Agent) made?.agentUrl ?: made?.url ?: memberUrl else made?.url ?: memberUrl,
                    shareTitle = label,
                    waiting = w != null && made == null, busy = busy, error = error,
                    waitingText = "Making the link…",
                    onRetry = { if (w != null) fetch(w, people, linkDays) },
                )
                Spacer(Modifier.height(12.dp))
                // canvas inviting/sheet-external v45/v46: the role first. Stewards: Member | Steward
                // (Member by default); a member the belfry lets invite members: a member link, no
                // switch; anyone else: a guest link. Never offer what the server will refuse.
                val steward = world.isCommunityAdmin
                // canvas v48: radio rows, only the roles the viewer may give; one role = no rows
                val term = (w as? JoinsAs.Member)?.membership ?: "permanent"
                val mayAgent = (mine?.limit ?: 0) > 0 && (room > 0 || w == JoinsAs.Agent)
                val asRows = buildList {
                    if (mayMembers) add(Triple("Member", "Joins open channels, invites guests, votes.", JoinsAs.Member(roles.defaultRole, term)))
                    if (steward) add(Triple("Steward", "Runs the belfry: settings, roles, the charter.", JoinsAs.Member(Role.ADMIN, term)))
                    if (mayAgent) add(Triple("Agent", "Your agent, a member while you are one.", JoinsAs.Agent))
                }
                if (asRows.size > 1) {
                    Hint("Invite as")
                    Spacer(Modifier.height(6.dp))
                    ActionGroup(Modifier.testTag("invite-as")) {
                        for ((title, line, x) in asRows) {
                            val on = when (x) {
                                is JoinsAs.Member -> w is JoinsAs.Member && (w.role == Role.ADMIN) == (x.role == Role.ADMIN)
                                else -> w == x
                            }
                            ChoiceRow(title, line, on, if (x == JoinsAs.Agent) BelfryIcons.Shield else BelfryIcons.Users, enabled = !busy, tag = "invite-as-" + title.lowercase()) {
                                if (x is JoinsAs.Member && x.role == Role.ADMIN) people = 1
                                if (x == JoinsAs.Agent) people = 1
                                pick(x)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                // one line that always says what the link does now, then Edit
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(inviteLine(w, people, linkDays, label), fontSize = 15.sp, lineHeight = 21.sp, color = c.text, modifier = Modifier.weight(1f).testTag("invite-line"))
                    androidx.compose.material3.TextButton(onClick = { editing = !editing }, enabled = !busy, modifier = Modifier.testTag("invite-edit")) {
                        Text(if (editing) "Done" else "Edit", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    }
                }
                if (!steward && !mayMembers) Hint("Only stewards invite new members here.", Modifier.padding(top = 2.dp))
                if (editing) {
                    Spacer(Modifier.height(8.dp))
                    val single = w is JoinsAs.Agent || (w is JoinsAs.Member && w.role == Role.ADMIN)
                    ActionGroup {
                        ActionRow("People", onClick = { panel = InvitePanel.LINK }, value = if (people == 1) "One person" else "Up to 50", sub = if (single) null else "or up to 50", chevron = true, enabled = !busy, modifier = Modifier.testTag("invite-link"))
                        ActionRow("Works for", onClick = { panel = InvitePanel.LINK }, value = when (linkDays) { 1 -> "24 hours"; 7 -> "A week"; else -> "A month" }, chevron = true, enabled = !busy, modifier = Modifier.testTag("invite-works"))
                        if (w is JoinsAs.Member) {
                            ActionRow("Stays", onClick = { panel = InvitePanel.JOINS }, value = JoinsAs.membershipWord(w.membership).replaceFirstChar { it.uppercase() }, chevron = true, enabled = !busy, modifier = Modifier.testTag("invite-stays"))
                        }
                        if (steward || mayMembers || (mine?.limit ?: 0) > 0) {
                            ActionRow("Instead", onClick = { panel = InvitePanel.JOINS }, value = if (w is JoinsAs.Guest) "Guest of $label" else if (w is JoinsAs.Agent) "My own agent" else "Guest of $label only", sub = "for a day, a week, a month", chevron = true, enabled = !busy, modifier = Modifier.testTag("invite-joins"))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private enum class InvitePanel { MAIN, JOINS, LINK }

/**
 * What the link does now (canvas inviting/sheet-external v45): "This QR code and link work for one
 * person for 24 hours.", "…let one person join #Garden as a guest for a day.".
 */
internal fun inviteLine(w: JoinsAs?, people: Int, linkDays: Int, label: String): String {
    val who = if (people == 1) "one person" else "up to 50 people"
    return when (w) {
        is JoinsAs.Guest -> "This QR code and link let $who join $label as a guest for " + (when (w.days) { 1 -> "a day"; 7 -> "a week"; else -> "a month" }) + "."
        JoinsAs.Agent -> "This link brings your own agent in, once, within " + (when (linkDays) { 1 -> "24 hours"; 7 -> "a week"; else -> "a month" }) + "."
        else -> "This QR code and link work for $who for " + (when (linkDays) { 1 -> "24 hours"; 7 -> "a week"; else -> "a month" }) + "."
    }
}

/** A choice page inside the invite flow, with its own back arrow (back returns to the invite page, never pushes). */
@Composable
private fun InfoSubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg)) {
        TopBar(title, navigation = { BackButton(onBack) })
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), content = content)
    }
}

/** One choice of "Joins as": a list row with a check when chosen (Discord-style list, not stacked buttons). */
@Composable
internal fun ChoiceRow(title: String, sub: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, tag: String, onClick: () -> Unit) {
    ActionRow(
        title, onClick = onClick, icon = icon, sub = sub.ifEmpty { null }, enabled = enabled,
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

/** "Invite a community member:" (canvas v44): a name, then one row per person found. */
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
        q, { q = it; error = null }, label = "Invite a community member:", placeholder = "Invite a community member",
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
                    if (here) sh.belfry.core.model.Followers.alreadyHere(g, guestHere) else "Not in this channel"
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
