package sh.belfry.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.belfry.core.engine.NewAttachment
import java.io.ByteArrayOutputStream

/** A photo picked in the composer: what will be sent and a small picture of it to show. */
class PickedPhoto(val attachment: NewAttachment, val thumb: ImageBitmap)

object Photos {
    const val MAX_SIDE = 2048
    private const val THUMB_SIDE = 256

    /** Reads a picked photo off the main thread: downscaled to [MAX_SIDE], JPEG. Null when it can't be read. */
    suspend fun load(context: Context, uri: Uri, index: Int): PickedPhoto? = withContext(Dispatchers.IO) {
        try {
            val bitmap = decode(context, uri, MAX_SIDE) ?: return@withContext null
            fromBitmap(bitmap, "photo-${System.currentTimeMillis()}-$index.jpg")
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    fun fromBitmap(bitmap: Bitmap, name: String): PickedPhoto {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val scale = THUMB_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val thumb = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        return PickedPhoto(NewAttachment(name, "image/jpeg", out.toByteArray(), bitmap.width, bitmap.height), thumb.asImageBitmap())
    }

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies the photo's orientation.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }
        // Android 8: no orientation fix (known debt).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scale = maxSide.toFloat() / maxOf(raw.width, raw.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true) else raw
    }

    /** A picture kept on the device (a pending attachment, a demo photo): decoded small enough to show. */
    fun decodeBytes(bytes: ByteArray): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
