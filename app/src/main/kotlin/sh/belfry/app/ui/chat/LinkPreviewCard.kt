package sh.belfry.app.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.belfry.app.preview.AndroidPictureEncoder
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.kit.rememberOpenLink
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalStatus
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.dm.Dm
import sh.belfry.core.model.Message
import sh.belfry.core.model.Special
import sh.belfry.core.preview.LinkPreview
import sh.belfry.core.preview.LinkPreviews
import sh.belfry.core.preview.PreviewMode
import sh.belfry.core.preview.PreviewPicture

// Link previews built by the sender (SPEC 19.87; docs/link-previews-v2.md; canvas channels/og-message,
// og-no-image, og-no-preview, og-several, og-dm, og-thread, og-dark, og-laptop, og-composer). A reader's
// box is made from the message's tags only: nothing here ever contacts the linked site, and a picture is
// loaded only from the belfry's Blossom. The sender's composer builds the preview (Session.linkPreviews.build).

/** The boards' width: 300 on a phone, 440 where there is room (og-laptop). */
@Composable
private fun previewMaxWidth(): Dp = if (LocalConfiguration.current.screenWidthDp >= 840) 440.dp else 300.dp

/** Under a channel message or a thread reply: its preview, if its sender attached one (not for cards and e-mails). */
@Composable
fun MessageLinkPreview(m: Message) {
    if (m.special != Special.None || m.text.isEmpty() || m.preview.isEmpty()) return
    val session = LocalSession.current
    val p = remember(m.text, m.preview) { session.linkPreviews.read(m.text, m.preview) } ?: return
    LinkPreviewBox(p)
}

/** Under a direct message's bubble (og-dm): the same box, from the rumor's tags. Never for an e-mail. */
@Composable
fun DmLinkPreview(d: Dm, modifier: Modifier = Modifier) {
    if (d.isMail || d.text.isEmpty() || d.preview.isEmpty()) return
    val session = LocalSession.current
    val p = remember(d.text, d.preview) { session.linkPreviews.read(d.text, d.preview) } ?: return
    LinkPreviewBox(p, modifier)
}

