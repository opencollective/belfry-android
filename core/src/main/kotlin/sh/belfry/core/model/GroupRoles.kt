package sh.belfry.core.model

import sh.belfry.core.crypto.Hex
import sh.belfry.core.net.Community
import java.net.URI

// Roles in a group and check-ins (SPEC 19.29; the belfry's contract of 2026-11-15):
// - admin: the group's admins (World.groups: the relay's latest 39001, the creator, admins by
//   role). In an event the creator is the "Host", other admins "Co-host".
// - steward (shown as "Door", PlaceNames.DOOR): events only; may check people in. Kind 1985
//   `L belfry/group-role`, `l steward|none`, `p`, `h`, signed by the groups key; latest per `p` wins.
// - member: everyone else ("Attendee" in an event).
// Check-ins: kind 1985 `L belfry/ticket`, `l checked-in|not-checked-in`, `p`, `h ev-<d>`, signed by
// the groups key; latest per `p` wins. Only the groups key's labels count.

enum class GroupRole(val key: String) { ADMIN("admin"), STEWARD("steward"), MEMBER("member") }

/** A person's place in an event, as the UI names it. */
enum class EventRole(val label: String) {
    HOST("Host"), COHOST("Co-host"), STEWARD(PlaceNames.DOOR), ATTENDEE("Attendee");

    /** What `POST /group-roles` takes for this choice (the host is never picked). */
    val groupRole: GroupRole get() = when (this) {
        HOST, COHOST -> GroupRole.ADMIN
        STEWARD -> GroupRole.STEWARD
        ATTENDEE -> GroupRole.MEMBER
    }

    val maysCheckIn: Boolean get() = this != ATTENDEE
}

object GroupRoles {
    const val ROLE_NS = "belfry/group-role"
    const val TICKET_NS = "belfry/ticket"

    /** The value of the label in NS on E (`["l", value, ns]`), else null. */
    internal fun labelValue(e: sh.belfry.core.nostr.Event, ns: String): String? =
        e.tags.firstOrNull { it.size >= 2 && it[0] == "l" && (it.size < 3 || it[2] == ns) }?.get(1)
}

/** The groups key's latest label in NS per person in GROUP: pubkey → (value, time). */
internal fun World.latestGroupsKeyLabels(groupId: String, ns: String): Map<String, Pair<String, Long>> {
    val key = ctx.groupsKey
    if (key.isNullOrEmpty()) return emptyMap()
    val out = HashMap<String, Pair<String, Long>>()
    for (e in groupEventsSorted(groupId)) {
        if (e.kind != 1985 || e.pubkey != key || e.t("L") != ns) continue
        val p = e.t("p") ?: continue
        if (!Hex.isKey(p)) continue
        val v = GroupRoles.labelValue(e, ns) ?: continue
        val prev = out[p]
        if (prev != null && prev.second > e.createdAt) continue
        out[p] = v to e.createdAt
    }
    return out
}

/** Stewards ("Door") of an event's group: the groups key's latest label for them says "steward". */
fun World.stewards(groupId: String): Set<String> {
    if (World.typeOfId(groupId) != ChannelType.EVENT) return emptySet()
    return latestGroupsKeyLabels(groupId, GroupRoles.ROLE_NS).filterValues { it.first == "steward" }.keys
}

/** Keys that administer every group: the belfry's admins, the relay's key (the author of the group's 39000), the groups key. */
fun World.everywhere(groupId: String): Set<String> {
    val out = HashSet(ctx.communityAdmins)
    metaAuthors[groupId]?.let(out::add)
    ctx.groupsKey?.takeIf { it.isNotEmpty() }?.let(out::add)
    return out
}

/**
 * "Steward" for a channel's or group's own admin (the web's word since chat-source cbc57a3; events
 * keep Host / Co-host / Door); null for everyone else and for the keys that administer every group.
 */
fun World.adminWord(groupId: String, pubkey: String): String? =
    if (group(groupId)?.admins?.contains(pubkey) == true && pubkey !in everywhere(groupId)) PlaceNames.STEWARD else null

/** May I moderate GROUP on the relay myself (9000 / 9001 / 9002)? Its 39001 or creator, or a belfry admin. An admin by role goes through the belfry instead. */
fun World.mayModerate(g: Group?): Boolean = g != null && (me in g.relayAdmins || isCommunityAdmin)

/** PUBKEY's role in GROUP. */
fun World.groupRole(groupId: String, pubkey: String): GroupRole = when {
    group(groupId)?.admins?.contains(pubkey) == true -> GroupRole.ADMIN
    World.typeOfId(groupId) == ChannelType.EVENT && pubkey in stewards(groupId) -> GroupRole.STEWARD
    else -> GroupRole.MEMBER
}

