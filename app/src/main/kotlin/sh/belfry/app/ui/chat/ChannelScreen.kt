package sh.belfry.app.ui.chat

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import sh.belfry.core.model.secondAttentionLine
import sh.belfry.app.ui.kit.TodoTextField
import sh.belfry.core.model.TodoLimit
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.rememberNameLoading
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.runtime.collectAsState
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
import sh.belfry.core.model.readOnlyClosed
import sh.belfry.app.ui.cards.EventHandoff
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Banner
import sh.belfry.app.ui.kit.BannerKind
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.DaySeparator
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.OneHeader
import sh.belfry.app.ui.kit.OnePulled
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalStatus
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.people.SaveAccountBar
import sh.belfry.app.ui.people.GuestBar
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.model.FeedItem
import sh.belfry.core.model.FeedRow
import sh.belfry.core.model.feedRowsWithPreviews
import sh.belfry.core.model.Mentions
import sh.belfry.core.model.Message
import sh.belfry.core.model.Topics
import sh.belfry.core.model.attentionLine
import sh.belfry.core.model.isDeletedGroup
import sh.belfry.core.model.isLocked
import androidx.compose.ui.text.style.TextOverflow
import sh.belfry.app.ui.kit.Avatar

/**
 * A channel: its one-shape header (☰ or ‹ · name over one icon row · the bell; tapped or dragged
 * down, [ChannelMenu] over the feed), the feed anchored at the bottom with its activity previews,
 * and the composer. [focusMessage] scrolls to a message and
 * highlights it briefly (search results, links); [onFocusDone] is called once it did.
 */
