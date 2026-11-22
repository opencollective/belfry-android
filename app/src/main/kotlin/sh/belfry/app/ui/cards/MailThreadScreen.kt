@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.chat.Composer
import sh.belfry.app.ui.chat.MessageRow
import sh.belfry.app.ui.chat.rememberChatState
import sh.belfry.app.ui.chat.rememberComposerState
import sh.belfry.app.ui.info.NotifySheet
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.IconRow
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.RowItem
import sh.belfry.app.ui.kit.leadColor
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.CardStatus
import sh.belfry.core.model.ComposerDraft
import sh.belfry.core.model.InboxThread
import sh.belfry.core.model.MailInbox
import sh.belfry.core.model.MailItem
import sh.belfry.core.model.Message
import sh.belfry.core.model.World
import sh.belfry.core.model.isLocked
import sh.belfry.core.nostr.Event

/** What went out by e-mail is blue (the sent card, the Undo bar's words). */
internal val MAIL_BLUE = Color(0xFF2563EB)

/**
 * An e-mail thread's three states (docs/one-shape.md 2; canvas one-shape/email-*, the lead's
 * ruling 6): [TINY] the two-line header only, the e-mails are preview lines in the conversation;
 * [MID] (the default while it is open) the e-mails on top, then the conversation; [FULL] also the
 * From / To / Cc line, to-dos, photos, files, people, notifications, settings and the actions.
 */
enum class MailLevel { TINY, MID, FULL }

/**
 * A shared inbox's e-mail thread in one shape (docs/one-shape.md 4): the header (subject; "✉ From
 * Lou · Open · 👤 Maya · 👥 2 · ✓ 1 · 📁 1", no button), pulled down the e-mails on top with a round
 * reply-all icon each (the only reply: a full compose sheet), below them the team's conversation:
 * comments, the e-mails as previews, what went out, who took it, "Marked answered", internal
 * drafts. The composer is the team's: "Comment for the team".
 */
@Composable
fun MailThreadScreen(groupId: String, t: InboxThread, onClose: (() -> Unit)? = null, startLevel: MailLevel? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val chat: ChatState = rememberChatState("mail:$groupId:${t.id}")
    val composer = rememberComposerState("mail:" + t.id)
    val g = world.group(groupId)
    val admin = world.canAdmin(g)
    val locked = world.isLocked(groupId)
    val canMail = admin && world.isInbox(g) && t.lastIn != null && !locked
    // the lead's ruling 5: mid while it needs someone (open), tiny once answered or closed
    var level by remember(t.id) { mutableStateOf(startLevel ?: if (t.state.status == CardStatus.OPEN) MailLevel.MID else MailLevel.TINY) }
    var full by remember { mutableStateOf<MailItem?>(null) }
    var compose by remember { mutableStateOf<ComposeOpen?>(null) }
    val replies = world.mailThreadReplies(groupId, t)
    val list = rememberLazyListState()
    val flow = remember(t, replies) { threadFlow(t, replies) }
    LaunchedEffect(level, flow.size) { if (level == MailLevel.TINY) list.scrollToItem(flow.size * 2 + 40) else list.scrollToItem(0) }
    // the Undo countdown lives under the header; leaving the thread sends at once
    DisposableEffect(Unit) { onDispose { ReplyQueue.pending?.let { ReplyQueue.fire(session, it) } } }
    // Undo brings the reply back in its sheet
    val reopen = ReplyQueue.reopen
    LaunchedEffect(reopen) { if (reopen != null && reopen in t.ids) { ReplyQueue.reopen = null; compose = ComposeOpen(null) } }
    val now = session.now()
    val counts = remember(t, replies) { threadCounts(world, groupId, t, replies) }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding().testTag("mail-thread")) {
        MailHeader(t, counts, level, onLevel = { level = it }, onBack = if (onClose == null) ({ nav.back() }) else null, onClose = onClose)
        UndoSendHost(groupId)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
            if (level != MailLevel.TINY) {
                topPanel(groupId, t, level, counts, admin && !locked, canMail, onLevel = { level = it }, onReply = { compose = ComposeOpen(null) }, onFull = { full = it })
            }
            for ((i, x) in flow.withIndex()) {
                val prevAt = flow.getOrNull(i - 1)?.at
                val day = ChatFormat.day(world, x.at, now)
                if (prevAt == null || ChatFormat.day(world, prevAt, now) != day) item(key = "<day>$i") { DayLine(day) }
                when (x) {
                    is Step.In -> item(key = x.m.m.id) { MailPreview(x.m, open = level == MailLevel.TINY, canMail = canMail, chat = chat, onReply = { compose = ComposeOpen(null) }, onFull = { full = x.m }) }
                    is Step.Out -> item(key = x.m.m.id) { SentMail(world, x.m) }
                    is Step.Reply -> item(key = x.m.id) { MessageRow(groupId, x.m, true, chat) }
                    is Step.Label -> labelLine(world, x.e)?.let { line -> item(key = x.e.id) { ActivityLine(line.first, line.second, ChatFormat.time(world, x.at)) } }
                    is Step.Draft -> item(key = x.m.id) { DraftCard(world, t, x.m, onOpen = if (canMail) ({ compose = ComposeOpen(x.m) }) else null) }
                }
            }
        }
        if (locked) return@Column
        Hairline()
        Composer(
            composer,
            placeholder = "Comment for the team",
            onSend = { text, files, picked, _ ->
                val root = t.root ?: return@Composer
                session.replyInThread(groupId, root.ev, text, files, picked)
            },
            modifier = Modifier.navigationBarsPadding(),
            draftKey = ComposerDraft.thread(t.id),
            groupId = groupId,
        )
    }
    full?.let { m -> m.original?.let { FullMailSheet(m.subject, it) { full = null } } }
    compose?.let { open -> MailComposeSheet(groupId, t, open.draft) { compose = null } }
}

