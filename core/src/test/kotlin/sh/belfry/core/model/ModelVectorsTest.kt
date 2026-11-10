package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.Vectors
import sh.belfry.core.crypto.Hex
import sh.belfry.core.nostr.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

private fun JsonElement?.o(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonElement?.list(): List<JsonObject> = (this as? JsonArray)?.map { it.o() } ?: emptyList()
private fun JsonElement?.sOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
private fun JsonElement?.strs(): List<String> = (this as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.tags(): List<List<String>> = (this as? JsonArray)?.map { it.strs() } ?: emptyList()
private fun JsonElement?.events(): List<Event> = (this as? JsonArray)?.map { Event.fromJson(it) ?: fail("bad event in vector") } ?: emptyList()

/** Every case of the shared vectors that the model answers (SPEC 17). */
class ModelVectorsTest {
    private fun world(c: JsonObject, admins: Set<String> = emptySet(), events: List<Event>? = null) =
        World(events ?: c["events"].events(), emptyList(), WorldContext(me = c["me"].sOrNull()!!, communityAdmins = admins))

    @Test
    fun groups() {
        val cases = Vectors.load("groups.json")["cases"].list()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val got = world(c).groups
            val want = c["expect"].o()["groups"].list()
            assertEquals(want.map { it["id"].sOrNull() }, got.map { it.id }, "order")
            for ((g, w) in got.zip(want)) {
                assertEquals(w["name"].sOrNull(), g.name, g.id)
                assertEquals(w["about"].sOrNull(), g.about, g.id)
                assertEquals(w["created_at"].long(), g.createdAt, g.id)
                assertEquals(w["last_activity"].long(), g.lastActivity, g.id)
                assertEquals(w["members"].strs(), g.members.sorted(), g.id + " members")
                assertEquals(w["admins"].strs(), g.admins.sorted(), g.id + " admins")
                assertEquals(w["type"].sOrNull(), g.type.key, g.id)
                assertEquals(w["parent"].sOrNull(), g.parent, g.id + " parent")
                assertEquals(w["closed"].bool(), g.closed, g.id)
                assertEquals(w["prompt"].sOrNull(), g.prompt, g.id)
                assertEquals(w["applications"].list().map { it["pubkey"].sOrNull() }, g.applications.map { it.pubkey }, g.id)
                assertEquals(w["applications"].list().map { it["answer"].sOrNull() }, g.applications.map { it.answer }, g.id)
            }
        }
    }

    @Test
    fun messages() {
        val cases = Vectors.load("messages.json")["cases"].list()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val got = world(c).messages(c["group"].sOrNull()!!)
            val want = c["expect"].o()["messages"].list()
            assertEquals(want.map { it["id"].sOrNull() }, got.map { it.id })
            for ((m, w) in got.zip(want)) {
                assertEquals(w["text"].sOrNull(), m.text)
                assertEquals(w["topics"].strs(), m.topics)
                val media = w["media"].list()
                assertEquals(media.map { it["url"].sOrNull() }, m.media.map { it.url })
                assertEquals(media.map { it["mime"].sOrNull() }, m.media.map { it.mime })
                assertEquals(media.map { it["w"].int() }, m.media.map { it.w })
                assertEquals(media.map { it["h"].int() }, m.media.map { it.h })
                assertEquals(media.map { it["name"].sOrNull() }, m.media.map { it.name })
                assertEquals(null, m.edited)
            }
        }
    }

    @Test
    fun reactions() {
        val cases = Vectors.load("reactions.json")["cases"].list()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val got = world(c).reactions(c["group"].sOrNull()!!)
            val want = c["expect"].o()["reactions"].o()
            assertEquals(want.keys, got.keys)
            for ((id, list) in want) {
                val w = list.list()
                val g = got[id] ?: emptyList()
                assertEquals(w.map { it["emoji"].sOrNull() }, g.map { it.emoji })
                assertEquals(w.map { it["who"].strs() }, g.map { it.who.sorted() })
                assertEquals(w.map { it["mine"].sOrNull() }, g.map { it.mine?.id })
            }
        }
    }

    @Test
    fun threads() {
        val cases = Vectors.load("threads.json")["cases"].list()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c["name"].sOrNull() ?: ""
            val group = c["group"].sOrNull()!!
            val w = world(c)
            val expect = c["expect"].o()
            val th = w.threadsOf(group)
            for ((root, info) in expect["threads"].o()) {
                val i = info.o()
                assertEquals(i["count"].int(), th[root]?.count, name)
                assertEquals(i["last"].long(), th[root]?.last, name)
                assertEquals(i["who"].strs(), th[root]?.who, name)
            }
            for ((root, ids) in expect["replies"].o()) {
                assertEquals(ids.strs(), w.threadReplies(group, root).map { it.id }, name)
            }
            if (expect["inline_visible"] != null) {
                val root = expect["replies"].o().keys.first()
                val (shown, hidden) = w.inlineThread(group, root)
                assertEquals(expect["inline_visible"].strs(), shown.map { it.id }, name)
                assertEquals(w.threadReplies(group, root).size - shown.size, hidden)
            }
            if (expect["feed"] != null) {
                val feed = w.feed(group)
                val want = expect["feed"].list()
                assertEquals(want.size, feed.size, name)
                for ((item, x) in feed.zip(want)) {
                    when (item) {
                        is FeedItem.Msg -> { assertEquals("msg", x["t"].sOrNull(), name); assertEquals(x["id"].sOrNull(), item.m.id, name) }
                        is FeedItem.Join -> {
                            assertEquals("join", x["t"].sOrNull(), name)
                            assertEquals(x["id"].sOrNull(), item.j.id, name)
                            assertEquals(x["pubkey"].sOrNull(), item.j.pubkey, name)
                            assertEquals(x["by"].sOrNull(), item.j.by, name)
                        }
                        is FeedItem.Activity -> {
                            assertEquals("act", x["t"].sOrNull(), name)
                            assertEquals(x["root"].sOrNull(), item.root.id, name)
                            assertEquals(x["last"].sOrNull(), item.last.id, name)
                        }
                    }
                    assertEquals(x["at"].long(), item.at, name)
                }
            }
        }
    }

    @Test
    fun recentlyActive() {
        val cases = Vectors.load("recently-active.json")["cases"].list()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c["name"].sOrNull() ?: ""
            val w = world(c)
            val group = c["group"].sOrNull()!!
            val got = w.recentlyActive(group, c["now"].long()!!)
            val want = c["expect"].o()["recently_active"].list()
            assertEquals(want.map { it["pubkey"].sOrNull() }, got.map { it.pubkey }, name)
            assertEquals(want.map { it["at"].long() }, got.map { it.at }, name)
            c["expect"].o()["my_seen"].long()?.let { assertEquals(it, w.mySeen(group), name) }
        }
    }

    @Test
    fun canAdmin() {
        val v = Vectors.load("can-admin.json")
        val doc = v["belfry_json"].o()
        val admins = (doc["admins"].strs() + doc["access"].o()["admins"].strs()).filter(Hex::isKey).toSet()
        val evs = v["events"].events()
        val cases = v["cases"].list()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c["name"].sOrNull() ?: ""
            val w = world(c, admins, evs)
            val e = c["expect"].o()
            assertEquals(e["g1"].bool(), w.canAdmin(w.group("g1")), name)
            assertEquals(e["g2"].bool(), w.canAdmin("g2"), name)
            assertEquals(e["unknown"].bool(), w.canAdmin(w.group("nope")), name)
            assertEquals(e["is_community_admin"].bool(), w.isCommunityAdmin, name)
        }
    }

    @Test
    fun mentions() {
        val v = Vectors.load("mentions.json")
        for (c in v["mention_tags"].list()) assertEquals(c["p_tags"].tags(), Mentions.tags(c["text"].sOrNull()!!), c["text"].sOrNull())
        val s = v["mention_tags_with_skip"].o()
        assertEquals(s["p_tags"].tags(), Mentions.tags(s["text"].sOrNull()!!, s["skip"].strs()))
        for (c in v["picker_queries"].list()) assertEquals(c["query"].sOrNull(), Mentions.query(c["before_caret"].sOrNull()!!), c["before_caret"].sOrNull())
        for (c in v["substitutions"].list()) {
            val picked = (c["picked"] as JsonArray).map { it.strs() }.map { it[0] to it[1] }
            assertEquals(c["sent"].sOrNull(), Mentions.substitute(c["text"].sOrNull()!!, picked))
        }
        for (c in v["hashtags"].list()) assertEquals(c["topics"].strs(), Topics.hashtags(c["text"].sOrNull()!!), c["text"].sOrNull())
    }

    @Test
    fun actions() {
        val v = Vectors.load("actions.json")
        for (c in v["slug_examples"].list()) {
            val want = c["slug"].sOrNull()!!
            assertEquals(want, Actions.slug(c["name"].sOrNull()!!) { "0".repeat(it * 2) }, c["name"].sOrNull())
        }
        val cases = v["cases"].list()
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val a = c["args"].o()
            val action = c["action"].sOrNull()!!
            val g = a["group"].sOrNull() ?: ""
            val got: List<Draft> = when (action) {
                "postMessage" -> listOf(Actions.postMessage(g, a["text"].sOrNull()!!, a["topic"].sOrNull(), a["announce"].bool() ?: false))
                "postThreadReply" -> {
                    val r = a["root"].o()
                    listOf(Actions.postThreadReply(g, r["id"].sOrNull()!!, r["pubkey"].sOrNull()!!, r["kind"].int()!!, a["text"].sOrNull()!!))
                }
                "react" -> {
                    val m = a["message"].o()
                    listOf(Actions.react(g, m["id"].sOrNull()!!, m["pubkey"].sOrNull()!!, a["emoji"].sOrNull()!!))
                }
                "unreact" -> listOf(Actions.unreact(g, a["reaction"].sOrNull()!!))
                "deleteMessage" -> listOf(Actions.deleteMessage(g, a["message"].sOrNull()!!))
                "editChannel" -> listOf(Actions.editChannel(g, a["name"].sOrNull()!!, a["about"].sOrNull()!!))
                "setInbox" -> listOf(Actions.setInbox(g, a["on"].bool() ?: false))
                "joinGroup" -> listOf(Actions.join(g))
                "leaveGroup" -> listOf(Actions.leave(g))
                "createChannel" -> Actions.createChannel(a["id"].sOrNull()!!, a["name"].sOrNull()!!, a["about"].sOrNull()!!)
                "markSeen" -> listOf(Actions.seen(g))
                else -> fail("unknown action $action")
            }
            val want = c["events"].list()
            assertEquals(want.size, got.size, action)
            for ((d, w) in got.zip(want)) {
                assertEquals(w["target"].sOrNull(), d.target.key, action)
                assertEquals(w["kind"].int(), d.kind, action)
                assertEquals(w["content"].sOrNull(), d.content, action)
                assertEquals(w["tags"].tags(), d.tags, action)
            }
        }
    }

    @Test
    fun seenThrottle() {
        assertFalse(Actions.shouldMarkSeen(now = 10_000, mySeen = 9_000, lastSentHere = 0))
        assertTrue(Actions.shouldMarkSeen(now = 9_000 + 6 * 3600, mySeen = 9_000, lastSentHere = 0))
        assertFalse(Actions.shouldMarkSeen(now = 30_000, mySeen = 0, lastSentHere = 29_000))
        assertNotNull(Actions.seen("general"))
    }
}