@Composable
fun ChannelScreen(
    groupId: String,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    /** ☰'s count (Town: the town's unread channels). */
    menuBadge: Int? = null,
    /** Pushed over a place (a card, a link): a back arrow instead of ☰. */
    onBack: (() -> Unit)? = null,
    /** Inside another screen (an event's Chat tab): no header of its own. */
    embedded: Boolean = false,
    /** Embedded with nothing said yet: what stands there instead of "… is new · Invite people" (an event's short menu). */
    emptyContent: (@Composable () -> Unit)? = null,
    focusMessage: String? = null,
    onFocusDone: () -> Unit = {},
    /** Bumped by a second tap on Town: scroll to the newest message. */
    newestTick: Int = 0,
    chat: ChatState = rememberChatState(groupId),
    composer: ComposerState = rememberComposerState(groupId),
    /** A large screen: threads open beside the channel instead of on their own screen. */
    onOpenThread: ((rootId: String) -> Unit)? = null,
    /** A proposal's chat: the level its header opens at (round 6: half; from a proposal's card: full). */
    proposalStart: sh.belfry.app.ui.cards.EventLevel = sh.belfry.app.ui.cards.EventLevel.HALF,
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
    if (world.isDeletedGroup(groupId)) {
        GroupGone(onBack, onMenu)
        return
    }
    // guest mode (SPEC 19.26): reading only; the composer's place says so, with Join
    val guestInfo by session.guest.collectAsState()
    val guestReading = guestInfo?.reading == true
    // a topic reached through a card (SPEC 19.77): the one place where a reading guest has a composer
    val guestTopic = sh.belfry.core.model.GuestTopic.here(guestReading, guestInfo?.channel, guestInfo?.card, groupId) && !world.isLocked(groupId)

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
    val rows = remember(feedWorld, groupId, chat.topic, openRoots) { feedWorld.feedRowsWithPreviews(groupId, chat.topic, openRoots) }
    val threads = feedWorld.threadsOf(groupId)
    val reversed = rows.asReversed()
    val list = rememberLazyListState()
    var highlight by remember { mutableStateOf<String?>(null) }

    val focus = focusMessage ?: chat.focus
    // a message link to something not shown here (deleted, unknown, not readable): a grey line (canvas channels/message-link)
    var unavailable by remember(focusMessage) { mutableStateOf(false) }
    val rowsNow by androidx.compose.runtime.rememberUpdatedState(reversed)
    val worldNow by androidx.compose.runtime.rememberUpdatedState(world)
    LaunchedEffect(focusMessage) {
        if (focusMessage == null) return@LaunchedEffect
        delay(3_000)
        if (rowsNow.none { it.item.key == focusMessage } && worldNow.replyRoot(groupId, focusMessage) == null) { unavailable = true; onFocusDone() }
    }
    // it stays at the top until the feed is scrolled
    LaunchedEffect(unavailable) { if (unavailable) { snapshotFlow { list.isScrollInProgress }.first { it }; unavailable = false } }
    LaunchedEffect(focus, rows.size) {
        if (focus != null) {
            val i = reversed.indexOfFirst { it.item.key == focus }
            // a thread reply: its thread, the reply centred and flashed
            val root = if (i < 0) world.replyRoot(groupId, focus) else null
            if (root != null) {
                if (focusMessage != null) onFocusDone()
                chat.focus = null
                if (onOpenThread != null) onOpenThread(root) else nav.go(Routes.thread(groupId, root, focus))
            } else if (i >= 0) {
                unavailable = false
                list.scrollToItem(i)
                highlight = focus
                if (focusMessage != null) onFocusDone()
                chat.focus = null
                delay(1800)
                highlight = null
            }
        }
    }
    LaunchedEffect(newestTick) { if (newestTick > 0 && rows.isNotEmpty()) list.animateScrollToItem(0) }
    // What I just wrote shows at once: go to the end.
    val lastKey = rows.lastOrNull()?.item?.key
    LaunchedEffect(lastKey) {
        val last = rows.lastOrNull()?.item
        if (last is FeedItem.Msg && last.m.pubkey == world.me && world.isPending(last.m.id) && focus == null) list.scrollToItem(0)
    }

    // Read state (SPEC 19.27): on screen, app in the foreground, at the newest message → read up to it.
    val started = sh.belfry.app.ui.kit.rememberStarted()
    val atNewest by remember(list) { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex == 0 } }
    val newest = sh.belfry.core.read.Unread.channelMark(world, groupId)
    LaunchedEffect(groupId, started, atNewest, newest) { if (started && atNewest && newest != null) session.markChannelRead(groupId) }

    // the channel's address as the belfry says (SPEC 19.60): once a session, kept; shown and answered from
    LaunchedEffect(groupId) { if (!groupId.startsWith("ev-") && !groupId.startsWith("prop-")) try { session.mailAlias.info(groupId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { } }
    // "Got it, hide" on a pinned message is remembered on the device.
    LaunchedEffect(groupId) {
        val saved = withContext(Dispatchers.IO) { session.store.get(K_HIDDEN_PINS) }.orEmpty().split(',').filter { it.isNotEmpty() }
        chat.hiddenPins = chat.hiddenPins + saved
    }
    LaunchedEffect(chat.hiddenPins) {
        if (chat.hiddenPins.isNotEmpty()) withContext(Dispatchers.IO) { session.store.set(K_HIDDEN_PINS, chat.hiddenPins.toList().takeLast(200).joinToString(",")) }
    }
    BackHandler(enabled = chat.lineOpen) { chat.pushLine() }

    val pinnedIds = remember(world, groupId) { world.pinned(groupId).map { it.id }.toSet() }
    val todos = remember(world, groupId) { world.todosBySource(groupId) }
    // A message right after one that is still queued or was refused starts its own block, so the
    // "Waiting for the connection…" / "Not sent" line never sits between two texts of one block.
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
        // one-shape header (docs/one-shape.md 2): the name, one icon row, the bell; tap or drag for the rest
        if (!embedded) OneHeader(
            title = g?.name ?: "Channel", // never the id (SPEC 19.39)
            items = if (g != null) channelRowItems(world, groupId, now) else emptyList(),
            onPull = { chat.pullLine() },
            onPush = { chat.closeLine() },
            pulled = chat.lineOpen,
            pullLabel = if (chat.lineOpen) "Close the channel summary" else "Open the channel summary",
            navigation = when {
                onBack != null -> ({ BackButton(onBack) })
                onMenu != null -> ({ IconAction(BelfryIcons.Menu, "The town", onMenu, badge = menuBadge ?: dms.unreadTotal) })
                else -> null
            },
            // nothing at the top right but the bell (search is a row of the pull-down)
            actions = { if (g != null && !guestReading && !world.isLocked(groupId)) ChannelBell(groupId, g.name) },
            titleMark = if (g != null && world.failedSettings(groupId).isNotEmpty()) ({ SettingsFailedMark(groupId) }) else null,
            titleLoading = g != null && rememberNameLoading(g.named),
        )
        if (g != null && g.archived) ArchivedBar(g)
        status.banner?.let { Banner(it, kind = BannerKind.Warning) }
        if (!embedded) ChannelBars(groupId)
        // a proposal's chat: the proposal on top (round 6, SPEC 19.42); the keyboard folds it
        val proposal = if (!embedded && g?.isProposal == true) world.proposalFor(groupId) else null
        var pLevel by androidx.compose.runtime.saveable.rememberSaveable(groupId) { mutableStateOf(proposalStart) }
        var pTab by remember(groupId) { mutableStateOf(sh.belfry.app.ui.cards.ProposalTab.CHAT) }
        val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        if (proposal != null) {
            sh.belfry.app.ui.cards.ProposalTop(proposal, if (imeOpen) sh.belfry.app.ui.cards.EventLevel.UP else pLevel) { pLevel = it }
            val tabs = sh.belfry.app.ui.cards.proposalTabs(world, proposal)
            if (pTab !in tabs) pTab = sh.belfry.app.ui.cards.ProposalTab.CHAT
            sh.belfry.app.ui.cards.ProposalTabsRow(tabs, pTab) { pTab = it }
            Hairline()
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (proposal != null && pTab == sh.belfry.app.ui.cards.ProposalTab.VERSIONS) {
                sh.belfry.app.ui.cards.ProposalVersionsPane(proposal) { pLevel = sh.belfry.app.ui.cards.EventLevel.FULL }
            } else if (proposal != null && pTab == sh.belfry.app.ui.cards.ProposalTab.VOTES) {
                sh.belfry.app.ui.cards.ProposalVotesPane(proposal)
            } else if (rows.isEmpty()) {
                if (chat.topic != null) {
                    EmptyState("Nothing here yet", text = if (chat.topic == Topics.ANNOUNCE) "No announcements in this channel." else "No messages in #${chat.topic}.")
                } else if (guestReading) {
                    EmptyState("No messages here yet.", icon = BelfryIcons.Hash)
                } else if (emptyContent != null) {
                    emptyContent()
                } else {
                    EmptyState(
                        "${g?.name ?: "This channel"} is new",
                        text = "No messages yet. Bring the people who belong here, then say hi.",
                        icon = BelfryIcons.Hash,
                        action = { BusyButton("Invite people", onClick = { nav.go(Routes.invite(groupId)) }, kind = ButtonKind.Ghost, icon = BelfryIcons.Users, small = true) },
                    )
                }
            } else {
                if (unavailable) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 10.dp).testTag("message-unavailable"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(BelfryIcons.Lock, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("This message isn't available", fontSize = 15.sp, color = c.text)
                    }
                }
                CompositionLocalProvider(LocalWorld provides feedWorld) {
                    LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + TYPING_ROOM)) {
                        items(reversed, key = { it.item.key }, contentType = { it.item::class }) { row ->
                            FeedRowView(groupId, row, chat, threads[row.item.key], highlight == row.item.key, feedNow, row.item.key in afterPending, row.item.key in pinnedIds, todos[row.item.key], openThread)
                        }
                    }
                }
            }
            // the header pulled down, over the feed: about and pins, then the rows (each pushes its screen)
            if (g != null) OnePulled(chat.lineOpen, onClose = { chat.closeLine() }) {
                ChannelMenu(groupId, chat, now, onShowMessage = { chat.focus = it })
            }
        }

        // canvas v110 messages/typing-channel: drawn over the feed's bottom, the feed doesn't move
        ChannelTyping(groupId)
        // a recording that runs, or one that was not posted (the web's RecordingBar)
        RecordingBar(groupId)
        SaveAccountBar(session)
        if (guestTopic) sh.belfry.app.ui.people.GuestTopicComposer(groupId) else if (guestReading) GuestBar(groupId)
        val readOnly = guestReading || (g != null && world.readOnlyClosed(g)) || world.isLocked(groupId)
        val editing = chat.editing
        // Posts (SPEC 19.60): with Messages and Threads off, the composer's plain send is replaced
        // by the kinds that are on (#proposals, #events, #bookings: web 2026-11-11)
        val page = world.webPage(g)
        val accepts = page?.accepts ?: sh.belfry.core.model.ChannelPosts.ALL
        val kinds = if (sh.belfry.core.model.ChannelPosts.plainSend(accepts)) emptyList()
            else listOf(sh.belfry.core.model.PostKind.EVENT, sh.belfry.core.model.PostKind.PROPOSAL).filter { it in accepts }
        val only = kinds.singleOrNull()?.key
        val isPub = page?.on == true && page.visibility == "public"
        if (!readOnly && kinds.isNotEmpty()) {
            Hairline()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        only == null -> if (isPub) "Public: anyone on the web sees what is posted." else "Events and proposals only."
                        only == "proposal" && isPub -> "Public proposals: anyone on the web sees them."
                        only == "proposal" -> "Members only: proposals to discuss and vote on."
                        isPub -> "Public: anyone on the web sees these events."
                        else -> "Members only: internal bookings."
                    },
                    fontSize = 13.sp, color = c.muted, modifier = Modifier.weight(1f),
                )
                for (k in kinds) {
                    Spacer(Modifier.width(8.dp))
                    BusyButton(
                        if (k == sh.belfry.core.model.PostKind.PROPOSAL) "New proposal" else "New event",
                        onClick = { nav.go(if (k == sh.belfry.core.model.PostKind.PROPOSAL) Routes.createProposal(groupId) else Routes.createEvent(groupId)) },
                        small = true, modifier = Modifier.testTag("post-${k.key}"),
                    )
                }
            }
        }
        if (!readOnly && kinds.isEmpty() && isPub) {
            Hairline()
            Text(
                // (#requests says no more than any public channel: web 1826857, Xavier 2026-11-23)
                "🌐 Public: anyone on the web sees this channel.",
                fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
        // Navigation v2: a channel not in my menu is a preview (nav2/channel-preview)
        if (!readOnly && !embedded) sh.belfry.app.ui.shell.PreviewBar(groupId)
        if (!readOnly && kinds.isEmpty() && (chat.topic != Topics.ANNOUNCE || admin)) {
            if (!isPub) Hairline()
            if (editing != null) EditingBar(onCancel = { chat.editing = null; composer.clear() })
            Composer(
                composer,
                placeholder = when {
                    editing != null -> "Edit your message"
                    composer.announce || chat.topic == Topics.ANNOUNCE -> "Announcement"
                    chat.topic != null -> "Message #${chat.topic}"
                    sh.belfry.core.model.PostKind.MESSAGE !in accepts -> "Start a thread"
                    else -> "Message ${g?.name ?: "this channel"}"
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
                        session.postMessage(groupId, text, files, topic, admin && (announce || chat.topic == Topics.ANNOUNCE), picked, preview = composer.previewTags(world.ctx.domain, session.community.blossom))
                    }
                },
                modifier = Modifier.navigationBarsPadding(),
                draftKey = ComposerDraft.channel(groupId),
                draftPaused = { chat.editing != null },
                linkPreview = if (editing == null) sh.belfry.core.engine.LinkPreviewService.modeForGroup(world, groupId) else null,
                photos = editing == null,
                onPlus = if (editing == null) ({ chat.plusOpen = true }) else null,
                groupId = groupId,
                onInvite = { pk -> inviteSaid(session.inviteMember(groupId, pk)) },
                typingPlace = sh.belfry.core.typing.TypingPlace.channel(groupId),
            )
        } else if (!guestReading) {
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
    if (chat.plusOpen) PlusSheet(groupId, admin, composer, onClose = { chat.plusOpen = false }, onTodo = { chat.newTodo = true }, onRecord = { chat.recorderOpen = true })
    if (chat.recorderOpen) RecorderSheet(groupId, onClose = { chat.recorderOpen = false })
    if (chat.newTodo) {
        val close = { chat.newTodo = false; chat.todoText = ""; chat.todoFrom = null }
        BelfrySheet(onDismiss = close, title = "New to-do") { NewTodoContent(groupId, onDone = close, initial = chat.todoText, from = chat.todoFrom) }
    }
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
                // an event's chat: a host's "invited" line is a system line, not a message (SPEC 19.65)
                val invited = world.calEventFor(groupId)?.let { sh.belfry.core.model.EventInvites.invitedBy(world, it, m.ev) }
                if (invited != null) { sh.belfry.app.ui.cards.InvitedLine(m.pubkey, invited, m.at); return@Column }
                // a printed card's first line, signed by the groups key: an activity line in our words (SPEC 19.75)
                world.cardLine(groupId, m)?.let { (who, line) -> sh.belfry.app.ui.cards.CardStartedLine(who, line, m.at); return@Column }
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
            is FeedItem.BelfryJoined -> BelfryJoinRow(item.j)
            is FeedItem.MailThread -> MailThreadRow(item.t) { openThread(item.t.id) }
            is FeedItem.Preview -> ActivityPreviewRow(groupId, item.p, now)
            is FeedItem.Answer -> sh.belfry.app.ui.cards.AnswerLine(item)
            is FeedItem.Activity -> {
                val key = item.key
                val open = chat.openThreads.containsKey(key)
                // channels/reply-row (web aa4a9a5, as on iOS): the message it answers on one grey line
                // with an elbow (opens the thread), then the reply as a normal message with its own menu
                ReplyQuote(item.root, open) { openThread(item.root.id) }
                MessageRow(groupId, item.last, true, chat)
                if (open) {
                    InlineThread(groupId, item.root, chat, focus = chat.openThreads[key] == true, onHide = { chat.openThreads.remove(key) }, onOpen = { openThread(item.root.id) })
                }
            }
        }
    }
}

