package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.people.AccessAskDialog
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.EncryptionScreen
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.app.ui.people.DevicesScreen
import sh.belfry.app.ui.people.MeScreen
import sh.belfry.app.ui.people.ShareHistoryContent
import sh.belfry.app.ui.people.SharedWhyContent
import sh.belfry.app.ui.people.UnlockStepsScreen
import sh.belfry.app.ui.shell.LinkDeviceScreen
import sh.belfry.core.engine.Demo
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.model.DeviceNotice

/**
 * canvas "Messages · encryption" (approved 2026-11-20, v80 / v81): screens only, on the demo's
 * sealed conversations. No crypto: the demo answers on the device.
 */
class EncryptionShotsTest : DemoShots() {
    private val maya get() = "n:" + Demo.maya.pubkey
    private val trio get() = MailThreads.nostrConv(Demo.tomas.pubkey, listOf(Demo.lena.pubkey, session.me), session.me)
    private val mail get() = MailThreads.mailThreadOf("permits@cityhall.example", "Street party permit")

    /** messages/encryption-thread: the lock by the name, device lines, the amber one. */
    @Test fun thread() {
        demoShot("encryption-thread") { ConversationScreen(maya) }
        compose.onNodeWithTag("seal-lock").assertExists()
        compose.onNodeWithTag("seal-notice-amber").assertExists()
        compose.onNodeWithText("Maya Okafor signed in on a new device that can't read earlier messages yet").assertExists()
        compose.onNodeWithText("You added a device · Firefox on Linux").assertExists()
    }

    /** The lock opens Encryption. */
    @Test fun lockOpens() {
        demoShot("encryption-thread-lock", act = { compose.onNodeWithTag("seal-lock").performClick() }) { ConversationScreen(maya) }
        assertTrue(nav.went.any { it.startsWith("encryption/") })
    }

    /** A conversation that isn't sealed has no lock (the not-sealed line is FOR XAVIER). */
    @Test fun notSealedNoLock() {
        val other = "n:" + Demo.kofi.pubkey
        demoShot("encryption-not-sealed-nothing") { ConversationScreen(other) }
        compose.onNodeWithTag("seal-lock").assertDoesNotExist()
        compose.onNodeWithTag("seal-notice").assertDoesNotExist()
    }

    /** messages/encryption-devices (+ -dark): my devices in full, the other person as a count. */
    @Test fun devices() {
        demoShot("encryption-devices") { EncryptionScreen(maya) }
        assertEquals(2, compose.onAllNodesWithTag("seal-my-device").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("seal-person").fetchSemanticsNodes().size)
        compose.onNodeWithText("2 devices").assertExists()
        compose.onNodeWithText("Removed a device today").assertExists()
        compose.onNodeWithTag("seal-counts-only").assertExists()
        compose.onNodeWithTag("seal-people").assertDoesNotExist()
        compose.onNodeWithText("When a device is removed, the lock changes: that device can't read anything new.").assertExists()
    }

    /** messages/encryption-devices-group: "3 people · 6 devices", only You unfolds. */
    @Test fun devicesGroup() {
        demoShot("encryption-devices-group") { EncryptionScreen(trio) }
        compose.onNodeWithText("3 people · 6 devices").assertExists()
        assertEquals(2, compose.onAllNodesWithTag("seal-person").fetchSemanticsNodes().size)
        compose.onNodeWithText("Added a device yesterday").assertExists()
        compose.onNodeWithText("When a device is removed or someone leaves, the lock changes: they can't read anything new.").assertExists()
    }

    @Test fun devicesGroupFolded() {
        demoShot("encryption-devices-group-folded", act = { compose.onNodeWithTag("seal-me").performClick() }) { EncryptionScreen(trio) }
        compose.onNodeWithTag("seal-my-device").assertDoesNotExist()
    }

    /** messages/encryption-email: an e-mail thread has the lock too; the composer says Reply by e-mail. */
    @Test fun email() {
        demoShot("encryption-email") { ConversationScreen(mail) }
        compose.onNodeWithTag("seal-lock").assertExists()
        compose.onNodeWithText("Reply by e-mail").assertExists()
    }

    /** messages/encryption-devices-email: "City Office · by e-mail", no count, the mail sentence. */
    @Test fun devicesEmail() {
        demoShot("encryption-devices-email") { EncryptionScreen(mail) }
        compose.onNodeWithText("City Office · by e-mail").assertExists()
        compose.onNodeWithText("not on this belfry").assertExists()
        compose.onNodeWithTag("seal-mail-sentence").assertExists()
        compose.onNodeWithTag("seal-counts-only").assertDoesNotExist()
    }

    /** messages/encryption-new-device: the amber banner, "Locked on this device" rows. */
    @Test fun newDevice() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-new-device") { MessagesScreen() }
        compose.onNodeWithTag("seal-banner").assertExists()
        assertTrue(compose.onAllNodesWithTag("seal-locked-row").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun showHow() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-new-device-steps") { UnlockStepsScreen() }
        compose.onNodeWithText("It asks whether to give this device access: tap Allow.").assertExists()
    }

