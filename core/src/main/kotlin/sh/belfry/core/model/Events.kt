package sh.belfry.core.model

import sh.belfry.core.crypto.Random
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.Tag
import sh.belfry.core.outbox.Target
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// Belfry events (collective-belfry work/events-design/PROTOCOL.md; the web chat's
// src/core/events.ts at belfry-chat ef9e1cc). See SPEC.md 19.14.
//
//   event         NIP-52 31922 / 31923 on the community relay, `d` = <year>-<6 of a-z0-9>, its
//                 chat = NIP-29 group ev-<d> (the hosts are its admins). Rule B: among the
//                 versions with that d signed by a host (the group's creator and admins), the
//                 newest counts; the coordinate stays the creator's.
//   ticket type   NIP-99 kind 30402, `d` = <event d>:<id>, `a` = the event, `h` = ev-<d>; no
//                 price in v1. None defined: the implicit free open ticket <d>:free.
//   holding one   kind 9000 in ev-<d> with `ticket` (a later 9001 takes it back)
//   application   kind 9021 in ev-<d>, `ticket`, content = the answer; `waitlist` when full
//   RSVP          NIP-52 31925 (`ticket` when one is held); a guest's goes on the groups relay with `h`
//   status        NIP-32 label belfry/event-status (cancelled | scheduled) by a host

enum class Visibility(val key: String, val label: String, val hint: String) {
    MEMBERS("members", "Members", "Only members see it"),
    LINK("link", "Link", "Anyone with the URL"),
    PUBLIC("public", "Public", "Listed on the website");

    companion object { fun of(key: String?): Visibility = entries.firstOrNull { it.key == key } ?: MEMBERS }
}

enum class EventStatus(val key: String) { SCHEDULED("scheduled"), CANCELLED("cancelled") }

/** An event a message shows as a card: by its coordinate (known here) or by its URL's last part. */
data class EventRef(val coord: String? = null, val link: String? = null) {
    val key: String get() = coord ?: link ?: ""
}

data class Ticket(
    /** `<event d>:<id>` */
    val d: String,
    val id: String,
    val title: String,
    val summary: String = "",
    val quantity: Int? = null,
    /** Needs an application (the hosts approve). */
    val closed: Boolean = false,
    /** The question applicants answer. */
    val prompt: String = "",
    val members: Boolean = false,
    val position: Int = 0,
    /** The free open ticket an event has when it defines none. */
    val implicit: Boolean = false,
    val ev: Event? = null,
    /** Places left as the belfry counted them (remote events). */
    val apiLeft: Int? = null,
)

data class EventApplication(val pubkey: String, val answer: String, val ticket: String?, val waitlist: Boolean, val at: Long, val ev: Event)

data class EventPeople(
    /** pubkey → ticket d */
    val holders: Map<String, String>,
    val applications: List<EventApplication>,
    val declined: Set<String>,
) {
    fun taken(ticketD: String): Int = holders.values.count { it == ticketD }
    /** Null: no limit. */
    fun placesLeft(t: Ticket): Int? = t.apiLeft ?: t.quantity?.let { maxOf(0, it - taken(t.d)) }

    companion object { val EMPTY = EventPeople(emptyMap(), emptyList(), emptySet()) }
}

/** Where I stand with an event (PROTOCOL.md §2), first match. */
enum class MyState(
    /** On rows and cards ("Going · 12 going"); null = nothing to say. */
    val label: String?,
    /** On the event's line. */
    val line: String?,
) {
    HOST("Hosting", null),
    TICKET("Going", "You’re going"),
    MAYBE("Maybe", "Maybe"),
    CANT("Can't go", "Can’t go"),
    APPLIED("Applied", "Application sent: the hosts will answer"),
    WAITLIST("Waitlist", "On the waitlist: the hosts will tell you"),
    DECLINED("Not this time", "The hosts could not give you a place"),
    NONE(null, null),
}

/** What the event's page lists: the four tabs of All events. */
enum class EventsTab(val label: String, val empty: String) {
    UPCOMING("Upcoming", "Nothing planned yet. Make the first event."),
    MINE("Mine", "You’re not going to anything yet."),
    HOSTING("Hosting", "You’re not hosting anything yet."),
    PAST("Past", "No past events."),
}

