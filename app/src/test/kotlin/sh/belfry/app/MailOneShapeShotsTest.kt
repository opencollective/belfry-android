package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.belfry.app.ui.cards.MailComposeSheet
import sh.belfry.app.ui.cards.MailLevel
import sh.belfry.app.ui.cards.MailThreadScreen
import sh.belfry.app.ui.cards.PendingReply
import sh.belfry.app.ui.cards.ReplyQueue
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.CardStatus
import sh.belfry.core.model.InboxThread
import sh.belfry.core.model.OneShape
import sh.belfry.core.model.World

/** One shape, the e-mail thread (docs/one-shape.md 4; canvas one-shape/email-*), in demo mode. */
class MailOneShapeShotsTest : DemoShots() {
    private val hello = Demo.HELLO

    @Before
    fun clean() = ReplyQueue.reset()

    @After
    fun cleanAfter() = ReplyQueue.reset()

    private fun lou(w: World = world): InboxThread = w.mailThreads(hello).list.first { it.subject == "Park cleaning on Saturday" }

    /** email-thread-folded: the header only; the e-mail is a preview line with Cc and the reply-all icon. */
    @Test fun folded() {
        val t = lou()
        assertEquals(1, t.drafts.size)
        demoShot("mail-folded") { MailThreadScreen(hello, t, startLevel = MailLevel.TINY) }
        compose.onNodeWithTag("mail-header").assertExists()
        compose.onNodeWithText("Comment for the team").assertExists()
        assertTrue(compose.onAllNodesWithText("Cc Dev Patel, park@example.org").fetchSemanticsNodes().isNotEmpty())
    }

    /** email-thread (mid, how an open thread opens): the e-mails on top, the conversation below. */
    @Test fun pulled() {
        val t = lou()
        assertEquals(CardStatus.OPEN, t.state.status)
        demoShot("mail-pulled") { MailThreadScreen(hello, t) }
        compose.onNodeWithTag("mail-in").assertExists()
        compose.onNodeWithTag("mail-cc").assertExists()
    }

    /** email-thread-open: the whole thread, From / To / Cc folded on one line, the rows, Take it, Mark answered, Close. */
    @Test fun open() {
        demoShot("mail-open") { MailThreadScreen(hello, lou(), startLevel = MailLevel.FULL) }
        compose.onNodeWithTag("mail-fields").assertExists()
        compose.onNodeWithTag("mail-claim").assertExists()
    }

    /** email-compose: Reply all, From fixed, To the sender, Cc the others (never our own address). */
    @Test fun composeSheet() {
        val t = lou()
        val r = OneShape.replyAll(t.lastIn!!.address, t.lastIn!!.m.ev.tags, world.ctx.domain)
        assertEquals(listOf("lou@example.com", "dev@example.com", "park@example.org"), r.rcptTo)
        demoScreenShot("mail-compose") {
            MailThreadScreen(hello, t, startLevel = MailLevel.TINY)
            MailComposeSheet(hello, t, null) {}
        }
        compose.onNodeWithTag("compose-cc").assertExists()
    }

    /** email-draft: the dashed "Internal draft" card in the feed; tapping it continues in the sheet. */
    @Test fun draftCard() {
        val t = lou()
        demoShot("mail-draft") { MailThreadScreen(hello, t, startLevel = MailLevel.TINY) }
        compose.onNodeWithTag("mail-draft").assertExists()
        compose.onNodeWithText("Internal draft").assertExists()
    }

    @Test fun draftContinued() {
        val t = lou()
        demoScreenShot("mail-draft-continue") {
            MailThreadScreen(hello, t, startLevel = MailLevel.TINY)
            MailComposeSheet(hello, t, t.drafts.first()) {}
        }
        compose.onNodeWithTag("compose-delete").assertExists()
    }

    /** email-sent: the blue sent card, "Marked answered" once delivered, the header says Answered, and the Undo bar. */
    @Test fun sentWithUndo() {
        started()
        val t = lou()
        val last = t.lastIn!!
        val r = OneShape.replyAll(last.address, last.m.ev.tags, world.ctx.domain)
        runBlocking {
            session.sendAsChannel(hello, r.to!!, "Re: Park cleaning on Saturday", "Hi Lou, yes! We can lend you 12 rakes and a box of gloves. Pick them up Friday after 6 PM at the hall.",
                last.messageId, last.m.id, t.refs, r.cc, t.drafts.first().id, t.id)
        }
        var after = lou()
        repeat(200) {
            settle()
            after = lou()
            if (after.state.status == CardStatus.WAITING && after.labels.any { l -> l.tags.any { it.size >= 2 && it[1] == "answered" } }) return@repeat
            Thread.sleep(25)
        }
        assertEquals("answered after the demo's delivery", CardStatus.WAITING, after.state.status)
        assertTrue("the sent draft is hidden", after.drafts.isEmpty())
        ReplyQueue.pending = PendingReply(hello, "lou@example.com", "Lou", "Re: Park cleaning on Saturday", "Thanks again!", last.messageId, last.m.id, t.refs,
            cc = r.cc, threadId = t.id, names = listOf("Lou", "Dev Patel", "park@example.org"))
        demoShot("mail-sent") { MailThreadScreen(hello, after, startLevel = MailLevel.TINY) }
        assertTrue(compose.onAllNodesWithText("Sent by e-mail to Lou, Dev Patel and 1 more").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Sending to Lou and 2 more…").fetchSemanticsNodes().isNotEmpty())
        // nothing left to send when the screen goes (its dispose would fire the reply)
        ReplyQueue.reset()
    }
}
