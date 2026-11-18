package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.push.PushState
import sh.belfry.app.ui.cards.ApplyBody
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.info.ChannelPlaceScreen
import sh.belfry.app.ui.info.ChannelSettingsForm
import sh.belfry.app.ui.info.NotifyBody
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.ChannelAccess
import sh.belfry.core.model.channelAccess
import sh.belfry.core.model.needsJoinBar
import sh.belfry.core.model.readOnlyClosed
import sh.belfry.core.push.NotificationSettings

/** Round 6 (2026-11-18; canvas boards channels/…; SPEC 19.41 / 19.42). */
class R6ShotsTest : DemoShots() {

    /** channels/notifications: one sheet over the channel, the permission line, "(the default here)". */
    @Test fun notifySheet() {
        val push = runBlocking { session.notificationSettings() }
        demoScreenShot("channel-notify") {
            ChannelScreen(Demo.GARDEN, onMenu = {})
            BelfrySheet(onDismiss = {}) { NotifyBody(Demo.GARDEN, push = push, follows = "stop", state = PushState.NEEDS_PERMISSION) }
        }
        compose.onNodeWithTag("notify-default").assertExists()
        compose.onNodeWithTag("notify-turn-on").assertExists()
    }

    /** A tap applies at once and the sheet shows the stored level. */
    @Test fun notifyApplies() {
        demoScreenShot("channel-notify-everything") {
            BelfrySheet(onDismiss = {}) { NotifyBody(Demo.GARDEN, push = NotificationSettings(), follows = "asap", state = PushState.ON) }
        }
        compose.onNodeWithTag("notify-all").performClick()
        compose.waitUntil(5_000) { runBlocking { session.notificationSettings() }.level(Demo.GARDEN).key == "all" }
    }

    /** channels/settings: Who can read it, three rows; the e-mail switch under it. */
    @Test fun settingsWhoReads() {
        assertEquals(ChannelAccess.MEMBERS, world.channelAccess(world.group(Demo.GARDEN)))
        demoShot("channel-settings-who") { ChannelPlaceScreen(Demo.GARDEN, ChannelPlaceKey.SETTINGS) }
        compose.onNodeWithTag("access-closed").assertExists()
    }

    /** channels/make-public: Public asks first. */
    @Test fun makePublic() {
        val g = world.group(Demo.GARDEN)!!
        demoScreenShot("channel-make-public") {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ChannelSettingsForm(g, onClose = {}, askPublicAtOnce = true) }
        }
        compose.onNodeWithTag("make-public").assertExists()
    }

    /** channels/closed-ask: a member outside a closed channel reads it, with the bar and no composer. */
    @Test fun closedAsk() {
        runBlocking { session.setAdmin(session.me, false) }
        val g = world.group(Demo.TOOLS)!!
        assertTrue(g.closed)
        assertTrue(world.needsJoinBar(g))
        assertTrue(world.readOnlyClosed(g))
        demoShot("channel-closed") { ChannelScreen(Demo.TOOLS, onMenu = {}) }
        compose.onNodeWithTag("ask-to-join").assertExists()
    }

    @Test fun closedAskSheet() {
        runBlocking { session.setAdmin(session.me, false) }
        val g = world.group(Demo.TOOLS)!!
        demoScreenShot("channel-closed-ask") {
            ChannelScreen(Demo.TOOLS, onMenu = {})
            BelfrySheet(onDismiss = {}, title = "Ask to join #${g.name}") {
                ApplyBody(g, "A drill for the community shelves, and I can lend my sander.", {}) {}
            }
        }
    }

    /** A belfry admin is no outsider: no bar, and the composer stays. */
    @Test fun closedForAnAdmin() {
        val g = world.group(Demo.TOOLS)!!
        assertFalse(world.needsJoinBar(g))
        assertFalse(world.readOnlyClosed(g))
    }

    /** channels/mail-comment: the same thread in Comment mode (yellow). channels/mail-row and mail-reply are mail-feed / mail-thread. */
    @Test fun mailComment() {
        val t = world.mailThreads(Demo.HELLO).list.first()
        demoShot("mail-comment") { sh.belfry.app.ui.cards.MailThreadScreen(Demo.HELLO, t, startWithComment = true) }
        compose.onNodeWithTag("mode-comment").assertExists()
    }
}
