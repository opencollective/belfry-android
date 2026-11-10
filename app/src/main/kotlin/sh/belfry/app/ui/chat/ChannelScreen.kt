package sh.belfry.app.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.ui.cards.ChannelBars
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Banner
import sh.belfry.app.ui.kit.BannerKind
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Chip
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

/**
 * A channel: its top bar (☰ · name and "N active members this month" · search · follow), the
 * feed anchored at the bottom, and the composer. [focusMessage] scrolls to a message and
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
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val status = LocalStatus.current
    val dms = LocalDms.current
    val g = world.group(groupId)
    val admin = world.canAdmin(g)
    val now = session.now()

    LaunchedEffect(groupId, g != null) { if (g != null) session.markSeen(groupId) }

    val openRoots = chat.openThreads.keys.filter { !it.startsWith("act:") }.toSet()
    val rows = remember(world, groupId, chat.topic, openRoots) { world.feedRows(groupId, chat.topic, openRoots) }
    val threads = world.threadsOf(groupId)
    val reversed = rows.asReversed()
    val list = rememberLazyListState()
    var highlight by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(focusMessage, rows.size) {
        if (focusMessage != null) {
            val i = reversed.indexOfFirst { it.item.key == focusMessage }
            if (i >= 0) {
                list.scrollToItem(i)
                highlight = focusMessage
                onFocusDone()
                delay(1800)
                highlight = null
            }
        }
    }
    // What I just wrote shows at once: go to the end.
    val lastKey = rows.lastOrNull()?.item?.key
    LaunchedEffect(lastKey) {
        val last = rows.lastOrNull()?.item
        if (last is FeedItem.Msg && last.m.pubkey == world.me && world.isPending(last.m.id) && focusMessage == null) list.scrollToItem(0)
    }

    Column(modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar(
            title = g?.name ?: groupId,
            subtitle = if (g != null) world.activeLine(groupId, now) else null,
            onTitleClick = { nav.go(Routes.channelInfo(groupId)) },
            navigation = if (onMenu != null) {
                { IconAction(BelfryIcons.Menu, "Channels", onMenu, badge = dms.unreadTotal) }
            } else null,
            actions = {
                IconAction(BelfryIcons.Search, "Search", { nav.go(Routes.search(groupId)) })
                IconAction(BelfryIcons.Bell, "Follow this channel", { nav.go(Routes.follow(groupId)) })
            },
        )
        Hairline()
        status.banner?.let { Banner(it, kind = BannerKind.Warning) }
        ChannelBars(groupId)
        TopicsBar(groupId, chat)

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
                LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(reversed, key = { it.item.key }) { row ->
                        FeedRowView(groupId, row, chat, threads[row.item.key], highlight == row.item.key, now)
                    }
                }
            }
        }

        SaveAccountBar(session)
        val readOnly = g != null && g.isPod && g.closed && world.me !in g.members
        val editing = chat.editing
        if (!readOnly && (chat.topic != Topics.ANNOUNCE || admin)) {
            Hairline()
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
                        chat.editing = null
                    } else {
                        val topic = chat.topic?.takeIf { it != Topics.ANNOUNCE }
                        session.postMessage(groupId, text, files, topic, admin && (announce || chat.topic == Topics.ANNOUNCE), picked)
                    }
                },
                modifier = Modifier.navigationBarsPadding(),
                photos = editing == null,
                onPlus = if (editing == null) ({ chat.plusOpen = true }) else null,
            )
        } else {
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    val startEdit: (Message) -> Unit = { m ->
        val (text, picks) = Mentions.unsubstitute(m.text) { world.realName(it) }
        chat.editing = m
        composer.set(text, picks)
        composer.focus?.invoke()
    }
    MessageSheets(
        groupId, chat,
        onReply = { m -> chat.toggleThread(m.id, focus = true) },
        onOpenThread = { m -> nav.go(Routes.thread(groupId, m.id)) },
        onEdit = startEdit,
    )
    if (chat.plusOpen) PlusSheet(groupId, admin, composer, onClose = { chat.plusOpen = false })
    chat.viewer?.let { ImageViewer(it) { chat.viewer = null } }
}

/** Pinned topics as chips: tap one to see only its messages (and write in it). */
@Composable
private fun TopicsBar(groupId: String, chat: ChatState) {
    val world = LocalWorld.current
    val topics = world.topicsBar(groupId, chat.topic)
    val announcements = world.hasAnnouncements(groupId)
    if (topics.isEmpty() && !announcements && chat.topic == null) return
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Chip("All", selected = chat.topic == null, onClick = { chat.topic = null }) }
        if (announcements || chat.topic == Topics.ANNOUNCE) {
            item { Chip("Announcements", selected = chat.topic == Topics.ANNOUNCE, icon = BelfryIcons.Megaphone, onClick = { chat.topic = Topics.ANNOUNCE }) }
        }
        items(topics) { t -> Chip("#$t", selected = chat.topic == t, onClick = { chat.topic = if (chat.topic == t) null else t }) }
    }
    Hairline()
}

