package sh.belfry.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.belfry.app.scan.QrDecoder
import sh.belfry.core.net.InviteLink
import sh.belfry.core.net.InviteScan
import java.nio.ByteBuffer

/** The scanner's decoder on drawn frames (plain JVM: ZXing only, no camera). */
class QrDecoderTest {
    private val link = "https://chat.belfry.sh/join/AbCdEfGhIjKlMnOpQrStUvWx_-12"

    /** A grey camera frame with the code drawn in it; [inverted] = light on dark. */
    private fun frame(text: String, width: Int = 640, height: Int = 480, inverted: Boolean = false): ByteArray {
        val size = 300
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val out = ByteArray(width * height) { 0xB0.toByte() }
        val x0 = (width - size) / 2
        val y0 = (height - size) / 2
        for (y in 0 until size) for (x in 0 until size) {
            val dark = m.get(x, y) != inverted
            out[(y0 + y) * width + x0 + x] = (if (dark) 0x10 else 0xF0).toByte()
        }
        return out
    }

    @Test fun readsAnInvitation() {
        val text = QrDecoder.decode(frame(link), 640, 480)
        assertEquals(link, text)
        assertEquals(InviteLink("belfry.sh", "AbCdEfGhIjKlMnOpQrStUvWx_-12"), InviteScan.read(text))
    }

    @Test fun readsLightOnDark() {
        assertEquals(link, QrDecoder.decode(frame(link, inverted = true), 640, 480))
    }

    @Test fun anotherCodeIsNotAnInvitation() {
        val text = QrDecoder.decode(frame("https://example.org/"), 640, 480)
        assertEquals("https://example.org/", text)
        assertNull(InviteScan.read(text))
    }

    @Test fun nothingInAnEmptyFrame() {
        assertNull(QrDecoder.decode(ByteArray(640 * 480) { 0x80.toByte() }, 640, 480))
        assertNull(QrDecoder.decode(ByteArray(10), 640, 480))
    }

    @Test fun rowPaddingIsDropped() {
        val w = 640; val h = 480; val stride = 704
        val tight = frame(link, w, h)
        val padded = ByteArray(stride * h) { 0x55 }
        for (row in 0 until h) System.arraycopy(tight, row * w, padded, row * stride, w)
        val packed = QrDecoder.packed(ByteBuffer.wrap(padded), stride, w, h)
        assertEquals(link, QrDecoder.decode(packed, w, h))
        // the last row of a real buffer may stop at the width, without padding
        val short = padded.copyOf(stride * (h - 1) + w)
        assertEquals(link, QrDecoder.decode(QrDecoder.packed(ByteBuffer.wrap(short), stride, w, h), w, h))
    }
}
