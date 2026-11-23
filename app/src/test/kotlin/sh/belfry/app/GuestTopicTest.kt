package sh.belfry.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.nav.ChannelPlaceKey
import sh.belfry.app.ui.info.ChannelPlaceScreen
import sh.belfry.app.ui.nav.AccountsUi
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.people.GuestJoinHost
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.GuestTopic

/**
 * SPEC 19.77: a reading guest who reached a topic through a printed card has a composer there
 * ("Sending as a guest · Log in"), and after the first post "Shared. Don't lose your account";
 * nowhere else (a group card, an ordinary guest link, another channel: the Guest mode bar).
 * SPEC 19.78: a channel's people are its followers; a group keeps its members.
 * The demo's guest: `demoGuestMode(channel, card = "topic")`.
 */
class GuestTopicTest : DemoShots() {
    private fun none(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    private fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private var wayIn: List<Any?>? = null

    private fun topic(channel: String = Demo.GARDEN) {
        compose.setContent {
            BelfryTheme {
                InDemo {
                    CompositionLocalProvider(LocalAccounts provides AccountsUi(wayIn = { event, apply -> wayIn = listOf(event, apply) })) {
                        Box(Modifier.fillMaxSize()) {
                            ChannelScreen(channel)
                            GuestJoinHost { _, _, _, _ -> }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // ---- the composer of a topic card's guest --------------------------------------------------

    /** cards/follow-channel, contribute-compose: the composer to share at once, the line under it. */
    @Test
    fun aTopicCardsGuestHasTheComposerAndTheLine() {
        session.demoGuestMode(Demo.GARDEN, card = "topic")
        demoShot("guest-topic-compose") { ChannelScreen(Demo.GARDEN) }
        compose.onNodeWithTag("guest-topic").assertExists()
        compose.onNodeWithTag("composer-field").assertExists()
        compose.onNodeWithText(GuestTopic.PLACEHOLDER).assertExists()
        compose.onNodeWithText("Sending as a guest ·").assertExists()
        compose.onNodeWithTag("guest-topic-login").assertExists()
        assertTrue("no Guest mode bar here", none("guest-bar"))
        assertTrue("nothing shared yet: no card", none("guest-topic-save"))
    }

    /** cards/contribute-save: after the first post, one card over the composer. */
    @Test
    fun afterTheFirstPostTheSaveCardShot() {
        session.demoGuestMode(Demo.GARDEN, card = "topic")
        runBlocking { session.postGuestMessage(Demo.GARDEN, "Spare cordless drill, ask me at no. 7") }
        settle()
        demoShot("guest-topic-shared") { ChannelScreen(Demo.GARDEN) }
        compose.onNodeWithTag("guest-topic-save").assertExists()
        compose.onNodeWithText(GuestTopic.SAVE_TITLE).assertExists()
        compose.onNodeWithText(GuestTopic.SAVE_TEXT).assertExists()
        compose.onNodeWithText("Spare cordless drill, ask me at no. 7").assertExists()
        compose.onNodeWithTag("composer-field").assertExists()
    }

    @Test
    fun sharingThenSaveOpensTheJoinSheetAndNotNowPutsTheCardAway() {
        session.demoGuestMode(Demo.GARDEN, card = "topic")
        topic()
        val before = session.store.outbox().size
        compose.onNodeWithTag("composer-field").performTextInput("Does anyone have a long ladder for Saturday?")
        compose.onNodeWithTag("composer-send").performClick()
        // through the outbox, as the web sends it: kind 9, [["h", T]] and the client tag
        compose.waitUntil(10_000) { session.store.outbox().size > before || world.messages(Demo.GARDEN).any { it.pubkey == session.me && it.text.startsWith("Does anyone") } }
        waitFor("guest-topic-save")
        assertFalse("sharing never opens the Join sheet", session.joinAsked.value)
        assertTrue(session.reading)
        // Save: the app's Join sheet (name and e-mail, then the code)
        compose.onNodeWithTag("guest-topic-save-button").performClick()
        compose.waitUntil(10_000) { session.joinAsked.value }
        waitFor("join-name")
        compose.onNodeWithTag("join-email").assertExists()
        session.closeJoin()
        compose.waitUntil(10_000) { none("join-sheet") }
        // Not now: gone, for this topic on this device; the composer and the line stay
        compose.onNodeWithTag("guest-topic-later").performClick()
        compose.waitUntil(10_000) { none("guest-topic-save") }
        assertEquals("1", session.store.get(GuestTopic.laterKey(Demo.GARDEN)))
        compose.onNodeWithTag("composer-field").assertExists()
        compose.onNodeWithTag("guest-topic-line").assertExists()
    }

    @Test
    fun logInOpensThisBelfrysLogIn() {
        session.demoGuestMode(Demo.GARDEN, card = "topic")
        topic()
        compose.onNodeWithTag("guest-topic-login").performClick()
        compose.waitUntil(5_000) { wayIn != null }
        assertEquals(listOf<Any?>(null, false), wayIn)
    }

    @Test
    fun joiningTurnsItIntoTheUsualComposer() {
        session.demoGuestMode(Demo.GARDEN, card = "topic")
        topic()
        runBlocking { session.guestVerify("ana@example.org", sh.belfry.core.model.GuestMode.DEMO_CODE, "Ana") }
        compose.waitUntil(10_000) { none("guest-topic") }
        compose.onNodeWithTag("composer-field").assertExists()
        assertTrue(compose.onAllNodesWithText("Sending as a guest ·").fetchSemanticsNodes().isEmpty())
    }

    // ---- nowhere else ----------------------------------------------------------------------------

    @Test
    fun aGroupCardsGuestHasTheBarAndNoComposer() {
        session.demoGuestMode(Demo.GARDEN, card = "group")
        topic()
        compose.onNodeWithTag("guest-bar").assertExists()
        assertTrue(none("composer-field"))
        assertTrue(none("guest-topic"))
    }

    @Test
    fun anOrdinaryGuestLinkHasTheBarAndNoComposer() {
        session.demoGuestMode(Demo.GARDEN)
        topic()
        compose.onNodeWithTag("guest-bar").assertExists()
        assertTrue(none("composer-field"))
        assertTrue(none("guest-topic"))
    }

    @Test
    fun anotherChannelHasTheBarAndNoComposer() {
        session.demoGuestMode(Demo.GARDEN, card = "topic")
        topic(Demo.KITCHEN)
        compose.onNodeWithTag("guest-bar").assertExists()
        assertTrue(none("composer-field"))
        assertTrue(none("guest-topic"))
    }

    // ---- followers, not members (SPEC 19.78) -----------------------------------------------------

    /** The pull-down of a channel: Followers and Activity log are rows like the others. */
    @Test
    fun aChannelsMenuSaysFollowersAndActivityLog() {
        val chat = ChatState(Demo.GARDEN).apply { lineOpen = true }
        demoShot("channel-menu-followers") { ChannelScreen(Demo.GARDEN, onMenu = {}, chat = chat) }
        compose.onNodeWithTag("pd-members").assertExists()
        compose.onNodeWithText("Followers").assertExists()
        compose.onNodeWithText("Activity log").assertExists()
        assertTrue(compose.onAllNodesWithText("People").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Log").fetchSemanticsNodes().isEmpty())
    }

    /** A group keeps its word. */
    @Test
    fun aGroupsMenuKeepsItsPeople() {
        val chat = ChatState(Demo.POD_OPEN).apply { lineOpen = true }
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(Demo.POD_OPEN, onMenu = {}, chat = chat) } } }
        compose.waitForIdle()
        compose.onNodeWithTag("pd-members").assertExists()
        assertTrue(compose.onAllNodesWithText("Followers").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Activity log").assertExists()
    }

    /** channels/closed-requests: the Followers screen of a closed channel, "Asking to follow" over its requests. */
    @Test
    fun theFollowersScreenOfAClosedChannel() {
        val g = world.group(Demo.TOOLS)!!
        assertTrue(g.applications.isNotEmpty())
        demoShot("channel-followers-asking") { ChannelPlaceScreen(Demo.TOOLS, ChannelPlaceKey.MEMBERS) }
        compose.onNodeWithText("Followers").assertExists()
        compose.onNodeWithText("Asking to follow · ${g.applications.size}").assertExists()
        compose.onNodeWithText("Followers · ${g.members.size}").assertExists()
        assertTrue(compose.onAllNodesWithText("Nobody here shares their city yet.", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theFollowersScreenOfAnOpenChannelAndTheMembersOfAGroup() {
        demoShot("channel-followers") { ChannelPlaceScreen(Demo.GARDEN, ChannelPlaceKey.MEMBERS) }
        compose.onNodeWithText("Followers").assertExists()
        assertTrue(compose.onAllNodesWithText("Members").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Nobody here shares their city yet.", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aGroupsScreenIsStillMembers() {
        compose.setContent { BelfryTheme { InDemo { ChannelPlaceScreen(Demo.POD_CLOSED, ChannelPlaceKey.MEMBERS) } } }
        compose.waitForIdle()
        compose.onNodeWithText("Members").assertExists()
        assertTrue(compose.onAllNodesWithText("Followers").fetchSemanticsNodes().isEmpty())
    }

    /** The activity log's own screen. */
    @Test
    fun theActivityLogScreen() {
        demoShot("channel-activity-log") { ChannelPlaceScreen(Demo.GARDEN, ChannelPlaceKey.LOG) }
        compose.onNodeWithText("Activity log").assertExists()
    }

    /** #requests: the public line says no more than any public channel's. */
    @Test
    fun requestsHasNoProposalLineUnderTheComposer() {
        demoShot("channel-requests") { ChannelScreen(Demo.REQUESTS, onMenu = {}) }
        assertTrue(compose.onAllNodesWithText("Members can turn a request into a proposal.", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