// ---- what the belfry's API answers (GET /api/v1/events/<link>) --------------------------------

data class ApiTicket(
    val d: String, val id: String, val title: String, val summary: String, val quantity: Int?, val left: Int?,
    val closed: Boolean, val prompt: String, val members: Boolean, val position: Int, val implicit: Boolean,
) {
    fun ticket(): Ticket = Ticket(d, id, title, summary, quantity, closed, prompt, members, position, implicit, null, left)
}

data class ApiMe(val host: Boolean, val ticket: String?, val applied: Boolean, val waitlist: Boolean, val declined: Boolean, val rsvp: Rsvp?, val member: Boolean)

data class ApiEvent(
    val d: String,
    val coord: String,
    val kind: Int,
    val group: String,
    val link: String,
    val url: String,
    val title: String,
    val description: String,
    val allDay: Boolean,
    val start: String?,
    val end: String?,
    val tz: String,
    val location: String,
    val online: Boolean,
    val links: List<String>,
    val cover: String?,
    val visibility: Visibility,
    val status: EventStatus,
    val statusReason: String,
    /** pubkey → name */
    val hosts: List<Pair<String, String>>,
    val tickets: List<ApiTicket>,
    val going: Int,
    val community: String,
    val me: ApiMe?,
) {
    /** The event in the app's own shape (for guests, and cards they get). */
    fun toCalEvent(zone: ZoneId, me: String): CalEvent {
        val parts = coord.split(":")
        val k = parts.getOrNull(0)?.toIntOrNull() ?: kind
        val creator = parts.getOrNull(1) ?: ""
        val isAllDay = k == 31922
        fun parse(v: String?): Long? {
            if (v.isNullOrEmpty()) return null
            return try {
                if (isAllDay) LocalDate.parse(v.take(10)).atStartOfDay(zone).toEpochSecond()
                else try { OffsetDateTime.parse(v).toEpochSecond() } catch (e: Exception) { Instant.parse(v).epochSecond }
            } catch (e: Exception) { null }
        }
        val fake = Event("api-$d", creator, 0, k, listOf(listOf("d", d)), description)
        val mine = this.me?.rsvp
        return CalEvent(
            coord = coord, d = d, ev = fake, title = title, description = description, location = location.ifEmpty { null },
            allDay = isAllDay, start = parse(start) ?: 0, end = parse(end), groupId = group, links = links, channels = emptyList(),
            rsvps = if (mine != null) mapOf(me to mine) else emptyMap(), tz = tz.ifEmpty { null }, isNew = true, creator = creator,
            hosts = hosts.map { it.first }.toSet(), cover = cover, visibility = visibility, slug = if (link != d) link else null,
            status = status, statusReason = statusReason.ifEmpty { null }, versions = listOf(fake), goingKeys = emptyList(), remote = true, goingCount = going,
        )
    }
}

/** The custom URL check (GET /events/slug). */
data class SlugCheck(val free: Boolean, val suggestion: String, val problem: String? = null)

/** What the event form says (make or edit). */
data class EventForm(
    val title: String,
    val description: String = "",
    val allDay: Boolean = false,
    /** Timed: Unix seconds. */
    val start: Long = 0,
    val end: Long? = null,
    /** All day: ISO dates. */
    val startDate: String = "",
    val endDate: String? = null,
    /** IANA zone of the times. */
    val tz: String = "UTC",
    val location: String = "",
    val online: String = "",
    val visibility: Visibility = Visibility.MEMBERS,
    val slug: String = "",
    /** A file server URL, uploaded before. */
    val cover: String? = null,
    /** Making an event: coming takes an application with this question (one 30402 ticket). Null: anyone RSVPs. */
    val applicationPrompt: String? = null,
)

data class TicketForm(
    val id: String? = null,
    val title: String,
    val summary: String = "",
    /** Digits, or "" for no limit. */
    val quantity: String = "",
    val closed: Boolean = false,
    val prompt: String = "",
    val members: Boolean = false,
    val position: Int = 0,
)

