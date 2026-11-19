package sh.belfry.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import sh.belfry.core.net.LoginLink
import sh.belfry.core.nostr.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** vectors/login-links.json (belfry-ios 23670e8): "Link your phone" links. */
class LoginLinkTest {
    @Test
    fun asTheVectorsSay() {
        val cases = (Vectors.load("login-links.json")["cases"] as JsonArray).map { it as JsonObject }
        assertTrue(cases.size >= 24)
        for (c in cases) {
            val url = c.s("url")!!
            val got = LoginLink.parse(url)
            assertEquals(c["valid"]!!.jsonPrimitive.boolean, got != null, url)
            if (got != null) assertEquals(c.s("address"), got.address, url)
        }
    }
}
