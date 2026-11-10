package sh.belfry.core.model

import sh.belfry.core.crypto.Bech32
import sh.belfry.core.nostr.Event
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.Target
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the shared vectors do not cover yet: edits, guest labels, the feed's layout, search, people… */
class ModelTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val groupsKey = "9".repeat(64)
    private var n = 0

    private fun ev(pk: String, at: Long, kind: Int, content: String = "", vararg tags: List<String>, id: String? = null): Event {
        n++
        return Event(id ?: n.toString(16).padStart(64, '0'), pk, at, kind, tags.toList(), content)
    }
    private fun t(vararg v: String) = v.toList()
    private fun world(events: List<Event>, me: String = alice, outbox: List<OutboxItem> = emptyList(), admins: Set<String> = emptySet()) =
        World(events, outbox, WorldContext(me = me, domain = "example.org", communityName = "Example", communityAdmins = admins, groupsKey = groupsKey, zone = ZoneId.of("UTC")))

    private val t0 = 1_793_000_000L // 2026-10-26 14:13:20 UTC

    @Test
    fun editsOnlyTheAuthorsLatestCounts() {
        val m = ev(alice, t0, 9, "frist #plans https://x.org/a.jpg", t("h", "general"), t("t", "plans"), t("t", "garden"), t("imeta", "url https://x.org/a.jpg", "m image/jpeg"))
        val e1 = ev(alice, t0 + 10, 1010, "first #plans\nhttps://x.org/a.jpg", t("h", "general"), t("e", m.id), t("k", "9"), t("imeta", "url https://x.org/a.jpg", "m image/jpeg"), t("t", "garden"), t("t", "plans"))
        val e2 = ev(alice, t0 + 20, 1010, "first, really", t("h", "general"), t("e", m.id), t("k", "9"))
        val forged = ev(bob, t0 + 30, 1010, "hacked", t("h", "general"), t("e", m.id), t("k", "9"))
        val w1 = world(listOf(m, e1))
        val got1 = w1.messages("general").single()
        assertEquals(m.id, got1.id)
        assertEquals("first #plans", got1.text)
        assertEquals(t0 + 10, got1.edited)
        assertEquals(listOf("garden", "plans"), got1.topics)
        assertEquals(1, got1.media.size)
        val got2 = world(listOf(m, e1, e2, forged)).messages("general").single()
        assertEquals("first, really", got2.text)
        assertEquals(t0 + 20, got2.edited)
        // an edit that was deleted by its author no longer counts
        val del = ev(alice, t0 + 40, 5, "", t("h", "general"), t("e", e2.id))
        assertEquals("first #plans", world(listOf(m, e1, e2, del)).messages("general").single().text)
        // the edit action keeps photos and the topic the message was sent in
        val d = Actions.editMessage("general", w1.messages("general").single(), "first #later")
        assertEquals(1010, d.kind)
        assertEquals("first #later\nhttps://x.org/a.jpg", d.content)
        assertEquals(listOf(t("h", "general"), t("e", m.id), t("k", "9"), t("imeta", "url https://x.org/a.jpg", "m image/jpeg"), t("t", "garden"), t("t", "later")), d.tags)
        assertTrue(w1.canEdit(got1))
        assertFalse(world(listOf(m), me = bob).canEdit(world(listOf(m), me = bob).messages("general").single()))
    }

    @Test
    fun editedThreadReply() {
        val m = ev(alice, t0, 9, "root", t("h", "general"))
        val r = ev(bob, t0 + 5, 1111, "repyl", t("h", "general"), t("E", m.id, "", alice), t("K", "9"))
        val e = ev(bob, t0 + 6, 1010, "reply", t("h", "general"), t("e", r.id), t("k", "1111"))
        val got = world(listOf(m, r, e)).threadReplies("general", m.id).single()
        assertEquals("reply", got.text)
        assertEquals(t0 + 6, got.edited)
        assertEquals(1, world(listOf(m, r, e)).messages("general").size)
    }

    @Test
    fun guestLabels() {
        val join = ev(groupsKey, t0, 9000, "", t("h", "general"), t("p", carol, "member"), t("name", "Carol"), t("invited-by", alice), t("unsaved", "1"), t("guest", "2026-12-01"))
        val w = world(listOf(join))
        val j = w.guestJoins("general").single()
        assertTrue(j.unsaved); assertFalse(j.member); assertEquals("guest · not saved", j.chip)
        assertEquals("Carol", w.displayName(carol))
        assertNotNull(w.noAdminReason(carol))
        val saved = ev(groupsKey, t0 + 5, 1985, "", t("h", "general"), t("L", "belfry/guest"), t("l", "saved", "belfry/guest"), t("p", carol))
        val w2 = world(listOf(join, saved))
        assertEquals("guest", w2.guestJoins("general").single().chip)
        assertNull(w2.noAdminReason(carol))
        val member = ev(groupsKey, t0 + 9, 1985, "", t("h", "general"), t("L", "belfry/guest"), t("l", "member", "belfry/guest"), t("p", carol))
        val w3 = world(listOf(join, member))
        val j3 = w3.guestJoins("general").single()
        assertTrue(j3.member); assertFalse(j3.unsaved); assertNull(j3.chip)
        assertTrue(w3.guestJoinsOf(carol).isEmpty())
        assertNull(w3.memberRows("general").first { it.pubkey == carol }.guest)
        // taken out again without ever writing: no join line
        val out = ev(groupsKey, t0 + 3, 9001, "", t("h", "general"), t("p", carol), t("guest", "ended"))
        assertTrue(world(listOf(join, out)).guestJoins("general").isEmpty())
        val agent = ev(groupsKey, t0, 9000, "", t("h", "general"), t("p", bob, "member"), t("invited-by", alice), t("agent", "1"))
        assertEquals("agent", world(listOf(agent)).guestJoins("general").single().chip)
        assertEquals("An agent cannot be an admin.", world(listOf(agent)).noAdminReason(bob))
        assertTrue(world(listOf(agent), me = bob).amGuest)
    }

    @Test
    fun feedRowsAndCards() {
        val create = ev(alice, t0 - 100, 9007, "", t("h", "general"))
        val m1 = ev(alice, t0, 9, "hello", t("h", "general"))
        val m2 = ev(alice, t0 + 60, 9, "again", t("h", "general"))
        val m3 = ev(alice, t0 + 600, 9, "later", t("h", "general"))
        val m4 = ev(bob, t0 + 610, 9, "hi", t("h", "general"))
        val pod = ev(bob, t0 + 620, 9, "⬡ New pod: Garden", t("h", "general"), t("pod", "pod-garden-0000"))
        val ann = ev(alice, t0 + 630, 9, "Big news", t("h", "general"), t("t", "announcement"))
        val notAnn = ev(bob, t0 + 640, 9, "Fake news", t("h", "general"), t("t", "announcement"))
        val nextDay = ev(bob, t0 + 86400, 9, "morning", t("h", "general"))
        val card = ev(groupsKey, t0 + 86500, 9, "**Hello**\n\nA question", t("h", "general"), t("client", "belfry-mail"), t("mail-from", "x@y.org"), t("mail-name", "X"), t("subject", "Hello"), t("belfry-auth", "pass"))
        val sent = ev(groupsKey, t0 + 86600, 9, "An answer", t("h", "general"), t("client", "belfry-mail-out"), t("e", card.id), t("mail-to", "x@y.org"), t("subject", "Re: Hello"), t("sent-by", alice))
        val mail = ev(bob, t0 + 86700, 9, "**Subj**\nBody", t("h", "general"), t("client", "belfry-mail"), t("subject", "Subj"))
        val drop = ev(carol, t0 + 86800, 9, "", t("h", "general"), t("client", "belfry-drop"), t("imeta", "url https://x.org/p.png", "m image/png"))
        val podGroup = ev(bob, t0 + 615, 9007, "", t("h", "pod-garden-0000"))
        val w = world(listOf(create, podGroup, m1, m2, m3, m4, pod, ann, notAnn, nextDay, card, sent, mail, drop))
        val rows = w.feedRows("general")
        fun row(e: Event) = rows.first { (it.item as FeedItem.Msg).m.id == e.id }
        assertTrue(row(m1).newDay && row(m1).first)
        assertFalse(row(m2).first)
        assertTrue(row(m3).first) // more than 300 s
        assertTrue(row(m4).first) // another author
        assertEquals("a pod", (row(pod).item as FeedItem.Msg).m.created)
        assertTrue(row(pod).first)
        assertTrue(row(ann).announcement)
        assertFalse(row(notAnn).announcement)
        assertTrue(row(nextDay).newDay)
        assertEquals(2, rows.count { it.newDay })
        val c = (row(card).item as FeedItem.Msg).m
        assertTrue(c.special is Special.InboxCard)
        assertEquals("A question", c.body)
        assertTrue((c.special as Special.InboxCard).verified)
        assertTrue((row(sent).item as FeedItem.Msg).m.special is Special.SentAsChannel)
        assertEquals(CardStatus.WAITING, w.inboxStates("general")[card.id]?.status)
        assertEquals(1, w.inboxStates("general")[card.id]?.answered)
        val mm = (row(mail).item as FeedItem.Msg).m
        assertEquals(Special.MemberMail("Subj", null), mm.special)
        assertEquals("Body", mm.body)
        assertEquals("Drop box", w.nameOver((row(drop).item as FeedItem.Msg).m))
        assertEquals(listOf(ann.id), w.feed("general", "announcement").map { (it as FeedItem.Msg).m.id })
        assertTrue(w.hasAnnouncements("general"))
        assertEquals("pod-garden-0000", w.pods("general").single().id)
        assertEquals(1, w.files("general").size)
    }

    @Test
    fun pendingItemsShowAsEvents() {
        val item = OutboxItem("local-1", Target.GROUPS, 9, "queued", listOf(t("h", "general")), t0 + 5, status = OutboxStatus.FAILED, error = "blocked: no", tries = 1)
        val w = world(listOf(ev(alice, t0, 9, "hello", t("h", "general"))), outbox = listOf(item))
        val last = w.messages("general").last()
        assertEquals("local-1", last.id)
        assertEquals(alice, last.pubkey)
        assertEquals(OutboxStatus.FAILED, w.pending(last.id)?.status)
        assertNull(w.pending(w.messages("general").first().id))
        assertFalse(w.canEdit(last))
    }

    @Test
    fun searchFoldsAccentsAndCase() {
        val p = ev(bob, t0 - 5, 0, "{\"name\":\"Zoë Müller\",\"about\":\"Hi\",\"nip05\":\"zoe@example.org\"}")
        val m1 = ev(bob, t0, 9, "Rendez-vous au CAFÉ", t("h", "general"))
        val m2 = ev(alice, t0 + 1, 9, "nothing here", t("h", "other"))
        val m3 = ev(alice, t0 + 2, 9, "see file", t("h", "other"), t("imeta", "url https://x.org/1.pdf", "m application/pdf", "alt Café-menu.pdf"))
        val w = world(listOf(p, m1, m2, m3))
        assertEquals(listOf(m3.id, m1.id), w.search("cafe").map { it.m.id })
        assertEquals(listOf(m1.id), w.search("cafe", "general").map { it.m.id })
        assertEquals(listOf(m1.id), w.search("ZOE  rendez").map { it.m.id })
        assertTrue(w.search("   ").isEmpty())
        assertEquals("Hi", w.aboutOf(bob))
        assertEquals("Zoë Müller", w.realName(bob))
        assertEquals("You", w.displayName(alice))
        assertEquals(Bech32.npub(carol).take(12) + "…", w.displayName(carol))
    }

    @Test
    fun peopleExtras() {
        val x1 = ev(bob, t0, 30078, "{\"tags\":[\"#Solar Power\",\"solar-power\",\"faci!litation\"],\"share\":\"city\",\"place\":{\"id\":\"gn:1\",\"name\":\"Brussels\",\"country\":\"BE\",\"lat\":50.8467,\"lon\":4.3525}}", t("d", "belfry:profile"))
        val x2 = ev(carol, t0, 30078, "{\"tags\":[\"solar-power\"],\"share\":\"country\",\"place\":{\"name\":\"Gent\",\"country\":\"BE\",\"lat\":51.0}}", t("d", "belfry:profile"))
        val x3 = ev(alice, t0, 30078, "{\"share\":\"none\",\"place\":{\"country\":\"BE\"}}", t("d", "belfry:profile"))
        val w = world(listOf(x1, x2, x3))
        assertEquals(listOf("solar-power", "facilitation"), w.extras[bob]?.tags)
        assertEquals(50.85, w.extras[bob]?.place?.lat)
        assertEquals(Place("BE"), w.extras[carol]?.place)
        assertNull(w.extras[alice]?.place)
        assertEquals(TagCount("solar-power", 2), w.allTags.first())
        val d = Actions.saveExtras(listOf("Solar Power"), Share.COUNTRY, Place("BE", name = "Gent"))
        assertEquals("{\"tags\":[\"solar-power\"],\"share\":\"country\",\"place\":{\"country\":\"BE\"}}", d.content)
        assertEquals(listOf(t("d", "belfry:profile")), d.tags)
        assertEquals(Target.MAIN, d.target)
    }

    @Test
    fun calendarProposalsDropLinksTopics() {
        val create = ev(alice, t0 - 100, 9007, "", t("h", "general"))
        val (coord, drafts) = Actions.createEvent(
            NewEvent("Work day", "Bring gloves", "The garden", start = t0 + 7200, end = t0 + 10800, online = "https://meet.jit.si/x", tzid = "Europe/Brussels", whenLabel = "soon"),
            "general", alice, "wss://groups", "wss://main", x = "abcd1234",
        )
        assertEquals("31923:$alice:session-abcd1234", coord)
        assertEquals(listOf(9007, 9002, 31923, 9), drafts.map { it.kind })
        assertEquals("📅 Work day · soon · The garden", drafts.last().content)
        val evs = drafts.mapIndexed { i, d -> ev(alice, t0 + i, d.kind, d.content, *d.tags.toTypedArray()) }
        val w0 = world(listOf(create) + evs)
        val e = w0.calendar.single()
        assertEquals("Work day", e.title); assertEquals("ws-abcd1234", e.groupId); assertEquals("general", e.channel)
        assertEquals(listOf("https://meet.jit.si/x"), e.links)
        assertEquals(t0 + 7200, e.start)
        assertEquals(e to 0, w0.nextEvent("general", t0))
        assertNull(w0.nextEvent("general", t0 + 3 * 86400))
        assertEquals(Special.EventCard(coord), w0.messages("general").single().special)
        assertEquals(ChannelType.EVENT, w0.group("ws-abcd1234")?.type)
        val r = Actions.rsvp(e, Rsvp.ACCEPTED, bob)
        val w1 = world(listOf(create) + evs + ev(bob, t0 + 50, r.kind, r.content, *r.tags.toTypedArray()), me = bob)
        assertEquals(Rsvp.ACCEPTED, w1.myRsvp(w1.calendar.single()))
        assertEquals(1, w1.calendar.single().going)
        assertEquals("Jitsi", Actions.meetingLabel("https://meet.jit.si/x"))
        assertEquals("Google Meet", Actions.meetingLabel("https://meet.google.com/abc"))

        val (gid, pd) = Actions.createProposal(NewProposal("Buy a shed", "For tools", "Text", ProposalStatus.VOTING), "general", alice, "wss://groups", "wss://main", t0, x = "00ff00")
        assertEquals("prop-buy-a-shed-00ff00", gid)
        assertEquals(listOf(9007, 9002, 30023, 9, 1068), pd.map { it.kind })
        val pevs = pd.mapIndexed { i, d -> ev(alice, t0 + 100 + i, d.kind, d.content, *d.tags.toTypedArray()) }
        val w2 = world(listOf(create) + pevs)
        val p = w2.proposalFor(gid)!!
        assertEquals(ProposalStatus.VOTING, p.status); assertTrue(p.votingNow); assertTrue(w2.canDecide(p))
        assertEquals("general", w2.group(gid)?.parent)
        val v = Actions.vote(p, "yes")!!
        val st = Actions.setProposalStatus(p, ProposalStatus.PASSED).single()
        val w3 = world(listOf(create) + pevs + ev(bob, t0 + 200, v.kind, "", *v.tags.toTypedArray()) + ev(alice, t0 + 300, st.kind, "", *st.tags.toTypedArray()) +
            ev(carol, t0 + 400, 1985, "", t("h", gid), t("L", "belfry/proposal-status"), t("l", "rejected", "belfry/proposal-status")))
        val p3 = w3.proposalFor(gid)!!
        assertEquals(mapOf("yes" to 1), p3.tally)
        assertEquals(ProposalStatus.PASSED, p3.status) // carol may not decide

        val (token, dl) = Actions.createDropLink("general", 1.0, true, t0, token = "tok")
        val dle = ev(alice, t0, dl.kind, "", *dl.tags.toTypedArray())
        val w4 = world(listOf(create, dle))
        val link = w4.dropLinks("general", t0 + 10).single()
        assertEquals("tok", token); assertTrue(link.login); assertEquals(t0 + 86400, link.expires)
        assertEquals("https://example.org/drop/tok", w4.dropUrl(link.token))
        assertTrue(w4.dropLinks("general", t0 + 86401).isEmpty())
        val end = Actions.endDropLink(link)
        assertTrue(world(listOf(create, dle, ev(alice, t0 + 5, 5, "", *end.tags.toTypedArray()))).dropLinks("general", t0 + 10).isEmpty())

        val pin = Actions.pinTopic("general", emptyList(), "#Garden", true)
        val w5 = world(listOf(create, ev(bob, t0, pin.kind, "", *pin.tags.toTypedArray())))
        assertEquals(listOf("garden"), w5.pinnedTopics("general"))
        assertEquals(listOf("plans", "garden"), w5.topicsBar("general", "plans"))

        val on = Actions.setInbox("general", true)
        assertTrue(world(listOf(create, ev(alice, t0, on.kind, on.content, *on.tags.toTypedArray()))).isInbox(world(listOf(create)).group("general")))
        assertFalse(world(listOf(create, ev(bob, t0, on.kind, on.content, *on.tags.toTypedArray()))).isInbox(world(listOf(create)).group("general")))
    }

    @Test
    fun mentionPicker() {
        val people = listOf(bob to "Bob Smith", carol to "Zoë")
        assertEquals(listOf(carol to "Zoë"), Mentions.candidates("zo", people, alice))
        assertEquals(listOf(bob to "Bob Smith"), Mentions.candidates("sm", people, alice))
        val (text, caret) = Mentions.pick("hi @bo there", 6, "bo", "Bob Smith")
        assertEquals("hi @Bob Smith  there", text)
        assertEquals(14, caret)
        val sent = Mentions.substitute("hi @Bob Smith", listOf("Bob Smith" to bob))
        assertEquals("hi nostr:" + Bech32.npub(bob), sent)
        val (back, picked) = Mentions.unsubstitute(sent) { if (it == bob) "Bob Smith" else "" }
        assertEquals("hi @Bob Smith", back)
        assertEquals(listOf("Bob Smith" to bob), picked)
        assertEquals("hi @…", Mentions.hide(sent))
        assertEquals("ete 2027", fold("Été 2027"))
    }
}
