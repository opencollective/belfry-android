package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.withId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelLineTest {
    private val admin = "a".repeat(64)
    private val member = "b".repeat(64)
    private val other = "c".repeat(64)
    private val now = 1_800_000_000L
    private val g = "garden"

    private fun ev(pk: String, at: Long, kind: Int, tags: List<List<String>>, content: String = ""): Event =
        Event("", pk, at, kind, tags, content).withId()

    private fun ev(pk: String, at: Long, d: Draft): Event = ev(pk, at, d.kind, d.tags, d.content)

    private fun base(): MutableList<Event> = mutableListOf(
        ev(admin, now - 5000, 9007, listOf(listOf("h", g))),
        ev(admin, now - 4999, 9002, listOf(listOf("h", g), listOf("name", "garden"))),
        ev(member, now - 4000, 9, listOf(listOf("h", g)), "Watering rota: **Mon** Joris"),
        ev(other, now - 3900, 9, listOf(listOf("h", g)), "Compost is full"),
    )

    private fun world(events: List<Event>, me: String = member, communityAdmins: Set<String> = emptySet()) =
        World(events, emptyList(), WorldContext(me = me, domain = "example.org", communityName = "Example", communityAdmins = communityAdmins, zone = ZoneOffset.UTC))

    @Test
    fun pinsCountOnlyFromAdminsAndTheLatestLabelWins() {
        val events = base()
        val msg = events[2]
        // someone who is not an admin: ignored
        events.add(ev(member, now - 3000, Actions.pinMessage(g, msg.id, true)))
        assertTrue(world(events).pinned(g).isEmpty())
        // the channel's admin
        events.add(ev(admin, now - 2900, Actions.pinMessage(g, msg.id, true)))
        assertEquals(listOf(msg.id), world(events).pinned(g).map { it.id })
        assertTrue(world(events).isPinned(g, msg.id))
        // a non-admin cannot unpin
        events.add(ev(other, now - 2850, Actions.pinMessage(g, msg.id, false)))
        assertEquals(1, world(events).pinned(g).size)
        // unpinned later
        events.add(ev(admin, now - 2800, Actions.pinMessage(g, msg.id, false)))
        assertTrue(world(events).pinned(g).isEmpty())
        // and pinned again: the latest wins, whatever the order of arrival
        events.add(0, ev(admin, now - 2700, Actions.pinMessage(g, msg.id, true)))
        assertEquals(1, world(events).pinned(g).size)
        // a community admin may pin too
        val second = events.first { it.content == "Compost is full" }
        events.add(ev(other, now - 2600, Actions.pinMessage(g, second.id, true)))
        assertEquals(1, world(events).pinned(g).size)
        assertEquals(listOf(second.id, msg.id), world(events, communityAdmins = setOf(other)).pinned(g).map { it.id })
    }

    @Test
    fun pinDraftShape() {
        val d = Actions.pinMessage(g, "m1", true)
        assertEquals(1985, d.kind)
        assertEquals(listOf(listOf("h", g), listOf("L", "belfry/pin"), listOf("l", "pinned", "belfry/pin"), listOf("e", "m1")), d.tags)
        assertEquals("unpinned", Actions.pinMessage(g, "m1", false).tags[2][1])
    }

    @Test
    fun podSides() {
        assertEquals(3, PodShapes.sides(0))
        assertEquals(3, PodShapes.sides(2))
        assertEquals(5, PodShapes.sides(5))
        assertEquals(12, PodShapes.sides(12))
        assertFalse(PodShapes.isCircle(12))
        assertTrue(PodShapes.isCircle(13))
    }

    @Test
    fun aToDoRemembersTheMessageItCameFrom() {
        val events = base()
        val msg = events[3]
        val draft = Actions.addTask(g, "Empty the compost", msg.id)
        assertTrue(draft.tags.contains(listOf("q", msg.id)))
        assertFalse(Actions.addTask(g, "Plain").tags.any { it[0] == "q" })
        val task = ev(member, now - 2000, draft)
        events.add(task)
        events.add(ev(member, now - 1990, Actions.addTask(g, "Plain")))
        var w = world(events)
        assertEquals(msg.id, w.tasks(g).first().source)
        assertNull(w.tasks(g)[1].source)
        assertEquals("To do", w.todoChip(w.todosBySource(g)[msg.id]!!))
        events.add(ev(other, now - 1900, Actions.taskAction(g, task.id, "done")))
        w = world(events)
        assertTrue(w.todosBySource(g)[msg.id]!!.done)
        assertEquals("Done", w.todoChip(w.todosBySource(g)[msg.id]!!))
        assertEquals(1, w.channelFacts(g, now).todos.size)
    }

    @Test
    fun theLinePicksWhatMattersMost() {
        val events = base()
        // nothing special: people
        var line = world(events).channelLine(g, now)
        assertEquals(LineIcon.PEOPLE, line.icon)
        assertTrue(line.quiet)
        assertEquals("2 people active this month", line.text)
        // a topic filter says so first
        assertEquals(LineAction.ShowAll, world(events).channelLine(g, now, topic = "seeds").action)
        // an open proposal (draft: nothing to vote)
        val (_, drafts) = Actions.createProposal(NewProposal("Tool library", "Lend tools", "Text", ProposalStatus.DRAFT), g, admin, "wss://g", "wss://m", now - 1500, "aa11")
        drafts.forEachIndexed { i, d -> events.add(ev(admin, now - 1500 + i, d)) }
        line = world(events).channelLine(g, now)
        assertEquals(LineIcon.PROPOSAL, line.icon)
        assertEquals("Tool library", line.bold)
        assertNull(line.pill)
        // the next event, which I have not answered, comes before it
        val start = now + 2 * 86400
        val (coord, evDrafts) = Actions.createEvent(NewEvent(title = "Seed swap", start = start, end = start + 3600, tzid = "UTC", whenLabel = "soon"), g, admin, "wss://g", "wss://m", "bb22")
        evDrafts.forEachIndexed { i, d -> events.add(ev(admin, now - 1400 + i, d)) }
        line = world(events).channelLine(g, now)
        assertEquals(LineIcon.EVENT, line.icon)
        assertEquals("RSVP", line.pill)
        assertEquals(LineAction.OpenEvent(coord), line.action)
        assertTrue(line.counts.any { it.kind == "proposals" && it.n == 1 && !it.plus })
        // a pinned message comes before an event I answered…
        val w0 = world(events)
        val e = w0.calEvent(coord)!!
        events.add(ev(member, now - 1300, Actions.rsvp(e, Rsvp.ACCEPTED, member, false)))
        line = world(events).channelLine(g, now)
        assertEquals(LineIcon.EVENT, line.icon)
        assertEquals("Going", line.pill)
        assertFalse(line.ask)
        events.add(ev(admin, now - 1200, Actions.pinMessage(g, events[2].id, true)))
        line = world(events).channelLine(g, now)
        assertEquals(LineIcon.PIN, line.icon)
        assertEquals("Watering rota: Mon Joris", line.text)
        assertTrue(line.counts.any { it.kind == "events" && it.n == 1 })
        // …unless I hid it
        assertEquals(LineIcon.EVENT, world(events).channelLine(g, now, hiddenPins = setOf(events[2].id)).icon)
    }
}
