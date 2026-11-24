package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.PlusSheetContent
import sh.belfry.app.ui.chat.rememberComposerState
import sh.belfry.app.ui.kit.SheetContent
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.GroupEncText

/**
 * Format 3, private groups (SPEC 19.88; Encrypted canvas encrypted/group …): pictures only, on the demo with a made-up
 * format-3 view ([Enc3GroupsFixture]). Run by hand: `gh workflow run ci.yml -f shots='*Enc3GroupsShotsTest*'`.
 */
class Enc3GroupsShotsTest : DemoShots() {
    private val g = Enc3GroupsFixture.G

    @androidx.compose.runtime.Composable
    private fun chat() = ChannelScreen(g, onMenu = {})

    private fun openWho(wait: String) {
        compose.onNodeWithContentDescription(GroupEncText.LOCK).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(wait, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** encrypted/group: the lock in the header. */
    @Test fun group() { Enc3GroupsFixture.install(session); Enc3GroupsFixture.talk(session); demoShot("enc3-group") { chat() } }

    /** encrypted/group-who: my devices by name, then each person with a count; Kofi signed in nowhere. */
    @Test fun who() { Enc3GroupsFixture.install(session); demoScreenShot("enc3-group-who", act = { openWho("Safari on Mac") }) { chat() } }

    /** encrypted/group-who-cant-check. */
    @Test fun whoCantCheck() { Enc3GroupsFixture.install(session, cant = true); demoScreenShot("enc3-group-who-cant-check", act = { openWho("Safari on Mac") }) { chat() } }

    /** encrypted/group-joined: I joined: from when I joined. */
    @Test fun joined() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.member(session, add = true, who = session.me, minutes = -5)
        Enc3GroupsFixture.talk(session)
        demoShot("enc3-group-joined") { chat() }
    }

    /** encrypted/group-joined-others: Aroha joined, as the others see it. */
    @Test fun joinedOthers() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.talk(session)
        Enc3GroupsFixture.member(session, add = true, who = Demo.aroha.pubkey, minutes = 10)
        session.demoAddEvents(listOf(Enc3GroupsFixture.say(Demo.maya, 12, "Welcome Aroha! We meet on Thursday at the hall.")))
        demoShot("enc3-group-joined-others") { chat() }
    }

    /** encrypted/group-removed: Kofi removed, the others' view with the (i). */
    @Test fun removed() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.talk(session)
        Enc3GroupsFixture.member(session, add = false, who = Demo.kofi.pubkey, minutes = 25)
        session.demoAddEvents(listOf(Enc3GroupsFixture.say(Demo.maya, 27, "Saturday is still on, 6 PM.")))
        demoShot("enc3-group-removed") { chat() }
    }

    /** encrypted/group-removed-info: the (i). */
    @Test fun removedInfo() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.talk(session)
        Enc3GroupsFixture.member(session, add = false, who = Demo.kofi.pubkey, minutes = 25)
        demoScreenShot("enc3-group-removed-info", act = {
            compose.onNodeWithContentDescription("What this means").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Kofi Mensah was removed").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
        }) { chat() }
    }

    /** encrypted/group-removed-self: Maya removed me. */
    @Test fun removedSelf() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.talk(session)
        Enc3GroupsFixture.removedMe(session)
        demoShot("enc3-group-removed-self") { chat() }
    }

    /** "‹name› can read from here", drawn from one key's entries to the next (no join line at that time). */
    @Test fun canReadFromHere() {
        Enc3GroupsFixture.install(session, changes = listOf(sh.belfry.core.engine.KeyChange("k".repeat(64), DemoShots.NOW - 3 * 3600 + 900, listOf(Demo.aroha.pubkey), listOf(Demo.kofi.pubkey), 0)))
        Enc3GroupsFixture.talk(session)
        demoShot("enc3-group-can-read") { chat() }
    }

    /** encrypted/group-email: "By e-mail". */
    @Test fun email() { Enc3GroupsFixture.install(session); Enc3GroupsFixture.mail(session); demoShot("enc3-group-email") { chat() } }

    /** Who can read this with the group's mail note. */
    @Test fun whoEmail() { Enc3GroupsFixture.install(session); Enc3GroupsFixture.mail(session); demoScreenShot("enc3-group-who-email", act = { openWho("Safari on Mac") }) { chat() } }

    /** encrypted/group-too-big: never unencrypted, not sent. */
    @Test fun tooBig() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.talk(session)
        Enc3GroupsFixture.held(session, "I'll bring the projector.", "invalid: enc3:TOO_MANY")
        demoShot("enc3-group-too-big") { chat() }
    }

    /** A message held: the group's members couldn't be checked; Try again. */
    @Test fun held() {
        Enc3GroupsFixture.install(session)
        Enc3GroupsFixture.talk(session)
        Enc3GroupsFixture.held(session, "See you at six", "held: enc3:GROUP_UNKNOWN")
        demoShot("enc3-group-held") { chat() }
    }

    /** Messages opening, and one with no copy for this device (Ask for them). */
    @Test fun waiting() { Enc3GroupsFixture.install(session, waiting = 2 to 1); Enc3GroupsFixture.talk(session); demoShot("enc3-group-waiting") { chat() } }

    /** A guest member's session: no composer. */
    @Test fun guest() { Enc3GroupsFixture.install(session); session.demoGuestMember(g); Enc3GroupsFixture.talk(session); demoShot("enc3-group-guest") { chat() } }

    /** The composer's ＋: events, meetings and proposals not available in a private group yet. */
    @Test fun notOffered() {
        Enc3GroupsFixture.install(session)
        demoShot("enc3-group-plus") { SheetContent { PlusSheetContent(g, admin = true, composer = rememberComposerState(g), onClose = {}, go = {}, onTodo = {}, onRecord = {}) } }
    }
}