/** Ids, links and URL names. */
object EventLinks {
    val D = Regex("^\\d{4}-[a-z0-9]{6}$")
    val SLUG = Regex("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$")
    private val RESERVED = setOf("new", "slug", "feed", "past", "mine")
    const val STATUS_NS = "belfry/event-status"
    private const val ALPHA = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** A new event's id: the year it is made in (UTC) and 6 random characters. Never recomputed. */
    fun newD(now: Long, random: ByteArray = Random.bytes(6)): String {
        val year = Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).year
        return "$year-" + random.take(6).joinToString("") { ALPHA[(it.toInt() and 0xff) % 36].toString() }
    }

    /** Why a custom URL can't be used (fixed text), or null. */
    fun slugProblem(s: String): String? = when {
        !SLUG.matches(s) -> "3 to 40 of a–z, 0–9 and -"
        D.matches(s) -> "That is shaped like an event’s own id"
        s in RESERVED -> "That word is kept"
        else -> null
    }

    /** The URL name a title suggests (the web's slugOf). */
    fun slugOf(title: String): String =
        java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')

    /** Keeps what a URL name may hold while it is typed. */
    fun cleanSlug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9-]"), "").take(40)

    fun url(domain: String, link: String): String = "https://$domain/events/$link"

    /** https://<own domain>/events/<link> in a text: the links, at most three, in order. */
    fun linksIn(text: String, domain: String): List<String> {
        if (domain.isEmpty() || text.isEmpty()) return emptyList()
        val re = Regex("https://" + Regex.escape(domain) + "/events/([a-z0-9][a-z0-9-]{1,38}[a-z0-9])(?![a-z0-9-])")
        return re.findAll(text).map { it.groupValues[1] }.distinct().take(3).toList()
    }

    fun isEventKind(k: Int): Boolean = k == 31922 || k == 31923

    /** Every event coordinate a message's `a` tags name (up to three). */
    fun coordsOf(e: Event): List<String> =
        e.tags.filter { it.size >= 2 && it[0] == "a" && it[1].substringBefore(':').toIntOrNull()?.let(::isEventKind) == true }.map { it[1] }.distinct().take(3)

    /** Who holds a ticket, who applied, who was declined: from the event group's events (by time). */
    fun people(groupEvents: List<Event>): EventPeople {
        val put = HashMap<String, Event>()
        val out = HashMap<String, Long>()
        val decided = HashMap<String, Long>()
        val applied = LinkedHashMap<String, Event>()
        val left = HashMap<String, Long>()
        for (x in groupEvents) {
            val p = x.t("p")
            when (x.kind) {
                9000 -> if (!p.isNullOrEmpty()) { decided[p] = x.createdAt; if (!x.t("ticket").isNullOrEmpty()) put[p] = x }
                9001 -> if (!p.isNullOrEmpty()) { decided[p] = x.createdAt; out[p] = x.createdAt }
                9021 -> applied[x.pubkey] = x
                9022 -> left[x.pubkey] = x.createdAt
            }
        }
        val holders = LinkedHashMap<String, String>()
        for ((p, x) in put) if (x.createdAt > (out[p] ?: 0)) holders[p] = x.t("ticket")!!
        val apps = ArrayList<EventApplication>()
        for ((p, x) in applied) {
            if (p in holders || (decided[p] ?: 0) >= x.createdAt || (left[p] ?: 0) >= x.createdAt) continue
            apps.add(EventApplication(p, x.content, x.t("ticket"), x.tags.any { it.firstOrNull() == "waitlist" }, x.createdAt, x))
        }
        val declined = out.filter { (p, at) -> p !in holders && at >= (decided[p] ?: 0) }.keys
        return EventPeople(holders, apps.sortedBy { it.at }, declined)
    }

    fun freeTicket(d: String): Ticket = Ticket("$d:free", "free", "RSVP", implicit = true)
}

// ---- views on the world -------------------------------------------------------------------------

