package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who really made it (SPEC 19.75; docs/cards.md §2.2; web model.ts `authorOf`, chat 802be45), and
 * the town square's topic post (SPEC 19.76; web `newTopicOf` / TopicCard, chat f10e6b2).
 */
class CardAuthorsTest {
    private val gk = "9".repeat(64)
    private val ana = "a".repeat(64)
    private val lou = "b".repeat(64)
    private val dev = "c".repeat(64)
    private val mallory = "d".repeat(64)
    private val guest = "e".repeat(64)
    private var n = 0
    private val t0 = 1_795_000_000L

    private fun ev(pk: String, at: Long, kind: Int, vararg tags: List<String>, content: String = ""): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags.toList(), content)
    }
    private fun t(vararg v: String) = v.toList()
    private fun name(pk: String, s: String) = ev(pk, t0, 0, content = "{\"name\":\"$s\"}")

    // ---- the pure rule ----

    @Test fun byCountsOnlyOnTheGroupsKeysEvents() {
        val made = ev(gk, t0, 9007, t("h", "pod-x"), t("open"), t("public"), t("by", lou))
        assertEquals(lou, CardAuthors.by(made, gk))
        assertEquals(lou, CardAuthors.authorOf(made, gk, emptyMap()))
        // anyone else's event: its `by` says nothing, it is their own
        val forged = ev(mallory, t0, 9007, t("h", "pod-y"), t("by", lou))
        assertNull(CardAuthors.by(forged, gk))
        assertEquals(mallory, CardAuthors.authorOf(forged, gk, mapOf("pod-y" to lou)))
        val forgedPost = ev(mallory, t0, 9, t("h", "general"), t("pod", "pod-y"), t("by", lou), content = "⬡ New group: Mine")
        assertEquals(mallory, CardAuthors.authorOf(forgedPost, gk, mapOf("pod-y" to lou)))
        // no groups key known here (an old belfry, offline at first start): nothing is trusted
        assertNull(CardAuthors.by(made, null))
        assertNull(CardAuthors.by(made, ""))
        assertEquals(gk, CardAuthors.authorOf(made, null, mapOf("pod-x" to lou)))
        // an event of the groups key without `by` that is not a claim's: the groups key's own
        val mail = ev(gk, t0, 9, t("h", "pod-x"), t("mail-from", "x@example.org"), content = "hello")
        assertEquals(gk, CardAuthors.authorOf(mail, gk, mapOf("pod-x" to lou)))
    }

    @Test fun malformedAndSeveralBy() {
        // not a key: upper case, too short, empty, missing
        for (bad in listOf(lou.uppercase(), "abc", "", "npub1" + "q".repeat(59), lou + "0")) {
            val e = ev(gk, t0, 9007, t("h", "pod-x"), t("by", bad))
            assertNull(CardAuthors.by(e, gk), bad)
            assertEquals(gk, CardAuthors.authorOf(e, gk, emptyMap()), bad)
        }
        assertNull(CardAuthors.by(ev(gk, t0, 9007, t("h", "pod-x"), t("by")), gk))
        // several: the first that is a key (as the web's find), a malformed one before it is skipped
        assertEquals(lou, CardAuthors.by(ev(gk, t0, 9007, t("h", "pod-x"), t("by", lou), t("by", dev)), gk))
        assertEquals(dev, CardAuthors.by(ev(gk, t0, 9007, t("h", "pod-x"), t("by", "nope"), t("by", dev), t("by", lou)), gk))
        // a malformed `by` falls back to the card line's claimer for a claim's own events
        assertEquals(lou, CardAuthors.authorOf(ev(gk, t0, 9007, t("h", "pod-x"), t("by", "nope")), gk, mapOf("pod-x" to lou)))
    }

    @Test fun olderGroupsFallBackToTheCardLine() {
        val line = ev(gk, t0 + 4, 9, t("h", "pod-old"), t("p", lou), t("card", "1"), content = "Lou started this group from a printed card")
        val later = ev(gk, t0 + 90, 9, t("h", "pod-old"), t("p", dev), t("card", "1"))
        val forged = ev(mallory, t0 + 1, 9, t("h", "pod-old"), t("p", mallory), t("card", "1"))
        val noKey = ev(gk, t0 + 2, 9, t("h", "pod-other"), t("p", "someone"), t("card", "1"))
        val notOne = ev(gk, t0 + 2, 9, t("h", "pod-third"), t("p", dev), t("card", "2"))
        val claimers = CardAuthors.claimers(listOf(later, forged, line, noKey, notOne), gk)
        assertEquals(mapOf("pod-old" to lou), claimers) // the oldest line by the groups key, only with a key
        assertTrue(CardAuthors.claimers(listOf(line), null).isEmpty())
        assertTrue(CardAuthors.isCardLine(line, gk))
        assertFalse(CardAuthors.isCardLine(forged, gk))
        assertFalse(CardAuthors.isCardLine(notOne, gk))
        // the claim's own events, without `by`
        assertEquals(lou, CardAuthors.authorOf(ev(gk, t0, 9007, t("h", "pod-old")), gk, claimers))
        assertEquals(lou, CardAuthors.authorOf(ev(gk, t0, 9002, t("h", "pod-old"), t("name", "Old")), gk, claimers))
        assertEquals(lou, CardAuthors.authorOf(ev(gk, t0, 9, t("h", "general"), t("pod", "pod-old"), content = "⬡ New group: Old"), gk, claimers))
        assertEquals(lou, CardAuthors.authorOf(line, gk, claimers))
        // `by` wins over the line
        assertEquals(dev, CardAuthors.authorOf(ev(gk, t0, 9007, t("h", "pod-old"), t("by", dev)), gk, claimers))
        // a group with no card line: the groups key's
        assertEquals(gk, CardAuthors.authorOf(ev(gk, t0, 9007, t("h", "pod-none")), gk, claimers))
        // a 9001 of the groups key in that group is not the claimer's doing
        assertEquals(gk, CardAuthors.authorOf(ev(gk, t0, 9001, t("h", "pod-old"), t("p", dev)), gk, claimers))
    }

    @Test fun theTopicPostAndItsWords() {
        val post = ev(gk, t0, 9, t("h", "general"), t("t", "new-topic"), t("channel", "bread"), t("by", dev),
            content = "# New topic: #bread · Sourdough starters · Swap and feed\nhttps://chat.example.org/#/c/bread")
        assertEquals("bread", CardAuthors.newTopicOf(post))
        assertNull(CardAuthors.newTopicOf(ev(gk, t0, 9, t("h", "general"), t("t", "new-topic"))))
        assertNull(CardAuthors.newTopicOf(ev(gk, t0, 9, t("h", "general"), t("channel", "bread"))))
        assertNull(CardAuthors.newTopicOf(ev(gk, t0, 1111, t("h", "general"), t("t", "new-topic"), t("channel", "bread"))))
        assertEquals("Sourdough starters · Swap and feed", CardAuthors.aboutInPost(post.content))
        assertEquals("Sourdough starters", CardAuthors.aboutInPost("# New topic: #bread · Sourdough starters"))
        assertEquals("" to "", CardAuthors.topicParts(""))
        assertEquals("Sourdough starters" to "", CardAuthors.topicParts("Sourdough starters"))
        assertEquals("Sourdough starters" to "Swap and feed · every week", CardAuthors.topicParts("Sourdough starters · Swap and feed · every week"))
        assertEquals("1 following", CardAuthors.following(1))
        assertEquals("Lou created the group from a printed card", CardAuthors.startedLine("Lou", true))
        assertEquals("Dev created the topic from card X2M7KE", CardAuthors.startedLine("Dev", false, "X2M7KE"))
        assertEquals("This topic was created from a printed card", CardAuthors.startedLine(null, false))
        assertEquals("Steward: Lou · 1 person", CardAuthors.stewardLine("Lou", pod = true, count = 1))
        assertEquals("Steward: Dev · 3 following", CardAuthors.stewardLine("Dev", pod = false, count = 3))
        assertEquals("Steward: Dev", CardAuthors.stewardLine("Dev", pod = false, count = null))
    }

    // ---- in the world: events shaped as the belfry writes them (docs/cards.md §2.2) ----

    private fun claim(group: String, who: String, whoName: String, at: Long, topic: Boolean, title: String, about: String, by: Boolean = true, guestPass: Boolean = false): List<Event> {
        val b = if (by) listOf(t("by", who)) else emptyList()
        val access = if (topic) emptyList() else listOf(t("open"), t("public"))
        val nm = if (topic) group else title
        val desc = if (!topic) about else "$title · $about"
        return listOf(
            ev(gk, at, 9007, t("h", group), *access.toTypedArray(), *b.toTypedArray()),
            ev(gk, at + 1, 9002, t("h", group), t("name", nm), t("about", desc), *access.toTypedArray(), *b.toTypedArray()),
            ev(gk, at + 2, 9000, *listOfNotNull(t("h", group), t("p", who, "admin"), t("role", "admin"), t("by", who), t("name", whoName), if (guestPass) t("guest", "2099-12-31") else null).toTypedArray()),
            if (topic) ev(gk, at + 3, 9, t("h", "general"), t("t", "new-topic"), t("channel", group), *b.toTypedArray(), content = "# New topic: #$group · $title · $about\nhttps://chat.example.org/#/c/$group")
            else ev(gk, at + 3, 9, t("h", "general"), t("pod", group), *b.toTypedArray(), content = "⬡ New group: $title · $about"),
            ev(gk, at + 4, 9, t("h", group), t("p", who), t("card", "1"), *b.toTypedArray(), content = "$whoName started this ${if (topic) "topic" else "group"} from a printed card"),
        )
    }

    private val base = listOf(
        ev(ana, t0, 9007, t("h", "general")), ev(ana, t0, 9002, t("h", "general"), t("name", "general")),
        name(ana, "Ana Silva"), name(lou, "Lou Martin"), name(dev, "Dev Patel"),
        ev(ana, t0 + 1, 9, t("h", "general"), content = "hi"),
    )
    private fun world(events: List<Event>, me: String = ana, admins: Set<String> = setOf(ana)) =
        World(base + events, emptyList(), WorldContext(me = me, domain = "example.org", communityName = "Riverside Commons", communityAdmins = admins, groupsKey = gk, zone = ZoneId.of("UTC")))

    @Test fun aCardsGroupIsItsClaimers() {
        val w = world(claim("pod-sourdough-ab12", lou, "Lou Martin", t0 + 100, false, "Sourdough starters", "Swap starters") + claim("bread", dev, "Dev Patel", t0 + 200, true, "Bread", "Loaves and crumbs"))
        val g = w.group("pod-sourdough-ab12")!!
        assertEquals(lou, g.creator)
        assertTrue(lou in g.admins)
        assertEquals(PlaceNames.STEWARD, w.adminWord(g.id, lou))
        assertNull(w.adminWord(g.id, gk)) // the groups key is nobody's steward
        // the relay lets the key that signed the 9007 moderate, not the claimer: they go through the belfry
        assertTrue(gk in g.relayAdmins)
        assertFalse(lou in g.relayAdmins)
        // what the groups key wrote for someone puts nobody in the member list
        assertEquals(setOf(lou), g.members)
        assertFalse(gk in w.group("general")!!.members)
        assertEquals(lou, w.cardStarter(g.id))
        // the "⬡ New group" post in the main channel: the group's card, by the claimer
        val post = w.messages("general").single { it.special is Special.PodCard }
        assertEquals(Special.PodCard(g.id), post.special)
        assertEquals(gk, post.pubkey)
        assertEquals(lou, post.author)
        assertEquals("Lou Martin", w.nameOver(post))
        assertEquals("created the group", post.cardLine)
        assertTrue(post.fromCard)
        // the topic's post: its card, no #new-topic chip, by the claimer
        val topic = w.messages("general").single { it.special is Special.TopicCard }
        assertEquals(Special.TopicCard("bread"), topic.special)
        assertEquals(dev, topic.author)
        assertEquals("created the topic", topic.cardLine)
        assertTrue("new-topic" in topic.topics)
        assertEquals(emptyList(), w.extraTopics(topic))
        assertEquals("Bread · Loaves and crumbs", w.group("bread")!!.about)
        assertEquals(dev, w.group("bread")!!.creator)
        assertEquals(1, w.group("bread")!!.members.size)
        // the first line: an activity line in our words, by name
        val line = w.messages(g.id).single()
        assertEquals(lou to "Lou Martin created the group from a printed card", w.cardLine(g.id, line))
        assertEquals(dev to "Dev Patel created the topic from a printed card", w.cardLine("bread", w.messages("bread").single()))
        assertNull(w.cardLine("general", post))
        // the log: the claimer created it, and it is a group
        val log = w.channelLog(g.id)
        val created = log.single { it.ev.kind == 9007 }
        assertEquals("created the group", created.text)
        assertEquals("Lou Martin", w.logWho(g.id, created.ev))
        assertEquals("created the channel", w.channelLog("bread").single { it.ev.kind == 9007 }.text)
        assertEquals("Dev Patel", w.logWho("bread", w.channelLog("bread").single { it.ev.kind == 9007 }.ev))
        // my own card: "You"
        assertEquals("You created the group from a printed card", world(claim("pod-mine-ab12", ana, "Ana Silva", t0 + 100, false, "Mine", "x")).let { x -> x.cardLine("pod-mine-ab12", x.messages("pod-mine-ab12").single())!!.second })
    }

    @Test fun olderGroupsWithoutByGoByTheCardLine() {
        val w = world(claim("pod-old-ab12", lou, "Lou Martin", t0 + 100, false, "Old", "From before", by = false))
        assertEquals(lou, w.group("pod-old-ab12")!!.creator)
        assertEquals(lou, w.messages("general").single { it.special is Special.PodCard }.author)
        assertEquals("Lou Martin", w.logWho("pod-old-ab12", w.channelLog("pod-old-ab12").single { it.ev.kind == 9007 }.ev))
    }

    @Test fun byOnSomeoneElsesEventIsIgnoredInTheWorld() {
        val w = world(listOf(
            ev(mallory, t0 + 100, 9007, t("h", "pod-forged-ab12"), t("by", lou)),
            ev(mallory, t0 + 101, 9002, t("h", "pod-forged-ab12"), t("name", "Forged"), t("by", lou)),
            ev(mallory, t0 + 102, 9, t("h", "general"), t("pod", "pod-forged-ab12"), t("by", lou), content = "⬡ New group: Forged"),
            ev(mallory, t0 + 103, 9, t("h", "pod-forged-ab12"), t("p", lou), t("card", "1"), t("by", lou), content = "Lou started this group from a printed card"),
        ))
        val g = w.group("pod-forged-ab12")!!
        assertEquals(mallory, g.creator)
        assertFalse(lou in g.admins)
        val post = w.messages("general").single { it.special is Special.PodCard }
        assertEquals(mallory, post.author)
        assertFalse(post.fromCard)
        assertEquals("Created a group", post.cardLine)
        // their "card line" is an ordinary message of theirs
        val line = w.messages(g.id).single()
        assertNull(w.cardLine(g.id, line))
        assertEquals(mallory, line.author)
        assertNull(w.cardStarter(g.id))
    }

    /** docs/cards.md §4: a guest is the steward of the group they started from a card, and of that one only. */
    @Test fun aGuestWhoStartedItIsItsSteward() {
        val events = claim("pod-guest-ab12", guest, "Gus", t0 + 100, false, "Guests' group", "x", guestPass = true) +
            listOf(ev(ana, t0 + 50, 9007, t("h", "pod-other-cd34")), ev(ana, t0 + 51, 9002, t("h", "pod-other-cd34"), t("name", "Other")), ev(ana, t0 + 52, 9, t("h", "general"), t("pod", "pod-other-cd34"), content = "⬡ New group: Other"))
        val w = world(events, me = guest)
        assertTrue(w.canAdmin("pod-guest-ab12"))
        assertTrue(w.mayArchive(w.group("pod-guest-ab12")))
        assertEquals(GroupRole.ADMIN, w.groupRole("pod-guest-ab12", guest))
        // through the belfry, not on the relay
        assertFalse(w.mayModerate(w.group("pod-guest-ab12")))
        // nothing anywhere else
        assertFalse(w.canAdmin("pod-other-cd34"))
        assertFalse(w.canAdmin("general"))
        assertFalse(w.isCommunityAdmin)
        assertEquals("Gus", w.realName(guest)) // the name the belfry gave on the 9000
    }
}
