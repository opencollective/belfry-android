package sh.belfry.app

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.belfry.app.ui.cards.EventScreen
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.eventByLink

/** canvas v113 events/event-full-address and -copied: Address, then Copy link · Share… (equal widths, 50 dp). */
class EventAddressShotsTest : DemoShots() {
    private val repair get() = world.eventByLink(Demo.REPAIR_D)!!

    private fun open() {
        compose.onNodeWithTag("event-handle").performClick() // half → full
        compose.waitForIdle()
        compose.onNodeWithTag("event-copy").performScrollTo()
        compose.waitForIdle()
    }

    @Test fun address() {
        demoShot("event-full-address", act = { open() }) { EventScreen(repair.coord) }
        compose.onNodeWithTag("event-address").assertExists()
        val copy = compose.onNodeWithTag("event-copy").getBoundsInRoot()
        val share = compose.onNodeWithTag("event-share").getBoundsInRoot()
        assertEquals(copy.right - copy.left, share.right - share.left)
        assertEquals(50.dp, copy.bottom - copy.top)
        assertEquals(50.dp, share.bottom - share.top)
    }

    @Test fun copied() {
        demoShot("event-full-address-copied", act = {
            open()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("event-copy").performClick()
            compose.mainClock.advanceTimeBy(300)
        }) { EventScreen(repair.coord) }
        compose.onNodeWithText("Copied").assertExists()
        compose.mainClock.advanceTimeBy(2_500)
        compose.onNodeWithText("Copy link").assertExists()
        compose.mainClock.autoAdvance = true
    }
}
