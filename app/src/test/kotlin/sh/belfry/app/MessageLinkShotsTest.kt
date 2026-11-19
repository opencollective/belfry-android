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

/** canvas channels/message-link: a link to a thread reply opens its thread, the reply centred and flashed. */
class MessageLinkThreadShotsTest : DemoShots() {
    @Test fun threadReply() {
        val w = session.world.value
        val (groupId, root) = w.groups.firstNotNullOf { g -> w.threadsOf(g.id).entries.firstOrNull { (r, _) -> w.threadReplies(g.id, r).size >= 2 }?.let { g.id to it.key } }
        val reply = w.threadReplies(groupId, root)[1]
        demoShot("message-link-thread") { sh.belfry.app.ui.chat.ThreadScreen(groupId, root, focus = reply.id) }
    }
}
