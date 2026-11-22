package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Someone taken out who never wrote shows nowhere (the lead, 2026-11-22: Enspiral's guests "Marcel"
 * with one 9000 and one 9001 each in general); someone taken out who wrote shows only as an author.
 */
class RemovedSilentTest {
    private val alice = "a".repeat(64)
    private val marcel = "b".repeat(64)
    private val wrote = "c".repeat(64)
    private val back = "d".repeat(64)
    private val gk = "9".repeat(64)
    private var n = 0
    private val t0 = 1_793_000_000L

    private fun ev(pk: String, at: Long, kind: Int, vararg tags: List<String>, content: String = ""): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags.toList(), content)
    }
    private fun t(vararg v: String) = v.toList()
    private fun name(pk: String, s: String, at: Long) = ev(pk, at, 0, content = "{\"name\":\"$s\"}")
    private fun guest(p: String, at: Long) = ev(gk, at, 9000, t("h", "general"), t("p", p, "member"), t("guest", "2026-12-12"), t("invited-by", alice))
    private fun out(p: String, at: Long) = ev(gk, at, 9001, t("h", "general"), t("p", p))

    private val events = listOf(
        ev(alice, t0, 9007, t("h", "general")),
        ev(alice, t0, 9002, t("h", "general"), t("name", "general")),
        ev(alice, t0 + 1, 9, t("h", "general"), content = "hi"),
        name(marcel, "Marcel", t0 + 2), guest(marcel, t0 + 10), out(marcel, t0 + 20),
        name(wrote, "Wren", t0 + 2), guest(wrote, t0 + 10), ev(wrote, t0 + 12, 9, t("h", "general"), content = "hello all"), out(wrote, t0 + 20),
        name(back, "Bea", t0 + 2), guest(back, t0 + 10), out(back, t0 + 20), guest(back, t0 + 30),
    )
    private val w = World(events, emptyList(), WorldContext(me = alice, domain = "example.org", communityName = "Example", groupsKey = gk, zone = ZoneId.of("UTC")))

    @Test fun removedWhoNeverWroteShowsNowhere() {
        assertEquals(setOf(marcel), w.removedSilent)
        assertFalse(w.people.any { it.first == marcel }, "not in the mention picker")
        assertFalse(marcel in w.memberKeys(), "not in Members")
        assertTrue(w.guestJoins("general").none { it.pubkey == marcel }, "no join line")
    }

    @Test fun removedWhoWroteStaysTheAuthor() {
        assertFalse(wrote in w.removedSilent)
        assertTrue(w.messages("general").any { it.pubkey == wrote && it.text == "hello all" })
        assertFalse(wrote in w.group("general")!!.members, "not a member any more")
    }

    @Test fun putBackInIsNotRemoved() {
        assertFalse(back in w.removedSilent)
        assertTrue(back in w.group("general")!!.members)
    }
}
