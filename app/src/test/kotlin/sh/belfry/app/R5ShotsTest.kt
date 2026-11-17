package sh.belfry.app

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.info.ChannelPlaceScreen
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.Message

/** Round 5: buttons on messages, links, the settings that were not saved, the pull-down without empty rows (demo mode). */
@OptIn(ExperimentalRoborazziApi::class)
class R5ShotsTest : DemoShots() {
    private val versions: List<Message> get() = world.messages(Demo.WEBSITE).filter { world.buttons(it).isNotEmpty() }
    private val open: Message get() = versions.last()

    /** #website: a version published (closed for everyone), one whose publishing failed for me, one to approve. */
    @Test fun websiteButtons() {
        val (v2, v3, v4) = versions
        assertTrue(world.buttonState(v2).closed)
        assertEquals("Published by Maya Okafor.", world.buttonState(v2).closedText)
        assertEquals("error", world.buttonState(v3).mine?.status)
        assertFalse(world.buttonState(v4).closed)
        assertEquals(2, world.buttons(v4).size)
        demoShot("buttons-website") { ChannelScreen(Demo.WEBSITE, onMenu = {}) }
    }

    /** A belfry action asks first with the app's own words; Publish sends the press; the belfry's answer closes the buttons. */
    @Test fun publishAsksThenCloses() {
        val v4 = open
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(Demo.WEBSITE, onMenu = {}) } } }
        compose.onNode(hasTestTag("button-publish") and hasAnyAncestor(hasTestTag("buttons-" + v4.id))).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Publish this version as the website? Everyone will see it at https://${Demo.DOMAIN}.").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenRoboImage("${Shots.DIR}/buttons-confirm.png")
        compose.onNodeWithTag("button-confirm").performClick()
        compose.waitUntil(10_000) { world.buttonState(v4).closed }
        assertEquals(session.me, world.buttonState(v4).myPress?.pubkey)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Published by ${Demo.DEFAULT_NAME}.").fetchSemanticsNodes().isNotEmpty() }
    }

    /** #random: a refused change to its settings: the mark next to its name, then Discard brings back the relay's words. */
    @Test fun settingsNotSaved() {
        assertEquals(1, world.failedSettings(Demo.RANDOM).size)
        assertEquals("Anything else: lost and found, spare pears.", world.group(Demo.RANDOM)?.about)
        demoShot("settings-failed") { ChannelScreen(Demo.RANDOM, onMenu = {}) }
    }

    @Test fun settingsNotSavedDialogAndDiscard() {
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(Demo.RANDOM, onMenu = {}) } } }
        compose.onNodeWithTag("settings-failed").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings-discard").fetchSemanticsNodes().isNotEmpty() }
        captureScreenRoboImage("${Shots.DIR}/settings-failed-dialog.png")
        compose.onNodeWithTag("settings-discard").performClick()
        compose.waitUntil(10_000) { world.failedSettings(Demo.RANDOM).isEmpty() }
        assertEquals("Anything else.", world.group(Demo.RANDOM)?.about)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings-failed").fetchSemanticsNodes().isEmpty() }
    }

    /** Try again: the demo's belfry takes it this time. */
    @Test fun settingsNotSavedTryAgain() {
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(Demo.RANDOM, onMenu = {}) } } }
        compose.onNodeWithTag("settings-failed").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings-retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-retry").performClick()
        compose.waitUntil(10_000) { world.failedSettings(Demo.RANDOM).isEmpty() }
    }

    /** The channel's settings: Save filled, Archive a quiet text button under it. */
    @Test fun channelSettings() = demoShot("channel-settings") { ChannelPlaceScreen(Demo.RANDOM, ChannelPlaceKey.SETTINGS) }

    /** #random has no pins, events, photos, files, to-dos or groups: those rows are left out. */
    @Test fun pullDownWithoutEmptyRows() {
        val chat = ChatState(Demo.RANDOM).apply { lineOpen = true }
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(Demo.RANDOM, onMenu = {}, chat = chat) } } }
        compose.waitForIdle()
        for (tag in listOf("pd-pinned", "pd-photos", "pd-files", "pd-todos", "pd-groups")) {
            assertTrue(tag, compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())
        }
        for (tag in listOf("pd-members", "pd-log", "pd-settings")) {
            assertTrue(tag, compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test fun pullDownRandomShot() {
        val chat = ChatState(Demo.RANDOM).apply { lineOpen = true }
        demoShot("pulldown-random") { ChannelScreen(Demo.RANDOM, onMenu = {}, chat = chat) }
    }

    /** Links in a message: the whole address, never the sentence's dot. */
    @Test fun linksInAMessage() {
        myMessage(
            "**Website updated**: https://preview.opensourcevillage.org/p/npub10j0emvtx36vh5h0dmd2e2zau7uh7vssx9mqnk2mn68agvknvecsqraxdux@website\n" +
                "Read https://belfry.opensourcevillage.org/website. (or https://a.org/b_c_d?x=1#top), a bare host example.org stays text.",
            Demo.KITCHEN,
        )
        demoShot("links-message") { ChannelScreen(Demo.KITCHEN, onMenu = {}) }
    }
}
