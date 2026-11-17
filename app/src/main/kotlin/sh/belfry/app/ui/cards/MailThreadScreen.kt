package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.chat.Composer
import sh.belfry.app.ui.chat.MessageRow
import sh.belfry.app.ui.chat.rememberChatState
import sh.belfry.app.ui.chat.rememberComposerState
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.model.Actions
import sh.belfry.core.model.CardStatus
import sh.belfry.core.model.ComposerDraft
import sh.belfry.core.model.InboxThread
import sh.belfry.core.model.MailItem
import sh.belfry.core.model.Message
import sh.belfry.core.model.isLocked

/**
 * A shared inbox's e-mail thread (SPEC 19.33): its e-mails in frames (received light, sent solid,
 * "unverified sender", Full e-mail), the team's replies between them, the state with Take / Close
 * for the channel's admins, and ONE composer with a line above it saying who receives what is
 * written: the thread (a NIP-22 reply under the root) or, for the channel's admins while the inbox
 * is on, the sender by e-mail (8 s to undo, from the channel's plain alias).
 */
@Composable
fun MailThreadScreen(groupId: String, t: InboxThread, onClose: (() -> Unit)? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val chat: ChatState = rememberChatState("mail:$groupId:${t.id}")
    val composer = rememberComposerState("mail:" + t.id)
    val g = world.group(groupId)
    val admin = world.canAdmin(g)
    val canMail = admin && world.isInbox(g) && t.lastIn != null && !world.isLocked(groupId)
    var byMail by remember(t.id) { mutableStateOf(false) }
    var full by remember { mutableStateOf<MailItem?>(null) }
    val replies = world.mailThreadReplies(groupId, t)
    val list = rememberLazyListState()
    val flow: List<Pair<Long, Any>> = (t.mails.map { it.m.at to (it as Any) } + replies.map { it.at to (it as Any) }).sortedBy { it.first }
    LaunchedEffect(flow.size) { if (flow.isNotEmpty()) list.scrollToItem(flow.size + 1) }
    // the Undo countdown lives under the bar; leaving the thread sends at once
    DisposableEffect(Unit) { onDispose { ReplyQueue.pending?.let { ReplyQueue.fire(session, it) } } }
    val first = t.name.split(' ').firstOrNull { it.isNotEmpty() } ?: t.address

    Column(Modifier.fillMaxSize().background(c.bg).imePadding().testTag("mail-thread")) {
        TopBar(
            t.subject.ifEmpty { "(no subject)" }, subtitle = t.name.ifEmpty { t.address },
            navigation = if (onClose == null) ({ BackButton { nav.back() } }) else null,
            actions = { if (onClose != null) IconAction(BelfryIcons.Close, "Close the thread", onClose) },
        )
        UndoSendHost(groupId)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
            item(key = "<state>") { StateLine(groupId, t, admin) }
            for ((i, x) in flow.withIndex()) {
                when (val v = x.second) {
                    is MailItem -> item(key = v.m.id) { MailFrame(world, v, onFull = { full = v }) }
                    is Message -> item(key = v.id) {
                        val prev = flow.getOrNull(i - 1)?.second as? Message
                        MessageRow(groupId, v, prev == null || prev.pubkey != v.pubkey || v.at - prev.at > 300, chat)
                    }
                }
            }
            item(key = "<team>") {
                Text("Team only: ${t.name.ifEmpty { t.address }} sees only what is sent by e-mail.", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
        if (world.isLocked(groupId)) return@Column
        Hairline()
        // who receives what is written
        Column(Modifier.fillMaxWidth().background(if (byMail) c.surface else c.bg).padding(horizontal = 16.dp, vertical = 6.dp).testTag("send-to")) {
            if (byMail) {
                Text("This will be sent to ${t.name.ifEmpty { t.address }} by e-mail", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text("From ${world.mailAddress(groupId)} · Re: ${t.lastIn?.let { MailThreads.baseSubject(it.subject) } ?: t.subject}", fontSize = 13.sp, color = c.muted)
                Text("Only send in this thread instead", fontSize = 14.sp, color = c.text, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(role = Role.Button) { byMail = false }.padding(vertical = 4.dp).testTag("send-thread"))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Message will be sent in this thread", fontSize = 13.sp, color = c.muted, modifier = Modifier.weight(1f))
                    if (canMail) {
                        Text("Reply by e-mail instead", fontSize = 14.sp, color = c.text, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable(role = Role.Button) { byMail = true }.padding(vertical = 4.dp).testTag("send-mail"))
                    }
                }
            }
        }
        Composer(
            composer,
            placeholder = if (byMail) "Write to $first" else "Reply in the thread",
            onSend = { text, files, picked, _ ->
                val last = t.lastIn
                if (byMail && last != null) {
                    ReplyQueue.queue(session, PendingReply(groupId, last.address, first, "Re: " + MailThreads.baseSubject(last.subject).ifEmpty { "(no subject)" },
                        text.trim(), last.messageId, last.m.id, t.refs))
                    byMail = false
                } else {
                    val root = t.root ?: return@Composer
                    session.replyInThread(groupId, root.ev, text, files, picked)
                }
            },
            modifier = Modifier.navigationBarsPadding(),
            draftKey = ComposerDraft.thread(t.id),
            photos = !byMail,
            groupId = groupId,
        )
    }
    full?.let { m -> m.original?.let { FullMailSheet(m.subject, it) { full = null } } }
}

@Composable
private fun StateLine(groupId: String, t: InboxThread, admin: Boolean) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val state = when (t.state.status) { CardStatus.OPEN -> "Open"; CardStatus.WAITING -> "Answered"; CardStatus.CLOSED -> "Closed" }
    val who = t.state.assignee?.let { if (it == world.me) "You have it" else world.displayName(it) + " has it" } ?: "Nobody has it"
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$state · $who", fontSize = 14.sp, color = c.text, modifier = Modifier.weight(1f).testTag("mail-state"))
        if (admin && !world.isLocked(groupId)) {
            val mine = t.state.assignee == world.me
            BusyButton(if (mine) "Let go" else "Take", onClick = { session.send(Actions.assignCard(groupId, t.id, if (mine) "" else world.me)) }, kind = ButtonKind.Ghost, small = true)
            Spacer(Modifier.width(8.dp))
            val closed = t.state.status == CardStatus.CLOSED
            BusyButton(if (closed) "Reopen" else "Close", onClick = { session.send(Actions.setCardStatus(groupId, t.id, !closed)) }, kind = ButtonKind.Ghost, small = true)
        }
    }
}

