package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.belfry.app.ui.shell.ConfirmStep
import sh.belfry.app.ui.shell.ConnectedAppsScreen
import sh.belfry.app.ui.shell.DoneStep
import sh.belfry.app.ui.shell.ProblemStep
import sh.belfry.app.ui.shell.SignerProblem
import sh.belfry.core.net.RemoteAppPreview

/** SPEC 19.47: sign in to another app with the belfry as its remote signer (demo answers). */
class RemoteAppsShotsTest : DemoShots() {
    private val preview = RemoteAppPreview("Git Workshop", "https://gitworkshop.dev", "gitworkshop.dev", null, asksSign = true, asksDecrypt = true, known = false)

    /** What the app asks, then Connect: its name, "It says it is …", the private-messages switch. */
    @Test fun confirm() {
        demoShot("remote-app-confirm") { ConfirmStep(preview, null, onCancel = {}, onConnect = {}) }
        compose.onNodeWithTag("remote-app-title").assertExists()
        compose.onNodeWithTag("remote-app-decrypt").assertExists()
        compose.onNodeWithTag("remote-app-unverified").assertExists()
    }

    /** home/signer-errors-android: a fixed line in place of the confirm screen, one way on. */
    @Test fun problems() {
        demoShot("remote-app-problem-taken") { ProblemStep(SignerProblem.ANOTHER_ACCOUNT) {} }
        compose.onNodeWithTag("remote-app-problem-ANOTHER_ACCOUNT").assertExists()
    }

    @Test fun guestProblem() = demoShot("remote-app-problem-guest") { ProblemStep(SignerProblem.GUEST) {} }

    @Test fun done() {
        demoShot("remote-app-done") { DoneStep("gitworkshop", "Alex Morgan", onDone = {}, onApps = {}) }
        compose.onNodeWithTag("remote-app-done").assertExists()
    }

    /** No private messages asked: no switch. */
    @Test fun confirmSignOnly() {
        demoShot("remote-app-confirm-sign") { ConfirmStep(preview.copy(asksDecrypt = false, name = null), "Couldn't reach the app. Show a new code and try again.", onCancel = {}, onConnect = {}) }
        compose.onNodeWithTag("remote-app-decrypt").assertDoesNotExist()
        compose.onNodeWithTag("remote-app-error").assertExists()
    }

    @Test fun connectedApps() {
        runBlocking { session.remoteApps.connect("nostrconnect://" + "ab".repeat(32) + "?relay=wss%3A%2F%2Fr.example&secret=x", decrypt = true) }
        demoShot("remote-apps") { ConnectedAppsScreen() }
        assertEquals(1, compose.onAllNodesWithTag("remote-app-row").fetchSemanticsNodes().size)
    }

    @Test fun connectedAppsNone() {
        demoShot("remote-apps-none") { ConnectedAppsScreen() }
        compose.onNodeWithTag("remote-apps-none").assertExists()
    }
}
