package sh.belfry.app.ui.people

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.kit.avatarColor
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.Slots
import sh.belfry.core.model.MailFace
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The face of an outside e-mail correspondent (SPEC 19.59, canvas home/messages): their picture
 * from `<api base>/avatar/<hash>` when the belfry has one, else their initials on a colour picked
 * from the hash. [envelope] puts a small envelope on the corner so e-mail stays recognisable.
 * Nothing is asked in the demo, in tests (LocalNetworkImages) or in previews: initials.
 */
@Composable
fun MailFaceView(address: String, name: String?, size: Dp = 44.dp, envelope: Boolean = true, modifier: Modifier = Modifier) {
    val session = LocalSession.current
    val hash = remember(address) { MailFace.hash(address) }
    val initials = remember(address, name) { MailFace.initials(name, address) }
    val network = LocalNetworkImages.current && !LocalInspectionMode.current && !session.localOnly
    val context = LocalContext.current
    val key = Slots.key(hash, session.slot)
    var picture by remember(key) { mutableStateOf(if (network) MailFaceCache.known(context, key) else null) }
    if (network) LaunchedEffect(key) {
        picture = MailFaceCache.load(context, key, MailFace.url(session.community.apiBase, hash))
    }
    val fontSize = with(LocalDensity.current) { (size * (if (initials.length > 1) 0.36f else 0.42f)).toSp() }
    Box(modifier.size(size).semantics { contentDescription = if (envelope) "$initials, by e-mail" else initials }.testTag("mail-face")) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(avatarColor(hash)), contentAlignment = Alignment.Center) {
            Text(initials, color = Color(0xFF1A231D), fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = fontSize)
            picture?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        if (envelope) EnvelopeCorner(size)
    }
}

/** The small envelope on a face's corner: the page colour, a dark outline, a dark envelope (legible on e-ink). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.EnvelopeCorner(face: Dp) {
    val c = Belfry.colors
    val d = (face * 0.45f).coerceAtLeast(18.dp)
    Box(
        Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(d).clip(CircleShape).background(c.bg)
            .border(1.5.dp, c.text, CircleShape).testTag("mail-face-envelope"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BelfryIcons.Mail, contentDescription = null, tint = c.text, modifier = Modifier.size(d * 0.62f))
    }
}

/**
 * Pictures from the belfry's avatar service, kept in the app's cache dir by hash (named with
 * [Slots.key]: one belfry per account). The rules are `MailFace.keep`: a picture 30 days, a 404 7
 * days (or its max-age), a 429 or any other failure only in memory ("later": initials meanwhile).
 * /api is rate-limited per IP and shared with the app's other calls: at most 3 faces are asked at
 * a time, and only for rows on screen (the row's LaunchedEffect is cancelled when it scrolls away,
 * which drops its place in the queue). Only the belfry's own host is asked; redirects are not followed.
 */
object MailFaceCache {
    private const val DIR = "mail-faces"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val http by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }
    private val gate = Semaphore(3)
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** key → when to ask again (unix seconds), for answers that are not cached on disk. */
    private val laterUntil = ConcurrentHashMap<String, Long>()

    private fun now() = System.currentTimeMillis() / 1000
    private fun dir(context: Context) = File(context.cacheDir, DIR)
    private fun pic(context: Context, key: String) = File(dir(context), "$key.img")
    /** A 404: the file holds when it expires (unix seconds). */
    private fun miss(context: Context, key: String) = File(dir(context), "$key.miss")
    private fun freshPicture(f: File) = f.exists() && MailFace.fresh(true, f.lastModified() / 1000, now())
    private fun freshMiss(f: File) = f.exists() && (try { f.readText().trim().toLong() } catch (e: Exception) { 0L }) > now()

    /** A fresh cached picture, without asking (cheap file checks: fine on a redraw). */
    fun known(context: Context, key: String): File? = pic(context, key).takeIf { freshPicture(it) }

    /** The picture: from the cache when fresh, else asked once (one call at a time per face, 3 in all). Null = initials. */
    suspend fun load(context: Context, key: String, url: String): File? = withContext(Dispatchers.IO) {
        locks.getOrPut(key) { Mutex() }.withLock {
            val p = pic(context, key)
            if (freshPicture(p)) return@withLock p
            if (freshMiss(miss(context, key))) return@withLock null
            laterUntil[key]?.let { if (now() < it) return@withLock p.takeIf { f -> f.exists() } }
            gate.withPermit { ask(context, key, url, p) }
        }
    }

    private fun ask(context: Context, key: String, url: String, p: File): File? = try {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            val body = r.body
            val image = r.header("Content-Type").orEmpty().startsWith("image/") && body != null && body.contentLength() <= MAX_BYTES
            val keep = MailFace.keep(r.code, image, r.header("Cache-Control"), r.header("Retry-After"))
            when (keep.kind) {
                MailFace.Kind.PICTURE -> {
                    val src = body!!.source()
                    src.request(MAX_BYTES + 1)
                    val bytes = if (src.buffer.size > MAX_BYTES) null else src.buffer.readByteArray()
                    if (bytes == null || bytes.isEmpty()) { laterUntil[key] = now() + MailFace.LATER_SECONDS; null } else {
                        dir(context).mkdirs()
                        val tmp = File(dir(context), "$key.tmp")
                        tmp.writeBytes(bytes)
                        miss(context, key).delete()
                        laterUntil.remove(key)
                        if (tmp.renameTo(p)) p else { tmp.delete(); null }
                    }
                }
                MailFace.Kind.MISS -> {
                    dir(context).mkdirs()
                    miss(context, key).writeText((now() + keep.seconds).toString())
                    p.delete()
                    laterUntil.remove(key)
                    null
                }
                // later: an older picture still shows rather than initials
                MailFace.Kind.LATER -> { laterUntil[key] = now() + keep.seconds; p.takeIf { it.exists() } }
            }
        }
    } catch (e: Exception) {
        laterUntil[key] = now() + MailFace.LATER_SECONDS
        p.takeIf { it.exists() }
    }
}
