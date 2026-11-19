package sh.belfry.core.model

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ticket prices (docs/checkin.md "Ticket prices", "VAT and prices for a while"; the web's pricesOf). */
class PricesTest {
    private val utc = ZoneId.of("UTC")
    private val jan1 = 1798761600L // 2027-01-01 00:00 UTC

    @Test fun freeAndPlain() {
        assertEquals(TicketPrice.FREE, Prices.of(emptyList(), 0))
        val p = Prices.of(listOf(listOf("price", "50", "EUR"), listOf("vat", "21")), 0)
        assertTrue(p.priced); assertTrue(p.onSale)
        assertEquals("€50 + 21% VAT", Prices.line(p, utc))
        assertEquals("€50 incl. 21% VAT", Prices.line(Prices.of(listOf(listOf("price", "50", "EUR"), listOf("vat", "21", "included")), 0), utc))
        assertEquals("€7.50", Prices.words(Prices.of(listOf(listOf("price", "7.5", "EUR")), 0)))
        assertEquals("2 hours", Prices.line(Prices.of(listOf(listOf("price", "2", "HOUR"), listOf("vat", "21")), 0), utc))
        assertEquals("3 tokens", Prices.line(Prices.of(listOf(listOf("price", "3", "tokens")), 0), utc))
    }

    @Test fun pricesForAWhile() {
        val tags = listOf(listOf("price_at", "40", "EUR", "", jan1.toString()), listOf("price", "50", "EUR"))
        val early = Prices.of(tags, jan1 - 10)
        assertEquals("40", early.price?.amount)
        assertEquals("€40 · until 1 January 2027", Prices.line(early, utc))
        val later = Prices.of(tags, jan1 + 10)
        assertEquals("50", later.price?.amount)
        assertNull(later.price?.until)
        // only dated prices, none on now: not on sale
        val past = Prices.of(listOf(listOf("price_at", "40", "EUR", "", jan1.toString())), jan1 + 10)
        assertTrue(past.priced); assertFalse(past.onSale)
        assertEquals("Not on sale now", Prices.line(past, utc))
        // an unreadable price still counts as priced
        assertTrue(Prices.of(listOf(listOf("price", "abc", "EUR")), 0).priced)
        // the server's price_of: still on sale, read as "Paid" (vectors/ticket-prices.json)
        assertTrue(Prices.of(listOf(listOf("price", "abc", "EUR")), 0).onSale)
        assertEquals("Paid", Prices.words(Prices.of(listOf(listOf("price", "abc", "EUR")), 0)))
    }

    @Test fun aPricedTicketIsAsked() {
        val t = Ticket("ev:supporter", "supporter", "Supporter", cost = Prices.of(listOf(listOf("price", "50", "EUR")), 0))
        assertTrue(t.asks)
        assertFalse(Ticket("ev:seat", "seat", "Seat").asks)
    }

    /** docs/sales.md §10: only https on the belfry's own domain (or a subdomain) is opened. */
    @Test fun checkoutAddresses() {
        assertEquals("https://belfry.example/tickets?event=x&ticket=y", checkoutUrlOf("https://belfry.example/tickets?event=x&ticket=y", "belfry.example"))
        assertEquals("https://shop.belfry.example/t", checkoutUrlOf("https://shop.belfry.example/t", "belfry.example"))
        assertNull(checkoutUrlOf("http://belfry.example/t", "belfry.example"))
        assertNull(checkoutUrlOf("https://evil.example/t", "belfry.example"))
        assertNull(checkoutUrlOf("https://belfry.example.evil.example/t", "belfry.example"))
        assertNull(checkoutUrlOf("https://user@belfry.example/t", "belfry.example"))
        assertNull(checkoutUrlOf("https://belfry.example:8443/t", "belfry.example"))
        assertNull(checkoutUrlOf(null, "belfry.example"))
    }
}
