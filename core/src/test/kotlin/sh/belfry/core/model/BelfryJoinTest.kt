package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Joining the belfry: a line in the main channel (SPEC 19.59, docs/one-shape.md 2026-11-22). */
class BelfryJoinTest {
    private val alice = "a".repeat(64)
    private val lina = "b".repeat(64)
    private val kit = "c".repeat(64)
    private val gone = "d".repeat(64)
    private val goneWrote = "e".repeat(64)
    private val guest = "f".repeat(64)
    private val reader = "1".repeat(64)
    private val old = "2".repeat(64)
    private val noProfile = "3".repeat(64)
    private val stranger = "4".repeat(64)
    private val gk = "9".repeat(64)
    private var n = 0
    private val t0 = 1_795_000_000L

    private fun ev(pk: String, at: Long, kind: Int, vararg tags: List<String>, content: String = ""): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags.toList(), content)
    }
    private fun t(vararg v: String) = v.toList()
    private fun name(pk: String, s: String) = ev(pk, t0, 0, content = "{\"name\":\"$s\"}")
    private fun joined(p: String, at: Long, nm: String, vararg extra: List<String>, by: String = gk, h: String = "general") =
        ev(by, at, 9000, t("h", h), t("p", p, "member"), t("joined", "belfry"), t("name", nm), *extra)

    private val linaJoin = joined(lina, t0 + 10, "Lina M")
    private val events = listOf(
        ev(alice, t0, 9007, t("h", "general")),
        ev(alice, t0, 9002, t("h", "general"), t("name", "general")),
        name(alice, "Ana Silva"), name(lina, "Lina Moreau"), name(kit, "Kit"),
        ev(alice, t0 + 1, 9, t("h", "general"), content = "hi"),
        linaJoin,
        joined(lina, t0 + 50, "Lina again"), // twice: the first counts
        joined(kit, t0 + 20, "Kit", t("agent", "1")),
        joined(noProfile, t0 + 25, "Noor"),
        // removed since, never wrote: no line
        joined(gone, t0 + 30, "Gone"), ev(gk, t0 + 40, 9001, t("h", "general"), t("p", gone)),
        // removed since, but wrote: keeps the line
        joined(goneWrote, t0 + 30, "Wren"), ev(goneWrote, t0 + 35, 9, t("h", "general"), content = "hello"), ev(gk, t0 + 40, 9001, t("h", "general"), t("p", goneWrote)),
        // a guest's 9000: its own join line, never a belfry line
        ev(gk, t0 + 30, 9000, t("h", "general"), t("p", guest, "member"), t("guest", "2026-12-12"), t("invited-by", alice), t("joined", "belfry")),
        // a reading guest
        joined(reader, t0 + 30, "Reader", t("reading", "1")),
        // an older 9000 without the tag
        ev(gk, t0 + 5, 9000, t("h", "general"), t("p", old, "member")),
        // not the groups key / not general
        joined(stranger, t0 + 30, "Stranger", by = alice),
        joined(stranger, t0 + 31, "Stranger", h = "garden"),
    )
    private val w = World(events, emptyList(), WorldContext(me = alice, domain = "example.org", communityName = "Riverside Commons", groupsKey = gk, zone = ZoneId.of("UTC"), agents = mapOf(kit to alice)))

    @Test fun onlyTaggedJoinsByTheGroupsKeyInGeneral() {
        assertEquals(listOf(lina, kit, noProfile, goneWrote), w.belfryJoins("general").map { it.pubkey })
        assertTrue(w.belfryJoins("garden").isEmpty())
    }

    @Test fun oncePerPersonTheFirst() {
        val l = w.belfryJoins("general").single { it.pubkey == lina }
        assertEquals(linaJoin.id, l.id)
        assertEquals(t0 + 10, l.at)
    }

    @Test fun guestsKeepTheirOwnLineOnly() {
        assertTrue(w.guestJoins("general").any { it.pubkey == guest })
        assertTrue(w.feed("general").none { it is FeedItem.BelfryJoined && it.j.pubkey == guest })
    }

    @Test fun inTheFeedAtItsTime() {
        val feed = w.feed("general")
        val items = feed.filterIsInstance<FeedItem.BelfryJoined>()
        assertEquals(4, items.size)
        assertEquals(feed.sortedBy { it.at }.map { it.at }, feed.map { it.at })
    }

    @Test fun words() {
        val js = w.belfryJoins("general").associateBy { it.pubkey }
        assertEquals("Lina Moreau" to "Lina Moreau joined Riverside Commons", w.belfryJoinLine(js.getValue(lina)))
        assertEquals("Kit" to "Kit, your agent, joined Riverside Commons", w.belfryJoinLine(js.getValue(kit)))
        assertEquals("Noor joined Riverside Commons", w.belfryJoinLine(js.getValue(noProfile)).second, "no profile: the name tag")
        val bob = "5".repeat(64)
        val w2 = World(events + name(bob, "Bob"), emptyList(), WorldContext(me = bob, groupsKey = gk, zone = ZoneId.of("UTC"), agents = mapOf(kit to alice)))
        assertEquals("Kit, Ana Silva's agent, joined the belfry", w2.belfryJoinLine(w2.belfryJoins("general").single { it.pubkey == kit }).second)
    }

    @Test fun noGroupsKeyNoLines() {
        val w2 = World(events, emptyList(), WorldContext(me = alice, zone = ZoneId.of("UTC")))
        assertTrue(w2.belfryJoins("general").isEmpty())
    }

    @Test fun feedKeyChangesWithANewJoin() {
        val w2 = World(events + joined(stranger, t0 + 60, "Sam"), emptyList(), w.ctx)
        assertNotEquals(w.feedKey("general"), w2.feedKey("general"))
    }
}