/** One e-mail in its frame: received light, sent solid. */
@Composable
private fun MailFrame(world: sh.belfry.core.model.World, m: MailItem, onFull: () -> Unit) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(14.dp)
    val bg = if (m.incoming) c.surface else c.accent
    val fg = if (m.incoming) c.text else c.onAccent
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).border(1.dp, c.line, shape).background(bg).padding(12.dp).testTag(if (m.incoming) "mail-frame-in" else "mail-frame-out"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MailOutline, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                val who = if (m.incoming) m.name.ifEmpty { m.address } else "To " + m.address + (m.by?.let { " · by " + world.displayName(it) } ?: "")
                Text(who, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg, modifier = Modifier.weight(1f))
                Text(ChatFormat.time(world, m.m.at), fontSize = 12.sp, color = fg)
            }
            if (m.incoming && !m.verified) Text("Unverified sender", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.danger, modifier = Modifier.padding(top = 2.dp))
            if (m.subject.isNotEmpty()) Text(m.subject, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = fg, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.size(4.dp))
            // the bridge's text may start with the subject in bold: it is shown once, above
            val text = m.m.text.removePrefix("**${m.subject}**").trimStart()
            MarkdownText(text.ifEmpty { "(no text)" }, fontSize = 16.sp, color = fg)
            if (m.original != null) {
                Text("Full e-mail", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg,
                    modifier = Modifier.padding(top = 6.dp).clickable(role = Role.Button, onClick = onFull).padding(vertical = 4.dp).testTag("mail-full"))
            }
        }
    }
}
