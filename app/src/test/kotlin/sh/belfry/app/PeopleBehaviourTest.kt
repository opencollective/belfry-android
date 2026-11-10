package sh.belfry.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.ProfileScreen
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo

/** The owner's rules on the people screens, checked by driving them (demo mode). */
class PeopleBehaviourTest : DemoShots() {
    @Test
    fun aDirectMessageIsSentOnceOnADoubleTap() {
        started()
        val conv = "n:" + Demo.maya.pubkey
        val text = "Saturday works, I have the key."
        compose.setContent { BelfryTheme { InDemo { ConversationScreen(conv) } } }
        compose.onNodeWithTag("composer-field").performTextInput(text)
        val send = compose.onNodeWithTag("composer-send")
        send.performClick()
        send.performClick()
        send.performClick()
        compose.waitUntil(10_000) { session.dms.value.thread(conv).any { it.text == text } }
        // Give a second send the time to show up, if there was one.
        repeat(20) {
            settle()
            Thread.sleep(25)
        }
        compose.waitForIdle()
        assertEquals("the message was sent more than once", 1, session.dms.value.thread(conv).count { it.text == text })
    }

    @Test
    fun anUnsavedGuestCannotBeMadeAdminAndTheScreenSaysWhy() {
        compose.setContent { BelfryTheme { InDemo { ProfileScreen(Demo.marcel.pubkey) } } }
        compose.onNodeWithTag("admin-switch").assertIsNotEnabled()
        compose.onNodeWithText("This guest has no e-mail yet", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("admin-switch").performClick()
        compose.waitForIdle()
        assertFalse(world.isBelfryAdmin(Demo.marcel.pubkey))
    }
}