/** The compose sheet is open: a new reply all, or an internal draft continued. */
internal class ComposeOpen(val draft: Message?)

/** One entry of the conversation, by time. */
private sealed class Step(val at: Long, val id: String) {
    class In(val m: MailItem) : Step(m.m.at, m.m.id)
    class Out(val m: MailItem) : Step(m.m.at, m.m.id)
    class Reply(val m: Message) : Step(m.at, m.id)
    class Label(val e: Event) : Step(e.createdAt, e.id)
    class Draft(val m: Message) : Step(m.at, m.id)
}

private fun threadFlow(t: InboxThread, replies: List<Message>): List<Step> =
    (t.mails.map { if (it.incoming) Step.In(it) else Step.Out(it) } + replies.map { Step.Reply(it) } + t.labels.map { Step.Label(it) } + t.drafts.map { Step.Draft(it) })
        .sortedWith(compareBy<Step> { it.at }.thenBy { if (it is Step.Label) 1 else 0 }.thenBy { it.id }) // "Marked answered" after the reply it follows

/** The header row's counts and the pulled-down rows' numbers. */
private class ThreadCounts(val people: Int, val todos: Int, val photos: Int, val files: Int)

private fun threadCounts(world: World, groupId: String, t: InboxThread, replies: List<Message>): ThreadCounts {
    val ids = t.ids.toSet() + replies.map { it.id }
    val media = replies.flatMap { it.media }
    return ThreadCounts(
        people = t.who.size,
        todos = world.tasks(groupId).count { t2 -> !t2.done && t2.source?.let { it in ids } == true },
        photos = media.count { it.isImage },
        files = media.count { !it.isImage },
    )
}

private fun firstWord(name: String, fallback: String): String = name.split(' ').firstOrNull { it.isNotEmpty() && '@' !in it } ?: fallback

private fun stateWord(s: CardStatus): String = when (s) { CardStatus.OPEN -> "Open"; CardStatus.WAITING -> "Answered"; CardStatus.CLOSED -> "Closed" }

