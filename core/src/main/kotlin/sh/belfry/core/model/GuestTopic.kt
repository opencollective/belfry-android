package sh.belfry.core.model

import sh.belfry.core.outbox.Target

/**
 * A topic reached through a printed card (SPEC 19.77; collective-belfry docs/cards.md §3; web 8e2b3b2,
 * fa579cd core/guestmode.ts `guestTopic` / `guestTopicPost`; canvas cards/follow-channel,
 * contribute-compose, contribute-save): its reading guest, who gave no e-mail address, may post a
 * plain message (text, links, photos) in that topic and nowhere else. The belfry says the link was a
 * topic card's with `"card": "topic"` in the join answer (and in `GET /channel-invites/<token>`).
 * The relay does not enforce this: the client lets exactly [mayPost] through (Session.send).
 */
object GuestTopic {
    const val TOPIC = "topic"
    const val GROUP = "group"

    /** The `card` field as the belfry sends it: `group`, `topic`, else null (absent on every other invite). */
    fun card(raw: String?): String? = raw?.takeIf { it == GROUP || it == TOPIC }

    /** Reading in GROUP, which this device reached through a topic card. */
    fun here(reading: Boolean, channel: String?, card: String?, group: String): Boolean =
        reading && group.isNotEmpty() && channel == group && card == TOPIC

    /**
     * The approved list, exactly: one kind 9 to the groups relay whose tags are `[["h", T]]` (its
     * photos become `imeta` when it is sent), T being that topic, with images only. Nothing else a
     * reading guest writes goes.
     */
    fun mayPost(reading: Boolean, channel: String?, card: String?, target: Target, kind: Int, tags: List<List<String>>, mimes: List<String> = emptyList()): Boolean {
        if (target != Target.GROUPS || kind != 9) return false
        val h = tags.filter { it.firstOrNull() == "h" }
        if (h.size != 1 || h[0].size < 2 || !here(reading, channel, card, h[0][1])) return false
        return tags.all { it.firstOrNull() == "h" || it.firstOrNull() == "imeta" } && mimes.all { it.startsWith("image/") }
    }

    /** The message as it is queued: kind 9, `[["h", T]]` only (no topics, no mentions, no references). */
    fun post(groupId: String, text: String): Draft = Draft(Target.GROUPS, 9, text.trim(), listOf(listOf("h", groupId)))

    // ---- the words (the web's, canvas cards/contribute-*) -------------------------------------------
    const val PLACEHOLDER = "Share a link, a message, a photo"
    const val SENDING = "Sending as a guest"
    const val LOG_IN = "Log in"
    const val SAVE_TITLE = "Shared. Don’t lose your account"
    const val SAVE_TEXT = "Add your e-mail to keep what you shared, get replies and follow this topic."
    const val SAVE = "Save"
    const val NOT_NOW = "Not now"
    /** A reading guest has no name until they save: "Guest" (web model.ts displayName). */
    const val GUEST_NAME = "Guest"

    /** The store key of "Not now" for one topic (kept on the device, as the web's localStorage). */
    fun laterKey(groupId: String): String = "ui:topic-save-no:$groupId"
}
