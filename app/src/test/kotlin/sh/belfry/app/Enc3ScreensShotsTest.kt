package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Test
import org.robolectric.annotation.Config
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.enc.EncWrite
import sh.belfry.core.enc.HoldReason
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.Enc3State
import sh.belfry.core.engine.Here
import sh.belfry.core.mail.MailThreads

/**
 * Format 3, the screens of people conversations (SPEC 19.84; Encrypted canvas, the encrypted boards): pictures only, on the
 * demo with a made-up format-3 view ([Enc3Fixture]). Run by hand: `gh workflow run ci.yml -f shots='*Enc3ScreensShotsTest*'`.
 */
class Enc3ScreensShotsTest : DemoShots() {
    private val maya get() = Enc3Fixture.maya()
    private val trio get() = MailThreads.nostrConv(Demo.tomas.pubkey, listOf(Demo.lena.pubkey, session.me), session.me)
    private val mail get() = MailThreads.mailThreadOf("permits@cityhall.example", "Street party permit")
    private val long = DemoShots.NOW - 40 * 86400L
    private val notes get() = "n:" + session.me

    private fun everything() = Enc3Fixture.install(session, mapOf(maya to long, trio to long, notes to long))

    private fun openWho() {
        compose.onNodeWithTag("enc3-lock").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Safari on Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** encrypted/list: no lock in the list, Notes to myself first. */
    @Test fun list() { everything(); demoShot("enc3-list") { MessagesScreen() } }

    /** encrypted/conversation: the lock next to the name. */
    @Test fun conversation() { everything(); demoShot("enc3-conversation") { ConversationScreen(maya) } }

    /** Notes to myself, encrypted: the lock and the first line. */
    @Test fun notes() { everything(); demoShot("enc3-notes") { ConversationScreen(notes) } }

    /** encrypted/who-can-read: my devices by name, Maya as a count. */
    @Test fun whoCanRead() { everything(); demoScreenShot("enc3-who-can-read", act = { openWho() }) { ConversationScreen(maya) } }

    /** The same in a conversation with two others. */
    @Test fun whoGroup() { everything(); demoScreenShot("enc3-who-group", act = { openWho() }) { ConversationScreen(trio) } }

    /** encrypted/who-agent: my agent among my devices. */
    @Test fun whoAgent() {
        Enc3Fixture.install(session, mapOf(maya to long), agent = Demo.wren.pubkey)
        demoScreenShot("enc3-who-agent", act = { openWho() }) { ConversationScreen(maya) }
    }

    /** This device has no list yet. */
    @Test fun whoNotSetUp() {
        Enc3Fixture.install(session, mapOf(maya to long), setUp = false)
        demoScreenShot("enc3-who-not-set-up", act = { compose.onNodeWithTag("enc3-lock").performClick(); compose.waitForIdle() }) { ConversationScreen(maya) }
    }

    private fun fetching(): List<Dm> {
        val now = session.now()
        return listOf(
            Dm("a", maya, Demo.maya.pubkey, false, now - 3000, 14, "See you Saturday at the hall!", enc = true),
            Dm("p:1", maya, "", false, now - 600, 14, "", opening = true),
        )
    }

    /** encrypted/state-fetching: opening, with a spinner. */
    @Test fun stateFetching() { everything(); demoShot("enc3-state-fetching") { Enc3Fixture.WithDms(session, fetching()) { ConversationScreen(maya) } } }

    /** encrypted/state-fetching, offline: when it will open. */
    @Test fun stateOffline() { everything(); demoShot("enc3-state-offline") { Enc3Fixture.WithDms(session, fetching(), online = false) { ConversationScreen(maya) } } }

    private fun heldIn(conv: String, h: EncWrite.Hold, text: String, notTo: List<String> = emptyList()): List<Dm> {
        val now = session.now()
        return listOf(
            Dm("a", conv, if (conv == maya) Demo.maya.pubkey else Demo.tomas.pubkey, false, now - 3000, 14, "Can you feed the cat on Friday?", enc = true),
            Dm("h", conv, session.me, true, now - 60, 14, text, pending = true, heldSince = now - 60, heldWhy = DmSnapshot.enc3Why(h), notTo = notTo),
        )
    }

    /** encrypted/state-held: the text kept, why, Try again · Cancel. */
    @Test fun stateHeld() {
        everything()
        demoShot("enc3-state-held") { Enc3Fixture.WithDms(session, heldIn(maya, EncWrite.Hold(HoldReason.FORK), "I'll bring the long ladder and the paint.")) { ConversationScreen(maya) } }
    }

    /** encrypted/edge-no-device: for one person signed in nowhere, held. */
    @Test fun edgeNoDevice() {
        everything()
        demoShot("enc3-edge-no-device") { Enc3Fixture.WithDms(session, heldIn(maya, EncWrite.Hold(HoldReason.NO_DEVICE, Demo.maya.pubkey), "Thanks for the plums!")) { ConversationScreen(maya) } }
    }

    /** encrypted/edge-grew-100: an encrypted conversation past 100 devices, held, never in clear. */
    @Test fun edgeGrew100() {
        everything()
        demoShot("enc3-edge-grew-100") { Enc3Fixture.WithDms(session, heldIn(trio, EncWrite.Hold(HoldReason.TOO_MANY), "I'll bring the long tables.")) { ConversationScreen(trio) } }
    }

    /** encrypted/edge-no-device-group: sent to the others, "Lena won't get this". */
    @Test fun edgeNoDeviceGroup() {
        everything()
        val now = session.now()
        val items = listOf(
            Dm("a", trio, Demo.tomas.pubkey, false, now - 3000, 14, "Who brings the ladder on Saturday?", enc = true),
            Dm("b", trio, session.me, true, now - 2900, 14, "I will, at 9.", enc = true, notTo = listOf(Demo.lena.pubkey)),
        )
        demoShot("enc3-edge-no-device-group") { Enc3Fixture.WithDms(session, items) { ConversationScreen(trio) } }
    }

    /** encrypted/not-before: one line where the unencrypted messages end; and one that came clear after the key. */
    @Test fun notBefore() {
        val first = session.now() - 3600
        Enc3Fixture.install(session, mapOf(maya to first))
        val m = Demo.maya.pubkey
        val items = listOf(
            Dm("a", maya, m, false, first - 86400 * 40, 14, "Shall we split the plot?", plain = true),
            Dm("b", maya, session.me, true, first - 86400 * 40 + 120, 14, "Yes, half each.", plain = true),
            Dm("c", maya, m, false, first + 60, 14, "Thursday at the plots?", enc = true),
            Dm("d", maya, m, false, first + 600, 14, "Sent from an old app", plain = true, clear = true),
        )
        demoShot("enc3-not-before") { Enc3Fixture.WithDms(session, items) { ConversationScreen(maya) } }
    }

    /** encrypted/not-large: a conversation never encrypted (over 100 devices): no lock, nothing said. */
    @Test fun notLarge() { Enc3Fixture.install(session, mapOf(maya to long)); demoShot("enc3-not-large") { ConversationScreen(trio) } }

    /** encrypted/email: an e-mail thread on format 3: "By e-mail" stays, the header has the lock. */
    @Test fun email() { everything(); demoShot("enc3-email") { ConversationScreen(mail) } }

    /** encrypted/email-who: the lock of an e-mail thread explains e-mail. */
    @Test fun emailWho() {
        everything()
        demoScreenShot("enc3-email-who", act = { openWho() }) { ConversationScreen(mail) }
    }

    /** encrypted/edge-21st: the twenty-first device, its final words. */
    @Test fun edge21st() {
        everything()
        session.demoEnc3State(Enc3State(here = Here.FULL, format3 = true))
        demoShot("enc3-edge-21st") { MessagesScreen() }
    }

    /** encrypted/laptop-who: on a wide window, Who can read this beside the conversation. */
    @Config(qualifiers = "w1280dp-h800dp-land-240dpi")
    @Test fun laptopWho() { everything(); demoShot("enc3-laptop-who", act = { openWho() }) { ConversationScreen(maya) } }
}
