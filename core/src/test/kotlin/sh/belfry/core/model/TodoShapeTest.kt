package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The to-do in one shape: the due date (read, set, cleared by the author's 1010) and its conversation's words. */
class TodoShapeTest {
    private val ana = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val g = "garden"
    private var n = 0
    private val t0 = Instant.parse("2026-11-22T08:00:00Z").epochSecond
    /** Sat 28 Nov 2026, 9 AM UTC */
    private val sat9 = Instant.parse("2026-11-28T09:00:00Z").epochSecond

    private fun world(events: List<Event>, me: String = ana) =
        World(events, emptyList(), WorldContext(me = me, domain = "belfry.example", communityName = "Belfry", zone = ZoneId.of("UTC")), null)

    private fun signed(d: Draft, who: String, at: Long): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), who, at, d.kind, d.tags, d.content)
    }

    private fun profile(who: String, name: String) = Event("f".repeat(63) + who[0], who, t0 - 100, 0, emptyList(), "{\"name\":\"$name\"}")

    private val made = signed(Actions.addTask(g, "Bring the extension cable", from = "9".repeat(64)), ana, t0)
    private fun task(events: List<Event>, me: String = ana) = world(listOf(made) + events, me).tasks(g).single()

    @Test fun dueLabels() {
        val utc = ZoneId.of("UTC")
        assertEquals("Sat 28 Nov, 9 AM", TodoShape.dueLabel(sat9, utc, t0))
        assertEquals("Sat 28 Nov, 9:30 PM", TodoShape.dueLabel(sat9 + 12 * 3600 + 1800, utc, t0))
        assertEquals("Sat 28 Nov, 12 PM", TodoShape.dueLabel(sat9 + 3 * 3600, utc, t0))
        assertEquals("Fri 1 Jan 2027, 12 AM", TodoShape.dueLabel(Instant.parse("2027-01-01T00:00:00Z").epochSecond, utc, t0))
        assertEquals("Sat 28 Nov", TodoShape.dayLabel(sat9, utc, t0))
    }

    @Test fun setKeepsEveryOtherTagAndClears() {
        val set = Actions.setTaskDue(made, "Bring the extension cable", sat9)
        assertEquals(1010, set.kind)
        assertEquals("Bring the extension cable", set.content)
        // the 2100's tags (t, h, q) are all there, then e, k and the due
        for (tag in made.tags) assertTrue(tag in set.tags, "kept $tag")
        assertEquals(listOf("e", made.id), set.tags.first { it[0] == "e" })
        assertEquals(listOf("k", "2100"), set.tags.first { it[0] == "k" })
        assertEquals(listOf("due", sat9.toString()), set.tags.first { it[0] == "due" })
        // a 2100 that already had a due: the old one is replaced, never doubled
        val withDue = signed(Draft(sh.belfry.core.outbox.Target.GROUPS, 2100, "X", made.tags + listOf(listOf("due", "123"))), ana, t0)
        assertEquals(1, Actions.setTaskDue(withDue, "X", sat9).tags.count { it[0] == "due" })
        val clear = Actions.setTaskDue(made, "Bring the extension cable", null)
        assertFalse(clear.tags.any { it[0] == "due" })
    }

    @Test fun dueReadFromTheAuthorsNewestEdit() {
        assertNull(task(emptyList()).due)
        val e1 = signed(Actions.setTaskDue(made, made.content, sat9), ana, t0 + 10)
        assertEquals(sat9, task(listOf(e1)).due)
        // someone else's edit does not count
        val bobs = signed(Actions.setTaskDue(made, made.content, sat9 + 3600), bob, t0 + 20)
        assertEquals(sat9, task(listOf(e1, bobs)).due)
        // the author's newer edit without a due clears it
        val e2 = signed(Actions.setTaskDue(made, made.content, null), ana, t0 + 30)
        assertNull(task(listOf(e1, bobs, e2)).due)
        // order of arrival does not matter: newest created_at wins
        assertNull(task(listOf(e2, e1)).due)
        // a due on the 2100 itself
        val direct = signed(Draft(sh.belfry.core.outbox.Target.GROUPS, 2100, "Y", listOf(listOf("t", World.listIdFor(g)), listOf("h", g), listOf("due", sat9.toString()))), ana, t0)
        assertEquals(sat9, world(listOf(direct)).tasks(g).single().due)
    }

    @Test fun doneAt() {
        val done = signed(Actions.taskAction(g, made.id, "done"), bob, t0 + 50)
        assertEquals(t0 + 50, task(listOf(done)).doneAt)
        val reopened = signed(Actions.taskAction(g, made.id, "undone"), bob, t0 + 60)
        assertNull(task(listOf(done, reopened)).doneAt)
    }

    @Test fun conversationWords() {
        val evs = listOf(
            profile(ana, "Ana Silva"), profile(bob, "Sam Rivera"), made,
            signed(Actions.setTaskDue(made, made.content, sat9), ana, t0 + 10),
            signed(Actions.assignTask(g, made.id, bob, ana), ana, t0 + 20),
            signed(Actions.commentTask(g, made.id, "Found it, it's by the door."), bob, t0 + 30),
            signed(Actions.taskAction(g, made.id, "working"), bob, t0 + 35),
            signed(Actions.taskAction(g, made.id, "done"), bob, t0 + 40),
            signed(Actions.taskAction(g, made.id, "undone"), ana, t0 + 50),
            signed(Actions.setTaskDue(made, made.content, null), ana, t0 + 60),
        )
        val w = world(evs, me = "c".repeat(64))
        val lines = w.todoLines(g, made.id, t0).map { (w.displayName(it.who) + " " + (it.words ?: ": " + it.comment)) }
        assertEquals(
            listOf(
                "Ana Silva made this to-do",
                "Ana Silva set the due date to Sat 28 Nov, 9 AM",
                "Ana Silva gave it to Sam Rivera",
                "Sam Rivera : Found it, it's by the door.",
                "Sam Rivera marked it done",
                "Ana Silva reopened it",
                "Ana Silva cleared the due date",
            ),
            lines,
        )
        // seen by Sam: "gave it to you"
        assertEquals("gave it to you", world(evs, me = bob).todoLines(g, made.id, t0)[2].words)
    }

    @Test fun opensMidWhileItNeedsMe() {
        val w = world(listOf(made))
        assertTrue(w.todoNeedsMe(task(emptyList())))
        assertFalse(w.todoNeedsMe(task(listOf(signed(Actions.assignTask(g, made.id, bob, ana), ana, t0 + 1)))))
        assertTrue(w.todoNeedsMe(task(listOf(signed(Actions.assignTask(g, made.id, ana, ana), ana, t0 + 1)))))
        assertFalse(w.todoNeedsMe(task(listOf(signed(Actions.taskAction(g, made.id, "done"), ana, t0 + 1)))))
    }

    @Test fun logLine() {
        val e = signed(Actions.setTaskDue(made, made.content, sat9), ana, t0 + 10)
        assertEquals("set a to-do's due date", ChannelLog.item(e, emptyMap()) { "x" }?.text)
    }
}

class TodoAttachmentsTest {
    @Test fun counts() {
        val t = Task("x", "T", "a", null, false, false, listOf(
            TaskComment("a", "see https://x.example/a/plugs.JPG and https://x.example/b.pdf?dl=1", 1),
            TaskComment("a", "https://x.example/page and https://x.example/c.webp.", 2),
        ))
        assertEquals(2 to 1, t.attachmentCounts())
    }
}
