package sh.belfry.core.model

/**
 * Followers, not members (SPEC 19.78; Xavier 2026-11-23 via the lead; Channels canvas v5 note
 * n-followers; web 75810d1 model.ts `followsIt` / `peopleCount`): in a channel (a topic) its people
 * are followers; groups, events and proposals keep members. The belfry's own members stay members
 * everywhere ("Members only", the Members place, "a member of <belfry>").
 */
object Followers {
    /** A channel (a topic): not a group, not an event's chat, not a proposal's. */
    fun followsIt(g: Group?): Boolean = g != null && !g.isPod && !g.isEvent && !g.isProposal

    fun count(follows: Boolean, n: Int): String =
        if (follows) "$n ${if (n == 1) "follower" else "followers"}" else "$n ${if (n == 1) "member" else "members"}"

    /** "12 followers" in a channel, "8 members" elsewhere. */
    fun peopleCount(g: Group?, n: Int): String = count(followsIt(g), n)

    /** The pull-down's row and its screen's title: "Followers" for a channel; OTHERWISE elsewhere. */
    fun title(g: Group?, otherwise: String = "Members"): String = if (followsIt(g)) "Followers" else otherwise

    /** Over the requests of a closed channel or group. */
    fun asking(g: Group?): String = if (followsIt(g)) "Asking to follow" else "Asking to join"

    /** The bar of a closed channel or group I am not in, before I asked. */
    fun askLine(g: Group): String = if (followsIt(g)) "People ask to follow this channel." else "People ask to join this ${if (g.isPod) "group" else "channel"}."

    /** Its button. */
    fun askButton(g: Group?): String = if (followsIt(g)) "Ask to follow" else "Ask to join"

    /** The ask sheet's title: "Ask to follow #tools", "Ask to join Finance". */
    fun askTitle(g: Group): String = if (followsIt(g)) "Ask to follow #${g.name}" else "Ask to join ${if (g.isPod) g.name else "#" + g.name}"

    /** Who can read it → Closed, the line under it. */
    fun closedLine(g: Group?): String = if (g == null || followsIt(g)) "People ask to follow. A steward of this channel approves." else "People ask to join. A steward of this ${if (g.isPod) "group" else "channel"} approves."

    /** The invite list's word for someone who is here already (web Invite.tsx). */
    fun alreadyHere(g: Group?, guestHere: Boolean): String = when {
        followsIt(g) && !guestHere -> "Already following"
        g?.closed == true || guestHere -> "Already in"
        else -> "Already a member"
    }

    /** An internal comment in an e-mail thread: who sees it (web MailPost.tsx). */
    fun onlySee(g: Group?, where: String): String = "Only ${if (followsIt(g)) "followers" else "members"} of $where see this."
}
