package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import sh.belfry.core.outbox.Target
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC 19.77 (a reading guest posts in a topic reached by a card, and only there) and SPEC 19.78
 * (followers, not members, in a channel). The rules are the web's (8e2b3b2, fa579cd, 75810d1).
 */
class CardsGuestTest {
    private val topic = "tool-library-9f2a"
    private val h = listOf(listOf("h", topic))

    private fun may(
        reading: Boolean = true, channel: String? = topic, card: String? = "topic", target: Target = Target.GROUPS, kind: Int = 9,
        tags: List<List<String>> = h, mimes: List<String> = emptyList(),
    ) = GuestTopic.mayPost(reading, channel, card, target, kind, tags, mimes)

    @Test
    fun theCardFieldIsGroupOrTopicOrNothing() {
        assertEquals("topic", GuestTopic.card("topic"))
        assertEquals("group", GuestTopic.card("group"))
        assertNull(GuestTopic.card(null))
        assertNull(GuestTopic.card(""))
        assertNull(GuestTopic.card("Topic"))
        assertNull(GuestTopic.card("event"))
    }

    @Test
    fun aReadingGuestOfATopicCardIsThereAndNowhereElse() {
        assertTrue(GuestTopic.here(true, topic, "topic", topic))
        assertFalse(GuestTopic.here(true, topic, "topic", "general"), "another channel")
        assertFalse(GuestTopic.here(true, topic, "group", topic), "a group card")
        assertFalse(GuestTopic.here(true, topic, null, topic), "an ordinary guest link")
        assertFalse(GuestTopic.here(false, topic, "topic", topic), "joined with a name and an e-mail: the usual composer")
        assertFalse(GuestTopic.here(true, null, "topic", topic))
        assertFalse(GuestTopic.here(true, "", "topic", ""))
    }

    @Test
    fun onlyAPlainMessageWithPicturesGoes() {
        assertTrue(may(), "kind 9 with [[h, T]]")
        assertTrue(may(mimes = listOf("image/jpeg", "image/png")), "its photos")
        assertTrue(may(tags = h + listOf(listOf("imeta", "url https://x/y.jpg", "m image/jpeg"))), "imeta")
        assertEquals(Draft(Target.GROUPS, 9, "Spare drill at no. 7", h), GuestTopic.post(topic, "  Spare drill at no. 7 "))
        assertTrue(GuestTopic.post(topic, "hi #tools @Ana").let { may(target = it.target, kind = it.kind, tags = it.tags) }, "the text is never turned into tags")

        assertFalse(may(mimes = listOf("image/jpeg", "application/pdf")), "a file that is not a picture")
        assertFalse(may(mimes = listOf("video/mp4")))
        assertFalse(may(kind = 7), "a reaction")
        assertFalse(may(kind = 1111), "a reply in a thread")
        assertFalse(may(kind = 9021), "a request to join")
        assertFalse(may(target = Target.MAIN))
        assertFalse(may(target = Target.API))
        assertFalse(may(tags = h + listOf(listOf("t", "tools"))), "a hashtag topic")
        assertFalse(may(tags = h + listOf(listOf("p", "ab".repeat(32)))), "a mention")
        assertFalse(may(tags = h + listOf(listOf("e", "cd".repeat(32)))), "an edit or a reply")
        assertFalse(may(tags = emptyList()), "no h")
        assertFalse(may(tags = h + h), "two h")
        assertFalse(may(tags = listOf(listOf("h"))))
        assertFalse(may(tags = listOf(listOf("h", "general"))), "another channel")
        assertFalse(may(card = "group"), "a group card")
        assertFalse(may(card = null), "an ordinary guest link")
        assertFalse(may(reading = false), "not in guest mode: not this rule's business")
        // what the app's usual composer queues for the same text is not on the list either
        val usual = Actions.postMessage(topic, "see #seeds")
        assertFalse(may(tags = usual.tags))
    }

