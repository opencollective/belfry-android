package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.belfry.app.ui.people.MessagesScreen

/**
 * canvas home/messages (v186), SPEC 19.59: every row is a face, the name in bold, one line. The demo's
 * e-mail threads (Ana Pereira, City Office) are initials discs with the envelope on the corner: no
 * network in tests or the demo, which is what a belfry without the avatar service (404) looks like.
 */
class MailFacesShotsTest : DemoShots() {
    @Test fun messagesFaces() {
        demoShot("messages-faces") { MessagesScreen() }
        // e-mail rows: the correspondent's name, then the subject
        compose.onNodeWithText("Ana Pereira").assertExists()
        compose.onNodeWithText("Visiting next week").assertExists()
        compose.onNodeWithText("City Office").assertExists()
        compose.onNodeWithText("Street party permit").assertExists()
        // their faces: initials discs marked as e-mail, never an envelope as the face
        compose.onNodeWithContentDescription("AP, by e-mail").assertExists()
        compose.onNodeWithContentDescription("CO, by e-mail").assertExists()
        val faces = compose.onAllNodesWithTag("mail-face", useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals(2, faces)
        assertEquals(faces, compose.onAllNodesWithTag("mail-face-envelope", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Ana Pereira · ", substring = true).fetchSemanticsNodes().size)
    }
}