/** Two lines at most: the subject, then the icon row. Tap (or pull) opens and closes; no button in it. */
@Composable
private fun MailHeader(t: InboxThread, n: ThreadCounts, level: MailLevel, onLevel: (MailLevel) -> Unit, onBack: (() -> Unit)?, onClose: (() -> Unit)?) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val sender = t.lastIn?.name?.ifEmpty { null } ?: t.name.ifEmpty { t.address }
    val items = buildList {
        add(RowItem("lead", BelfryIcons.Mail, "From " + firstWord(sender, t.address), "From $sender", lead = true))
        add(RowItem("state", null, stateWord(t.state.status), "State: " + stateWord(t.state.status)))
        t.state.assignee?.let { a ->
            val who = if (a == world.me) "You" else world.displayName(a)
            add(RowItem("owner", BelfryIcons.User, firstWord(who, who), if (a == world.me) "You have it" else "$who has it"))
        }
        if (n.people > 0) add(RowItem("people", BelfryIcons.Users, n.people.toString(), "${n.people} ${if (n.people == 1) "person" else "people"}"))
        if (n.todos > 0) add(RowItem("todos", BelfryIcons.Check, n.todos.toString(), "${n.todos} open ${if (n.todos == 1) "to-do" else "to-dos"}"))
        if (n.files > 0) add(RowItem("files", Icons.Outlined.Folder, n.files.toString(), "${n.files} ${if (n.files == 1) "file" else "files"}"))
    }
    // the shared one-shape header (kit OneHeader): a tap or a drag down opens a level, a drag up folds one
    sh.belfry.app.ui.kit.OneHeader(
        t.subject.ifEmpty { "(no subject)" }, items,
        navigation = if (onBack != null) ({ BackButton(onBack) }) else null,
        actions = { if (onClose != null) IconAction(BelfryIcons.Close, "Close the thread", onClose) },
        onPull = { onLevel(if (level == MailLevel.TINY) MailLevel.FULL else MailLevel.entries[(level.ordinal + 1).coerceAtMost(2)]) },
        onPush = { onLevel(if (level == MailLevel.FULL) MailLevel.TINY else MailLevel.entries[(level.ordinal - 1).coerceAtLeast(0)]) },
        pulled = level != MailLevel.TINY,
        pullLabel = if (level == MailLevel.TINY) "Show the e-mails" else "Fold",
    )
}

/** Pulled down: the e-mails on top; at FULL also the From / To / Cc line, the rows and the actions. */
private fun LazyListScope.topPanel(
    groupId: String, t: InboxThread, level: MailLevel, n: ThreadCounts, admin: Boolean, canMail: Boolean,
    onLevel: (MailLevel) -> Unit, onReply: () -> Unit, onFull: (MailItem) -> Unit,
) {
    val incoming = t.mails.filter { it.incoming }
    val shown = if (level == MailLevel.FULL) t.mails else listOfNotNull(incoming.lastOrNull() ?: t.mails.lastOrNull())
    item(key = "<mails-head>") { SectionLabel("The e-mails · ${t.mails.size}") }
    for (m in shown) item(key = "<card>" + m.m.id) {
        MailCard(m, whole = level == MailLevel.FULL, canMail = canMail && m.incoming, onReply = onReply, onFull = { onFull(m) })
    }
    if (level == MailLevel.MID) {
        item(key = "<more>") {
            MoreLine(if (t.mails.size > 1) "All ${t.mails.size} e-mails, to-dos, people" else "The whole e-mail, to-dos, people") { onLevel(MailLevel.FULL) }
        }
    } else {
        t.lastIn?.let { last -> item(key = "<fields>") { FieldsLine(groupId, last) } }
        item(key = "<rows>") { ThreadRows(groupId, t, n, admin) }
    }
    item(key = "<panel-end>") { Spacer(Modifier.height(8.dp)); Hairline() }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp))
}

@Composable
private fun MoreLine(text: String, onClick: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("mail-more"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 14.sp, color = c.text, textDecoration = TextDecoration.Underline, modifier = Modifier.weight(1f))
        Icon(BelfryIcons.ChevronDown, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

/** "Cc Dev Patel, park@example.org": only when the card has cc tags. */
private fun ccLine(m: MailItem): String? = m.cc.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.shown }?.let { "Cc $it" }

/** The small round reply-all icon at a card's top right: the only reply. */
@Composable
private fun ReplyAllIcon(onClick: () -> Unit) {
    val c = Belfry.colors
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(c.surface2).clickable(role = Role.Button, onClickLabel = "Reply all", onClick = onClick)
            .semantics { contentDescription = "Reply all" }.testTag("reply-all"),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.AutoMirrored.Outlined.ReplyAll, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp)) }
}