@Composable
private fun FeedRowView(groupId: String, row: FeedRow, chat: ChatState, thread: sh.belfry.core.model.ThreadInfo?, highlight: Boolean, now: Long) {
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
                    groupId, m, row.first, chat,
                    announcement = row.announcement, highlight = highlight, thread = thread, threadOpen = open,
                    onToggleThread = { chat.toggleThread(m.id) },
                    onOpenThread = { nav.go(Routes.thread(groupId, m.id)) },
                )
                if (open) {
                    InlineThread(groupId, m, chat, focus = chat.openThreads[m.id] == true, onHide = { chat.openThreads.remove(m.id) }, onOpen = { nav.go(Routes.thread(groupId, m.id)) })
                }
            }
            is FeedItem.Join -> {
                val j = item.j
                val what = if (j.agent) "an agent" else "a guest"
                val by = j.by?.let { ", invited by ${world.displayName(it)}" } ?: ""
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
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { chat.toggleThread(key) }.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(BelfryIcons.Thread, contentDescription = null, tint = c.faint, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${world.displayName(item.last.pubkey)} replied to ${world.displayName(item.root.pubkey)}: “${Markdown.plain(world.activitySnippet(item.root))}”",
                            fontSize = 13.sp, color = c.muted, maxLines = 2, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(ChatFormat.time(world, item.at), fontSize = 12.sp, color = c.faint)
                    }
                    if (!open) Text(Markdown.plain(world.activityReply(item.last)), fontSize = 15.sp, color = c.text, maxLines = 3, modifier = Modifier.padding(start = 23.dp, top = 2.dp))
                }
                if (open) {
                    InlineThread(groupId, item.root, chat, focus = chat.openThreads[key] == true, onHide = { chat.openThreads.remove(key) }, onOpen = { nav.go(Routes.thread(groupId, item.root.id)) })
                }
            }
        }
    }
}

/** ＋ : what can be added to the conversation, as a Discord-style list. */
@Composable
private fun PlusSheet(groupId: String, admin: Boolean, composer: ComposerState, onClose: () -> Unit) {
    val nav = LocalNav.current
    BelfrySheet(onDismiss = onClose) { PlusSheetContent(groupId, admin, composer, onClose, nav::go) }
}

@Composable
fun PlusSheetContent(groupId: String, admin: Boolean, composer: ComposerState, onClose: () -> Unit, go: (String) -> Unit) {
    ActionGroup {
        ActionRow("Photos", onClick = { onClose(); composer.pickPhotos?.invoke() }, icon = BelfryIcons.Image)
    }
    Spacer(Modifier.height(12.dp))
    ActionGroup {
        if (admin) {
            ActionRow("Announcement", onClick = { onClose(); composer.announce = true; composer.focus?.invoke() }, icon = BelfryIcons.Megaphone, sub = "Stands out; followers get it by e-mail")
        }
        ActionRow("Event", onClick = { onClose(); go(Routes.createEvent(groupId)) }, icon = BelfryIcons.Calendar)
        ActionRow("Proposal", onClick = { onClose(); go(Routes.createProposal(groupId)) }, icon = BelfryIcons.Proposal, sub = "A text to discuss and vote on")
        ActionRow("Pod", onClick = { onClose(); go(Routes.createPod(groupId)) }, icon = BelfryIcons.Pod, sub = "A working group with its own channel")
        ActionRow("Drop box link", onClick = { onClose(); go(Routes.drop(groupId)) }, icon = BelfryIcons.Upload, sub = "Let anyone send photos and files here")
    }
}
