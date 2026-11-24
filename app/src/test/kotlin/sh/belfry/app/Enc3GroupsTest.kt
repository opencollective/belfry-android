package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.PlusSheetContent
import sh.belfry.app.ui.chat.rememberComposerState
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.ActionException
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.KeyChange
import sh.belfry.core.model.GroupEncText

/**
 * SPEC 19.88 (format 3, part 7): a private group's screens and rules on the demo, with a made-up format-3 view
 * ([Enc3GroupsFixture]: the demo itself stays format 1).
 */
class Enc3GroupsTest : DemoShots() {
    private val g = Enc3GroupsFixture.G

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { BelfryTheme { InDemo(content) } }
        compose.waitForIdle()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(100); compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun theLockInTheHeaderOpensWhoCanReadThisForTheGroup() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.talk(session)
        show { ChannelScreen(g, onMenu = {}) }
        compose.onNodeWithContentDescription(GroupEncText.LOCK).performClick()
        waitForText("Only the devices of these 4 people can read Surprise party.")
        waitForText("Safari on Mac")
        compose.onNodeWithText("This device").assertExists()
        // Kofi has no device: under "Not signed in on any device", never with a count
        compose.onNodeWithText(GroupEncText.NOT_SIGNED_IN).performScrollTo()
        compose.onNodeWithText(GroupEncText.GETS_LATER).assertExists()
        assertEquals(2, count("enc3-group-other"))
    }

    @Test fun cantCheckSaysSoAndShowsNoOthers() {
        Enc3GroupsFixture.install(session, cant = true)
        show { ChannelScreen(g, onMenu = {}) }
        compose.onNodeWithContentDescription(GroupEncText.LOCK).performClick()
        waitForText(GroupEncText.CANT_CHECK)
        waitForText("This device couldn’t confirm which devices can read Surprise party. It keeps trying.")
        assertEquals(0, count("enc3-group-other"))
    }

    @Test fun noLockInAGroupThatIsNotPrivate() {
        Enc3GroupsFixture.install(session)
        show { ChannelScreen(Demo.GENERAL, onMenu = {}) }
        assertEquals(0, count("enc3-group-lock"))
    }

    @Test fun eventsMeetingsAndProposalsAreNotOfferedInAPrivateGroup() {
        Enc3GroupsFixture.install(session)
        show { Column { PlusSheetContent(g, admin = true, composer = rememberComposerState(g), onClose = {}, go = { fail("nothing opens: $it") }, onTodo = {}, onRecord = {}) } }
        for (tag in listOf("plus-event", "plus-meeting", "plus-proposal")) compose.onNodeWithTag(tag).assertIsNotEnabled()
        assertTrue(compose.onAllNodesWithText(GroupEncText.NO_EXTRAS).fetchSemanticsNodes().size >= 3)
        // the session refuses them too, whatever a screen forgot
        for (call in listOf<suspend () -> Unit>(
            { session.createEvent(sh.belfry.core.model.NewEvent("x", start = DemoShots.NOW + 86400, end = DemoShots.NOW + 90000), g) },
            { session.createProposal(sh.belfry.core.model.NewProposal("x", "y", "z"), g) },
        )) {
            val e = runCatching { runBlocking { call() } }.exceptionOrNull()
            assertTrue("refused: $e", e is ActionException && e.message == GroupEncText.NO_EXTRAS)
        }
    }

    @Test fun inAnOrdinaryChannelTheyAreOffered() {
        Enc3GroupsFixture.install(session)
        show { Column { PlusSheetContent(Demo.GENERAL, admin = true, composer = rememberComposerState(Demo.GENERAL), onClose = {}, go = {}, onTodo = {}, onRecord = {}) } }
        assertEquals(0, compose.onAllNodesWithText(GroupEncText.NO_EXTRAS).fetchSemanticsNodes().size)
    }

    @Test fun aGuestMembersSessionHasNoComposerInAPrivateGroup() {
        Enc3GroupsFixture.install(session)
        session.demoGuestMember(g)
        show { ChannelScreen(g, onMenu = {}) }
        waitForText(GroupEncText.GUEST)
        assertEquals(1, count("enc3-group-guest"))
    }

    @Test fun joinedAndRemovedLinesWithTheInfoForARemoval() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.talk(session)
        Enc3GroupsFixture.member(session, add = true, who = Demo.aroha.pubkey, minutes = 10)
        Enc3GroupsFixture.member(session, add = false, who = Demo.kofi.pubkey, minutes = 25)
        show { ChannelScreen(g, onMenu = {}) }
        waitForText("Aroha Ngata joined")
        waitForText("They see messages from now on.")
        waitForText("Maya removed Kofi Mensah")
        compose.onNodeWithContentDescription("What this means").performClick()
        waitForText("Kofi Mensah was removed")
        waitForText("When Maya removed Kofi, a new key was made for Surprise party.")
        compose.onNodeWithTag("enc3-group-info-ok").performClick()
    }

    @Test fun aKeyChangeWithoutAJoinIsCanReadFromHere() {
        Enc3GroupsFixture.install(session, changes = listOf(KeyChange("k".repeat(64), DemoShots.NOW - 3 * 3600 + 900, listOf(Demo.aroha.pubkey), emptyList(), 0)))
        Enc3GroupsFixture.talk(session)
        show { ChannelScreen(g, onMenu = {}) }
        waitForText("Aroha Ngata can read from here")
    }

    @Test fun removedMyselfReadsOnly() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.talk(session)
        Enc3GroupsFixture.removedMe(session)
        show { ChannelScreen(g, onMenu = {}) }
        waitForText("Maya removed you from Surprise party")
        assertEquals(1, count("enc3-group-removed-bar"))
    }

    @Test fun aHeldMessageSaysWhyWithTryAgainNeverSent() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.held(session, "See you at six", "held: enc3:GROUP_UNKNOWN")
        show { ChannelScreen(g, onMenu = {}) }
        waitForText("Not sent: couldn’t check who can read this group")
        compose.onNodeWithTag("enc3-group-try").performClick()
        compose.waitUntil(5_000) { session.store.outbox().single().error == null }
    }

    @Test fun tooManyDevicesIsRefusedWithWordsAndDelete() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.held(session, "General assembly on Sunday", "invalid: enc3:TOO_MANY")
        show { ChannelScreen(g, onMenu = {}) }
        waitForText(GroupEncText.TOO_BIG)
        compose.onNodeWithText(GroupEncText.TELL_STEWARD + " · ").assertExists()
        compose.onNodeWithTag("enc3-group-try").performClick()
        compose.waitUntil(5_000) { session.store.outbox().none { it.localId == "local-held" } }
    }

    @Test fun anEmailIntoThePrivateGroupSaysByEmail() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.mail(session)
        show { ChannelScreen(g, onMenu = {}) }
        waitForText(GroupEncText.BY_EMAIL)
    }

    @Test fun messagesWaitingHereSayOpeningAndCantBeReadWithAsk() {
        Enc3GroupsFixture.install(session, waiting = 2 to 1)
        Enc3GroupsFixture.talk(session)
        show { ChannelScreen(g, onMenu = {}) }
        waitForText("Opening 2 encrypted messages…")
        waitForText("1 message can’t be read on this device")
        compose.onNodeWithTag("enc3-group-ask").performClick()
        compose.waitUntil(5_000) { session.demoAsks == 1 }
    }
}
