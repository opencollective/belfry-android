package sh.belfry.app.ui.chat

import sh.belfry.app.ui.kit.MarkdownWithBox
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
import androidx.compose.runtime.collectAsState
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
import sh.belfry.app.ui.cards.EventCards
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
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Task
import sh.belfry.core.model.ThreadInfo
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.core.outbox.Attachment
import sh.belfry.core.outbox.OutboxStatus
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

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
    /** Pinned as the channel's notice: shows the pin mark. */
    pinned: Boolean = false,
    /** The to-do made from this message: shows the mark that ticks it off. */
    todo: Task? = null,
    /** A small tag after the name (an e-mail thread's "team only"), with its icon. */
    tag: Pair<androidx.compose.ui.graphics.vector.ImageVector?, String>? = null,
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val pending = world.pending(m.id)
    val author = world.authorName(groupId, m)
    val avatar: Dp = if (compact) 24.dp else 36.dp
    val bg = when {
        // the flash of a message a link opened: yellow (canvas channels/message-link)
        highlight -> if (c.isDark) Color(0xFF4A3B10) else Color(0xFFFFF1C9)
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
                onLongClick = { if (session.reading) session.askToJoin() else chat.menu(m) },
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
                    if (tag != null) {
                        Spacer(Modifier.width(6.dp))
                        NameTag(tag.first, tag.second)
                    }
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
            if (pinned) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)) {
                    Icon(BelfryIcons.Pin, contentDescription = null, tint = c.orange, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pinned", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.orange)
                }
            }
            m.created?.let { Text("Created $it", fontSize = 14.sp, color = c.muted) }
            if (m.special == Special.None) {
                if (m.text.isNotEmpty()) MarkdownWithBox(m.text, world.ctx.domain, onTopic = { chat.topic = it }, eventCards = m.eventRefs.isNotEmpty())
            } else {
                SpecialCard(groupId, m)
            }
            MessageLinkPreview(m) // the first outside link's preview (SPEC 19.80); channels and threads only
            EventCards(m)
            MessageButtons(groupId, m)
            if (!first && m.edited != null) Text("edited", fontSize = 12.sp, color = c.faint)
            val extra = world.extraTopics(m, chat.topic)
            if (extra.isNotEmpty()) {
                Text(extra.joinToString("  ") { "#$it" }, fontSize = 13.sp, color = c.orange, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MessageMedia(m, chat)
            if (pending != null) for (a in pending.attachments) PendingAttachment(a, session, chat)
            val reactions = world.reactionsOf(groupId, m.id)
            // no to-do mark here any more (web 2026-11-12): the ⏳ / ☑️ reactions say it
            if (reactions.isNotEmpty()) Reactions(groupId, m, reactions, chat)
            if (thread != null && thread.count > 0 && onToggleThread != null) {
                ThreadLine(groupId, m.id, thread, threadOpen, onToggleThread)
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
                    val line = rememberSendLine(pending.localId, pending.tries)
                    val busy = rememberBusy()
                    Row(Modifier.padding(top = 2.dp)) {
                        Text(line.text + if (line.retry) " · " else "", fontSize = 13.sp, color = c.faint, modifier = Modifier.weight(1f, fill = false).testTag("send-line"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (line.retry) Text(
                            if (busy.busy) "Retrying…" else "Retry", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                            modifier = Modifier.clickable(enabled = !busy.busy, role = Role.Button) { busy.run { session.sendNow() } }.testTag("send-retry"),
                        )
                    }
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
                        busy.run { session.toggleReaction(groupId, m.id, m.pubkey, r.emoji, m.ev.kind) }
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.emoji, fontSize = 14.sp)
                Spacer(Modifier.width(5.dp))
                Text(r.who.size.toString(), fontSize = 13.sp, fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal, color = c.text)
            }
        }
        if (reactions.isNotEmpty()) {
            val shape = RoundedCornerShape(99.dp)
            Box(
                Modifier.clip(shape).border(1.dp, c.line, shape).clickable(role = Role.Button, onClickLabel = "Add a reaction") { if (session.reading) session.askToJoin() else chat.menu(m, emojiOnly = true) }.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(BelfryIcons.SmilePlus, contentDescription = "Add a reaction", tint = c.muted, modifier = Modifier.size(17.dp))
            }
        }
    }
}

/**
 * Under a message with replies, one line (web proposal A): the repliers' faces, how many, when,
 * and the start of the last reply. Tap unfolds the replies (their "Open thread" goes further).
 */
@Composable
private fun ThreadLine(groupId: String, rootId: String, thread: ThreadInfo, open: Boolean, onToggle: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = sh.belfry.app.ui.nav.LocalSession.current
    val last = if (open) null else world.threadReplies(groupId, rootId).lastOrNull()
    // read state (SPEC 19.27): replies I have not read, in a thread I am in; unfolded = read
    val read by session.readState.collectAsState()
    val fresh = if (open) 0 else sh.belfry.core.read.Unread.thread(world, read, groupId, rootId, session.now())
    val started = sh.belfry.app.ui.kit.rememberStarted()
    androidx.compose.runtime.LaunchedEffect(open, thread.last, started) { if (open && started) session.markThreadRead(groupId, rootId) }
    val said = last?.let { replyPreview(it.text).ifEmpty { if (it.media.isNotEmpty()) "a photo" else "" } }.orEmpty()
    Row(
        Modifier.padding(top = 4.dp, bottom = 2.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onToggle).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarStack(thread.who.take(3).map { world.face(it) }, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            if (open) "Hide replies" else "${thread.count} ${if (thread.count == 1) "reply" else "replies"}" + if (fresh > 0) " · $fresh new" else "",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (c.isDark) Color(0xFF60A5FA) else Color(0xFF2563EB), maxLines = 1,
        )
        if (!open) {
            Spacer(Modifier.width(6.dp))
            Text(ChatFormat.time(world, thread.last), fontSize = 12.sp, color = c.faint, maxLines = 1)
            if (last != null && said.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(world.displayName(last.pubkey)) }
                        append(": ")
                        append(said)
                    },
                    fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.width(2.dp))
            Icon(BelfryIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(14.dp))
        }
    }
}

