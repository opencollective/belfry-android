package sh.belfry.core.read

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.Vectors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun JsonElement?.o(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonElement?.list(): List<JsonObject> = (this as? JsonArray)?.map { it.o() } ?: emptyList()
private fun JsonElement?.str(): String = (this as JsonPrimitive).content
private fun JsonElement?.long(): Long = (this as JsonPrimitive).longOrNull!!

/** Every case of vectors/readstate.json (SPEC 19.27). */
class ReadStateVectorsTest {
    private val cases = Vectors.load("readstate.json")["cases"].list()

    private fun st(e: JsonElement?) = ReadState.parse(e!!)
    private fun same(name: String, expect: JsonElement?, got: ReadState) =
        assertEquals(st(expect).serialize(), got.serialize(), name)

    @Test
    fun everyCase() {
        assertTrue(cases.size >= 15)
        val ops = HashSet<String>()
        for (c in cases) {
            val name = c["name"].str()
            val op = c["op"].str()
            ops.add(op)
            when (op) {
                "merge" -> same(name, c["expect"], ReadState.merge(st(c["local"]), st(c["remote"])))
                "parse" -> same(name, c["expect"], ReadState.parse(c["input"].str()))
                "serialize" -> assertEquals(c["expect_text"].str(), st(c["input"]).serialize(), name)
                "readUpTo" -> {
                    val s = st(c["state"])
                    for (q in c["queries"].list())
                        assertEquals(q["expect"].long(), s.readUpTo(q["sec"].str()[0], q["key"].str(), c["now"].long()), "$name: ${q["sec"].str()} ${q["key"].str().take(12)}")
                }
                "mark" -> {
                    var s = st(c["state"])
                    for (step in c["steps"].list()) s = s.mark(step["sec"].str()[0], step["key"].str(), step["at"].long())
                    same(name, c["expect"], s)
                }
                "prune" -> {
                    val got = st(c["state"]).prune(c["now"].long())
                    same(name, c["expect"], got)
                    c["expect_counts"]?.let { n ->
                        assertEquals(n.o()["c"].long(), got.c.size.toLong(), name)
                        assertEquals(n.o()["d"].long(), got.d.size.toLong(), name)
                        assertEquals(n.o()["s"].long(), got.s, name)
                    }
                }
                "covers" -> assertEquals((c["expect"] as JsonPrimitive).booleanOrNull, ReadState.covers(st(c["remote"]), st(c["local"]), c["now"].long()), name)
                else -> error("unknown op $op in $name")
            }
        }
        assertEquals(setOf("merge", "parse", "serialize", "readUpTo", "mark", "prune", "covers"), ops)
    }

    @Test
    fun serializeRoundTrips() {
        val s = ReadState(s = 5, m = 6, c = mapOf("b" to 2, "a" to 1), extra = mapOf("q" to JsonPrimitive("x")))
        assertEquals("{\"q\":\"x\",\"v\":1,\"s\":5,\"m\":6,\"c\":{\"a\":1,\"b\":2},\"t\":{},\"d\":{}}", s.serialize())
        assertEquals(s.serialize(), ReadState.parse(s.serialize()).serialize())
    }
}
