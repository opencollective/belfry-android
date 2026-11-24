package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.enc.EncWrite
import sh.belfry.core.enc.HoldReason
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.WhoText
import sh.belfry.core.mail.MailThreads

/** Format 3, the screens of people conversations (SPEC 19.84): behaviour, on the demo with a made-up format-3 view. */
class Enc3ScreensTest : DemoShots() {
    private val maya get() = Enc3Fixture.maya()
    private val trio get() = MailThreads.nostrConv(Demo.tomas.pubkey, listOf(Demo.lena.pubkey, session.me), session.me)

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /** The lock next to the name only where the conversation is encrypted; never v1's lock on format 3. */
    @Test fun lockOnlyWhereEncrypted() {
        Enc3Fixture.install(session, mapOf(maya to DemoShots.NOW - 40 * 86400L))
        demoShot("enc3-t-lock") { ConversationScreen(maya) }
        assertEquals(1, count("enc3-lock"))
        assertEquals(0, count("seal-lock")) // the demo's maya conversation is sealed (v1): its lock never shows on format 3
        assertEquals(0, count("seal-notice-amber")) // nor its device lines
        assertEquals(0, count("seal-not-sealed"))
    }

    @Test fun noLockWhereNotEncrypted() {
        Enc3Fixture.install(session, mapOf(maya to DemoShots.NOW - 40 * 86400L))
        demoShot("enc3-t-nolock") { ConversationScreen(trio) }
        assertEquals(0, count("enc3-lock"))
        assertEquals(0, count("seal-lock"))
    }

    /** Format 1 (the demo as it is): v1's lock, never the format-3 one. */
    @Test fun formatOneKeepsTheV1Lock() {
        demoShot("enc3-t-v1") { ConversationScreen(maya) }
        assertEquals(1, count("seal-lock"))
        assertEquals(0, count("enc3-lock"))
    }

    /**
     * Notes to myself on format 3 (Xavier, Encrypted canvas v33 encrypted/notes-self, -who): "Encrypted for your N devices"
     * under the name, a tap opens Who can read this ("Only your devices can read your notes."); no fixed first line.
     */
    @Test fun notesSaySoInTheHeader() {
        val notes = "n:" + session.me
        Enc3Fixture.install(session, mapOf(notes to DemoShots.NOW - 40 * 86400L))
        compose.setContent { sh.belfry.app.ui.theme.BelfryTheme { InDemo { ConversationScreen(notes) } } }
        compose.waitForIdle()
        compose.onNodeWithText("Encrypted for your 3 devices", useUnmergedTree = true).assertExists()
        assertEquals(0, count("notes-line-sealed") + count("notes-line-unsealed"))
        compose.onNodeWithText("Encrypted for your 3 devices", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Only your devices can read your notes.").assertExists()
    }

    /** The Messages list shows no lock on format 3 (encrypted/list). */
    @Test fun noLockInTheList() {
        Enc3Fixture.install(session, mapOf(maya to DemoShots.NOW - 40 * 86400L, "n:" + session.me to DemoShots.NOW - 40 * 86400L))
        demoShot("enc3-t-list") { MessagesScreen() }
        assertEquals(0, count("notes-row-lock"))
        assertEquals(0, count("enc3-lock"))
    }

    /** Who can read this: my devices by their names, this device marked; the other person only with a count. */
    @Test fun whoCanReadNamesMineCountsOthers() {
        Enc3Fixture.install(session, mapOf(maya to DemoShots.NOW - 40 * 86400L))
        demoScreenShot("enc3-t-who", act = {
            compose.onNodeWithTag("enc3-lock").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Safari on Mac").fetchSemanticsNodes().isNotEmpty() }
        }) { ConversationScreen(maya) }
        compose.onNodeWithText(WhoText.TITLE).assertExists()
        compose.onNodeWithText("Belfry on Pixel 8").assertExists()
        compose.onNodeWithText("Safari on Mac").assertExists()
        compose.onNodeWithText(WhoText.THIS_DEVICE).assertExists()
        assertEquals(1, count("enc3-who-here"))
        assertEquals(2, count("enc3-who-mine"))
        // Maya: a person and a count, nothing else
        assertEquals(1, count("enc3-who-other"))
        compose.onNodeWithText("2 devices").assertExists()
        compose.onNodeWithText(WhoText.ONLY_COUNTS).assertExists()
        compose.onNodeWithText("Only Maya’s devices and yours can read this conversation.").assertExists()
        compose.onNodeWithText(WhoText.HOW_1).assertExists()
    }

    /** My agent, when it reads (a reader on my list), is among my devices. */
    @Test fun whoCanReadShowsMyAgent() {
        Enc3Fixture.install(session, mapOf(maya to DemoShots.NOW - 40 * 86400L), agent = Demo.wren.pubkey)
        demoScreenShot("enc3-t-agent", act = {
            compose.onNodeWithTag("enc3-lock").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Safari on Mac").fetchSemanticsNodes().isNotEmpty() }
        }) { ConversationScreen(maya) }
        assertEquals(1, count("enc3-who-agent"))
        compose.onNodeWithText("4 devices").assertExists()
    }

    /** E-mail on format 3: the header lock explains e-mail; no device count for the correspondent. */
    @Test fun mailLockExplainsMail() {
        Enc3Fixture.install(session, emptyMap())
        val mail = MailThreads.mailThreadOf("permits@cityhall.example", "Street party permit")
        demoScreenShot("enc3-t-mail", act = {
            compose.onNodeWithTag("enc3-lock").performClick()
            compose.waitForIdle()
        }) { ConversationScreen(mail) }
        assertEquals(1, count("enc3-who-mail"))
        assertEquals(1, count("enc3-who-mail-text"))
        assertEquals(0, count("enc3-who-other"))
        compose.onNodeWithText(WhoText.MAIL_LEAD).assertExists()
    }

    /** A held message's Try again asks part 3 to decide it again (Session.retryHeldDms). */
    @Test fun heldTryAgainRetries() {
        Enc3Fixture.install(session, mapOf(maya to DemoShots.NOW - 40 * 86400L))
        val now = session.now()
        val held = Dm("h1", maya, session.me, true, now - 60, 14, "I'll bring the long ladder", pending = true, heldSince = now - 60,
            heldWhy = DmSnapshot.enc3Why(EncWrite.Hold(HoldReason.FORK)))
        val before = session.heldRetries.get()
        demoShot("enc3-t-held") { Enc3Fixture.WithDms(session, listOf(held)) { ConversationScreen(maya) } }
        compose.onNodeWithText("Not sent: couldn’t check who can read this conversation").assertExists()
        compose.onNodeWithTag("dm-held-try-again", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { session.heldRetries.get() > before }
        assertTrue(session.heldRetries.get() > before)
    }

    /** The not-before line, once, where the unencrypted messages end. */
    @Test fun notBeforeOnce() {
        val first = session.now() - 3600
        Enc3Fixture.install(session, mapOf(maya to first))
        val m = Demo.maya.pubkey
        val items = listOf(
            Dm("a", maya, m, false, first - 7200, 14, "Shall we split the plot?", plain = true),
            Dm("b", maya, session.me, true, first - 7000, 14, "Yes, half each.", plain = true),
            Dm("c", maya, m, false, first + 60, 14, "Thursday at the plots?", enc = true),
            Dm("d", maya, session.me, true, first + 120, 14, "Yes, at 9.", enc = true),
        )
        demoShot("enc3-t-not-before") { Enc3Fixture.WithDms(session, items) { ConversationScreen(maya) } }
        assertEquals(1, count("enc3-not-before"))
    }
}
