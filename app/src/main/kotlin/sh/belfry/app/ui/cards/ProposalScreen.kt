package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Proposal
import sh.belfry.core.model.ProposalStatus

/** A proposal: its text and version, its status, the vote while votes are open. */
@Composable
fun ProposalScreen(groupId: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val p = world.proposalFor(groupId)
    if (p == null) {
        CardScreen("Proposal") { EmptyState("The proposal's text is on its way", text = "It is still syncing.", icon = BelfryIcons.Proposal) }
        return
    }
    val t = p.tally
    val mine = world.myVote(p)
    val pollPending = p.poll?.let { world.isPending(it.ev.id) } ?: false
    var revising by remember(groupId) { mutableStateOf(false) }
    var status by remember(groupId) { mutableStateOf(false) }
    val voteBusy = rememberBusy()

    CardScreen("Proposal", subtitle = "Version ${p.version} · by ${world.displayName(p.author)}") {
        PageColumn {
            Column {
                Text(p.title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Gap(6)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(p.status.label, p.status.tone())
                    GapW(8)
                    Muted("Version ${p.version}", maxLines = 1)
                }
                if (p.summary.isNotEmpty()) {
                    Gap(8)
                    Text(p.summary, fontSize = 15.sp, lineHeight = 21.sp, color = c.muted)
                }
            }

            if (p.text.isNotEmpty()) MarkdownText(p.text, rich = false) else Muted("No text yet.")

            val poll = p.poll
            if (p.votingNow) {
                SectionTitle("Vote on version ${p.version}")
                Tally(t)
                ActionGroup {
                    for ((id, label) in Proposal.VOTE_OPTIONS) {
                        BusyRow(
                            label,
                            onClick = { Actions.vote(p, id)?.let { session.send(it) } },
                            modifier = Modifier.testTag("vote-$id"),
                            icon = if (mine == id) BelfryIcons.Check else null,
                            sub = if (mine == id) "Your vote" else null,
                            enabled = !pollPending && mine != id,
                            busy = voteBusy,
                        )
                    }
                }
                if (mine != null) Muted("You can change your vote while votes are open.")
            } else if (poll != null && (p.status == ProposalStatus.PASSED || p.status == ProposalStatus.REJECTED || p.status == ProposalStatus.CLOSED)) {
                SectionTitle("Vote on version ${poll.version}")
                Tally(t)
            }

            ActionGroup {
                ActionRow("Discussion", onClick = { nav.openChannel(groupId) }, modifier = Modifier.testTag("proposal-chat"), icon = BelfryIcons.Thread, sub = "Feedback goes in the proposal's chat", chevron = true)
                if (p.author == world.me) {
                    ActionRow("New version", onClick = { revising = true }, modifier = Modifier.testTag("proposal-revise"), icon = BelfryIcons.Edit)
                }
                if (world.canDecide(p)) {
                    ActionRow("Change status", onClick = { status = true }, modifier = Modifier.testTag("proposal-status"), icon = BelfryIcons.Settings, value = p.status.label)
                }
            }
        }
    }

    if (revising) ReviseSheet(p) { revising = false }
    if (status) StatusSheet(p) { status = false }
}

/** Yes / No / Abstain with their counts as bars. */
@Composable
internal fun Tally(t: Map<String, Int>) {
    val c = Belfry.colors
    val total = t.values.sum().coerceAtLeast(1)
    Column(Modifier.testTag("tally")) {
        for ((id, label) in Proposal.VOTE_OPTIONS) {
            val n = t[id] ?: 0
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(66.dp), fontSize = 14.sp, color = c.text)
                Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(c.surface2)) {
                    if (n > 0) Box(Modifier.fillMaxWidth(n.toFloat() / total).height(8.dp).clip(CircleShape).background(if (id == "yes") c.text else c.muted))
                }
                Text(n.toString(), Modifier.width(32.dp).padding(start = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
        }
    }
}

/** The statuses as a list; opening the votes starts a vote on the current version. */
@Composable
internal fun StatusSheet(p: Proposal, onDismiss: () -> Unit) {
    val session = LocalSession.current
    val busy = rememberBusy()
    BelfrySheet(onDismiss = onDismiss, title = "Status") {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            ActionGroup {
                for (s in ProposalStatus.entries) {
                    BusyRow(
                        s.label,
                        onClick = {
                            session.send(Actions.setProposalStatus(p, s))
                            onDismiss()
                        },
                        modifier = Modifier.testTag("status-" + s.key),
                        icon = if (s == p.status) BelfryIcons.Check else null,
                        enabled = s != p.status,
                        busy = busy,
                    )
                }
            }
            Gap(10)
            Muted("Opening the votes starts a vote on version ${p.version}.")
        }
    }
}

/** A new version of the text, with a line saying what changed. */
@Composable
internal fun ReviseSheet(p: Proposal, onDismiss: () -> Unit) {
    val session = LocalSession.current
    var title by remember(p.coord) { mutableStateOf(p.title) }
    var summary by remember(p.coord) { mutableStateOf(p.summary) }
    var text by remember(p.coord) { mutableStateOf(p.text) }
    var note by remember(p.coord) { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    BelfrySheet(onDismiss = onDismiss, title = "Version ${p.version + 1}") {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            ProposalFields(title, { title = it }, summary, { summary = it }, text, { text = it }, titleError = if (tried && title.isBlank()) "Give the proposal a title" else null)
            Gap(12)
            BelfryTextField(note, { note = it }, Modifier.fillMaxWidth(), label = "What changed", placeholder = "e.g. Budget lowered after feedback")
            Gap(14)
            BusyButton(
                "Publish version ${p.version + 1}",
                onClick = {
                    tried = true
                    if (title.isNotBlank()) {
                        session.reviseProposal(p, title.trim(), summary.trim(), text.trim(), note.trim())
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("revise-publish"),
            )
            if (p.status == ProposalStatus.VOTING) {
                Gap(10)
                Muted("The vote restarts on the new version.")
            }
        }
    }
}

@Composable
internal fun ProposalFields(
    title: String,
    onTitle: (String) -> Unit,
    summary: String,
    onSummary: (String) -> Unit,
    text: String,
    onText: (String) -> Unit,
    titleError: String?,
) {
    Column {
        BelfryTextField(title, onTitle, Modifier.fillMaxWidth().testTag("proposal-title"), label = "Title", placeholder = "What do you propose?", error = titleError)
        Gap(12)
        BelfryTextField(summary, onSummary, Modifier.fillMaxWidth(), label = "In one line")
        Gap(12)
        BelfryTextField(text, onText, Modifier.fillMaxWidth(), label = "The proposal", placeholder = "Markdown: **bold**, - lists, [links](https://…)", singleLine = false, minLines = 5, maxLines = 12)
    }
}
