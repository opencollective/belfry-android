package sh.belfry.core.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import sh.belfry.core.engine.MemoryVault
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.nostr.Event
import sh.belfry.core.outbox.Target
import sh.belfry.core.store.MemoryStore
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meetings (web core/meetings.ts, 2026-11-12): what a meeting is on the relay, who is invited, who was there. */
class MeetingsTest {
    private val alice = "a".repeat(64) // makes the meeting
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val dave = "d".repeat(64)
    private var n = 0
    private val d = "2026-meet01"
    private val g = "ev-$d"
    private val t0 = 1_793_000_000L

    private fun ev(pk: String, at: Long, kind: Int, content: String = "", tags: List<List<String>>): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags, content)
    }
    private fun draft(pk: String, at: Long, x: Draft) = ev(pk, at, x.kind, x.content, x.tags)
    private fun world(events: List<Event>, me: String = alice) =
        World(events, emptyList(), WorldContext(me = me, domain = "example.org", communityName = "Example", zone = ZoneId.of("UTC")))

    private val form = MeetingForm(topic = " Plan the garden day ", people = listOf(bob, carol, bob, alice, "not-a-key"), start = t0 + 86400, minutes = 90, place = " The shed ", online = "https://meet.jit.si/x", tz = "Europe/Brussels")
    private fun made(): List<Event> = Meetings.create("garden", form, d, alice, "wss://g", "wss://m").mapIndexed { i, x -> draft(alice, t0 + i, x) }

    @Test
    fun aMeetingIsAMembersOnlyEventWithAClosedChatAndThePeopleInIt() {
        val drafts = Meetings.create("garden", form, d, alice, "wss://g", "wss://m")
        assertEquals(listOf(9007, 9002, 31923, 9000, 9000), drafts.map { it.kind })
        assertEquals(listOf(Target.GROUPS, Target.GROUPS, Target.MAIN, Target.GROUPS, Target.GROUPS), drafts.map { it.target })
        // its chat: closed, no ticket
        assertTrue(drafts[1].tags.contains(listOf("closed")))
        assertTrue(drafts[1].tags.contains(listOf("name", "Plan the garden day")))
        val v = drafts[2].tags
        assertTrue(v.contains(listOf("t", "meeting")))
        assertTrue(v.contains(listOf("channel", "garden")))
        assertTrue(v.contains(listOf("visibility", "members")))
        assertTrue(v.contains(listOf("location", "The shed")))
        assertTrue(v.contains(listOf("r", "https://meet.jit.si/x")))
        assertTrue(v.contains(listOf("start", (t0 + 86400).toString())) && v.contains(listOf("end", (t0 + 86400 + 90 * 60).toString())))
        assertTrue(v.contains(listOf("start_tzid", "Europe/Brussels")))
        // each person once, never me, only keys
        assertEquals(listOf(listOf("p", bob, "", "invited"), listOf("p", carol, "", "invited")), v.filter { it[0] == "p" })
        assertEquals(listOf(listOf("h", g), listOf("p", bob, "member")), drafts[3].tags)
        assertEquals(listOf(listOf("h", g), listOf("p", carol, "member")), drafts[4].tags)

        val e = world(made()).calendar.single()
        assertTrue(e.meeting)
        assertEquals(listOf(bob, carol), e.invited)
        assertEquals("garden", e.madeIn)
        assertEquals(Visibility.MEMBERS, e.visibility)
        assertEquals("The shed", e.location)
        assertEquals(listOf(alice, bob, carol), world(made()).meetingPeople(e))
        // an ordinary event is no meeting
        val plain = EventActions.create(EventForm(title = "Garden day", start = t0), "2026-plain1", alice, "wss://g", "wss://m").mapIndexed { i, x -> draft(alice, t0 + i, x) }
        assertFalse(world(plain).calendar.single().meeting)
        assertTrue(world(plain).calendar.single().invited.isEmpty())
    }

    @Test
    fun aNewVersionKeepsWhatMakesItAMeetingAndInvitingAddsToIt() {
        val events = made().toMutableList()
        var e = world(events).calendar.single()
        // an ordinary edit (the event form): still a meeting, the same people
        val edited = EventActions.edit(e, Meetings.formOf(e, "UTC").copy(title = "Garden day, take two"), world(events).group(g), "wss://g")
        events.addAll(edited.mapIndexed { i, x -> draft(alice, t0 + 100 + i, x) })
        e = world(events).calendar.single()
        assertEquals("Garden day, take two", e.title)
        assertTrue(e.meeting)
        assertEquals(listOf(bob, carol), e.invited)
        assertEquals("garden", e.madeIn)
        assertEquals("Europe/Brussels", e.tz)
        assertEquals(t0 + 86400, e.start)

        // inviting: only the new ones, into the chat and on the event
        val more = Meetings.invite(e, listOf(carol, dave, dave), world(events).group(g), "wss://g", "UTC")
        assertEquals(listOf(9000, 31923), more.filter { it.kind != 9002 }.map { it.kind })
        assertEquals(listOf(listOf("h", g), listOf("p", dave, "member")), more[0].tags)
        events.addAll(more.mapIndexed { i, x -> draft(alice, t0 + 200 + i, x) })
        e = world(events).calendar.single()
        assertEquals(listOf(bob, carol, dave), e.invited)
        assertEquals(1, e.ev.tags.count { it[0] == "p" && it[1] == carol })
        assertTrue(Meetings.invite(e, listOf(bob, carol), null, "wss://g", "UTC").isEmpty())
        // someone invited sees it among their events
        assertEquals(listOf(e.coord), world(events, me = dave).yourEvents(t0).map { it.coord })
        assertTrue(world(events, me = "e".repeat(64)).yourEvents(t0).isEmpty())
    }

    @Test
    fun attendanceIsTheHostsLatestMark() {
        val events = made().toMutableList()
        val e = world(events).calendar.single()
        val mark = Meetings.checkIn(e, bob, true)
        assertEquals(1985, mark.kind)
        assertEquals(listOf(listOf("h", g), listOf("L", "belfry/attendance"), listOf("l", "attended", "belfry/attendance"), listOf("p", bob), listOf("a", e.coord)), mark.tags)
        assertTrue(world(events).attendance(e).isEmpty())
        events.add(draft(alice, t0 + 300, mark))
        events.add(draft(alice, t0 + 301, Meetings.checkIn(e, carol, false)))
        // someone who is not a host cannot mark people
        events.add(draft(bob, t0 + 302, Meetings.checkIn(e, carol, true)))
        // another label in the chat is none of this
        events.add(ev(alice, t0 + 303, 1985, tags = listOf(listOf("h", g), listOf("L", "belfry/pin"), listOf("l", "pinned", "belfry/pin"), listOf("p", dave))))
        var w = world(events)
        assertEquals(mapOf(bob to true, carol to false), w.attendance(e))
        assertEquals("There", w.meetingRole(e, bob, w.attendance(e)))
        assertEquals("Invited", w.meetingRole(e, carol, w.attendance(e)))
        assertEquals("Host", w.meetingRole(e, alice, w.attendance(e)))
        // the host changes their mind: the latest counts
        events.add(draft(alice, t0 + 400, Meetings.checkIn(e, bob, false)))
        w = world(events)
        assertEquals(false, w.attendance(e)[bob])
        // an RSVP says more than "Invited"
        events.add(draft(carol, t0 + 500, EventActions.rsvp(e, Rsvp.TENTATIVE, carol)))
        w = world(events)
        val now = w.calendar.single()
        assertEquals("Maybe", w.meetingRole(now, carol, w.attendance(now)))
    }

    @Test
    fun theFormsHelpers() {
        assertEquals("30 min", Meetings.lengthLabel(30))
        assertEquals("1 h", Meetings.lengthLabel(60))
        assertEquals("1 h 30", Meetings.lengthLabel(90))
        assertEquals("2 h", Meetings.lengthLabel(120))
        // the "+": a name or an e-mail, then the other one
        assertEquals(Named("Ines", null), Meetings.named(" Ines ", ""))
        assertEquals(Named("Ines", "ines@example.org"), Meetings.named("Ines", " ines@example.org "))
        assertEquals(Named("Ines", null), Meetings.named("Ines", "not an address"))
        assertEquals(Named("ines", "ines@example.org"), Meetings.named("ines@example.org", ""))
        assertEquals(Named("Ines Duarte", "ines@example.org"), Meetings.named("ines@example.org", "Ines Duarte"))
        assertTrue(Meetings.isEmail("a@b.co") && !Meetings.isEmail("a@b") && !Meetings.isEmail("a b@c.de"))

        // who to suggest: the most recently active in the channel, then its members; never me
        val base = listOf(
            ev(alice, t0, 9007, tags = listOf(listOf("h", "garden"))),
            ev("f".repeat(64), t0 + 1, 39000, tags = listOf(listOf("d", "garden"), listOf("name", "garden"))),
            ev("f".repeat(64), t0 + 1, 39002, tags = listOf(listOf("d", "garden"), listOf("p", alice), listOf("p", bob), listOf("p", carol), listOf("p", dave))),
            ev(carol, t0 + 10, 9, "Compost is full", listOf(listOf("h", "garden"))),
            ev(bob, t0 + 20, 9, "On it", listOf(listOf("h", "garden"))),
            ev(alice, t0 + 30, 9, "Thanks", listOf(listOf("h", "garden"))),
            ev(bob, t0 + 5, 0, """{"name":"Bob Builder"}""", emptyList()),
        )
        val w = world(base)
        assertEquals(listOf(bob, carol, dave), w.meetingSuggestions("garden", t0 + 60))
        assertEquals(listOf(bob, carol), w.findPeople("garden", "", t0 + 60, 2))
        assertEquals(listOf(bob), w.findPeople("garden", "build", t0 + 60))
        assertTrue(w.findPeople("garden", "zzz", t0 + 60).isEmpty())

        // the place of my last meeting first, then of my last event
        assertEquals("", w.lastPlace())
        val events = (base + made()).toMutableList()
        val other = EventActions.create(EventForm(title = "Dinner", start = t0 + 999, location = "The kitchen"), "2026-dinner", alice, "wss://g", "wss://m").mapIndexed { i, x -> draft(alice, t0 + 50 + i, x) }
        events.addAll(other)
        assertEquals("The shed", world(events).lastPlace())
        assertEquals(listOf("The shed"), world(events).meetingPlaces())
        assertEquals("The kitchen", world(base + other).lastPlace())
    }

    /** The whole thing in the demo: people by name get an account, are invited, can be handed over and checked in. */
    @Test
    fun aMeetingWithSomeoneAddedByNameInTheDemo() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val s = Onboarding(MemoryVault(), MemoryStore(), OkHttpClient(), scope, { t0 }, ZoneOffset.UTC).demo()
            s.start()
            suspend fun until(what: String, cond: suspend () -> Boolean) { var k = 0; while (!cond()) { delay(25); s.settle(); if (++k > 400) error(what) } }
            val someone = s.world.value.people.first { it.first != s.me }.first
            val made = s.events.createMeeting("general", MeetingForm("Plan the garden day", listOf(someone), listOf(Named("Ines", null), Named("Omar", "omar@example.org")), t0 + 7200, 45, "The shed", "", "UTC"))
            until("the meeting never showed") { s.world.value.calendar.firstOrNull { it.d == made }?.invited?.size == 3 }
            var e = s.world.value.calendar.first { it.d == made }
            assertTrue(e.meeting)
            assertEquals("general", e.madeIn)
            assertEquals(someone, e.invited.first())
            // Ines has no address: her account is mine to hand over; Omar's is his
            val held = s.world.value.heldByMe(e)
            assertEquals(1, held.size)
            val ines = held.first()
            assertTrue(ines in e.invited)
            assertEquals("Ines", s.world.value.displayName(ines))
            s.events.handOver(e.groupId, ines, "ines@example.org")
            until("the hand-over never showed") { s.world.value.heldByMe(e).isEmpty() }

            // check-in: one mark per change
            fun marks() = s.world.value.all.count { it.kind == 1985 && it.tags.any { t -> t.size >= 2 && t[0] == "L" && t[1] == Meetings.ATTENDANCE_NS } }
            s.events.checkIn(e, ines, true)
            until("the check-in never showed") { s.world.value.attendance(e)[ines] == true }
            s.events.checkIn(e, ines, true)
            s.settle()
            assertEquals(1, marks())
            s.events.checkIn(e, ines, false)
            until("the second mark never showed") { s.world.value.attendance(e)[ines] == false }
            assertEquals(2, marks())

            // someone added later, by name
            val late = s.events.addByName(e, Named("Rui", null))
            until("the late guest never showed") { late in (s.world.value.calendar.first { it.d == made }.invited) }
            e = s.world.value.calendar.first { it.d == made }
            assertEquals(4, e.invited.size)
            assertNull(s.world.value.calendar.firstOrNull { it.d != made && it.meeting })
            s.stop()
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }
}
