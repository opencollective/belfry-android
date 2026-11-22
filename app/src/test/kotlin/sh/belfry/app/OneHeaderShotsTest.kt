package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ActivityPreviewRow
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.chat.EmailsScreen
import sh.belfry.app.ui.chat.PreviewMemory
import sh.belfry.app.ui.chat.ThreadScreen
import sh.belfry.app.ui.chat.channelRowItems
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.OneHeader
import sh.belfry.app.ui.kit.RowItem
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.PreviewKind
import sh.belfry.core.model.previews

/** One shape (b) headers and (d) activity previews (canvas one-shape/anatomy, inbox-*, action-previews*; demo mode). */
class OneHeaderShotsTest : DemoShots() {
    private fun keys(channel: String) = world.previews(channel).list.map { it.key }

    /** Every preview of [channel] set open or folded on this device, as a tap would. */
    private fun setOpen(channel: String, open: Boolean) {
        session.store.set(PreviewMemory.KEY, keys(channel).joinToString(",") { "$it=${if (open) 1 else 0}" })
    }

    // ---- (b) headers ----------------------------------------------------------------------------

    /** #garden folded: the name, one icon row (👥 · 📌 · ✓ · 📅), ☰ on the left and only the bell on the right. */
    @Test fun channelFolded() {
        demoShot("oneshape-channel") { ChannelScreen(Demo.GARDEN, onMenu = {}) }
        compose.onNodeWithTag("channel-bell").assertExists()
        compose.onNodeWithTag("row-item-people", useUnmergedTree = true).assertExists()
        assertTrue("no search at the top right", compose.onAllNodesWithTag("one-header").fetchSemanticsNodes().size == 1)
    }

    /** Pulled down: about (with the address and who can read it), the pinned message, then the rows. */
    @Test fun channelPulled() {
        val chat = ChatState(Demo.GARDEN).apply { lineOpen = true }
        demoShot("oneshape-channel-pulled") { ChannelScreen(Demo.GARDEN, onMenu = {}, chat = chat) }
        compose.onNodeWithTag("pull-about-line").assertExists()
        compose.onNodeWithTag("pd-search").performScrollTo().assertExists()
    }

    /** A tap on the title pulls it down, the handle folds it. */
    @Test fun titleTapPulls() {
        val chat = ChatState(Demo.GARDEN)
        compose.setContent { sh.belfry.app.ui.theme.BelfryTheme { InDemo { ChannelScreen(Demo.GARDEN, onMenu = {}, chat = chat) } } }
        compose.onNodeWithTag("one-header-title").performClick()
        compose.waitUntil(5_000) { chat.lineOpen }
        compose.onNodeWithTag("pull-handle").performClick()
        compose.waitUntil(5_000) { !chat.lineOpen }
    }

    /** #hello takes e-mails: led in blue by ✉ N open (and 👤 on you once one is mine). */
    @Test fun inboxChannel() {
        demoShot("oneshape-inbox") { ChannelScreen(Demo.HELLO, onMenu = {}) }
        compose.onNodeWithTag("row-item-lead", useUnmergedTree = true).assertExists()
    }

    /** Its pull-down: an "E-mails" row on top of the usual rows. */
    @Test fun inboxPulled() {
        val chat = ChatState(Demo.HELLO).apply { lineOpen = true }
        demoShot("oneshape-inbox-pulled") { ChannelScreen(Demo.HELLO, onMenu = {}, chat = chat) }
        compose.onNodeWithTag("pd-emails").performClick()
        assertEquals(Routes.channelPlace(Demo.HELLO, ChannelPlaceKey.EMAILS), nav.went.last())
    }

    /** The e-mails as a list like the to-dos: Open / Mine / Answered / Closed, Claim in one tap. */
    @Test fun emailsList() {
        demoShot("oneshape-emails") { EmailsScreen(Demo.HELLO) }
        compose.onAllNodesWithTag("emails-row").fetchSemanticsNodes().isNotEmpty().let { assertTrue(it) }
    }

