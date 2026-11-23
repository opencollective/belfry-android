package sh.belfry.app.ui.chat

import android.content.Context
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.kit.rememberOpenLink
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.engine.Slots
import sh.belfry.core.model.Message
import sh.belfry.core.model.Special
import sh.belfry.core.preview.LinkPreview
import sh.belfry.core.preview.LinkPreviews
import sh.belfry.core.preview.PreviewLoader.Known
import sh.belfry.core.preview.PreviewPlace
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Previews of outside pages (SPEC 19.80; canvas channels/og-message, og-loading, og-no-image,
// og-no-preview, og-several, og-thread, og-dark, og-laptop, og-composer). The belfry fetches the
// page; nothing here ever contacts the page's own site, and a picture is loaded only from
// https://belfry.<domain>. Never used in a private conversation (PreviewPlace.PRIVATE).

/** The boards' width: 300 on a phone, 440 where there is room (og-laptop). */
@Composable
private fun previewMaxWidth(): Dp = if (LocalConfiguration.current.screenWidthDp >= 840) 440.dp else 300.dp

/**
 * Under a channel message or a thread reply: the preview of its first outside link, if it has one
 * (not for cards and e-mails, not for a message sent with ✕). [place] is where the message stands:
 * nothing at all is shown or asked for [PreviewPlace.PRIVATE].
 */
@Composable
fun MessageLinkPreview(m: Message, place: PreviewPlace = PreviewPlace.CHANNEL) {
    if (m.special != Special.None || m.text.isEmpty()) return
    val domain = LocalWorld.current.ctx.domain
    val files = LocalSession.current.community.blossom
    val link = remember(m.text, m.ev.tags, domain, files, place) { LinkPreviews.linkFor(place, m.text, m.ev.tags, domain, files) } ?: return
    LinkPreviewUnder(link)
}

/**
 * The preview of [url]: the card when the answer is kept, else a box of the same size while the
 * belfry is asked (only while this is on screen: the request is dropped when it scrolls away),
 * then the card or nothing (the link stays a link). Coming back online asks once more.
 */
@Composable
fun LinkPreviewUnder(url: String, modifier: Modifier = Modifier) {
    val session = LocalSession.current
    val online = session.status.collectAsState().value.online
    var state by remember(url) { mutableStateOf(session.linkPreviews.known(url)) }
    LaunchedEffect(url, online) {
        if (state is Known.None) state = session.linkPreviews.known(url)
        if (state is Known.Unknown) state = session.linkPreviews.get(url)?.let { Known.Found(it) } ?: Known.None
    }
    when (val s = state) {
        is Known.Found -> PreviewCard(s.preview, modifier)
        Known.Unknown -> PreviewLoading(modifier)
        Known.None -> Unit
    }
}

@Composable
private fun PreviewCard(p: LinkPreview, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val open = rememberOpenLink()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.padding(top = 6.dp, bottom = 2.dp).widthIn(max = previewMaxWidth()).fillMaxWidth().clip(shape).border(1.dp, c.line, shape).background(c.bg)
            .clickable(role = Role.Button, onClickLabel = "Open the link") { open(p.url) }.testTag("og-card"),
    ) {
        if (p.hasPicture) PreviewPicture(p, Modifier.fillMaxWidth().aspectRatio(p.ratio))
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = if (p.hasPicture) 12.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(p.siteLine, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.title.isNotEmpty()) Text(p.title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (p.description.isNotEmpty()) Text(p.description, fontSize = 14.sp, lineHeight = 19.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** og-loading: the card's box with nothing in it yet, so the feed does not jump when the answer comes. No animation. */
@Composable
private fun PreviewLoading(modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(16.dp)
    val bar = RoundedCornerShape(6.dp)
    Column(
        modifier.padding(top = 6.dp, bottom = 2.dp).widthIn(max = previewMaxWidth()).fillMaxWidth().clip(shape).border(1.dp, c.line, shape).background(c.bg)
            .semantics { contentDescription = "Loading the preview" }.testTag("og-loading"),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(LinkPreviews.ratio(null, null)).background(c.surface2))
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(bar).background(c.surface2))
            Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.9f).height(16.dp).clip(bar).background(c.surface2))
            Box(Modifier.fillMaxWidth(0.75f).height(12.dp).clip(bar).background(c.surface2))
        }
    }
}

private val DEMO_PICTURE = Brush.linearGradient(listOf(Color(0xFF7FA88B), Color(0xFF2E5E43)))

