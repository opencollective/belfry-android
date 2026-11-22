package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import sh.belfry.core.outbox.OutboxItem
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Groups: open, closed, private (SPEC 19.59; docs/one-shape.md "Groups: open, closed, private"). */
class GroupJoinTest {
    private val relay = "d".repeat(64)
    private val me = "a".repeat(64)
    private val ben = "b".repeat(64)
    private val emma = "e".repeat(64)
    private var n = 0

    private fun ev(pk: String, kind: Int, at: Long, tags: List<List<String>>, c: String = ""): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags, c)
    }

    private fun world(events: List<Event>, who: String = me, queued: List<OutboxItem> = emptyList()) =
        World(events, queued, WorldContext(me = who, domain = "belfry.example", communityName = "Belfry", zone = ZoneId.of("UTC")), null)

    private val general = listOf(
        ev(relay, 39000, 10, listOf(listOf("d", "general"), listOf("name", "general"))),
        ev(relay, 39002, 10, listOf(listOf("d", "general"), listOf("p", me), listOf("p", ben), listOf("p", emma))),
    )

    /** A pod made by CREATOR with ACCESS, its members by the relay's 39002. */
    private fun pod(id: String, creator: String, join: GroupJoin, members: List<String>, at: Long = 100): List<Event> =
        Actions.createPod(id, "Soup", "Sundays", "general", join.access()).mapIndexed { i, d -> ev(creator, d.kind, at + i, d.tags, d.content) } +
            ev(relay, 39002, at + 5, listOf(listOf("d", id)) + members.map { listOf("p", it) })

    @Test fun theThreeChoicesOnTheWire() {
        fun meta(j: GroupJoin) = Actions.createPod("pod-x-00", "X", "", "general", j.access())
        // 9007 and 9002 both say it; no belfry tag, never `hidden`
        for (k in 0..1) {
            assertTrue(listOf("open") in meta(GroupJoin.OPEN)[k].tags && listOf("public") in meta(GroupJoin.OPEN)[k].tags)
            assertTrue(listOf("closed") in meta(GroupJoin.CLOSED)[k].tags && listOf("public") in meta(GroupJoin.CLOSED)[k].tags)
            assertTrue(listOf("closed") in meta(GroupJoin.PRIVATE)[k].tags && listOf("private") in meta(GroupJoin.PRIVATE)[k].tags)
            for (j in GroupJoin.entries) assertFalse(meta(j)[k].tags.any { it.firstOrNull() == "hidden" })
        }
        // a new Closed group starts with no question
        assertFalse(meta(GroupJoin.CLOSED)[1].tags.any { it.firstOrNull() == "prompt" })
        // Open and Closed are announced in their parent; Private posts nothing anywhere
        assertEquals(1, meta(GroupJoin.OPEN).count { it.kind == 9 })
        assertEquals(1, meta(GroupJoin.CLOSED).count { it.kind == 9 })
        assertEquals(0, meta(GroupJoin.PRIVATE).count { it.kind == 9 })
    }

    @Test fun peopleAddedRightAfterTheCreate() {
        val d = Actions.createPod("pod-x-00", "X", "", "general", GroupJoin.PRIVATE.access(), listOf(ben, emma, ben))
        assertEquals(listOf(9007, 9002, 9000, 9000), d.map { it.kind })
        assertEquals(listOf(listOf("h", "pod-x-00"), listOf("p", ben, "member")), d[2].tags)
        assertEquals(listOf(listOf("h", "pod-x-00"), listOf("p", emma, "member")), d[3].tags)
    }

    @Test fun aPrivateGroupImNotInIsNowhere() {
        val evs = general + pod("pod-secret-01", ben, GroupJoin.PRIVATE, listOf(ben)) + pod("pod-open-02", ben, GroupJoin.OPEN, listOf(ben), 200) +
            ev(ben, 9, 300, listOf(listOf("h", "pod-secret-01")), "the plan")
        val w = world(evs)
        assertNull(w.group("pod-secret-01"))
        assertEquals(listOf("pod-open-02"), w.allPods().map { it.id })
        assertTrue(w.search("plan").isEmpty())
        assertTrue("pod-secret-01" in w.groupIndex.hiddenPrivate)
        // its members see it, as Private
        val b = world(evs, ben)
        assertEquals(GroupJoin.PRIVATE, GroupJoin.of(b.group("pod-secret-01")!!))
        assertEquals(1, b.search("plan").size)
        // added later (a 9000): now it is there
        val added = world(evs + ev(ben, 9000, 400, listOf(listOf("h", "pod-secret-01"), listOf("p", me, "member"))))
        assertNotNull(added.group("pod-secret-01"))
        assertFalse(added.needsJoinBar(added.group("pod-secret-01")!!))
    }

    @Test fun aPrivateGroupTakesNoRequests() {
        val evs = general + pod("pod-p-01", me, GroupJoin.PRIVATE, listOf(me)) + ev(ben, 9021, 300, listOf(listOf("h", "pod-p-01")), "let me in")
        val w = world(evs)
        val g = w.group("pod-p-01")!!
        assertTrue(g.applications.isEmpty())
        assertTrue(w.groupRequestsFor(g).isEmpty())
        assertEquals(0, w.groupRequestCount())
        assertTrue(w.channelLog("pod-p-01").none { it.ev.kind == 9021 })
        assertFalse(g.takesRequests())
        assertTrue(ben !in g.members)
        // the same request to a Closed group is shown to its admin
        val c = world(general + pod("pod-c-02", me, GroupJoin.CLOSED, listOf(me)) + ev(ben, 9021, 300, listOf(listOf("h", "pod-c-02")), "let me in"))
        assertEquals(1, c.groupRequestCount())
        assertTrue(c.group("pod-c-02")!!.takesRequests())
    }

    @Test fun settingsKeepAndChangeTheChoice() {
        val w = world(general + pod("pod-p-01", me, GroupJoin.PRIVATE, listOf(me)))
        val g = w.group("pod-p-01")!!
        // a name change keeps it Private (the relay replaces the whole metadata)
        val rename = Actions.groupMeta(g, name = "Soup!")
        assertTrue(listOf("closed") in rename.tags && listOf("private") in rename.tags)
        assertFalse(rename.tags.any { it.firstOrNull() == "prompt" || it.firstOrNull() == "vouches" })
        // Private → Closed, with a question and vouches
        val closed = Actions.groupMeta(g, closed = true, isPrivate = false, prompt = "Which Sunday?", vouches = 2)
        assertTrue(listOf("public") in closed.tags && listOf("closed") in closed.tags)
        assertTrue(listOf("prompt", "Which Sunday?") in closed.tags && listOf("vouches", "2") in closed.tags)
        // → Open
        val open = Actions.groupMeta(g, closed = false, isPrivate = false)
        assertTrue(listOf("open") in open.tags && listOf("public") in open.tags)
        // read back: a later 9002 saying public makes it Closed
        val after = world(general + pod("pod-p-01", me, GroupJoin.PRIVATE, listOf(me)) + ev(me, closed.kind, 500, closed.tags))
        assertEquals(GroupJoin.CLOSED, GroupJoin.of(after.group("pod-p-01")!!))
        // a Closed group with no question yet: none written
        val c = world(general + pod("pod-c-02", me, GroupJoin.CLOSED, listOf(me))).group("pod-c-02")!!
        assertFalse(Actions.groupMeta(c, name = "Soup").tags.any { it.firstOrNull() == "prompt" })
    }

    @Test fun thePickGridIsTheBelfrysMembers() {
        val w = world(general + ev(ben, 0, 5, emptyList(), "{\"name\":\"Ben Okafor\"}") + ev(emma, 0, 5, emptyList(), "{\"name\":\"Émma Lind\"}"))
        assertEquals(listOf(ben, emma), w.pickableMembers())
        assertEquals(listOf(emma), w.pickableMembers("emma"))
        assertEquals(listOf(ben), w.pickableMembers("ben ok"))
    }

    @Test fun hides() {
        assertTrue(GroupJoin.hides(true, me, setOf(ben), setOf(ben)))
        assertFalse(GroupJoin.hides(true, me, setOf(me), emptySet()))
        assertFalse(GroupJoin.hides(true, me, emptySet(), setOf(me)))
        assertFalse(GroupJoin.hides(true, me, emptySet(), emptySet(), setOf(me)))
        assertFalse(GroupJoin.hides(false, me, emptySet(), emptySet()))
    }
}
