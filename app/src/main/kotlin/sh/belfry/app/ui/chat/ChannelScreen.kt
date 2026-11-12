package sh.belfry.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Task
import sh.belfry.core.model.ComposerDraft
import sh.belfry.core.model.FormDrafts
import sh.belfry.app.ui.kit.Keep
import sh.belfry.app.ui.kit.rememberFormDraft
import sh.belfry.core.model.pinned
import sh.belfry.core.model.todosBySource
import sh.belfry.core.model.agentOwner
import sh.belfry.core.model.unsubstituteChannels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import sh.belfry.core.model.World
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.ui.cards.ChannelBars
import sh.belfry.app.ui.cards.EventHandoff
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Banner
import sh.belfry.app.ui.kit.BannerKind
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.DaySeparator
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalStatus
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.people.SaveAccountBar
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.model.FeedItem
import sh.belfry.core.model.FeedRow
import sh.belfry.core.model.Mentions
import sh.belfry.core.model.Message
import sh.belfry.core.model.Topics
import androidx.compose.ui.text.style.TextOverflow
import sh.belfry.app.ui.kit.Avatar

/**
 * A channel: its top bar (☰ · name · search · follow), the one summary line under the name
 * (pulled down for the rest: [SummaryLine], [PullDown]), the feed anchored at the bottom, and
 * the composer. [focusMessage] scrolls to a message and
 * highlights it briefly (search results, links); [onFocusDone] is called once it did.
 */
