package sh.belfry.core.preview

import sh.belfry.core.crypto.Random
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.dm.DmFiles

/** A picture re-encoded for upload: JPEG or WebP, its size in pixels. */
class EncodedPicture(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)

/**
 * Re-encodes a downloaded picture (the app's: Android's Bitmap APIs): JPEG or WebP, the longest
 * side at most [LinkPreviews.PICTURE_SIDE] px, at most [LinkPreviews.PICTURE_OUT_MAX] bytes. Null:
 * it can't (the preview goes without a picture). [mime] is what the first bytes say it is.
 */
fun interface PictureEncoder {
    fun encode(bytes: ByteArray, mime: String): EncodedPicture?
}

/**
 * Builds a preview on the sender's device (SPEC 19.87): the page through [fetch], what it says
 * ([PageMeta]), then its picture: downloaded through [fetch] too, re-encoded by [encoder], checked
 * again here, encrypted when [PreviewMode.ENCRYPTED] (AES-256-GCM like a file of a direct message,
 * a fresh key and nonce), and uploaded by [upload] (bytes, type → its URL). The picture is kept
 * only when that URL is on the belfry's Blossom ([blossom]'s origin, or the demo's [local]
 * prefix). Nothing usable (no title, no description) → null: no preview, the link stays a link.
 * A picture that fails in any way → the preview without it.
 */
class PreviewBuilder(
    private val fetch: SafeFetch,
    private val encoder: PictureEncoder?,
    private val upload: suspend (bytes: ByteArray, mime: String) -> String,
    private val blossom: String,
    private val local: String? = null,
    /** Tests: a fixed key and nonce (hex). */
    private val keyNonce: (() -> Pair<String, String>)? = null,
    /** Kept: the plain re-encoded picture by its imeta `x` (so the sender shows it without downloading it). */
    private val keep: (x: String, plain: ByteArray) -> Unit = { _, _ -> },
) {
    /** Why the last [build] gave no picture (tests). */
    @Volatile var lastPictureWhy: String? = null
        private set

    suspend fun build(url: String, mode: PreviewMode): LinkPreview? {
        val page = (fetch.page(url) as? SafeFetch.Result.Ok)?.got ?: return null
        val html = PageMeta.decode(page.answer.body.let { if (it.size > LinkPreviews.PAGE_MAX) it.copyOf(LinkPreviews.PAGE_MAX) else it }, page.answer.contentType)
        val meta = PageMeta.parse(html, page.finalUrl)
        if (!meta.usable) return null
        val picture = meta.image?.let { picture(it, mode) }
        return LinkPreview(url, meta.title, meta.description, meta.site, picture)
    }

    private fun accept(e: EncodedPicture): Boolean =
        (e.mime == "image/jpeg" || e.mime == "image/webp") && PreviewImages.kind(e.bytes) == e.mime &&
            e.width in 1..LinkPreviews.PICTURE_SIDE && e.height in 1..LinkPreviews.PICTURE_SIDE &&
            e.bytes.size in 1..LinkPreviews.PICTURE_OUT_MAX

    private fun onBlossom(u: String): Boolean =
        (local != null && u.startsWith(local)) || (LinkPreviews.originOf(blossom)?.let { it == LinkPreviews.originOf(u) } == true)

    private suspend fun picture(image: String, mode: PreviewMode): PreviewPicture? {
        lastPictureWhy = null
        val enc = encoder ?: return null.also { lastPictureWhy = "no encoder" }
        val got = when (val r = fetch.picture(image)) {
            is SafeFetch.Result.Ok -> r.got.answer.body
            is SafeFetch.Result.Refused -> return null.also { lastPictureWhy = r.why.name }
        }
        val kind = PreviewImages.kind(got) ?: return null.also { lastPictureWhy = "TYPE" }
        val e = try { enc.encode(got, kind) } catch (x: Exception) { null } ?: return null.also { lastPictureWhy = "encode" }
        if (!accept(e)) return null.also { lastPictureWhy = "encoded out of limits" }
        return try {
            if (mode == PreviewMode.ENCRYPTED) {
                val (k, n) = keyNonce?.invoke() ?: (Random.hex(32) to Random.hex(12))
                val s = DmFiles.seal(e.bytes, k, n)
                val u = upload(s.ciphertext, "application/octet-stream")
                if (!onBlossom(u)) return null.also { lastPictureWhy = "not on blossom" }
                keep(s.x, e.bytes)
                PreviewPicture(u, e.mime, e.width, e.height, s.x, s.ox, s.key, s.nonce)
            } else {
                val u = upload(e.bytes, e.mime)
                if (!onBlossom(u)) return null.also { lastPictureWhy = "not on blossom" }
                val x = sha256Hex(e.bytes)
                keep(x, e.bytes)
                PreviewPicture(u, e.mime, e.width, e.height, x)
            }
        } catch (x: kotlinx.coroutines.CancellationException) {
            throw x
        } catch (x: Exception) {
            lastPictureWhy = "upload"
            null
        }
    }
}
