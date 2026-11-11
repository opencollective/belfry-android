package sh.belfry.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskStepsTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun stepsTogetherMergeAndRepeatsDrop() {
        val t = Task("t1", "Book the room", a, a, true, false, emptyList(), "m1", listOf(
            TaskStep(100, a, "created"), TaskStep(101, a, "claim"), TaskStep(102, a, "working"),
            TaskStep(300, a, "working"), TaskStep(500, a, "done"), TaskStep(900, b, "undone"),
        ))
        val g = TaskSteps.groups(listOf(t))
        assertEquals(3, g.size)
        assertEquals("made a to-do, took it and started working on it", TaskSteps.text(g[0].what))
        assertEquals("marked it done", TaskSteps.text(g[1].what))
        assertEquals(b, g[2].who)
        assertEquals("reopened it", TaskSteps.text(g[2].what))
    }
}
