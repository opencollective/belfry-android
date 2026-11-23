package sh.belfry.core.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Signed out, an event's address (SPEC 19.71): the web's unsigned `GET <api>/events/<link>`. */
class EventDoorTest {
    private val server = MockWebServer()

    @AfterTest fun tearDown() { try { server.shutdown() } catch (e: Exception) { } }

    private fun api() = BelfryApi(Community.defaults("example.org").copy(apiBase = server.url("/api/v1").toString().trimEnd('/')), OkHttpClient())
    private fun answer(status: Int, body: String = "{}") = server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    @Test
    fun forbiddenIsForMembersAndNothingIsSigned() = runBlocking<Unit> {
        answer(403, """{"error":"members_only"}""")
        assertEquals(EventDoor.MEMBERS_ONLY, api().eventDoor("members-supper"))
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/events/members-supper", req.path)
        assertNull(req.getHeader("Authorization"))
    }

    @Test
    fun theOtherAnswers() = runBlocking<Unit> {
        answer(200, """{"d":"abc123","title":"Garden day"}""")
        assertEquals(EventDoor.OPEN, api().eventDoor("garden-day"))
        answer(404, """{"error":"not_found"}""")
        assertEquals(EventDoor.MISSING, api().eventDoor("nothing"))
        answer(500)
        assertEquals("http", assertFailsWith<ApiException> { api().eventDoor("garden-day") }.code)
    }
}