/** The box: the picture (when it came and could be had), site · host, title, two lines of description. A tap opens the link. */
@Composable
fun LinkPreviewBox(p: LinkPreview, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val open = rememberOpenLink()
    val shape = RoundedCornerShape(16.dp)
    val picture = p.picture?.let { rememberPreviewPicture(it) }
    // a picture that failed: the box without it
    val showPicture = picture != null && picture !is PictureState.Failed
    Column(
        modifier.padding(top = 6.dp, bottom = 2.dp).widthIn(max = previewMaxWidth()).fillMaxWidth().clip(shape).border(1.dp, c.line, shape).background(c.bg)
            .clickable(role = Role.Button, onClickLabel = "Open the link") { open(p.url) }.testTag("og-card"),
    ) {
        if (showPicture) Box(
            Modifier.fillMaxWidth().aspectRatio(p.ratio).background(c.surface2).semantics { contentDescription = "Picture from the page" },
        ) {
            (picture as? PictureState.Shown)?.let {
                Image(it.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("og-picture"))
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = if (showPicture) 12.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(p.siteLine, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.title.isNotEmpty()) Text(p.title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (p.description.isNotEmpty()) Text(p.description, fontSize = 14.sp, lineHeight = 19.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A preview's picture on screen. */
sealed interface PictureState {
    data object Loading : PictureState
    class Shown(val bitmap: ImageBitmap) : PictureState
    data object Failed : PictureState
}

/**
 * The picture of P: kept on this device (mine, or downloaded before) at once, else downloaded from
 * the belfry's Blossom and checked (Session.linkPreviews.picture). Pictures off (LocalNetworkImages)
 * or a preview render: only what is on the device.
 */
@Composable
private fun rememberPreviewPicture(p: PreviewPicture): PictureState {
    val session = LocalSession.current
    val network = (LocalNetworkImages.current || session.localOnly) && !LocalInspectionMode.current
    var state by remember(p) { mutableStateOf(session.linkPreviews.kept(p)?.let { decode(it) }?.let { PictureState.Shown(it) } ?: PictureState.Loading) }
    if (state is PictureState.Loading) LaunchedEffect(p, network) {
        if (!network) { state = PictureState.Failed; return@LaunchedEffect }
        val bytes = session.linkPreviews.picture(p)
        state = bytes?.let { withContext(Dispatchers.Default) { decode(it) } }?.let { PictureState.Shown(it) } ?: PictureState.Failed
    }
    return state
}

private fun decode(bytes: ByteArray): ImageBitmap? = try {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (e: Exception) { null }

/** What the composer's preview is doing for its link. */
sealed interface ComposerPreview {
    /** Being built (the page asked from this device): a quiet line with ✕. */
    data class Building(val link: String) : ComposerPreview
    data class Ready(val preview: LinkPreview) : ComposerPreview
    /** Nothing usable or it failed: no preview, the link stays a link. */
    data class NoPreview(val link: String) : ComposerPreview
}

/** How long the typing must stop before a link is looked up (a link typed letter by letter is asked once). */
internal const val PREVIEW_PAUSE_MS = 700L

/**
 * og-composer: above the field, the preview the message will carry, built now on this device, with
 * ✕ to send the link without it (`["preview","none"]`; after ✕ nothing more is fetched for that
 * link). While it is built: one quiet line. Offline: nothing (no preview).
 */
@Composable
fun ComposerLinkPreview(state: ComposerState, mode: PreviewMode, compact: Boolean = false) {
    val c = Belfry.colors
    val session = LocalSession.current
    val domain = LocalWorld.current.ctx.domain
    val online = LocalStatus.current.online || session.localOnly
    val files = session.community.blossom
    val text = state.value.text
    val link = remember(text, domain, files) { LinkPreviews.outsideLink(text, emptyList(), domain, files) }
    val dropped = link != null && link == state.previewDropped
    LaunchedEffect(link, dropped, online) {
        if (link == null || dropped || !online) return@LaunchedEffect
        val now = state.preview
        if (now is ComposerPreview.Ready && now.preview.url == link) return@LaunchedEffect
        if (now is ComposerPreview.NoPreview && now.link == link) return@LaunchedEffect
        // off the main thread, the pause too (real time, not the frame clock)
        val built = withContext(Dispatchers.Default) {
            delay(PREVIEW_PAUSE_MS)
            state.preview = ComposerPreview.Building(link)
            session.linkPreviews.build(link, mode, AndroidPictureEncoder)
        }
        state.preview = if (built != null) ComposerPreview.Ready(built) else ComposerPreview.NoPreview(link)
    }
    if (link == null || dropped) return
    val shape = RoundedCornerShape(14.dp)
    val frame = Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 12.dp, vertical = 4.dp).clip(shape).border(1.dp, c.line, shape).background(c.surface)
    val drop = { state.previewDropped = link; state.preview = null }
    when (val s = state.preview) {
        is ComposerPreview.Building -> if (s.link == link) Row(
            frame.padding(start = 12.dp, top = 2.dp, bottom = 2.dp).testTag("og-composer-building"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Getting a preview of the link…", fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconAction(BelfryIcons.Close, "Don't add a preview", drop, modifier = Modifier.testTag("og-composer-drop"))
        }
        is ComposerPreview.Ready -> if (s.preview.url == link) {
            val p = s.preview
            Row(frame.padding(start = 8.dp, top = 4.dp, bottom = 4.dp).testTag("og-composer"), verticalAlignment = Alignment.CenterVertically) {
                val pic = p.picture?.let { session.linkPreviews.kept(it) }?.let { remember(it) { decode(it) } }
                if (pic != null) {
                    Image(pic, contentDescription = "Picture from the page", contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(10.dp))
                } else Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title.ifEmpty { p.host }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.site.ifEmpty { p.host }, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconAction(BelfryIcons.Close, "Don't add a preview", drop, modifier = Modifier.testTag("og-composer-drop"))
            }
        }
        else -> Unit
    }
}
