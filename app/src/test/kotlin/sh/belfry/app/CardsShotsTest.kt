package sh.belfry.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import sh.belfry.app.ui.cards.ApplySheet
import sh.belfry.app.ui.cards.CreateChannelScreen
import sh.belfry.app.ui.cards.CreateEventScreen
import sh.belfry.app.ui.cards.CreatePodScreen
import sh.belfry.app.ui.cards.CreateProposalScreen
import sh.belfry.app.ui.cards.EventCard
import sh.belfry.app.ui.cards.EventScreen
import sh.belfry.app.ui.cards.EventsScreen
import sh.belfry.app.ui.cards.InboxActionsSheet
import sh.belfry.app.ui.cards.JoinBar
import sh.belfry.app.ui.cards.PendingReply
import sh.belfry.app.ui.cards.PodCard
import sh.belfry.app.ui.cards.PodScreen
import sh.belfry.app.ui.cards.ProposalCard
import sh.belfry.app.ui.cards.ProposalScreen
import sh.belfry.app.ui.cards.ReplyQueue
import sh.belfry.app.ui.cards.ReplySheet
import sh.belfry.app.ui.cards.ReviseSheet
import sh.belfry.app.ui.cards.SpecialCard
import sh.belfry.app.ui.cards.StatusSheet
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.CardState
import sh.belfry.core.model.Message
import sh.belfry.core.model.Rsvp
import sh.belfry.core.model.Special

/** The cards and their screens, in demo mode. */
class CardsShotsTest : DemoShots() {
    private val general = Demo.GENERAL
    private val hello = Demo.HELLO

    @Before
    fun clean() = ReplyQueue.reset()

    private val eventCoord: String get() = world.calendar.first().coord
    private val proposalGroup: String get() = world.proposals.first().groupId
    private fun card(channel: String, pick: (Message) -> Boolean): Message = world.messages(channel).first(pick)
    private val inboxCard: Message get() = card(hello) { it.special is Special.InboxCard }

    @Test
    fun cardsAlone() = demoShot("cards-alone") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EventCard(eventCoord, "")
            PodCard(Demo.POD_OPEN, "")
            PodCard(Demo.POD_CLOSED, "")
            ProposalCard(proposalGroup, "")
            SpecialCard(general, card(general) { it.special is Special.MemberMail })
        }
    }

    @Test
    fun mailCardsAlone() = demoShot("cards-mail-alone") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (m in world.messages(hello).filter { it.special != Special.None }.take(3)) SpecialCard(hello, m)
        }
    }

    @Test
    fun cardsInTheFeed() {
        val m = card(general) { it.special is Special.EventCard }
        demoShot("cards-feed") { ChannelScreen(general, focusMessage = m.id) }
    }

    @Test
    fun inboxFeed() = demoShot("cards-inbox-feed") { ChannelScreen(hello) }

    @Test
    fun joinBars() {
        val open = world.group(Demo.POD_OPEN)!!.let { it.copy(members = it.members - world.me) }
        val closed = world.group(Demo.POD_CLOSED)!!.let { it.copy(members = it.members - world.me) }
        val applied = closed.copy(applications = closed.applications.map { it.copy(pubkey = world.me) })
        demoShot("cards-join-bars") {
            Column {
                JoinBar(open)
                JoinBar(closed)
                JoinBar(applied)
            }
        }
    }

    @Test
    fun applySheet() {
        val closed = world.group(Demo.POD_CLOSED)!!
        demoScreenShot("cards-apply-sheet") {
            PodScreen(Demo.POD_CLOSED)
            ApplySheet(closed) {}
        }
    }

    @Test
    fun eventsList() = demoShot("events-list") { EventsScreen(null) }

    @Test
    fun eventBeforeAnswer() = demoShot("event-page") { EventScreen(eventCoord) }

    @Test
    fun eventAfterAnswer() {
        runBlocking { session.rsvp(world.calEvent(eventCoord)!!, Rsvp.ACCEPTED) }
        settle()
        demoShot("event-page-answered") { EventScreen(eventCoord) }
    }

    @Test
    fun podAsMember() = demoShot("pod-page") { PodScreen(Demo.POD_OPEN) }

    @Test
    fun podAsAdminWithRequest() = demoShot("pod-page-admin") { PodScreen(Demo.POD_CLOSED) }

    @Test
    fun proposalVoting() = demoShot("proposal-page") { ProposalScreen(proposalGroup) }

    @Test
    fun proposalStatusSheet() {
        val p = world.proposals.first()
        demoScreenShot("proposal-status-sheet") {
            ProposalScreen(proposalGroup)
            StatusSheet(p) {}
        }
    }

    @Test
    fun proposalReviseSheet() {
        val p = world.proposals.first()
        demoScreenShot("proposal-revise-sheet") {
            ProposalScreen(proposalGroup)
            ReviseSheet(p) {}
        }
    }

    @Test
    fun replyByEmailSheet() {
        val m = inboxCard
        ReplyQueue.drafts[m.id] = "Dear Noor,\n\nThursday at ten works for us. Come to the main gate, Maya will be there."
        demoScreenShot("inbox-reply-sheet") {
            ChannelScreen(hello)
            ReplySheet(hello, m, m.special as Special.InboxCard) {}
        }
    }

    @Test
    fun undoSendBar() {
        val m = inboxCard
        val card = m.special as Special.InboxCard
        ReplyQueue.hosts = 1
        ReplyQueue.pending = PendingReply(hello, card.from, card.name.split(' ').first(), "Re: " + card.subject, "Thursday at ten works for us.", card.messageId, m.id)
        demoShot("inbox-undo-send") { ChannelScreen(hello) }
        ReplyQueue.reset()
        ReplyQueue.hosts = 0
    }

    @Test
    fun inboxActionsSheet() {
        val m = inboxCard
        val card = m.special as Special.InboxCard
        val state = world.inboxStates(hello)[m.id] ?: CardState()
        demoScreenShot("inbox-actions-sheet") {
            ChannelScreen(hello)
            InboxActionsSheet(hello, m, card, state, card.name.split(' ').first(), {}, {}, {})
        }
    }

    @Test
    fun createChannel() = demoShot("create-channel") { CreateChannelScreen() }

    @Test
    fun createPod() = demoShot("create-pod") { CreatePodScreen(general) }

    @Test
    fun createEvent() = demoShot("create-event") { CreateEventScreen(general) }

    @Test
    fun createEventWithErrors() = demoShot("create-event-errors") { CreateEventScreen(general, showErrors = true) }

    @Test
    fun createEventDatePicker() = demoScreenShot("create-event-date") { CreateEventScreen(general, openPicker = "start-date") }

    @Test
    fun createProposal() = demoShot("create-proposal") { CreateProposalScreen(general) }
}
