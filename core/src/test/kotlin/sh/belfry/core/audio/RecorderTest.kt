package sh.belfry.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recorder's rules (SPEC 19.25), without a microphone. Times in ms. */
class RecorderTest {
    private val roomy = 2_000L * 1024 * 1024
    private val id = "0123456789abcdef0123456789abcdef"
    private fun started(at: Long = 1_000): RecorderMachine = RecorderMachine().also {
        assertEquals(listOf(RecCommand.SAVE, RecCommand.START_ENGINE), it.start(id, "me@one.example", "general", at, roomy))
    }

    @Test
    fun recordPauseResumeStop() {
        val m = started(1_000)
        assertTrue(m.state.live)
        assertEquals(RecPhase.RECORDING, m.state.meta!!.phase)
        assertEquals(4_000, m.state.elapsed(5_000))
        assertEquals(listOf(RecCommand.PAUSE_ENGINE, RecCommand.SAVE), m.pause(PauseBy.USER, 5_000))
        assertEquals(RecPhase.PAUSED, m.state.meta!!.phase)
        // a pause is not counted
        assertEquals(4_000, m.state.elapsed(60_000))
        assertEquals(emptyList(), m.pause(PauseBy.USER, 61_000), "pausing twice does nothing")
        assertEquals(listOf(RecCommand.RESUME_ENGINE, RecCommand.SAVE), m.resume(PauseBy.USER, 65_000))
        assertEquals(emptyList(), m.resume(PauseBy.USER, 65_500))
        assertEquals(6_000, m.state.elapsed(67_000))
        assertEquals(listOf(RecCommand.STOP_ENGINE, RecCommand.SAVE), m.stop(70_000))
        val meta = m.state.meta!!
        assertEquals(RecPhase.STOPPED, meta.phase)
        assertEquals(9_000, meta.ms)
        assertFalse(m.state.live)
        assertEquals(9_000, m.state.elapsed(999_000))
        assertEquals(emptyList(), m.stop(71_000))
        assertEquals(emptyList(), m.pause(PauseBy.USER, 71_000))
        assertEquals(emptyList(), m.tick(72_000, roomy))
        // posted or thrown away: nothing left
        m.clear()
        assertNull(m.state.meta)
    }

    @Test
    fun oneRecordingAtATime() {
        val m = started()
        assertEquals(emptyList(), m.start("f".repeat(32), "me@one.example", "garden", 2_000, roomy))
        assertEquals(RecNotice.BUSY, m.state.notice)
        assertEquals("general", m.state.meta!!.groupId)
        m.dismissNotice()
        assertNull(m.state.notice)
        // a stopped one that waits to be posted does not block a new one
        m.stop(3_000)
        assertEquals(listOf(RecCommand.SAVE, RecCommand.START_ENGINE), m.start("f".repeat(32), "me@one.example", "garden", 4_000, roomy))
        assertEquals("garden", m.state.meta!!.groupId)
    }

    @Test
    fun aCallPausesAndItGoesOnAfter() {
        val m = started(0)
        assertEquals(listOf(RecCommand.PAUSE_ENGINE, RecCommand.SAVE), m.pause(PauseBy.INTERRUPTION, 10_000))
        assertEquals(RecNotice.INTERRUPTED, m.state.notice)
        assertEquals(PauseBy.INTERRUPTION, m.state.meta!!.pausedBy)
        // the call ended
        assertEquals(listOf(RecCommand.RESUME_ENGINE, RecCommand.SAVE), m.resume(PauseBy.INTERRUPTION, 40_000))
        assertNull(m.state.notice)
        assertEquals(12_000, m.state.elapsed(42_000))
    }

    @Test
    fun aPersonsPauseIsNotUndoneByACallEnding() {
        val m = started(0)
        m.pause(PauseBy.USER, 10_000)
        assertEquals(emptyList(), m.pause(PauseBy.INTERRUPTION, 11_000), "already paused: stays the person's pause")
        assertEquals(emptyList(), m.resume(PauseBy.INTERRUPTION, 40_000))
        assertEquals(RecPhase.PAUSED, m.state.meta!!.phase)
        // paused by a call, then by the person during the call: it waits for the person
        m.resume(PauseBy.USER, 50_000)
        m.pause(PauseBy.INTERRUPTION, 60_000)
        assertEquals(listOf(RecCommand.SAVE), m.pause(PauseBy.USER, 61_000))
        assertNull(m.state.notice)
        assertEquals(emptyList(), m.resume(PauseBy.INTERRUPTION, 90_000))
        assertEquals(listOf(RecCommand.RESUME_ENGINE, RecCommand.SAVE), m.resume(PauseBy.USER, 95_000))
        assertEquals(20_000, m.state.elapsed(95_000))
    }