/** The picture's box (its room is taken before the picture is there); the demo's is a coloured block. */
@Composable
private fun PreviewPicture(p: LinkPreview, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Box(modifier.background(c.surface2).semantics { contentDescription = "Picture from the page" }) {
        if (p.demoPicture) Box(Modifier.fillMaxSize().background(DEMO_PICTURE))
        else p.image?.let { url ->
            rememberPreviewImage(url)?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** The belfry's copy of a preview's picture as a file of the cache dir; null until it is there (or when it can't be had). */
@Composable
private fun rememberPreviewImage(url: String): File? {
    val session = LocalSession.current
    val context = LocalContext.current
    val network = LocalNetworkImages.current && !LocalInspectionMode.current && !session.localOnly
    val origin = LinkPreviews.belfryOrigin(session.community.domain)
    val key = remember(url, session.slot) { Slots.key(sha256Hex(url), session.slot) }
    var file by remember(key) { mutableStateOf(if (network) LinkPreviewImages.known(context, key) else null) }
    if (network) LaunchedEffect(key) { file = LinkPreviewImages.load(context, key, url, origin) }
    return file
}

/**
 * og-composer: above the field, the preview the message will get, with ✕ to send the link without
 * it (`["preview","none"]`, [ComposerState.previewTags]). Shown only once the belfry answered with
 * something; asked a moment after the typing stops, so a link typed letter by letter is asked once.
 */
@Composable
fun ComposerLinkPreview(state: ComposerState, compact: Boolean = false) {
    val c = Belfry.colors
    val session = LocalSession.current
    val domain = LocalWorld.current.ctx.domain
    val files = session.community.blossom
    val text = state.value.text
    val link = remember(text, domain, files) { LinkPreviews.outsideLink(text, emptyList(), domain, files) }
    if (link == null || link == state.noPreviewFor) return
    var preview by remember(link) { mutableStateOf((session.linkPreviews.known(link) as? Known.Found)?.preview) }
    if (preview == null) LaunchedEffect(link) {
        delay(TYPING_PAUSE_MS)
        preview = session.linkPreviews.get(link)
    }
    val p = preview ?: return
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 12.dp, vertical = 4.dp).clip(shape).border(1.dp, c.line, shape).background(c.surface)
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp).testTag("og-composer"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.hasPicture) {
            PreviewPicture(p, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(10.dp))
        } else Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title.ifEmpty { p.host }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.site.ifEmpty { p.host }, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction(BelfryIcons.Close, "Don't show a preview", { state.noPreviewFor = link }, modifier = Modifier.testTag("og-composer-drop"))
    }
}

private const val TYPING_PAUSE_MS = 700L

/**
 * Pictures of previews, from the belfry's own copy (`https://belfry.<domain>/api/v1/link-preview/image/<hash>`,
 * no sign-in), kept in the app's cache dir for a week (the belfry's own time). Only that origin is
 * ever asked ([LinkPreviews.imageFrom], checked again here) and redirects are not followed; at most
 * two at a time, only for cards on screen; a failure is remembered in memory and not asked again
 * for a while.
 */
object LinkPreviewImages {
    private const val DIR = "link-previews"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val KEEP_SECONDS = 7 * 24 * 3600L
    private const val LATER_SECONDS = 600L
    private const val MISSING_SECONDS = 3600L
    private val http by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }
    private val gate = Semaphore(2)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val laterUntil = ConcurrentHashMap<String, Long>()

    private fun now() = System.currentTimeMillis() / 1000
    private fun dir(context: Context) = File(context.cacheDir, DIR)
    private fun pic(context: Context, key: String) = File(dir(context), "$key.img")
    private fun fresh(f: File) = f.exists() && now() - f.lastModified() / 1000 < KEEP_SECONDS

    /** A kept picture, without asking. */
    fun known(context: Context, key: String): File? = pic(context, key).takeIf { fresh(it) }

    suspend fun load(context: Context, key: String, url: String, origin: String): File? = withContext(Dispatchers.IO) {
        if (LinkPreviews.imageFrom(url, origin) == null) return@withContext null
        locks.getOrPut(key) { Mutex() }.withLock {
            val p = pic(context, key)
            if (fresh(p)) return@withLock p
            laterUntil[key]?.let { if (now() < it) return@withLock null }
            gate.withPermit { ask(context, key, url, p) }
        }
    }

    private fun ask(context: Context, key: String, url: String, p: File): File? = try {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            val body = r.body
            val type = r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
            if (r.code != 200 || body == null || type !in TYPES || body.contentLength() > MAX_BYTES) {
                laterUntil[key] = now() + if (r.code == 404) MISSING_SECONDS else LATER_SECONDS
                return null
            }
            val src = body.source()
            src.request(MAX_BYTES + 1)
            val bytes = if (src.buffer.size > MAX_BYTES) null else src.buffer.readByteArray()
            if (bytes == null || bytes.isEmpty()) { laterUntil[key] = now() + LATER_SECONDS; return null }
            dir(context).mkdirs()
            val tmp = File(dir(context), "$key.tmp")
            tmp.writeBytes(bytes)
            laterUntil.remove(key)
            if (tmp.renameTo(p)) p else { tmp.delete(); null }
        }
    } catch (e: Exception) {
        laterUntil[key] = now() + LATER_SECONDS
        null
    }

    private val TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
}
