package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.belfry.app.ui.cards.MailParts
import sh.belfry.app.ui.kit.PICTURE_FAILED
import sh.belfry.core.mail.MailPart

/**
 * canvas v179 one-shape/email-pictures (n-email-pictures): an e-mail's attachments in a channel, after
 * its text: one picture large; three as two + one wide; other files as rows after the pictures; a
 * picture that can't be opened falls back to its row.
 */
class MailPicturesShotsTest : DemoShots() {
    private fun res(name: String) = sh.belfry.core.engine.Demo::class.java.getResourceAsStream("/demo/$name")!!.use { it.readBytes() }
    private fun jpg(name: String, res: String) = MailPart(name, "image/jpeg", res(res))
    private val pdf get() = MailPart("Permit form.pdf", "application/pdf", res("garden-plan.pdf"))

    private fun pictures(n: Int) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("inline-picture").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("inline-picture-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test fun onePictureAndAFile() {
        demoShot("mail-pictures-one", act = { pictures(1) }) {
            Column(Modifier.padding(16.dp)) { MailParts(listOf(jpg("street.jpg", "seedlings-1.jpg"), pdf), "one") }
        }
        compose.onNodeWithTag("inline-picture").assertExists()
        compose.onNodeWithText("Permit form.pdf").assertExists()
    }

    @Test fun threePicturesTwoPlusOneWide() {
        demoShot("mail-pictures-three", act = { pictures(3) }) {
            Column(Modifier.padding(16.dp)) {
                MailParts(listOf(jpg("a.jpg", "seedlings-1.jpg"), jpg("b.jpg", "seedlings-2.jpg"), jpg("c.jpg", "seedlings-3.jpg")), "three")
            }
        }
        compose.onNodeWithTag("inline-picture-grid").assertExists()
    }

    @Test fun brokenPictureFallsBackToItsRow() {
        demoShot("mail-pictures-broken", act = { pictures(1) }) {
            Column(Modifier.padding(16.dp)) {
                MailParts(listOf(jpg("street.jpg", "seedlings-1.jpg"), MailPart("broken.png", "image/png", ByteArray(40) { 7 })), "broken")
            }
        }
        compose.onNodeWithTag("inline-picture").assertExists()
        assertEquals(1, compose.onAllNodesWithText(PICTURE_FAILED).fetchSemanticsNodes().size)
        compose.onNodeWithText("broken.png").assertExists()
    }
}
