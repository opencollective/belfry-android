package sh.belfry.app

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.info.ChannelPlaceScreen
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.shell.AddChannelScreen
import sh.belfry.app.ui.shell.ChannelDrawer
import sh.belfry.app.ui.shell.DrawerActions
import sh.belfry.app.ui.shell.HomeScreen
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.everyonePins
import sh.belfry.core.model.isPreview
import sh.belfry.core.model.townList

/** Navigation v2 (canvas nav2 boards, docs/one-shape.md §1): the bar, the menu, Add a channel, previews, pins, the composer's ＋. */
class NavShotsTest : DemoShots() {
    private fun shell(place: ShellState.Place) = ShellState(Demo.GENERAL).apply { this.place = place }
    private fun list() = world.townList(session.pins.value.c, session.now())

    // ---- the bar (nav2/channel) ----
    @Test fun bar() = demoShot("nav-bar") { HomeScreen(shell(ShellState.Place.TOWN)) }
    @Test fun messagesPlace() = demoShot("nav-messages") { HomeScreen(shell(ShellState.Place.MESSAGES)) }
    @Test fun todayPlace() = demoShot("nav-today") { HomeScreen(shell(ShellState.Place.HOME)) }

    @Test fun theBarHasMessagesAndNoCreate() {
        val s = shell(ShellState.Place.HOME)
        compose.setContent { BelfryTheme { InDemo { HomeScreen(s) } } }
        assertTrue(compose.onAllNodes(hasTestTag("place-create")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("place-messages").performClick()
        compose.waitForIdle()
        assertEquals(ShellState.Place.MESSAGES, s.place)
        compose.onNodeWithTag("place-groups").performClick()
        compose.waitForIdle()
        assertEquals(ShellState.Place.GROUPS, s.place)
    }

    // ---- the menu (nav2/drawer, drawer-unread) ----
    /** Everything read: plain rows, no counts. */
    @Test fun drawer() {
        for (g in world.groups) session.markChannelRead(g.id)
        demoShot("nav-drawer") { HomeScreen(shell(ShellState.Place.TOWN), drawerOpen = true) }
    }

    /** The demo's last two hours unread: bold rows with counts, Mentions in red, ☰'s total. */
    @Test fun drawerUnread() = demoShot("nav-drawer-unread") { ChannelDrawer(shell(ShellState.Place.TOWN), DrawerActions({}, {}, {}, {})) }

    /** Pins first (the main channel, everyone's, then mine), then the recent channels. */
    @Test fun theTownListOfTheDemo() {
        val l = list()
        assertEquals(listOf(Demo.GENERAL, Demo.HELLO, Demo.GARDEN), l.pinned)
        assertTrue(Demo.KITCHEN in l.recent)
        assertTrue(world.isPreview(Demo.RANDOM, l))
        assertEquals(listOf(Demo.GENERAL, Demo.HELLO), world.everyonePins())
    }

    @Test fun theMenusRowsOpenTheirScreens() {
        val s = shell(ShellState.Place.TOWN)
        val went = ArrayList<String>()
        compose.setContent { BelfryTheme { InDemo { ChannelDrawer(s, DrawerActions({ s.open(it) }, { s.place = ShellState.Place.HOME }, { s.place = it }, { went.add(it) })) } } }
        compose.onNodeWithTag("drawer-mentions").performClick()
        compose.onNodeWithTag("drawer-add-channel").performClick()
        compose.onNodeWithTag("drawer-messages").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Routes.MENTIONS, Routes.ADD_CHANNEL), went)
        assertEquals(ShellState.Place.MESSAGES, s.place)
    }

    // ---- Add a channel (nav2/channel-new, -typing) ----
    @Test fun addChannel() = demoShot("nav-add-channel") { AddChannelScreen() }

    @Test fun addChannelTyping() {
        demoShot("nav-add-channel-typing", act = {
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("add-channel-name"))).performTextInput("ra")
            compose.waitForIdle()
        }) { AddChannelScreen() }
        compose.onNodeWithTag("add-channel-row-${Demo.RANDOM}").assertExists()
        compose.onNodeWithTag("add-channel-create").assertExists()
        assertTrue(compose.onAllNodes(hasTestTag("add-channel-row-${Demo.KITCHEN}")).fetchSemanticsNodes().isEmpty())
    }

    /** A channel not in the menu opens pushed (a preview, back comes here); one in it opens in Town. */
    @Test fun addChannelOpens() {
        compose.setContent { BelfryTheme { InDemo { AddChannelScreen() } } }
        compose.onNodeWithTag("add-channel-row-${Demo.RANDOM}").performClick()
        compose.onNodeWithTag("add-channel-row-${Demo.KITCHEN}").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Routes.chat(Demo.RANDOM), "<channel:${Demo.KITCHEN}>"), nav.went)
    }

    // ---- previews (nav2/channel-preview, seq-3-interact) ----
    @Test fun preview() = demoShot("nav-preview") { ChannelScreen(Demo.RANDOM, onBack = {}) }

    @Test fun pinningBringsItIn() {
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(Demo.RANDOM, onBack = {}) } } }
        compose.onNodeWithTag("preview-bar").assertExists()
        session.togglePin(Demo.RANDOM)
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasTestTag("preview-bar")).fetchSemanticsNodes().isEmpty())
        assertFalse(world.isPreview(Demo.RANDOM, list()))
        assertEquals(listOf(Demo.GARDEN, Demo.RANDOM), session.pins.value.c)
    }

    @Test fun writingBringsItIn() {
        assertTrue(world.isPreview(Demo.WEBSITE, list()))
        myMessage("Opening hours fixed?", Demo.WEBSITE)
        assertFalse(world.isPreview(Demo.WEBSITE, list()))
        assertEquals(Demo.WEBSITE, list().recent.first())
    }

    // ---- settings (nav2/channel-pin) ----
    @Test fun channelSettingsPins() = demoShot("nav-channel-settings") { ChannelPlaceScreen(Demo.RANDOM, ChannelPlaceKey.SETTINGS) }
    @Test fun mainChannelSettings() = demoShot("nav-channel-settings-main") { ChannelPlaceScreen(Demo.GENERAL, ChannelPlaceKey.SETTINGS) }

    @Test fun pinForMeAndForEveryone() {
        compose.setContent { BelfryTheme { InDemo { ChannelPlaceScreen(Demo.RANDOM, ChannelPlaceKey.SETTINGS) } } }
        compose.onNodeWithTag("pin-me").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(Demo.RANDOM in session.pins.value.c)
        compose.onNodeWithTag("pin-everyone").performScrollTo().performClick()
        compose.waitUntil(10_000) { settle(); Demo.RANDOM in world.everyonePins() }
        // every other key of the channel-order event is kept, and the main channel can't be unpinned
        assertEquals(listOf(Demo.GENERAL, Demo.HELLO, Demo.RANDOM), world.everyonePins())
    }

    // ---- the composer's ＋ (nav2/plus) ----
    @Test fun plus() {
        val chat = ChatState(Demo.GENERAL).apply { plusOpen = true }
        demoScreenShot("nav-plus") { ChannelScreen(Demo.GENERAL, onMenu = {}, chat = chat) }
        compose.onNodeWithTag("plus-file").assertExists()
        compose.onNodeWithTag("plus-create-in").assertExists()
        assertTrue(compose.onAllNodes(hasTestTag("create-group")).fetchSemanticsNodes().isEmpty())
    }
}
