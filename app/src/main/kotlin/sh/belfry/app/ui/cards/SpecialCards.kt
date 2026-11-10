package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.CardState
import sh.belfry.core.model.CardStatus
import sh.belfry.core.model.MailOriginal
import sh.belfry.core.model.Message
import sh.belfry.core.model.ProposalStatus
import sh.belfry.core.model.Special

/**
 * What a special post shows in the feed, below the author line (the feed already wrote
 * "Created a pod / an event / a proposal" above it): an event, pod or proposal card, a member's
 * e-mail, a shared-inbox card, an answer sent as the channel. It renders the message's whole
 * content (the feed draws no text of its own for these), but not its reactions or thread chip.
 */
@Composable
fun SpecialCard(groupId: String, message: Message, modifier: Modifier = Modifier) {
    when (val s = message.special) {
        is Special.None -> Unit
        is Special.EventCard -> EventCard(s.coord, message.text, modifier)
        is Special.PodCard -> PodCard(s.podId, message.text, modifier)
        is Special.ProposalCard -> ProposalCard(s.groupId, message.text, modifier)
        is Special.MemberMail -> MemberMailPost(message, s, modifier)
        is Special.InboxCard -> InboxCardView(groupId, message, s, modifier)
        is Special.SentAsChannel -> SentCard(groupId, message, s, modifier)
    }
}

/** When the thing a card points at has not synced yet: the post's own readable text. */
@Composable
private fun Fallback(text: String, modifier: Modifier) {
    if (text.isNotEmpty()) MarkdownText(text, modifier)
}

@Composable
private fun CardTitle(text: String) {
    Text(text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun EventCard(coord: String, fallback: String, modifier: Modifier = Modifier) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val e = world.calEvent(coord) ?: return Fallback(fallback, modifier)
    val mine = world.myRsvp(e)
    CardFrame(modifier.testTag("event-card"), onClick = { nav.go(Routes.event(coord)) }) {
        DateChip(world, e.start)
        GapW(12)
        Column(Modifier.weight(1f)) {
            CardTitle(e.title)
            Muted(
                CardFormat.dayAndWhen(world, e, session.now()) +
                    (if (!e.location.isNullOrEmpty()) " · " + e.location else "") +
                    (if (e.links.isNotEmpty()) " · online" else ""),
                maxLines = 2,
            )
            Muted((if (mine != null) mine.label + " · " else "") + "${e.going} going", maxLines = 1)
        }
    }
}

@Composable
internal fun PodCard(podId: String, fallback: String, modifier: Modifier = Modifier) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val g = world.group(podId) ?: return Fallback(fallback, modifier)
    val inIt = world.me in g.members
    val applied = g.applications.any { it.pubkey == world.me }
    CardFrame(modifier.testTag("pod-card"), onClick = { nav.go(Routes.pod(podId)) }) {
        IconChip(BelfryIcons.Pod)
        GapW(12)
        Column(Modifier.weight(1f)) {
            CardTitle(g.name)
            if (g.about.isNotEmpty()) Muted(g.about, maxLines = 2)
            Muted((if (g.closed) "Closed pod" else "Pod") + " · " + CardFormat.members(g.members.size) + (if (inIt) " · you’re in" else ""), maxLines = 1)
        }
        if (!inIt) {
            GapW(8)
            when {
                !g.closed -> BusyButton("Join", onClick = { session.send(Actions.join(podId)) }, small = true)
                applied -> Badge("Requested", Tone.Warm)
                else -> Badge("Closed")
            }
        }
    }
}

@Composable
internal fun ProposalCard(groupId: String, fallback: String, modifier: Modifier = Modifier) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    val p = world.proposalFor(groupId) ?: return Fallback(fallback, modifier)
    val t = p.tally
    val mine = world.myVote(p)
    CardFrame(modifier.testTag("proposal-card"), onClick = { nav.go(Routes.proposal(groupId)) }) {
        IconChip(BelfryIcons.Proposal)
        GapW(12)
        Column(Modifier.weight(1f)) {
            CardTitle(p.title)
            if (p.summary.isNotEmpty()) Muted(p.summary, maxLines = 2)
            Gap(4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(p.status.label, p.status.tone())
                GapW(8)
                Muted(
                    "v${p.version}" + (if (p.poll != null && p.status == ProposalStatus.VOTING) " · ${t["yes"] ?: 0} yes · ${t["no"] ?: 0} no" else ""),
                    Modifier.weight(1f),
                    maxLines = 1,
                )
            }
            if (mine != null && p.votingNow) Muted("You voted " + voteLabel(mine).lowercase(), maxLines = 1)
        }
    }
}

internal fun voteLabel(option: String): String = sh.belfry.core.model.Proposal.VOTE_OPTIONS.firstOrNull { it.first == option }?.second ?: option

// ---- e-mail ------------------------------------------------------------------------------

@Composable
private fun MailLabel(icon: ImageVector, text: String) {
    val c = Belfry.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Icon(icon, contentDescription = null, tint = c.orange, modifier = Modifier.size(14.dp))
        GapW(6)
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.orange, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Subject(text: String) {
    if (text.isNotEmpty()) Text(text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text)
}

@Composable
private fun FullMailLink(onClick: () -> Unit) {
    Text(
        "Full e-mail",
        Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        fontSize = 13.sp,
        color = Belfry.colors.text,
        textDecoration = TextDecoration.Underline,
    )
}

/** A member's e-mail to `<channel>@`, posted as their message: "via e-mail", one tap from the whole mail. */
@Composable
private fun MemberMailPost(m: Message, s: Special.MemberMail, modifier: Modifier) {
    var full by remember(m.id) { mutableStateOf<MailOriginal?>(null) }
    Column(modifier) {
        MailLabel(BelfryIcons.Mail, "via e-mail")
        Subject(s.subject)
        if (m.body.isNotEmpty()) MarkdownText(m.body)
        s.original?.let { o -> FullMailLink { full = o } }
    }
    full?.let { FullMailSheet(s.subject, it) { full = null } }
}

@Composable
private fun MailFrame(modifier: Modifier, strong: Boolean, content: @Composable () -> Unit) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .padding(top = 4.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(if (strong) c.orange.copy(alpha = 0.07f) else c.bg)
            .border(1.dp, c.orange.copy(alpha = if (strong) 0.55f else 0.35f), shape)
            .padding(10.dp),
    ) { content() }
}

