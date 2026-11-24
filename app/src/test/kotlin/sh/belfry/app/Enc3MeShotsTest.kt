package sh.belfry.app

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.runBlocking
import org.junit.Test
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.DevicesScreen
import sh.belfry.app.ui.people.Enc3OneDeviceScreen
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.app.ui.people.ProfileScreen
import sh.belfry.core.enc.DeviceCard
import sh.belfry.core.enc.Enc3DeviceState
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.DemoEnc3View
import sh.belfry.core.engine.MyDevices
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.net.SessionInfo

/**
 * SPEC 19.86 (format 3, part 6; Encrypted canvas encrypted/devices, device, devices-agent, agent-access, agent-access-confirm,
 * agent-access-on, edge-agent-card): pictures only, from a made-up format-3 state (the demo stays format 1). Run by hand:
 * `gh workflow run ci.yml --ref <branch> -f shots='*Enc3MeShotsTest*'`.
 */
class Enc3MeShotsTest : DemoShots() {
    private val here = Enc3Fixture.HERE
    private val mac = Enc3Fixture.MAC
    private val tablet = Enc3Fixture.TABLET
    /** Pip, made my own agent for these pictures (in the demo it is Maya's). */
    private val agent get() = Demo.pip.pubkey

    private val sessions = listOf(
        SessionInfo("Belfry", "Belfry on Pixel 8", "", "bk-here", "2026-08-12T09:00:00Z", "2026-11-10T10:29:00Z", here, true),
        SessionInfo("Belfry", "Safari on Mac", "", "bk-mac", "2026-10-02T09:00:00Z", "2026-11-10T08:10:00Z", mac, true),
        SessionInfo("Belfry", "Belfry on Boox Tab", "", "bk-tab", "2026-11-10T09:40:00Z", "2026-11-10T09:41:00Z", tablet, true),
    )

    private fun mine(readers: List<String> = emptyList(), cards: List<DeviceCard> = emptyList()): Enc3DeviceState {
        val added = mapOf(here to Enc3Fixture.AUG12, mac to Enc3Fixture.OCT2, tablet to DemoShots.NOW - 50 * 60) + readers.associateWith { DemoShots.NOW - 20 * 60 }
        session.demoEnc3View = DemoEnc3View(myDevices = MyDevices(listOf(here, mac, tablet), readers, added, here), mySessions = sessions)
        return Enc3DeviceState(on = true, listed = true, devices = listOf(here, mac, tablet), readers = readers, added = added, here = here, cards = cards, sharedWith = setOf(mac))
    }

    private fun myAgent() = runBlocking { session.setMyAgent(agent, true) }

    @Test fun devices() {
        session.demoEnc3Device(mine())
        demoShot("enc3-devices") { DevicesScreen() }
    }

    @Test fun devicesAgent() {
        myAgent()
        session.demoEnc3Device(mine(readers = listOf(agent)))
        demoShot("enc3-devices-agent") { DevicesScreen() }
    }

    @Test fun devicesRemove() {
        session.demoEnc3Device(mine())
        demoScreenShot("enc3-devices-remove", act = { compose.onNodeWithContentDescription("Remove Safari on Mac").performClick() }) { DevicesScreen() }
    }

    @Test fun oneDevice() {
        session.demoEnc3Device(mine())
        demoShot("enc3-device") { Enc3OneDeviceScreen(tablet) }
    }

    @Test fun oneDeviceHere() {
        session.demoEnc3Device(mine())
        demoShot("enc3-device-here") { Enc3OneDeviceScreen(here) }
    }

    private fun profileAt(name: String, act: (() -> Unit)? = null) {
        demoScreenShot(name, act = {
            compose.onNodeWithTag("enc3-agent-access").performScrollTo()
            act?.invoke()
        }) { ProfileScreen(agent) }
    }

    @Test fun agentAccessOff() {
        myAgent(); session.demoEnc3Device(mine())
        profileAt("enc3-agent-access")
    }

    @Test fun agentAccessConfirm() {
        myAgent(); session.demoEnc3Device(mine())
        profileAt("enc3-agent-access-confirm") { compose.onNodeWithTag("enc3-agent-access").performClick() }
    }

    @Test fun agentAccessOn() {
        myAgent(); session.demoEnc3Device(mine(readers = listOf(agent)))
        profileAt("enc3-agent-access-on")
    }

    @Test fun readerCard() {
        myAgent()
        session.demoEnc3Device(mine(readers = listOf(agent), cards = listOf(DeviceCard(agent, DemoShots.NOW - 50 * 60, reader = true))))
        demoShot("enc3-edge-agent-card") { MessagesScreen() }
    }

    @Test fun readerCardRemove() {
        myAgent()
        session.demoEnc3Device(mine(readers = listOf(agent), cards = listOf(DeviceCard(agent, DemoShots.NOW - 50 * 60, reader = true))))
        demoScreenShot("enc3-edge-agent-card-remove", act = { compose.onNodeWithTag("enc3-reader-remove").performClick() }) { MessagesScreen() }
    }

    /** The remove confirmation on the device card, with encrypted/card-remove's two lines. */
    @Test fun cardRemove() {
        session.demoEnc3Device(mine(cards = listOf(DeviceCard(tablet, DemoShots.NOW - 50 * 60, name = "Belfry on Boox Tab"))))
        demoScreenShot("enc3-me-card-remove", act = { compose.onNodeWithTag("enc3-remove").performClick() }) { MessagesScreen() }
    }

    /** The leftovers: Messages on format 3 (no lock in Notes' preview), a group conversation without v1's "Shared by". */
    @Test fun messagesNoLock() {
        session.demoEnc3Device(mine())
        demoShot("enc3-me-messages") { MessagesScreen() }
    }

    @Test fun groupNoSharedBy() {
        val trio = MailThreads.nostrConv(Demo.tomas.pubkey, listOf(Demo.lena.pubkey, session.me), session.me)
        Enc3Fixture.install(session, mapOf(trio to DemoShots.NOW - 40 * 86_400L))
        session.demoEnc3Device(Enc3DeviceState(on = true, listed = true))
        demoShot("enc3-me-group") { ConversationScreen(trio) }
    }
}
