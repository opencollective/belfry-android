package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.Vectors
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.parseJsonOrNull
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

/** Round 6 (SPEC 19.41 / 19.42, iOS Round6Tests): visibility, closed channels, the notification choice, a proposal on top. */
class Round6Test {
    private val relay = "d".repeat(64)
    private val me = "a".repeat(64)
    private val ben = "b".repeat(64)
    private val emma = "e".repeat(64)
    private var n = 0

    private fun ev(pk: String, kind: Int, at: Long, tags: List<List<String>>, c: String = ""): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags, c)
    }

    private fun channel(id: String, name: String, admins: List<String>, members: List<String>, closed: Boolean = false) = listOf(
        ev(relay, 39000, 100, listOf(listOf("d", id), listOf("name", name)) + (if (closed) listOf(listOf("closed")) else emptyList())),
        ev(relay, 39001, 100, listOf(listOf("d", id)) + admins.map { listOf("p", it, "admin") }),
        ev(relay, 39002, 100, listOf(listOf("d", id)) + members.map { listOf("p", it) }),
    )

    private fun world(events: List<Event>, who: String = me, admins: Set<String> = emptySet()) =
        World(events, emptyList(), WorldContext(me = who, domain = "belfry.example", communityName = "Belfry", zone = ZoneId.of("UTC"), communityAdmins = admins), null)

    private fun webEvent(id: String, content: JsonElement?): List<Event> =
        if (content == null || content is JsonNull) emptyList()
        else listOf(ev(relay, 30078, 150, listOf(listOf("d", World.WEB_D + id), listOf("h", id)), content.toString()))

    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun periods(x: JsonElement?): List<List<Long?>> =
        ((x as? JsonArray) ?: JsonArray(emptyList())).map { p -> (p as JsonArray).map { (it as? JsonPrimitive)?.longOrNull } }

    @Test fun visibilityVectors() {
        val v = Vectors.load("visibility.json")
        val id = v["id"].s()!!; val name = v["name"].s()!!; val now = (v["now"] as JsonPrimitive).longOrNull!!
        for (c in v["cases"] as JsonArray) {
            c as JsonObject
            val label = c["name"].s()!!
            val w = world(channel(id, name, listOf(me), listOf(me)) + webEvent(id, c["content"]))
            val g = assertNotNull(w.group(id), label)
            val toPublic = c["to"].s() == "public"
            val out = Actions.setVisibility(g, toPublic, w, now)
            val want = c["expect"] as? JsonObject
            if (want == null) { assertNull(out, label); continue }
            val d = assertNotNull(out, label)
            assertEquals(30078, d.kind, label)
            assertEquals(listOf(listOf("d", World.WEB_D + id), listOf("h", id), listOf("t", "belfry-web")), d.tags, label)
            val got = parseJsonOrNull(d.content) as JsonObject
            for (k in listOf("on", "slug", "visibility", "posts")) assertEquals(want[k]?.s(), got[k]?.s(), "$label: $k")
            assertEquals(periods(want["periods"]), periods(got["periods"]), "$label: periods")
            // written back, it reads as the new choice
            val after = world(channel(id, name, listOf(me), listOf(me)) + listOf(ev(me, 30078, 2000, d.tags, d.content)))
            assertEquals(toPublic, after.isPublicChannel(after.group(id)), label)
        }
        for (c in v["access"] as JsonArray) {
            c as JsonObject
            val closed = (c["closed"] as? JsonPrimitive)?.booleanOrNull == true
            val w = world(channel(id, "Garden Club", listOf(me), listOf(me), closed) + webEvent(id, c["content"]))
            assertEquals(c["expect"].s(), w.channelAccess(w.group(id)).key, c["name"].s())
        }
    }

    @Test fun webSettingsOnlyFromItsAdmins() {
        val id = "garden-3f2a"
        val w = world(channel(id, "Garden", listOf(emma), listOf(me, emma)) +
            listOf(ev(ben, 30078, 150, listOf(listOf("d", World.WEB_D + id), listOf("h", id)), "{\"on\":true,\"slug\":\"g\",\"visibility\":\"public\"}")))
        assertEquals(ChannelAccess.MEMBERS, w.channelAccess(w.group(id)))
        assertTrue(w.webContent(w.group(id)).isEmpty())
    }

    @Test fun closedChannel() {
        val id = "board-5d1e"
        val evs = channel(id, "Board", listOf(emma), listOf(emma), closed = true) + channel("general", "general", listOf(emma), listOf(me, emma, ben)) +
            listOf(ev(ben, 9021, 200, listOf(listOf("h", id)), "I can help with the budget"))
        val mine = world(evs)
        val g = mine.group(id)!!
        assertTrue(mine.needsJoinBar(g)); assertTrue(mine.readOnlyClosed(g))
        assertTrue(mayClose(g)); assertFalse(mayClose(mine.group("general")))
        val e = world(evs, emma)
        val ge = e.group(id)!!
        assertFalse(e.needsJoinBar(ge)); assertFalse(e.readOnlyClosed(ge))
        assertEquals(listOf(ben), ge.applications.map { it.pubkey })
        assertEquals(listOf(id), e.groupRequests().map { it.group.id })
        val admin = world(evs, me, setOf(me))
        assertFalse(admin.needsJoinBar(admin.group(id)!!))
        val inside = world(evs + listOf(ev(emma, 9000, 300, listOf(listOf("h", id), listOf("p", ben, "member")))), ben)
        assertFalse(inside.readOnlyClosed(inside.group(id)!!))
        assertTrue(inside.group(id)!!.applications.isEmpty())
        val open = world(channel("garden-3f2a", "Garden", listOf(me), listOf(me))).group("garden-3f2a")!!
        val close = Actions.groupMeta(open, closed = true, prompt = "")
        assertTrue(listOf("closed") in close.tags)
        assertTrue(listOf("prompt", Actions.DEFAULT_PROMPT) in close.tags)
    }

    @Test fun aRefusedVisibilityIsAFailedSetting() {
        val item = OutboxItem(localId = "x", target = Target.GROUPS, kind = 30078, content = "{}", tags = listOf(listOf("d", World.WEB_D + "garden"), listOf("h", "garden")),
            createdAt = 10, status = OutboxStatus.FAILED, error = "restricted: no")
        assertTrue(item.isVisibilitySetting())
        assertEquals(1, World(emptyList(), listOf(item), WorldContext(me = me, domain = "belfry.example", communityName = "Belfry", zone = ZoneId.of("UTC")), null).failedSettings("garden").size)
    }

    @Test fun notifyChoice() {
        assertEquals(NotifyChoice.EVERYTHING, NotifyChoice.of("all"))
        assertEquals(NotifyChoice.FOR_ME, NotifyChoice.of("default"))
        assertEquals(NotifyChoice.FOR_ME, NotifyChoice.of("mentions"))
        assertEquals(NotifyChoice.FOR_ME, NotifyChoice.of(null))
        assertEquals(NotifyChoice.NOTHING, NotifyChoice.of("off"))
        assertEquals(listOf("all", "default", "off"), NotifyChoice.entries.map { it.level })
        assertEquals("Mentions, replies to me, announcements (the default here)", NotifyChoice.FOR_ME.line("default"))
        assertEquals("Mentions, replies to me, announcements", NotifyChoice.FOR_ME.line("mentions"))
        assertEquals("Every new message", NotifyChoice.EVERYTHING.line("default"))
    }

    @Test fun proposalOptionsTurnoutVersions() {
        val parent = "garden-3f2a"; val prop = "prop-sundays-1a2b"
        val coord = "30023:$emma:sundays"
        val base = channel(parent, "Garden", listOf(emma), listOf(me, ben, emma, relay)) + channel(prop, "Sundays", listOf(emma), listOf(emma)) + listOf(
            ev(emma, 9, 110, listOf(listOf("h", parent), listOf("proposal", prop)), "New proposal"),
            ev(emma, 30023, 120, listOf(listOf("d", "sundays"), listOf("h", prop), listOf("t", "proposal"), listOf("title", "Open on Sundays"), listOf("version", "2"), listOf("status", "voting")), "We open on Sundays."),
        )
        val poll = ev(emma, 1068, 130, listOf(listOf("h", prop), listOf("a", coord), listOf("version", "2"), listOf("option", "yes", "Yes"), listOf("option", "later", "Later")), "?")
        val evs = base + listOf(poll, ev(ben, 1018, 140, listOf(listOf("h", prop), listOf("e", poll.id), listOf("response", "later"))))
        val w = world(evs)
        val p = w.proposalFor(prop)!!
        assertEquals(listOf("yes", "later"), p.options.map { it.first })
        assertEquals(listOf("Yes", "Later"), p.options.map { it.second })
        assertTrue(p.votingNow)
        assertEquals(Turnout(1, 2), w.voteTurnout(p))
        assertEquals(mapOf("later" to listOf(ben)), w.votesByOption(p))
        assertTrue(w.needsMyVote(p))
        val more = evs + listOf(
            ev(emma, 9, 125, listOf(listOf("h", prop), listOf("a", coord, "wss://x"), listOf("version", "2")), "📝 Version 2: Sundays only in summer"),
            ev(ben, 9, 126, listOf(listOf("h", prop), listOf("version", "3")), "📝 Version 3: not about this proposal"),
        )
        val wv = world(more)
        val vs = wv.proposalVersions(wv.proposalFor(prop)!!)
        assertEquals(listOf(2, 1), vs.map { it.version })
        assertEquals("Sundays only in summer", vs[0].note)
        assertEquals("We open on Sundays.", vs[0].text)
        assertEquals(110L, vs[1].at)
        assertNull(vs[1].text)
        val bare = Poll(ev(emma, 1068, 1, listOf(listOf("h", prop))), 1, emptyMap())
        assertEquals(listOf("yes", "no", "abstain"), bare.options.map { it.first })
    }

    @Test fun postsAndVisibilityInOneEvent() {
        val id = "garden-3f2a"
        val w = world(channel(id, "Garden Club", listOf(me), listOf(me)))
        val g = w.group(id)!!
        assertNull(Actions.webSettings(g, w, 1000, public = false, posts = null))
        val d = Actions.webSettings(g, w, 1000, public = false, posts = "thread")!!
        val c = parseJsonOrNull(d.content) as JsonObject
        assertEquals("thread", c["posts"].s())
        assertEquals("members", c["visibility"].s())
        val w2 = world(channel(id, "Garden Club", listOf(me), listOf(me)) + listOf(ev(me, 30078, 2000, d.tags, d.content)))
        assertEquals("thread", w2.webPage(w2.group(id))?.posts)
        // public and events at once: one event, the period opened
        val both = parseJsonOrNull(Actions.webSettings(g, w, 1000, public = true, posts = "event")!!.content) as JsonObject
        assertEquals("public", both["visibility"].s()); assertEquals("event", both["posts"].s())
        assertEquals(listOf(listOf<Long?>(1000, null)), periods(both["periods"]))
    }

    /** Reactions on thread replies (channels/thread): k = the target's kind; readers key on e and never filter on k. */
    @Test fun reactionsOnThreadReplies() {
        val d = Actions.react("general", "f".repeat(64), ben, "🙏", kind = 1111)
        assertEquals(listOf(listOf("h", "general"), listOf("e", "f".repeat(64)), listOf("p", ben), listOf("k", "1111")), d.tags)
        assertEquals(listOf("k", "9"), Actions.react("general", "f".repeat(64), ben, "🙏").tags.last())
        val root = ev(ben, 9, 100, listOf(listOf("h", "general")), "Who has a ladder?")
        val reply = ev(emma, 1111, 110, listOf(listOf("h", "general"), listOf("E", root.id, "", root.pubkey), listOf("K", "9"), listOf("e", root.id), listOf("k", "9")), "I do")
        val r1 = ev(me, 7, 120, listOf(listOf("h", "general"), listOf("e", reply.id), listOf("p", emma), listOf("k", "1111")), "🙏")
        val r2 = ev(ben, 7, 121, listOf(listOf("h", "general"), listOf("e", reply.id), listOf("p", emma)), "🙏") // no k at all: still counts
        val w = world(channel("general", "general", listOf(emma), listOf(me, ben, emma)) + listOf(root, reply, r1, r2))
        val rs = w.reactionsOf("general", reply.id)
        assertEquals(1, rs.size)
        assertEquals(setOf(me, ben), rs[0].who)
        assertNotNull(rs[0].mine)
    }
}
