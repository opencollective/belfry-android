package sh.belfry.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.cards.CoHostSheet
import sh.belfry.app.ui.cards.CreateEventScreen
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.onAllNodesWithText
import sh.belfry.core.model.meetingSuggestions
import sh.belfry.core.model.attendance
import sh.belfry.core.model.Named
import sh.belfry.core.model.Meetings
import sh.belfry.core.model.MeetingForm
import sh.belfry.app.ui.cards.EventHandoff
import sh.belfry.app.ui.cards.CreateMeetingScreen
import sh.belfry.app.ui.cards.EventCardView
import sh.belfry.app.ui.cards.EventRefCard
import sh.belfry.core.model.EventRef
import sh.belfry.app.ui.cards.EventScreen
import sh.belfry.app.ui.cards.EventsScreen
import sh.belfry.app.ui.cards.GuestListSheet
import sh.belfry.app.ui.cards.TakeSheet
import sh.belfry.app.ui.cards.TicketsSheet
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.shell.ChannelDrawer
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.eventPeople
import sh.belfry.core.model.tickets

/** Belfry events in demo mode: screenshots, and the one-tap rules (join, apply, admit fire once). */
class EventsTest : DemoShots() {
    private val swap: CalEvent get() = world.calendar.first { it.d == Demo.SWAP_D }
    private val repair: CalEvent get() = world.calendar.first { it.d == Demo.REPAIR_D }

    private fun tap(tag: String) {
        try {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag(tag).performClick()
        } catch (e: AssertionError) {
            // gone between the check and the tap
        }
    }

