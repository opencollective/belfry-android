package sh.belfry.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.CardStatus
import sh.belfry.core.model.InboxThread
import sh.belfry.core.model.plainRefs

/**
 * One row per e-mail thread of a shared inbox, where its latest activity is (SPEC 19.33): sender,
 * subject, a line of the latest e-mail, the state, who has it, the counts. A tap opens the thread.
 */
@Composable
fun MailThreadRow(t: InboxThread, onOpen: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val latest = t.latest
    val who = t.name.ifEmpty { t.address }
    val line = latest?.let { (if (it.incoming) "" else "You answered: ") + world.plainRefs(it.m.text).replace('\n', ' ') } ?: ""
    val state = when (t.state.status) {
        CardStatus.OPEN -> "Open"
        CardStatus.WAITING -> "Answered"
        CardStatus.CLOSED -> "Closed"
    }
    val has = t.state.assignee?.let { if (it == world.me) "you have it" else world.displayName(it) + " has it" }
    val counts = listOfNotNull(
        "${t.mails.size} ${if (t.mails.size == 1) "e-mail" else "e-mails"}",
        if (t.replies > 0) "${t.replies} ${if (t.replies == 1) "reply" else "replies"}" else null,
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .border(1.dp, c.line, RoundedCornerShape(10.dp)).background(c.surface)
            .clickable(role = Role.Button, onClick = onOpen).padding(12.dp).testTag("mail-row"),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(BelfryIcons.Mail, contentDescription = null, tint = c.orange, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(who, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text(ChatFormat.time(world, t.last), fontSize = 12.sp, color = c.faint)
            }
            Text(t.subject.ifEmpty { "(no subject)" }, fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, fontSize = 14.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(state, has, counts).joinToString(" · "), fontSize = 13.sp, color = if (t.state.status == CardStatus.OPEN) c.text else c.muted, maxLines = 1)
        }
    }
}
