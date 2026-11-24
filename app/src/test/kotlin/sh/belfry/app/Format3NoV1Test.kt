package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.model.Notes

/**
 * The lesson of Xavier's web trial on belfry.sh (2026-11-24): its format-3 runtime ran and the web still showed
 * version 1's "Messages are locked on this device" banner and a Notes line that waited for this device to be allowed.
 * On a belfry this device knows as format 3 (Session.format3Here), NO version-1 lock UI shows anywhere, even with
 * version-1 state that says "locked" and no format-3 runtime at all (the demo has none); Notes keeps its composer
 * (it is written as a format-3 people conversation with only me: Enc3PeopleTest.notesToMyselfAreOnePerson).
 */
class Format3NoV1Test : DemoShots() {
    @After fun off() = session.demoFormat3Here(false)

    private fun none(tag: String) = assertTrue("no $tag", compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    private fun noText(t: String) = assertTrue("no \"$t\"", compose.onAllNodesWithText(t, substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty())

    @Test
    fun messagesShowNoVersion1LockOnFormat3() {
        session.encryption.demoAsNewDevice() // version 1 says: this device is new, everything locked
        session.demoFormat3Here(true)
        compose.setContent { BelfryTheme { InDemo { MessagesScreen() } } }
        compose.waitForIdle()
        none("seal-banner"); none("seal-locked-row"); none("seal-waiting-for"); none("seal-added")
        noText("locked on this device"); noText("Nothing to approve"); noText("No other device?")
    }

    @Test
    fun notesShowNoVersion1LockAndKeepTheComposerOnFormat3() {
        session.encryption.demoAsNewDevice()
        session.demoFormat3Here(true)
        compose.setContent { BelfryTheme { InDemo { ConversationScreen(Notes.conv(session.me)) } } }
        compose.waitForIdle()
        none("seal-locked-block"); none("seal-lock"); none("notes-line-sealed"); none("seal-waiting-for")
        noText("locked on this device"); noText("allowed")
        assertTrue("the composer is there", compose.onAllNodesWithTag("composer-field").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theSameStateOnFormat1StillShowsVersion1() {
        // the control: without format 3 the version-1 banner is there (so the tests above test something)
        session.encryption.demoAsNewDevice()
        compose.setContent { BelfryTheme { InDemo { MessagesScreen() } } }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("seal-banner").fetchSemanticsNodes().isNotEmpty())
    }
}
