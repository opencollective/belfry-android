package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Proposal
import sh.belfry.core.model.ProposalVersion
import sh.belfry.core.model.options
import sh.belfry.core.model.proposalVersions
import sh.belfry.core.model.voteTurnout
import sh.belfry.core.model.votesByOption

/** Which pane is under a proposal's header: its chat, its versions, its votes. */
enum class ProposalTab(val label: String) { CHAT("Chat"), VERSIONS("Versions"), VOTES("Votes") }

/** The tabs a proposal has: Chat always; Versions once a version 2 exists; Votes once someone voted. */
fun proposalTabs(world: sh.belfry.core.model.World, p: Proposal): List<ProposalTab> = buildList {
    add(ProposalTab.CHAT)
    if (world.proposalVersions(p).size > 1) add(ProposalTab.VERSIONS)
    if (!p.poll?.votes.isNullOrEmpty()) add(ProposalTab.VOTES)
}

/** "12 yes · 2 no · 1 abstain": the current poll's tally in its own options' words. */
fun tallyLine(p: Proposal): String {
    val t = p.tally
    return p.options.joinToString(" · ") { (id, label) -> "${t[id] ?: 0} ${label.lowercase()}" }
}

/**
 * A proposal at the top of its chat (round 6, boards channels/proposal-top and
 * channels/proposal-folded; SPEC 19.42): the event screen's three levels. Half = status, author and
 * version, title, the start of the summary, "Read the full proposal", the vote buttons (the poll's
 * own options with counts, mine filled) and the turnout; full = the whole text, Change the status,
 * New version; up = one bar with the title, status, tally and Vote.
 */
@Composable
fun ProposalTop(p: Proposal, level: EventLevel, onLevel: (EventLevel) -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val mine = world.myVote(p)
    val pending = p.poll?.let { world.isPending(it.ev.id) } ?: false
    var revising by remember(p.coord) { mutableStateOf(false) }
    var status by remember(p.coord) { mutableStateOf(false) }
    val busy = rememberBusy()

    if (level == EventLevel.UP) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp).testTag("proposal-bar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(p.status.label, p.status.tone())
                    if (p.poll != null) {
                        GapW(8)
                        Text(tallyLine(p), fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (p.votingNow) {
                GapW(8)
                val voted = p.options.firstOrNull { it.first == mine }?.second
                BusyButton(if (voted != null) "Voted $voted" else "Vote", onClick = { onLevel(EventLevel.HALF) },
                    kind = if (voted != null) ButtonKind.Ghost else ButtonKind.Primary, small = true, modifier = Modifier.testTag("proposal-vote"))
            }
        }
    } else {
        val full = level == EventLevel.FULL
        Column(
            (if (full) Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp) else Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp))
                .fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("proposal-top-${level.name.lowercase()}"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(p.status.label, p.status.tone())
                GapW(8)
                Text("Proposal by ${world.displayName(p.author)} · version ${p.version}", fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(p.title, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, color = c.text)
            if (full) {
                if (p.summary.isNotEmpty()) Text(p.summary, fontSize = 16.sp, lineHeight = 22.sp, color = c.muted)
                if (p.text.isNotEmpty()) MarkdownText(p.text, rich = false) else Muted("No text yet.")
            } else {
                Text(p.summary.ifEmpty { p.text }, fontSize = 16.sp, lineHeight = 22.sp, color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "Read the full proposal", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(role = Role.Button) { onLevel(EventLevel.FULL) }.padding(vertical = 2.dp).testTag("proposal-read"),
                )
            }
            if (p.votingNow) {
                val t = p.tally
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("proposal-votes")) {
                    for ((id, label) in p.options) {
                        val on = mine == id
                        val shape = RoundedCornerShape(12.dp)
                        Column(
                            Modifier.weight(1f).clip(shape)
                                .then(if (on) Modifier.background(c.text) else Modifier.border(1.dp, c.line, shape))
                                .semantics { selected = on }
                                .clickable(enabled = !pending && !busy.busy && !on, role = Role.Button) {
                                    busy.run { Actions.vote(p, id)?.let { session.send(it) } }
                                }
                                .padding(vertical = 10.dp)
                                .testTag("vote-$id"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.bg else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            Text((t[id] ?: 0).toString(), fontSize = 13.sp, color = if (on) c.bg else c.muted)
                        }
                    }
                }
                val turnout = world.voteTurnout(p)
                val voted = p.options.firstOrNull { it.first == mine }?.second
                Text(
                    (if (voted != null) "You voted $voted" else "You haven’t voted yet") + " · ${turnout.voted} of ${turnout.of} members have",
                    fontSize = 14.sp, color = c.muted, modifier = Modifier.testTag("proposal-turnout"),
                )
            } else p.poll?.let { poll ->
                Text("Vote on version ${poll.version}: ${tallyLine(p)}", fontSize = 14.sp, color = c.muted)
            }
            if (full && (world.canDecide(p) || p.author == world.me)) {
                ActionGroup {
                    if (world.canDecide(p)) ActionRow("Change the status", onClick = { status = true }, icon = BelfryIcons.Settings, value = p.status.label, modifier = Modifier.testTag("proposal-status"))
                    if (p.author == world.me) ActionRow("New version", onClick = { revising = true }, icon = BelfryIcons.Edit, modifier = Modifier.testTag("proposal-revise"))
                }
            }
        }
    }
    // the handle: a tap moves a level (half → full → up → half), a drag up / down too
    Box(
        Modifier.fillMaxWidth().height(20.dp)
            .pointerInput(Unit) {
                var moved = 0f
                detectVerticalDragGestures(onDragStart = { moved = 0f }, onDragEnd = {
                    if (moved < -16f) onLevel(level.up()) else if (moved > 16f) onLevel(level.down())
                }) { _, dy -> moved += dy }
            }
            .clickable(role = Role.Button, onClickLabel = "Show more or less of the proposal") { onLevel(level.next()) }
            .testTag("proposal-handle"),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) }

    if (revising) ReviseSheet(p) { revising = false }
    if (status) StatusSheet(p) { status = false }
}

