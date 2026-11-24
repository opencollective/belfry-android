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
 * One row per e-mail thread of a shared inbox, where its latest activity is (SPEC 19.33; round 6,
 * board channels/mail-row): shaped like a message — the sender's initials, name, "guest, by
 * e-mail", time; the subject; a line of the latest e-mail; the state, who has it, the counts.
 */
@Composable
fun MailThreadRow(t: InboxThread, privateGroup: Boolean = false, onOpen: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val latest = t.latest
    val who = t.name.ifEmpty { t.address }
    val line = latest?.let { (if (it.incoming) "" else "You answered: ") + world.plainRefs(it.m.text.removePrefix("**${it.subject}**").trimStart()).replace('\n', ' ') } ?: ""
    val has = t.state.assignee?.let { if (it == world.me) "you have it" else world.displayName(it) + " has it" }
    val counts = listOfNotNull(
        "${t.mails.size} ${if (t.mails.size == 1) "e-mail" else "e-mails"}",
        if (t.replies > 0) "${t.replies} ${if (t.replies == 1) "comment" else "comments"}" else null,
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp, vertical = 6.dp).testTag("mail-row"),
        verticalAlignment = Alignment.Top,
    ) {
        sh.belfry.app.ui.cards.MailFace(who)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(who, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                // SPEC 19.88 (encrypted/group-email): in a private group, just "By e-mail" after the time
                if (!privateGroup) { NameTag(BelfryIcons.Mail, "guest, by e-mail"); Spacer(Modifier.width(8.dp)) }
                Text(ChatFormat.time(world, t.last) + if (privateGroup) " · " + sh.belfry.core.model.GroupEncText.BY_EMAIL else "", fontSize = 12.sp, color = c.faint, maxLines = 1)
            }
            Text(t.subject.ifEmpty { "(no subject)" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, fontSize = 15.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                sh.belfry.app.ui.cards.StatePill(t.state.status)
                Spacer(Modifier.width(8.dp))
                Text(listOfNotNull(has, counts).joinToString(" · "), fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
