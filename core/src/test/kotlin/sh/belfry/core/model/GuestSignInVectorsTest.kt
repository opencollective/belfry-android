package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import sh.belfry.core.Vectors
import sh.belfry.core.nostr.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** vectors/guest-signin.json (belfry-ios 5d1ea9a): the guest's code signs an existing account in (canvas onboarding/guest-code v41). */
class GuestSignInVectorsTest {
    private val v = Vectors.load("guest-signin.json")

    private fun plain(e: JsonElement?): Any? = when (e) {
        null, JsonNull -> null
        is JsonObject -> e.mapValues { plain(it.value) }
        is JsonArray -> e.map { plain(it) }
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.intOrNull ?: e.content
    }

    @Test
    fun theRequestBody() {
        val r = v["request"] as JsonObject
        val t = r["typed"] as JsonObject
        val client = t.s("client")!!
        val body = GuestSignInRule.body(t.s("email")!!, t.s("code")!!, t.s("name"), t.s("session_key")!!, client)
        assertEquals(plain(r["body"]), body)
        assertEquals(plain(r["body_without_name"]), GuestSignInRule.body(t.s("email")!!, t.s("code")!!, null, t.s("session_key")!!, client))
    }

    @Test
    fun whatTheAppDoes() {
        val domain = v.s("domain")!!
        val typed = ((v["request"] as JsonObject)["typed"] as JsonObject).s("email")!!
        val answers = (v["answers"] as JsonArray).map { it as JsonObject }
        assertTrue(answers.size >= 13)
        for (a in answers) {
            val name = a.s("name")
            @Suppress("UNCHECKED_CAST")
            val o = GuestSignInRule.next(a["status"]!!.jsonPrimitive.content.toInt(), plain(a["body"]) as Map<String, Any?>?, typed, domain)
            assertEquals(a.s("next")!!.uppercase(), o.next.name, name)
            if (a.containsKey("address")) assertEquals(a.s("address"), o.address, "$name: address")
            if (a.containsKey("open")) assertEquals(plain(a["open"]) as String?, o.open, "$name: open")
            if (a.containsKey("moved")) assertEquals(a["moved"]!!.jsonPrimitive.booleanOrNull, o.moved, "$name: moved")
        }
    }
}
