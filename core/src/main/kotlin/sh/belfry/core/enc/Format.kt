package sh.belfry.core.enc

/**
 * What this device knows of one belfry's `encrypted.format` (docs/encrypted.md §12, §17.13; the web's
 * access.ts after bdd3b8b / 95df933 / 515400d). Pure; kept device-wide per belfry by `EncFormatCache`.
 *
 * FORMAT: 1, 3 or null (never read); 3 is kept for good (one way). SEEN: an `encrypted` object was in a
 * successful read of its belfry.json once. READ_AT: the last successful read that said 1 or 3 (seconds). SINCE3: the
 * first read that said 3 (seconds; 0 when a 3 was kept before it was recorded).
 */
data class EncFormatState(val format: Int? = null, val seen: Boolean = false, val readAt: Long = 0, val since3: Long = 0) {
    /** Since when (seconds) this device knows the belfry as format 3 (SPEC 19.83: plain NIP-17 from a listed account is dropped from then); null: not 3. */
    val format3Since: Long? get() = if (format != 3) null else since3.takeIf { it > 0 } ?: readAt

    /**
     * After a SUCCESSFUL read of belfry.json whose `encrypted` field was DOC ([Doc]) at NOW. An explicit 1 or 3
     * decides (3 for good); no object at all decides 1 only for a belfry that never showed one; an object with
     * another format decides nothing.
     */
    fun after(doc: String, now: Long): EncFormatState = when (doc) {
        DOC_3 -> copy(format = 3, seen = true, readAt = now, since3 = if (format == 3 && since3 > 0) since3 else now)
        DOC_1 -> copy(format = if (format == 3) 3 else 1, seen = true, readAt = now)
        DOC_OTHER -> copy(seen = true)
        DOC_NONE -> if (seen) this else copy(format = if (format == 3) 3 else 1, readAt = now)
        else -> this
    }

    /** A 1 counts for a write only when read at most 5 minutes before NOW (and not dated ahead of it by more than the skew). */
    fun freshOne(now: Long): Boolean = format == 1 && now - readAt < FRESH && readAt - now < FRESH

    /**
     * The format a write uses now: 3 (for good); 1 at once when no `encrypted` object was ever seen here (the
     * web's P3: an older server has no format 3); a fresh 1; else null (read again first; still null = hold).
     */
    fun writeFormat(now: Long): Int? = when {
        format == 3 -> 3
        !seen -> 1
        freshOne(now) -> 1
        else -> null
    }

    /** A read of belfry.json could change [writeFormat] (a stale or unknown format after an object was seen). */
    fun wantsRead(now: Long): Boolean = writeFormat(now) == null

    companion object {
        const val FRESH = 300L
        /** `Community.encrypted`: what belfry.json said, as read. */
        const val DOC_NONE = "none"
        const val DOC_1 = "1"
        const val DOC_3 = "3"
        const val DOC_OTHER = "other"

        /** belfry.json's `encrypted` value as one of the DOC_ words (anything but an object, null included, is no object: the web's `enc && typeof enc === 'object'`). */
        fun docOf(isObject: Boolean, format: Double?): String = when {
            !isObject -> DOC_NONE
            format == 3.0 -> DOC_3
            format == 1.0 -> DOC_1
            else -> DOC_OTHER
        }
    }
}
