package sh.belfry.core.model

// Who someone is here and what they can read (the web chat's core/invite.ts isGuest / isAgent /
// inChannel and access.ts agentOwner, village-app 1834584 + 453684b).

import sh.belfry.core.net.encodeURIComponent

/** Whose agent PUBKEY is, when it is a member's own agent (belfry.json `agents`). */
fun World.agentOwner(pubkey: String): String? = ctx.agents[pubkey]

/** Someone who is in this belfry only as a channel's guest (a person or an agent), not a member's own agent. */
fun World.isGuest(pubkey: String): Boolean = agentOwner(pubkey) == null && guestJoinsOf(pubkey).isNotEmpty()

/** An agent (a channel's guest, or a member's own), not a person. */
fun World.isAgent(pubkey: String): Boolean = agentOwner(pubkey) != null || groups.any { g -> guestJoins(g.id).any { it.pubkey == pubkey && it.agent } }

/**
 * Whether PUBKEY can read channel G: an open channel is every member's (and its own guests'); a
 * closed one (a pod by application) only its members' and admins'. Unknown channel → true.
 */
fun World.inChannel(g: Group?, pubkey: String): Boolean {
    if (g == null) return true
    if (pubkey in g.members || pubkey in g.admins) return true
    if (g.closed) return false
    return !isGuest(pubkey)
}

/** What someone is, when not a plain member, as the invite search says it: "Kofi's agent",
 *  "Agent, a guest", "Guest"; null for a member. */
fun World.kindLabel(pubkey: String): String? {
    val owner = agentOwner(pubkey)
    return when {
        owner != null -> "${displayName(owner)}'s agent"
        isGuest(pubkey) -> if (isAgent(pubkey)) "Agent, a guest" else "Guest"
        else -> null
    }
}

/** The people the invite search finds: everyone here with a name (members, guests, agents), me
 *  left out; a word of the name starts with the query; at most 5. */
fun World.inviteCandidates(query: String, limit: Int = 5): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return people.filter { (_, name) -> name.lowercase().split(Regex("\\s+")).any { it.startsWith(q) } }.take(limit).map { it.first }
}

/** The web chat's link to a channel, as a member invite carries it: https://chat.<domain>/#/c/<id>. */
fun World.channelUrl(id: String): String = "https://chat.${ctx.domain}/#/c/" + encodeURIComponent(id)

/** The direct message a member invite sends (my name as others know it, never "You"). [asked]:
 *  a closed pod I do not administer, they still have to apply. */
fun World.inviteText(groupId: String, asked: Boolean): String {
    val g = group(groupId)
    val name = if (g == null) groupId else if (g.isPod || g.isEvent || g.isProposal) g.name else "# " + g.name
    val from = profiles[me]?.name?.takeIf { it.isNotEmpty() } ?: "A member"
    val url = channelUrl(groupId)
    return if (asked) "$from thinks you should join $name. It takes an application: $url" else "$from invited you to $name: $url"
}
