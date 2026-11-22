package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Xavier's rule (2026-11-22): per author + kind + d only the newest copy counts, then its h. */
class ReplaceableTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private var n = 0
    private val t0 = 1_793_000_000L

    private fun ev(pk: String, at: Long, kind: Int, content: String = "", vararg tags: List<String>, id: String? = null): Event {
        n++
        return Event(id ?: n.toString(16).padStart(64, '0'), pk, at, kind, tags.toList(), content)
    }
    private fun t(vararg v: String) = v.toList()
    private fun world(events: List<Event>) = World(events, emptyList(), WorldContext(me = alice, domain = "example.org", communityName = "Example", zone = ZoneId.of("UTC")))

    @Test fun onlyTheNewestCopyPerAuthorKindAndD() {
        val p1 = ev(alice, t0, 0, "{\"name\":\"Old\"}")
        val p2 = ev(alice, t0 + 5, 0, "{\"name\":\"New\"}")
        val bobP = ev(bob, t0, 0, "{\"name\":\"Bob\"}")
        val seenHello1 = ev(alice, t0, 30078, "", t("d", "seen:hello"), t("h", "hello"))
        val seenHello2 = ev(alice, t0 + 9, 30078, "", t("d", "seen:hello"), t("h", "hello"))
        val seenSponsors = ev(alice, t0 + 1, 30078, "", t("d", "seen:sponsors"), t("h", "sponsors"))
        val adm1 = ev(bob, t0, 39001, "", t("d", "hello"), t("p", alice))
        val adm2 = ev(bob, t0 + 2, 39001, "", t("d", "hello"), t("p", bob))
        val msg = ev(alice, t0, 9, "hi", t("h", "hello"))
        val out = World.newestCopies(listOf(p2, p1, bobP, seenHello1, seenHello2, seenSponsors, adm1, adm2, msg))
        // arrival order kept; the replaced copies gone; other authors, other d and non-replaceable kinds untouched
        assertEquals(listOf(p2, bobP, seenHello2, seenSponsors, adm2, msg), out)
    }

    @Test fun aTieKeepsTheLowestId() {
        val hi = ev(alice, t0, 30078, "{\"on\":true}", t("d", "belfry:inbox"), t("h", "hello"), id = "f".repeat(64))
        val lo = ev(alice, t0, 30078, "{\"on\":true}", t("d", "belfry:inbox"), t("h", "sponsors"), id = "1".repeat(64))
        assertEquals(listOf(lo), World.newestCopies(listOf(hi, lo)))
        assertEquals(listOf(lo), World.newestCopies(listOf(lo, hi)))
    }

    @Test fun calendarEventsProposalsAndRsvpsKeepTheirVersions() {
        val v1 = ev(alice, t0, 31923, "", t("d", "swap"))
        val v2 = ev(alice, t0 + 5, 31923, "", t("d", "swap"))
        val p1 = ev(alice, t0, 30023, "", t("d", "tools"))
        val p2 = ev(alice, t0 + 5, 30023, "", t("d", "tools"))
        val r1 = ev(alice, t0, 31925, "", t("d", "x"), t("status", "tentative"))
        val r2 = ev(alice, t0 + 5, 31925, "", t("d", "x"), t("status", "accepted"))
        val all = listOf(v1, v2, p1, p2, r1, r2)
        assertEquals(all, World.newestCopies(all))
    }

    @Test fun nothingReplacedIsTheSameList() {
        val list = listOf(ev(alice, t0, 9, "a", t("h", "hello")), ev(alice, t0, 0, "{}"))
        assertTrue(World.newestCopies(list) === list)
    }

    /** OSV: a stale local copy (old d, h=hello) the relay replaced (h=sponsors), through the whole World. */
    @Test fun aReplacedCopyNoLongerCountsForItsChannel() {
        val hello = ev(alice, t0 - 100, 9007, "", t("h", "hello"))
        val sponsors = ev(alice, t0 - 99, 9007, "", t("h", "sponsors"))
        val stale = ev(alice, t0, 30078, "{\"on\":true}", t("d", "belfry:inbox"), t("h", "hello"))
        val replaced = ev(alice, t0 + 3, 30078, "{\"on\":true}", t("d", "belfry:inbox"), t("h", "sponsors"))
        val w = world(listOf(hello, sponsors, stale, replaced))
        assertFalse(w.isInbox(w.group("hello")))
        assertTrue(w.isInbox(w.group("sponsors")))
        assertFalse(w.all.any { it.id == stale.id })
    }
}
