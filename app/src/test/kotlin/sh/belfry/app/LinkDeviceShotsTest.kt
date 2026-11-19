package sh.belfry.app

import androidx.compose.ui.test.onNodeWithTag
import org.junit.Test
import sh.belfry.app.ui.shell.LinkDeviceBody

/** canvas home/link-device: the open account's login QR (demo mode never shows the row: it signs in nowhere). */
class LinkDeviceShotsTest : DemoShots() {
    @Test fun linkDevice() {
        demoShot("link-device") { sh.belfry.app.ui.info.InfoPage("Link a phone or tablet") { LinkDeviceBody("https://chat.belfry.example/login/alex", "alex@belfry.example") } }
        compose.onNodeWithTag("link-device-qr").assertExists()
    }
}
