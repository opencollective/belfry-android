package sh.belfry.app.ui.kit

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.chat.ImageViewer
import sh.belfry.app.ui.chat.ViewerImage
import sh.belfry.app.ui.theme.Belfry

/** The file row's words for a picture that can't be loaded or decrypted (canvas v179). */
const val PICTURE_FAILED = "Picture · couldn't be opened"

/** A picture decoded for the screen (at most [side] px on its long edge), or null when it isn't one. */
fun decodePicture(bytes: ByteArray, side: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
} catch (e: Throwable) { null }

/**
 * Pictures attached to a message, shown in it (Xavier, 2026-11-22; the DM files' layout, canvas v109
 * messages/attach-thread; canvas v179 messages/email-pictures, one-shape/email-pictures): one on its
 * own, large and rounded; two side by side; three as two + one wide; four or more as a 2 × 2 grid with
 * "+N" on the fourth; a tap opens the zoom viewer. [load] gives every picture's bytes at once (null for one
 * that can't be loaded or decrypted): those, and any that don't decode, are drawn by [fallback]
 * (the file row) under the pictures. [key] says when to load again. [names] label the viewer.
 */
@Composable
fun InlinePictures(key: Any, count: Int, names: List<String?>, load: suspend () -> List<ByteArray?>, fallback: @Composable (Int) -> Unit) {
    if (count <= 0) return
    // null: loading; else one bitmap or null (fall back) per picture
    var got by remember(key) { mutableStateOf<List<ImageBitmap?>?>(null) }
    LaunchedEffect(key) {
        got = withContext(Dispatchers.Default) {
            val bytes = try { load() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            List(count) { i -> bytes.getOrNull(i)?.let { decodePicture(it, if (count == 1) 1080 else 540) } }
        }
    }
    val c = Belfry.colors
    val list = got
    Column(Modifier.testTag("inline-pictures"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (list == null) {
            Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)).background(c.surface), contentAlignment = Alignment.Center) { Spinner(size = 18.dp) }
            return@Column
        }
        val shown = list.indices.filter { list[it] != null }
        when {
            shown.size == 1 -> OneInline(list[shown[0]]!!, names.getOrNull(shown[0]))
            shown.size > 1 -> InlineGrid(shown.map { list[it]!! to names.getOrNull(it) })
        }
        for (i in list.indices) if (list[i] == null) fallback(i)
    }
}

@Composable
private fun OneInline(bmp: ImageBitmap, name: String?) {
    var open by remember { mutableStateOf(false) }
    val ratio = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceIn(0.5f, 2f)
    Image(
        bmp, contentDescription = name ?: "Picture", contentScale = ContentScale.Crop,
        modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = "Open the picture") { open = true }.testTag("inline-picture"),
    )
    if (open) ImageViewer(ViewerImage(null, bmp, name)) { open = false }
}

@Composable
private fun InlineGrid(pictures: List<Pair<ImageBitmap, String?>>) {
    val shown = pictures.take(4)
    val more = pictures.size - 4
    Column(Modifier.widthIn(max = 260.dp).testTag("inline-picture-grid"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((r, row) in shown.chunked(2).withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for ((i, p) in row.withIndex()) {
                    val last = r * 2 + i == shown.size - 1 && more > 0
                    // the odd one out of three runs the whole width (canvas v179: 2 + 1 wide)
                    if (row.size == 1) InlineCell(p.first, p.second, 0, Modifier.fillMaxWidth(), ratio = 2f)
                    else InlineCell(p.first, p.second, if (last) more else 0, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun InlineCell(bmp: ImageBitmap, name: String?, more: Int, modifier: Modifier, ratio: Float = 1f) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.aspectRatio(ratio).clip(RoundedCornerShape(10.dp))) {
        Image(
            bmp, contentDescription = name ?: "Picture", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = "Open the picture") { open = true },
        )
        if (more > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Text("+$more", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
    if (open) ImageViewer(ViewerImage(null, bmp, name)) { open = false }
}
