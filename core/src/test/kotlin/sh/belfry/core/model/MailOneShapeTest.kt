package sh.belfry.core.model

import sh.belfry.core.mail.Mime
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.withId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/one-shape.md 4 as this app reads it: who a mail was for, Reply all, internal drafts, Answered. */
class MailOneShapeTest {
    private val me = "a".repeat(64)
    private val other = "c".repeat(64)
    private val relay = "d".repeat(64)
    private val gk = "9".repeat(64)
    private val g = "hello"

    private fun ev(pk: String, at: Long, kind: Int, tags: List<List<String>>, content: String = ""): Event =
        Event("", pk, at, kind, tags, content).withId()

    private val base = listOf(
        ev(relay, 10, 39000, listOf(listOf("d", g), listOf("name", "hello"))),
        ev(relay, 10, 39001, listOf(listOf("d", g), listOf("p", me, "admin"))),
        ev(relay, 10, 39002, listOf(listOf("d", g), listOf("p", me), listOf("p", other))),
    )
    private val card = ev(gk, 1000, 9, listOf(
        listOf("h", g), listOf("subject", "Park cleaning"), listOf("mail-from", "Lou@Example.com"), listOf("mail-name", "Lou"),
        listOf("message-id", "<p1@example.com>"), listOf("client", "belfry-mail"), listOf("to", "hello@example.org", "hello"),
        listOf("cc", "dev@example.com", "Dev Patel"), listOf("cc", "park@example.org"), listOf("cc", "DEV@example.com", "again"),
    ), "**Park cleaning**\n\nRakes?")
    private val draft1 = ev(me, 1100, 1111, Actions.mailDraft(g, card, "Hi Lou").tags, "Hi Lou")
    private val draft2 = ev(me, 1200, 1111, Actions.mailDraft(g, card, "Second try").tags, "Second try")
    /** Not an admin: shown as a team comment (as older apps do). */
    private val notAdmin = ev(other, 1150, 1111, Actions.mailDraft(g, card, "My idea").tags, "My idea")
    private val sent = ev(gk, 1300, 9, listOf(
        listOf("h", g), listOf("subject", "Re: Park cleaning"), listOf("mail-to", "lou@example.com"), listOf("to", "lou@example.com", "Lou"),
        listOf("cc", "dev@example.com", "Dev Patel"), listOf("e", card.id, "", "reply"), listOf("client", "belfry-mail-out"), listOf("draft", draft1.id),
    ), "Hi Lou, yes")
    private val deleteDraft2 = ev(me, 1400, 5, listOf(listOf("h", g), listOf("e", draft2.id)))

    private fun world(events: List<Event>) = World(base + events, emptyList(), WorldContext(me = me, domain = "example.org", groupsKey = gk))

    @Test
    fun toAndCcAreRead() {
        val t = world(listOf(card)).mailThreads(g).list.single()
        val m = t.mails.single()
        assertEquals(listOf(MailPerson("hello@example.org", "hello")), m.to)
        assertEquals(listOf(MailPerson("dev@example.com", "Dev Patel"), MailPerson("park@example.org", "")), m.cc)
        assertEquals("park@example.org", m.cc[1].shown)
    }

    @Test
    fun replyAllGoesToTheSenderAndEveryoneElseButUs() {
        val t = world(listOf(card)).mailThreads(g).list.single()
        val last = t.lastIn!!
        val r = OneShape.replyAll(last.address, last.m.ev.tags, "example.org")
        assertEquals(listOf("lou@example.com", "dev@example.com", "park@example.org"), r.rcptTo)
        assertEquals("lou@example.com", r.to)
        assertEquals(listOf("dev@example.com", "park@example.org"), r.cc)
        // the header's Cc line on the mail that goes out
        val built = Mime.buildMail("Alex for hello", "hello@example.org", r.to!!, "Re: Park cleaning", "Yes", "example.org", "<p1@example.com>", null, 1_800_000_000, r.cc)
        assertTrue(built.raw.contains("\r\nTo: <lou@example.com>\r\n"), built.raw.take(400))
        assertTrue(built.raw.contains("\r\nCc: <dev@example.com>, <park@example.org>\r\n"), built.raw.take(400))
        assertTrue(built.raw.contains("In-Reply-To: <p1@example.com>"))
        assertFalse(Mime.buildMail("A", "a@example.org", "b@example.net", "S", "T", "example.org").raw.contains("Cc:"))
    }

    @Test
    fun draftsShowUntilSentOrDeleted() {
        val before = world(listOf(card, draft1, notAdmin, draft2))
        val t = before.mailThreads(g).list.single()
        assertEquals(listOf(draft1.id, draft2.id), t.drafts.map { it.id })
        // a draft is not a team comment; one by a non-admin is
        assertEquals(listOf(notAdmin.id), before.mailThreadReplies(g, t).map { it.id })
        assertEquals(1, t.replies)

        val after = world(listOf(card, draft1, notAdmin, draft2, sent, deleteDraft2))
        val t2 = after.mailThreads(g).list.single()
        assertTrue(t2.drafts.isEmpty(), t2.drafts.map { it.text }.toString())
        assertEquals(draft1.id, t2.mails.last().draftId)
        assertEquals(listOf(MailPerson("lou@example.com", "Lou")), t2.mails.last().to)
        assertEquals(listOf(notAdmin.id), after.mailThreadReplies(g, t2).map { it.id })
        // the pure rule the vectors share
        assertEquals(listOf("x"), OneShape.visibleDrafts(listOf("x" to me, "y" to me, "z" to me), setOf("y"), listOf(me to "z")))
        assertTrue(OneShape.isMailDraft(draft1))
        assertFalse(OneShape.isMailDraft(notAdmin.copy(tags = notAdmin.tags.filter { it[0] != OneShape.MAIL_DRAFT })))
    }

    @Test
    fun answeredLabelThenANewerMailReopens() {
        val answered = ev(me, 1500, 1985, Actions.markAnswered(g, card.id).tags)
        val w = world(listOf(card, answered))
        val t = w.mailThreads(g).list.single()
        assertEquals(CardStatus.WAITING, t.state.status)
        assertEquals(listOf(answered.id), t.labels.map { it.id })
        assertEquals(CardStatus.WAITING, w.inboxStates(g)[card.id]?.status)
        val again = ev(gk, 1600, 9, listOf(listOf("h", g), listOf("subject", "Re: Park cleaning"), listOf("mail-from", "lou@example.com"), listOf("mail-name", "Lou"),
            listOf("message-id", "<p2@example.com>"), listOf("in-reply-to", "<p1@example.com>"), listOf("client", "belfry-mail")), "Thanks!")
        val t2 = world(listOf(card, answered, again)).mailThreads(g).list.single()
        assertEquals(2, t2.mails.size)
        assertEquals(CardStatus.OPEN, t2.state.status)
        assertEquals("open", OneShape.threadState(listOf(1000, 1600), listOf(OneShape.StatusLabel(answered.id, 1500, "answered"))))
    }

    @Test
    fun recipientNames() {
        assertEquals("Lou", MailInbox.names(listOf("Lou")))
        assertEquals("Lou and Dev Patel", MailInbox.names(listOf("Lou", "Dev Patel")))
        assertEquals("Lou, Dev Patel and 1 more", MailInbox.names(listOf("Lou", "Dev Patel", "park@example.org")))
        assertEquals("Lou and 2 more", MailInbox.names(listOf("Lou", "Dev Patel", "park@example.org"), 1))
    }
}