/** ＋ : what can be added to the conversation, as a Discord-style list. */
@Composable
private fun PlusSheet(groupId: String, admin: Boolean, composer: ComposerState, onClose: () -> Unit, onTodo: () -> Unit, onRecord: () -> Unit) {
    val nav = LocalNav.current
    BelfrySheet(onDismiss = onClose) { PlusSheetContent(groupId, admin, composer, onClose, nav::go, onTodo, onRecord) }
}

/**
 * The composer's ＋ (nav2/plus): Photos, File, Audio recording (and Announcement for admins), then
 * "Create in #channel": Event, Meeting, To-do, Proposal. A message to someone is the Messages
 * place's ＋; a group, the ＋ on GROUPS in the menu.
 */
@Composable
fun PlusSheetContent(groupId: String, admin: Boolean, composer: ComposerState, onClose: () -> Unit, go: (String) -> Unit, onTodo: (() -> Unit)? = null, onRecord: (() -> Unit)? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    // the sheet stays open under the system picker, so the answer comes back here
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            try {
                val f = readChannelFile(context, uri)
                if (f == null) composer.error = sh.belfry.core.dm.DmFiles.tooLarge(false)
                else session.postMessage(groupId, composer.outgoing(composer.value.text).trim(), listOf(f), picked = composer.picked.toList()).also { composer.clear() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: sh.belfry.core.engine.ActionException) {
                composer.error = e.message
            } catch (e: Exception) {
                composer.error = "This file could not be read."
            } finally {
                reading = false
                onClose()
            }
        }
    }
    ActionGroup {
        ActionRow("Photos", onClick = { onClose(); composer.pickPhotos?.invoke() }, icon = BelfryIcons.Image, sub = "Pictures from this device", modifier = Modifier.testTag("plus-photos"))
        ActionRow(
            "File", onClick = { if (!reading) pickFile.launch(arrayOf("*/*")) }, icon = MoreIcons.File, sub = "A document or a video", modifier = Modifier.testTag("plus-file"),
            enabled = !reading, trailing = if (reading) ({ sh.belfry.app.ui.kit.Spinner(size = 16.dp) }) else null,
        )
        if (onRecord != null) {
            ActionRow("Audio recording", onClick = { onClose(); onRecord() }, icon = MoreIcons.Mic, sub = "Record here; kept on this device until it is posted", modifier = Modifier.testTag("plus-record"))
        }
        if (admin) {
            ActionRow("Announcement", onClick = { onClose(); composer.announce = true; composer.focus?.invoke() }, icon = BelfryIcons.Megaphone, sub = "Stands out; followers get it by e-mail")
        }
    }
    val name = world.group(groupId)?.name ?: "this channel"
    Text(
        "Create in " + (if (name.startsWith("#")) name else "#$name"),
        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp).testTag("plus-create-in"),
    )
    ActionGroup {
        ActionRow("Event", onClick = { onClose(); go(Routes.createEvent(groupId)) }, icon = BelfryIcons.Calendar, modifier = Modifier.testTag("plus-event"))
        // a meeting makes accounts and puts members in a chat: not for guests (as the web)
        if (!world.amGuest) {
            ActionRow("Meeting", onClick = { onClose(); go(Routes.createMeeting(groupId)) }, icon = BelfryIcons.Users, sub = "Members only: a topic, people, a time", modifier = Modifier.testTag("plus-meeting"))
        }
        if (onTodo != null) {
            ActionRow("To-do", onClick = { onClose(); onTodo() }, icon = MoreIcons.Todo, sub = "Something to get done, on the channel's list", modifier = Modifier.testTag("plus-todo"))
        }
        ActionRow("Proposal", onClick = { onClose(); go(Routes.createProposal(groupId)) }, icon = BelfryIcons.Proposal, sub = "A text to discuss and vote on", modifier = Modifier.testTag("plus-proposal"))
    }
}

