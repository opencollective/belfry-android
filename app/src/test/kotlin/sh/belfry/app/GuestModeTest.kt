package sh.belfry.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.people.GuestJoinHost
import sh.belfry.app.ui.people.JoinSheet
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.GuestMode

/**
 * Guest mode (SPEC 19.26) in the demo: the channel read with a bar in the composer's place,
 * nothing sent, the Join sheet (name and e-mail → the code sent by e-mail), and an address that
 * has an account here. The demo's code is 123456; taken@example.org "has an account" (moved).
 */
class GuestModeTest : DemoShots() {
    private fun field(tag: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("$DIR/$name.png")
    }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private var signIn: Triple<String, String, Boolean>? = null

    private fun reading() {
        session.demoGuestMode(Demo.GENERAL)
        compose.setContent {
            BelfryTheme {
                InDemo {
                    Box(Modifier.fillMaxSize()) {
                        ChannelScreen(Demo.GENERAL)
                        GuestJoinHost { email, channel, moved -> signIn = Triple(email, channel, moved) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theChannelIsReadWithTheGuestBarAndNoComposer() {
        session.demoGuestMode(Demo.GENERAL)
        demoShot("guest-mode") { ChannelScreen(Demo.GENERAL) }
        compose.onNodeWithTag("guest-bar").assertExists()
        compose.onNodeWithText("Guest mode", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithTag("composer-field").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theJoinSheetShot() {
        session.demoGuestMode(Demo.GENERAL)
        demoScreenShot("guest-join-sheet") { JoinSheet(session, onClose = {}) }
    }

    @Test
    fun aLongPressOpensTheJoinSheetAndNothingIsSent() {
        reading()
        val before = session.store.outbox().size
        val m = world.messages(Demo.GENERAL).last()
        compose.onNodeWithTag("msg-" + m.id).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(session.joinAsked.value)
        waitFor("join-sheet")
        assertEquals(before, session.store.outbox().size)
    }

    @Test
    fun joiningWithNameEmailAndTheCode() {
        reading()
        compose.onNodeWithText("Join").performClick()
        waitFor("join-name")
        field("join-name").performTextInput("Ana Lima")
        field("join-email").performTextInput("ana@example.org")
        compose.onNodeWithTag("join-continue").performClick()
        waitFor("join-code-line")
        compose.onNodeWithText("Check your e-mail").assertExists()
        compose.onNodeWithText("ana@example.org", substring = true).assertExists()
        capture("guest-join-code")
        // a wrong code: the fixed message, the boxes emptied
        compose.onNodeWithTag("code-boxes").performTextInput("111111")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(GuestMode.message("wrong_code")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(session.reading)
        compose.onNodeWithTag("code-boxes").performTextInput(GuestMode.DEMO_CODE)
        compose.waitUntil(10_000) { !session.reading }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("guest-bar").fetchSemanticsNodes().isEmpty() }
        waitFor("composer-field")
        assertFalse(session.joinAsked.value)
        assertEquals(true, session.guest.value?.saved)
    }

    @Test
    fun anAddressWithAnAccountOffersSignInInstead() {
        reading()
        compose.onNodeWithText("Join").performClick()
        waitFor("join-name")
        field("join-name").performTextInput("Ana")
        field("join-email").performTextInput(GuestMode.DEMO_TAKEN)
        compose.onNodeWithTag("join-continue").performClick()
        waitFor("join-code-line")
        compose.onNodeWithTag("code-boxes").performTextInput(GuestMode.DEMO_CODE)
        waitFor("join-taken-line")
        compose.onNodeWithText("You have an account here").assertExists()
        capture("guest-join-moved")
        compose.onNodeWithText("Sign in instead").performClick()
        compose.waitUntil(5_000) { signIn != null }
        assertEquals(Triple(GuestMode.DEMO_TAKEN, Demo.GENERAL, true), signIn)
    }

    @Test
    fun anEmptyNameKeepsContinueOff() {
        reading()
        compose.onNodeWithText("Join").performClick()
        waitFor("join-email")
        field("join-email").performTextInput("ana@example.org")
        compose.onNodeWithTag("join-continue").performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("join-code-line").fetchSemanticsNodes().isEmpty())
    }
}
