@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import sh.belfry.app.ui.chat.NameTag
import sh.belfry.app.ui.chat.face
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

/** An e-mail thread's two modes' colours (round 6): blue for an e-mail, yellow for a comment. */
internal val MAIL_BLUE = Color(0xFF2563EB)
internal val NOTE_YELLOW = Color(0xFFFACC15)

/**
 * A shared inbox's e-mail thread (SPEC 19.33; round 6, boards channels/mail-reply and
 * channels/mail-comment): every entry is a message row — the outsider's e-mails with "guest, by
 * e-mail", what went out with "sent as <alias>@", the team's replies with "team only" — under the
 * sender's line and the state (Take / Close for the channel's admins). The composer has two modes:
 * "Reply to <name>" (an e-mail, blue, 8 s to undo, from the channel's plain alias) and "Comment"
 * (a NIP-22 reply only members see, yellow). Who may not answer by e-mail only comments.
 */
@Composable
fun MailThreadScreen(groupId: String, t: InboxThread, onClose: (() -> Unit)? = null, startWithComment: Boolean = false) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val chat: ChatState = rememberChatState("mail:$groupId:${t.id}")
    val composer = rememberComposerState("mail:" + t.id)
    val g = world.group(groupId)
    val admin = world.canAdmin(g)
    val canMail = admin && world.isInbox(g) && t.lastIn != null && !world.isLocked(groupId)
    var byMail by remember(t.id, canMail) { mutableStateOf(canMail && !startWithComment) }
    var full by remember { mutableStateOf<MailItem?>(null) }
    val replies = world.mailThreadReplies(groupId, t)
    val list = rememberLazyListState()
    val flow: List<Pair<Long, Any>> = (t.mails.map { it.m.at to (it as Any) } + replies.map { it.at to (it as Any) }).sortedBy { it.first }
    LaunchedEffect(flow.size) { if (flow.isNotEmpty()) list.scrollToItem(flow.size + 1) }
    // the Undo countdown lives under the bar; leaving the thread sends at once
    DisposableEffect(Unit) { onDispose { ReplyQueue.pending?.let { ReplyQueue.fire(session, it) } } }
    val who = t.lastIn?.name?.ifEmpty { null } ?: t.name.ifEmpty { t.address }
    val first = who.split(' ').firstOrNull { it.isNotEmpty() && '@' !in it } ?: t.address
    val alias = world.mailLocal(groupId)
    val label = sh.belfry.app.ui.info.channelLabel(g, groupId)
    val now = session.now()

    Column(Modifier.fillMaxSize().background(c.bg).imePadding().testTag("mail-thread")) {
        TopBar(
            t.subject.ifEmpty { "(no subject)" }, subtitle = "in $label",
            navigation = if (onClose == null) ({ BackButton { nav.back() } }) else null,
            actions = { if (onClose != null) IconAction(BelfryIcons.Close, "Close the thread", onClose) },
        )
        UndoSendHost(groupId)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
            item(key = "<state>") { StateLine(groupId, t, admin) }
            for ((i, x) in flow.withIndex()) {
                val prevAt = flow.getOrNull(i - 1)?.first
                val day = ChatFormat.day(world, x.first, now)
                if (prevAt == null || ChatFormat.day(world, prevAt, now) != day) item(key = "<day>$i") { DayLine(day) }
                when (val v = x.second) {
                    is MailItem -> item(key = v.m.id) { MailEntry(world, v, alias, onFull = { full = v }) }
                    is Message -> item(key = v.id) {
                        MessageRow(groupId, v, true, chat, tag = BelfryIcons.Lock to "team only")
                    }
                }
            }
        }
        if (world.isLocked(groupId)) return@Column
        Hairline()
        if (canMail) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("Reply to $first", BelfryIcons.Mail, byMail, MAIL_BLUE, Color.White, Modifier.weight(1f).testTag("mode-reply")) { byMail = true }
                ModeButton("Comment", BelfryIcons.Lock, !byMail, NOTE_YELLOW, Color(0xFF111111), Modifier.weight(1f).testTag("mode-comment")) { byMail = false }
            }
        }
        Text(
            buildAnnotatedString {
                if (byMail) {
                    append("An e-mail from ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(world.mailAddress(groupId)) }
                    append(" to $first")
                } else {
                    append("Only members of $label see this. ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$first does not.") }
                }
            },
            fontSize = 14.sp, color = c.muted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("send-to"),
        )
        Composer(
            composer,
            placeholder = if (byMail) "Write your e-mail to $first" else "Write a comment for the team",
            onSend = { text, files, picked, _ ->
                val last = t.lastIn
                if (byMail && last != null) {
                    // after an e-mail the thread stays in reply mode
                    ReplyQueue.queue(session, PendingReply(groupId, last.address, first, "Re: " + MailThreads.baseSubject(last.subject).ifEmpty { "(no subject)" },
                        text.trim(), last.messageId, last.m.id, t.refs))
                } else {
                    val root = t.root ?: return@Composer
                    session.replyInThread(groupId, root.ev, text, files, picked)
                }
            },
            modifier = Modifier.navigationBarsPadding(),
            draftKey = ComposerDraft.thread(t.id),
            photos = !byMail,
            groupId = groupId,
            tint = if (byMail) MAIL_BLUE else NOTE_YELLOW,
            onTint = if (byMail) Color.White else Color(0xFF111111),
        )
    }
    full?.let { m -> m.original?.let { FullMailSheet(m.subject, it) { full = null } } }
}