/** A reply's text for a one-line preview: without the "@someone" it starts with, on one line. */
internal fun replyPreview(text: String): String =
    text.replace(Regex("^(\\s*(nostr:npub1[02-9ac-hj-np-z]+|@\\S+)[,:]?\\s+)+"), "")
        .replace(Regex("nostr:\\S+"), "@…").replace(Regex("\\s+"), " ").trim().take(120)

/** A photo (tap: full screen) or a file (tap: opens its address). */
@Composable
private fun MediaView(media: Media, session: Session, chat: ChatState, pictures: List<String> = emptyList()) {
    val c = Belfry.colors
    // a recording plays here (web 2026-11-12); one still on this device only (not sent yet) is a file row
    if (media.mime.startsWith("audio/") && media.url.startsWith("https://") && !media.url.startsWith(Session.LOCAL_BLOB)) {
        AudioRow(media)
        return
    }
    // a picture that can't be loaded falls back to its file row (canvas v179)
    var failed by remember(media.url) { mutableStateOf(false) }
    if (!media.isImage || failed) {
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
            Text(media.name ?: if (failed) "Picture" else "File", fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (failed) {
                Spacer(Modifier.width(8.dp))
                Text(sh.belfry.app.ui.kit.PICTURE_FAILED, fontSize = 12.sp, color = c.muted, maxLines = 1)
            }
        }
        return
    }
    val local = rememberLocalImage(media.url) { if (media.url.startsWith(Session.LOCAL_BLOB)) session.localBlob(media.url) else null }
    val w = media.w ?: 0
    val h = media.h ?: 0
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.6f, 2.4f) else 4f / 3f
    PhotoBox(ratio, media.name, onClick = { chat.viewer = ViewerImage(media.url.takeIf { local == null }, local, media.name, pictures, pictures.indexOf(media.url).coerceAtLeast(0)) }) {
        val bitmap = local
        if (bitmap != null) {
            Image(bitmap, contentDescription = media.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (LocalNetworkImages.current && media.url.startsWith("https://")) {
            AsyncImage(model = media.url, contentDescription = media.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { failed = true })
        }
    }
}

/**
 * A message's pictures and files after its text (canvas v179 n-email-pictures): several pictures as the
 * DM grid, then the other files; one picture large; files as rows. Also under an e-mail in its thread.
 */
@Composable
fun MessageMedia(m: Message, chat: ChatState) = MediaList(m.media, chat)

/** [MessageMedia] for media that isn't a message's (a to-do comment's picture links). */
@Composable
fun MediaList(media: List<Media>, chat: ChatState) {
    if (media.isEmpty()) return
    val session = LocalSession.current
    val pictures = if (media.size > 1) media.filter { it.isImage && it.url.startsWith("https://") && !it.url.startsWith(Session.LOCAL_BLOB) }.map { it.url } else emptyList()
    if (pictures.size >= 2) {
        MediaGrid(media.filter { it.isImage && it.url in pictures }, chat, pictures)
        for (x in media) if (!(x.isImage && x.url in pictures)) MediaView(x, session, chat, pictures)
    } else {
        for (x in media) MediaView(x, session, chat, pictures)
    }
}

/**
 * Two or more pictures of a channel message (canvas v179, the DM grid): two side by side, three as two +
 * one wide, four or more as 2 × 2 with "+N" on the fourth; a tap opens the viewer on that picture.
 */
@Composable
private fun MediaGrid(images: List<Media>, chat: ChatState, urls: List<String>) {
    val c = Belfry.colors
    val shown = images.take(4)
    val more = images.size - 4
    val network = LocalNetworkImages.current
    Column(Modifier.padding(top = 4.dp).widthIn(max = 280.dp).fillMaxWidth().testTag("media-grid"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((r, row) in shown.chunked(2).withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for ((i, media) in row.withIndex()) {
                    val wide = row.size == 1
                    val cell = (if (wide) Modifier.fillMaxWidth().aspectRatio(2f) else Modifier.weight(1f).aspectRatio(1f))
                        .clip(RoundedCornerShape(10.dp)).background(c.surface2)
                        .clickable(role = Role.Image, onClickLabel = "Open the photo") { chat.viewer = ViewerImage(media.url, null, media.name, urls, urls.indexOf(media.url).coerceAtLeast(0)) }
                    Box(cell, contentAlignment = Alignment.Center) {
                        Icon(BelfryIcons.Image, contentDescription = media.name ?: "Photo", tint = c.faint, modifier = Modifier.size(24.dp))
                        if (network) AsyncImage(model = media.url, contentDescription = media.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (r * 2 + i == shown.size - 1 && more > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Text("+$more", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
            }
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

/** The small tag after a name in an e-mail thread ("guest, by e-mail", "sent as hello@", "team only"). */
@Composable
fun NameTag(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String, color: Color = Belfry.colors.muted) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, fontSize = 12.sp, color = color, maxLines = 1)
    }
}
