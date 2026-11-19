package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Test
import sh.belfry.app.ui.cards.TicketRow
import sh.belfry.core.model.EventPeople
import sh.belfry.core.model.Prices
import sh.belfry.core.model.Ticket

/** canvas events/get-tickets v38: can_pay + checkout_url → "Buy" (opens the checkout outside); otherwise "Ask"; "N left". */
class CheckoutShotsTest : DemoShots() {
    @Test fun buyOrAsk() {
        val price = Prices.of(listOf(listOf("price", "50", "EUR"), listOf("vat", "21")), 0)
        val buy = Ticket("2026-swap01:supporter", "supporter", "Supporter", cost = price, apiLeft = 12, checkoutUrl = "https://belfry.example/tickets?event=seed-swap&ticket=supporter")
        val ask = Ticket("2026-swap01:day", "day", "Day pass", cost = price)
        demoShot("tickets-buy-or-ask") {
            Column(Modifier.padding(16.dp)) {
                TicketRow(buy, EventPeople.EMPTY, onPick = {})
                TicketRow(ask, EventPeople.EMPTY, onPick = {})
            }
        }
        compose.onNodeWithTag("ticket-buy-supporter").assertExists()
        compose.onNodeWithTag("ticket-take-day").assertExists()
    }
}
