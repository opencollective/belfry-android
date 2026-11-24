package sh.belfry.app

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Test
import org.robolectric.annotation.Config
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.Enc3FirstScreenContent
import sh.belfry.app.ui.people.Enc3OnlyDeviceDialog
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.app.ui.people.RemovedDeviceScreen
import sh.belfry.core.enc.DeviceCard
import sh.belfry.core.enc.DeviceName
import sh.belfry.core.enc.Enc3DeviceState
import sh.belfry.core.enc.FirstRow
import sh.belfry.core.enc.RemovedHere
import sh.belfry.core.engine.Demo

/**
 * SPEC 19.85 (format 3, part 5; Encrypted canvas encrypted/new-*, transition, card*, laptop-card, removed-device,
 * signout-only, lost-all*, edge-added-removed, edge-two-lists, first-screen): pictures only, from a made-up part-5 state
 * (the demo stays format 1). Run by hand: `gh workflow run ci.yml --ref <branch> -f shots='*Enc3DeviceShotsTest*'`.
 */
class Enc3DeviceShotsTest : DemoShots() {
    private val conv get() = "n:" + Demo.maya.pubkey
    private val iphone = DeviceName("1".repeat(64), "Safari on iPhone")
    private val mac = DeviceName("2".repeat(64), "Safari on Mac")
    private val android = DeviceCard("3".repeat(64), DemoShots.NOW - 50 * 60, name = "Chrome on Android")
    private val base = Enc3DeviceState(on = true, listed = true, older = listOf(iphone, mac))

    private fun state(st: Enc3DeviceState) = session.demoEnc3Device(st)

    private fun bottomOfMessages(tag: String = "enc3-earlier") {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
    }

    @Test fun newList() {
        state(base.copy(demoMissing = 4))
        demoShot("enc3-new-list", act = { bottomOfMessages() }) { MessagesScreen() }
    }

    @Test fun newThread() {
        state(base.copy(demoMissing = 4)); session.demoEncConvs = setOf(conv)
        demoShot("enc3-new-thread") { ConversationScreen(conv) }
    }

    @Test fun newAsked() {
        state(base.copy(demoMissing = 4, askedAt = DemoShots.NOW - 60)); session.demoEncConvs = setOf(conv)
        demoShot("enc3-new-asked") { ConversationScreen(conv) }
    }

    @Test fun newArrived() {
        state(base.copy(sharedBy = iphone)); session.demoEncConvs = setOf(conv)
        demoShot("enc3-new-arrived") { ConversationScreen(conv) }
    }

    @Test fun transition() {
        state(base.copy(older = listOf(iphone), demoV1Missing = 3, since3 = DemoShots.NOW - 86_400)); session.demoEncConvs = setOf(conv)
        demoShot("enc3-transition") { ConversationScreen(conv) }
    }

    @Test fun lostAll() {
        state(base.copy(older = emptyList(), demoMissing = 4)); session.demoEncConvs = setOf(conv)
        demoShot("enc3-lost-all", act = { bottomOfMessages("enc3-lost") }) { MessagesScreen() }
    }

    @Test fun lostAllThread() {
        state(base.copy(older = emptyList(), demoMissing = 4)); session.demoEncConvs = setOf(conv)
        demoShot("enc3-lost-all-thread") { ConversationScreen(conv) }
    }

    @Test fun card() {
        state(base.copy(cards = listOf(android)))
        demoShot("enc3-card") { MessagesScreen() }
    }

    @Test fun cardSharing() {
        state(base.copy(cards = listOf(android)))
        demoShot("enc3-card-sharing", act = { compose.onNodeWithTag("enc3-share").performClick() }) { MessagesScreen() }
    }

    @Test fun cardRemove() {
        state(base.copy(cards = listOf(android)))
        demoScreenShot("enc3-card-remove", act = { compose.onNodeWithTag("enc3-remove").performClick() }) { MessagesScreen() }
    }

    @Test fun cardShared() {
        state(base.copy(notices = listOf("Earlier messages shared with Chrome on Android")))
        demoShot("enc3-card-shared") { MessagesScreen() }
    }

    @Test fun cardRemoved() {
        state(base.copy(notices = listOf("Chrome on Android was removed.")))
        demoShot("enc3-card-removed") { MessagesScreen() }
    }

    @Test fun cardAnswered() {
        state(base.copy(notices = listOf("Safari on Mac shared earlier messages with Chrome on Android")))
        demoShot("enc3-card-answered") { MessagesScreen() }
    }

    @Config(qualifiers = "w1280dp-h800dp-land-240dpi")
    @Test fun laptopCard() {
        state(base.copy(cards = listOf(android)))
        demoShot("enc3-laptop-card") { MessagesScreen() }
    }

    @Test fun addedAndRemoved() {
        state(base.copy(cards = listOf(DeviceCard("4".repeat(64), DemoShots.NOW - 11 * 3600, gone = true, goneAt = DemoShots.NOW - 10 * 3600, name = "Firefox on Windows"))))
        demoShot("enc3-edge-added-removed") { MessagesScreen() }
    }

    @Test fun twoLists() {
        state(base.copy(fork = true))
        demoShot("enc3-edge-two-lists") { MessagesScreen() }
    }

    @Test fun strangeEntry() {
        state(base.copy(strange = true))
        demoShot("enc3-edge-strange") { MessagesScreen() }
    }

    @Test fun firstScreen() {
        val rows = listOf(
            FirstRow(iphone.key, DemoShots.NOW - 90 * 86_400, name = iphone.name),
            FirstRow(mac.key, DemoShots.NOW - 40 * 86_400, name = mac.name),
            FirstRow(Demo.maya.pubkey, DemoShots.NOW - 40 * 86_400, reader = true),
        )
        demoShot("enc3-first-screen") { Enc3FirstScreenContent(rows, LocalWorld.current.ctx.zone, onRemove = {}, onContinue = {}) }
    }

    @Test fun removedDevice() = demoShot("enc3-removed-device") {
        RemovedDeviceScreen(RemovedHere(DemoShots.NOW - 120, "Safari on iPhone"), DemoShots.NOW, LocalWorld.current.ctx.zone) {}
    }

    @Test fun signoutOnly() = demoScreenShot("enc3-signout-only") { Enc3OnlyDeviceDialog(onAdd = {}, onAnyway = {}, onDismiss = {}) }
}
