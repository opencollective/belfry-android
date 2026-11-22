package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One shape, the event screen (SPEC 19.53): Join's window, answer lines in the event's chat, when it opens mid. */
class EventShapeTest {
    private val alice = "a".repeat(64) // hosts
    private val carol = "c".repeat(64) // a member
    private val lina = "1".repeat(64)
    private var n = 0
    private val d = "2026-shape1"
    private val g = "ev-$d"
    private val t0 = 1_793_000_000L
    private val start = t0 + 5 * 86400

    private fun draft(pk: String, at: Long, x: Draft): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, x.kind, x.tags, x.content)
    }
    private fun world(events: List<Event>, me: String = carol) =
        World(events, emptyList(), WorldContext(me = me, domain = "example.org", communityName = "Example", zone = ZoneId.of("UTC")))

    private val form = EventForm(title = "Garden planning", start = start, end = start + 3600, tz = "UTC", online = "https://meet.jit.si/garden")
    private fun base(f: EventForm = form): List<Event> = EventActions.create(f, d, alice, "wss://g", "wss://m").mapIndexed { i, x -> draft(alice, t0 + i, x) }

    @Test
    fun joinOpensTenMinutesBeforeAndClosesAtTheEnd() {
        val e = world(base()).calendar.single()
        assertEquals(listOf("https://meet.jit.si/garden"), e.links)
        assertFalse(EventShape.joinOpen(e, start - 601))
        assertTrue(EventShape.joinOpen(e, start - 600))
        assertTrue(EventShape.joinOpen(e, start + 3600))
        assertFalse(EventShape.joinOpen(e, start + 3601))
        val noEnd = world(base(form.copy(end = null))).calendar.single()
        assertTrue(EventShape.joinOpen(noEnd, start + 2 * 3600))
        assertFalse(EventShape.joinOpen(noEnd, start + 2 * 3600 + 1))
        assertEquals("Jitsi", Actions.meetingLabel(e.links[0]))
        assertEquals("Google Meet", Actions.meetingLabel("https://meet.google.com/abc-defg-hij"))
        assertEquals("Zoom", Actions.meetingLabel("https://us02web.zoom.us/j/123"))
    }

    @Test
    fun answersAreLinesInTheEventsChatAndItOpensMidUntilIAnswer() {
        val events = base().toMutableList()
        val e0 = world(events).calendar.single()
        assertTrue(world(events).eventNeedsMe(e0, t0 + 100), "not answered: mid")
        events.add(draft(lina, t0 + 200, EventActions.rsvp(e0, Rsvp.ACCEPTED, lina)))
        events.add(draft(carol, t0 + 300, EventActions.rsvp(e0, Rsvp.TENTATIVE, carol)))
        val w = world(events)
        val answers = w.feed(g).filterIsInstance<FeedItem.Answer>()
        assertEquals(listOf(lina to Rsvp.ACCEPTED, carol to Rsvp.TENTATIVE), answers.map { it.pubkey to it.status })
        assertEquals(listOf(t0 + 200, t0 + 300), answers.map { it.at })
        assertFalse(w.eventNeedsMe(w.calendar.single(), t0 + 400), "answered: tiny")
        // a changed answer moves the line to its new time
        events.add(draft(carol, t0 + 500, EventActions.rsvp(e0, Rsvp.ACCEPTED, carol)))
        val again = world(events).feed(g).filterIsInstance<FeedItem.Answer>()
        assertEquals(2, again.size)
        assertEquals(FeedItem.Answer(carol, Rsvp.ACCEPTED, t0 + 500), again.last())
        // an ended event needs nobody
        val w2 = world(base())
        assertFalse(w2.eventNeedsMe(w2.calendar.single(), start + 86400))
    }

    @Test
    fun words() {
        assertEquals("You’re going", EventShape.answerLine(null, Rsvp.ACCEPTED))
        assertEquals("Lina Moreau is going", EventShape.answerLine("Lina Moreau", Rsvp.ACCEPTED))
        assertEquals("Lina Moreau can’t go", EventShape.answerLine("Lina Moreau", Rsvp.DECLINED))
        assertEquals("Lina Moreau and Ben Okafor", EventShape.names(listOf("Lina Moreau", "Ben Okafor")))
        assertEquals("A, B and C", EventShape.names(listOf("A", "B", "C")))
    }
}
