package sh.belfry.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.people.EarlierWhere
import sh.belfry.app.ui.people.Enc3DeviceCards
import sh.belfry.app.ui.people.Enc3EarlierLine
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.enc.DeviceCard
import sh.belfry.core.enc.DeviceName
import sh.belfry.core.enc.Enc3DeviceState
import sh.belfry.core.enc.EncDeviceText

/** SPEC 19.85 (format 3, part 5): Share goes once per tap, Remove asks first, Ask once; on a made-up part-5 state. */
class Enc3DeviceTest : DemoShots() {
    private val card = DeviceCard("3".repeat(64), DemoShots.NOW - 600, name = "Chrome on Android")
    private val base = Enc3DeviceState(on = true, listed = true, older = listOf(DeviceName("1".repeat(64), "Safari on iPhone")))

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { BelfryTheme { InDemo(content) } }
        compose.waitForIdle()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(100); compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun shareGoesOncePerTap() {
        session.demoEnc3Device(base.copy(cards = listOf(card)))
        show { Enc3DeviceCards() }
        compose.onNodeWithText(EncDeviceText.CARD_TEXT).assertIsDisplayed()
        compose.onNodeWithTag("enc3-share").performClick()
        compose.onNodeWithTag("enc3-share").performClick() // disabled while it works
        compose.onNodeWithTag("enc3-remove").performClick() // so is Remove
        waitForText(EncDeviceText.shared("Chrome on Android"))
        assertEquals(1, session.demoShares)
        assertEquals(0, session.demoRemovals)
        assertTrue(compose.onAllNodesWithTag("enc3-card").fetchSemanticsNodes().isEmpty())
    }

    @Test fun removeAsksFirst() {
        session.demoEnc3Device(base.copy(cards = listOf(card)))
        show { Enc3DeviceCards() }
        compose.onNodeWithTag("enc3-remove").performClick()
        waitForText(EncDeviceText.removeTitle("Chrome on Android"))
        compose.onNodeWithText(EncDeviceText.REMOVE_TEXT).assertIsDisplayed()
        assertEquals("nothing removed before the person confirms", 0, session.demoRemovals)
        compose.onNodeWithTag("enc3-remove-confirm").performClick()
        waitForText(EncDeviceText.removed("Chrome on Android"))
        assertEquals(1, session.demoRemovals)
    }

    @Test fun askOnceThenWaiting() {
        session.demoEnc3Device(base.copy(demoMissing = 2))
        show { Enc3EarlierLine(EarlierWhere.LIST) }
        compose.onNodeWithText(EncDeviceText.EARLIER_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Safari on iPhone can share them with this one.").assertIsDisplayed()
        compose.waitForIdle()
        compose.onNodeWithTag("enc3-ask").performClick()
        compose.waitUntil(10_000) { session.enc3Device.value.askedAt != null }
        assertNotNull(session.enc3Device.value.askedAt)
        try {
            // the frame clock moved on by hand: with nothing animating, a state that came from another thread may wait for a frame
            compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(100); compose.onAllNodesWithText(EncDeviceText.ASKED_TITLE).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("not shown; state ${session.enc3Device.value}; tree:\n" + compose.onRoot(useUnmergedTree = true).printToString(), e)
        }
        compose.onNodeWithText(EncDeviceText.askedText(listOf("Safari on iPhone"))).assertIsDisplayed()
    }

    @Test fun nothingOnAFormatOneBelfry() {
        session.demoEnc3Device(base.copy(on = false, demoMissing = 2, cards = listOf(card), fork = true))
        show { androidx.compose.foundation.layout.Column { Enc3DeviceCards(); Enc3EarlierLine(EarlierWhere.LIST) } }
        assertTrue(compose.onAllNodesWithTag("enc3-card").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("enc3-fork").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("enc3-earlier").fetchSemanticsNodes().isEmpty())
    }
}
