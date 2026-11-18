package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Claim, assign, unassign a to-do (SPEC 19.40): what each writes, and what everyone reads. */
class TodoAssignTest {
    private val ana = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val cleo = "c".repeat(64)
    private val g = "garden"
    private var n = 0
    private val t0 = 1_793_000_000L

    private fun world(events: List<Event>, me: String = ana) =
        World(events, emptyList(), WorldContext(me = me, domain = "belfry.example", communityName = "Belfry", zone = ZoneId.of("UTC")), null)

    /** The event an action would make, signed by WHO at AT. */
    private fun signed(d: Draft, who: String, at: Long): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), who, at, d.kind, d.tags, d.content)
    }

    private val made = signed(Actions.addTask(g, "Water the beans"), ana, t0)
    private fun task(events: List<Event>, me: String = ana) = world(listOf(made) + events, me).tasks(g).single()

    @Test fun theWireFormat() {
        // to me: the plain claim every client reads
        val claim = Actions.assignTask(g, made.id, ana, me = ana)
        assertEquals(2101, claim.kind)
        assertEquals(listOf("action", "claim"), claim.tags.first { it[0] == "action" })
        assertNull(claim.tags.firstOrNull { it[0] == "p" })
        // to someone else: `assign` + p, with the list's t and the group's h
        val give = Actions.assignTask(g, made.id, bob, me = ana)
        assertEquals(listOf(listOf("e", made.id), listOf("action", "assign"), listOf("p", bob), listOf("t", World.listIdFor(g)), listOf("h", g)), give.tags)
        // off: my own = unclaim; someone else's = unassign + p
        assertEquals(listOf("action", "unclaim"), Actions.unassignTask(g, made.id, ana, me = ana).tags.first { it[0] == "action" })
        val off = Actions.unassignTask(g, made.id, bob, me = ana)
        assertEquals(listOf("action", "unassign"), off.tags.first { it[0] == "action" })
        assertEquals(listOf("p", bob), off.tags.first { it[0] == "p" })
    }

    @Test fun claimAssignUnassign() {
        assertNull(task(emptyList()).assignee)
        val claimed = signed(Actions.assignTask(g, made.id, ana, ana), ana, t0 + 1)
        assertEquals(ana, task(listOf(claimed)).assignee)
        // Ana gives it to Bob
        val given = signed(Actions.assignTask(g, made.id, bob, ana), ana, t0 + 2)
        assertEquals(bob, task(listOf(claimed, given)).assignee)
        // Cleo takes Bob off it
        val off = signed(Actions.unassignTask(g, made.id, bob, cleo), cleo, t0 + 3)
        assertNull(task(listOf(claimed, given, off)).assignee)
        // an unassign naming someone who is not on it changes nothing
        val stale = signed(Actions.unassignTask(g, made.id, cleo, ana), ana, t0 + 4)
        assertEquals(bob, task(listOf(claimed, given, stale)).assignee)
        // Bob lets go of it himself (unclaim)
        val letGo = signed(Actions.unassignTask(g, made.id, bob, bob), bob, t0 + 5)
        assertNull(task(listOf(given, letGo)).assignee)
        // the steps are kept for the thread's lines
        assertEquals(listOf("created", "claim", "assign", "unassign"), task(listOf(claimed, given, off)).history.map { it.what })
        assertEquals("took it and gave it to someone", TaskSteps.text(listOf("claim", "assign")))
    }

    @Test fun anAssignWithoutAKeyIsIgnored() {
        val bad = Event("f".repeat(64), ana, t0 + 1, 2101, listOf(listOf("e", made.id), listOf("action", "assign"), listOf("p", "nope"), listOf("t", World.listIdFor(g)), listOf("h", g)), "")
        assertNull(task(listOf(bad)).assignee)
    }

    @Test fun whoItCanGoTo() {
        val inChannel = Event("e".repeat(64), bob, t0 + 1, 9, listOf(listOf("h", g)), "hi")
        val list = world(listOf(made, inChannel)).todoAssignees(g)
        assertEquals(ana, list.first())
        assertEquals(setOf(ana, bob), list.toSet())
    }
}
