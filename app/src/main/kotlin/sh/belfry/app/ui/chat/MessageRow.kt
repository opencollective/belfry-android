package sh.belfry.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.cards.SpecialCard
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.AvatarStack
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.Face
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.isSafeLink
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.Session
import sh.belfry.core.model.Media
import sh.belfry.core.model.Message
import sh.belfry.core.model.Reaction
import sh.belfry.core.model.Special
import sh.belfry.core.model.ThreadInfo
import sh.belfry.core.outbox.Attachment
import sh.belfry.core.outbox.Outbox
import sh.belfry.core.outbox.OutboxStatus

/**
 * One message of a feed or a thread: avatar, name and time when it starts a block ([first]),
 * the text (Markdown, mentions, #topics) or its card, photos and files, "edited", reactions,
 * the thread chip, and how its sending goes. A long press opens the message menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    groupId: String,
    m: Message,
    first: Boolean,
    chat: ChatState,
    modifier: Modifier = Modifier,
    announcement: Boolean = false,
    compact: Boolean = false,
    highlight: Boolean = false,
    thread: ThreadInfo? = null,
    threadOpen: Boolean = false,
    onToggleThread: (() -> Unit)? = null,
    onOpenThread: (() -> Unit)? = null,
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val pending = world.pending(m.id)
    val author = world.authorName(groupId, m)
    val avatar: Dp = if (compact) 24.dp else 36.dp
    val bg = when {
        highlight -> c.orange.copy(alpha = 0.16f)
        announcement -> c.surface
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClickLabel = "Message actions",
                onLongClick = { chat.menu(m) },
                onClick = {},
            )
            .padding(start = if (compact) 0.dp else 16.dp, end = if (compact) 0.dp else 16.dp, top = if (first) 8.dp else 1.dp, bottom = 1.dp)
            .testTag("msg-" + m.id),
    ) {
        Box(Modifier.width(avatar)) {
            if (first) {
                val face = if (m.hasProfile()) world.face(m.pubkey).copy(name = author) else Face(m.pubkey, author)
                Avatar(face, size = avatar, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = m.hasProfile()) { nav.go(Routes.profile(m.pubkey)) })
            }
        }
        Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
        Column(Modifier.weight(1f)) {
            if (first) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        author, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable(enabled = m.hasProfile(), role = Role.Button) { nav.go(Routes.profile(m.pubkey)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(ChatFormat.time(world, m.at), fontSize = 12.sp, color = c.faint, maxLines = 1)
                    if (m.edited != null) {
                        Spacer(Modifier.width(6.dp))
                        Text("edited", fontSize = 12.sp, color = c.faint, maxLines = 1)
                    }
                }
            }
            if (announcement) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)) {
                    Icon(BelfryIcons.Megaphone, contentDescription = null, tint = c.orange, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Announcement", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.orange)
                }
            }
            m.created?.let { Text("Created $it", fontSize = 14.sp, color = c.muted) }
            if (m.special == Special.None) {
                if (m.text.isNotEmpty()) MarkdownText(m.text, onTopic = { chat.topic = it })
            } else {
                SpecialCard(groupId, m)
            }
            if (!first && m.edited != null) Text("edited", fontSize = 12.sp, color = c.faint)
            val extra = world.extraTopics(m, chat.topic)
            if (extra.isNotEmpty()) {
                Text(extra.joinToString("  ") { "#$it" }, fontSize = 13.sp, color = c.orange, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            for (media in m.media) MediaView(media, session, chat)
            if (pending != null) for (a in pending.attachments) PendingAttachment(a, session, chat)
            val reactions = world.reactionsOf(groupId, m.id)
            if (reactions.isNotEmpty()) Reactions(groupId, m, reactions, chat)
            if (thread != null && thread.count > 0 && onToggleThread != null) {
                ThreadChips(thread, threadOpen, onToggleThread, onOpenThread)
            }
            if (pending != null) {
                if (pending.status == OutboxStatus.FAILED) {
                    val busy = rememberBusy()
                    Row(Modifier.padding(top = 2.dp)) {
                        Text("Not sent" + (pending.error?.let { ": $it" } ?: "") + " · ", fontSize = 13.sp, color = c.danger, modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (busy.busy) "Retrying…" else "Retry", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.danger,
                            modifier = Modifier.clickable(enabled = !busy.busy, role = Role.Button) { busy.run { session.retry(pending.localId) } },
                        )
                    }
                } else {
                    Text(Outbox.stateText(pending), fontSize = 13.sp, color = c.faint, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reactions(groupId: String, m: Message, reactions: List<Reaction>, chat: ChatState) {
    val c = Belfry.colors
    val session = LocalSession.current
    FlowRow(Modifier.padding(top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in reactions) {
            val busy = rememberBusy()
            val mine = r.mine != null
            val shape = RoundedCornerShape(99.dp)
            Row(
                Modifier
                    .clip(shape)
                    .background(if (mine) c.surface2 else c.surface)
                    .border(1.dp, if (mine) c.text else c.line, shape)
                    .clickable(enabled = !busy.busy, role = Role.Button, onClickLabel = "React with ${r.emoji}") {
                        busy.run { session.toggleReaction(groupId, m.id, m.pubkey, r.emoji) }
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.emoji, fontSize = 14.sp)
                Spacer(Modifier.width(5.dp))
                Text(r.who.size.toString(), fontSize = 13.sp, fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal, color = c.text)
            }
        }
        val shape = RoundedCornerShape(99.dp)
        Box(
            Modifier.clip(shape).border(1.dp, c.line, shape).clickable(role = Role.Button, onClickLabel = "Add a reaction") { chat.menu(m, emojiOnly = true) }.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(BelfryIcons.SmilePlus, contentDescription = "Add a reaction", tint = c.muted, modifier = Modifier.size(17.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThreadChips(thread: ThreadInfo, open: Boolean, onToggle: () -> Unit, onOpen: (() -> Unit)?) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val shape = RoundedCornerShape(8.dp)
    FlowRow(Modifier.padding(top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.clip(shape).border(1.dp, c.line, shape).clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarStack(thread.who.take(3).map { world.face(it) }, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (open) "Hide replies" else "${thread.count} ${if (thread.count == 1) "reply" else "replies"}",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(ChatFormat.short(world, thread.last, session.now()), fontSize = 12.sp, color = c.faint, maxLines = 1)
        }
        if (onOpen != null) {
            Row(
                Modifier.clip(shape).border(1.dp, c.line, shape).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(BelfryIcons.Thread, contentDescription = null, tint = c.muted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Open thread", fontSize = 13.sp, color = c.text, maxLines = 1)
            }
        }
    }
}

/** A photo (tap: full screen) or a file (tap: opens its address). */
@Composable
private fun MediaView(media: Media, session: Session, chat: ChatState) {
    val c = Belfry.colors
    if (!media.isImage) {
        val uri = LocalUriHandler.current
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.padding(top = 4.dp).widthIn(max = 320.dp).fillMaxWidth().clip(shape).border(1.dp, c.line, shape)
                .clickable(role = Role.Button) { if (isSafeLink(media.url)) try { uri.openUri(media.url) } catch (e: Exception) { } }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(BelfryIcons.Upload, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(media.name ?: "File", fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val local = rememberLocalImage(media.url) { if (media.url.startsWith(Session.LOCAL_BLOB)) session.localBlob(media.url) else null }
    val w = media.w ?: 0
    val h = media.h ?: 0
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.6f, 2.4f) else 4f / 3f
    PhotoBox(ratio, media.name, onClick = { chat.viewer = ViewerImage(media.url.takeIf { local == null }, local, media.name) }) {
        val bitmap = local
        if (bitmap != null) {
            Image(bitmap, contentDescription = media.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (LocalNetworkImages.current && media.url.startsWith("https://")) {
            AsyncImage(model = media.url, contentDescription = media.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A photo still in the outbox: shown from the device's copy. */
@Composable
private fun PendingAttachment(a: Attachment, session: Session, chat: ChatState) {
    val bitmap = rememberLocalImage(a.blobKey) { session.blob(a.blobKey) }
    val w = a.width ?: 0
    val h = a.height ?: 0
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.6f, 2.4f) else 4f / 3f
    PhotoBox(ratio, a.name, onClick = { bitmap?.let { chat.viewer = ViewerImage(null, it, a.name) } }) {
        bitmap?.let { Image(it, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** A picture whose bytes are on the device, decoded off the main thread; null until then (or when there is none). */
@Composable
private fun rememberLocalImage(key: String, bytes: () -> ByteArray?): ImageBitmap? {
    var image by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) { image = withContext(Dispatchers.IO) { bytes()?.let(Photos::decodeBytes) } }
    return image
}

@Composable
private fun PhotoBox(ratio: Float, name: String?, onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = Belfry.colors
    Box(
        Modifier.padding(top = 4.dp).widthIn(max = 280.dp).fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(c.surface2)
            .clickable(role = Role.Image, onClickLabel = "Open the photo", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BelfryIcons.Image, contentDescription = name ?: "Photo", tint = c.faint, modifier = Modifier.size(28.dp))
        content()
    }
}
