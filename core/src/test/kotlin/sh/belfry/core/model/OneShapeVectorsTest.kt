package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import sh.belfry.core.Vectors
import sh.belfry.core.nostr.Event
import kotlin.test.Test
import kotlin.test.assertEquals

/** vectors/one-shape.json (written here, copied by belfry-ios). */
class OneShapeVectorsTest {
    private val v = Vectors.load("one-shape.json")
    private fun cases(section: String) = v[section]!!.jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
    private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    private fun JsonElement?.tags(): List<List<String>> = (this as? JsonArray)?.map { t -> t.jsonArray.map { it.jsonPrimitive.content } } ?: emptyList()
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun recent() = cases("recent").forEach { c ->
        val events = c["events"]!!.jsonArray.map { Event.fromJson(it)!! }
        val got = OneShape.townList(c.s("me"), c["now"]!!.jsonPrimitive.long, c["known"].strings().toSet(), c["pinsAll"].strings(), c["pinsMine"].strings(), events)
        val e = c["expect"]!!.jsonObject
        assertEquals(e["pinned"].strings(), got.pinned, c.s("name"))
        assertEquals(e["recent"].strings(), got.recent, c.s("name"))
        assertEquals(e["list"].strings(), got.list, c.s("name"))
    }

    @Test fun pins() = cases("pins").forEach { c ->
        val name = c.s("name")
        when {
            c["copies"] != null -> {
                val newest = c["copies"]!!.jsonArray.map { it.jsonObject }.maxBy { it["created_at"]!!.jsonPrimitive.long }
                assertEquals(c["expect"].strings(), OneShape.parsePins(newest.s("plain"), c["known"].strings().toSet()), name)
            }
            c["channelOrder"] != null ->
                assertEquals(c["expect"].strings(), OneShape.parsePins(c.s("channelOrder"), c["known"].strings().toSet(), key = "pinned"), name)
            else -> {
                var list = c["start"].strings()
                for (x in c["toggle"].strings()) list = OneShape.togglePin(list, x)
                assertEquals(c.s("expectContent"), OneShape.pinsContent(list), name)
            }
        }
    }

    @Test fun headerRow() {
        assertEquals(v["headerRow"]!!.jsonObject["dropOrder"].strings(), OneShape.DROP_ORDER)
        cases("headerRow").forEach { c ->
            val items = c["items"]!!.jsonArray.map { it.jsonObject.s("key") to it.jsonObject["width"]!!.jsonPrimitive.int }
            val got = OneShape.fitRow(items, { it.first }, { it.second }, c["gap"]!!.jsonPrimitive.int, c["budget"]!!.jsonPrimitive.int)
            assertEquals(c["expect"].strings(), got.map { it.first }, c.s("name"))
        }
    }

    @Test fun replyAll() = cases("replyAll").forEach { c ->
        val got = OneShape.replyAll(c.s("mailFrom"), c["tags"].tags(), c.s("domain"))
        val e = c["expect"]!!.jsonObject
        assertEquals(e["rcptTo"].strings(), got.rcptTo, c.s("name"))
        assertEquals(e.s("to"), got.to, c.s("name"))
        assertEquals(e["cc"].strings(), got.cc, c.s("name"))
    }

    @Test fun threadState() = cases("threadState").forEach { c ->
        val labels = c["labels"]!!.jsonArray.map { it.jsonObject }.map { OneShape.StatusLabel(it.s("id"), it["created_at"]!!.jsonPrimitive.long, it.s("l")) }
        val sent = (c["sent"] as? JsonArray)?.map { it.jsonPrimitive.long } ?: emptyList()
        assertEquals(c.s("expect"), OneShape.threadState(c["received"]!!.jsonArray.map { it.jsonPrimitive.long }, labels, sent), c.s("name"))
    }

    @Test fun drafts() = cases("drafts").forEach { c ->
        val drafts = c["drafts"]!!.jsonArray.map { it.jsonObject }.map { it.s("id") to it.s("pubkey") }
        val sent = c["sent"]!!.jsonArray.flatMap { it.jsonObject["tags"].tags() }.filter { it.size >= 2 && it[0] == "draft" }.map { it[1] }.toSet()
        val dels = c["deletions"]!!.jsonArray.map { it.jsonObject }.map { it.s("pubkey") to it.s("e") }
        assertEquals(c["expect"].strings(), OneShape.visibleDrafts(drafts, sent, dels), c.s("name"))
    }

    @Test fun due() = cases("due").forEach { c ->
        val want = c["expect"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.long }
        assertEquals(want, OneShape.due(c["tags"].tags()), c.s("name"))
    }

    @Test fun dmReply() = cases("dmReply").forEach { c ->
        val tag = OneShape.dmReplyTag(c.s("root"))
        assertEquals(c["expectTag"].strings(), tag)
        assertEquals(c.s("root"), OneShape.dmRoot(listOf(tag)))
    }
}
