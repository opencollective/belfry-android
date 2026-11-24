package sh.belfry.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import sh.belfry.core.model.ComposerDraft
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Mentions
import sh.belfry.core.model.unsubstituteChannels
import sh.belfry.core.model.Message
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.core.model.StepGroup
import sh.belfry.core.model.TaskSteps

/** A thread on its own screen: the message it hangs off, every reply, a reply box (NIP-22). */
@Composable
fun ThreadScreen(
    groupId: String,
    rootId: String,
    chat: ChatState = rememberChatState("thread:$groupId:$rootId"),
    composer: ComposerState = rememberComposerState(rootId),
    /** Shown as the third column beside its channel (a large screen): a close button, no back arrow. */
    onClose: (() -> Unit)? = null,
    /** A reply to centre and flash (canvas channels/message-link). */
    focus: String? = null,
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    // an e-mail thread of a shared inbox has its own view (SPEC 19.33)
    world.mailThread(groupId, rootId)?.let { t ->
        sh.belfry.app.ui.cards.MailThreadScreen(groupId, t, onClose)
        return
    }
    val session = LocalSession.current
    val nav = LocalNav.current
    val g = world.group(groupId)
    val root = world.message(groupId, rootId)
    val replies = world.threadReplies(groupId, rootId)
    val list = rememberLazyListState()
    var flash by remember(focus) { mutableStateOf<String?>(null) }
    LaunchedEffect(replies.size) {
        val at = if (focus != null) replies.indexOfFirst { it.id == focus } else -1
        if (at >= 0 && flash == null) {
            // centred: the reply's row a third of the screen down
            list.scrollToItem(at + 2, -(list.layoutInfo.viewportSize.height / 3))
            flash = focus
            kotlinx.coroutines.delay(2_000)
            flash = ""
        } else if (replies.isNotEmpty() && focus == null) list.scrollToItem(replies.size + 1)
    }
    // read state (SPEC 19.27): a thread on screen is read up to its newest reply
    val started = sh.belfry.app.ui.kit.rememberStarted()
    val newest = replies.lastOrNull()?.ev?.createdAt ?: root?.ev?.createdAt
    LaunchedEffect(rootId, started, newest) { if (started && newest != null) session.markThreadRead(groupId, rootId) }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        // one-shape header: the title is the beginning of the first message, then 👥 who took part · 💬 replies;
        // nothing is hidden (the first message stays on top of the replies)
        val people = remember(root, replies) { ((listOfNotNull(root?.pubkey)) + replies.map { it.pubkey }).toSet().size }
        sh.belfry.app.ui.kit.OneHeader(
            threadTitle(world, root),
            items = threadRowItems(people, replies.size),
            navigation = if (onClose == null) ({ BackButton { nav.back() } }) else null,
            actions = { if (onClose != null) IconAction(BelfryIcons.Close, "Close the thread", onClose) },
        )
        if (root == null) {
            Box(Modifier.weight(1f)) { EmptyState("This message is gone", text = "It was deleted, or it has not reached this device yet.") }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + TYPING_ROOM)) {
            item(key = "root") { MessageRow(groupId, root, true, chat, announcement = world.isAnnouncement(root, g)) }
            item(key = "sep") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(c.line))
                    Text(
                        if (replies.isEmpty()) "No replies yet" else "${replies.size} ${if (replies.size == 1) "reply" else "replies"}",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(c.line))
                }
            }
            // the replies and, between them, the status of to-dos made from this thread (web 2026-11-11)
            val ids = setOf(rootId) + replies.map { it.id }
            val steps = TaskSteps.groups(world.tasks(groupId).filter { it.source != null && it.source in ids })
            val flow: List<Any> = (replies.map { it.at to (it as Any) } + steps.map { it.at to (it as Any) })
                .sortedWith(compareBy({ it.first }, { if (it.second is StepGroup) 1 else 0 })).map { it.second }
            itemsIndexed(flow, key = { _, x -> if (x is StepGroup) "step:" + x.task.id + x.at + x.what.joinToString() else (x as sh.belfry.core.model.Message).id }) { i, x ->
                if (x is StepGroup) {
                    StepLine(x)
                } else {
                    val r = x as sh.belfry.core.model.Message
                    val prev = flow.getOrNull(i - 1) as? sh.belfry.core.model.Message
                    MessageRow(groupId, r, prev == null || prev.pubkey != r.pubkey || r.at - prev.at > 300, chat, highlight = flash == r.id)
                }
            }
        }
        // canvas v110 messages/typing-thread: only typing events whose e is this root
        val lastReply = remember(replies) { HashMap<String, Long>().also { m -> for (r in replies) if (r.at > (m[r.pubkey] ?: 0L)) m[r.pubkey] = r.at } }
        WatchTyping(groupId)
        TypingLine(sh.belfry.core.typing.TypingPlace.thread(groupId, rootId)) { lastReply[it] ?: 0L }
        val reading = session.guest.collectAsState().value?.reading == true
        if (reading) sh.belfry.app.ui.people.GuestBar(groupId)
        if (!reading) Hairline()
        val editing = chat.editing
        if (editing != null && !reading) EditingBar(onCancel = { chat.editing = null; composer.clear() })
        if (!reading) Composer(
            composer,
            placeholder = if (editing != null) "Edit your message" else "Reply in the thread",
            onSend = { text, files, picked, _ ->
                if (editing != null) {
                    session.editMessage(groupId, editing, text, picked)
                    chat.editing = null
                } else {
                    session.replyInThread(groupId, root.ev, text, files, picked, preview = composer.previewTags(world.ctx.domain, session.community.blossom))
                }
            },
            modifier = Modifier.navigationBarsPadding(),
            draftKey = ComposerDraft.thread(rootId),
            draftPaused = { chat.editing != null },
            linkPreview = if (editing == null) session.previewModeForGroup(groupId) else null,
            photos = editing == null,
            groupId = groupId,
            onInvite = { pk -> inviteSaid(session.inviteMember(groupId, pk)) },
            typingPlace = sh.belfry.core.typing.TypingPlace.thread(groupId, rootId),
        )
    }
    val startEdit: (Message) -> Unit = { m ->
        val (named, chans) = world.unsubstituteChannels(m.text)
        val (text, picks) = Mentions.unsubstitute(named) { world.realName(it) }
        chat.editing = m
        composer.set(text, picks, chans)
        composer.focus?.invoke()
    }
    MessageSheets(groupId, chat, onReply = null, onOpenThread = null, onEdit = startEdit)
    chat.viewer?.let { ImageViewer(it) { chat.viewer = null } }
}


/** A to-do's status in its thread: who, what, which to-do, when (merged when together). */
@Composable
private fun StepLine(g: StepGroup) {
    val c = Belfry.colors
    val world = LocalWorld.current
    Row(Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(MoreIcons.Todo, contentDescription = null, tint = if (g.what.last() in setOf("done", "working")) c.text else c.faint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(world.displayName(g.who)) }
                append(" " + TaskSteps.text(g.what) + ": “" + g.task.title + "”")
            },
            fontSize = 13.sp, color = c.muted, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(ChatFormat.time(world, g.at), fontSize = 12.sp, color = c.faint)
    }
}