@Composable
fun ChannelScreen(
    groupId: String,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    focusMessage: String? = null,
    onFocusDone: () -> Unit = {},
    chat: ChatState = rememberChatState(groupId),
    composer: ComposerState = rememberComposerState(groupId),
    /** A large screen: threads open beside the channel instead of on their own screen. */
    onOpenThread: ((rootId: String) -> Unit)? = null,
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val openThread: (String) -> Unit = { id -> if (onOpenThread != null) onOpenThread(id) else nav.go(Routes.thread(groupId, id)) }
    val status = LocalStatus.current
    val dms = LocalDms.current
    val g = world.group(groupId)
    val admin = world.canAdmin(g)
    val now = session.now()

    LaunchedEffect(groupId, g != null) { if (g != null) session.markSeen(groupId) }
    // a new event made from this channel's ＋: its URL waits in the composer, ready to send
    val handed = EventHandoff.pending
    LaunchedEffect(handed, groupId) {
        EventHandoff.take(groupId)?.let { url ->
            val t = composer.value.text.trimEnd()
            composer.set((if (t.isEmpty()) "" else "$t ") + url + " ", composer.picked.toList())
            composer.focus?.invoke()
        }
    }

    val openRoots = chat.openThreads.keys.filter { !it.startsWith("act:") }.toSet()
    // The feed is drawn from the snapshot it was last drawn from until something its rows show
    // changes (World.feedKey): a message arriving in another channel redraws nothing here.
    val feedWorld = rememberFeedWorld(world, groupId)
    val feedNow = now - now % 60
    val rows = remember(feedWorld, groupId, chat.topic, openRoots) { feedWorld.feedRows(groupId, chat.topic, openRoots) }
    val threads = feedWorld.threadsOf(groupId)
    val reversed = rows.asReversed()
    val list = rememberLazyListState()
    var highlight by remember { mutableStateOf<String?>(null) }

    val focus = focusMessage ?: chat.focus
    LaunchedEffect(focus, rows.size) {
        if (focus != null) {
            val i = reversed.indexOfFirst { it.item.key == focus }
            if (i >= 0) {
                list.scrollToItem(i)
                highlight = focus
                if (focusMessage != null) onFocusDone()
                chat.focus = null
                delay(1800)
                highlight = null
            }
        }
    }
    // What I just wrote shows at once: go to the end.
    val lastKey = rows.lastOrNull()?.item?.key
    LaunchedEffect(lastKey) {
        val last = rows.lastOrNull()?.item
        if (last is FeedItem.Msg && last.m.pubkey == world.me && world.isPending(last.m.id) && focus == null) list.scrollToItem(0)
    }

    // "Got it, hide" on a pinned message is remembered on the device.
    LaunchedEffect(groupId) {
        val saved = withContext(Dispatchers.IO) { session.store.get(K_HIDDEN_PINS) }.orEmpty().split(',').filter { it.isNotEmpty() }
        chat.hiddenPins = chat.hiddenPins + saved
    }
    LaunchedEffect(chat.hiddenPins) {
        if (chat.hiddenPins.isNotEmpty()) withContext(Dispatchers.IO) { session.store.set(K_HIDDEN_PINS, chat.hiddenPins.toList().takeLast(200).joinToString(",")) }
    }
    BackHandler(enabled = chat.lineOpen) { chat.lineOpen = false }

    val pinnedIds = remember(world, groupId) { world.pinned(groupId).map { it.id }.toSet() }
    val todos = remember(world, groupId) { world.todosBySource(groupId) }
    // A message right after one that is still queued or was refused starts its own block, so the
    // "Waiting for connection…" / "Not sent" line never sits between two texts of one block.
    val afterPending = remember(rows, world) {
        val out = HashSet<String>()
        for (i in 1 until rows.size) {
            val prev = rows[i - 1].item
            val cur = rows[i].item
            if (prev is FeedItem.Msg && cur is FeedItem.Msg && world.isPending(prev.m.id)) out.add(cur.key)
        }
        out
    }

    Column(modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar(
            title = (if (world.isPublicChannel(g)) "🌐 " else "") + (g?.name ?: groupId),
            onTitleClick = { chat.lineOpen = !chat.lineOpen },
            navigation = if (onMenu != null) {
                { IconAction(BelfryIcons.Menu, "Channels", onMenu, badge = dms.unreadTotal) }
            } else null,
            actions = {
                IconAction(BelfryIcons.Search, "Search", { nav.go(Routes.search(groupId)) })
                IconAction(BelfryIcons.Bell, "Follow this channel", { nav.go(Routes.follow(groupId)) })
            },
        )
        if (g != null) {
            SummaryLine(groupId, chat, now)
            Hairline()
        }
        status.banner?.let { Banner(it, kind = BannerKind.Warning) }
        ChannelBars(groupId)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                if (chat.topic != null) {
                    EmptyState("Nothing here yet", text = if (chat.topic == Topics.ANNOUNCE) "No announcements in this channel." else "No messages in #${chat.topic}.")
                } else {
                    EmptyState(
                        "${g?.name ?: groupId} is new",
                        text = "No messages yet. Bring the people who belong here, then say hi.",
                        icon = BelfryIcons.Hash,
                        action = { BusyButton("Invite people", onClick = { nav.go(Routes.invite(groupId)) }, kind = ButtonKind.Ghost, icon = BelfryIcons.Users, small = true) },
                    )
                }
            } else {
                CompositionLocalProvider(LocalWorld provides feedWorld) {
                    LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(reversed, key = { it.item.key }, contentType = { it.item::class }) { row ->
                            FeedRowView(groupId, row, chat, threads[row.item.key], highlight == row.item.key, feedNow, row.item.key in afterPending, row.item.key in pinnedIds, todos[row.item.key], openThread)
                        }
                    }
                }
            }
            // The summary line pulled down: over the feed, under the top bar.
            androidx.compose.animation.AnimatedVisibility(visible = chat.lineOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close the channel summary") { chat.lineOpen = false },
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = chat.lineOpen && g != null, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.fillMaxWidth().background(c.bg)) {
                    PullDown(groupId, chat, now, onShowMessage = { chat.focus = it }, modifier = Modifier.weight(1f, fill = false))
                    // the handle: tap or drag up to close
                    Box(
                        Modifier.fillMaxWidth().height(22.dp)
                            .pointerInput(Unit) {
                                var moved = 0f
                                detectVerticalDragGestures(onDragStart = { moved = 0f }, onDragEnd = { if (moved < -16f) chat.lineOpen = false }) { _, dy -> moved += dy }
                            }
                            .clickable(role = Role.Button, onClickLabel = "Close the channel summary") { chat.lineOpen = false },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) }
                    Hairline()
                }
            }
        }

        SaveAccountBar(session)
        val readOnly = g != null && g.isPod && g.closed && world.me !in g.members
        val editing = chat.editing
        // #proposals, #events, #bookings (web 2026-11-11): one type of post, no free text at the top
        val page = world.webPage(g)
        val only = page?.posts?.takeIf { it == "proposal" || it == "event" }
        val isPub = page?.on == true && page.visibility == "public"
        if (!readOnly && only != null) {
            Hairline()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        only == "proposal" && isPub -> "Public proposals: anyone on the web sees them."
                        only == "proposal" -> "Members only: proposals to discuss and vote on."
                        isPub -> "Public: anyone on the web sees these events."
                        else -> "Members only: internal bookings."
                    },
                    fontSize = 13.sp, color = c.muted, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                BusyButton(
                    if (only == "proposal") "New proposal" else "New event",
                    onClick = { nav.go(if (only == "proposal") Routes.createProposal(groupId) else Routes.createEvent(groupId)) },
                    small = true,
                )
            }
        }
        if (!readOnly && only == null && isPub) {
            Hairline()
            Text(
                "🌐 Public: anyone on the web sees this channel" + if (page?.posts == "request") ". Members can turn a request into a proposal." else ".",
                fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
        if (!readOnly && only == null && (chat.topic != Topics.ANNOUNCE || admin)) {
            if (!isPub) Hairline()
            if (editing != null) EditingBar(onCancel = { chat.editing = null; composer.clear() })
            Composer(
                composer,
                placeholder = when {
                    editing != null -> "Edit your message"
                    composer.announce || chat.topic == Topics.ANNOUNCE -> "Announcement"
                    chat.topic != null -> "Message #${chat.topic}"
                    else -> "Message ${g?.name ?: groupId}"
                },
                onSend = { text, files, picked, announce ->
                    if (editing != null) {
                        session.editMessage(groupId, editing, text, picked)
                        // emptied while the edit is still open: the draft keeper is paused then, so
                        // the emptied box is not taken for an emptied draft, and the channel's own
                        // draft comes back once the edit is over
                        composer.clear()
                        chat.editing = null
                    } else {
                        val topic = chat.topic?.takeIf { it != Topics.ANNOUNCE }
                        session.postMessage(groupId, text, files, topic, admin && (announce || chat.topic == Topics.ANNOUNCE), picked)
                    }
                },
                modifier = Modifier.navigationBarsPadding(),
                draftKey = ComposerDraft.channel(groupId),
                draftPaused = { chat.editing != null },
                photos = editing == null,
                onPlus = if (editing == null) ({ chat.plusOpen = true }) else null,
                groupId = groupId,
                onInvite = { pk -> inviteSaid(session.inviteMember(groupId, pk)) },
            )
        } else {
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    val startEdit: (Message) -> Unit = { m ->
        val (named, chans) = world.unsubstituteChannels(m.text)
        val (text, picks) = Mentions.unsubstitute(named) { world.realName(it) }
        chat.editing = m
        composer.set(text, picks, chans)
        composer.focus?.invoke()
    }
    MessageSheets(
        groupId, chat,
        onReply = { m -> openThread(m.id) },
        onOpenThread = { m -> openThread(m.id) },
        onEdit = startEdit,
    )
    if (chat.plusOpen) PlusSheet(groupId, admin, composer, onClose = { chat.plusOpen = false }, onTodo = { chat.newTodo = true })
    if (chat.newTodo) BelfrySheet(onDismiss = { chat.newTodo = false }, title = "New todo") { NewTodoContent(groupId, onDone = { chat.newTodo = false }) }
    chat.viewer?.let { ImageViewer(it) { chat.viewer = null } }
}

@Composable
private fun FeedRowView(groupId: String, row: FeedRow, chat: ChatState, thread: sh.belfry.core.model.ThreadInfo?, highlight: Boolean, now: Long, ownBlock: Boolean, pinned: Boolean, todo: Task?, openThread: (String) -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    Column(Modifier.fillMaxWidth()) {
        if (row.newDay) DaySeparator(ChatFormat.day(world, row.item.at, now))
        when (val item = row.item) {
            is FeedItem.Msg -> {
                val m = item.m
                val open = chat.openThreads.containsKey(m.id)
                MessageRow(
                    groupId, m, row.first || ownBlock, chat,
                    announcement = row.announcement, highlight = highlight, thread = thread, threadOpen = open,
                    onToggleThread = { openThread(m.id) }, // no replies unfolded in the channel (web 2026-11-11)
                    onOpenThread = { openThread(m.id) },
                    pinned = pinned, todo = todo,
                )
                if (open) {
                    InlineThread(groupId, m, chat, focus = chat.openThreads[m.id] == true, onHide = { chat.openThreads.remove(m.id) }, onOpen = { openThread(m.id) })
                }
            }
            is FeedItem.Join -> {
                val j = item.j
                // a member's own agent: "<owner>'s agent" (its owner, else who brought it)
                val own = j.agent && j.member && j.by != null
                val owner = world.agentOwner(j.pubkey) ?: j.by
                val what = if (own) (if (owner == world.me) "your agent" else "${world.displayName(owner!!)}'s agent") else if (j.agent) "an agent" else "a guest"
                val by = if (own) "" else j.by?.let { ", invited by ${world.displayName(it)}" } ?: ""
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(BelfryIcons.User, contentDescription = null, tint = c.faint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${world.displayName(j.pubkey)} joined as $what$by", fontSize = 13.sp, color = c.muted,
                        modifier = Modifier.weight(1f).clickable(role = Role.Button) { nav.go(Routes.profile(j.pubkey)) },
                    )
                    Text(ChatFormat.time(world, j.at), fontSize = 12.sp, color = c.faint)
                }
            }
            is FeedItem.Activity -> {
                val key = item.key
                val open = chat.openThreads.containsKey(key)
                // proposal A (web 2026-11-11): the message it answers as one grey quote line,
                // then who replied, when, and the reply without its leading @mention
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { openThread(item.root.id) }.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                    Avatar(world.face(item.last.pubkey), size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(2.dp).height(16.dp).background(c.line))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "↳ ${world.displayName(item.root.pubkey)}: ${unmarked(world.activitySnippet(item.root))}",
                                fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Text(world.displayName(item.last.pubkey), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1)
                            Spacer(Modifier.width(6.dp))
                            Text(ChatFormat.time(world, item.at), fontSize = 12.sp, color = c.faint)
                        }
                        if (!open) Text(replyPreview(unmarked(world.activityReply(item.last))).ifEmpty { "a photo" }, fontSize = 15.sp, color = c.text, maxLines = 3)
                    }
                }
                if (open) {
                    InlineThread(groupId, item.root, chat, focus = chat.openThreads[key] == true, onHide = { chat.openThreads.remove(key) }, onOpen = { openThread(item.root.id) })
                }
            }
        }
    }
}