fun World.eventByCoord(coord: String): CalEvent? = calendar.firstOrNull { it.coord == coord }
fun World.eventByLink(link: String): CalEvent? = calendar.firstOrNull { it.d == link || it.slug == link }
/** The event by coordinate or by its URL's last part. */
fun World.eventByKey(key: String): CalEvent? = eventByCoord(key) ?: eventByLink(key)
fun World.eventUrl(e: CalEvent): String = EventLinks.url(ctx.domain, e.link)

fun World.eventPeople(e: CalEvent): EventPeople = if (e.remote) EventPeople.EMPTY else EventLinks.people(groupEventsSorted(e.groupId))

/** Its ticket types; the implicit free one when none is defined. */
fun World.tickets(e: CalEvent, api: ApiEvent? = null): List<Ticket> {
    if (api != null) return api.tickets.map { it.ticket() }.ifEmpty { listOf(EventLinks.freeTicket(e.d)) }
    if (e.remote) return listOf(EventLinks.freeTicket(e.d))
    val list = all
    val latest = LinkedHashMap<String, Event>()
    for (x in list) {
        if (x.kind != 30402 || x.t("a") != e.coord || x.pubkey !in e.hosts) continue
        val d = x.t("d") ?: continue
        if (!d.startsWith(e.d + ":")) continue
        val had = latest[d]
        if (had == null || had.createdAt < x.createdAt) latest[d] = x
    }
    fun gone(d: String, at: Long) = list.any { x -> x.kind == 5 && x.pubkey in e.hosts && x.createdAt >= at && x.tags.any { it.size >= 2 && it[0] == "a" && it[1].endsWith(":$d") } }
    val out = ArrayList<Ticket>()
    for ((d, x) in latest) {
        if (gone(d, x.createdAt)) continue
        val q = x.t("quantity")?.toIntOrNull()
        out.add(Ticket(
            d = d, id = d.substring(e.d.length + 1), title = x.t("title").orEmpty().ifEmpty { "Ticket" }, summary = x.t("summary") ?: "",
            quantity = q?.takeIf { it >= 0 }, closed = x.tags.any { it.firstOrNull() == "closed" }, prompt = x.t("prompt") ?: "",
            members = x.tags.any { it.firstOrNull() == "members" }, position = x.t("position")?.toIntOrNull() ?: 0, ev = x,
        ))
    }
    return if (out.isEmpty()) listOf(EventLinks.freeTicket(e.d)) else out.sortedWith(compareBy<Ticket> { it.position }.thenBy { it.title })
}

/** Going: ticket holders who did not say otherwise, and RSVPs on the free ticket. */
fun goingList(e: CalEvent, p: EventPeople): List<String> {
    val keys = LinkedHashSet<String>(p.holders.keys)
    for ((k, s) in e.rsvps) if (s == Rsvp.ACCEPTED) keys.add(k)
    return keys.filter { (e.rsvps[it] ?: Rsvp.ACCEPTED) == Rsvp.ACCEPTED }
}

fun World.myState(e: CalEvent, p: EventPeople = eventPeople(e), api: ApiEvent? = null): MyState {
    val a = api?.me
    val rsvp = e.rsvps[me] ?: a?.rsvp
    if (me in p.declined || a?.declined == true) return MyState.DECLINED
    if (me in p.holders || a?.ticket != null) return when (rsvp) { Rsvp.TENTATIVE -> MyState.MAYBE; Rsvp.DECLINED -> MyState.CANT; else -> MyState.TICKET }
    val app = p.applications.firstOrNull { it.pubkey == me }
    if (app != null || a?.applied == true) return if (app?.waitlist == true || a?.waitlist == true) MyState.WAITLIST else MyState.APPLIED
    return when (rsvp) {
        Rsvp.ACCEPTED -> MyState.TICKET
        Rsvp.TENTATIVE -> MyState.MAYBE
        Rsvp.DECLINED -> MyState.CANT
        null -> if (me in e.hosts) MyState.HOST else MyState.NONE
    }
}

/** "Going" / "Hosting" / "Applied"… for rows and cards; null when I said nothing. */
fun World.stateLabel(e: CalEvent): String? = if (e.isNew) myState(e).label else e.rsvps[me]?.label

