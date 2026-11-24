package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.people.AgentAccess
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.Enc3DeviceCards
import sh.belfry.app.ui.people.Enc3DevicesScreen
import sh.belfry.app.ui.people.Enc3OneDeviceScreen
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.enc.DeviceCard
import sh.belfry.core.enc.Enc3DeviceState
import sh.belfry.core.enc.EncDeviceText
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.WhoText
import sh.belfry.core.mail.MailThreads

/**
 * SPEC 19.86 (format 3, part 6): Me › Devices, One device, my agent's access, the reader card; and part 4–5's two leftovers.
 * On the demo with a made-up format-3 state (the demo itself stays format 1).
 */
class Enc3MeTest : DemoShots() {
    private val here = Enc3Fixture.HERE
    private val mac = Enc3Fixture.MAC
    private val tablet = Enc3Fixture.TABLET
    private val agent get() = Demo.wren.pubkey
    private val agentName get() = world.displayName(agent)

    /** My list: this device, the Mac, the tablet (names from the fixture's sessions); READERS: my agent when given. */
    private fun mine(readers: List<String> = emptyList()) = Enc3DeviceState(
        on = true, listed = true,
        devices = listOf(here, mac, tablet), readers = readers, here = here,
        added = mapOf(here to Enc3Fixture.AUG12, mac to Enc3Fixture.OCT2, tablet to DemoShots.NOW - 3600) + readers.associateWith { DemoShots.NOW - 7200 },
    )

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { BelfryTheme { InDemo(content) } }
        compose.waitForIdle()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(100); compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun devicesListsMyListWithThisDeviceMarkedAndNoTrashOnIt() {
        Enc3Fixture.install(session, emptyMap())
        session.demoEnc3Device(mine(readers = listOf(agent)))
        show { Enc3DevicesScreen() }
        waitForText("Safari on Mac")
        compose.onNodeWithText("Belfry on Pixel 8").assertIsDisplayed()
        compose.onNodeWithText("Belfry on Boox Tab").assertExists()
        compose.onNodeWithText(WhoText.THIS_DEVICE).assertExists()
        compose.onNodeWithText(EncDeviceText.NEW).assertExists() // the tablet, added an hour ago
        assertEquals(1, count("enc3-device-here"))
        assertEquals(0, count("enc3-device-here-trash"))
        assertEquals(2, count("enc3-device-row-trash"))
        assertEquals(1, count("enc3-device-agent"))
        compose.onNodeWithText(agentName).assertExists()
        assertTrue(compose.onAllNodesWithText(EncDeviceText.AGENT, substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun theTrashAsksFirstThenRevokes() {
        Enc3Fixture.install(session, emptyMap())
        session.demoEnc3Device(mine())
        show { Enc3DevicesScreen() }
        waitForText("Safari on Mac")
        compose.onNodeWithContentDescription("Remove Safari on Mac").performClick()
        waitForText(EncDeviceText.removeTitle("Safari on Mac"))
        compose.onNodeWithText(EncDeviceText.REMOVE_TEXT).assertExists()
        assertTrue("nothing revoked before the person confirms", session.demoRevoked.isEmpty())
        compose.onNodeWithTag("enc3-devices-remove-confirm").performClick()
        waitForText(EncDeviceText.removed("Safari on Mac"))
        assertEquals(listOf(mac), session.demoRevoked.toList())
        assertTrue(session.demoAgentReads.isEmpty())
        assertTrue(mac !in session.enc3Device.value.devices)
    }

    @Test fun theAgentsTrashTurnsItsAccessOff() {
        Enc3Fixture.install(session, emptyMap())
        session.demoEnc3Device(mine(readers = listOf(agent)))
        show { Enc3DevicesScreen() }
        compose.onNodeWithContentDescription(EncDeviceText.agentRemoveTitle(agentName).removeSuffix("?")).performScrollTo().performClick()
        waitForText(EncDeviceText.agentRemoveTitle(agentName))
        compose.onNodeWithText(EncDeviceText.agentRemoveText(agentName)).assertExists()
        assertTrue(session.demoAgentReads.isEmpty())
        compose.onNodeWithTag("enc3-devices-remove-confirm").performClick()
        waitForText(EncDeviceText.agentOff(agentName))
        assertEquals(listOf(agent to false), session.demoAgentReads.toList())
        assertTrue("an agent is never revoked as a session", session.demoRevoked.isEmpty())
    }

    @Test fun oneDeviceRemoveAsksFirstAndThisDeviceHasNone() {
        Enc3Fixture.install(session, emptyMap())
        session.demoEnc3Device(mine().copy(sharedWith = setOf(mac)))
        show { Enc3OneDeviceScreen(mac) }
        waitForText("Safari on Mac")
        compose.onNodeWithText(EncDeviceText.SHARED).assertExists()
        compose.onNodeWithTag("enc3-one-remove").performClick()
        waitForText(EncDeviceText.removeTitle("Safari on Mac"))
        assertTrue(session.demoRevoked.isEmpty())
        compose.onNodeWithTag("enc3-one-remove-confirm").performClick()
        compose.waitUntil(10_000) { session.demoRevoked.isNotEmpty() }
        assertEquals(listOf(mac), session.demoRevoked.toList())
    }

    @Test fun oneDeviceForThisDevice() {
        Enc3Fixture.install(session, emptyMap())
        session.demoEnc3Device(mine())
        show { Enc3OneDeviceScreen(here) }
        waitForText("Belfry on Pixel 8")
        assertEquals(0, count("enc3-one-remove"))
        assertEquals(0, count("enc3-one-earlier"))
        compose.onNodeWithText(EncDeviceText.HERE_NOTE).assertExists()
    }

    @Test fun agentAccessAsksBeforeTurningOnAndFollowsTheList() {
        session.demoEnc3Device(mine())
        show { Column { AgentAccess(agent) } }
        compose.onNodeWithText(EncDeviceText.ACCESS).assertExists()
        compose.onNodeWithText(EncDeviceText.ACCESS_OFF).assertExists()
        compose.onNodeWithTag("enc3-agent-access").performClick()
        waitForText(EncDeviceText.giveTitle(agentName))
        compose.onNodeWithText(EncDeviceText.GIVE_TEXT).assertExists()
        assertTrue("nothing asked of the belfry before the person confirms", session.demoAgentReads.isEmpty())
        compose.onNodeWithTag("enc3-give-access").performClick()
        compose.waitUntil(10_000) { session.demoAgentReads.isNotEmpty() }
        assertEquals(listOf(agent to true), session.demoAgentReads.toList())
        // not on until my list says so
        waitForText(EncDeviceText.ACCESS_WAIT)
        // the belfry's new list: a reader entry for the agent
        compose.runOnIdle { session.demoEnc3Device(mine(readers = listOf(agent))) }
        waitForText(EncDeviceText.accessOn(agentName))
        // off: asks first (the agent's Remove words), then reads false
        compose.onNodeWithTag("enc3-agent-access").performClick()
        waitForText(EncDeviceText.agentRemoveTitle(agentName))
        assertEquals(1, session.demoAgentReads.size)
        compose.onNodeWithTag("enc3-access-off-confirm").performClick()
        compose.waitUntil(10_000) { session.demoAgentReads.size == 2 }
        assertEquals(agent to false, session.demoAgentReads.last())
        compose.runOnIdle { session.demoEnc3Device(mine()) }
        waitForText(EncDeviceText.ACCESS_OFF)
    }

    @Test fun agentAccessShowsTheListsState() {
        session.demoEnc3Device(mine(readers = listOf(agent)))
        show { Column { AgentAccess(agent) } }
        compose.onNodeWithText(EncDeviceText.accessOn(agentName)).assertExists()
        assertTrue(compose.onAllNodesWithText(EncDeviceText.ACCESS_OFF).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theReaderCardsRemoveTurnsAccessOff() {
        session.demoEnc3Device(mine(readers = listOf(agent)).copy(cards = listOf(DeviceCard(agent, DemoShots.NOW - 600, reader = true))))
        show { Enc3DeviceCards() }
        compose.onNodeWithText(EncDeviceText.readerTitle(agentName)).assertIsDisplayed()
        assertEquals("no Share for an agent", 0, count("enc3-share"))
        compose.onNodeWithTag("enc3-reader-remove").performClick()
        waitForText(EncDeviceText.agentRemoveTitle(agentName))
        assertTrue(session.demoAgentReads.isEmpty())
        compose.onNodeWithTag("enc3-reader-remove-confirm").performClick()
        waitForText(EncDeviceText.agentOff(agentName))
        assertEquals(listOf(agent to false), session.demoAgentReads.toList())
        assertEquals(0, count("enc3-reader-card"))
    }

    @Test fun theReaderCardsOkKeepsAccess() {
        session.demoEnc3Device(mine(readers = listOf(agent)).copy(cards = listOf(DeviceCard(agent, DemoShots.NOW - 600, reader = true))))
        show { Enc3DeviceCards() }
        compose.onNodeWithTag("enc3-reader-ok").performClick()
        waitForText(EncDeviceText.readerOk(agentName))
        assertTrue(session.demoAgentReads.isEmpty())
        assertEquals(0, count("enc3-reader-card"))
    }

    /** Leftover of part 4: no lock in the Notes row's preview on a format-3 belfry (it stays on format 1). */
    @Test fun notesRowHasNoLockOnFormat3() {
        session.demoEnc3Device(Enc3DeviceState(on = true, listed = true))
        demoShot("enc3-me-t-notes") { MessagesScreen() }
        compose.onNodeWithTag("notes-row").assertExists()
        assertEquals(0, count("notes-row-lock"))
        assertEquals(0, count("seal-locked-row"))
    }

    @Test fun notesRowKeepsItsLockOnFormat1() {
        demoShot("enc3-me-t-notes-v1") { MessagesScreen() }
        assertEquals(1, count("notes-row-lock"))
    }

    /** Leftover of part 5: version 1's "Shared by" history line never shows in a format-3 conversation (kept on format 1). */
    @Test fun v1SharedByNeverOnFormat3() {
        val trio = MailThreads.nostrConv(Demo.tomas.pubkey, listOf(Demo.lena.pubkey, session.me), session.me)
        Enc3Fixture.install(session, mapOf(trio to DemoShots.NOW - 40 * 86400L))
        demoShot("enc3-me-t-shared") { ConversationScreen(trio) }
        assertEquals(0, count("seal-shared-by"))
        assertEquals("nor its rule beside the shared messages", 0, count("seal-shared-message"))
        assertTrue(compose.onAllNodesWithText("Shared by Lena Fischer").fetchSemanticsNodes().isEmpty())
    }

    @Test fun v1SharedByOnPart5StateAlone() {
        val trio = MailThreads.nostrConv(Demo.tomas.pubkey, listOf(Demo.lena.pubkey, session.me), session.me)
        session.demoEnc3Device(Enc3DeviceState(on = true, listed = true))
        demoShot("enc3-me-t-shared5") { ConversationScreen(trio) }
        assertEquals(0, count("seal-shared-by"))
    }

    @Test fun v1SharedByStaysOnFormat1() {
        val trio = MailThreads.nostrConv(Demo.tomas.pubkey, listOf(Demo.lena.pubkey, session.me), session.me)
        demoShot("enc3-me-t-shared-v1") { ConversationScreen(trio) }
        assertEquals(1, count("seal-shared-by"))
        assertEquals(3, count("seal-shared-message"))
    }
}
