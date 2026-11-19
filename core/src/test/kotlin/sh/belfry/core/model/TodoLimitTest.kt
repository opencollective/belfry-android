package sh.belfry.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** canvas todos/new-todo-limit: a to-do holds 300 characters, never cut silently. */
class TodoLimitTest {
    @Test fun underNearOver() {
        assertNull(TodoLimit.counter("x".repeat(249)))
        assertEquals("250 of 300", TodoLimit.counter("x".repeat(250)))
        assertEquals("300 of 300", TodoLimit.counter("x".repeat(300)))
        assertTrue(TodoLimit.fits("x".repeat(300)))
        assertNull(TodoLimit.error("x".repeat(300)))
        assertNull(TodoLimit.counter("x".repeat(301)))
        assertFalse(TodoLimit.fits("x".repeat(301)))
        assertEquals("1 character too long. A to-do holds 300; put the rest in a comment on it.", TodoLimit.error("x".repeat(301)))
        assertEquals("4 characters too long. A to-do holds 300; put the rest in a comment on it.", TodoLimit.error("x".repeat(304)))
    }

    @Test fun spacesAroundDontCountAndEmptyNeverFits() {
        assertFalse(TodoLimit.fits("   "))
        assertTrue(TodoLimit.fits("  " + "x".repeat(300) + "  "))
        assertNull(TodoLimit.overFrom("  " + "x".repeat(300)))
        assertEquals(302, TodoLimit.overFrom("  " + "x".repeat(305)))
    }

    @Test fun nothingIsCutOnTheWire() {
        val long = "x".repeat(320)
        assertEquals(long, Actions.addTask("g", long).content)
    }
}
