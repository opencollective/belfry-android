package sh.belfry.core.model

import sh.belfry.core.crypto.sha256Hex

/**
 * The face of an outside e-mail correspondent (docs/one-shape.md "The Messages list: faces, not
 * envelopes", SPEC 19.59): their picture from the belfry's avatar service, keyed by the hash of
 * their address, else a disc with their initials in a colour picked from that hash.
 * Apps never contact Gravatar (or any other host) themselves: only `<api base>/avatar/<hash>`.
 */
object MailFace {
    /** How long a picture (200) is kept before it is asked again. */
    const val PICTURE_SECONDS = 30L * 24 * 3600
    /** How long a "no picture" (404) is kept before it is asked again. */
    const val MISS_SECONDS = 7L * 24 * 3600

    /** Lowercase hex sha256 of the trimmed, lowercased address. */
    fun hash(address: String): String = sha256Hex(address.trim().lowercase())

    /** `<api base>/avatar/<hash>` (api base = `https://belfry.<domain>/api/v1` by default). */
    fun url(apiBase: String, hash: String): String = apiBase.trimEnd('/') + "/avatar/" + hash

    /**
     * Initials: from the name when it has one (first letters of its first and last words, "Ana
     * Pereira" → "AP", "Lou" → "L"; a part in brackets is left out), else the first letter or digit
     * of the address ("permits@cityhall.example" → "P"). "?" when there is nothing to take.
     */
    fun initials(name: String?, address: String): String {
        val n = name?.trim().orEmpty()
        if (n.isNotEmpty() && '@' !in n) {
            val words = n.replace(BRACKETS, " ").split(Regex("\\s+"))
                .mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }
            if (words.isNotEmpty()) {
                val first = words.first().uppercaseChar()
                return if (words.size == 1) first.toString() else "$first${words.last().uppercaseChar()}"
            }
        }
        val local = (if ('@' in n && address.isBlank()) n else address).trim().substringBefore('@')
        return local.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    }

    /**
     * Which of [size] colours: the first 6 hex digits of the hash modulo [size], the same rule as a
     * member's colour from their key (web chat `avatarColor`), so one address has one colour everywhere.
     */
    fun colorIndex(hash: String, size: Int): Int {
        require(size > 0)
        val v = hash.take(6).toIntOrNull(16) ?: 0
        return v % size
    }

    /** Is a cached answer saved at [savedAt] (unix seconds) still good at [now]? */
    fun fresh(picture: Boolean, savedAt: Long, now: Long): Boolean =
        now >= savedAt && now - savedAt < (if (picture) PICTURE_SECONDS else MISS_SECONDS)

    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^]]*]")
}
