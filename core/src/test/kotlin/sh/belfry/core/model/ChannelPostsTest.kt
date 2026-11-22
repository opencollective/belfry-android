package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.parseJsonOrNull
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A channel's Posts (SPEC 19.60; docs/one-shape.md "A channel's Settings"). */
class ChannelPostsTest {
    private val M = PostKind.MESSAGE; private val T = PostKind.THREAD; private val E = PostKind.EVENT; private val P = PostKind.PROPOSAL

    private fun obj(vararg kv: Pair<String, Any>): JsonObject = JsonObject(kv.associate { (k, v) ->
        k to when (v) { is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) }); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v as String) }
    })

    @Test fun readingPrefersAccepts() {
        assertEquals(ChannelPosts.ALL, ChannelPosts.read(obj()))
        assertEquals(ChannelPosts.ALL, ChannelPosts.read(obj("posts" to "anything")))
        assertEquals(setOf(E), ChannelPosts.read(obj("posts" to "event")))
        assertEquals(setOf(T), ChannelPosts.read(obj("posts" to "thread")))
        assertEquals(setOf(P), ChannelPosts.read(obj("posts" to "proposal")))
        assertEquals(ChannelPosts.ALL, ChannelPosts.read(obj("posts" to "request")))
        // accepts wins over posts; unknown kinds are left out; empty = absent
        assertEquals(setOf(M, E), ChannelPosts.read(obj("accepts" to listOf("event", "message", "poll"), "posts" to "anything")))
        assertEquals(setOf(P), ChannelPosts.read(obj("accepts" to listOf<String>(), "posts" to "proposal")))
        assertEquals(ChannelPosts.ALL, ChannelPosts.read(obj("accepts" to listOf("poll"))))
    }

    @Test fun writingAlsoWritesPostsForOlderApps() {
        assertEquals("anything", ChannelPosts.legacyPosts(ChannelPosts.ALL))
        assertEquals("event", ChannelPosts.legacyPosts(setOf(E)))
        assertEquals("thread", ChannelPosts.legacyPosts(setOf(T)))
        assertEquals("proposal", ChannelPosts.legacyPosts(setOf(P)))
        assertEquals("anything", ChannelPosts.legacyPosts(setOf(M)))
        assertEquals("anything", ChannelPosts.legacyPosts(setOf(E, P)))
        val c = ChannelPosts.write(obj("slug" to "garden", "on" to true, "posts" to "anything"), setOf(P, M))
        assertEquals(JsonArray(listOf(JsonPrimitive("message"), JsonPrimitive("proposal"))), c["accepts"])
        assertEquals(JsonPrimitive("anything"), c["posts"])
        // every other key kept
        assertEquals(JsonPrimitive("garden"), c["slug"]); assertEquals(JsonPrimitive(true), c["on"])
        // round trip
        assertEquals(setOf(M, P), ChannelPosts.read(c))
        // requests from the web stay while it takes all four
        assertEquals(JsonPrimitive("request"), ChannelPosts.write(obj("posts" to "request"), ChannelPosts.ALL)["posts"])
        assertEquals(JsonPrimitive("event"), ChannelPosts.write(obj("posts" to "request"), setOf(E))["posts"])
    }

    @Test fun theLastOneStaysOn() {
        assertEquals(setOf(E), ChannelPosts.toggle(setOf(E), E, false))
        assertFalse(ChannelPosts.mayTurnOff(setOf(E), E))
        assertTrue(ChannelPosts.mayTurnOff(setOf(E), M))
        assertEquals(setOf(M, E), ChannelPosts.toggle(setOf(E), M, true))
        assertEquals(setOf(T, P), ChannelPosts.toggle(ChannelPosts.toggle(ChannelPosts.ALL, M, false), E, false))
    }

    @Test fun thePlainSend() {
        assertTrue(ChannelPosts.plainSend(ChannelPosts.ALL))
        assertTrue(ChannelPosts.plainSend(setOf(T, E)))
        assertFalse(ChannelPosts.plainSend(setOf(E, P)))
    }

    // ---- through the channel's web settings (newest copy, keys merged) ----

    private val relay = "d".repeat(64)
    private val me = "a".repeat(64)
    private var n = 0
    private fun ev(pk: String, kind: Int, at: Long, tags: List<List<String>>, c: String = ""): Event { n++; return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags, c) }
    private fun world(events: List<Event>) = World(events, emptyList(), WorldContext(me = me, domain = "belfry.example", zone = ZoneId.of("UTC")), null)
    private val id = "garden-3f2a"
    private val channel = listOf(
        ev(relay, 39000, 100, listOf(listOf("d", id), listOf("name", "Garden"))),
        ev(relay, 39001, 100, listOf(listOf("d", id), listOf("p", me, "admin"))),
        ev(relay, 39002, 100, listOf(listOf("d", id), listOf("p", me))),
    )

    @Test fun webSettingsWriteAcceptsAndKeepTheRest() {
        val older = ev(me, 30078, 150, listOf(listOf("d", World.WEB_D + id), listOf("h", id)), """{"slug":"garden","on":true,"visibility":"public","periods":[[100,null]],"posts":"anything","extra":1}""")
        val w = world(channel + older)
        val g = w.group(id)!!
        assertEquals(ChannelPosts.ALL, w.webPage(g)!!.accepts)
        // nothing changed: nothing written
        assertNull(Actions.webSettings(g, w, 1000, public = true, posts = "anything", accepts = ChannelPosts.ALL))
        val d = Actions.webSettings(g, w, 1000, public = true, posts = "anything", accepts = setOf(PostKind.EVENT))!!
        val c = parseJsonOrNull(d.content) as JsonObject
        assertEquals(JsonPrimitive("event"), c["posts"])
        assertEquals(JsonArray(listOf(JsonPrimitive("event"))), c["accepts"])
        assertEquals(JsonPrimitive(1), c["extra"]); assertEquals(JsonPrimitive("garden"), c["slug"])
        val w2 = world(channel + older + ev(me, 30078, 200, d.tags, d.content))
        assertEquals(setOf(PostKind.EVENT), w2.webPage(w2.group(id))!!.accepts)
        assertEquals("event", w2.webPage(w2.group(id))!!.posts)
    }
}