/** May I host it: its hosts, and the community's admins for events read from the relay. */
fun World.isHost(e: CalEvent): Boolean = me in e.hosts || (isCommunityAdmin && !e.remote)

/** "Still going?": my RSVP predates a change of the date or the place. */
fun World.stillGoing(e: CalEvent): Boolean {
    val mine = e.rsvpEvents[me] ?: return false
    if (e.rsvps[me] == Rsvp.DECLINED) return false
    val said = mine.t("e")
    val base = e.versions.firstOrNull { it.id == said } ?: e.versions.lastOrNull { it.createdAt <= mine.createdAt } ?: return false
    fun key(v: Event) = listOf(v.t("start"), v.t("end"), v.t("location")).joinToString("|")
    return key(base) != key(e.ev)
}

fun World.endOf(e: CalEvent): Long = e.end ?: e.start
fun World.isPast(e: CalEvent, now: Long): Boolean = endOf(e) < dayOf(now).atStartOfDay(ctx.zone).toEpochSecond()
fun World.ended(e: CalEvent, now: Long): Boolean = endOf(e) < now
/** Taking a place still makes sense: not cancelled, not over. */
fun World.isOpen(e: CalEvent, now: Long): Boolean = e.status != EventStatus.CANCELLED && !ended(e, now)

/** All events: one tab (of one channel's cards, or of all). */
fun World.eventsIn(tab: EventsTab, now: Long, channel: String? = null): List<CalEvent> {
    val list = calendar.filter { channel == null || channel in it.channels }
    fun mine(e: CalEvent) = myState(e).let { it != MyState.NONE && it != MyState.DECLINED && it != MyState.CANT }
    return when (tab) {
        EventsTab.PAST -> list.filter { isPast(it, now) }.reversed()
        EventsTab.UPCOMING -> list.filter { !isPast(it, now) }
        EventsTab.MINE -> list.filter { !isPast(it, now) && mine(it) }
        EventsTab.HOSTING -> list.filter { !isPast(it, now) && me in it.hosts }
    }
}

/** In the drawer: the events I host or have a place at, upcoming and up to 30 days after. */
fun World.yourEvents(now: Long): List<CalEvent> {
    val since = now - 30 * 86400L
    return calendar.filter { e ->
        e.isNew && endOf(e) >= since && (me in e.hosts || myState(e) in setOf(MyState.HOST, MyState.TICKET, MyState.MAYBE, MyState.APPLIED, MyState.WAITLIST))
    }
}

/** The event whose chat this group is. */
fun World.eventOfGroup(groupId: String): CalEvent? = calendar.firstOrNull { it.groupId == groupId }

/** The `a` tags of the events a text links to (those this device knows). */
fun World.eventTagsFor(text: String, mainRelay: String, known: (String) -> String? = { null }): List<Tag> =
    EventLinks.linksIn(text, ctx.domain).mapNotNull { l -> eventByLink(l)?.coord ?: known(l) }.distinct().map { listOf("a", it, mainRelay) }

// ---- the events each action queues ---------------------------------------------------------------

object EventActions {
    private fun versionTags(d: String, n: EventForm, groupsRelay: String, extra: List<Tag> = emptyList()): Pair<Int, List<Tag>> {
        val tags = ArrayList<Tag>()
        tags.add(listOf("d", d)); tags.add(listOf("title", n.title.jsTrim())); tags.add(listOf("h", "ev-$d", groupsRelay)); tags.add(listOf("visibility", n.visibility.key))
        if (n.allDay) {
            tags.add(listOf("start", n.startDate.take(10)))
            if (!n.endDate.isNullOrEmpty()) tags.add(listOf("end", n.endDate.take(10)))
        } else {
            tags.add(listOf("start", n.start.toString())); tags.add(listOf("start_tzid", n.tz))
            if (n.end != null) { tags.add(listOf("end", n.end.toString())); tags.add(listOf("end_tzid", n.tz)) }
        }
        val where = n.location.jsTrim()
        if (where.isNotEmpty()) tags.add(listOf("location", where))
        val online = n.online.jsTrim()
        if (online.startsWith("https://")) tags.add(listOf("r", online))
        if (!n.cover.isNullOrEmpty()) tags.add(listOf("image", n.cover))
        if (n.slug.isNotEmpty()) tags.add(listOf("slug", n.slug))
        return (if (n.allDay) 31922 else 31923) to (tags + extra)
    }

