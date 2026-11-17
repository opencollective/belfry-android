package sh.belfry.core.model

import java.time.Instant
import java.time.LocalDate

// What Home, Calendar and Groups read (SPEC 19.31; iOS AppModel+Home.swift): my to-dos across
// channels, the events coming up and their state in words, a guest's last day.

/** A to-do and the channel or group it is in. */
data class TodoRef(val group: String, val task: Task)

/** Calendar's lists (members: mine, invited, discover; guests: public events). */
enum class CalendarList(val title: String) { MINE("My calendar"), INVITED("Invited"), DISCOVER("Discover"), PUBLIC("Public events") }

/** To-dos in the channels and groups I'm in: on me first, then open ones nobody took. */
fun World.myTodos(): Pair<List<TodoRef>, List<TodoRef>> {
    val onMe = ArrayList<TodoRef>()
    val open = ArrayList<TodoRef>()
    for (g in groups) {
        if (g.id == Charter.ID || !inChannel(g, me)) continue
        for (t in tasks(g.id)) {
            if (t.done) continue
            if (t.assignee == me) onMe.add(TodoRef(g.id, t)) else if (t.assignee == null) open.add(TodoRef(g.id, t))
        }
    }
    return onMe to open
}

fun World.isMine(e: CalEvent): Boolean {
    if (me in e.hosts) return true
    return when (myState(e)) {
        MyState.HOST, MyState.TICKET, MyState.MAYBE, MyState.APPLIED, MyState.WAITLIST -> true
        else -> myRsvp(e) == Rsvp.ACCEPTED || myRsvp(e) == Rsvp.TENTATIVE
    }
}

/** A meeting I was asked to and haven't answered. */
fun World.isInvited(e: CalEvent): Boolean = me in e.invited && myRsvp(e) == null && !isMine(e)

/** Events I'm part of (or invited to) in the next 7 days. */
fun World.comingUp(now: Long): List<CalEvent> {
    val until = dayOf(now).atStartOfDay(ctx.zone).toEpochSecond() + 8 * 86400
    return eventsIn(EventsTab.UPCOMING, now).filter { it.start < until && it.status != EventStatus.CANCELLED && (isMine(it) || isInvited(it)) }
}

/** One of Calendar's lists. PUBLIC_REMOTE: the belfry's public events a guest can't read from the relay. */
fun World.calendarList(list: CalendarList, now: Long, publicRemote: List<CalEvent> = emptyList()): List<CalEvent> {
    val all = eventsIn(EventsTab.UPCOMING, now).filter { it.status != EventStatus.CANCELLED || isMine(it) }
    return when (list) {
        CalendarList.MINE -> all.filter { isMine(it) }
        CalendarList.INVITED -> all.filter { isInvited(it) }
        CalendarList.DISCOVER -> all.filter { !isMine(it) && !isInvited(it) }
        CalendarList.PUBLIC -> {
            val mineIds = groups.filter { me in it.members || me in it.reading }.map { it.id }.toSet()
            val local = all.filter { e -> e.visibility == Visibility.PUBLIC || e.channels.any { it in mineIds } }
            val known = local.map { it.coord }.toSet()
            val start = dayOf(now).atStartOfDay(ctx.zone).toEpochSecond()
            (local + publicRemote.filter { it.coord !in known && endOf(it) >= start && it.status != EventStatus.CANCELLED }).sortedBy { it.start }
        }
    }
}

/** An event's state in words (no buttons on rows): "you host · check-in today", "going", "invited · not answered yet". */
fun World.eventStatus(e: CalEvent, now: Long): String {
    if (e.status == EventStatus.CANCELLED) return "cancelled"
    val today = dayOf(e.start) == dayOf(now)
    if (me in e.hosts) return if (today && e.isNew && !e.meeting) "you host · check-in today" else "you host"
    when (myState(e)) {
        MyState.TICKET -> return "going"
        MyState.MAYBE -> return "maybe"
        MyState.APPLIED -> return "applied"
        MyState.WAITLIST -> return "on the waitlist"
        MyState.CANT -> return "can't go"
        MyState.DECLINED -> return "not this time"
        else -> {}
    }
    myRsvp(e)?.let { return it.label.lowercase() }
    if (isInvited(e)) return "invited · not answered yet"
    if (e.visibility == Visibility.PUBLIC) return "open to all"
    val going = e.goingKeys.size
    return if (going > 0) "$going going" else "not answered yet"
}

/** Where an event is: its place, else "Online meeting" / "Meeting". */
fun World.eventWhere(e: CalEvent): String? {
    val l = e.location
    if (!l.isNullOrEmpty()) return l
    if (e.meeting) return if (e.links.isEmpty()) "Meeting" else "Online meeting"
    return null
}

/** The listed channels and groups with something unread (by the caller's count), newest activity first. */
fun World.unreadPlaces(unread: (String) -> Int): List<Group> =
    groups.filter { it.id != Charter.ID && !it.isEvent && unread(it.id) > 0 }.sortedByDescending { it.lastActivity }

/** A guest's last day (the `guest` tag of the newest 9000 that let me in), null if unknown. */
fun World.guestUntil(): LocalDate? {
    val s = all.filter { it.kind == 9000 && it.t("p") == me && (it.t("guest") ?: "").length == 10 }.maxWithOrNull(World.BY_TIME)?.t("guest") ?: return null
    return try { LocalDate.parse(s) } catch (e: Exception) { null }
}

/** "7 days left" / "1 day left" / "Last day" (null: unknown). */
fun World.guestDaysLeft(now: Long): String? {
    val u = guestUntil() ?: return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochSecond(now).atZone(ctx.zone).toLocalDate(), u)
    return when { days <= 0 -> "Last day"; days == 1L -> "1 day left"; else -> "$days days left" }
}

/** The channels a guest was let into. */
fun World.guestChannels(): List<Group> = groups.filter { (me in it.members || me in it.reading) && !it.isEvent && it.id != Charter.ID }

/** Members of the belfry (the Townsquare's people, guests and agents left out). */
fun World.memberKeys(): Set<String> = (group("general")?.members ?: emptySet()).filter { !isGuest(it) && !isAgent(it) }.toSet()

/**
 * A new member's first minutes (web welcomeDue): shown once on a device that saw the account's
 * guest become a member, when a charter exists and the person never ticked an agreement, not to an
 * agent or a steward.
 */
fun World.welcomeDue(sawGuest: Boolean): Boolean =
    sawGuest && !amGuest && !isAgent(me) && !isCommunityAdmin && charter().isNotEmpty() && !everTickedCharter()
