package sh.belfry.core.model

import sh.belfry.core.crypto.Hex
import sh.belfry.core.nostr.Tag
import sh.belfry.core.outbox.Target

/** Someone who isn't on the belfry, added to a meeting by name (an account is made for them). */
data class Named(val name: String, val email: String? = null)

/** What the meeting form says. Times in Unix seconds, set in the zone [tz]. */
data class MeetingForm(
    val topic: String,
    /** The members picked (their keys). */
    val people: List<String> = emptyList(),
    val named: List<Named> = emptyList(),
    val start: Long,
    val minutes: Int = 60,
    val place: String = "",
    val online: String = "",
    val tz: String = "UTC",
)

/**
 * Meetings (web core/meetings.ts, 2026-11-12): a members-only event made in a few taps from a
 * channel's ＋. Under the hood a Belfry event (SPEC 19.18), so it shows in upcoming events and has
 * its own chat and RSVPs:
 *  - the event (NIP-52) carries `["t","meeting"]`, `["channel",<where it was made>]` and one
 *    `["p",<pubkey>,"","invited"]` per person invited; visibility members;
 *  - its chat (`ev-<d>`) is closed, the invited are put in it (kind 9000, no ticket);
 *  - attendance: a host marks someone there or not (a NIP-32 label in the chat, namespace
 *    `belfry/attendance`, `l` = attended | absent, `p` the person, `a` the event).
 */
object Meetings {
    const val ATTENDANCE_NS = "belfry/attendance"
    val LENGTHS = listOf(30, 45, 60, 90, 120)
    val EMAIL = Regex("^\\S+@\\S+\\.\\S+$")

    /** "45 min", "1 h", "1 h 30". */
    fun lengthLabel(m: Int): String = if (m < 60) "$m min" else "${m / 60} h" + (if (m % 60 != 0) " ${m % 60}" else "")

    fun isEmail(s: String): Boolean = EMAIL.matches(s.trim())

    /**
     * Who the form's "+" adds, from its two steps: WHO is a name or an e-mail, MORE the other one
     * (optional). An address alone gets the part before its @ as the name.
     */
    fun named(who: String, more: String): Named {
        val a = who.trim()
        val b = more.trim()
        return if (isEmail(a)) Named(b.ifEmpty { a.substringBefore('@') }, a) else Named(a, b.takeIf { isEmail(it) })
    }

    private fun invitedTags(keys: List<String>): List<Tag> = keys.map { listOf("p", it, "", "invited") }

    /** The form as the event it makes. */
    fun eventForm(m: MeetingForm): EventForm = EventForm(
        title = m.topic.jsTrim(), description = "", allDay = false, start = m.start, end = m.start + m.minutes * 60L, tz = m.tz,
        location = m.place.jsTrim(), online = m.online.jsTrim(), visibility = Visibility.MEMBERS, slug = "", closed = true,
    )

    /** A new meeting made from CHANNEL: the event and its closed chat, then each member invited put in the chat. */
    fun create(channel: String, m: MeetingForm, d: String, me: String, groupsRelay: String, mainRelay: String): List<Draft> {
        val people = m.people.distinct().filter { it != me && Hex.isKey(it) }
        val extra = listOf(listOf("t", "meeting"), listOf("channel", channel)) + invitedTags(people)
        return EventActions.create(eventForm(m), d, me, groupsRelay, mainRelay, extra = extra) +
            people.map { Draft(Target.GROUPS, 9000, tags = listOf(listOf("h", "ev-$d"), listOf("p", it, "member"))) }
    }

    /** Invite more people to meeting E: into its chat, and on the event (a new version). Empty when all are invited already. */
    fun invite(e: CalEvent, keys: List<String>, g: Group?, groupsRelay: String, zone: String): List<Draft> {
        val fresh = keys.distinct().filter { it !in e.invited && Hex.isKey(it) }
        if (fresh.isEmpty()) return emptyList()
        return fresh.map { Draft(Target.GROUPS, 9000, tags = listOf(listOf("h", e.groupId), listOf("p", it, "member"))) } +
            EventActions.edit(e, formOf(e, zone), g, groupsRelay, invitedTags(fresh))
    }

