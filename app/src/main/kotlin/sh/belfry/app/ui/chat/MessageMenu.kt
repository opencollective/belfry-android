package sh.belfry.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.BusyState
import sh.belfry.app.ui.kit.copyText
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Message
import sh.belfry.core.model.World
import sh.belfry.core.model.canPin
import sh.belfry.core.model.isPinned
import sh.belfry.core.model.pinMessage
import sh.belfry.core.model.todoTitle
import sh.belfry.core.model.todosBySource
import sh.belfry.app.ui.kit.MoreIcons
import androidx.compose.runtime.LaunchedEffect
import sh.belfry.core.outbox.OutboxStatus

private val NSITE = Regex("nsite:(npub1[02-9ac-hj-np-z]{58})(?:/([a-z0-9-]{1,13}))?")

/** My most used emojis first (SPEC 8). */
private fun myTopEmojis(world: World, n: Int): List<String> {
    val usage = HashMap<String, Int>()
    for (e in world.all) if (e.kind == 7 && e.pubkey == world.me && e.content.isNotEmpty() && e.content != "+") usage[e.content] = (usage[e.content] ?: 0) + 1
    return Actions.topEmojis(usage, n)
}

/**
 * The sheets a chat opens over itself: the message menu (quick reactions, then Discord-style
 * rows) and the delete confirmation that shows the message. Put it once at the end of a screen
 * that shows [MessageRow]s with the same [chat].
 *
 * [onReply] unfolds the inline thread with its reply box focused (null in a thread screen).
 */
@Composable
fun MessageSheets(groupId: String, chat: ChatState, onReply: ((Message) -> Unit)?, onOpenThread: ((Message) -> Unit)?, onEdit: (Message) -> Unit) {
    val acting = chat.acting
    if (acting != null) {
        // A queued message gets a new id when it leaves the outbox: a menu opened on the queued
        // one would act on an id that is gone, so it closes instead.
        val menuWorld = LocalWorld.current
        val wasPending = remember(acting.id) { menuWorld.isPending(acting.id) }
        val gone = wasPending && !menuWorld.isPending(acting.id)
        LaunchedEffect(gone) { if (gone) chat.acting = null }
        BelfrySheet(onDismiss = { chat.acting = null }, title = if (chat.emojiOnly) "Add a reaction" else null) {
            MessageMenuContent(groupId, acting, chat, onReply, onOpenThread, onEdit)
        }
    }
    val deleting = chat.deleting
    if (deleting != null) {
        val session = LocalSession.current
        val world = LocalWorld.current
        ConfirmSheet(
            title = "Delete this message?",
            confirmLabel = "Delete",
            onConfirm = {
                session.deleteMessage(groupId, deleting.id)
                chat.deleting = null
            },
            onDismiss = { chat.deleting = null },
            message = "It disappears for everyone, with its reactions. This can't be undone.",
            preview = { DeletePreview(world.displayName(deleting.pubkey), world.deletePreview(deleting)) },
        )
    }
}

@Composable
fun DeletePreview(name: String, text: String) {
    val c = Belfry.colors
    Column {
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        if (text.isNotEmpty()) Text(text, fontSize = 15.sp, color = c.text)
    }
}

