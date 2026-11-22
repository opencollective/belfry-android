package sh.belfry.core.model

/**
 * Which attachments show as pictures in the message (Xavier, 2026-11-22: "Images should be displayed
 * inline instead of linked as a file"): JPEG, PNG, GIF, WebP, HEIC / HEIF, told by the type, else
 * (no type, or a generic one) by the name's extension. Any other image type (SVG, TIFF…) and every
 * other file stays a file row; so does a picture that can't be loaded or decrypted.
 */
object Pictures {
    private val TYPES = setOf("image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif")
    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif")
    private val GENERIC = setOf("", "application/octet-stream", "binary/octet-stream", "application/binary")

    fun isPicture(mime: String?, name: String?): Boolean {
        val m = (mime ?: "").lowercase().substringBefore(';').trim()
        if (m in TYPES) return true
        if (m !in GENERIC) return false
        val file = (name ?: "").substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = file.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext != file.lowercase() && ext in EXTENSIONS
    }
}
