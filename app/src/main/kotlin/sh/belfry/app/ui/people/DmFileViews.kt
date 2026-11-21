package sh.belfry.app.ui.people

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.chat.ImageViewer
import sh.belfry.app.ui.chat.ViewerImage
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmFiles
import java.io.File

/** A file's bytes, decrypted (null while loading; [Failed] when it can't be opened). */
private sealed interface Got { object Loading : Got; object Failed : Got; class Bytes(val b: ByteArray) : Got }

@Composable
private fun rememberFile(d: Dm): Got {
    val session = LocalSession.current
    val key = d.file?.ox ?: d.file?.localBlob ?: d.rid
    val got by produceState<Got>(Got.Loading, key) {
        value = try { session.dmFile(d)?.let { Got.Bytes(it) } ?: Got.Failed } catch (e: Exception) { Got.Failed }
    }
    return got
}

@Composable
private fun rememberPicture(d: Dm, side: Int): Pair<Boolean, ImageBitmap?> {
    val got = rememberFile(d)
    // null: still decoding; Failed: not a picture this phone can show
    val bmp by produceState<Any?>(null, got) {
        val b = (got as? Got.Bytes)?.b ?: return@produceState
        value = withContext(Dispatchers.Default) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
                BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } catch (e: Throwable) { null } ?: Got.Failed
        }
    }
    return (got is Got.Failed || bmp === Got.Failed) to (bmp as? ImageBitmap)
}

/** "This file can't be opened": never the bytes. */
@Composable
private fun Unreadable(modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(c.surface).padding(12.dp).testTag("dm-file-unreadable"), contentAlignment = Alignment.Center) {
        Text(DmFiles.UNREADABLE, fontSize = 14.sp, color = c.muted)
    }
}

/**
 * Pictures, files and recordings of one message (canvas v109 messages/attach-thread): one picture
 * on its own, rounded, no bubble; several as a 2 × 2 grid ("+N" on the fourth); a file as its
 * name and "PDF · 1.2 MB" (tap: the phone's viewer); a recording with play, a bar that fills and
 * its length. PENDING: dimmed while it is not out.
 */
@Composable
fun DmFileParts(files: List<Dm>, pending: Boolean, modifier: Modifier = Modifier) {
    val pictures = files.filter { it.file?.isPicture == true }
    val others = files.filter { it.file?.isPicture != true }
    Column(modifier.alpha(if (pending) 0.55f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            pictures.size == 1 -> OnePicture(pictures[0])
            pictures.size > 1 -> PictureGrid(pictures)
        }
        for (d in others) {
            if (d.file?.isAudio == true) Recording(d) else FileRow(d)
        }
    }
}

@Composable
private fun OnePicture(d: Dm) {
    val c = Belfry.colors
    val (failed, bmp) = rememberPicture(d, 1080)
    var open by remember { mutableStateOf(false) }
    val ratio = d.file?.dim?.let { (w, h) -> if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 2f) else null } ?: (4f / 3f)
    val m = Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(14.dp))
    when {
        failed -> Unreadable(m)
        bmp == null -> Box(m.background(c.surface), contentAlignment = Alignment.Center) { Spinner(size = 18.dp) }
        else -> Image(bmp, contentDescription = "Picture", contentScale = ContentScale.Crop, modifier = m.clickable(role = Role.Button, onClickLabel = "Open the picture") { open = true }.testTag("dm-picture"))
    }
    if (open && bmp != null) ImageViewer(ViewerImage(null, bmp, null)) { open = false }
}

