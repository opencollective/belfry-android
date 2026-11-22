package sh.belfry.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.model.Notes

/**
 * Notes to myself (collective-belfry docs/one-shape.md; SPEC 19.66; canvas messages/notes-list,
 * one-shape/notes-empty, notes-thread, notes-unsealed): the first row of Messages, and its screen
 * sealed (the lock and the fixed line) and on an account that is not sealed (no lock, the other line).
 */
class NotesShotsTest : DemoShots() {
    private val notes get() = Notes.conv(session.me)

    /** The demo without its notes: the row and the screen while empty. */
    @Composable
    private fun WithoutNotes(content: @Composable () -> Unit) {
        val d = LocalDms.current
        val empty = DmSnapshot(d.items.filter { it.conv != notes }, d.reports, d.read, d.me, d.now, d.reactions)
        CompositionLocalProvider(LocalDms provides empty, content = content)
    }

    /** messages/notes-list: Notes first with its pin, the last note, no count. */
    @Test fun list() {
        demoShot("notes-list") { MessagesScreen() }
        val rows = session.dms.value.messagesRows
        assertEquals(notes, rows.first().conv)
        assertTrue(rows.size > 1 && rows.drop(1).none { it.notes })
        // a newer conversation exists, and Notes is still above it
        assertTrue(rows[1].thread!!.last.at > rows[0].thread!!.last.at)
        val top = compose.onNodeWithTag("notes-row").fetchSemanticsNode().boundsInRoot.top
        val maya = compose.onAllNodesWithText("Maya Okafor").fetchSemanticsNodes().first().boundsInRoot.top
        assertTrue("Notes is above every conversation", top < maya)
        compose.onNodeWithText(Notes.TITLE).assertExists()
        compose.onNodeWithTag("notes-pin", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("notes-row-lock", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Bring the extension reel, 2 plugs").assertExists()
    }

    /** The row opens the conversation whose only participant is me. */
    @Test fun rowOpens() {
        demoShot("notes-list-tap", act = { compose.onNodeWithTag("notes-row").performClick() }) { MessagesScreen() }
        assertTrue(nav.went.toString(), nav.went.any { it.startsWith("conversation/") })
    }

    /** Empty: the row is there all the same, "Only you can read these". */
    @Test fun listEmpty() {
        demoShot("notes-list-empty") { WithoutNotes { MessagesScreen() } }
        compose.onNodeWithTag("notes-row").assertExists()
        compose.onNodeWithText(Notes.EMPTY_PREVIEW).assertExists()
    }

    /** An account that is not sealed: the row without the lock. */
    @Test fun listUnsealed() {
        session.encryption.demoNotSealed()
        demoShot("notes-list-unsealed") { MessagesScreen() }
        compose.onNodeWithTag("notes-row").assertExists()
        compose.onNodeWithTag("notes-row-lock", useUnmergedTree = true).assertDoesNotExist()
    }

    /** one-shape/notes-thread: the lock by the name, the fixed first line, a few notes and a picture. */
    @Test fun thread() {
        demoShot("notes-thread") { ConversationScreen(notes) }
        compose.onNodeWithTag("seal-lock").assertExists()
        compose.onNodeWithTag("notes-line-sealed").assertExists()
        compose.onNodeWithText(Notes.SEALED_LINE).assertExists()
        compose.onNodeWithText(Notes.UNSEALED_LINE).assertDoesNotExist()
        compose.onNodeWithText("Bring the extension reel, 2 plugs").assertExists()
    }

    /** The line opens Encryption, as the lock does. */
    @Test fun lineOpensEncryption() {
        demoShot("notes-thread-line", act = { compose.onNodeWithTag("notes-line-sealed").performClick() }) { ConversationScreen(notes) }
        assertTrue(nav.went.toString(), nav.went.any { it.startsWith("encryption/") })
    }

    /** one-shape/notes-empty: what it is, before the first note. */
    @Test fun empty() {
        demoShot("notes-empty") { WithoutNotes { ConversationScreen(notes) } }
        compose.onNodeWithTag("seal-lock").assertExists()
        compose.onNodeWithText(Notes.SEALED_LINE).assertExists()
        compose.onNodeWithText(Notes.EMPTY_TEXT).assertExists()
        assertTrue(compose.onAllNodesWithText(Notes.TITLE).fetchSemanticsNodes().isNotEmpty())
    }

    /** one-shape/notes-unsealed: no lock, no claim. */
    @Test fun unsealed() {
        session.encryption.demoNotSealed()
        demoShot("notes-unsealed") { ConversationScreen(notes) }
        compose.onNodeWithTag("seal-lock").assertDoesNotExist()
        compose.onNodeWithTag("notes-line-unsealed").assertExists()
        compose.onNodeWithText(Notes.UNSEALED_LINE).assertExists()
        compose.onNodeWithText(Notes.SEALED_LINE).assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("obody else", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun unsealedEmpty() {
        session.encryption.demoNotSealed()
        demoShot("notes-unsealed-empty") { WithoutNotes { ConversationScreen(notes) } }
        compose.onNodeWithTag("seal-lock").assertDoesNotExist()
        compose.onNodeWithText(Notes.UNSEALED_LINE).assertExists()
        assertTrue(compose.onAllNodesWithText("obody else", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
