package sh.belfry.app.ui.info

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
    makeLink: (suspend (people: Int, linkDays: Int, agent: Boolean) -> GuestLink)? = null,
    invite: (suspend (pubkey: String) -> Session.Invited)? = null,
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val g = world.group(id)
    val label = channelLabel(g, id)
    val memberUrl = remember(id) { session.community.channelLink(id) }

    var guest by remember { mutableStateOf(false) }
    var own by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf<MyAgents?>(null) }
    LaunchedEffect(Unit) { mine = try { session.myAgents() } catch (e: ActionException) { null } }
    val room = mine?.room ?: 0
    var people by remember { mutableIntStateOf(1) }
    var linkDays by remember { mutableIntStateOf(1) }
    var link by remember { mutableStateOf<GuestLink?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val asked = remember { intArrayOf(0) }

    fun fetch(n: Int, days: Int, agent: Boolean) {
        val turn = ++asked[0] // a newer choice wins
        busy = true
        error = null
        link = null
        scope.launch {
            try {
                val made = if (makeLink != null) makeLink(if (agent) 1 else n, days, agent) else session.createGuestLink(id, if (agent) 1 else n, days, agent)
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

    fun pickGuest(on: Boolean) {
        guest = on
        own = false
        if (on) fetch(people, linkDays, false) else { error = null; link = null }
    }

    fun pickOwn(on: Boolean) {
        own = on
        guest = false
        if (on) fetch(1, linkDays, true) else { error = null; link = null }
    }

    LaunchedEffect(Unit) { if (startAsGuest) pickGuest(true) }

    InfoPage("Invite to $label") {
        InviteSomeone(id, invite)
        Spacer(Modifier.height(16.dp))
        ActionGroup {
            SwitchRow(
                "Guest access for 30 days", guest, { pickGuest(it) }, icon = BelfryIcons.User, busy = busy && guest,
                sub = "For a person or an AI agent who isn't a member: this channel only, nothing else.",
                modifier = Modifier.testTag("invite-guest"),
            )
            val m = mine
            if (m != null && m.limit > 0) {
                Hairline()
                SwitchRow(
                    "My own agent", own, { pickOwn(it) }, icon = BelfryIcons.Shield, busy = busy && own,
                    enabled = room > 0 || own,
                    sub = if (room > 0 || own) {
                        "For your AI agent: it joins as a member and stays for as long as you are one. Everyone sees it is yours."
                    } else {
                        "You have " + (if (m.limit == 1) "your agent" else "your ${m.limit} agents") + " already (" +
                            m.agents.joinToString(", ") { it.name.ifEmpty { "Agent" } } + "). Remove one from its profile to bring another."
                    },
                    modifier = Modifier.testTag("invite-own-agent"),
                )
            }
        }
        if (guest || own) {
            if (guest) {
                Spacer(Modifier.height(12.dp))
                Text("How many", fontSize = 13.sp, color = c.muted)
                Spacer(Modifier.height(6.dp))
                Segmented(
                    PEOPLE.map { it.second }, PEOPLE.indexOfFirst { it.first == people },
                    { i -> val n = PEOPLE[i].first; if (n != people) { people = n; fetch(n, linkDays, false) } },
                    enabled = !busy,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Link works for", fontSize = 13.sp, color = c.muted)
            Spacer(Modifier.height(6.dp))
            Segmented(
                LINK_DAYS.map { it.second }, LINK_DAYS.indexOfFirst { it.first == linkDays },
                { i -> val d = LINK_DAYS[i].first; if (d != linkDays) { linkDays = d; fetch(people, d, own) } },
                enabled = !busy,
            )
        }
        Spacer(Modifier.height(18.dp))
        val linked = guest || own
        val made = link
        val pending = linked && made == null
        LinkBox(
            // my agent reads its instructions (agent_url): that is the link to hand it
            url = if (own) made?.agentUrl ?: made?.url ?: memberUrl else if (guest) made?.url ?: memberUrl else memberUrl,
            shareTitle = label,
            waiting = pending, busy = busy, error = error,
            waitingText = "Making the link…",
            onRetry = { fetch(people, linkDays, own) },
        )
        Spacer(Modifier.height(14.dp))
        val within = when (linkDays) { 1 -> "a day"; 7 -> "a week"; else -> "a month" }
        Hint(
            when {
                own -> "Give this link to your agent, within $within: it reads the instructions there and joins with its own key. It works once."
                guest -> (if (people == 1) "For one person" else "For up to 50 people") + ", within $within" +
                    ". They give a name and are in; someone who has an account joins with it. Everyone here sees who invited them."
                else -> "Members open $label with this link. For someone who isn't a member yet, turn on guest access above."
            },
        )
        Spacer(Modifier.height(24.dp))
    }
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
        q, { q = it; error = null }, label = "Invite someone who is here already", placeholder = "Type a name",
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
