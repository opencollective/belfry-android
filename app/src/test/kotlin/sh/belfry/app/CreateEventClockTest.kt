package sh.belfry.app

import androidx.compose.ui.test.onNodeWithTag
import org.junit.Test
import sh.belfry.app.ui.cards.CreateEventScreen
import sh.belfry.app.ui.theme.BelfryTheme

/**
 * Diagnosis of the hang in CardsShotsTest.createEvent ("Compose did not get idle", CI 44568908965):
 * the same screen, first in a fresh JVM (forkEvery = 1), with the test clock advanced by hand.
 * Passing here while createEvent hangs = an animation or a clock (no harm on a phone); hanging
 * here too = a real recomposition loop (battery). The lead's method, 2026-11-19.
 */
class CreateEventClockTest : DemoShots() {
    @Test fun newEventSettlesWithAManualClock() {
        compose.mainClock.autoAdvance = false
        compose.setContent { BelfryTheme { InDemo { CreateEventScreen("general") } } }
        repeat(100) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithTag("event-title").assertExists()
    }
}
