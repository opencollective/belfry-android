package sh.belfry.app

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.TypingRow
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.app.ui.people.PrivacyScreen
import sh.belfry.app.ui.people.TypingSetting
import sh.belfry.core.engine.Demo
import sh.belfry.core.typing.TypingPlace

/** canvas v110 messages/typing-*: "… is typing" (docs/typing.md). Dots still: the system's animations are off here. */
class TypingShotsTest : DemoShots() {
    private val conv get() = "n:" + Demo.lena.pubkey

    @Before fun stillDots() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.Global.putFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    /** messages/typing-texts: one, two, several, somebody. */
    @Test fun texts() {
        shot("typing-texts") {
            Column(Modifier.fillMaxSize()) {
                TypingRow(listOf("Lina Moreau")); Spacer(Modifier.height(12.dp))
                TypingRow(listOf("Lina Moreau", "Ben Okafor")); Spacer(Modifier.height(12.dp))
                TypingRow(listOf("Lina Moreau", "Ben Okafor", "Ana Silva")); Spacer(Modifier.height(12.dp))
                TypingRow(listOf("Somebody"))
            }
        }
        compose.onNodeWithText("Lina Moreau and Ben Okafor are typing…").assertExists()
        compose.onNodeWithText("Several people are typing…").assertExists()
        compose.onNodeWithText("Somebody is typing…").assertExists()
    }

    /** messages/typing-channel: over the feed's bottom, above the composer. */
    @Test fun channel() {
        session.showTyping(TypingPlace.channel(Demo.GENERAL), Demo.lena.pubkey)
        demoShot("typing-channel") { ChannelScreen(Demo.GENERAL, onMenu = {}) }
        compose.onNodeWithTag("typing-line").assertExists()
        compose.onNodeWithText("Lena Fischer is typing…", substring = true).assertExists()
    }

    /** messages/typing-dm. */
    @Test fun dm() {
        session.showTyping(TypingPlace.dm(conv), Demo.lena.pubkey)
        demoShot("typing-dm") { ConversationScreen(conv) }
        compose.onNodeWithText("is typing…", substring = true).assertExists()
    }

    /** messages/typing-list: a one-to-one row says "typing…" in place of its preview. */
    @Test fun list() {
        session.showTyping(TypingPlace.dm(conv), Demo.lena.pubkey)
        demoShot("typing-list") { MessagesScreen() }
        compose.onNodeWithTag("dm-row-typing", useUnmergedTree = true).assertExists()
    }

    /** Someone typing in another place shows nothing here. */
    @Test fun otherPlacesStayQuiet() {
        session.showTyping(TypingPlace.thread(Demo.GENERAL, "someroot"), Demo.lena.pubkey)
        demoShot("typing-channel-none") { ChannelScreen(Demo.GENERAL, onMenu = {}) }
        compose.onNodeWithTag("typing-line").assertDoesNotExist()
    }

    /** messages/typing-setting: Me → Privacy, on by default, per device. */
    @Test fun setting() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        TypingSetting.set(c, true)
        demoShot("typing-setting") { PrivacyScreen() }
        compose.onNodeWithText(TypingSetting.FOOT).assertExists()
        compose.onNodeWithTag("privacy-typing").performClick()
        compose.waitForIdle()
        assertFalse(TypingSetting.on(c))
        assertFalse(session.sendTyping)
        TypingSetting.set(c, true)
    }
}
