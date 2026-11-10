package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Message
import sh.belfry.core.model.Special

/**
 * What a special post shows in the feed, below the author line (the feed already wrote
 * "Created a pod / an event / a proposal" above it): an event, pod or proposal card, a member's
 * e-mail, a shared-inbox card, an answer sent as the channel. It renders the message's whole
 * content (the feed draws no text of its own for these), but not its reactions or thread chip.
 *
 * Seam: these are plain readable fallbacks; the cards package replaces the bodies.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SpecialCard(groupId: String, message: Message, modifier: Modifier = Modifier) {
    val world = LocalWorld.current
    val nav = LocalNav.current
    when (val s = message.special) {
        is Special.None -> Unit
        is Special.EventCard -> {
            val e = world.calEvent(s.coord)
            PlainCard(BelfryIcons.Calendar, e?.title ?: "An event", e?.location, modifier) { nav.go(Routes.event(s.coord)) }
        }
        is Special.PodCard -> {
            val g = world.group(s.podId)
            PlainCard(BelfryIcons.Pod, g?.name ?: "A pod", g?.about, modifier) { nav.go(Routes.pod(s.podId)) }
        }
        is Special.ProposalCard -> {
            val p = world.proposalFor(s.groupId)
            PlainCard(BelfryIcons.Proposal, p?.title ?: "A proposal", p?.let { it.status.label }, modifier) { nav.go(Routes.proposal(s.groupId)) }
        }
        is Special.MemberMail -> Column(modifier) {
            Label(BelfryIcons.Mail, "via e-mail")
            if (s.subject.isNotEmpty()) Text(s.subject, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text)
            if (message.body.isNotEmpty()) MarkdownText(message.body)
        }
        is Special.InboxCard -> MailFrame(modifier) {
            Label(BelfryIcons.Inbox, if (s.name.isNotEmpty()) "E-mail from ${s.name}" else "E-mail from ${s.from}")
            if (s.subject.isNotEmpty()) Text(s.subject, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text)
            if (message.body.isNotEmpty()) MarkdownText(message.body)
        }
        is Special.SentAsChannel -> MailFrame(modifier) {
            Label(BelfryIcons.Send, "Answered by e-mail to ${s.to} · ${world.displayName(s.by)}")
            if (s.subject.isNotEmpty()) Text(s.subject, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text)
            if (message.body.isNotEmpty()) MarkdownText(message.body)
        }
    }
}

@Composable
private fun PlainCard(icon: ImageVector, title: String, sub: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .padding(top = 4.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, fontSize = 13.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MailFrame(modifier: Modifier, content: @Composable () -> Unit) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.padding(top = 4.dp).fillMaxWidth().clip(shape).border(1.dp, c.orange.copy(alpha = 0.5f), shape).padding(10.dp),
    ) { content() }
}

@Composable
private fun Label(icon: ImageVector, text: String) {
    val c = Belfry.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Icon(icon, contentDescription = null, tint = c.orange, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.orange, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
