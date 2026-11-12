package sh.belfry.core.net

/**
 * A scanned QR code (the sign-in screen's "Scan an invitation"). Only an invitation link counts,
 * read by the same parser as a link the app is opened with ([InviteLink.parse]): its domain and
 * token. Nothing else of the code is used and none of its text is ever shown.
 */
object InviteScan {
    /** What the scanner says for any other code. */
    const val NOT_AN_INVITE = "This isn’t a Belfry invitation."

    /** A QR code holds at most a few thousand characters; an invitation link is short. */
    private const val MAX = 512

    fun read(text: String?): InviteLink? {
        if (text == null || text.length > MAX) return null
        return InviteLink.parse(text)
    }
}
