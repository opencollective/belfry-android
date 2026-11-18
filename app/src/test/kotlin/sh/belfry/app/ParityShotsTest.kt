package sh.belfry.app

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.belfry.BelfrySettingsScreen
import sh.belfry.app.ui.belfry.ChannelsSettingsScreen
import sh.belfry.app.ui.belfry.CharterScreen
import sh.belfry.app.ui.belfry.MembershipSettingsScreen
import sh.belfry.app.ui.belfry.RolesSettingsScreen
import sh.belfry.app.ui.belfry.ApplyScreen
import sh.belfry.app.ui.belfry.WelcomeScreen
import sh.belfry.app.ui.cards.CheckInScreen
import sh.belfry.app.ui.cards.EventScreen
import sh.belfry.app.ui.cards.MailThreadScreen
import sh.belfry.app.ui.cards.TicketSheet
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.info.ChannelPlaceScreen
import sh.belfry.app.ui.info.InviteScreen
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.app.ui.people.AddAgentDialog
import sh.belfry.app.ui.people.MembersScreen
import sh.belfry.app.ui.people.ProfileScreen
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.charter
import sh.belfry.core.model.eventByLink

/** The screens that bring the Android app level with the iPhone app (SPEC 19.29–19.34), in demo mode. */
class ParityShotsTest : DemoShots() {
    private val general = Demo.GENERAL

    @Test fun headerClosed() = demoShot("channel-header") { ChannelScreen(general, onMenu = {}) }
    @Test fun headerOpen() {
        val chat = ChatState(general).apply { lineOpen = true }
        demoShot("channel-header-open") { ChannelScreen(general, onMenu = {}, chat = chat) }
    }
    @Test fun pinned() = demoShot("channel-pinned") { ChannelPlaceScreen(Demo.GARDEN, ChannelPlaceKey.PINNED) }
    @Test fun channelMembers() = demoShot("channel-members") { ChannelPlaceScreen(general, ChannelPlaceKey.MEMBERS) }
    @Test fun channelSettings() = demoShot("channel-settings") { ChannelPlaceScreen(Demo.GARDEN, ChannelPlaceKey.SETTINGS) }
    @Test fun invite() = demoShot("invite-c") { InviteScreen(Demo.GARDEN, startAsGuest = true) }

    @Test fun mailFeed() = demoShot("mail-feed") { ChannelScreen(Demo.HELLO, onMenu = {}) }
    @Test fun mailThread() {
        val t = world.mailThreads(Demo.HELLO).list.first()
        demoShot("mail-thread") { MailThreadScreen(Demo.HELLO, t) }
    }

    @Test fun members() = demoShot("members") { MembersScreen() }
    @Test fun profileRole() = demoShot("profile-role") { ProfileScreen(Demo.maya.pubkey, group = Demo.GARDEN) }
    @Test fun myProfileAgent() = demoShot("profile-me-agent") { ProfileScreen(session.me) }
    @Test fun addAgent() = demoScreenShot("add-agent") { AddAgentDialog(onDismiss = {}) { "https://belfry.belfry.example/invite/demo-agent.md" } }

    @Test fun settings() = demoShot("belfry-settings") { BelfrySettingsScreen() }
    @Test fun settingsRoles() = demoShot("settings-roles") { RolesSettingsScreen() }
    @Test fun settingsMembership() = demoShot("settings-membership") { MembershipSettingsScreen() }
    @Test fun settingsChannels() = demoShot("settings-channels") { ChannelsSettingsScreen() }

    @Test fun charter() {
        assertEquals(3, world.charter().size)
        demoShot("charter") { CharterScreen() }
    }
    @Test fun welcome() = demoShot("welcome") { WelcomeScreen() }
    @Test fun guestApply() {
        session.demoGuestMode(general)
        demoShot("guest-apply") { ApplyScreen() }
    }

    @Test fun eventScreen() {
        val e = world.eventByLink(Demo.REPAIR_D)!!
        demoShot("event-half") { EventScreen(e.coord) }
    }
    @Test fun eventFull() {
        val e = world.eventByLink(Demo.REPAIR_D)!!
        demoShot("event-full", act = { compose.onNodeWithTag("event-handle").performClick() }) { EventScreen(e.coord) }
    }
    @Test fun ticket() {
        val e = world.eventByLink(Demo.REPAIR_D)!!
        demoScreenShot("event-ticket") { TicketSheet(e, "https://chat.belfry.example/events/${e.link}/t/abcdefghijklmnop", null) {} }
    }
    @Test fun checkIn() {
        val e = world.eventByLink(Demo.REPAIR_D)!!
        demoShot("check-in") { CheckInScreen(e.coord) }
    }

    /** Ticking an agreement writes my own tick; the line at the top follows. */
    @Test fun tickingAnAgreement() {
        started()
        val stale = world.charter().first { session.me in it.stale }
        compose.setContent { BelfryTheme { InDemo { CharterScreen() } } }
        val i = world.charter().indexOf(stale)
        compose.onNodeWithTag("agree-$i").performClick()
        compose.waitUntil(10_000) { world.charter().first { it.id == stale.id }.hasAgreed(session.me) }
        assertTrue(world.charter().all { it.hasAgreed(session.me) || it.title.startsWith("Shared") })
    }
}