/** One of the composer's two modes: the chosen one filled (its colour), the other outlined. */
@Composable
private fun ModeButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, color: Color, onColor: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.heightIn(min = 44.dp).clip(shape)
            .then(if (on) Modifier.background(color) else Modifier.border(1.dp, c.line, shape))
            .semantics { selected = on }
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) onColor else c.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) onColor else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DayLine(day: String) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
        Text(day, fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
    }
}

/** The sender, the state, and for the channel's admins: take it / let go, close / reopen. */
@Composable
private fun StateLine(groupId: String, t: InboxThread, admin: Boolean) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(BelfryIcons.Mail, contentDescription = null, tint = c.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            val name = t.name.ifEmpty { null }
            Text(if (name != null) "$name <${t.address}>" else t.address, fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatePill(t.state.status, Modifier.testTag("mail-state"))
            val mine = t.state.assignee == world.me
            val has = t.state.assignee?.let { if (it == world.me) "You have it" else world.displayName(it) + " has it" }
            val locked = !admin || world.isLocked(groupId)
            when {
                locked -> Text(has ?: "Nobody yet", fontSize = 14.sp, color = c.muted)
                else -> BusyButton(
                    when { mine -> "You have it · let go"; has != null -> "$has · take it"; else -> "Nobody yet · take it" },
                    onClick = { session.send(Actions.assignCard(groupId, t.id, if (mine) "" else world.me)) },
                    kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("mail-take"),
                )
            }
            if (!locked) {
                val closed = t.state.status == CardStatus.CLOSED
                BusyButton(if (closed) "Reopen" else "Close", onClick = { session.send(Actions.setCardStatus(groupId, t.id, !closed)) }, kind = ButtonKind.Ghost, small = true)
            }
        }
    }
}

/** Open / Answered / Closed as a small pill: words first, colour second (e-ink). */
@Composable
internal fun StatePill(status: CardStatus, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val (text, open) = when (status) { CardStatus.OPEN -> "Open" to true; CardStatus.WAITING -> "Answered" to false; CardStatus.CLOSED -> "Closed" to false }
    val shape = RoundedCornerShape(50)
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (open) c.orange else c.muted,
        modifier = modifier.clip(shape).border(1.dp, if (open) c.orange else c.line, shape).background(if (open) c.orange.copy(alpha = 0.12f) else c.surface).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** An outsider's initials on grey: guests by e-mail have no profile. */
@Composable
internal fun MailFace(name: String, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val c = Belfry.colors
    val letters = name.split(' ', '.', '@').filter { it.isNotEmpty() }.let { w ->
        val one = w.firstOrNull() ?: "?"
        one.take(1).uppercase() + (one.drop(1).firstOrNull { it.isLetter() }?.toString() ?: "")
    }
    Box(Modifier.size(size).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
        Text(letters, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
    }
}

/** One e-mail as a message row: received = the sender's initials and "guest, by e-mail"; sent = the member, "sent as <alias>@". */
@Composable
private fun MailEntry(world: sh.belfry.core.model.World, m: MailItem, alias: String, onFull: () -> Unit) {
    val c = Belfry.colors
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val text = m.m.text.removePrefix("**${m.subject}**").trimStart()
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true }).padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag(if (m.incoming) "mail-in" else "mail-out"),
        ) {
            if (m.incoming || m.by == null) MailFace(m.name.ifEmpty { m.address }) else sh.belfry.app.ui.kit.Avatar(world.face(m.by!!), size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val name = if (m.incoming) m.name.ifEmpty { m.address } else m.by?.let { if (it == world.me) "You" else world.displayName(it) } ?: alias
                    Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    if (m.incoming) NameTag(BelfryIcons.Mail, "guest, by e-mail") else NameTag(BelfryIcons.Send, "sent as $alias@", MAIL_BLUE)
                    Spacer(Modifier.width(8.dp))
                    Text(ChatFormat.time(world, m.m.at), fontSize = 12.sp, color = c.faint, maxLines = 1)
                }
                if (m.incoming && !m.verified) Text("Unverified sender", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.danger)
                MarkdownText(text.ifEmpty { "(no text)" }, fontSize = 16.sp)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.bg) {
            if (m.original != null) DropdownMenuItem(text = { Text("Full e-mail") }, onClick = { menu = false; onFull() }, leadingIcon = { Icon(BelfryIcons.Mail, null) }, modifier = Modifier.testTag("mail-full"))
            DropdownMenuItem(text = { Text("Copy text") }, onClick = { menu = false; clipboard.setText(AnnotatedString(text)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
        }
    }
}
