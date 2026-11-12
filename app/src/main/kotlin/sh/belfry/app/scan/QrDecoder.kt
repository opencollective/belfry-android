package sh.belfry.app.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/**
 * Reads a QR code from a camera frame with ZXing's pure-Java decoder: no Google Play services,
 * no ML Kit. Free of Android classes, so a JVM test can feed it a drawn code.
 */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * The text of the QR code in this frame, or null. [luma] is the Y plane, one byte per pixel,
     * rows of [width] (see [packed]). A code can be at any angle; a light-on-dark one is tried too.
     */
    fun decode(luma: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || luma.size < width * height) return null
        val src = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        return read(src) ?: read(src.invert())
    }

    private fun read(src: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), hints).text
    } catch (e: Exception) {
        // no code in this frame (NotFound), or a damaged one (Checksum, Format)
        null
    }

    /** The Y plane without its row padding: [rowStride] bytes per row in the buffer, [width] kept. */
    fun packed(plane: ByteBuffer, rowStride: Int, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        val buf = plane.duplicate()
        if (rowStride == width) {
            buf.position(0)
            buf.get(out, 0, minOf(out.size, buf.remaining()))
            return out
        }
        for (row in 0 until height) {
            val at = row * rowStride
            if (at >= buf.limit()) break
            buf.position(at)
            buf.get(out, row * width, minOf(width, buf.remaining()))
        }
        return out
    }
}