    private fun about(description: String): String = description.split("\n")[0].sliceUnits(200).ifEmpty { "The event’s chat" }

    /**
     * A new event: its group (ev-<d>, me as creator and admin), then the NIP-52 event; with an
     * application question, one ticket that takes applications (and the group closed). No post in
     * any channel: sending its URL in one shows its card there. Returns the drafts.
     */
    fun create(n: EventForm, d: String, me: String, groupsRelay: String, mainRelay: String, ticketId: String = Random.hex(3)): List<Draft> {
        val groupId = "ev-$d"
        val closed = n.applicationPrompt != null
        val out = ArrayList<Draft>()
        out.add(Draft(Target.GROUPS, 9007, tags = listOf(listOf("h", groupId))))
        out.add(Draft(Target.GROUPS, 9002, tags = listOf(listOf("h", groupId), listOf("name", n.title.jsTrim()), listOf("about", about(n.description))) +
            (if (!n.cover.isNullOrEmpty()) listOf(listOf("picture", n.cover)) else emptyList()) + listOf(listOf(if (closed) "closed" else "open"))))
        val (kind, tags) = versionTags(d, n, groupsRelay)
        out.add(Draft(Target.MAIN, kind, n.description, tags))
        if (closed) {
            val coord = "$kind:$me:$d"
            out.add(ticketDraft(d, coord, groupId, TicketForm(ticketId, "Application", closed = true, prompt = n.applicationPrompt.orEmpty(), position = 1), mainRelay))
        }
        return out
    }

    /** A host's new version (rule B), and the group's name / picture when they changed. */
    fun edit(e: CalEvent, n: EventForm, g: Group?, groupsRelay: String): List<Draft> {
        val (kind, tags) = versionTags(e.d, n, groupsRelay, if (e.status == EventStatus.CANCELLED) listOf(listOf("status", "cancelled")) else emptyList())
        val out = arrayListOf(Draft(Target.MAIN, kind, n.description, tags))
        if (g != null && (g.name != n.title.jsTrim() || (!n.cover.isNullOrEmpty() && g.picture != n.cover)))
            out.add(Draft(Target.GROUPS, 9002, tags = listOf(listOf("h", e.groupId), listOf("name", n.title.jsTrim()), listOf("about", g.about)) +
                (if (!n.cover.isNullOrEmpty()) listOf(listOf("picture", n.cover)) else emptyList())))
        return out
    }

    /** Cancel (with a line to the guests) or reopen: a label in the group and a new version. */
    fun setStatus(e: CalEvent, status: EventStatus, reason: String = ""): List<Draft> {
        val tags = e.ev.tags.filter { it.firstOrNull() != "status" && it.firstOrNull() != "client" }
        return listOf(
            Draft(Target.GROUPS, 1985, reason.sliceUnits(500), listOf(listOf("h", e.groupId), listOf("L", EventLinks.STATUS_NS), listOf("l", status.key, EventLinks.STATUS_NS), listOf("a", e.coord))),
            Draft(Target.MAIN, e.ev.kind, e.ev.content, if (status == EventStatus.CANCELLED) tags + listOf(listOf("status", "cancelled")) else tags),
        )
    }

    private fun ticketDraft(d: String, coord: String, groupId: String, t: TicketForm, mainRelay: String): Draft {
        val id = t.id ?: Random.hex(3)
        val tags = arrayListOf(listOf("d", "$d:$id"), listOf("a", coord, mainRelay), listOf("h", groupId), listOf("title", t.title.jsTrim().ifEmpty { "Ticket" }), listOf("t", "ticket"), listOf("position", t.position.toString()))
        val summary = t.summary.jsTrim()
        if (summary.isNotEmpty()) tags.add(listOf("summary", summary))
        val q = t.quantity.trim()
        if (q.isNotEmpty() && q.all { it.isDigit() }) tags.add(listOf("quantity", q))
        if (t.closed) { tags.add(listOf("closed")); tags.add(listOf("prompt", t.prompt.jsTrim().ifEmpty { "Why would you like to come?" })) }
        if (t.members) tags.add(listOf("members"))
        return Draft(Target.MAIN, 30402, summary, tags)
    }