/** One e-mail on top: From leads the row, the Cc line, the subject, the text (two lines at MID). */
@Composable
private fun MailCard(m: MailItem, whole: Boolean, canMail: Boolean, onReply: () -> Unit, onFull: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val text = m.m.text.removePrefix("**${m.subject}**").trimStart()
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().clip(shape).border(1.dp, c.line, shape)
                .combinedClickable(onClick = {}, onLongClick = { menu = true }).padding(12.dp)
                .testTag(if (m.incoming) "mail-in" else "mail-out"),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (m.incoming) BelfryIcons.Mail else BelfryIcons.Send, contentDescription = null, tint = if (m.incoming) c.muted else MAIL_BLUE, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        val who = if (m.incoming) "E-mail from " + m.name.ifEmpty { m.address } else "Sent to " + MailInbox.names(recipients(m))
                        Text(who, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(" · " + ChatFormat.time(world, m.m.at), fontSize = 13.sp, color = c.muted, maxLines = 1)
                    }
                    ccLine(m)?.let { Text(it, fontSize = 13.sp, color = c.muted, maxLines = if (whole) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("mail-cc")) }
                    if (m.incoming && !m.verified) Text("Unverified sender", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.danger)
                }
                if (canMail) { Spacer(Modifier.width(8.dp)); ReplyAllIcon(onReply) }
            }
            Spacer(Modifier.height(6.dp))
            Text(m.subject.ifEmpty { "(no subject)" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            if (whole) MarkdownText(text.ifEmpty { "(no text)" }, fontSize = 15.sp)
            else Text(text.replace('\n', ' ').ifEmpty { "(no text)" }, fontSize = 15.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (whole && m.original != null) {
                Text(
                    "Show the whole e-mail", Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onFull).padding(vertical = 4.dp),
                    fontSize = 13.sp, color = c.text, textDecoration = TextDecoration.Underline,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.bg) {
            if (m.original != null) DropdownMenuItem(text = { Text("Full e-mail") }, onClick = { menu = false; onFull() }, leadingIcon = { Icon(BelfryIcons.Mail, null) }, modifier = Modifier.testTag("mail-full"))
            DropdownMenuItem(text = { Text("Copy text") }, onClick = { menu = false; clipboard.setText(AnnotatedString(text)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
        }
    }
}

/** Who a sent card went to, as names (its to / cc tags; an older card: its one address). */
internal fun recipients(m: MailItem): List<String> = (m.to + m.cc).map { it.shown }.ifEmpty { listOf(m.address).filter { it.isNotEmpty() } }

/** "From Lou · To hello@ · Cc 2", folded; a tap opens the three lines. */
@Composable
private fun FieldsLine(groupId: String, m: MailItem) {
    val c = Belfry.colors
    var open by remember { mutableStateOf(false) }
    val from = m.name.ifEmpty { m.address }
    val to = m.to.ifEmpty { listOf(sh.belfry.core.model.MailPerson(LocalWorld.current.mailAddress(groupId), "")) }
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show who it was for") { open = !open }
            .padding(horizontal = 16.dp, vertical = 10.dp).testTag("mail-fields"),
    ) {
        if (!open) {
            val folded = listOfNotNull(
                "From " + firstWord(from, m.address),
                "To " + to.first().address.substringBefore('@') + "@" + if (to.size > 1) " +${to.size - 1}" else "",
                m.cc.size.takeIf { it > 0 }?.let { "Cc $it" },
            ).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(folded, fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(BelfryIcons.ChevronDown, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
            }
        } else {
            Row {
                FieldText("From", if (m.name.isNotEmpty()) "${m.name} <${m.address}>" else m.address, Modifier.weight(1f))
                Text("Hide", fontSize = 14.sp, color = c.text, textDecoration = TextDecoration.Underline)
            }
            FieldText("To", to.joinToString(", ") { if (it.name.isNotEmpty() && it.name != it.address.substringBefore('@')) "${it.name} <${it.address}>" else it.address })
            if (m.cc.isNotEmpty()) FieldText("Cc", m.cc.joinToString(", ") { if (it.name.isNotEmpty()) "${it.name} <${it.address}>" else it.address })
        }
    }
}

@Composable
private fun FieldText(label: String, value: String, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = c.muted)) { append("$label ") }
            append(value)
        },
        fontSize = 14.sp, lineHeight = 20.sp, color = c.text, modifier = modifier.padding(vertical = 2.dp),
    )
}