/** Who is checked in at GROUP (an event's group): pubkey → when. */
fun World.checkIns(groupId: String): Map<String, Long> =
    latestGroupsKeyLabels(groupId, GroupRoles.TICKET_NS).filterValues { it.first == "checked-in" }.mapValues { it.value.second }

/** The latest check-in label per person (true: checked in) and its time, to lay over an older answer. */
fun World.ticketLabels(groupId: String): Map<String, Pair<Boolean, Long>> =
    latestGroupsKeyLabels(groupId, GroupRoles.TICKET_NS).mapValues { (it.value.first == "checked-in") to it.value.second }

/** PUBKEY's role in event E: its creator hosts, other admins co-host, then stewards. */
fun World.eventRole(e: CalEvent, pubkey: String): EventRole = when {
    pubkey == e.creator -> EventRole.HOST
    group(e.groupId)?.admins?.contains(pubkey) == true && pubkey !in everywhere(e.groupId) -> EventRole.COHOST
    pubkey in stewards(e.groupId) -> EventRole.STEWARD
    else -> EventRole.ATTENDEE
}

/** May I check people in at E, as the relay tells it (the API's `may_check_in` wins when known). */
fun World.mayCheckIn(e: CalEvent): Boolean {
    if (e.remote) return false
    return eventRole(e, me).maysCheckIn || group(e.groupId)?.admins?.contains(me) == true || isCommunityAdmin
}

/** From 00:00 of the event's first day to the end of its last day, in the context's zone. */
fun World.onEventDays(e: CalEvent, now: Long): Boolean {
    val first = dayOf(e.start).atStartOfDay(ctx.zone).toEpochSecond()
    var lastAt = endOf(e)
    // an all-day event's end date is the day after its last day
    if (e.allDay) { val end = e.end; if (end != null && end > e.start) lastAt = end - 1 }
    val after = dayOf(lastAt).plusDays(1).atStartOfDay(ctx.zone).toEpochSecond()
    return now >= first && now < after
}

/** The people of event E for its People tab: hosts (creator first, co-hosts, stewards), then going, then maybe, each by name. */
fun World.eventPeopleList(e: CalEvent): Triple<List<String>, List<String>, List<String>> {
    val st = stewards(e.groupId)
    val byName = compareBy<String> { displayName(it).lowercase() }
    val co = (group(e.groupId)?.admins ?: emptySet()).filter { it != e.creator && eventRole(e, it) == EventRole.COHOST }
    val hosts = listOf(e.creator) + co.sortedWith(byName) + st.filter { it != e.creator && it !in co }.sortedWith(byName)
    val taken = hosts.toSet()
    val going = e.goingKeys.filter { it !in taken }.sortedWith(byName)
    val maybe = e.rsvps.filter { it.value == Rsvp.TENTATIVE && it.key !in taken && it.key !in e.goingKeys }.keys.sortedWith(byName)
    return Triple(hosts.filter { it.isNotEmpty() }, going, maybe)
}

/** A ticket's code in a scanned text (SPEC 19.29): a URL whose path (or fragment) ends `/t/<16 base32>`. Nothing of the text is shown. */
object TicketScan {
    private val CODE = Regex("^[a-z2-7]{16}$")

    fun isCode(s: String): Boolean = CODE.matches(s)

    fun code(text: String): String? {
        val t = text.trim()
        if (t.length > 512) return null
        val u = try { URI(t) } catch (e: Exception) { return null }
        if (u.scheme != "https" && u.scheme != "http") return null
        var parts = (u.rawPath ?: "").split("/").filter { it.isNotEmpty() }
        val f = u.rawFragment
        if (f != null && (parts.isEmpty() || (u.rawPath ?: "").endsWith("/"))) parts = f.split("/").filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[parts.size - 2] != "t" || !isCode(parts.last())) return null
        return parts.last()
    }

    /** An event address opened from outside: https://chat.<domain>/events/<link>[/t/<code>] (or after `#`). Only the domain, the link and the code are read. */
    fun eventLink(url: String): Triple<String, String, String?>? {
        val u = try { URI(url.trim()) } catch (e: Exception) { return null }
        val host = u.host?.lowercase() ?: return null
        if (u.scheme != "https" || !host.startsWith("chat.")) return null
        var parts = (u.rawPath ?: "").split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) u.rawFragment?.let { f -> parts = f.split("/").filter { it.isNotEmpty() } }
        if ((parts.size != 2 && parts.size != 4) || parts[0] != "events") return null
        val link = parts[1]
        if (!EventLinks.SLUG.matches(link) && !EventLinks.D.matches(link)) return null
        var code: String? = null
        if (parts.size == 4) {
            if (parts[2] != "t" || !isCode(parts[3])) return null
            code = parts[3]
        }
        val domain = host.removePrefix("chat.")
        if (!Community.isDomain(domain)) return null
        return Triple(domain, link, code)
    }
}

