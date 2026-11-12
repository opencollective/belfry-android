package sh.belfry.app.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.LocalNetworkImages

/**
 * A photo full screen; a tap, ✕ or back closes it. A message with several pictures: swipe
 * sideways through them (the neighbours are loaded ahead), "2 / 5" at the top.
 */
@Composable
fun ImageViewer(image: ViewerImage, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val close = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
        val network = LocalNetworkImages.current
        Box(Modifier.fillMaxSize().background(Color.Black).then(close), contentAlignment = Alignment.Center) {
            val urls = image.more.filter { it.startsWith("https://") }
            if (urls.size > 1 && network) {
                val pager = rememberPagerState(initialPage = image.index.coerceIn(0, urls.size - 1)) { urls.size }
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { urls[it] }) { page ->
                    Box(Modifier.fillMaxSize().then(close), contentAlignment = Alignment.Center) {
                        AsyncImage(model = urls[page], contentDescription = image.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(
                    "${pager.currentPage + 1} / ${urls.size}", color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp).testTag("viewer-count"),
                )
            } else if (image.bitmap != null) {
                Image(image.bitmap, contentDescription = image.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else if (image.url != null && image.url.startsWith("https://") && network) {
                AsyncImage(model = image.url, contentDescription = image.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            IconAction(BelfryIcons.Close, "Close", onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp), tint = Color.White)
        }
    }
}