/** To-dos, photos, files, people, notifications, settings; then Claim / Take it, Mark answered, Close. */
@Composable
private fun ThreadRows(groupId: String, t: InboxThread, n: ThreadCounts, admin: Boolean) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    var notify by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionGroup {
            if (n.todos > 0) ActionRow("To-dos", onClick = { nav.go(Routes.channelPlace(groupId, ChannelPlaceKey.TODOS)) }, icon = BelfryIcons.Check, value = n.todos.toString(), chevron = true)
            if (n.photos > 0) ActionRow("Photos", onClick = { nav.go(Routes.channelPlace(groupId, ChannelPlaceKey.PHOTOS)) }, icon = Icons.Outlined.Image, value = n.photos.toString(), chevron = true)
            if (n.files > 0) ActionRow("Files", onClick = { nav.go(Routes.files(groupId)) }, icon = Icons.Outlined.Folder, value = n.files.toString(), chevron = true)
            ActionRow("People", onClick = { nav.go(Routes.channelPlace(groupId, ChannelPlaceKey.MEMBERS)) }, icon = BelfryIcons.Users, value = n.people.takeIf { it > 0 }?.toString(), chevron = true, modifier = Modifier.testTag("mail-people"))
            ActionRow("Notifications", onClick = { notify = true }, icon = BelfryIcons.Bell, chevron = true)
            ActionRow("Settings", onClick = { nav.go(Routes.channelPlace(groupId, ChannelPlaceKey.SETTINGS)) }, icon = BelfryIcons.Settings, chevron = true)
        }
        if (admin) {
            val a = t.state.assignee
            val closed = t.state.status == CardStatus.CLOSED
            ActionGroup {
                when {
                    a == null -> BusyRow("Claim", onClick = { session.send(Actions.assignCard(groupId, t.id, world.me)) }, icon = BelfryIcons.User, sub = "Nobody has it yet", modifier = Modifier.testTag("mail-claim"))
                    a != world.me -> BusyRow("Take it", onClick = { session.send(Actions.assignCard(groupId, t.id, world.me)) }, icon = BelfryIcons.User, sub = world.displayName(a) + " has it", modifier = Modifier.testTag("mail-claim"))
                    else -> BusyRow("Let it go", onClick = { session.send(Actions.assignCard(groupId, t.id, "")) }, icon = BelfryIcons.User, sub = "You have it", modifier = Modifier.testTag("mail-claim"))
                }
                if (t.state.status == CardStatus.OPEN) BusyRow("Mark answered", onClick = { session.send(Actions.markAnswered(groupId, t.id)) }, icon = BelfryIcons.Check, modifier = Modifier.testTag("mail-answered"))
                BusyRow(if (closed) "Reopen" else "Close", onClick = { session.send(Actions.setCardStatus(groupId, t.id, !closed)) }, icon = if (closed) BelfryIcons.Retry else BelfryIcons.Close, modifier = Modifier.testTag("mail-close"))
            }
        }
    }
    if (notify) NotifySheet(groupId) { notify = false }
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

/** A quiet line: an icon, "**Maya** took it", the time. */
@Composable
private fun ActivityLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: AnnotatedString, time: String, trailing: (@Composable () -> Unit)? = null) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("activity-line"), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString { append(text); withStyle(SpanStyle(color = c.faint, fontSize = 12.sp)) { append("  $time") } },
            fontSize = 14.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

private fun bold(name: String, rest: String): AnnotatedString = buildAnnotatedString {
    if (name.isNotEmpty()) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }; append(" ") }
    append(rest)
}

/** A `belfry/inbox` label as a line, or null when it says nothing new. */
private fun labelLine(world: World, e: Event): Pair<androidx.compose.ui.graphics.vector.ImageVector, AnnotatedString>? {
    val l = e.tags.firstOrNull { it.size >= 3 && it[0] == "l" && it[2] == World.INBOX_NS }?.get(1) ?: return null
    val who = if (e.pubkey == world.me) "You" else world.displayName(e.pubkey)
    return when (l) {
        "assigned" -> {
            val p = e.tag("p")?.takeIf { it.isNotEmpty() }
            when {
                p == null -> BelfryIcons.User to bold(who, "let it go")
                p == e.pubkey -> BelfryIcons.User to bold(who, "took it")
                else -> BelfryIcons.User to bold(who, "gave it to " + if (p == world.me) "you" else world.displayName(p))
            }
        }
        "answered" -> BelfryIcons.Check to bold("", "Marked answered")
        "closed" -> BelfryIcons.Close to bold(who, "closed it")
        "open" -> BelfryIcons.Retry to bold(who, "reopened it")
        else -> null
    }
}

