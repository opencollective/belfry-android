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

/** A photo full screen. A tap or back closes it. */
@Composable
fun ImageViewer(image: ViewerImage, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            if (image.bitmap != null) {
                Image(image.bitmap, contentDescription = image.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else if (image.url != null && image.url.startsWith("https://") && LocalNetworkImages.current) {
                AsyncImage(model = image.url, contentDescription = image.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            IconAction(BelfryIcons.Close, "Close", onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp), tint = Color.White)
        }
    }
}