/** A picked file for a channel: its bytes, name and type; null when it is over 25 MB (not read whole then). */
private suspend fun readChannelFile(context: android.content.Context, uri: android.net.Uri): sh.belfry.core.engine.NewAttachment? = withContext(Dispatchers.IO) {
    val max = sh.belfry.core.dm.DmFiles.MAX
    val r = context.contentResolver
    var name = "file"
    var size = -1L
    r.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) {
            cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> cur.getString(i)?.let { name = it } }
            cur.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 && !cur.isNull(it) }?.let { i -> size = cur.getLong(i) }
        }
    }
    if (size > max) return@withContext null
    val bytes = r.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return@withContext null
        }
        out.toByteArray()
    } ?: throw java.io.IOException("unreadable")
    sh.belfry.core.engine.NewAttachment(name, r.getType(uri) ?: "application/octet-stream", bytes)
}

/** A snippet without Markdown marks (bold, italics, links stay as their text). */
private fun unmarked(text: String): String = Markdown.text(Markdown.inline(text))

/** "New to-do": something to get done, on the channel's list. */
@Composable
fun NewTodoContent(groupId: String, onDone: () -> Unit, initial: String = "", from: String? = null) {
    val session = LocalSession.current
    var title by remember { mutableStateOf(initial) }
    // kept on this device until it is added (web 2026-11-11); a message turned into a to-do starts from its text
    val draft = rememberFormDraft(FormDrafts.todo(groupId)) { kept -> if (initial.isEmpty()) title = kept["title"] ?: title }
    draft.Keep("title" to title)
    // never cut: past 300 the extra is tinted and Add is off (canvas todos/new-todo-limit)
    TodoTextField(title, { title = it }, label = "What needs doing?", placeholder = "e.g. Book the room for Friday", modifier = Modifier.fillMaxWidth().testTag("todo-title"))
    Spacer(Modifier.height(12.dp))
    BusyButton(
        "Add to the list",
        onClick = {
            session.send(Actions.addTask(groupId, title.trim(), from))
            draft.clear()
            onDone()
        },
        modifier = Modifier.fillMaxWidth().testTag("todo-add-button"), enabled = TodoLimit.fits(title),
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

/** The answered message on one grey line: an elbow, its author's face and name, the start of its text. A tap opens the thread. */
@Composable
private fun ReplyQuote(root: Message, open: Boolean, onOpen: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val text = unmarked(world.activitySnippet(root)).replace(Regex("\\s+"), " ").trim().ifEmpty { if (root.media.isNotEmpty()) "a photo" else "" }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp).clip(RoundedCornerShape(8.dp))
            .background(if (open) c.surface else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = "Open the thread") { onOpen() }
            .padding(start = 22.dp, end = 4.dp, top = 2.dp).heightIn(min = 24.dp).testTag("reply-quote"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the elbow: a line down from above, turning right towards the quote
        val line = c.line
        Box(Modifier.width(22.dp).height(12.dp).padding(top = 4.dp).drawBehind {
            val w = 2.dp.toPx()
            drawLine(line, androidx.compose.ui.geometry.Offset(w / 2, size.height), androidx.compose.ui.geometry.Offset(w / 2, w / 2), strokeWidth = w)
            drawLine(line, androidx.compose.ui.geometry.Offset(w / 2, w / 2), androidx.compose.ui.geometry.Offset(size.width, w / 2), strokeWidth = w)
        })
        Spacer(Modifier.width(4.dp))
        Avatar(world.face(root.pubkey), size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(world.displayName(root.pubkey), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        Spacer(Modifier.width(6.dp))
        Text(text.take(120), fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