    /** canvas v84 (messages/encryption-locked-thread): the amber block in place of every sealed message, no composer. */
    @Test fun lockedConversation() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-locked-thread-group") { ConversationScreen(trio) }
        compose.onNodeWithTag("seal-locked-block").assertExists()
        compose.onNodeWithTag("seal-write-later").assertExists()
        compose.onNodeWithTag("dm-bubble").assertDoesNotExist()
    }

    /** Messages from before the conversation was sealed read as usual, then the block. */
    @Test fun lockedConversationOlder() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-locked-thread") { ConversationScreen(maya) }
        compose.onNodeWithTag("seal-locked-block").assertExists()
        assertEquals(2, compose.onAllNodesWithTag("dm-bubble").fetchSemanticsNodes().size)
        compose.onNodeWithText("You can write here once this device is allowed.").assertExists()
    }

    /** canvas v86 (messages/encryption-shared-history): "Shared by Lena Fischer" above the shared block. */
    @Test fun sharedHistory() {
        demoShot("encryption-shared-history") { ConversationScreen(trio) }
        compose.onNodeWithText("Shared by Lena Fischer").assertExists()
        assertEquals(3, compose.onAllNodesWithTag("seal-shared-message").fetchSemanticsNodes().size)
    }

    @Test fun sharedHistoryWhy() {
        demoShot("encryption-shared-history-why") { Column(Modifier.padding(16.dp)) { SharedWhyContent("Lena Fischer") {} } }
        compose.onNodeWithText("Lena Fischer sent you these earlier messages. They come from Lena Fischer's device, so the app can't check them the way it checks new ones.").assertExists()
    }

    /** No banner on a device that isn't new. */
    @Test fun noBanner() {
        demoShot("encryption-messages") { MessagesScreen() }
        compose.onNodeWithTag("seal-banner").assertDoesNotExist()
        compose.onNodeWithTag("seal-locked-row").assertDoesNotExist()
    }

    /** messages/encryption-allow: a Material dialog over whatever is open. */
    @Test fun allow() {
        session.encryption.demoAsk("Firefox on Linux")
        val ask = session.encryption.state.value.asks.first()
        demoScreenShot("encryption-allow") {
            MessagesScreen()
            AccessAskDialog(ask, "Alex Morgan", session.now(), onDismiss = {}, onAllow = {}, onNotMe = {})
        }
        compose.onNodeWithTag("seal-ask").assertExists()
        compose.onNodeWithTag("seal-allow").assertExists()
        compose.onNodeWithTag("seal-not-me").assertExists()
    }

    /** Allow: the ask leaves, so every other device drops the prompt. */
    @Test fun allowDrops() {
        session.encryption.demoAsk()
        val id = session.encryption.state.value.asks.first().id
        runBlocking { session.encryption.allow(id) }
        assertTrue(session.encryption.state.value.asks.isEmpty())
    }

    /** messages/encryption-link-qr: inside Link a phone or tablet. */
    @Test fun linked() {
        session.encryption.demoLinked("Galaxy Tab")
        demoShot("encryption-link-qr") { LinkDeviceScreen() }
        compose.onNodeWithText("Galaxy Tab is linked").assertExists()
        compose.onNodeWithText("Scanning your code was the confirmation: nothing more to allow.").assertExists()
    }

    /** messages/encryption-share-history: the sheet's body. */
    @Test fun shareHistory() {
        val seal = session.encryption.state.value.seal(maya)!!
        demoShot("encryption-share-history") {
            Column(Modifier.padding(16.dp)) { ShareHistoryContent(seal, world, session.now(), onShare = {}, onNotNow = {}) }
        }
        compose.onNodeWithText("Share this conversation's history with Maya Okafor's new device?").assertExists()
        compose.onNodeWithText("A new phone · signed in today 9:50").assertExists()
    }

    /** The amber line opens the sheet. */
    @Test fun amberOpensSheet() {
        demoScreenShot("encryption-share-history-sheet", act = { compose.onNodeWithTag("seal-notice-amber").performClick() }) { ConversationScreen(maya) }
        compose.onNodeWithTag("seal-share").assertExists()
    }

    /** Shared: the amber line becomes the plain "<name> added a device" (canvas v81). */
    @Test fun sharedBecomesPlain() {
        runBlocking { session.encryption.shareHistory(maya) }
        val seal = session.encryption.state.value.seal(maya)!!
        assertNull(seal.waiting)
        assertFalse(seal.notices.any { it.kind == DeviceNotice.Kind.NEW_LOCKED })
        demoShot("encryption-thread-shared") { ConversationScreen(maya) }
        compose.onNodeWithTag("seal-notice-amber").assertDoesNotExist()
    }

    /** messages/encryption-remove-device: Me → Devices; Remove asks first. */
    @Test fun removeDevice() {
        demoScreenShot("encryption-remove-device", act = { compose.onNodeWithTag("seal-remove-d-laptop").performClick() }) { DevicesScreen() }
        compose.onNodeWithText("Remove Firefox on Linux?").assertExists()
        compose.onNodeWithTag("seal-remove").assertExists()
    }

    @Test fun devicesList() {
        demoShot("encryption-devices-me") { DevicesScreen() }
        assertEquals(2, compose.onAllNodesWithTag("seal-device").fetchSemanticsNodes().size)
        compose.onNodeWithText("This device can't be removed here: use Sign out.").assertExists()
    }

    /** canvas v87: Me has a Devices row once this device has a device list. */
    @Test fun meDevicesRow() {
        demoShot("encryption-me-devices") { MeScreen() }
        compose.onNodeWithTag("me-devices").assertExists()
        compose.onNodeWithText("Where you're signed in").assertExists()
    }
}
