package sh.belfry.app.ui.info

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.Segmented
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.net.GuestLink
import sh.belfry.core.net.JoinsAs
import sh.belfry.core.net.MyAgents
import sh.belfry.core.net.Role

/** The channel a link to the belfry itself is made in (the web's `inviteIn`): its main channel. */
private const val MAIN_CHANNEL = "general"

private enum class SheetView { MAIN, JOINS, LINK }

/**
 * "Invite to <community>": the sheet the ＋ of the belfry's Members opens (canvas
 * inviting/sheet-from-members; the web's `InviteSheet preset="member"`, SPEC 19.70). The link is
 * made at once, for the safest choice (one person, a day, the default role for good); the rows
 * "Joins as" and "Link" open their choices inside the sheet, and a pick goes back and makes a new
 * link. No search by name: everyone in Members is in already.
 *
 * [makeLink] stands in for the belfry's `POST /channel-invites` (tests pass slow or failing ones).
 */
@Composable
fun InviteToCommunitySheet(
    onDismiss: () -> Unit,
    makeLink: (suspend (who: JoinsAs, people: Int, linkDays: Int) -> GuestLink)? = null,
) {
    BelfrySheet(onDismiss = onDismiss, modifier = Modifier.testTag("invite-sheet")) { InviteToCommunity(makeLink) }
}