internal fun CardStatus.tone(): Tone = when (this) {
    CardStatus.OPEN -> Tone.Warm
    CardStatus.WAITING -> Tone.Good
    CardStatus.CLOSED -> Tone.Neutral
}

/** Shared inbox: an e-mail from outside, as a bronze card with its status and who took it. */
@Composable
internal fun InboxCardView(groupId: String, m: Message, card: Special.InboxCard, modifier: Modifier = Modifier) {
    val world = LocalWorld.current
    val c = Belfry.colors
    val state = world.inboxStates(groupId)[m.id] ?: CardState()
    var menu by remember(m.id) { mutableStateOf(false) }
    var writing by remember(m.id) { mutableStateOf(false) }
    var full by remember(m.id) { mutableStateOf(false) }
    val who = if (card.name.isNotEmpty()) "${card.name} <${card.from}>" else card.from
    val first = card.name.split(' ').firstOrNull { it.isNotEmpty() } ?: card.from

    // Undo brings the answer back in its sheet.
    val reopen = ReplyQueue.reopen
    LaunchedEffect(reopen) {
        if (reopen == m.id) {
            ReplyQueue.reopen = null
            writing = true
        }
    }

    MailFrame(modifier.testTag("inbox-card"), strong = true) {
        MailLabel(BelfryIcons.Inbox, "E-mail received · " + ChatFormat.time(world, m.at))
        Text(who, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
        if (!card.verified) Text("unverified sender", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.danger)
        Subject(card.subject)
        MarkdownText(m.body.ifEmpty { "(empty)" })
        if (card.original != null) FullMailLink { full = true }
        Gap(8)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { menu = true }.testTag("inbox-actions").padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(state.status.label, state.status.tone())
            GapW(8)
            Muted(state.assignee?.let { world.displayName(it) } ?: "Nobody yet", Modifier.weight(1f), maxLines = 1)
            Icon(BelfryIcons.More, contentDescription = "Actions for this e-mail", tint = c.muted, modifier = Modifier.size(20.dp))
        }
    }

    if (menu) InboxActionsSheet(groupId, m, card, state, first, onDismiss = { menu = false }, onReply = { menu = false; writing = true }, onFull = { menu = false; full = true })
    if (writing) ReplySheet(groupId, m, card) { writing = false }
    val original = card.original
    if (full && original != null) FullMailSheet(card.subject, original) { full = false }
}

/** What can be done with an e-mail in a shared inbox: answer, take, close. */
@Composable
internal fun InboxActionsSheet(
    groupId: String,
    m: Message,
    card: Special.InboxCard,
    state: CardState,
    first: String,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onFull: () -> Unit,
) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val canSend = world.canAdmin(groupId)
    val mineNow = state.assignee == world.me
    val closed = state.status == CardStatus.CLOSED
    BelfrySheet(onDismiss = onDismiss, title = "E-mail from $first") {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            ActionGroup {
                ActionRow(
                    "Reply by e-mail",
                    onClick = if (canSend) onReply else null,
                    modifier = Modifier.testTag("inbox-reply"),
                    icon = BelfryIcons.Reply,
                    sub = if (canSend) "Goes out as ${world.mailAddress(groupId)}" else REPLY_NOT_ALLOWED,
                    enabled = canSend,
                )
                BusyRow(
                    if (mineNow) "Let it go" else "Take it",
                    onClick = {
                        session.send(Actions.assignCard(groupId, m.id, if (mineNow) "" else world.me))
                        onDismiss()
                    },
                    modifier = Modifier.testTag("inbox-take"),
                    icon = BelfryIcons.User,
                    sub = if (mineNow) "It is yours now" else state.assignee?.let { "Now with " + world.displayName(it) } ?: "Nobody has it yet",
                )
                BusyRow(
                    if (closed) "Reopen" else "Close",
                    onClick = {
                        session.send(Actions.setCardStatus(groupId, m.id, !closed))
                        onDismiss()
                    },
                    modifier = Modifier.testTag("inbox-close"),
                    icon = if (closed) BelfryIcons.Retry else BelfryIcons.Check,
                )
            }
            if (card.original != null) {
                Gap(12)
                ActionGroup { ActionRow("Full e-mail", onClick = onFull, icon = BelfryIcons.Mail) }
            }
        }
    }
}

/** An answer that went out by e-mail as the channel. */
@Composable
private fun SentCard(groupId: String, m: Message, s: Special.SentAsChannel, modifier: Modifier) {
    val world = LocalWorld.current
    val by = if (s.by.isNotEmpty()) world.displayName(s.by) else "a member"
    MailFrame(modifier.testTag("sent-card"), strong = false) {
        MailLabel(BelfryIcons.Send, "E-mail sent by $by as $groupId@ · " + ChatFormat.time(world, m.at))
        if (s.to.isNotEmpty()) Text("To ${s.to}", fontSize = 13.sp, lineHeight = 18.sp, color = Belfry.colors.muted)
        Subject(s.subject)
        if (m.text.isNotEmpty()) MarkdownText(m.text)
    }
}