    /** Save a ticket type (no price in v1); a ticket that takes applications closes the event's group. */
    fun saveTicket(e: CalEvent, t: TicketForm, existing: List<Ticket>, g: Group?, mainRelay: String, newId: String = Random.hex(3)): List<Draft> {
        val form = if (t.id == null) t.copy(id = newId) else t
        val out = arrayListOf(ticketDraft(e.d, e.coord, e.groupId, form, mainRelay))
        val closing = form.closed || existing.any { it.closed && !it.implicit && it.id != form.id }
        if (g != null && g.closed != closing)
            out.add(Draft(Target.GROUPS, 9002, tags = listOf(listOf("h", e.groupId), listOf("name", g.name), listOf("about", g.about), listOf(if (closing) "closed" else "open"))))
        return out
    }

    fun deleteTicket(t: Ticket): Draft? {
        val x = t.ev ?: return null
        return Draft(Target.MAIN, 5, tags = listOf(listOf("a", "30402:${x.pubkey}:${t.d}"), listOf("e", x.id)))
    }

    /**
     * RSVP. A member's goes on the community relay (NIP-52); a guest's (or one for an event read
     * from the API) on the groups relay in the event's group ([onGroups]).
     */
    fun rsvp(e: CalEvent, status: Rsvp, me: String, ticketD: String? = null, onGroups: Boolean = false, eventPending: Boolean = false): Draft {
        val parts = e.coord.split(":")
        val author = parts.getOrElse(1) { "" }
        val d = parts.getOrElse(2) { "" }
        val tags = ArrayList<Tag>()
        tags.add(listOf("d", sha256Hex("$me:$author:$d").substring(0, 30))); tags.add(listOf("a", e.coord))
        if (!eventPending && !e.remote) tags.add(listOf("e", e.ev.id))
        tags.add(listOf("status", status.key)); tags.add(listOf("p", author))
        if (ticketD != null) tags.add(listOf("ticket", ticketD))
        if (onGroups) tags.add(listOf("h", e.groupId))
        return Draft(if (onGroups) Target.GROUPS else Target.MAIN, 31925, tags = tags)
    }

    /** An application (or a place on the waitlist): kind 9021 in the event's group. */
    fun apply(e: CalEvent, t: Ticket, answer: String, waitlist: Boolean): Draft =
        Draft(Target.GROUPS, 9021, answer.sliceUnits(2000), listOf(listOf("h", e.groupId), listOf("ticket", t.d)) + if (waitlist) listOf(listOf("waitlist")) else emptyList())

    fun withdraw(e: CalEvent): Draft = Draft(Target.GROUPS, 9022, tags = listOf(listOf("h", e.groupId)))

    /** A host approves (the ticket: a 9000 with `ticket`) or declines (9001). */
    fun decide(e: CalEvent, pubkey: String, approve: Boolean, ticketD: String? = null, application: Event? = null): Draft =
        if (!approve) Draft(Target.GROUPS, 9001, tags = listOf(listOf("h", e.groupId), listOf("p", pubkey)))
        else Draft(Target.GROUPS, 9000, tags = listOf(
            listOf("h", e.groupId), listOf("p", pubkey, "member"), listOf("ticket", ticketD ?: "${e.d}:free"), listOf("paid", "free"), listOf("a", e.coord),
        ) + (if (application != null) listOf(listOf("e", application.id)) else emptyList()))

    /** Make someone a co-host: an admin of the event's group. */
    fun addCoHost(e: CalEvent, pubkey: String): Draft = Draft(Target.GROUPS, 9000, tags = listOf(listOf("h", e.groupId), listOf("p", pubkey, "admin")))
}
