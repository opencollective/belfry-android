package sh.belfry.core.typing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import sh.belfry.core.Vectors
import kotlin.test.Test
import kotlin.test.assertEquals

/** vectors/typing.json (belfry-ios fc1e392, sha256 c387649a…6b2f91; tools/vectors/typing.py there). */
class TypingVectorsTest {
    private val v = Vectors.load("typing.json")
    private val place = TypingPlace.channel("g")

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.d(k: String) = this[k]!!.jsonPrimitive.double
    private fun JsonObject.flag(k: String) = this[k]?.jsonPrimitive?.booleanOrNull == true

    @Test fun constants() {
        val c = v["constants"]!!.jsonObject
        assertEquals(Typing.GAP_S, c["send_gap"]!!.jsonPrimitive.long)
        assertEquals(Typing.SHOW_S, c["show_for"]!!.jsonPrimitive.long)
        assertEquals(Typing.SKEW_S, c["skew"]!!.jsonPrimitive.long)
        assertEquals(Typing.SHOW_S, c["stop_window"]!!.jsonPrimitive.long)
        assertEquals(Typing.PAUSE_S, c["refused_pause"]!!.jsonPrimitive.long)
        assertEquals(Typing.SIGNER_MS, c["signer_wait"]!!.jsonPrimitive.long * 1000)
        assertEquals(Typing.KIND, c["kind"]!!.jsonPrimitive.int)
        assertEquals(Typing.WRAP_KIND, c["wrap_kind"]!!.jsonPrimitive.int)
    }

    @Test fun texts() {
        for (t in v["texts"]!!.jsonArray.map { it.jsonObject }) {
            val names = t["names"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(t.s("text"), Typing.line(names) ?: "", names.toString())
        }
    }

    @Test fun accept() {
        for (a in v["accept"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(a["accepted"]!!.jsonPrimitive.boolean, Typing.fresh(a["created_at"]!!.jsonPrimitive.long, a.d("now")), a.s("note"))
        }
    }

    @Test fun board() {
        for (c in v["board"]!!.jsonArray.map { it.jsonObject }) {
            val b = TypingBoard(c.s("me"))
            val lastMessage = HashMap<String, Long>()
            val got = ArrayList<List<String>>()
            for (st in c["steps"]!!.jsonArray.map { it.jsonObject }) {
                val now = st.d("now")
                when (st.s("type")) {
                    "event" -> b.apply(place, st.s("author"), st["created_at"]!!.jsonPrimitive.long, st.flag("stop"), now)
                    "message" -> lastMessage[st.s("author")] = (st["created_at"]?.jsonPrimitive?.long ?: now.toLong())
                    "query" -> got.add(b.typers(place, now) { lastMessage[it] ?: 0L })
                }
            }
            val want = c["expect"]!!.jsonArray.map { e -> e.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals(want, got, c.s("note"))
        }
    }

    @Test fun sender() {
        for (c in v["sender"]!!.jsonArray.map { it.jsonObject }) {
            val s = TypingSender()
            val got = ArrayList<Pair<String, Double>>()
            for (st in c["steps"]!!.jsonArray.map { it.jsonObject }) {
                val now = st.d("now")
                when (st.s("type")) {
                    "key" -> if (s.onText(place, st.s("text"), now, restored = st.flag("restored") || st.flag("editing"))) got.add("typing" to now)
                    "clear" -> if (s.onCleared(place, now)) got.add("stop" to now)
                    "send" -> s.onSent(place)
                }
            }
            val want = c["expect"]!!.jsonArray.map { e -> e.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.double } }
            assertEquals(want, got, c.s("note"))
        }
    }
}
