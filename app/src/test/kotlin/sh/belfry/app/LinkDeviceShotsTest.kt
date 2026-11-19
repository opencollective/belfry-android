package sh.belfry.app

import androidx.compose.ui.test.onNodeWithTag
import org.junit.Test
import sh.belfry.app.ui.shell.LinkDeviceSheet

/** canvas home/link-device: the open account's login QR (demo mode never shows the row: it signs in nowhere). */
class LinkDeviceShotsTest : DemoShots() {
    @Test fun linkDevice() {
        demoScreenShot("link-device") { LinkDeviceSheet("https://chat.belfry.example/login/alex", "alex@belfry.example", onDismiss = {}) }
        compose.onNodeWithTag("link-device-qr").assertExists()
    }
}
