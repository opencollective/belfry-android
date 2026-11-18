package sh.belfry.core.model

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

// Ticket prices (collective-belfry docs/checkin.md "Ticket prices", "VAT and prices for a while";
// a port of the web's events.ts pricesOf / priceLabel / priceFull / priceLine). A priced ticket is
// never self-served: people ask, settle with the hosts, and a host's admission (a 9000 with
// ["paid","paid"]) says the price is settled. Belfry takes no money for tickets.

/** The price on now: "€50", maybe with VAT, until when (unix seconds). */
data class Price(val amount: String, val currency: String, val vat: String? = null, val vatIncluded: Boolean = false, val until: Long? = null)

/** One row of a ticket's prices: without dates (`price`) or for a while (`price_at`). */
data class PriceRow(val amount: String, val currency: String, val from: Long? = null, val until: Long? = null)

/** What a ticket costs now. [priced] = it has a price at all; [onSale] false = priced, nothing on now. */
data class TicketPrice(val price: Price?, val priced: Boolean, val prices: List<PriceRow>, val onSale: Boolean) {
    companion object { val FREE = TicketPrice(null, false, emptyList(), true) }
}

object Prices {
    private val AMOUNT = Regex("^\\d{1,7}(\\.\\d{1,2})?$")
    private val VAT_RATE = Regex("^\\d{1,2}(\\.\\d{1,2})?$")
    private val MONEY = Regex("^[A-Z]{3}$")

    fun isMoney(currency: String) = MONEY.matches(currency)

    /** The tags' prices and the one on now: the first `price_at` whose window holds NOW, else the plain `price`. */
    fun of(tags: List<List<String>>, now: Long): TicketPrice {
        fun ok(a: String?) = a != null && AMOUNT.matches(a) && (a.toDoubleOrNull() ?: 0.0) > 0
        fun num(v: String?) = v?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
        val rows = tags.filter { it.firstOrNull() == "price_at" && ok(it.getOrNull(1)) && !it.getOrNull(2).isNullOrEmpty() }
            .map { PriceRow(it[1], it[2], num(it.getOrNull(3)), num(it.getOrNull(4))) }
        val plain = tags.firstOrNull { it.firstOrNull() == "price" }
        val base = plain?.takeIf { ok(it.getOrNull(1)) && !it.getOrNull(2).isNullOrEmpty() }?.let { PriceRow(it[1], it[2]) }
        // an unreadable price_at still makes the ticket priced (docs: "with no readable amount")
        val priced = tags.any { it.firstOrNull() == "price_at" } || plain != null
        val v = tags.firstOrNull { it.firstOrNull() == "vat" && VAT_RATE.matches(it.getOrNull(1) ?: "") }
        fun withVat(p: Price) = if (v != null && isMoney(p.currency)) p.copy(vat = v[1], vatIncluded = v.getOrNull(2) == "included") else p
        val on = rows.firstOrNull { (it.from == null || it.from <= now) && (it.until == null || now < it.until) }
        val price = when {
            on != null -> withVat(Price(on.amount, on.currency, until = on.until))
            base != null -> withVat(Price(base.amount, base.currency, until = rows.mapNotNull { it.from }.filter { it > now }.minOrNull()))
            else -> null
        }
        return TicketPrice(price, priced, if (base != null) rows + base else rows, !priced || price != null)
    }

    /** "€15", "2 hours", "3 tokens". */
    fun label(p: Price): String {
        val n = p.amount.toDoubleOrNull() ?: 0.0
        if (isMoney(p.currency) && p.currency != "HOU") {
            try {
                val f = NumberFormat.getCurrencyInstance(Locale.ENGLISH)
                f.currency = Currency.getInstance(p.currency)
                val whole = n == Math.floor(n)
                f.minimumFractionDigits = if (whole) 0 else 2
                f.maximumFractionDigits = if (whole) 0 else 2
                return f.format(n)
            } catch (e: IllegalArgumentException) { /* a code Java doesn't know: as a label */ }
        }
        if (p.currency == "HOUR") return "${p.amount} ${if (n == 1.0) "hour" else "hours"}"
        return "${p.amount} ${p.currency}"
    }

    /** "€50 + 21% VAT", "€50 incl. 21% VAT", "2 hours". */
    fun full(p: Price): String = label(p) + if (p.vat != null && isMoney(p.currency)) (if (p.vatIncluded) " incl. ${p.vat}% VAT" else " + ${p.vat}% VAT") else ""

    private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    /** Under a ticket's name: "€50 + 21% VAT · until 1 January 2027", "Not on sale now", or "" (free). */
    fun line(t: TicketPrice, zone: ZoneId): String = when {
        t.priced && !t.onSale -> "Not on sale now"
        t.price != null -> full(t.price) + (t.price.until?.let { " · until " + DAY.format(Instant.ofEpochSecond(it).atZone(zone)) } ?: "")
        else -> ""
    }
}
