package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import sh.belfry.app.ui.cards.EventScreen
import sh.belfry.app.ui.cards.PendingReply
import sh.belfry.app.ui.cards.ProposalScreen
import sh.belfry.app.ui.cards.ReplyQueue
import sh.belfry.app.ui.cards.UndoSendHost
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.Rsvp
import sh.belfry.core.model.Special

/** The cards' rules, checked by driving the screens (demo mode). */
class CardsBehaviourTest : DemoShots() {
    private val hello = Demo.HELLO

    @Before
    fun clean() = ReplyQueue.reset()

    @After
    fun cleanAfter() = ReplyQueue.reset()

    /** Taps a node if it is (still) there: a control may be gone once its work is done. */
    private fun tap(tag: String) {
        try {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag(tag).performClick()
        } catch (e: AssertionError) {
            // gone between the check and the tap
        }
    }

    private fun mine(kind: Int): Int = world.all.count { it.kind == kind && it.pubkey == session.me }

    @Test
    fun rsvpCannotDoubleFireAndShowsMyAnswer() {
        started()
        val coord = world.calendar.first { !it.isNew }.coord
        val before = mine(31925)
        compose.setContent { BelfryTheme { InDemo { EventScreen(coord) } } }
        tap("rsvp-accepted")
        tap("rsvp-accepted")
        tap("rsvp-accepted")
        compose.waitUntil(10_000) { world.calEvent(coord)?.let { world.myRsvp(it) } == Rsvp.ACCEPTED }
        settle()
        assertEquals("one tap, one answer", before + 1, mine(31925))
        // after an answer the event's line says so
        compose.waitUntil(5_000) { compose.onAllNodesWithText("You’re going", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun votingRecordsOneVote() {
        started()
        val p = world.proposals.first()
        assertNull("the demo starts without my vote", world.myVote(p))
        val before = mine(1018)
        compose.setContent { BelfryTheme { InDemo { ProposalScreen(p.groupId) } } }
        tap("vote-yes")
        tap("vote-yes")
        tap("vote-yes")
        compose.waitUntil(10_000) { world.proposalFor(p.groupId)?.let { world.myVote(it) } == "yes" }
        settle()
        assertEquals("one tap, one vote", before + 1, mine(1018))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Your vote").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun sentCount(): Int {
        runBlocking { session.settle() }
        return world.messages(hello).count { it.special is Special.SentAsChannel }
    }

    private fun pendingReply(): PendingReply {
        val m = world.messages(hello).first { it.special is Special.InboxCard }
        val card = m.special as Special.InboxCard
        return PendingReply(hello, card.from, "Noor", "Re: " + card.subject, "Thursday at ten works for us.", card.messageId, m.id)
    }

    private val seconds = Channel<Unit>(Channel.UNLIMITED)
    private fun pass(n: Int) = repeat(n) { seconds.trySend(Unit) }
    private fun shows(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun host() {
        compose.setContent { BelfryTheme { InDemo { Column { UndoSendHost(hello, second = { seconds.receive() }) } } } }
        compose.waitForIdle()
        assertEquals("the bar is on screen", 1, ReplyQueue.hosts)
    }

    @Test
    fun undoWithinTheWindowSendsNothing() {
        val before = sentCount()
        host()
        ReplyQueue.queue(session, pendingReply())
        shows("Sending to Noor in 8 s")
        pass(3)
        shows("Sending to Noor in 5 s")
        assertEquals("still counting down", before, sentCount())
        compose.onNodeWithTag("undo-send").performClick()
        compose.waitForIdle()
        pass(12)
        compose.waitForIdle()
        Thread.sleep(500)
        assertNull(ReplyQueue.pending)
        assertEquals("undone: nothing went out", before, sentCount())
        assertEquals("the text is kept for the sheet", "Thursday at ten works for us.", ReplyQueue.drafts.values.firstOrNull())
    }

    @Test
    fun lettingTheWindowElapseSendsOnce() {
        val before = sentCount()
        host()
        ReplyQueue.queue(session, pendingReply())
        shows("Sending to Noor in 8 s")
        pass(7)
        shows("Sending to Noor in 1 s")
        assertEquals("still counting down", before, sentCount())
        pass(1)
        var now = before
        for (i in 0 until 100) {
            compose.waitForIdle()
            now = sentCount()
            if (now > before || ReplyQueue.failed != null) break
            Thread.sleep(100)
        }
        assertNull("the send failed: " + ReplyQueue.failed?.second, ReplyQueue.failed)
        pass(20)
        compose.waitForIdle()
        Thread.sleep(300)
        assertEquals("sent once", before + 1, sentCount())
    }
}
