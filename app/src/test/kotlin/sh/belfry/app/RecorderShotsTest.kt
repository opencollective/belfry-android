package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.rec.Recorder
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.PlusSheetContent
import sh.belfry.app.ui.chat.RecView
import sh.belfry.app.ui.chat.RecorderBody
import sh.belfry.app.ui.chat.RecordingBarContent
import sh.belfry.app.ui.chat.rememberComposerState
import sh.belfry.app.ui.kit.SheetContent
import sh.belfry.core.audio.RecMeta
import sh.belfry.core.audio.RecNotice
import sh.belfry.core.audio.RecPhase
import sh.belfry.core.audio.Recordings
import sh.belfry.core.engine.Demo
import java.io.File

/** Audio recording (SPEC 19.25): the sheet's steps and the bar in the chat, without a microphone. */
class RecorderShotsTest : DemoShots() {
    @Composable
    private fun Body(view: RecView, ms: Long = 0, level: Float = 0f, notice: String? = null, events: Boolean = false, settings: Boolean = false) {
        SheetContent(title = "Audio recording") {
            RecorderBody(
                view = view, ms = ms, level = level, notice = notice,
                events = if (events) Recordings.candidates(world.calendar, world.calendar.minOf { it.start } * 1000) else emptyList(),
                channelName = "general",
                eventLine = { e -> ChatFormat.day(world, e.start, session.now()) + " · " + ChatFormat.time(world, e.start) },
                onStart = {}, onPause = {}, onResume = {}, onStop = {}, onPost = {}, onDiscard = {},
                onSettings = if (settings) ({}) else null,
            )
        }
    }

    @Test fun ready() = demoShot("recorder-ready") { Body(RecView.READY) }
    @Test fun denied() = demoShot("recorder-denied") { Body(RecView.READY, notice = RecNotice.NO_PERMISSION.text, settings = true) }
    @Test fun recording() = demoShot("recorder-recording") { Body(RecView.RECORDING, ms = 754_000, level = 0.6f) }
    @Test fun pausedByACall() = demoShot("recorder-paused-call") { Body(RecView.PAUSED, ms = 3_723_000, notice = RecNotice.INTERRUPTED.text) }
    @Test fun where() = demoShot("recorder-where") { Body(RecView.WHERE, ms = 754_000, events = true) }
    @Test fun whereNoEvent() = demoShot("recorder-where-none") { Body(RecView.WHERE, ms = 61_000) }

    @Test fun bars() = demoShot("recording-bars") {
        Column(Modifier.fillMaxSize()) {
            RecordingBarContent(live = false to 754_000L, notice = null, unfinished = null, onOpen = {}, onPost = {}, onDiscard = {})
            Spacer(Modifier.height(16.dp))
            RecordingBarContent(live = true to 3_723_000L, notice = RecNotice.INTERRUPTED.text, unfinished = null, onOpen = {}, onPost = {}, onDiscard = {})
            Spacer(Modifier.height(16.dp))
            RecordingBarContent(live = null, notice = null, unfinished = "12:34 · Today", onOpen = {}, onPost = {}, onDiscard = {})
        }
    }

    @Test fun plusHasTheRecorder() = demoShot("composer-plus-recording") {
        SheetContent { PlusSheetContent(Demo.GENERAL, admin = true, composer = rememberComposerState(), onClose = {}, go = {}, onTodo = {}, onRecord = {}) }
    }

    /**
     * A recording cut short (the app was killed while it recorded) is found on the device, measured
     * from its file, and posted as the web posts one: a message with an .m4a that plays on its own.
     */
    @Test
    fun aRecordingLeftOnTheDeviceIsFoundAndPosted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = Recorder.dir(context).apply { mkdirs() }
        val id = "0123456789abcdef0123456789abcdef"
        val aac = javaClass.getResourceAsStream("/audio/sample.aac")!!.use { it.readBytes() }
        // cut in the middle of a frame, as a kill would leave it
        File(dir, "$id.aac").writeBytes(aac.copyOf(aac.size - 50))
        val startedAt = (DemoShots.NOW - 3600) * 1000
        File(dir, "$id.json").writeText(RecMeta(id, session.accountId, Demo.GENERAL, startedAt, 1_000, RecPhase.RECORDING).toJsonString())
        // another account's recording is not this one's to post
        File(dir, "f".repeat(32) + ".aac").writeBytes(aac)
        File(dir, "f".repeat(32) + ".json").writeText(RecMeta("f".repeat(32), "someone@else.example", Demo.GENERAL, startedAt, 1_000, RecPhase.STOPPED).toJsonString())

        val left = Recorder.unfinished(context, session.accountId)
        assertEquals(listOf(id), left.map { it.id })
        assertEquals(RecPhase.STOPPED, left[0].phase)
        assertEquals(79 * 1024L * 1000 / 32_000, left[0].ms)

        started()
        runBlocking { Recorder.post(context, session, left[0], Demo.GENERAL) }
        var found = false
        repeat(200) {
            settle()
            val m = world.messages(Demo.GENERAL).lastOrNull { it.text.startsWith("🎙 Recording · 0:02") }
            if (m != null && m.media.any { it.mime == "audio/mp4" && it.name?.endsWith(".m4a") == true }) { found = true; return@repeat }
            Thread.sleep(25)
        }
        assertTrue("the recording was not posted", found)
        assertFalse(File(dir, "$id.aac").exists())
        assertFalse(File(dir, "$id.json").exists())
        assertTrue(File(dir, "f".repeat(32) + ".aac").exists())
        assertTrue(Recorder.unfinished(context, session.accountId).isEmpty())
    }
}