/** The menu's body (also rendered directly by screenshot tests). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageMenuContent(groupId: String, m: Message, chat: ChatState, onReply: ((Message) -> Unit)?, onOpenThread: ((Message) -> Unit)?, onEdit: (Message) -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val close = { chat.acting = null }
    val pending = world.pending(m.id)
    val isReply = m.ev.kind == 1111
    val thread = if (isReply) null else world.threadsOf(groupId)[m.id]
    val mine = world.reactionsOf(groupId, m.id).filter { it.mine != null }.map { it.emoji }.toSet()
    var more by remember { mutableStateOf(chat.emojiOnly) }
    var copied by remember { mutableStateOf("") }
    val reacting = rememberBusy()
    val top = remember(world) { myTopEmojis(world, 5) }

    if (pending == null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            for (e in top) EmojiButton(e, e in mine, reacting) {
                session.toggleReaction(groupId, m.id, m.pubkey, e)
                close()
            }
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(if (more) c.surface2 else c.surface)
                    .clickable(role = Role.Button, onClickLabel = "More emojis") { more = !more },
                contentAlignment = Alignment.Center,
            ) { Icon(BelfryIcons.SmilePlus, contentDescription = "More emojis", tint = c.muted, modifier = Modifier.size(22.dp)) }
        }
        if (more) {
            Spacer(Modifier.height(10.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in Actions.MORE_EMOJIS) EmojiButton(e, e in mine, reacting, small = true) {
                    session.toggleReaction(groupId, m.id, m.pubkey, e)
                    close()
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
    if (chat.emojiOnly) return

    if (pending != null) {
        ActionGroup {
            if (pending.status == OutboxStatus.FAILED) {
                BusyRow("Try sending again", onClick = { session.retry(pending.localId); close() }, icon = BelfryIcons.Retry)
            }
            BusyRow("Discard, don't send", onClick = { session.discard(pending.localId); close() }, icon = BelfryIcons.Trash, danger = true)
        }
        if (m.text.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ActionGroup {
                ActionRow(if (copied == "text") "Copied" else "Copy text", onClick = { copyText(context, m.text); copied = "text" }, icon = BelfryIcons.Copy)
            }
        }
        return
    }

    ActionGroup {
        if (world.canEdit(m)) {
            ActionRow("Edit message", onClick = { close(); onEdit(m) }, icon = BelfryIcons.Edit, modifier = Modifier.testTag("menu-edit"))
        }
        if (!isReply && onReply != null) {
            val n = thread?.count ?: 0
            ActionRow(if (n > 0) "Reply in the thread ($n)" else "Reply", onClick = { close(); onReply(m) }, icon = BelfryIcons.Reply)
        }
        if (!isReply && onOpenThread != null) {
            val n = thread?.count ?: 0
            ActionRow(if (n > 0) "Open the thread ($n)" else "Start a thread", onClick = { close(); onOpenThread(m) }, icon = BelfryIcons.Thread)
        }
        if (m.pubkey != world.me) {
            ActionRow("Message ${world.displayName(m.pubkey)}", onClick = { close(); nav.go(Routes.conversation("n:" + m.pubkey)) }, icon = BelfryIcons.At)
        }
    }
    Spacer(Modifier.height(12.dp))
    ActionGroup {
        if (m.text.isNotEmpty()) {
            ActionRow(if (copied == "text") "Copied" else "Copy text", onClick = { copyText(context, m.text); copied = "text" }, icon = BelfryIcons.Copy)
        }
        if (!isReply) {
            ActionRow(
                if (copied == "link") "Copied" else "Copy link",
                onClick = { copyText(context, session.community.channelLink(groupId) + "/m/" + m.id); copied = "link" },
                icon = BelfryIcons.Link,
            )
        }
        // #requests (web 2026-11-11): a member turns a request into a proposal in #proposals
        if (m.ev.kind == 9 && !world.amGuest && world.webPage(world.group(groupId))?.posts == "request") {
            ActionRow("Turn into a proposal", onClick = { close(); nav.go(Routes.createProposal("proposals", from = m.id, fromChannel = groupId)) }, icon = BelfryIcons.Proposal, modifier = Modifier.testTag("menu-propose"))
        }
        val title = world.todoTitle(m)
        if (title.isNotEmpty() && !world.todosBySource(groupId).containsKey(m.id)) {
            BusyRow("Turn into a todo", onClick = { session.send(Actions.addTask(groupId, title, m.id)); close() }, icon = MoreIcons.Todo, modifier = Modifier.testTag("menu-todo"))
        }
        if (m.ev.kind == 9 && world.canPin(groupId)) {
            val pinned = world.isPinned(groupId, m.id)
            BusyRow(
                if (pinned) "Unpin from the channel" else "Pin to the channel",
                onClick = { session.send(Actions.pinMessage(groupId, m.id, !pinned)); close() },
                icon = BelfryIcons.Pin, modifier = Modifier.testTag("menu-pin"),
            )
        }
        val site = if (world.isCommunityAdmin) NSITE.find(m.text) else null
        if (site != null) {
            BusyRow("Make this the website", onClick = { session.publishWebsite(site.value, m.id); close() }, icon = BelfryIcons.Globe)
        }
    }
    if (world.canDelete(m)) {
        Spacer(Modifier.height(12.dp))
        ActionGroup {
            ActionRow("Delete message", onClick = { close(); chat.deleting = m }, icon = BelfryIcons.Trash, danger = true, modifier = Modifier.testTag("menu-delete"))
        }
    }
}

@Composable
private fun EmojiButton(emoji: String, on: Boolean, busy: BusyState, small: Boolean = false, onPick: suspend () -> Unit) {
    val c = Belfry.colors
    Box(
        Modifier.size(if (small) 42.dp else 46.dp).clip(RoundedCornerShape(12.dp)).background(if (on) c.surface2 else c.surface)
            .clickable(enabled = !busy.busy, role = Role.Button, onClickLabel = "React with $emoji") { busy.run(block = onPick) },
        contentAlignment = Alignment.Center,
    ) { Text(emoji, fontSize = if (small) 20.sp else 22.sp) }
}