    @Test
    fun anotherAppTakingTheMicrophoneWaitsForThePerson() {
        val m = started(0)
        m.pause(PauseBy.TAKEN, 5_000)
        assertEquals(RecNotice.TAKEN, m.state.notice)
        assertEquals(emptyList(), m.resume(PauseBy.INTERRUPTION, 9_000))
        assertEquals(listOf(RecCommand.RESUME_ENGINE, RecCommand.SAVE), m.resume(PauseBy.USER, 10_000))
    }

    @Test
    fun lowStorage() {
        val m = RecorderMachine()
        assertEquals(emptyList(), m.start(id, "me@one.example", "general", 0, Recordings.MIN_FREE_TO_START - 1))
        assertEquals(RecNotice.NO_ROOM, m.state.notice)
        assertNull(m.state.meta)
        // room to start; it runs out while recording: stopped and kept
        m.start(id, "me@one.example", "general", 0, roomy)
        assertEquals(listOf(RecCommand.SAVE), m.tick(5_000, roomy))
        assertEquals(5_000, m.state.meta!!.ms)
        assertEquals(7_000, m.state.elapsed(7_000))
        assertEquals(listOf(RecCommand.STOP_ENGINE, RecCommand.SAVE), m.tick(10_000, Recordings.MIN_FREE_TO_GO_ON - 1))
        assertEquals(RecNotice.FULL, m.state.notice)
        assertEquals(RecPhase.STOPPED, m.state.meta!!.phase)
        assertEquals(10_000, m.state.meta!!.ms)
        // while paused too
        val p = started(0)
        p.pause(PauseBy.USER, 3_000)
        assertEquals(emptyList(), p.tick(4_000, roomy))
        assertEquals(listOf(RecCommand.STOP_ENGINE, RecCommand.SAVE), p.tick(5_000, 1))
        assertEquals(3_000, p.state.meta!!.ms)
    }

    @Test
    fun theMicrophoneFails() {
        val m = started(0)
        m.startFailed()
        assertNull(m.state.meta)
        assertEquals(RecNotice.NO_MICROPHONE, m.state.notice)
        val b = started(0)
        assertEquals(listOf(RecCommand.STOP_ENGINE, RecCommand.SAVE), b.broke(8_000))
        assertEquals(RecNotice.BROKE, b.state.notice)
        assertEquals(8_000, b.state.meta!!.ms)
        val n = started(0)
        n.noBackground()
        assertEquals(RecNotice.BACKGROUND, n.state.notice)
        assertTrue(n.state.live)
    }

    @Test
    fun whatIsLeftOnTheDeviceIsOffered() {
        val a = RecMeta(id, "me@one.example", "general", 5_000, 60_000, RecPhase.STOPPED)
        val cut = RecMeta("a".repeat(32), "me@one.example", "garden", 9_000, 12_000, RecPhase.RECORDING)
        val live = RecMeta("b".repeat(32), "me@one.example", "garden", 10_000, 1_000, RecPhase.PAUSED)
        val other = RecMeta("c".repeat(32), "other@two.example", "general", 11_000, 1_000, RecPhase.STOPPED)
        val left = Recordings.unfinished(listOf(a, cut, live, other), live.id, "me@one.example")
        assertEquals(listOf(cut.id, a.id), left.map { it.id }, "newest first, not the live one, not another account's")
        assertTrue(left.all { it.phase == RecPhase.STOPPED }, "one cut short counts as stopped")
        // its small file
        assertEquals(a, RecMeta.fromJsonString(a.toJsonString()))
        assertEquals(RecPhase.RECORDING, RecMeta.fromJsonString(cut.toJsonString())!!.phase)
        assertNull(RecMeta.fromJsonString("{}"))
        assertNull(RecMeta.fromJsonString("""{"id":"../x","account":"a","groupId":"general","startedAt":1}"""))
        assertNull(RecMeta.fromJsonString("""{"id":"$id","account":"a","groupId":"a/b","startedAt":1}"""))
    }
}
