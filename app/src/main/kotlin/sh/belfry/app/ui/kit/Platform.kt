package sh.belfry.app.ui.kit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code of [text]. Always dark on white with a quiet zone (also in dark mode: scanners need
 * it). Nothing is drawn when the text cannot be encoded (too long).
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val matrix: BitMatrix? = remember(text) {
        try {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
        } catch (e: Exception) {
            null
        }
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp)
            .semantics { contentDescription = "QR code" },
    ) {
        if (matrix != null) {
            Canvas(Modifier.fillMaxSize()) {
                val n = matrix.width
                val cell = this.size.minDimension / n
                for (y in 0 until matrix.height) {
                    var x = 0
                    while (x < n) {
                        if (matrix.get(x, y)) {
                            var run = 1
                            while (x + run < n && matrix.get(x + run, y)) run++
                            // 0.5 px overlap so no hairlines show between cells
                            drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(run * cell + 0.5f, cell + 0.5f))
                            x += run
                        } else {
                            x++
                        }
                    }
                }
            }
        }
    }
}

/** Opens the system share sheet with a text (an invite link). */
fun shareText(context: Context, text: String, title: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (title != null) putExtra(Intent.EXTRA_TITLE, title)
    }
    val chooser = Intent.createChooser(send, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
        context.startActivity(chooser)
    } catch (e: Exception) {
        // no app can share text: nothing to do
    }
}

/**
 * Copies to the clipboard. Returns true when the app should say "Copied" itself: Android 13+
 * shows its own confirmation.
 */
fun copyText(context: Context, text: String, label: String = "Belfry"): Boolean {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
}
