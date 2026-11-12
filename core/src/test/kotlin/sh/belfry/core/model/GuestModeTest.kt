package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guest mode (SPEC 19.26), the cases of the web's b2e968b that vectors/guestmode.json does not hold. */
class GuestModeTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val gk = "9".repeat(64)
    private var n = 0
    private val t0 = 1_793_000_000L

    private fun ev(pk: String, at: Long, kind: Int, vararg tags: List<String>, content: String = ""): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags.toList(), content)
    }
    private fun t(vararg v: String) = v.toList()
    private fun world(events: List<Event>, me: String = alice, prev: World? = null) =
        World(events, emptyList(), WorldContext(me = me, domain = "example.org", communityName = "Example", groupsKey = gk, zone = ZoneId.of("UTC")), prev)

    private fun reading(p: String, at: Long, h: String = "general") =
        ev(gk, at, 9000, t("h", h), t("p", p, "member"), t("guest", "2026-12-12"), t("invited-by", alice), t("unsaved", "1"), t("reading", "1"))
    private fun joined(p: String, at: Long, name: String, h: String = "general") =
        ev(gk, at, 9000, t("h", h), t("p", p, "member"), t("guest", "2026-12-12"), t("invited-by", alice), t("name", name))

    private val base = listOf(
        ev(alice, t0, 9007, t("h", "general")),
        ev(alice, t0, 9002, t("h", "general"), t("name", "general")),
        ev(alice, t0 + 1, 9, t("h", "general"), content = "hi"),
    )

    @Test
    fun readingThenJoiningGivesTheJoinLineAtTheJoin() {
        val w1 = world(base + reading(bob, t0 + 10))
        assertEquals(setOf(bob), w1.group("general")!!.reading)
        assertFalse(bob in w1.group("general")!!.members)
        assertTrue(w1.guestJoins("general").isEmpty())
        assertTrue(w1.feed("general").none { it is FeedItem.Join })
        assertEquals(1, w1.readingCount("general"))

        val w2 = world(base + reading(bob, t0 + 10) + joined(bob, t0 + 50, "Bob"), prev = w1)
        assertTrue(w2.group("general")!!.reading.isEmpty())
        assertTrue(bob in w2.group("general")!!.members)
        val line = w2.guestJoins("general").single()
        assertEquals(bob, line.pubkey)
        assertEquals(t0 + 50, line.at)
        assertFalse(line.unsaved)
        // the feed's redraw key moved with it
        assertNotEquals(w1.feedKey("general"), w2.feedKey("general"))
        assertEquals(0, w2.readingCount("general"))
    }

    @Test
    fun aLaterReading9000PutsThemBackAndOnlyThatChannelCounts() {
        // reading in one channel does not touch another one where they are a member
        val w = world(base + ev(alice, t0, 9007, t("h", "garden")) + ev(bob, t0 + 2, 9, t("h", "garden"), content = "hello") + reading(bob, t0 + 10))
        assertFalse(bob in w.group("general")!!.members)
        assertTrue(bob in w.group("garden")!!.members)
        assertTrue(w.group("garden")!!.reading.isEmpty())
        assertTrue(bob !in w.readingOnly) // a member somewhere: still someone
    }

    @Test
    fun readingGuestsAreNotMentionableNorActive() {
        val profile = ev(bob, t0 + 11, 0, content = """{"name":"Bob"}""")
        val seen = ev(bob, t0 + 12, 30078, t("d", "seen:general"), t("h", "general"))
        val w = world(base + reading(bob, t0 + 10) + profile + seen + ev(gk, t0 + 13, 39002, t("d", "general"), t("p", alice), t("p", bob)))
        assertTrue(w.people.none { it.first == bob })
        assertTrue(w.recentlyActive("general", t0 + 20).none { it.pubkey == bob })
        assertFalse(bob in w.group("general")!!.members) // even though the relay's list has them
        assertTrue(w.memberRows("general").none { it.pubkey == bob })
    }

    @Test
    fun aHeldMeetingGuestIsNotHidden() {
        val held = ev(gk, t0 + 10, 9000, t("h", "general"), t("p", carol, "member"), t("guest", "2026-12-12"), t("invited-by", alice), t("name", "Carol"), t("unsaved", "1"))
        val w = world(base + held)
        assertTrue(carol in w.group("general")!!.members)
        assertTrue(w.group("general")!!.reading.isEmpty())
        assertTrue(w.guestJoins("general").single().unsaved)
    }

    @Test
    fun theLabelOrARemovalEndsReading() {
        val saved = ev(gk, t0 + 20, 1985, t("h", "general"), t("L", World.GUEST_NS), t("l", "saved", World.GUEST_NS), t("p", bob))
        assertTrue(world(base + reading(bob, t0 + 10) + saved).group("general")!!.reading.isEmpty())
        val member = ev(gk, t0 + 20, 1985, t("h", "general"), t("L", World.GUEST_NS), t("l", "member", World.GUEST_NS), t("p", bob))
        assertTrue(world(base + reading(bob, t0 + 10) + member).group("general")!!.reading.isEmpty())
        val out = ev(gk, t0 + 20, 9001, t("h", "general"), t("p", bob))
        val w = world(base + reading(bob, t0 + 10) + out)
        assertTrue(w.group("general")!!.reading.isEmpty())
        assertFalse(bob in w.group("general")!!.members)
    }

    @Test
    fun myOwnReadingKeepsMeInMyChannelAndMakesMeAGuest() {
        val w = world(base + reading(bob, t0 + 10), me = bob)
        assertTrue(bob in w.group("general")!!.members)
        assertTrue(w.amGuest)
        assertEquals(0, w.readingCount("general"))
        assertTrue(w.readingOnly.isEmpty())
    }

    @Test
    fun fixedTexts() {
        assertEquals("Ana Lima", GuestMode.cleanName("  Ana \n  Lima "))
        assertEquals(100, GuestMode.cleanName("x".repeat(300)).length)
        assertTrue(GuestMode.isEmail(" ana@example.org "))
        assertFalse(GuestMode.isEmail("ana@example"))
        assertFalse(GuestMode.isEmail("ana example.org"))
        assertEquals("Opening…", GuestMode.openingLine(null))
        assertEquals("Opening # garden…", GuestMode.openingLine("garden"))
        assertEquals("You’re reading # garden as a guest.", GuestMode.barLine("# garden"))
        assertNull(GuestMode.readingLine(0))
        assertEquals("1 guest is reading: they opened an invite link and have not joined yet.", GuestMode.readingLine(1))
        assertEquals("3 guests are reading: they opened an invite link and have not joined yet.", GuestMode.readingLine(3))
        assertEquals("That did not work. Try again.", GuestMode.message("<script>"))
        for (c in GuestMode.CODES) assertTrue(GuestMode.message(c).isNotEmpty())
        // only a name@domain of this belfry goes into the native sign-in's field
        assertEquals("ana@belfry.sh", GuestMode.signInPrefill(" Ana@Belfry.sh ", "belfry.sh"))
        assertNull(GuestMode.signInPrefill("ana@gmail.com", "belfry.sh"))
        assertNull(GuestMode.signInPrefill("a@b@belfry.sh", "belfry.sh"))
        // the belfry's nip05 for the account wins; another domain's or a malformed one is ignored
        assertEquals("ana.lima@belfry.sh", GuestMode.signInPrefill("ana@gmail.com", "belfry.sh", "Ana.Lima@belfry.sh"))
        assertNull(GuestMode.signInPrefill("ana@gmail.com", "belfry.sh", "ana@evil.example"))
        assertEquals("ana@belfry.sh", GuestMode.signInPrefill("ana@belfry.sh", "belfry.sh", "<b>x</b>"))
        assertEquals("That code is not right. 3 tries left.", GuestMode.message("wrong_code", 3))
        assertEquals("That code is not right. 1 try left.", GuestMode.message("wrong_code", 1))
        assertEquals("That code is not right. Send a new one.", GuestMode.message("wrong_code", 0))
        assertEquals(GuestMode.message("wrong_code"), GuestMode.message("wrong_code", null))
    }
}
