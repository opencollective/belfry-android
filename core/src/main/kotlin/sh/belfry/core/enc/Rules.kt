package sh.belfry.core.enc

import sh.belfry.core.nostr.Event

/** §4 + §17.2: what a people conversation of N devices does. */
enum class PeopleLimit { ENCRYPT, CLEAR, HELD }

/** §4: a private group over 1000 devices refuses to send (never falls back). */
enum class GroupLimit { ENCRYPT, REFUSE }

/** §13, §16.9, §17.2, §17.12: the small rules. */
object EncRules {
    /** §17.12: BOOK (K → the conversation it was first declared for) with a declaration added; the first stays (false when K was already declared). */
    fun declare(book: MutableMap<String, String>, K: String, conv: String): Boolean {
        if (K in book) return false
        book[K] = conv
        return true
    }

    /** §17.2: an unencrypted event dated AT in a conversation whose first key is dated FIRST_KEY_AT: shown "Not encrypted" and never acting (true) when after it. */
    fun clearAfterKey(firstKeyAt: Long?, at: Long): Boolean = firstKeyAt != null && at > firstKeyAt

    /** §4 + §17.2: encrypted up to 100 devices; over: not encrypted when it never was, held when it was. */
    fun peopleLimit(devices: Int, everEncrypted: Boolean): PeopleLimit =
        if (devices <= Enc.MAX_PEOPLE_DEVICES) PeopleLimit.ENCRYPT else if (everEncrypted) PeopleLimit.HELD else PeopleLimit.CLEAR

    fun groupLimit(devices: Int): GroupLimit = if (devices <= Enc.MAX_GROUP_DEVICES) GroupLimit.ENCRYPT else GroupLimit.REFUSE

    private val CLEAR_KINDS = setOf(9000, 9001, 9002, 9005, 9007, 9008, 9021, 9022, 39000, 39001, 39002, 39003)

    /** §16.9: what stays in clear in a private group (the relay or the belfry reads it). §26: an opened rumor of these is dropped. */
    fun clearInGroup(e: Event, groupsKey: String?): Boolean {
        if (e.kind in CLEAR_KINDS) return true
        if (e.kind == 1985) return (!groupsKey.isNullOrEmpty() && e.pubkey == groupsKey) || e.tags.any { it.size >= 2 && it[0] == "L" && it[1] == "belfry/vouch" }
        if (e.kind == 30078) {
            val d = e.tags.firstOrNull { it.isNotEmpty() && it[0] == "d" }?.getOrNull(1) ?: ""
            return d.startsWith("belfry:inbox:") || d.startsWith("seen:")
        }
        return false
    }
}