@Composable
private fun InviteToCommunity(makeLink: (suspend (who: JoinsAs, people: Int, linkDays: Int) -> GuestLink)?) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val community = session.community.name
    val label = channelLabel(world.group(MAIN_CHANNEL), MAIN_CHANNEL)
    val roles by session.members.roles.collectAsState()
    val membership by session.members.membership.collectAsState()
    // never offer what the belfry will refuse: stewards, and members where Membership lets them
    val steward = world.isCommunityAdmin
    val mayMembers = steward || (membership.invite == "members" && membership.mayInviteMembers && !world.amGuest)

    var who by remember { mutableStateOf<JoinsAs?>(null) }
    var mine by remember { mutableStateOf<MyAgents?>(null) }
    var people by remember { mutableIntStateOf(1) }
    var linkDays by remember { mutableIntStateOf(1) }
    var link by remember { mutableStateOf<GuestLink?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf(SheetView.MAIN) }
    val asked = remember { intArrayOf(0) }

    fun fetch(w: JoinsAs, n: Int, days: Int) {
        val turn = ++asked[0] // a newer choice wins
        busy = true
        error = null
        link = null
        scope.launch {
            try {
                val made = if (makeLink != null) makeLink(w, w.people(n), days) else session.members.createInvite(MAIN_CHANNEL, w, n, days)
                if (turn == asked[0]) link = made
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActionException) {
                if (turn == asked[0]) error = e.message ?: "The link could not be made. Try again."
            } catch (e: Exception) {
                if (turn == asked[0]) error = "The link could not be made. Try again."
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

    LaunchedEffect(Unit) {
        if (who == null) pick(if (mayMembers) JoinsAs.Member(roles.defaultRole, "permanent") else JoinsAs.Guest(1))
        try { session.members.refreshMembership() } catch (e: Exception) { }
        if (steward) try { session.members.refreshRoles() } catch (e: Exception) { }
    }
    LaunchedEffect(Unit) { mine = try { session.myAgents() } catch (e: Exception) { null } }

    val w = who
    val room = mine?.room ?: 0
    val within = JoinsAs.daysWord(linkDays)

    when (view) {
        SheetView.JOINS -> {
            BackHandler { view = SheetView.MAIN }
            SheetHead("Joins as", onBack = { view = SheetView.MAIN })
            val m = mine
            if (m != null && m.limit > 0) {
                ActionGroup {
                    ChoiceRow(
                        "My own agent",
                        if (room > 0 || w == JoinsAs.Agent) "A member for as long as you are one" else "You have " + (if (m.limit == 1) "your agent" else "your ${m.limit} agents") + " already. Remove one from its profile to bring another.",
                        w == JoinsAs.Agent, BelfryIcons.Shield, enabled = !busy && (room > 0 || w == JoinsAs.Agent), tag = "invite-own-agent",
                    ) { pick(JoinsAs.Agent); view = SheetView.MAIN }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (mayMembers) {
                val cur = w as? JoinsAs.Member
                val term = cur?.membership ?: "permanent"
                Hint("Member of $community")
                Spacer(Modifier.height(6.dp))
                Segmented(
                    JoinsAs.MEMBERSHIPS.map { JoinsAs.membershipWord(it).replaceFirstChar { ch -> ch.uppercase() } }, JoinsAs.MEMBERSHIPS.indexOf(term).coerceAtLeast(0),
                    { k -> pick(JoinsAs.Member(cur?.role ?: roles.defaultRole, JoinsAs.MEMBERSHIPS[k])) }, enabled = !busy, modifier = Modifier.testTag("invite-term"),
                )
                Spacer(Modifier.height(8.dp))
                ActionGroup {
                    // a member who may invite members gives the default role only (the belfry refuses the rest)
                    val choices = roles.roles.filter { steward || it.id == roles.defaultRole }
                    for (r in choices) ChoiceRow(r.label, r.description, cur?.role == r.id, BelfryIcons.Users, enabled = !busy, tag = "invite-role-${r.id}") { pick(JoinsAs.Member(r.id, term)); view = SheetView.MAIN }
                }
                Hint("Picking a role goes back and makes a new link.", Modifier.padding(top = 6.dp))
            } else {
                Hint("Only stewards invite new members here.")
            }
        }
        SheetView.LINK -> {
            BackHandler { view = SheetView.MAIN }
            SheetHead("Link", onBack = { view = SheetView.MAIN })
            val single = w is JoinsAs.Agent || (w is JoinsAs.Member && w.role == Role.ADMIN)
            Hint("How many people")
            Spacer(Modifier.height(6.dp))
            ActionGroup {
                for ((n, words) in listOf(1 to "One person", 50 to "Up to 50 people")) {
                    val on = if (single) n == 1 else people == n
                    ChoiceRow(words, "", on, BelfryIcons.Users, enabled = !busy && (n == 1 || !single), tag = "invite-people-$n") {
                        if (n != people && w != null) { people = n; if (!single) fetch(w, n, linkDays) }
                        view = SheetView.MAIN
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Hint("The link can be opened for")
            Spacer(Modifier.height(6.dp))
            ActionGroup {
                for ((d, words) in listOf(1 to "1 day", 7 to "1 week", 30 to "1 month")) ChoiceRow(words, "", linkDays == d, BelfryIcons.Clock, enabled = !busy, tag = "invite-days-$d") {
                    if (d != linkDays && w != null) { linkDays = d; fetch(w, people, d) }
                    view = SheetView.MAIN
                }
            }
            Hint(
                (if (single) (if (w is JoinsAs.Agent) "A link for your agent works once. " else "A link that makes someone a Steward works once. ") else "") +
                    "How long they stay is chosen under “Joins as”.",
                Modifier.padding(top = 6.dp),
            )
        }
        SheetView.MAIN -> {
            SheetHead("Invite to $community")
            val made = link
            val (asName, asDesc) = w?.line(label, community, roles, toBelfry = true) ?: ("" to "")
            LinkBox(
                // my agent reads its instructions (agent_url): that is the link to hand it
                url = (if (w is JoinsAs.Agent) made?.agentUrl ?: made?.url else made?.url) ?: session.community.channelLink(MAIN_CHANNEL),
                shareTitle = community,
                waiting = made == null, busy = busy, error = error,
                onRetry = { if (w != null) fetch(w, people, linkDays) },
                qrSize = 184.dp,
                between = {
                    ActionGroup {
                        ActionRow("Joins as", onClick = { view = SheetView.JOINS }, icon = BelfryIcons.Users, value = asName, sub = asDesc, chevron = true, enabled = !busy, modifier = Modifier.testTag("invite-joins"))
                        ActionRow(
                            "Link", onClick = { view = SheetView.LINK }, icon = BelfryIcons.Link,
                            sub = (if ((w?.people(people) ?: 1) == 1) "One person" else "Up to 50 people") + ", the link works for $within",
                            chevron = true, enabled = !busy, modifier = Modifier.testTag("invite-link"),
                        )
                    }
                },
            )
            Hint(
                when (w) {
                    JoinsAs.Agent -> "Give this link to your agent, within $within: it reads the instructions there and joins with its own key. It works once."
                    is JoinsAs.Member -> "They join $community as ${roles.role(w.role)?.label ?: "Member"} with their name and e-mail address."
                    else -> "They read $label at once and join with a name and an e-mail address; someone who has an account joins with it. Everyone here sees who invited them."
                },
                Modifier.padding(top = 12.dp).testTag("invite-line"),
            )
        }
    }
}

/** The sheet's own title line; with a back arrow on a page of choices (back returns to the sheet's first page). */
@Composable
private fun SheetHead(title: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            BackButton(onBack)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}
