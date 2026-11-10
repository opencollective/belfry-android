package sh.belfry.app.ui.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.chat.ImageViewer
import sh.belfry.app.ui.chat.Photos
import sh.belfry.app.ui.chat.ViewerImage
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.signin.openInBrowser
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.Session
import sh.belfry.core.model.Actions
import sh.belfry.core.model.DropLink
import sh.belfry.core.model.Media

/** The Files page of a channel on its own (from the ＋ menu or a link). */
@Composable
fun FilesScreen(id: String) {
    val world = LocalWorld.current
    InfoPage("Files", subtitle = channelLabel(world.group(id), id)) { FilesTab(id) }
}

/** The drop box and every photo and file posted in the channel, newest first. */
@Composable
fun FilesTab(groupId: String) {
    val world = LocalWorld.current
    val context = LocalContext.current
    val media = remember(world, groupId) { world.files(groupId) }
    val photos = media.filter { it.isImage }
    val files = media.filter { !it.isImage }
    var viewer by remember { mutableStateOf<ViewerImage?>(null) }

    DropBoxCard(groupId)
    Spacer(Modifier.height(16.dp))
    if (media.isEmpty()) {
        EmptyState("No photos or files yet", text = "Photos and files posted in this channel show up here.", icon = BelfryIcons.Image)
    } else {
        Hint(
            "${photos.size} " + (if (photos.size == 1) "photo" else "photos") +
                (if (files.isNotEmpty()) " · ${files.size} " + (if (files.size == 1) "file" else "files") else ""),
        )
        Spacer(Modifier.height(8.dp))
        photos.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { m -> Thumb(m, Modifier.weight(1f)) { viewer = it } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (files.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ActionGroup {
                files.forEach { m ->
                    ActionRow(
                        m.name ?: "File", { if (isSafeLink(m.url)) openInBrowser(context, m.url) },
                        icon = BelfryIcons.Link, sub = m.mime, chevron = true, enabled = isSafeLink(m.url),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    viewer?.let { ImageViewer(it) { viewer = null } }
}

/** A square thumbnail: the picture when it can be loaded, else a neutral box (offline, tests). */
@Composable
private fun Thumb(m: Media, modifier: Modifier, onOpen: (ViewerImage) -> Unit) {
    val c = Belfry.colors
    val session = LocalSession.current
    val network = LocalNetworkImages.current
    val local = rememberLocal(session, m.url)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(c.surface2)
            .clickable { onOpen(ViewerImage(m.url, local, m.name)) },
        contentAlignment = Alignment.Center,
    ) {
        if (local != null) {
            Image(local, contentDescription = m.name ?: "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (network && m.url.startsWith("https://")) {
            AsyncImage(model = m.url, contentDescription = m.name ?: "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(BelfryIcons.Image, contentDescription = m.name ?: "Photo", tint = c.faint, modifier = Modifier.size(28.dp))
        }
    }
}

/** A picture the demo keeps on the device (`https://blob.local/…`), decoded off the main thread. */
@Composable
private fun rememberLocal(session: Session, url: String): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.startsWith(Session.LOCAL_BLOB)) {
            bitmap = withContext(Dispatchers.Default) { session.localBlob(url)?.let { Photos.decodeBytes(it) } }
        }
    }
    return bitmap
}

/** The channel's drop box: live links (tap to share, end your own) and "New link". */
@Composable
fun DropBoxCard(groupId: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val links = remember(world, groupId) { world.dropLinks(groupId, session.now()) }
    var sharing by remember { mutableStateOf<String?>(null) }
    var ending by remember { mutableStateOf<DropLink?>(null) }

    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(BelfryIcons.Upload, null, tint = Belfry.colors.muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Drop box", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Hint("A link or QR code anyone can use to upload here")
            }
            Spacer(Modifier.width(8.dp))
            BusyButton("New link", { nav.go(Routes.drop(groupId)) }, small = true, modifier = Modifier.testTag("drop-new"))
        }
        if (links.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ActionGroup {
                links.forEach { l ->
                    ActionRow(
                        "Until " + untilLabel(l.expires, world.ctx.zone), { sharing = l.token }, icon = BelfryIcons.Link, chevron = true,
                        sub = (if (l.login) "Signed-in members only" else "Anyone with the link") + (if (world.isPending(l.ev.id)) " · sending…" else ""),
                    )
                }
            }
        }
    }

    val shared = links.firstOrNull { it.token == sharing }
    if (shared != null) {
        BelfrySheet({ sharing = null }, title = "Drop link") {
            DropShare(shared, onEnd = if (shared.author == world.me) ({ ending = shared; sharing = null }) else null)
        }
    }
    ending?.let { l ->
        ConfirmSheet(
            "End this drop link?", "End link",
            onConfirm = { session.send(Actions.endDropLink(l)); ending = null },
            onDismiss = { ending = null },
            message = "It stops working at once.",
        )
    }
}

/** A drop link to hand out: QR, address, copy, share; its author can end it. */
@Composable
fun DropShare(l: DropLink, onEnd: (() -> Unit)?) {
    val world = LocalWorld.current
    LinkBox(world.dropUrl(l.token), "Drop box")
    Hint(
        "Works until ${untilLabel(l.expires, world.ctx.zone)} · " + (if (l.login) "signed-in members only" else "anyone with the link") + ".",
        Modifier.padding(top = 12.dp),
    )
    if (onEnd != null) {
        Spacer(Modifier.height(12.dp))
        ActionGroup { ActionRow("End link", onEnd, icon = BelfryIcons.Trash, danger = true, sub = "It stops working at once.") }
    }
}