    @Test
    fun aReadingGuestWithNoNameIsCalledGuest() {
        val by = "aa".repeat(32)
        val guest = "bb".repeat(32)
        val me = "cc".repeat(32)
        fun ev(id: String, pk: String, at: Long, kind: Int, tags: List<List<String>>, content: String = "") = Event(id.repeat(32), pk, at, kind, tags, content, "")
        val events = listOf(
            ev("01", by, 100, 9007, listOf(listOf("h", topic))),
            ev("02", by, 101, 9002, listOf(listOf("h", topic), listOf("name", "tool-library"))),
            ev("03", by, 110, 9000, listOf(listOf("h", topic), listOf("p", guest, "member"), listOf("guest", ""), listOf("reading", "1"))),
            ev("04", guest, 120, 9, listOf(listOf("h", topic)), "Spare cordless drill, ask me at no. 7"),
        )
        val w = World(events, emptyList(), WorldContext(me = me, domain = "example.org", communityName = "Example", zone = ZoneOffset.UTC))
        assertEquals("Guest", w.displayName(guest))
        assertEquals(World.SOMEBODY, w.displayName("dd".repeat(32)), "anyone else without a profile stays Somebody")
        // their message shows; they are no follower of the topic
        assertEquals(listOf("Spare cordless drill, ask me at no. 7"), w.messages(topic).map { it.text })
        assertFalse(guest in w.group(topic)!!.members)
    }

    // ---- followers (SPEC 19.78) ----------------------------------------------------------------------

    private fun group(type: ChannelType, name: String = "tools", closed: Boolean = false) =
        World(
            listOf(
                Event("0a".repeat(32), "aa".repeat(32), 100, 9007, listOf(listOf("h", name)), "", ""),
                Event("0b".repeat(32), "aa".repeat(32), 101, 9002, listOf(listOf("h", name), listOf("name", name)), "", ""),
            ),
            emptyList(), WorldContext(me = "aa".repeat(32), domain = "example.org", communityName = "Example", zone = ZoneOffset.UTC),
        ).group(name)!!.copy(type = type, closed = closed)

    @Test
    fun aChannelHasFollowersAGroupKeepsMembers() {
        val channel = group(ChannelType.CHANNEL)
        val pod = group(ChannelType.POD, "Finance")
        val event = group(ChannelType.EVENT)
        val proposal = group(ChannelType.PROPOSAL)
        assertTrue(Followers.followsIt(channel))
        for (g in listOf(pod, event, proposal, null)) assertFalse(Followers.followsIt(g))

        assertEquals("1 follower", Followers.peopleCount(channel, 1))
        assertEquals("12 followers", Followers.peopleCount(channel, 12))
        assertEquals("0 followers", Followers.peopleCount(channel, 0))
        assertEquals("1 member", Followers.peopleCount(pod, 1))
        assertEquals("8 members", Followers.peopleCount(pod, 8))
        assertEquals("8 members", Followers.peopleCount(event, 8))
        assertEquals("8 members", Followers.peopleCount(proposal, 8))
        assertEquals("3 members", Followers.peopleCount(null, 3))

        assertEquals("Followers", Followers.title(channel))
        assertEquals("Members", Followers.title(pod))
        assertEquals("People", Followers.title(pod, otherwise = "People"))
        assertEquals("Followers", Followers.title(channel, otherwise = "People"))

        assertEquals("Asking to follow", Followers.asking(channel))
        assertEquals("Asking to join", Followers.asking(pod))
        assertEquals("People ask to follow this channel.", Followers.askLine(channel))
        assertEquals("People ask to join this group.", Followers.askLine(pod))
        assertEquals("Ask to follow", Followers.askButton(channel))
        assertEquals("Ask to join", Followers.askButton(pod))
        assertEquals("Ask to follow #tools", Followers.askTitle(channel))
        assertEquals("Ask to join Finance", Followers.askTitle(pod))
        assertEquals("People ask to follow. A steward of this channel approves.", Followers.closedLine(channel))
        assertEquals("People ask to join. A steward of this group approves.", Followers.closedLine(pod))

        // the invite list (web Invite.tsx): a member who follows; a guest is "in"; a group keeps its words
        assertEquals("Already following", Followers.alreadyHere(channel, guestHere = false))
        assertEquals("Already following", Followers.alreadyHere(group(ChannelType.CHANNEL, closed = true), guestHere = false))
        assertEquals("Already in", Followers.alreadyHere(channel, guestHere = true))
        assertEquals("Already a member", Followers.alreadyHere(pod, guestHere = false))
        assertEquals("Already in", Followers.alreadyHere(group(ChannelType.POD, "Finance", closed = true), guestHere = false))

        assertEquals("Only followers of #Hello see this.", Followers.onlySee(channel, "#Hello"))
        assertEquals("Only members of Finance see this.", Followers.onlySee(pod, "Finance"))
    }
}
