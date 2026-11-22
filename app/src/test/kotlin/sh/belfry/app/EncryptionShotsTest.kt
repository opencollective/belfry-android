package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import sh.belfry.app.ui.people.NotSealedContent
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
        compose.onNodeWithText("You (you)").assertDoesNotExist()
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

    /**
     * messages/encryption-new-device (canvas Messages v5, n-e2e-locked-named): the amber banner names the
     * device that holds the key, says when this one asked and that there is nothing to approve here;
     * "Locked on this device" rows; "No other device?" opens the screen with Start fresh.
     */
    @Test fun newDevice() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-new-device") { MessagesScreen() }
        compose.onNodeWithTag("seal-banner").assertExists()
        assertTrue(compose.onAllNodesWithTag("seal-locked-row", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Open Chat · Firefox on Linux. This device is added by itself as soon as that one is open.").assertExists()
        compose.onNodeWithText("waiting for one of your devices", substring = true).assertExists()
        compose.onNodeWithText("Asked ", substring = true).assertExists()
        compose.onNodeWithText("Nothing to approve on this device.").assertExists()
        compose.onNodeWithText("Show how").assertDoesNotExist()
        compose.onNodeWithText("tap Allow", substring = true).assertDoesNotExist()
        compose.onNodeWithText("No other device?").performClick()
        assertTrue(nav.went.contains(sh.belfry.app.ui.nav.Routes.UNLOCK_STEPS))
    }

    /** The same with two devices that can read: "Open A or B. … as soon as one is open." */
    @Test fun newDeviceTwoNamed() {
        session.encryption.demoAsNewDevice(unlockers = listOf("Chat · Safari on Mac", "Belfry on Pixel 8"))
        demoShot("encryption-new-device-two") { MessagesScreen() }
        compose.onNodeWithText("Open Chat · Safari on Mac or Belfry on Pixel 8. This device is added by itself as soon as one is open.").assertExists()
    }

    /** The session names aren't known and this device hasn't asked yet: both said plainly. */
    @Test fun newDeviceUnnamedNotAsked() {
        session.encryption.demoAsNewDevice(unlockers = emptyList(), asked = false)
        demoShot("encryption-new-device-unnamed") { MessagesScreen() }
        compose.onNodeWithText("Open Belfry on another device of yours. This device is added by itself as soon as that one is open.").assertExists()
        compose.onNodeWithText(sh.belfry.core.model.SealedText.NOT_ASKED).assertExists()
    }

    /** The state as data: the head's device names and when this device asked. */
    @Test fun waitingStateIsData() {
        session.encryption.demoAsNewDevice()
        val st = session.encryption.state.value
        assertEquals(listOf("Chat · Firefox on Linux"), st.unlockers)
        assertEquals(session.now() - 120, st.askedAt)
    }

    /** messages/encryption-waiting-list: a device not added yet that holds messages it can't open: how many, who can open them, when it asked. */
    @Test fun waitingList() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-waiting-list") {
            val d = sh.belfry.app.ui.nav.LocalDms.current
            val waiting = sh.belfry.core.dm.DmSnapshot(d.items, d.reports, d.read, d.me, d.now, d.reactions, lockedCount = 3)
            androidx.compose.runtime.CompositionLocalProvider(sh.belfry.app.ui.nav.LocalDms provides waiting) { MessagesScreen() }
        }
        compose.onNodeWithText("3 messages are waiting for another of your devices").assertExists()
        compose.onNodeWithTag("seal-open-device").assertExists()
        compose.onNodeWithTag("seal-asked").assertExists()
    }

    /** messages/encryption-locked-added: added, the key on its way: "Added … · Your messages open here in a moment." */
    @Test fun lockedAdded() {
        session.encryption.demoWaitingKeys()
        demoShot("encryption-locked-added") { MessagesScreen() }
        compose.onNodeWithTag("seal-added-here").assertExists()
        compose.onNodeWithText("Added ", substring = true).assertExists()
        compose.onNodeWithText("Your messages open here in a moment.").assertExists()
        compose.onNodeWithTag("seal-banner").assertDoesNotExist()
    }

    /**
     * Xavier's mail to himself (2026-11-22; canvas v177 draft messages/encryption-waiting-list): a device
     * not allowed yet that received sealed messages it can't open says how many wait, even when no
     * conversation it knows is locked.
     */
    @Test fun waitingMessages() {
        demoShot("encryption-waiting-messages") {
            val d = sh.belfry.app.ui.nav.LocalDms.current
            val waiting = sh.belfry.core.dm.DmSnapshot(d.items, d.reports, d.read, d.me, d.now, d.reactions, lockedCount = 3)
            androidx.compose.runtime.CompositionLocalProvider(sh.belfry.app.ui.nav.LocalDms provides waiting) { MessagesScreen() }
        }
        compose.onNodeWithTag("seal-banner").assertExists()
        compose.onNodeWithText("3 messages are waiting for another of your devices").assertExists()
    }

    /** The lead, 2026-11-22: on a device not allowed yet, every conversation has the lock; Encryption says it can't read it yet. */
    @Test fun waitingLockEverywhere() {
        session.encryption.demoAsNewDevice()
        val mail = MailThreads.mailThreadOf("ana@example.org", "Visiting next week")
        demoShot("encryption-waiting-lock") { ConversationScreen(mail) }
        compose.onNodeWithTag("seal-lock").assertExists()
    }

    @Test fun waitingEncryptionScreen() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-waiting-screen") { EncryptionScreen(MailThreads.mailThreadOf("ana@example.org", "Visiting next week")) }
        compose.onNodeWithText(sh.belfry.core.model.SealedText.CANT_READ_YET).assertExists()
        compose.onNodeWithTag("seal-show-how").assertExists()
        // canvas Messages v5 messages/encryption-devices-waiting: the named device, the state, and this device "Not added yet · asked …"
        compose.onNodeWithTag("seal-open-device").assertExists()
        compose.onNodeWithTag("seal-asked").assertExists()
        compose.onNodeWithText("Nothing to approve on this device.").assertExists()
        assertTrue(compose.onAllNodesWithText("Not added yet · asked", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    /** canvas v183 messages/encryption-device-added: on top of Messages, "Belfry for iPhone was added just now", Remove / It was me. */
    @Test fun deviceAdded() {
        session.encryption.demoAdded("Belfry for iPhone")
        demoShot("encryption-device-added") { MessagesScreen() }
        compose.onNodeWithText("Belfry for iPhone was added just now").assertExists()
        compose.onNodeWithText(sh.belfry.core.model.SealedText.ADDED_TEXT).assertExists()
        compose.onNodeWithTag("seal-added-remove").assertExists()
        compose.onNodeWithTag("seal-added-mine").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("seal-added").assertDoesNotExist()
    }

    @Test fun showHow() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-new-device-steps") { UnlockStepsScreen() }
        compose.onNodeWithText("No other device?").assertExists()
        compose.onNodeWithText("Open Chat · Firefox on Linux. This device is added by itself as soon as that one is open. Nothing to approve here.").assertExists()
        compose.onNodeWithText("The people you write to can also share a conversation's history with this device.").assertExists()
        compose.onNodeWithText("tap Allow", substring = true).assertDoesNotExist()
    }

    /** canvas v84 (messages/encryption-locked-thread): the amber block in place of every sealed message, no composer. */
    @Test fun lockedConversation() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-locked-thread-group") { ConversationScreen(trio) }
        compose.onNodeWithTag("seal-locked-block").assertExists()
        compose.onNodeWithTag("seal-write-later").assertExists()
        compose.onNodeWithTag("dm-bubble").assertDoesNotExist()
        compose.onNodeWithTag("seal-open-device").assertExists()
        compose.onNodeWithTag("seal-asked").assertExists()
    }

    /** Messages from before the conversation was sealed read as usual, then the block. */
    @Test fun lockedConversationOlder() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-locked-thread") { ConversationScreen(maya) }
        compose.onNodeWithTag("seal-locked-block").assertExists()
        assertEquals("Maya's three messages from before it was sealed", 3, compose.onAllNodesWithTag("dm-bubble").fetchSemanticsNodes().size)
        compose.onNodeWithText("You can write here once this device is added.").assertExists()
        compose.onNodeWithText("Open Chat · Firefox on Linux. This device is added by itself as soon as that one is open.").assertExists()
        compose.onNodeWithText("Nothing to approve on this device.").assertExists()
        compose.onNodeWithTag("seal-show-how").assertExists()
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
        compose.onNodeWithTag("seal-locked-row", useUnmergedTree = true).assertDoesNotExist()
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
        compose.onNodeWithText("Signed in as You").assertDoesNotExist()
    }

    /** canvas v87: Me has a Devices row once this device has a device list. */
    @Test fun meDevicesRow() {
        demoShot("encryption-me-devices") { MeScreen() }
        compose.onNodeWithTag("me-devices").assertExists()
        compose.onNodeWithText("Where you're signed in").assertExists()
    }

    /** canvas v89: "Not sealed" on a message that came the old way after the conversation was sealed. */
    @Test fun notSealedMark() {
        demoShot("encryption-not-sealed-mark") { ConversationScreen(maya) }
        assertEquals(1, compose.onAllNodesWithTag("seal-not-sealed", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun notSealedWhy() {
        demoShot("encryption-not-sealed-why") { Column(Modifier.padding(16.dp)) { NotSealedContent {} } }
        compose.onNodeWithText("This message was not sent with the lock. It may come from an old app version.").assertExists()
    }

    /** canvas v90: a 21st device can't be allowed. */
    @Test fun allowTooMany() {
        session.encryption.demoAsk("Firefox on Linux")
        val ask = session.encryption.state.value.asks.first()
        demoScreenShot("encryption-allow-too-many") {
            MessagesScreen()
            AccessAskDialog(ask, "Alex Morgan", session.now(), onDismiss = {}, onAllow = {}, onNotMe = {}, startFull = true)
        }
        compose.onNodeWithText("You already have 20 devices. Remove one in Devices, then try again.").assertExists()
        compose.onNodeWithTag("seal-too-many-devices").assertExists()
    }

    /** canvas v90: a device that is itself locked shows no Remove. */
    @Test fun lockedDeviceNoRemove() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-devices-locked") { DevicesScreen() }
        compose.onNodeWithTag("seal-remove-d-laptop").assertDoesNotExist()
    }

    /** canvas v96 messages/encryption-waiting: allowed, no key yet: messages as usual, the composer waits. */
    @Test fun waitingKeys() {
        session.encryption.demoWaitingKeys()
        demoShot("encryption-waiting") { ConversationScreen(maya) }
        compose.onNodeWithTag("seal-waiting-keys").assertExists()
        compose.onNodeWithText("Open Chat · Firefox on Linux. Nothing to approve here.").assertExists()
        compose.onNodeWithText("waiting for one of your devices", substring = true).assertExists()
        compose.onNodeWithTag("seal-locked-block").assertDoesNotExist()
        assertTrue(compose.onAllNodesWithTag("dm-bubble").fetchSemanticsNodes().isNotEmpty())
    }

    /** canvas v96 messages/encryption-allow-waiting: "Not allowed yet: waiting for your other device". */
    @Test fun allowWaiting() {
        session.encryption.demoAsk("Firefox on Linux")
        val ask = session.encryption.state.value.asks.first()
        demoScreenShot("encryption-allow-waiting") {
            MessagesScreen()
            AccessAskDialog(ask, "Alex Morgan", session.now(), onDismiss = {}, onAllow = {}, onNotMe = {}, startWaiting = true)
        }
        compose.onNodeWithText("Not allowed yet: waiting for your other device").assertExists()
        compose.onNodeWithTag("seal-not-me").assertExists()
    }

    /** canvas v117 messages/encryption-only-device (+ -dark): my only device asks before Sign out. */
    @Test fun onlyDeviceSignOut() {
        session.encryption.demoOnlyDevice()
        assertTrue(session.encryption.state.value.onlyDevice)
        demoScreenShot("encryption-only-device", act = { compose.onNodeWithTag("me-signout").performScrollTo().performClick() }) { MeScreen() }
        compose.onNodeWithTag("seal-only-device").assertExists()
        compose.onNodeWithText("This is your only device for sealed conversations").assertExists()
        compose.onNodeWithText("If you sign out, your sealed messages can't be opened again, here or on a new device. Only what the people you write with share again comes back.").assertExists()
        compose.onNodeWithText("To keep them, link another device first.").assertExists()
        compose.onNodeWithText("Link a device").assertExists()
        compose.onNodeWithText("Sign out anyway").assertExists()
        compose.onNodeWithTag("seal-only-cancel").assertExists()
    }

    /** Link a device goes to Link a phone, and nobody is signed out. */
    @Test fun onlyDeviceLink() {
        session.encryption.demoOnlyDevice()
        demoScreenShot("encryption-only-device-link", act = {
            compose.onNodeWithTag("me-signout").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("seal-only-link").performClick()
        }) { MeScreen() }
        assertTrue(nav.went.contains(sh.belfry.app.ui.nav.Routes.LINK_DEVICE))
        compose.onNodeWithTag("seal-only-device").assertDoesNotExist()
    }

    /** With another device in my roster: the usual "Sign out?". */
    @Test fun twoDevicesPlainSignOut() {
        assertFalse(session.encryption.state.value.onlyDevice)
        demoScreenShot("encryption-signout-two-devices", act = { compose.onNodeWithTag("me-signout").performScrollTo().performClick() }) { MeScreen() }
        compose.onNodeWithTag("seal-only-device").assertDoesNotExist()
        compose.onNodeWithText("Sign out?").assertExists()
    }

    /** Waiting to be allowed (no letterbox of mine on this device yet): no warning. */
    @Test fun onlyDeviceNotWhileWaiting() {
        session.encryption.demoOnlyDevice()
        session.encryption.demoAsNewDevice()
        assertFalse(session.encryption.state.value.onlyDevice)
    }

    /** canvas v84 messages/encryption-start-fresh: no other device → Start fresh on this device. */
    @Test fun startFreshRow() {
        session.encryption.demoAsNewDevice()
        demoShot("encryption-start-fresh") { UnlockStepsScreen() }
        compose.onNodeWithText("No other device?").assertExists()
        compose.onNodeWithText("With another device").assertExists()
        compose.onNodeWithTag("seal-no-other").assertExists()
        compose.onNodeWithText("I don't have another device").assertExists()
        compose.onNodeWithText("Start fresh on this device").assertExists()
    }

    /** messages/encryption-start-fresh-confirm: the sheet, then Start fresh unlocks this device. */
    @Test fun startFreshConfirm() {
        session.encryption.demoAsNewDevice()
        demoScreenShot("encryption-start-fresh-confirm", act = { compose.onNodeWithTag("seal-no-other").performScrollTo().performClick() }) { UnlockStepsScreen() }
        compose.onNodeWithText("Start fresh on this device?").assertExists()
        compose.onNodeWithText("Earlier sealed messages stay locked here unless someone shares them with you or an old device comes back.", substring = true).assertExists()
        compose.onNodeWithText("The people you write with will see that you started on a new device.", substring = true).assertExists()
        compose.onNodeWithTag("seal-fresh-confirm").performClick()
        compose.waitUntil(5_000) { !session.encryption.state.value.earlierLocked }
        assertTrue(session.encryption.state.value.onlyDevice)
    }

    /** A device in my head sees the steps but no Start fresh. */
    @Test fun noStartFreshInHead() {
        demoShot("encryption-unlock-in-head") { UnlockStepsScreen() }
        compose.onNodeWithTag("seal-no-other").assertDoesNotExist()
    }

    /** An old device after a fresh start elsewhere: where and when, Sign out every other device / It was me. */
    @Test fun freshElsewhere() {
        session.encryption.demoFreshElsewhere()
        demoScreenShot("encryption-fresh-elsewhere") { MessagesScreen(); sh.belfry.app.ui.people.FreshElsewhereHost() }
        compose.onNodeWithTag("seal-fresh-elsewhere").assertExists()
        compose.onNodeWithText("Sealed conversations were started fresh on \u201cFirefox on Linux\u201d").assertExists()
        compose.onNodeWithText("Today at", substring = true).assertExists()
        compose.onNodeWithText("This device can't read new sealed messages until it's allowed again.").assertExists()
        compose.onNodeWithText("If this wasn't you, sign out every other device.").assertExists()
        compose.onNodeWithTag("seal-fresh-elsewhere-me").assertExists()
    }

    @Test fun freshElsewhereSignOut() {
        session.encryption.demoFreshElsewhere()
        demoScreenShot("encryption-fresh-elsewhere-done", act = {
            compose.onNodeWithTag("seal-fresh-elsewhere-signout").performClick()
            compose.waitUntil(5_000) { session.encryption.state.value.freshElsewhere == null }
            compose.waitForIdle()
        }) { MessagesScreen(); sh.belfry.app.ui.people.FreshElsewhereHost() }
        compose.onNodeWithTag("seal-fresh-elsewhere-done").assertExists()
        compose.onNodeWithText("Firefox on Windows and Safari on Mac can't open your account any more.").assertExists()
        compose.onNodeWithText("To read sealed conversations here again, start fresh on this device.").assertExists()
        compose.onNodeWithTag("seal-fresh-done-show-how").performClick()
        assertTrue(nav.went.contains(sh.belfry.app.ui.nav.Routes.UNLOCK_STEPS))
    }
}
