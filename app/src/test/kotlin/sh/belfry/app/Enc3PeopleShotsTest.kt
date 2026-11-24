package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Test
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.people.Bubble
import sh.belfry.app.ui.people.Enc3CountLine
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.enc.EncText
import sh.belfry.core.enc.EncWrite
import sh.belfry.core.enc.HoldReason
import sh.belfry.core.engine.Demo
import sh.belfry.core.read.ReadState

/**
 * SPEC 19.83 (format 3, people conversations): the states a message shows, drawn from made-up items (the demo stays
 * format 1). Minimal on purpose: parts 4–5 draw the final screens.
 */
class Enc3PeopleShotsTest : DemoShots() {
    @Test fun states() {
        val maya = Demo.maya.pubkey
        val conv = "n:$maya"
        val now = session.now()
        val me = session.me
        val items = listOf(
            Dm("p:1", conv, "", false, now - 300, 14, "", opening = true),
            Dm("a", conv, maya, false, now - 240, 14, "Sent from an old app", plain = true, clear = true),
            Dm("b", conv, me, true, now - 120, 14, "Dinner at 8?", enc = true, notTo = listOf(Demo.lena.pubkey)),
            Dm("c", conv, me, true, now - 60, 14, "For Maya alone", heldSince = now - 60, heldWhy = DmSnapshot.enc3Why(EncWrite.Hold(HoldReason.NO_DEVICE, maya))),
        )
        val dms = DmSnapshot(items, emptyMap(), ReadState.EMPTY, me, now)
        demoShot("enc3-people-states") {
            val world = LocalWorld.current
            Column {
                Enc3CountLine(EncText.unreadable(2), "enc3-unreadable")
                Enc3CountLine(EncText.opening(1), "enc3-opening")
                for (d in items) Bubble(d, dms, world, group = true, onMenu = {}, notSealed = if (d.clear) ({}) else null, onCancelHeld = { true })
            }
        }
        compose.onNodeWithTag("dm-opening", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(EncText.NOT_ENCRYPTED, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("dm-wont-get", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("dm-held-try-again", useUnmergedTree = true).assertExists()
    }
}