    /** A host marks PUBKEY as there (ON) or not. */
    fun checkIn(e: CalEvent, pubkey: String, on: Boolean): Draft = Draft(
        Target.GROUPS, 1985, "",
        listOf(listOf("h", e.groupId), listOf("L", ATTENDANCE_NS), listOf("l", if (on) "attended" else "absent", ATTENDANCE_NS), listOf("p", pubkey), listOf("a", e.coord)),
    )

    /** An event as the form that would make it again (for a new version that changes only its tags). */
    fun formOf(e: CalEvent, zone: String): EventForm = EventForm(
        title = e.title, description = e.description, allDay = e.allDay, start = e.start, end = e.end,
        startDate = if (e.allDay) e.ev.t("start").orEmpty() else "", endDate = if (e.allDay) e.ev.t("end") else null,
        tz = e.tz ?: zone, location = e.location.orEmpty(), online = e.links.firstOrNull().orEmpty(),
        visibility = e.visibility, slug = e.slug.orEmpty(), cover = e.cover,
    )
}

/** Who the hosts marked as there (true) or not (false): the latest label per person. */
fun World.attendance(e: CalEvent): Map<String, Boolean> {
    val out = LinkedHashMap<String, Pair<Long, Boolean>>()
    for (x in groupEventsSorted(e.groupId)) {
        if (x.kind != 1985 || x.t("L") != Meetings.ATTENDANCE_NS || x.pubkey !in e.hosts) continue
        val p = x.t("p") ?: continue
        val l = x.tags.firstOrNull { it.isNotEmpty() && it[0] == "l" }?.getOrNull(1)
        if (l != "attended" && l != "absent") continue
        val had = out[p]
        if (had == null || had.first <= x.createdAt) out[p] = x.createdAt to (l == "attended")
    }
    return out.mapValues { it.value.second }
}

/** A meeting's people: who made it, everyone invited, and whoever answered. */
fun World.meetingPeople(e: CalEvent): List<String> = (listOf(e.creator) + e.invited + e.rsvps.keys).distinct()

/** The name-only accounts I made for a meeting: mine to hand over (the belfry checks it too). */
fun World.heldByMe(e: CalEvent): Set<String> = guestJoins(e.groupId).filter { it.unsaved && it.by == me }.map { it.pubkey }.toSet()

/** What a person's row of a meeting says under the name. */
fun World.meetingRole(e: CalEvent, pubkey: String, there: Map<String, Boolean>): String = when {
    there[pubkey] == true -> "There"
    e.rsvps[pubkey] != null -> e.rsvps.getValue(pubkey).label
    pubkey in e.hosts -> "Host"
    else -> "Invited"
}

/**
 * People to suggest first for a meeting made from CHANNEL: the most recently active there, then
 * in its parent, then their members; me left out.
 */
fun World.meetingSuggestions(channel: String, now: Long): List<String> {
    val g = group(channel)
    val parent = g?.parent?.let { group(it) }
    val keys = recentlyActive(channel, now).map { it.pubkey } +
        (parent?.let { p -> recentlyActive(p.id, now).map { it.pubkey } } ?: emptyList()) +
        (g?.members ?: emptySet()) + (parent?.members ?: emptySet())
    return keys.distinct().filter { it != me && Hex.isKey(it) }
}

/** Anyone whose name matches Q (the form's "+"), suggestions first; no Q: the suggestions. */
fun World.findPeople(channel: String, q: String, now: Long, limit: Int = 8): List<String> {
    val s = q.trim().lowercase()
    val first = meetingSuggestions(channel, now)
    if (s.isEmpty()) return first.take(limit)
    val pool = (first + profiles.keys).distinct().filter { it != me }
    return pool.filter { displayName(it).lowercase().contains(s) || (profiles[it]?.name ?: "").lowercase().contains(s) }.take(limit)
}

/** The place of my last meeting (then of my last event): the form's default. */
fun World.lastPlace(): String {
    val mine = calendar.filter { it.creator == me && !it.location.isNullOrBlank() }.sortedByDescending { it.ev.createdAt }
    return (mine.firstOrNull { it.meeting } ?: mine.firstOrNull())?.location.orEmpty()
}

/** Where meetings were held lately (the form's chips), most recent first, at most five. */
fun World.meetingPlaces(): List<String> =
    calendar.filter { it.meeting }.sortedByDescending { it.ev.createdAt }.mapNotNull { it.location?.takeIf { l -> l.isNotBlank() } }.distinct().take(5)
