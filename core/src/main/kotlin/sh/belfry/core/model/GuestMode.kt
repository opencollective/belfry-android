package sh.belfry.core.model

import sh.belfry.core.crypto.Random
import sh.belfry.core.nostr.Event

/**
 * Guest mode (SPEC 19.26; collective-belfry docs/guests.md; web b2e968b core/guestmode.ts and
 * core/guestjoin.ts): someone who opened a guest link reads its channel at once, with no name.
 * Nothing they do is sent until they join with a name, an e-mail and the 6-digit code mailed to it.
 * The relay does not enforce this: the client refuses every write (Session.guard).
 *
 * How everyone else reads it is in [World]: a key whose latest kind 9000 in a channel carries
 * `["reading","1"]` is "reading" there until a later 9000 without it, a `belfry/guest` label
 * `saved` or `member`, or a 9001. Reading keys are left out of members, counts, "active",
 * join lines and pickers.
 */
object GuestMode {
    /** The tag on a guest-mode 9000 (`["reading","1"]`). */
    const val TAG = "reading"

    /** What a refused write says (the Join sheet opens with it). */
    const val REFUSED = "guest_mode"
    const val REFUSED_TEXT = "Join to do this: you are reading as a guest."

    /** The codes [message] knows (the web's JoinError). */
    val CODES = setOf("invalid", "name_needed", "too_many", "wrong_code", "expired", "taken", "not_guest", "offline", "failed")

    /** Fixed words for each answer (never the server's text, never a URL's). TRIES_LEFT: with `wrong_code`, when the belfry said. */
    fun message(code: String, triesLeft: Int? = null): String = when {
        code == "wrong_code" && triesLeft != null -> when {
            triesLeft <= 0 -> "That code is not right. Send a new one."
            triesLeft == 1 -> "That code is not right. 1 try left."
            else -> "That code is not right. $triesLeft tries left."
        }
        else -> fixed(code)
    }

    private fun fixed(code: String): String = when (code) {
        "invalid" -> "That doesn’t look like an e-mail address."
        "name_needed" -> "Your name is needed to join."
        "too_many" -> "Too many codes for now. Try again in half an hour."
        "wrong_code" -> "That code is not right. Check the e-mail and try again."
        "expired" -> "That code has expired. Send a new one."
        "taken" -> "This address already has an account here."
        "not_guest" -> "This guest access has ended. Ask for a new invite."
        "offline" -> "No connection. Try again."
        else -> "That did not work. Try again."
    }

    /** A name as it is sent: trimmed, inner spaces folded, at most 100 characters. */
    fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(100)

    private val EMAIL = Regex("^\\S+@\\S+\\.\\S+$")
    /** What the Join sheet takes as an e-mail address (the web's test). */
    fun isEmail(s: String): Boolean = EMAIL.matches(s.trim())

    /** The loading line from the link to the channel. */
    fun openingLine(channelName: String?): String = if (channelName.isNullOrBlank()) "Opening…" else "Opening # $channelName…"

    /** The bar that stands where the composer would be. */
    fun barLine(channelLabel: String): String = "You’re reading $channelLabel as a guest."

    /** The Members tab's line for the guests who are reading; null when there are none. */
    fun readingLine(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "1 guest is reading: they opened an invite link and have not joined yet."
        else -> "$n guests are reading: they opened an invite link and have not joined yet."
    }

    /**
     * "Sign in instead": the address to prefill in the native sign-in. The belfry's `nip05` (that
     * account's name@domain) first, else the e-mail typed; either only when it is a `name@domain`
     * of this belfry. Null: leave the field empty (with the hint).
     */
    fun signInPrefill(email: String, domain: String, nip05: String? = null): String? =
        nip05?.let { address(it, domain) } ?: address(email, domain)

    private fun address(raw: String, domain: String): String? {
        val e = raw.trim().lowercase()
        val at = e.indexOf('@')
        if (at <= 0 || at != e.lastIndexOf('@')) return null
        return if (e.substring(at + 1) == domain.lowercase() && Regex("^[a-z0-9][a-z0-9._-]{0,63}$").matches(e.substring(0, at))) e else null
    }

    // ---- the demo ----------------------------------------------------------------------------------

    /** The demo's code (no mail is sent there). */
    const val DEMO_CODE = "123456"
    /** The demo's address that "has an account here" (moved). */
    const val DEMO_TAKEN = "taken@example.org"

    /** What the belfry publishes when a guest in guest mode joins (demo): the 9000 with the name, then `saved`. */
    fun demoJoined(channel: String, guest: String, name: String, now: Long): List<Event> {
        val by = Random.hex(32)
        return listOf(
            Event(Random.hex(32), by, now, 9000, listOf(listOf("h", channel), listOf("p", guest, "member"), listOf("guest", ""), listOf("invited-by", by), listOf("name", name)), "", ""),
            Event(Random.hex(32), by, now + 1, 1985, listOf(listOf("h", channel), listOf("L", World.GUEST_NS), listOf("l", "saved", World.GUEST_NS), listOf("p", guest)), "", ""),
        )
    }
}
