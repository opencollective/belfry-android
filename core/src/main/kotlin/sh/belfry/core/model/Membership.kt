package sh.belfry.core.model

import sh.belfry.core.outbox.Target

// Groups that accept by vouches (collective-belfry docs/membership.md "Groups that accept by
// vouches"; web requests.ts): a vouch is a kind 1985 `L belfry/vouch`, `l vouch|withdrawn`, `e` =
// the 9021, `p` = the applicant, `h` = the group; each author's latest counts.

/** The requests to one group that I can act on. */
data class GroupRequests(val group: Group, val list: List<Application>)

object Vouches {
    const val NS = "belfry/vouch"
}

/** Per request (the 9021's id): each author's latest vouch label ("vouch" / "withdrawn") and its time. */
fun World.vouchLabels(): Map<String, Map<String, Pair<String, Long>>> = vouchIndex

/** Who vouches for application A in group G now: members of G (not guests or agents), never the applicant. */
fun World.vouchesFor(g: Group, a: Application): List<String> {
    val m = vouchLabels()[a.ev.id] ?: return emptyList()
    return m.filter { (pk, x) -> x.first == "vouch" && pk != a.pubkey && pk in g.members && !isGuest(pk) && !isAgent(pk) }.keys.sorted()
}

/** Requests to group G I can act on: all when I'm one of its admins; while it accepts by vouches, its members see them to vouch. */
fun World.groupRequestsFor(g: Group): List<Application> {
    if (!g.closed || g.applications.isEmpty()) return emptyList()
    if (me in g.admins) return g.applications
    if (g.vouches > 0 && me in g.members && !isGuest(me) && !isAgent(me)) return g.applications.filter { it.pubkey != me }
    return emptyList()
}

fun World.groupRequests(): List<GroupRequests> = groups.map { GroupRequests(it, groupRequestsFor(it)) }.filter { it.list.isNotEmpty() }

/** Group requests waiting for me (admins: all; vouchers: the ones I haven't vouched for). */
fun World.groupRequestCount(): Int = groupRequests().sumOf { x ->
    if (me in x.group.admins) x.list.size else x.list.count { me !in vouchesFor(x.group, it) }
}

/** A vouch (or its withdrawal) for a request to join a closed group. */
fun Actions.vouch(groupId: String, requestId: String, applicant: String, on: Boolean = true): Draft =
    Draft(Target.GROUPS, 1985, tags = listOf(listOf("h", groupId), listOf("L", Vouches.NS), listOf("l", if (on) "vouch" else "withdrawn", Vouches.NS), listOf("e", requestId), listOf("p", applicant)))

/** The groups (pods) I'm a member or admin of (the Groups place: "My groups"). */
fun World.myPods(): List<Group> = groups.filter { it.isPod && (me in it.members || me in it.admins) }

/** Every listed pod (the Groups place: "All"). */
fun World.allPods(): List<Group> = groups.filter { it.isPod }

/** Mine with a message in the last 30 days ("Active"). */
fun World.activePods(now: Long): List<Group> = myPods().filter { now - it.lastActivity < 30 * 86400L }