    private fun field(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))

    private fun mine(kind: Int, pred: (sh.belfry.core.nostr.Event) -> Boolean = { true }): Int = world.all.count { it.kind == kind && it.pubkey == session.me && pred(it) }

    // ---- screenshots -------------------------------------------------------------------------------

    @Test
    fun eventsUpcoming() = demoShot("events-upcoming") { EventsScreen(null) }

    @Test
    fun eventsHosting() = demoShot("events-hosting") { EventsScreen(null, initialTab = 2) }

    @Test
    fun eventOpenToAll() = demoShot("event-new-open") { EventScreen(swap.coord) }

    @Test
    fun eventByLink() = demoShot("event-new-by-link") { EventScreen(Demo.SWAP_SLUG) }

    @Test
    fun eventIHost() = demoShot("event-new-host") { EventScreen(repair.coord) }

    @Test
    fun eventGuestList() = demoScreenShot("event-guest-list") {
        EventScreen(repair.coord)
        GuestListSheet(repair) {}
    }

    @Test
    fun eventTakeSheet() {
        val e = repair
        val t = world.tickets(e).single()
        demoScreenShot("event-apply-sheet") {
            EventScreen(e.coord)
            TakeSheet(e, listOf(t), world.eventPeople(e), t, {}, {})
        }
    }

    @Test
    fun eventTicketsSheet() = demoScreenShot("event-tickets-sheet") {
        EventScreen(repair.coord)
        TicketsSheet(repair) {}
    }

    @Test
    fun eventCoHostSheet() = demoScreenShot("event-cohost-sheet") {
        EventScreen(repair.coord)
        CoHostSheet(repair) {}
    }

    @Test
    fun eventCardsInTheFeed() = demoShot("event-cards-feed") { ChannelScreen(Demo.GARDEN) }

    @Test
    fun eventChat() = demoShot("event-chat-bar") { ChannelScreen("ev-" + Demo.REPAIR_D) }

    @Test
    fun eventCards() = demoShot("event-cards") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EventCardView(swap, null)
            EventCardView(repair, null)
        }
    }

    @Test
    fun drawerYourEvents() = demoShot("drawer-your-events") { ChannelDrawer(Demo.GENERAL, onPick = {}, go = {}) }

    @Test
    fun newEventForm() = demoShot("event-form-new") { CreateEventScreen(Routes.NO_CHANNEL) }

    @Test
    fun editEventForm() = demoShot("event-form-edit") { CreateEventScreen(Routes.NO_CHANNEL, edit = repair.coord) }

    // ---- behaviour ---------------------------------------------------------------------------------

    @Test
    fun goingOnACardFiresOnce() {
        started()
        val e = swap
        val before = mine(31925) { it.tags.any { t -> t.size >= 2 && t[0] == "a" && t[1] == e.coord } }
        compose.setContent { BelfryTheme { InDemo { EventRefCard(EventRef(coord = e.coord)) } } }
        tap("card-going")
        tap("card-going")
        tap("card-maybe")
        compose.waitUntil(10_000) { world.calendar.first { it.d == Demo.SWAP_D }.rsvps[session.me] != null }
        settle()
        assertEquals("one tap, one answer", before + 1, mine(31925) { it.tags.any { t -> t.size >= 2 && t[0] == "a" && t[1] == e.coord } })
    }

    @Test
    fun rsvpOnTheEventLineFiresOnce() {
        started()
        val e = swap
        val before = mine(31925)
        compose.setContent { BelfryTheme { InDemo { EventScreen(e.coord) } } }
        tap("line-rsvp")
        tap("line-rsvp")
        tap("rsvp-accepted")
        compose.waitUntil(10_000) { world.calendar.first { it.d == Demo.SWAP_D }.rsvps[session.me] != null }
        settle()
        assertEquals(before + 1, mine(31925))
    }

    @Test
    fun applyingSendsOneApplication() {
        started()
        val e = repair
        val t = world.tickets(e).single()
        val before = mine(9021)
        compose.setContent { BelfryTheme { InDemo { TakeSheet(e, listOf(t), world.eventPeople(e), t, {}, {}) } } }
        field("take-answer").performTextInput("I can fix lamps.")
        tap("take-submit")
        tap("take-submit")
        tap("take-submit")
        compose.waitUntil(10_000) { mine(9021) > before }
        settle()
        assertEquals("one tap, one application", before + 1, mine(9021))
        val sent = world.all.last { it.kind == 9021 && it.pubkey == session.me }
        assertTrue(sent.tags.contains(listOf("ticket", t.d)) && sent.tags.contains(listOf("h", e.groupId)))
    }

    @Test
    fun admitFiresOnceAndDeclineIsLockedWithIt() {
        started()
        val e = repair
        val sam = Demo.sam.pubkey
        fun decisions() = mine(9000) { it.tags.any { t -> t.size >= 2 && t[0] == "p" && t[1] == sam } } + mine(9001) { it.tags.any { t -> t.size >= 2 && t[0] == "p" && t[1] == sam } }
        val before = decisions()
        compose.setContent { BelfryTheme { InDemo { GuestListSheet(repair) {} } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("approve-" + sam.take(8)).fetchSemanticsNodes().isNotEmpty() }
        tap("approve-" + sam.take(8))
        tap("approve-" + sam.take(8))
        tap("decline-" + sam.take(8))
        compose.waitUntil(10_000) { world.eventPeople(world.calendar.first { it.d == Demo.REPAIR_D }).holders.containsKey(sam) }
        settle()
        assertEquals("one tap, one decision", before + 1, decisions())
        val admitted = world.all.last { it.kind == 9000 && it.pubkey == session.me }
        assertTrue(admitted.tags.contains(listOf("ticket", "${Demo.REPAIR_D}:app")))
    }

    @Test
    fun aNewEventFromAChannelPutsItsUrlInTheComposer() {
        started()
        compose.setContent { BelfryTheme { InDemo { CreateEventScreen(Demo.KITCHEN) } } }
        field("event-title").performTextInput("Soup night")
        compose.onNodeWithTag("create-submit").performScrollTo()
        tap("create-submit")
        tap("create-submit")
        compose.waitUntil(10_000) { world.calendar.any { it.title == "Soup night" } }
        settle()
        assertEquals("one event", 1, world.calendar.count { it.title == "Soup night" })
        val e = world.calendar.first { it.title == "Soup night" }
        assertTrue(e.isNew && session.me in e.hosts)
        assertEquals("soup-night", e.slug)
        assertEquals(null, world.messages(Demo.KITCHEN).firstOrNull { it.text.contains("/events/") })
        assertEquals("https://${Demo.DOMAIN}/events/soup-night", sh.belfry.app.ui.cards.EventHandoff.take(Demo.KITCHEN))
    }

    // ---- meetings (web 2026-11-12) ---------------------------------------------------------------------

    /** A meeting made in the demo: two members, one person by name (her account is mine to hand over). */
    private fun aMeeting(): CalEvent {
        started()
        val people = world.people.map { it.first }.filter { it != session.me }.take(2)
        val d = runBlocking {
            session.events.createMeeting(Demo.GARDEN, MeetingForm("Plan the garden day", people, listOf(Named("Ines Duarte", null)), NOW + 2 * 3600, 45, "The shed", "", "UTC"))
        }
        repeat(200) {
            settle()
            val e = world.calendar.firstOrNull { it.d == d }
            if (e != null && e.invited.size == 3 && !world.isPending(e.ev.id)) return e
            Thread.sleep(25)
        }
        error("the meeting never showed")
    }

    @Test
    fun meetingForm() = demoShot("meeting-new") { CreateMeetingScreen(Demo.GARDEN) }

    @Test
    fun meetingPage() {
        val e = aMeeting()
        runBlocking { session.events.checkIn(e, e.invited.first(), true) }
        var tries = 0
        while (world.attendance(e).isEmpty() && tries++ < 200) { settle(); Thread.sleep(25) }
        demoShot("meeting-page") { EventScreen(e.coord) }
    }

    @Test
    fun aMeetingIsMadeFromTheFormOnce() {
        started()
        val before = world.calendar.count { it.meeting }
        compose.setContent { BelfryTheme { InDemo { CreateMeetingScreen(Demo.GARDEN) } } }
        val someone = world.meetingSuggestions(Demo.GARDEN, NOW).first()
        field("meet-topic").performTextInput("Plan the garden day")
        compose.onNodeWithTag("meet-face-" + someone.take(8)).performScrollTo().performClick()
        compose.onNodeWithTag("create-submit").performScrollTo().performClick()
        tap("create-submit")
        tap("create-submit")
        compose.waitUntil(10_000) { world.calendar.count { it.meeting } == before + 1 }
        settle()
        assertEquals("one tap, one meeting", before + 1, world.calendar.count { it.meeting })
        val e = world.calendar.first { it.meeting }
        assertEquals(listOf(someone), e.invited)
        assertEquals(Demo.GARDEN, e.madeIn)
        // its chat is closed and the person invited was put in it
        assertEquals(1, world.all.count { it.kind == 9000 && it.tag("h") == e.groupId && it.tag("p") == someone })
        // its URL waits for the channel's message box, and the form is left
        compose.waitUntil(5_000) { EventHandoff.pending?.first == Demo.GARDEN }
        assertTrue(nav.went.contains("<back>"))
        EventHandoff.take(Demo.GARDEN)
    }

    @Test
    fun aHostChecksSomeoneIn() {
        val e = aMeeting()
        val who = e.invited.first()
        compose.setContent { BelfryTheme { InDemo { EventScreen(e.coord) } } }
        val tag = "meet-check-" + who.take(8)
        // (a second tap once the first is sent is a new answer: it would mark them not there again)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitUntil(10_000) { world.attendance(e)[who] == true }
        settle()
        assertEquals("one tap, one mark", 1, world.all.count { x -> x.kind == 1985 && x.tags.any { it.size >= 2 && it[0] == "L" && it[1] == Meetings.ATTENDANCE_NS } })
        compose.waitUntil(5_000) { compose.onAllNodesWithText("✓ There").fetchSemanticsNodes().isNotEmpty() }
    }
}
