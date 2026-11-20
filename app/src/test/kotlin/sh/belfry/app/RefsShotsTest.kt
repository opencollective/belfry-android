package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.kit.MarkdownWithBox
import sh.belfry.core.engine.Demo

/** SPEC 19.44 (canvas channels/link-preview-end + -inline): names inside the text, a box at the end. */
class RefsShotsTest : DemoShots() {
    @Test fun inTheChannel() {
        demoShot("refs-channel") { ChannelScreen(Demo.RANDOM, onMenu = {}) }
        assertTrue(compose.onAllNodesWithTag("ref-box").fetchSemanticsNodes().isNotEmpty())
    }

    /** One message per state: a name inside, the box, only a reference, an unknown one (bar, then "#channel"). */
    @Test fun states() {
        val d = Demo.DOMAIN
        demoShot("refs-states") {
            Column(Modifier.padding(16.dp)) {
                MarkdownWithBox("Ask https://chat.$d/#/c/${Demo.POD_OPEN} about the seeds.", d)
                MarkdownWithBox("Seed talk carries on here https://chat.$d/#/c/${Demo.GARDEN}", d, Modifier.padding(top = 16.dp))
                MarkdownWithBox("https://chat.$d/#/c/${Demo.KITCHEN}", d, Modifier.padding(top = 16.dp))
                MarkdownWithBox("Somewhere new https://chat.$d/#/c/not-here-yet", d, Modifier.padding(top = 16.dp))
            }
        }
        compose.onNodeWithText("Seed talk carries on here").assertExists()
        assertTrue(compose.onAllNodesWithTag("ref-box").fetchSemanticsNodes().size >= 3)
    }

    /** The kinds added on 2026-11-20 (the web's chat-source vectors): a to-do, a message, a person, a drop box; inside the text and as the box. */
    @Test fun moreKinds() {
        val d = Demo.DOMAIN
        val w = world
        val tg = listOf(Demo.GARDEN, Demo.KITCHEN, Demo.GENERAL, Demo.TOOLS, Demo.RANDOM).first { w.tasks(it).isNotEmpty() }
        val task = w.tasks(tg).first()
        val msg = w.messages(Demo.GARDEN).last { it.pubkey != w.me && it.text.length > 20 }
        demoShot("refs-more") {
            Column(Modifier.padding(16.dp)) {
                MarkdownWithBox("Who can take this? https://chat.$d/#/c/$tg/todo/${task.id}", d)
                MarkdownWithBox("See what was said https://chat.$d/#/c/${Demo.GARDEN}/m/${msg.ev.id}", d, Modifier.padding(top = 16.dp))
                MarkdownWithBox("https://chat.$d/#/u/${msg.pubkey} knows, ask https://chat.$d/#/u/${msg.pubkey}", d, Modifier.padding(top = 16.dp))
                MarkdownWithBox("Done with https://chat.$d/#/c/$tg/todo/${task.id}, next one", d, Modifier.padding(top = 16.dp))
                MarkdownWithBox("Send your photos here https://$d/drop/Zx81kQ_p-3aB9cD0", d, Modifier.padding(top = 16.dp))
            }
        }
        compose.onNodeWithText("Who can take this?").assertExists()
        assertTrue(compose.onAllNodesWithTag("ref-box").fetchSemanticsNodes().size >= 4)
    }
}
