package sh.belfry.app.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.LocalNetworkImages
import kotlin.math.abs

/**
 * A photo full screen. Pinch to zoom (up to 5×), drag to move around when zoomed, double-tap to
 * zoom in on that spot and back; swipe down, a tap, ✕ or back closes it (a tap on a zoomed photo
 * first shows it whole again). A message with several pictures: swipe sideways through them (the
 * neighbours are loaded ahead; not while zoomed), "2 / 5" at the top.
 */
@Composable
fun ImageViewer(image: ViewerImage, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImageViewerContent(image, onClose)
    }
}

/** What the viewer's window holds (tests drive it without the dialog). */
@Composable
fun ImageViewerContent(image: ViewerImage, onClose: () -> Unit) {
    val network = LocalNetworkImages.current
    // how far the photo is being pulled down (px): the black fades with it, like the web's lightbox
    var pull by remember { mutableFloatStateOf(0f) }
    val shade = 1f - (pull / 1200f).coerceIn(0f, 0.6f)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = shade)), contentAlignment = Alignment.Center) {
        val urls = image.more.filter { it.startsWith("https://") }
        if (urls.size > 1 && network) {
            val pager = rememberPagerState(initialPage = image.index.coerceIn(0, urls.size - 1)) { urls.size }
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { urls[it] }) { page ->
                ZoomablePhoto(onClose, { pull = it }) {
                    AsyncImage(model = urls[page], contentDescription = image.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Text(
                "${pager.currentPage + 1} / ${urls.size}", color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp).testTag("viewer-count"),
            )
        } else {
            ZoomablePhoto(onClose, { pull = it }) {
                if (image.bitmap != null) {
                    Image(image.bitmap, contentDescription = image.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else if (image.url != null && image.url.startsWith("https://") && network) {
                    AsyncImage(model = image.url, contentDescription = image.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
        IconAction(BelfryIcons.Close, "Close", onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp), tint = Color.White)
    }
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * One photo with its gestures. Two fingers zoom; one finger moves a zoomed photo, or pulls a
 * whole one down ([onPull] says how far, in px; past 120 dp it closes). A sideways drag on a
 * whole photo is left alone, so the pager under it turns the page.
 */
@Composable
private fun ZoomablePhoto(onClose: () -> Unit, onPull: (Float) -> Unit, content: @Composable BoxScope.() -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var pull by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val close by rememberUpdatedState(onClose)
    val pulled by rememberUpdatedState(onPull)

    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .testTag("viewer-photo")
            .semantics { stateDescription = if (scale > 1f) "Zoomed in" else "Whole" }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else close() },
                    onDoubleTap = { at ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // zoom in on the spot that was tapped
                            scale = DOUBLE_TAP_ZOOM
                            offset = clamp(Offset((size.width / 2f - at.x) * (DOUBLE_TAP_ZOOM - 1f), (size.height / 2f - at.y) * (DOUBLE_TAP_ZOOM - 1f)), DOUBLE_TAP_ZOOM)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val closeAt = 120.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // 0: not decided yet, 1: pulling down, 2: not ours (zooming, or a sideways swipe)
                    var axis = 0
                    var moved = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            val s = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            scale = s
                            offset = clamp(offset + event.calculatePan(), s)
                            if (axis == 1) { pull = 0f; pulled(0f) }
                            axis = 2
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (axis != 2) {
                            val change = event.changes.firstOrNull()
                            if (change == null || (axis == 0 && change.isConsumed)) {
                                axis = 2
                            } else {
                                moved += change.positionChange()
                                if (axis == 0 && moved.getDistance() > slop) axis = if (moved.y > 0f && abs(moved.y) > abs(moved.x)) 1 else 2
                                if (axis == 1) {
                                    pull = moved.y.coerceAtLeast(0f)
                                    pulled(pull)
                                    change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (axis == 1) {
                        val far = pull > closeAt
                        pull = 0f
                        pulled(0f)
                        if (far) close()
                    }
                    if (scale < 1.05f) { scale = 1f; offset = Offset.Zero }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y + pull
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}
