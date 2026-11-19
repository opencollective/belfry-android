package sh.belfry.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import sh.belfry.core.net.MembersApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** membership.md (2026-11-19): request rows carry the username asked for and `verified`. */
class RequestNameTest {
    @Test fun usernameAndVerified() {
        val rows = Json.parseToJsonElement("""[{"id":1,"name":"Lina","motivation":"m","email":"lina.moreau@example.org","username":"lina","verified":true},{"id":2,"name":"Old","motivation":"m","username":"Not Valid!"}]""").jsonArray
        val r = MembersApi.parseRequests(rows)
        assertEquals("lina", r[0].username)
        assertTrue(r[0].verified)
        assertEquals("", r[1].username, "a username that breaks the rule is not shown")
    }
}
