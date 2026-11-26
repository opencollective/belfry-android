package sh.belfry.app

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Test
import sh.belfry.app.ui.cards.EventScreen
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.eventByLink

/** The event's page, full (SPEC 19.93; Event form canvas evview-full; was canvas v113 events/event-full-address): Our page, Copy and QR icons. */
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
        compose.onNodeWithTag("event-url").assertExists()
        compose.onNodeWithTag("event-qr").assertExists()
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
        compose.mainClock.autoAdvance = true
    }
}
