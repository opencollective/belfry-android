package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every agreement ever added and not deleted is listed, in its order (Xavier, 2026-11-18): an
 * agreement others (or I) ticked never disappears like a finished to-do; it counts its ticks.
 */
class CharterKeepsTest {
    private val admin = "a".repeat(64)
    private val ben = "b".repeat(64)
    private val cleo = "c".repeat(64)
    private var n = 0
    private fun ev(pk: String, kind: Int, at: Long, tags: List<List<String>>, c: String = ""): Event { n++; return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags, c) }

    @Test fun tickedAgreementsStayListed() {
        val first = ev(admin, 2100, 100, Charter.tags() + listOf(listOf("order", "0"), listOf("about", "Kind words.")), "We look after each other")
        val second = ev(admin, 2100, 101, Charter.tags() + listOf(listOf("order", "1")), "Leave it better than you found it")
        fun tick(who: String, rule: Event, at: Long, action: String = "done") = ev(who, 2101, at, listOf(listOf("e", rule.id), listOf("action", action)) + Charter.tags())
        val evs = listOf(first, second, tick(ben, second, 200), tick(cleo, second, 201), tick(admin, first, 202), tick(admin, first, 203, "undone"))
        val list = Charter.parse(evs, setOf(admin))
        assertEquals(listOf("We look after each other", "Leave it better than you found it"), list.map { it.title })
        assertEquals(setOf(ben, cleo), list[1].agreed.keys)
        assertEquals(emptySet(), list[0].agreed.keys)
        // only a steward's delete removes one
        val gone = Charter.parse(evs + ev(ben, 2101, 300, listOf(listOf("e", second.id), listOf("action", "delete")) + Charter.tags()), setOf(admin))
        assertEquals(2, gone.size)
        val deleted = Charter.parse(evs + ev(admin, 2101, 301, listOf(listOf("e", second.id), listOf("action", "delete")) + Charter.tags()), setOf(admin))
        assertEquals(listOf("We look after each other"), deleted.map { it.title })
    }
}
