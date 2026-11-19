package sh.belfry.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The lead's rule (2026-11-19): the relay's refusal is a line; the full screen only on the register's "no role". */
class RefusalTest {
    @Test
    fun theRegisterDecides() {
        assertTrue(RegisterAnswer.fullScreen(RegisterAnswer.of(null)), "no role at all → the screen")
        assertFalse(RegisterAnswer.fullScreen(RegisterAnswer.of("guest")), "a guest → the line")
        assertFalse(RegisterAnswer.fullScreen(RegisterAnswer.of("member")), "a member → the line")
        assertFalse(RegisterAnswer.fullScreen(RegisterAnswer.of("agent")), "an agent → the line")
        assertFalse(RegisterAnswer.fullScreen(RegisterAnswer.NO_ANSWER), "no answer → the line")
        assertEquals(RegisterAnswer.NO_ROLE, RegisterAnswer.of(null))
    }
}
