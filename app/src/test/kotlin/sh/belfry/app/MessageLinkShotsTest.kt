package sh.belfry.app

import androidx.compose.ui.test.onNodeWithTag
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.core.engine.Demo

/** canvas channels/message-link (2026-11-19): a link to a message nobody can show here: the grey line. */
class MessageLinkShotsTest : DemoShots() {
    @Test fun unavailable() {
        demoShot("message-link-unavailable", act = {
            compose.mainClock.advanceTimeBy(3_500)
        }) { ChannelScreen(Demo.GARDEN, onMenu = {}, focusMessage = "ab".repeat(32)) }
        compose.onNodeWithTag("message-unavailable").assertExists()
    }
}
