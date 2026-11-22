package sh.belfry.app

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.info.ChannelPlaceScreen
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.core.engine.Demo

/** A channel's pull-down and Settings (SPEC 19.60; canvas channels/pull-down, channels/settings, channels/settings-townsquare). */
class ChannelSettingsShotsTest : DemoShots() {
    private fun field(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))

    /** channels/pull-down: the description, the pinned message, the rows; no e-mail address line. */
    @Test fun pullDownWithoutTheAddress() {
        val chat = ChatState(Demo.GENERAL).apply { lineOpen = true }
        demoShot("settings-pull-down") { ChannelScreen(Demo.GENERAL, onMenu = {}, chat = chat) }
        compose.onNodeWithTag("pull-about-line").assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("E-mails to", substring = true).fetchSemanticsNodes().isEmpty())
    }

    /** channels/settings: Posts as four switches, two of them off; the rows in Xavier's order. */
    @Test fun settingsInTheNewOrder() {
        demoShot("settings-order", act = {
            compose.onNodeWithTag("posts-thread").performScrollTo().performClick()
            compose.onNodeWithTag("posts-proposal").performScrollTo().performClick()
            compose.waitForIdle()
        }) { ChannelPlaceScreen(Demo.GARDEN, ChannelPlaceKey.SETTINGS) }
        // the order: Posts above E-mail above Pin; no Notifications
        compose.onNodeWithTag("settings-address").assertExists()
        compose.onNodeWithTag("pin-me").assertExists()
        assertTrue(compose.onAllNodesWithTag("notify-everything").fetchSemanticsNodes().isEmpty())
    }

    /** The last kind on can't be switched off. */
    @Test fun oneKindStaysOn() {
        compose.setContent { sh.belfry.app.ui.theme.BelfryTheme { InDemo { ChannelPlaceScreen(Demo.GARDEN, ChannelPlaceKey.SETTINGS) } } }
        for (k in listOf("message", "thread", "event")) compose.onNodeWithTag("posts-$k").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("posts-proposal").performScrollTo().performClick()
        compose.waitForIdle()
        // still on: the switch is locked
        compose.onNodeWithTag("posts-proposal").assertIsNotEnabledCompat()
    }

    /** channels/settings → E-mail: a belfry admin edits the part before the @, the live check says Available. */
    @Test fun editableAddressAvailable() {
        demoShot("settings-address", act = {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("address-field").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("address-field").performScrollTo()
            field("address-field").performTextClearance()
            field("address-field").performTextInput("veg")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("✓ Available").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("address-state").performScrollTo()
        }) { ChannelPlaceScreen(Demo.GARDEN, ChannelPlaceKey.SETTINGS) }
    }

    /** channels/settings-townsquare: the pin is one plain line. */
    @Test fun townsquareSettings() {
        demoShot("settings-townsquare", act = {
            compose.onNodeWithTag("pin-main").performScrollTo()
            compose.waitForIdle()
        }) { ChannelPlaceScreen(Demo.GENERAL, ChannelPlaceKey.SETTINGS) }
        assertTrue(compose.onAllNodesWithText("always first in everyone's menu", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithTag("pin-me").fetchSemanticsNodes().isEmpty())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotEnabledCompat() {
        assert(androidx.compose.ui.test.isNotEnabled())
    }
}
