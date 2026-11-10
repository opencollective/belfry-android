package sh.belfry.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import sh.belfry.core.net.EventsApi
import sh.belfry.core.nostr.Event
import sh.belfry.core.outbox.Target
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Belfry events (SPEC 19.14): rule B, tickets, the people of an event, where I stand, cards from URLs. */
class EventsTest {
    private val alice = "a".repeat(64) // creator
    private val bob = "b".repeat(64) // co-host
    private val carol = "c".repeat(64) // a member
    private val dave = "d".repeat(64) // applies
    private val eve = "e".repeat(64) // not a host
    private val groupsKey = "9".repeat(64)
    private var n = 0
    private val d = "2026-abc123"
    private val g = "ev-$d"

    private fun ev(pk: String, at: Long, kind: Int, content: String = "", tags: List<List<String>>): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags, content)
    }
    private fun draft(pk: String, at: Long, x: Draft) = ev(pk, at, x.kind, x.content, x.tags)
    private fun world(events: List<Event>, me: String = alice, admins: Set<String> = emptySet()) =
        World(events, emptyList(), WorldContext(me = me, domain = "example.org", communityName = "Example", communityAdmins = admins, groupsKey = groupsKey, zone = ZoneId.of("UTC")))

    private val t0 = 1_793_000_000L
    private val form = EventForm(title = "Garden day", description = "We plant beans.", start = t0 + 5 * 86400, end = t0 + 5 * 86400 + 3600, tz = "Europe/Brussels", location = "The garden", visibility = Visibility.PUBLIC, slug = "garden-day")

    private fun base(prompt: String? = null): List<Event> =
        EventActions.create(form.copy(applicationPrompt = prompt), d, alice, "wss://g", "wss://m", ticketId = "app").mapIndexed { i, x -> draft(alice, t0 + i, x) }

    @Test
    fun creatingWritesTheGroupTheEventAndAnApplicationTicket() {
        val drafts = EventActions.create(form.copy(applicationPrompt = "Why?"), d, alice, "wss://g", "wss://m", ticketId = "app")
        assertEquals(listOf(9007, 9002, 31923, 30402), drafts.map { it.kind })
        assertEquals(listOf(Target.GROUPS, Target.GROUPS, Target.MAIN, Target.MAIN), drafts.map { it.target })
        assertTrue(drafts[1].tags.contains(listOf("closed")))
        val v = drafts[2].tags
        assertTrue(v.contains(listOf("d", d)) && v.contains(listOf("h", g, "wss://g")) && v.contains(listOf("visibility", "public")) && v.contains(listOf("slug", "garden-day")))
        assertTrue(v.contains(listOf("start", (t0 + 5 * 86400).toString())) && v.contains(listOf("start_tzid", "Europe/Brussels")))
        assertTrue(drafts[3].tags.contains(listOf("d", "$d:app")) && drafts[3].tags.contains(listOf("prompt", "Why?")) && drafts[3].tags.contains(listOf("closed")))
        assertEquals(1, EventActions.create(form, d, alice, "wss://g", "wss://m").count { it.kind == 9002 && it.tags.contains(listOf("open")) })

        val w = world(base("Why?"))
        val e = w.calendar.single()
        assertTrue(e.isNew)
        assertEquals("31923:$alice:$d", e.coord)
        assertEquals(g, e.groupId)
        assertEquals("garden-day", e.link)
        assertEquals(Visibility.PUBLIC, e.visibility)
        assertEquals(setOf(alice), e.hosts)
        val tks = w.tickets(e)
        assertEquals(1, tks.size)
        assertTrue(tks[0].closed)
        assertEquals("Why?", tks[0].prompt)
        assertEquals(MyState.HOST, w.myState(e))
        assertEquals("https://example.org/events/garden-day", w.eventUrl(e))
    }

    @Test
    fun ruleBTheNewestVersionByAHostCounts() {
        val evs = base().toMutableList()
        // bob becomes a co-host (admin of the group), eve is nobody
        evs.add(ev(alice, t0 + 10, 9000, "", listOf(listOf("h", g), listOf("p", bob, "admin"))))
        val w0 = world(evs)
        val e0 = w0.calendar.single()
        assertEquals(setOf(alice, bob), e0.hosts)
        evs.addAll(EventActions.edit(e0, form.copy(title = "Garden day (moved)", location = "The courtyard"), w0.group(g), "wss://g").map { draft(bob, t0 + 20, it) })
        evs.add(ev(eve, t0 + 30, 31923, "spam", listOf(listOf("d", d), listOf("title", "Hacked"), listOf("start", "1"))))
        val w = world(evs)
        val e = w.calendar.single()
        assertEquals("Garden day (moved)", e.title)
        assertEquals("The courtyard", e.location)
        assertEquals(bob, e.ev.pubkey)
        assertEquals("31923:$alice:$d", e.coord, "the coordinate stays the creator's")
        assertEquals(2, e.versions.size)
    }

    @Test
    fun ticketsHoldersApplicationsAndWhereIStand() {
        val evs = base("Why?").toMutableList()
        val tk = "$d:app"
        evs.add(ev(groupsKey, t0 + 10, 9000, "", listOf(listOf("h", g), listOf("p", carol, "member"), listOf("ticket", tk))))
        evs.add(ev(dave, t0 + 20, 9021, "I bring a spade", listOf(listOf("h", g), listOf("ticket", tk))))
        evs.add(ev(eve, t0 + 21, 9021, "", listOf(listOf("h", g), listOf("ticket", tk), listOf("waitlist"))))
        val w = world(evs)
        val e = w.calendar.single()
        val p = w.eventPeople(e)
        assertEquals(mapOf(carol to tk), p.holders)
        assertEquals(listOf(dave, eve), p.applications.map { it.pubkey })
        assertTrue(p.applications[1].waitlist)
        assertEquals(listOf(carol), e.goingKeys)
        assertEquals(1, e.going)
        // an application is not a join: the group is closed, but even an open one would not count it
        assertFalse(dave in w.group(g)!!.members)
        assertEquals(MyState.TICKET, world(evs, me = carol).let { it.myState(it.calendar.single()) })
        assertEquals(MyState.APPLIED, world(evs, me = dave).let { it.myState(it.calendar.single()) })
        assertEquals(MyState.WAITLIST, world(evs, me = eve).let { it.myState(it.calendar.single()) })

        // the host admits dave and declines eve
        val ok = EventActions.decide(e, dave, true, tk, p.applications[0].ev)
        assertEquals(listOf(listOf("h", g), listOf("p", dave, "member"), listOf("ticket", tk), listOf("paid", "free"), listOf("a", e.coord), listOf("e", p.applications[0].ev.id)), ok.tags)
        val no = EventActions.decide(e, eve, false)
        assertEquals(9001, no.kind)
        val after = evs + draft(alice, t0 + 30, ok) + draft(alice, t0 + 31, no)
        val w2 = world(after)
        val p2 = w2.eventPeople(w2.calendar.single())
        assertTrue(p2.applications.isEmpty())
        assertEquals(setOf(eve), p2.declined)
        assertEquals(2, w2.calendar.single().going)
        assertEquals(MyState.DECLINED, world(after, me = eve).let { it.myState(it.calendar.single()) })
        // a ticket holder who says Maybe is no longer counted as going
        val maybe = EventActions.rsvp(w2.calendar.single(), Rsvp.TENTATIVE, carol, tk)
        val w3 = world(after + draft(carol, t0 + 40, maybe), me = carol)
        assertEquals(MyState.MAYBE, w3.myState(w3.calendar.single()))
        assertEquals(1, w3.calendar.single().going)
    }

    @Test
    fun ticketTypesPlacesAndDeletion() {
        val evs = base().toMutableList()
        val w0 = world(evs)
        val e0 = w0.calendar.single()
        assertTrue(w0.tickets(e0).single().implicit)
        val saved = EventActions.saveTicket(e0, TicketForm(null, "Standard", quantity = "2", position = 1), w0.tickets(e0), w0.group(g), "wss://m", newId = "std")
        assertEquals(listOf(30402), saved.map { it.kind }, "an open ticket leaves the group open")
        evs.addAll(saved.map { draft(alice, t0 + 10, it) })
        evs.add(ev(groupsKey, t0 + 11, 9000, "", listOf(listOf("h", g), listOf("p", carol, "member"), listOf("ticket", "$d:std"))))
        val w1 = world(evs)
        val e1 = w1.calendar.single()
        val t = w1.tickets(e1).single()
        assertEquals("Standard", t.title)
        assertEquals(1, w1.eventPeople(e1).placesLeft(t))
        val closing = EventActions.saveTicket(e1, TicketForm(null, "Speaker", closed = true, prompt = "What would you show?"), w1.tickets(e1), w1.group(g), "wss://m", newId = "sp")
        assertEquals(listOf(30402, 9002), closing.map { it.kind })
        assertTrue(closing[1].tags.contains(listOf("closed")))
        val del = EventActions.deleteTicket(t)!!
        val w2 = world(evs + draft(alice, t0 + 20, del))
        assertTrue(w2.tickets(w2.calendar.single()).single().implicit)
    }

    @Test
    fun statusAndStillGoing() {
        val evs = base().toMutableList()
        val w0 = world(evs, me = carol)
        val e0 = w0.calendar.single()
        evs.add(draft(carol, t0 + 10, EventActions.rsvp(e0, Rsvp.ACCEPTED, carol)))
        assertFalse(world(evs, me = carol).let { it.stillGoing(it.calendar.single()) })
        evs.addAll(EventActions.edit(e0, form.copy(location = "Elsewhere"), w0.group(g), "wss://g").map { draft(alice, t0 + 20, it) })
        val w1 = world(evs, me = carol)
        assertTrue(w1.stillGoing(w1.calendar.single()))
        evs.addAll(EventActions.setStatus(w1.calendar.single(), EventStatus.CANCELLED, "Rain").map { draft(alice, t0 + 30, it) })
        val w2 = world(evs, me = carol)
        val e2 = w2.calendar.single()
        assertEquals(EventStatus.CANCELLED, e2.status)
        assertEquals("Rain", e2.statusReason)
        // a label by someone who is not a host does not reopen it
        evs.add(ev(eve, t0 + 40, 1985, "", listOf(listOf("h", g), listOf("L", EventLinks.STATUS_NS), listOf("l", "scheduled", EventLinks.STATUS_NS))))
        assertEquals(EventStatus.CANCELLED, world(evs).calendar.single().status)
        evs.addAll(EventActions.setStatus(e2, EventStatus.SCHEDULED).map { draft(alice, t0 + 50, it) })
        assertEquals(EventStatus.SCHEDULED, world(evs).calendar.single().status)
    }

    @Test
    fun cardsFromUrlsAndTags() {
        val evs = base().toMutableList()
        assertEquals(listOf("garden-day", "2026-abc123"), EventLinks.linksIn("see https://example.org/events/garden-day and https://example.org/events/2026-abc123, not https://other.org/events/x-y-z", "example.org"))
        val withUrl = ev(carol, t0 + 10, 9, "Come! https://example.org/events/garden-day", listOf(listOf("h", "general")))
        val withTag = ev(carol, t0 + 11, 9, "This one", listOf(listOf("h", "kitchen"), listOf("a", "31923:$alice:$d", "wss://m")))
        val unknown = ev(carol, t0 + 12, 9, "And https://example.org/events/not-here", listOf(listOf("h", "general")))
        val w = world(evs + withUrl + withTag + unknown)
        val e = w.calendar.single()
        assertEquals(listOf("general", "kitchen"), e.channels)
        val m1 = w.messages("general").first { it.id == withUrl.id }
        assertEquals(Special.None, m1.special)
        assertEquals(listOf(EventRef(link = "garden-day")), m1.eventRefs)
        assertEquals(listOf(EventRef(coord = e.coord)), w.messages("kitchen").single().eventRefs)
        assertEquals(listOf(EventRef(link = "not-here")), w.messages("general").first { it.id == unknown.id }.eventRefs)
        assertEquals(listOf(listOf("a", e.coord, "wss://m")), w.eventTagsFor("x https://example.org/events/garden-day", "wss://m"))
        assertEquals(e, w.eventByKey("garden-day"))
        assertEquals(e, w.eventByKey(e.coord))
        assertEquals(listOf(e), w.eventsIn(EventsTab.UPCOMING, t0, "general"))
        assertEquals(listOf(e), w.eventsIn(EventsTab.HOSTING, t0))
        assertTrue(w.eventsIn(EventsTab.PAST, t0).isEmpty())
        assertEquals(listOf(e), w.yourEvents(t0))
        assertTrue(world(evs, me = carol).yourEvents(t0).isEmpty())
    }

    @Test
    fun ruleOfTheUrlName() {
        assertNull(EventLinks.slugProblem("garden-day"))
        assertNotNull(EventLinks.slugProblem("ab"))
        assertNotNull(EventLinks.slugProblem("2026-abc123"))
        assertNotNull(EventLinks.slugProblem("past"))
        assertEquals("cafe-reparation", EventLinks.slugOf("Café réparation!"))
        assertTrue(EventLinks.D.matches(EventLinks.newD(t0)))
    }

    @Test
    fun guestRsvpGoesToTheGroupsRelay() {
        val w = world(base())
        val e = w.calendar.single()
        val r = EventActions.rsvp(e, Rsvp.ACCEPTED, carol, "$d:free", onGroups = true)
        assertEquals(Target.GROUPS, r.target)
        assertTrue(r.tags.contains(listOf("h", g)) && r.tags.contains(listOf("ticket", "$d:free")))
        assertEquals(Target.MAIN, EventActions.rsvp(e, Rsvp.ACCEPTED, carol).target)
    }

    @Test
    fun theApiAnswerReadsAsAnEvent() {
        val json = """{"d":"$d","coord":"31923:$alice:$d","kind":31923,"group":"$g","link":"garden-day","url":"https://example.org/events/garden-day",
            "title":"Garden day","description":"x","all_day":false,"start":"2026-11-15T10:00:00Z","end":null,"tz":"UTC","location":"The garden",
            "online":true,"links":[],"cover":null,"visibility":"link","status":"scheduled","status_reason":"","hosts":[{"pubkey":"$alice","name":"Ana"}],
            "tickets":[{"d":"$d:std","id":"std","title":"Standard","summary":"","quantity":30,"left":18,"closed":false,"prompt":"","members":false,"position":1,"implicit":false}],
            "going":12,"community":"Example","me":{"host":false,"ticket":null,"applied":true,"waitlist":false,"declined":false,"rsvp":null,"member":false}}"""
        val api = EventsApi.parse(Json.parseToJsonElement(json).jsonObject)!!
        val e = api.toCalEvent(ZoneId.of("UTC"), carol)
        assertTrue(e.remote && e.isNew)
        assertEquals("garden-day", e.link)
        assertEquals(12, e.going)
        assertEquals(1_794_736_800L, e.start)
        val w = world(emptyList(), me = carol)
        assertEquals(18, w.eventPeople(e).placesLeft(w.tickets(e, api).single()))
        assertEquals(MyState.APPLIED, w.myState(e, w.eventPeople(e), api))
    }
}
