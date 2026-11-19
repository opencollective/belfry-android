package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import sh.belfry.core.Vectors
import sh.belfry.core.nostr.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** vectors/way-in.json (belfry-ios af85497): what the app shows after each answer of the new way in. */
class WayInVectorsTest {
    private val v = Vectors.load("way-in.json")
    private val domain = v.s("domain")!!

    @Test fun cases() {
        val cases = (v["cases"] as JsonArray).map { it as JsonObject }
        assertTrue(cases.size >= 40)
        for (c in cases) {
            val name = c.s("name")
            val o = WayIn.next(c.s("call")!!, c["status"]!!.jsonPrimitive.content.toInt(), c["body"] as? JsonObject, domain)
            assertEquals(c.s("screen")!!.uppercase(), o.screen.name, name)
            assertEquals(c.s("message"), o.message, "$name: message")
            assertEquals((c["clears_code"] as? JsonPrimitive)?.booleanOrNull ?: false, o.clearsCode, "$name: clears_code")
            if (c.containsKey("suggestions")) assertEquals((c["suggestions"] as JsonArray).map { it.jsonPrimitive.content }, o.suggestions, "$name: suggestions")
            if (c.containsKey("proof")) assertEquals(c.s("proof"), o.proof, "$name: proof")
            if (c.containsKey("nip05")) assertEquals(c["nip05"].takeIf { it !is JsonNull }?.jsonPrimitive?.content, o.nip05, "$name: nip05")
            if (c.containsKey("pending")) assertEquals((c["pending"] as JsonObject).mapValues { it.value.jsonPrimitive.content }, o.pending, "$name: pending")
        }
    }

    @Test fun usernameLines() {
        for (c in (v["username_check"] as JsonArray).map { it as JsonObject }) {
            assertEquals(c.s("line"), WayIn.usernameLine(c["body"] as JsonObject, domain), c.s("name"))
        }
    }
}
