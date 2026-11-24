package sh.belfry.app.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import sh.belfry.core.preview.EncodedPicture
import sh.belfry.core.preview.LinkPreviews
import sh.belfry.core.preview.PictureEncoder
import java.io.ByteArrayOutputStream

/**
 * A link preview's picture re-encoded on the device (SPEC 19.87): decoded with a sample size (a
 * huge picture never lands whole in memory; more than 40 megapixels is refused), scaled so the
 * longest side is at most 1200 px, drawn on white (JPEG has no transparency) and written as JPEG,
 * the quality lowered, then the size, until it is at most 300 kB. A GIF gives its first frame.
 */
object AndroidPictureEncoder : PictureEncoder {
    private const val MAX_PIXELS = 40_000_000L

    override fun encode(bytes: ByteArray, mime: String): EncodedPicture? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0 || w0.toLong() * h0 > MAX_PIXELS) return null
        var sample = 1
        while (maxOf(w0, h0) / (sample * 2) >= LinkPreviews.PICTURE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        var side = LinkPreviews.PICTURE_SIDE
        repeat(4) {
            val scaled = fit(decoded, side)
            for (q in intArrayOf(85, 75, 65, 55)) {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, q, out)) break
                if (out.size() <= LinkPreviews.PICTURE_OUT_MAX) {
                    val r = EncodedPicture(out.toByteArray(), "image/jpeg", scaled.width, scaled.height)
                    if (scaled !== decoded) scaled.recycle()
                    decoded.recycle()
                    return r
                }
            }
            if (scaled !== decoded) scaled.recycle()
            side = side * 3 / 4
        }
        decoded.recycle()
        return null
    }

    /** SRC with its longest side at most SIDE, on white. */
    private fun fit(src: Bitmap, side: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        val k = if (long > side) side.toFloat() / long else 1f
        val w = maxOf(1, Math.round(src.width * k))
        val h = maxOf(1, Math.round(src.height * k))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