@Composable
private fun PictureGrid(pictures: List<Dm>) {
    val shown = pictures.take(4)
    val more = pictures.size - 4
    Column(Modifier.widthIn(max = 260.dp).testTag("dm-picture-grid"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in shown.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for ((i, d) in row.withIndex()) {
                    val last = d === shown.last() && more > 0
                    GridCell(d, if (last) more else 0, Modifier.weight(1f))
                    if (row.size == 1 && i == 0) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GridCell(d: Dm, more: Int, modifier: Modifier) {
    val c = Belfry.colors
    val (failed, bmp) = rememberPicture(d, 540)
    var open by remember { mutableStateOf(false) }
    val m = modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
    Box(m) {
        when {
            failed -> Unreadable(Modifier.fillMaxSize())
            bmp == null -> Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) { Spinner(size = 16.dp) }
            else -> Image(bmp, contentDescription = "Picture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = "Open the picture") { open = true })
        }
        if (more > 0) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Text("+$more", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)
        }
    }
    if (open && bmp != null) ImageViewer(ViewerImage(null, bmp, null)) { open = false }
}

/** "PDF · 1.2 MB" */
fun fileFacts(mime: String, name: String, size: Long): String {
    val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 && it != name }?.uppercase()
        ?: mime.substringAfter('/').substringBefore(';').substringAfterLast('.').uppercase().take(5).ifEmpty { "FILE" }
    return "$ext · " + sizeText(size)
}

fun sizeText(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000} KB"
    else -> "$n bytes"
}

/** A name too long for the row: cut in the middle, so its end (the type) still shows. */
fun middleCut(s: String, max: Int = 34): String = if (s.length <= max) s else s.take(max / 2 - 1) + "…" + s.takeLast(max / 2 - 1)

@Composable
private fun FileRow(d: Dm) {
    val c = Belfry.colors
    val f = d.file ?: return
    val context = LocalContext.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.widthIn(max = 280.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Open ${f.name}") {
                busy = true; failed = false
                scope.launch {
                    val bytes = try { session.dmFile(d) } catch (e: Exception) { null }
                    failed = bytes == null || !openInViewer(context, bytes, f.name, f.mime)
                    busy = false
                }
            }
            .padding(10.dp).testTag("dm-file"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            if (busy) Spinner(size = 16.dp) else Icon(MoreIcons.File, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(middleCut(f.name), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (failed) DmFiles.UNREADABLE else fileFacts(f.mime, f.name, f.size), fontSize = 12.sp, color = c.muted, maxLines = 1)
        }
    }
}

/** Hands the decrypted file to the phone's viewer through this app's private cache (FileProvider). */
private suspend fun openInViewer(context: Context, bytes: ByteArray, name: String, mime: String): Boolean {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "open").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "file" }
        val f = File(dir, safe)
        f.writeBytes(bytes)
        FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

private fun clock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

/** A recording: play / pause, a bar that fills as it plays, its length. It plays in place. */
@Composable
private fun Recording(d: Dm) {
    val c = Belfry.colors
    val f = d.file ?: return
    val context = LocalContext.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val player = remember(d.rid) { arrayOfNulls<MediaPlayer>(1) }
    var playing by remember(d.rid) { mutableStateOf(false) }
    var loading by remember(d.rid) { mutableStateOf(false) }
    var failed by remember(d.rid) { mutableStateOf(false) }
    var done by remember(d.rid) { mutableFloatStateOf(0f) }
    DisposableEffect(d.rid) { onDispose { try { player[0]?.release() } catch (e: Exception) { }; player[0] = null } }
    LaunchedEffect(playing) {
        while (playing) {
            player[0]?.let { p -> try { if (p.duration > 0) done = p.currentPosition.toFloat() / p.duration } catch (e: Exception) { } }
            delay(200)
        }
    }
    fun toggle() {
        val p = player[0]
        if (p != null) {
            try { if (playing) p.pause() else p.start(); playing = !playing } catch (e: Exception) { failed = true }
            return
        }
        loading = true
        scope.launch {
            val bytes = try { session.dmFile(d) } catch (e: Exception) { null }
            if (bytes == null) { failed = true; loading = false; return@launch }
            try {
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "play").apply { mkdirs() }.let { dir -> File(dir, (f.ox ?: d.rid).take(32) + ".m4a").also { if (!it.exists()) it.writeBytes(bytes) } }
                }
                val mp = MediaPlayer()
                mp.setDataSource(file.path)
                mp.setOnCompletionListener { playing = false; done = 0f; try { it.seekTo(0) } catch (e: Exception) { } }
                mp.prepare()
                player[0] = mp
                mp.start()
                playing = true
            } catch (e: Exception) { failed = true }
            loading = false
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.widthIn(max = 280.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(10.dp).testTag("dm-recording"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.accent)
                .clickable(enabled = !loading && !failed, role = Role.Button, onClickLabel = if (playing) "Pause" else "Play") { toggle() },
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> Spinner(size = 16.dp, color = c.onAccent)
                playing -> Row { Box(Modifier.width(4.dp).height(14.dp).background(c.onAccent)); Spacer(Modifier.width(4.dp)); Box(Modifier.width(4.dp).height(14.dp).background(c.onAccent)) }
                else -> Text("▶", fontSize = 15.sp, color = c.onAccent)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // a waveform-like row of bars that fills as it plays (the same bars for every recording: no audio analysis)
            Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                val bars = 28
                for (i in 0 until bars) {
                    val h = WAVE[i % WAVE.size]
                    Box(Modifier.weight(1f).height((22 * h).dp).clip(RoundedCornerShape(1.dp)).background(if (i.toFloat() / bars < done) c.text else c.line))
                }
            }
            Text(if (failed) DmFiles.UNREADABLE else (f.duration?.let { clock(it) } ?: "Audio recording"), fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private val WAVE = floatArrayOf(0.35f, 0.6f, 0.9f, 0.5f, 0.75f, 0.4f, 1f, 0.55f, 0.3f, 0.7f, 0.85f, 0.45f, 0.65f, 0.25f)