/** Chat · Versions · Votes under the header (only those with something in them). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProposalTabsRow(tabs: List<ProposalTab>, tab: ProposalTab, onTab: (ProposalTab) -> Unit) {
    val c = Belfry.colors
    if (tabs.size < 2) return
    PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), containerColor = c.bg, contentColor = c.text) {
        for (t in tabs) {
            Tab(selected = t == tab, onClick = { onTab(t) }, text = { Text(t.label, fontSize = 15.sp) }, selectedContentColor = c.text, unselectedContentColor = c.muted,
                modifier = Modifier.testTag("proposal-tab-${t.name.lowercase()}"))
        }
    }
}

/** The versions it had, newest first; the current one opens the header at full. */
@Composable
fun ProposalVersionsPane(p: Proposal, onReadCurrent: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val now = session.now()
    var open by remember(p.coord) { mutableStateOf<Int?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("proposal-versions"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(world.proposalVersions(p).size) { i ->
            val v: ProposalVersion = world.proposalVersions(p)[i]
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Version ${v.version}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f))
                    if (v.version == p.version) Badge("Current")
                }
                Text("${world.displayName(v.by)}" + (if (v.at > 0) " · " + ChatFormat.day(world, v.at, now) else ""), fontSize = 13.sp, color = c.muted)
                if (v.note.isNotEmpty()) { Gap(4); Text(v.note, fontSize = 15.sp, color = c.text) }
                when {
                    v.version == p.version -> { Gap(6); BusyButton("Read it", onClick = onReadCurrent, kind = ButtonKind.Ghost, small = true) }
                    v.text != null && open == v.version -> { Gap(6); MarkdownText(v.text ?: "", rich = false) }
                    v.text != null -> { Gap(6); BusyButton("Show its text", onClick = { open = v.version }, kind = ButtonKind.Ghost, small = true) }
                }
            }
        }
    }
}

/** Per option, who answered it (the latest answer of each person counts). */
@Composable
fun ProposalVotesPane(p: Proposal) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val by = world.votesByOption(p)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("proposal-votes-pane"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Votes on version ${p.poll?.version ?: p.version}", fontSize = 14.sp, color = c.muted)
        for ((id, label) in p.options) {
            val who = by[id].orEmpty().sortedBy { world.displayName(it).lowercase() }
            Column {
                Text("$label · ${who.size}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                if (who.isEmpty()) Muted("Nobody yet.")
                for (pk in who) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(world.face(pk), size = 24.dp)
                        GapW(8)
                        Text(world.displayName(pk), fontSize = 15.sp, color = c.text)
                    }
                }
            }
        }
    }
}