/** ＋ : what can be added to the conversation, as a Discord-style list. */
@Composable
private fun PlusSheet(groupId: String, admin: Boolean, composer: ComposerState, onClose: () -> Unit, onTodo: () -> Unit) {
    val nav = LocalNav.current
    BelfrySheet(onDismiss = onClose) { PlusSheetContent(groupId, admin, composer, onClose, nav::go, onTodo) }
}

@Composable
fun PlusSheetContent(groupId: String, admin: Boolean, composer: ComposerState, onClose: () -> Unit, go: (String) -> Unit, onTodo: (() -> Unit)? = null) {
    ActionGroup {
        ActionRow("Photos", onClick = { onClose(); composer.pickPhotos?.invoke() }, icon = BelfryIcons.Image)
    }
    Spacer(Modifier.height(12.dp))
    ActionGroup {
        if (admin) {
            ActionRow("Announcement", onClick = { onClose(); composer.announce = true; composer.focus?.invoke() }, icon = BelfryIcons.Megaphone, sub = "Stands out; followers get it by e-mail")
        }
        ActionRow("Event", onClick = { onClose(); go(Routes.createEvent(groupId)) }, icon = BelfryIcons.Calendar)
        if (onTodo != null) {
            ActionRow("Todo", onClick = { onClose(); onTodo() }, icon = MoreIcons.Todo, sub = "Something to get done, on the channel's list", modifier = Modifier.testTag("plus-todo"))
        }
        ActionRow("Proposal", onClick = { onClose(); go(Routes.createProposal(groupId)) }, icon = BelfryIcons.Proposal, sub = "A text to discuss and vote on")
        ActionRow("Pod", onClick = { onClose(); go(Routes.createPod(groupId)) }, icon = BelfryIcons.Pod, sub = "A working group with its own channel")
        ActionRow("Drop box link", onClick = { onClose(); go(Routes.drop(groupId)) }, icon = BelfryIcons.Upload, sub = "Let anyone send photos and files here")
    }
}

