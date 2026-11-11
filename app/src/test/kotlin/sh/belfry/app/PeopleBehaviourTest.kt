package sh.belfry.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import androidx.compose.ui.test.onAllNodesWithTag
import sh.belfry.core.push.PushOpen
import sh.belfry.core.push.PushLevel
import sh.belfry.core.push.NotificationSettings
import sh.belfry.app.ui.people.PushLevelRows
import sh.belfry.app.ui.people.NotificationsScreen
import sh.belfry.app.push.PushState
import sh.belfry.app.push.AndroidPush
import kotlinx.coroutines.CompletableDeferred
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.ProfileScreen
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.agentOwner

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

    @Test
    fun aGuestAgentBecomesMineAndIsRemovedAfterAConfirmation() {
        val scout = Demo.scout.pubkey
        compose.setContent { BelfryTheme { InDemo { ProfileScreen(scout) } } }
        compose.onNodeWithTag("agent-make-mine").performClick()
        compose.waitUntil(5_000) { world.agentOwner(scout) == session.me }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("agent of you").fetchSemanticsNodes().isNotEmpty() }
        // removing asks first, in the app
        compose.onNodeWithTag("agent-remove").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Remove Scout").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(session.me, world.agentOwner(scout))
        compose.onNodeWithText("Remove Scout").performClick()
        compose.waitUntil(5_000) { world.agentOwner(scout) == null }
    }

    @Test
    fun aMembersAgentSaysWhose() {
        compose.setContent { BelfryTheme { InDemo { ProfileScreen(Demo.wren.pubkey) } } }
        compose.onNodeWithTag("agent-owner").assertIsDisplayed()
        compose.onNodeWithText("agent of Kofi Mensah").assertIsDisplayed()
    }

    @Test
    fun aNotificationSwitchLocksAndSendsOnce() {
        val gate = CompletableDeferred<Unit>()
        val sent = ArrayList<String>()
        var now = NotificationSettings()
        compose.setContent {
            BelfryTheme {
                InDemo {
                    NotificationsScreen(
                        load = { now },
                        save = { set -> sent.add(set.toString()); gate.await(); now = now.merged(set); now },
                        state = PushState.ON,
                    )
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("push-switch-dms").fetchSemanticsNodes().isNotEmpty() }
        val dms = compose.onNodeWithTag("push-switch-dms")
        dms.performClick()
        dms.performClick()
        compose.onNodeWithTag("push-switch-mentions").performClick()
        compose.waitForIdle()
        dms.assertIsNotEnabled()
        gate.complete(Unit)
        compose.waitUntil(5_000) { !now.dms }
        compose.waitForIdle()
        // one request for the double tap on Direct messages, one for Mentions
        assertEquals(listOf("""{"dms":false}""", """{"mentions":false}"""), sent)
    }

    @Test
    fun aChannelsPushLevelIsSentOnce() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        compose.setContent {
            BelfryTheme { InDemo { PushLevelRows(PushLevel.DEFAULT, onPick = { calls++; gate.await() }) } }
        }
        compose.onNodeWithTag("push-level-all").performClick()
        compose.onNodeWithTag("push-level-off").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("push-level-mentions").assertIsNotEnabled()
        gate.complete(Unit)
        compose.waitForIdle()
        assertEquals(1, calls)
    }

    @Test
    fun aTapOnANotificationOnlyOpensWhatItNames() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        fun intent(vararg extras: Pair<String, String>) = android.content.Intent(ctx, MainActivity::class.java).apply {
            action = AndroidPush.ACTION_OPEN
            extras.forEach { (k, v) -> putExtra(k, v) }
        }
        val ev = "e".repeat(64)
        assertEquals(PushOpen.Channel("general", ev), AndroidPush.openOf(intent("kind" to "channel", "channel" to "general", "message" to ev)))
        assertEquals(PushOpen.Channel("general", null), AndroidPush.openOf(intent("kind" to "channel", "channel" to "general", "message" to "<b>x</b>")))
        assertEquals(PushOpen.Home, AndroidPush.openOf(intent("kind" to "channel", "channel" to "../../etc")))
        assertEquals(PushOpen.Dms, AndroidPush.openOf(intent("kind" to "dms")))
        assertEquals(PushOpen.Thread("general", ev), AndroidPush.openOf(intent("kind" to "thread", "channel" to "general", "message" to ev)))
        assertEquals(PushOpen.Channel("general", null), AndroidPush.openOf(intent("kind" to "thread", "channel" to "general")))
        assertEquals(null, AndroidPush.openOf(android.content.Intent(android.content.Intent.ACTION_VIEW)))
    }
}
