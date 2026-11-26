package sh.belfry.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today's header and its accounts menu (SPEC 19.90; Home canvas home/today-belfry, -dark, -tap,
 * switch-1-drawer … switch-3-today). Every word the header and the menu show is here.
 */
object TodayHeader {
    private val DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH)

    /** The first line of Today's content: "Wednesday, November 25" (the board's words). */
    fun date(epochSeconds: Long, zone: ZoneId): String = DATE.format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

    /** My role in the belfry as people read it (SPEC 19.31): Steward · Member · Guest. */
    fun role(steward: Boolean, guest: Boolean): String = when {
        guest -> PlaceNames.GUEST
        steward -> PlaceNames.STEWARD
        else -> PlaceNames.MEMBER
    }

    /** The header's second line: my address here, always; without one, the belfry's domain. */
    fun address(myAddress: String?, domain: String): String = myAddress?.takeIf { it.isNotBlank() } ?: domain

    /** A menu row's second line: "sam@demo.belfry.sh · Steward" (either part may be missing). */
    fun line(address: String, role: String): String = listOf(address, role).filter { it.isNotBlank() }.joinToString(" · ")

    /** What the header says to a screen reader. */
    fun label(belfry: String, address: String): String = "$belfry, $address: switch account or belfry"

    const val ADD = "Add an account"
    const val ACCOUNTS = "Accounts"
    const val OPEN_NOW = "Open now"
    const val NEWS = "Something new"
}