/** A snippet without Markdown marks (bold, italics, links stay as their text). */
private fun unmarked(text: String): String = Markdown.text(Markdown.inline(text))

/** "New to-do": something to get done, on the channel's list. */
@Composable
fun NewTodoContent(groupId: String, onDone: () -> Unit) {
    val session = LocalSession.current
    var title by remember { mutableStateOf("") }
    // kept on this device until it is added (web 2026-11-11)
    val draft = rememberFormDraft(FormDrafts.todo(groupId)) { kept -> title = kept["title"] ?: title }
    draft.Keep("title" to title)
    BelfryTextField(title, { title = it.take(300) }, label = "What needs doing?", placeholder = "e.g. Book the room for Friday", modifier = Modifier.fillMaxWidth().testTag("todo-title"))
    Spacer(Modifier.height(12.dp))
    BusyButton(
        "Add to the list",
        onClick = {
            session.send(Actions.addTask(groupId, title.trim()))
            draft.clear()
            onDone()
        },
        modifier = Modifier.fillMaxWidth(), enabled = title.isNotBlank(),
    )
    Spacer(Modifier.height(10.dp))
    Text("Everyone in the channel sees it under the channel's name. Anyone can take it or tick it off.", fontSize = 13.sp, color = Belfry.colors.muted)
}

private const val K_HIDDEN_PINS = "pins-hidden"

/**
 * The snapshot the feed is drawn from: the latest one whenever what the channel's rows show has
 * changed ([World.feedKey]), else the one it already has. The rows read it as `LocalWorld`.
 */
@Composable
private fun rememberFeedWorld(live: World, groupId: String): World {
    val key = live.feedKey(groupId)
    val held = remember(groupId) { arrayOf<Any>(live, key) }
    if (held[1] != key) { held[0] = live; held[1] = key }
    return held[0] as World
}
