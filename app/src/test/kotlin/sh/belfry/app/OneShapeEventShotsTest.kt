package sh.belfry.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.cards.EventScreen
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.Rsvp

/**
 * One shape, the event screen (SPEC 19.54; canvas one-shape/event-default, -default-online, -tiny,
 * -full): mid while it needs me (cover on top, ‹ ••• floating, Going / Maybe / Can't go), tiny once
 * answered (my answer leads the icon row), full on pull; the answer lands in the event's chat.
 */
class OneShapeEventShotsTest : DemoShots() {
    private fun ev(d: String): CalEvent = world.calendar.first { it.d == d }
    private val swap get() = ev(Demo.SWAP_D)

    private fun has(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The cover is decoded off the main thread: wait for it before the picture. */
    private fun coverIn() {
        compose.waitUntil(30_000) { has("cover-loaded") } // busy CI runners took > 10 s (main run 45022495254)
        compose.waitForIdle()
    }

    @Composable
    private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale), content = content)
    }

    /** one-shape/event-default: not answered yet → opens mid, the cover across the top. */
    @Test fun default() {
        demoShot("os-event-default", act = { coverIn() }) { EventScreen(swap.coord) }
        assertTrue(has("event-screen-half"))
        assertTrue(has("event-cover") && has("event-back") && has("event-more"))
        assertTrue(has("rsvp-accepted") && has("rsvp-tentative") && has("rsvp-declined"))
        assertTrue(has("event-faces") && has("event-hosted-by") && has("event-place"))
    }

    /** one-shape/event-default-online: "Online · Jitsi", Join off until 10 minutes before. */
    @Test fun onlineJoinOff() {
        demoShot("os-event-online-off", act = { coverIn() }) { EventScreen(ev(Demo.PLAN_D).coord) }
        assertTrue(has("event-online") && has("event-join-off") && !has("event-join"))
        assertTrue(compose.onAllNodesWithText("Online · Jitsi").fetchSemanticsNodes().isNotEmpty())
    }

    /** A call happening now, no cover: the usual header, "Online · Google Meet", Join on. */
    @Test fun onlineJoinOnNoCover() {
        demoShot("os-event-online-on") { EventScreen(ev(Demo.CALL_D).coord) }
        assertTrue(has("event-join") && !has("event-cover"))
        assertTrue(compose.onAllNodesWithText("Online · Google Meet").fetchSemanticsNodes().isNotEmpty())
    }

    /** No cover, a place: the title block under the usual header (I host it; people ask to come, so it opens mid). */
    @Test fun noCover() {
        demoShot("os-event-no-cover") { EventScreen(ev(Demo.REPAIR_D).coord) }
        assertTrue(has("event-screen-half") && !has("event-cover"))
    }

    /** one-shape/event-tiny: the two-line header (title, then the icon row) over the chat. */
    @Test fun tiny() {
        demoShot("os-event-tiny", act = {
            compose.onNodeWithTag("event-handle").performClick() // mid → full
            compose.waitForIdle()
            compose.onNodeWithTag("event-handle").performClick() // full → tiny
        }) { EventScreen(swap.coord) }
        assertTrue(has("event-screen-up") && has("event-tiny-header") && has("icon-row") && has("row-item-lead"))
    }

    /** one-shape/event-full: where with Directions, the whole description, who comes, the answer. */
    @Test fun full() {
        demoShot("os-event-full", act = { coverIn(); compose.onNodeWithTag("event-more-details").performClick() }) { EventScreen(swap.coord) }
        assertTrue(has("event-screen-full") && has("event-directions") && has("rsvp-accepted"))
        // the same top as mid: the cover with ‹ ••• and the title, no other header
        assertTrue(has("event-cover") && has("event-title") && !has("event-tiny-header"))
    }

    /** Answered → it still opens mid (every event does), Going marked, and "You're going" in the chat. */
    @Test fun answeredOpensMid() {
        started()
        runBlocking { session.events.rsvp(swap, Rsvp.ACCEPTED) }
        settle()
        assertEquals(Rsvp.ACCEPTED, swap.rsvps[session.me])
        demoShot("os-event-answered") { EventScreen(swap.coord) }
        assertTrue(has("event-screen-half") && has("rsvp-accepted"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("You’re going").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Tapping Going in mid: one answer, the button marked, and the line in the chat; it stays mid. */
    @Test fun tappingGoingAddsTheLine() {
        started()
        compose.setContent { BelfryTheme { InDemo { EventScreen(swap.coord) } } }
        compose.onNodeWithTag("rsvp-accepted").performClick()
        compose.waitUntil(10_000) { swap.rsvps[session.me] == Rsvp.ACCEPTED }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("You’re going").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(has("event-screen-half"))
    }

    /** Font scale 2: nothing clips; the details scroll above the chat. */
    @Test fun fontScale2() {
        demoShot("os-event-default-font2", act = { coverIn() }) { Scaled(2f) { EventScreen(swap.coord) } }
        assertTrue(has("event-title"))
    }
}