/** An e-mail received, in the conversation: "Lou e-mailed" and, opened, its subject, two lines, the Cc and the reply-all icon. */
@Composable
private fun MailPreview(m: MailItem, open: Boolean, canMail: Boolean, chat: ChatState, onReply: () -> Unit, onFull: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    var shown by remember(m.m.id, open) { mutableStateOf(open) }
    val text = m.m.text.removePrefix("**${m.subject}**").trimStart().replace('\n', ' ')
    val name = firstWord(m.name.ifEmpty { m.address }, m.address)
    Column(Modifier.fillMaxWidth().testTag("mail-preview")) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (shown) "Hide the details" else "Show the e-mail") { shown = !shown }
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(BelfryIcons.Mail, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(name) }
                    append(" e-mailed")
                    if (!shown) append(": " + m.subject)
                    withStyle(SpanStyle(color = c.faint, fontSize = 12.sp)) { append("  " + ChatFormat.time(world, m.m.at)) }
                },
                fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(if (shown) BelfryIcons.ChevronUp else BelfryIcons.ChevronDown, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
        if (shown) {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.padding(start = 40.dp, end = 12.dp, bottom = 4.dp).widthIn(max = 600.dp).fillMaxWidth().clip(shape).background(c.surface)
                    .clickable(enabled = m.original != null, role = Role.Button, onClick = onFull).padding(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.subject.ifEmpty { "(no subject)" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text.ifEmpty { "(no text)" }, fontSize = 14.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ccLine(m)?.let { Text(it, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (canMail) { Spacer(Modifier.width(8.dp)); ReplyAllIcon(onReply) }
            }
            // its pictures and files after it (canvas v179 one-shape/email-pictures): the bridge's imeta tags
            Column(Modifier.padding(start = 40.dp, end = 12.dp, bottom = 4.dp)) { sh.belfry.app.ui.chat.MessageMedia(m.m, chat) }
        }
    }
}

/** What went out: "Maya replied all", then the blue card "Sent by e-mail to Lou, Dev Patel and 1 more" with the text. */
@Composable
private fun SentMail(world: World, m: MailItem) {
    val c = Belfry.colors
    val by = m.by?.let { if (it == world.me) "You" else world.displayName(it) } ?: "The team"
    Column(Modifier.fillMaxWidth().testTag("sent-mail")) {
        ActivityLine(BelfryIcons.Send, bold(by, "replied all"), ChatFormat.time(world, m.m.at))
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier.padding(start = 40.dp, end = 12.dp, bottom = 4.dp).widthIn(max = 600.dp).fillMaxWidth().clip(shape)
                .background(MAIL_BLUE.copy(alpha = if (c.isDark) 0.22f else 0.08f)).border(1.dp, MAIL_BLUE.copy(alpha = 0.6f), shape).padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(BelfryIcons.Send, contentDescription = null, tint = leadColor(), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sent by e-mail to " + MailInbox.names(recipients(m)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = leadColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (m.m.text.isNotEmpty()) Text(m.m.text, fontSize = 14.sp, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An internal draft: a dashed card, "Internal draft", the subject, two lines, "Tap to continue" (for who may send). */
@Composable
private fun DraftCard(world: World, t: InboxThread, m: Message, onOpen: (() -> Unit)?) {
    val c = Belfry.colors
    val dash = c.muted
    val by = if (m.pubkey == world.me) "you" else world.displayName(m.pubkey)
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 640.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .drawBehind {
                drawRoundRect(dash, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))))
            }
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Continue the draft", onClick = onOpen) else Modifier)
            .padding(12.dp).testTag("mail-draft"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(BelfryIcons.Edit, contentDescription = null, tint = c.text, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Internal draft", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(" · by $by · " + ChatFormat.time(world, m.edited ?: m.at), fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(replySubject(t), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(m.text.replace('\n', ' '), fontSize = 14.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onOpen != null) Text("Tap to continue", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** "Re: <subject>" of the latest e-mail received. */
internal fun replySubject(t: InboxThread): String {
    val s = t.lastIn?.subject ?: t.subject
    return if (s.startsWith("re:", ignoreCase = true)) s else "Re: " + sh.belfry.core.mail.MailThreads.baseSubject(s).ifEmpty { "(no subject)" }
}

/** Open / Answered / Closed as a small pill: words first, colour second (e-ink). */
@Composable
internal fun StatePill(status: CardStatus, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val open = status == CardStatus.OPEN
    val shape = RoundedCornerShape(50)
    Text(
        stateWord(status), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (open) c.orange else c.muted,
        modifier = modifier.clip(shape).border(1.dp, if (open) c.orange else c.line, shape).background(if (open) c.orange.copy(alpha = 0.12f) else c.surface).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** An outsider's initials on grey: guests by e-mail have no profile. */
@Composable
internal fun MailFace(name: String, size: Dp = 36.dp) {
    val c = Belfry.colors
    val letters = name.split(' ', '.', '@').filter { it.isNotEmpty() }.let { w ->
        val one = w.firstOrNull() ?: "?"
        one.take(1).uppercase() + (one.drop(1).firstOrNull { it.isLetter() }?.toString() ?: "")
    }
    Box(Modifier.size(size).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
        Text(letters, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
    }
}
