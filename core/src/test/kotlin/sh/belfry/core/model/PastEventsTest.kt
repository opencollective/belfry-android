package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SPEC 19.46 (the lead 2026-11-20, an OSV member's bug: "Upcoming events show events of the
 * current day that have already passed"): past = now ≥ end; a timed event without an end ends an
 * hour after its start; an all-day event ends at the start of the day after its last day (end date
 * inclusive), in its time zone, the viewer's when none; going on now = upcoming. A fixed clock.
 */
class PastEventsTest {
    private val me = "a".repeat(64)
    private val zone = ZoneId.of("Europe/Brussels")
    private var n = 0

    private fun at(day: Int, h: Int, m: Int = 0, z: ZoneId = zone) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, z).toEpochSecond()

    private fun timed(name: String, start: Long, end: Long? = null): Event {
        n++
        val tags = mutableListOf(listOf("d", "session-$name"), listOf("title", name), listOf("start", start.toString()), listOf("start_tzid", zone.id), listOf("h", "ws-$name"))
        if (end != null) tags.add(listOf("end", end.toString()))
        return Event(n.toString(16).padStart(64, '0'), me, 1_790_000_000L + n, 31923, tags, "")
    }

    private fun allDay(name: String, start: String, end: String? = null, tz: String? = null): Event {
        n++
        val tags = mutableListOf(listOf("d", "session-$name"), listOf("title", name), listOf("start", start), listOf("h", "ws-$name"))
        if (end != null) tags.add(listOf("end", end))
        if (tz != null) tags.add(listOf("start_tzid", tz))
        return Event(n.toString(16).padStart(64, '0'), me, 1_790_000_000L + n, 31922, tags, "")
    }

    private fun world(vararg evs: Event) =
        World(evs.toList(), emptyList(), WorldContext(me = me, domain = "example.org", communityName = "Example", zone = zone))

    private fun World.ev(name: String) = calendar.single { it.title == name }
    private fun World.upcomingTitles(now: Long) = eventsIn(EventsTab.UPCOMING, now).map { it.title }.toSet()
    private fun World.pastTitles(now: Long) = eventsIn(EventsTab.PAST, now).map { it.title }.toSet()

    @Test
    fun aTimedEventThatEndedThisMorningIsPast() {
        val w = world(timed("Breakfast", at(20, 9), at(20, 10)), timed("Lunch", at(20, 13), at(20, 14)))
        val noon = at(20, 12)
        assertEquals(setOf("Lunch"), w.upcomingTitles(noon))
        assertEquals(setOf("Breakfast"), w.pastTitles(noon))
        assertEquals(listOf("Lunch"), w.upcoming(null, noon).map { it.title })
        assertTrue(w.isPast(w.ev("Breakfast"), noon))
        assertFalse(w.isOpen(w.ev("Breakfast"), noon), "RSVP closes by the same rule")
        // exactly at its end: past
        assertTrue(w.isPast(w.ev("Lunch"), at(20, 14)))
        assertFalse(w.isPast(w.ev("Lunch"), at(20, 14) - 1))
    }

    @Test
    fun goingOnNowStaysUpcomingAndNext() {
        val w = world(timed("Workshop", at(20, 11), at(20, 13)), timed("Talk", at(20, 15), at(20, 16)))
        val noon = at(20, 12)
        assertEquals(listOf("Workshop", "Talk"), w.upcoming(null, noon).map { it.title })
        assertTrue(w.isOpen(w.ev("Workshop"), noon))
    }

    @Test
    fun noEndMeansOneHour() {
        val w = world(timed("Standup", at(20, 11, 30)))
        val e = w.ev("Standup")
        assertEquals(at(20, 12, 30), w.endOf(e))
        assertFalse(w.isPast(e, at(20, 12, 29)))
        assertTrue(w.isPast(e, at(20, 12, 30)))
    }

    @Test
    fun anAllDayEventLastsUntilTheEndOfItsLastDay() {
        val w = world(allDay("Fair", "2026-11-20"), allDay("Camp", "2026-11-18", "2026-11-20"))
        val fair = w.ev("Fair")
        val camp = w.ev("Camp")
        assertEquals(at(21, 0), w.endOf(fair))
        assertEquals(at(21, 0), w.endOf(camp), "the end date is inclusive")
        assertEquals(setOf("Fair", "Camp"), w.upcomingTitles(at(20, 23, 59)))
        assertEquals(setOf("Fair", "Camp"), w.pastTitles(at(21, 0)))
        // a multi-day event shows on each of its days (Calendar's day view)
        assertTrue(w.onDay(camp, LocalDate.of(2026, 11, 19)))
        assertTrue(w.onDay(camp, LocalDate.of(2026, 11, 20)))
        assertFalse(w.onDay(camp, LocalDate.of(2026, 11, 21)))
        // the check-in window: the event's days, its last day included
        assertTrue(w.onEventDays(camp, at(20, 22)))
        assertFalse(w.onEventDays(camp, at(21, 0)))
    }

    @Test
    fun anAllDayEventEndsInItsOwnTimeZone() {
        val ny = ZoneId.of("America/New_York")
        val w = world(allDay("Parade", "2026-11-20", tz = ny.id), allDay("Here", "2026-11-20"))
        // 00:30 in Brussels on the 21st is 18:30 on the 20th in New York
        val now = at(21, 0, 30)
        assertFalse(w.isPast(w.ev("Parade"), now))
        assertTrue(w.isPast(w.ev("Here"), now), "no time zone: the viewer's")
        assertEquals(ZonedDateTime.of(2026, 11, 21, 0, 0, 0, 0, ny).toEpochSecond(), w.endOf(w.ev("Parade")))
    }
}
