package sh.belfry.core.model

import sh.belfry.core.nostr.Tag

/**
 * Notes to myself (collective-belfry docs/one-shape.md "Notes to myself", SPEC 19.66): the ordinary
 * direct conversation whose only participant is me, always the first row of Messages. No new kind:
 * a note is a kind 14 rumor (a file: kind 15) of mine with exactly one `p` tag, my own account.
 * Every word the screens say about it is here, so every screen and test say the same.
 */
object Notes {
    /** The row's and the screen's name. */
    const val TITLE = "Notes to myself"
    /** The row's second line while there is no note. */
    const val EMPTY_PREVIEW = "Only you can read these"
    /** The fixed first line of the screen when the notes are sealed (the lock shows with it). */
    const val SEALED_LINE = "Encrypted for your own devices. Nobody else can read your notes, not the belfry's admins either."
    /** The line of an account that is not sealed (an agent, a member holding their own key, a device that cannot seal): no lock. */
    const val UNSEALED_LINE = "Private to your account. Not end-to-end encrypted on this account."
    /** Under the name while there is no note, sealed or not (canvas one-shape/notes-unsealed): no claim in it. */
    const val EMPTY_TEXT = "Write anything you want to keep: ideas, lists, pictures, files."
    /** What the lock beside a sealed note's preview says to a screen reader. */
    const val LOCK = "Encrypted for your own devices"
    /** The message box. */
    const val PLACEHOLDER = "Note to myself"
    /** What the pin beside the row's name says to a screen reader. */
    const val PINNED = "Pinned"

    /** The conversation id: the DM id of {me}. */
    fun conv(me: String): String = "n:$me"

    fun isNotes(conv: String, me: String): Boolean = me.isNotEmpty() && conv == "n:$me"

    /** The `p` tags of a rumor in CONV whose other participants are OTHERS: my own account, once, in Notes. */
    fun pTags(conv: String, me: String, others: List<String>): List<Tag> =
        if (isNotes(conv, me)) listOf(listOf("p", me)) else others.filter { it != me }.map { listOf("p", it) }

    /** The screen's first line: the claim only when sealed, never "nobody else" otherwise. */
    fun line(sealed: Boolean): String = if (sealed) SEALED_LINE else UNSEALED_LINE
}