    /** Claim locks and then the row says "You have it". */
    @Test fun emailsClaim() {
        started()
        compose.setContent { sh.belfry.app.ui.theme.BelfryTheme { InDemo { EmailsScreen(Demo.HELLO) } } }
        compose.onAllNodesWithTag("emails-claim")[0].performClick()
        compose.waitUntil(10_000) { world.mailThreads(Demo.HELLO).list.any { it.state.assignee == session.me } }
    }

    /** A direct conversation: the name and ✓ · 📁 · 🖼, no face and no people count. */
    @Test fun dmHeader() = demoShot("oneshape-dm") { ConversationScreen("n:" + Demo.maya.pubkey) }

    /** A thread: the beginning of its first message as the title, 👥 · 💬; the first message on top. */
    @Test fun threadHeader() {
        val w = world
        val root = w.threadsOf(Demo.GENERAL).entries.maxBy { it.value.count }.key
        demoShot("oneshape-thread") { ThreadScreen(Demo.GENERAL, root) }
        compose.onNodeWithTag("row-item-comments", useUnmergedTree = true).assertExists()
    }

    /** At font scale 2 on a narrow phone the row drops items (owner, files, to-dos…) and never clips. */
    @Test fun fontScale2() {
        demoShot("oneshape-header-font2") {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                Column(Modifier.width(280.dp)) {
                    OneHeader("hello", channelRowItems(world, Demo.HELLO, session.now()) + RowItem("owner", BelfryIcons.User, "1 on you", "1 on you", lead = true),
                        navigation = { IconAction(BelfryIcons.Menu, "The town", {}) }, actions = { IconAction(BelfryIcons.Bell, "Bell", {}) })
                    OneHeader("garden", channelRowItems(world, Demo.GARDEN, session.now()), navigation = { BackButton {} }, actions = { IconAction(BelfryIcons.Bell, "Bell", {}) })
                }
            }
        }
        // the lead and the people count stay in both rows; the owner left first
        assertEquals(2, compose.onAllNodesWithTag("row-item-people", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    // ---- (d) activity previews ------------------------------------------------------------------

    /** #garden's activity, every line folded: icon, "<first name> <verb>: <item>", time, ⌄. */
    @Test fun previewsCollapsed() {
        setOpen(Demo.GARDEN, open = false)
        demoShot("oneshape-previews") { ChannelScreen(Demo.GARDEN, onMenu = {}) }
        assertTrue(compose.onAllNodesWithTag("preview-line").fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, compose.onAllNodesWithTag("preview-card").fetchSemanticsNodes().size)
    }

    /** The same lines unfolded: the to-do with its tick, who has it and its due date; the event with Going / Maybe. */
    @Test fun previewsExpanded() {
        setOpen(Demo.GARDEN, open = true)
        demoShot("oneshape-previews-open") { ChannelScreen(Demo.GARDEN, onMenu = {}) }
        assertTrue(compose.onAllNodesWithTag("preview-card").fetchSemanticsNodes().isNotEmpty())
    }

    /** With nothing remembered, the to-do given to me starts open; the others stay folded. */
    @Test fun previewThatNeedsMeStartsOpen() {
        val water = world.previews(Demo.GARDEN).list.last { it.kind == PreviewKind.TODO && it.title == "Water the seedlings" }
        demoShot("oneshape-preview-needs-me") { Column(Modifier.fillMaxSize()) { ActivityPreviewRow(Demo.GARDEN, water, session.now()) } }
        compose.onNodeWithTag("preview-todo").assertExists()
    }

    /** #general: a proposal's card becomes "opened a proposal", votes are lines; the vote I haven't cast starts open. */
    @Test fun previewsGeneral() = demoShot("oneshape-previews-general") { ChannelScreen(Demo.GENERAL, onMenu = {}) }
}
