package sh.belfry.core.dm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import sh.belfry.core.Vectors
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** vectors/dm-reactions.json (shared with iOS and the web): reading reaction rumors into chips. */
class DmReactionsVectorsTest {
    private val v = Vectors.load("dm-reactions.json")

    private fun event(o: JsonObject) = Event(
        o.s("id") ?: "", o.s("pubkey")!!, o["created_at"]?.jsonPrimitive?.long ?: 0, o["kind"]!!.jsonPrimitive.long.toInt(),
        (o["tags"] as JsonArray).map { t -> (t as JsonArray).map { it.jsonPrimitive.content } }, o.s("content") ?: "", "",
    )

    @Test
    fun readsAsTheVectorsSay() {
        val target = (v["message"] as JsonObject).s("id")!!
        val cases = (v["read"] as JsonArray).map { it as JsonObject }
        assertTrue(cases.size >= 8)
        for (c in cases) {
            val reacts = LinkedHashMap<String, DmReaction>(); val gone = HashMap<String, String>()
            for (r in c["rumors"] as JsonArray) DmReaction.apply(reacts, gone, event(r as JsonObject))
            val snap = DmSnapshot(emptyList(), emptyMap(), sh.belfry.core.read.ReadState.EMPTY, c.s("viewer")!!, 0, reacts.values.toList())
            val got = snap.reactionsOf(target).map { ch ->
                JsonObject(mapOf("emoji" to JsonPrimitive(ch.emoji), "who" to JsonArray(ch.who.keys.map { JsonPrimitive(it) }), "mine" to JsonPrimitive(ch.mine != null)))
            }
            assertEquals(c["chips"], JsonArray(got), c.s("name"))
        }
    }
}
