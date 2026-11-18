package sh.belfry.app

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.cards.CheckInScreen
import sh.belfry.app.ui.cards.EventScreen
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.eventByLink
import sh.belfry.core.model.eventPeople
import sh.belfry.core.model.Prices
import sh.belfry.core.model.Ticket
import sh.belfry.app.ui.cards.TakeSheet

/** The event screen redesign (2026-11-18; the events boards). */
class EventsShotsTest : DemoShots() {
    private val repair get() = world.eventByLink(Demo.REPAIR_D)!!

    /** events/event-bar: folded, the title in the bar. */
    @Test fun eventBar() = demoShot("event-bar", act = {
        compose.onNodeWithTag("event-handle").performClick() // half → full
        compose.waitForIdle()
        compose.onNodeWithTag("event-handle").performClick() // full → up
    }) { EventScreen(repair.coord) }

    /** events/people: Hosts (with their role) · Going · Maybe, from the faces row. */
    @Test fun people() {
        demoShot("event-people", act = { compose.onNodeWithTag("event-tab-2").performClick() }) { EventScreen(repair.coord) }
        compose.onNodeWithTag("event-people").assertExists()
    }

    /** events/ticket-card: a ticket link opened by someone who may check people in. Nobody is checked in by it. */
    @Test fun ticketCard() {
        demoShot("event-ticket-card") { EventScreen(repair.coord, ticket = "abcdefghijklmnopqrstu") }
        compose.onNodeWithTag("ticket-link-card").assertExists()
        compose.onNodeWithTag("ticket-checkin").assertExists()
    }

    /** events/check-in: a tap checks someone in and offers Undo / Keep. */
    @Test fun checkInUndo() {
        demoShot("check-in-undo", act = {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("check-row").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("check-row")[0].performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("check-undo").fetchSemanticsNodes().isNotEmpty() }
        }) { CheckInScreen(repair.coord) }
        assertTrue(compose.onAllNodesWithTag("check-result").fetchSemanticsNodes().isNotEmpty())
    }

    /** events/get-tickets: free, priced with VAT and a date, by application, not on sale now; and the payment note. */
    @Test fun getTickets() {
        val e = repair
        val d = e.d
        val now = System.currentTimeMillis() / 1000
        val jan1 = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusMonths(2).withDayOfMonth(1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
        val tickets = listOf(
            Ticket("$d:seat", "seat", "Workshop seat", quantity = 6, position = 1),
            Ticket("$d:supporter", "supporter", "Supporter", position = 2, cost = Prices.of(listOf(listOf("price_at", "50", "EUR", "", jan1.toString()), listOf("vat", "21")), now)),
            Ticket("$d:volunteer", "volunteer", "Volunteer", closed = true, prompt = "What would you like to do?", position = 3),
            Ticket("$d:early", "early", "Early bird", position = 4, cost = Prices.of(listOf(listOf("price_at", "30", "EUR", "", "1000")), now)),
        )
        demoScreenShot("event-get-tickets") { TakeSheet(e, tickets, world.eventPeople(e), null, onPick = {}, onDismiss = {}) }
        compose.onNodeWithTag("payment-note").assertExists()
    }
}
