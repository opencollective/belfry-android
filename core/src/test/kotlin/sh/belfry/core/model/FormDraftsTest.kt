package sh.belfry.core.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import sh.belfry.core.engine.MemoryVault
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.store.MemoryStore
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A form's fields kept on the device until it is sent (web `useFormDraft`, 5ccc71f). */
class FormDraftsTest {
    private val now = 1_800_000_000L

    @Test
    fun fieldsRoundTripAndGoAfterThirtyDays() {
        val fields = mapOf("title" to "Garden day", "allDay" to "0", "description" to "")
        val text = FormDrafts.toJsonString(fields, now)
        assertEquals(fields, FormDrafts.parse(text, now + 60))
        assertNull(FormDrafts.parse(text, now + 30 * 86400L), "thirty days")
        assertNull(FormDrafts.parse(null, now))
        assertNull(FormDrafts.parse("not json", now))
        assertNull(FormDrafts.parse("""{"v":{"title":"x"}}""", now), "no date: not a draft")
        assertNull(FormDrafts.parse("""{"at":$now,"v":{}}""", now))
        // only text is read back
        assertEquals(mapOf("title" to "x"), FormDrafts.parse("""{"at":$now,"v":{"title":"x","n":3,"o":{}}}""", now))
        assertEquals(FormDrafts.MAX_FIELD, FormDrafts.parse(FormDrafts.toJsonString(mapOf("text" to "x".repeat(30_000)), now), now)!!["text"]!!.length)
        // the web's keys
        assertEquals("form:event:members", FormDrafts.storeKey(FormDrafts.event("members")))
        assertEquals("form:proposal:general", FormDrafts.storeKey(FormDrafts.proposal("general")))
        assertEquals("form:todo:general", FormDrafts.storeKey(FormDrafts.todo("general")))
        assertEquals("form:channel", FormDrafts.storeKey(FormDrafts.CHANNEL))
        assertEquals("form:meeting:general", FormDrafts.storeKey(FormDrafts.meeting("general")))
    }

    @Test
    fun theSessionKeepsThemPerForm() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val s = Onboarding(MemoryVault(), MemoryStore(), OkHttpClient(), scope, { now }, ZoneOffset.UTC).demo()
            suspend fun until(cond: suspend () -> Boolean) { var n = 0; while (!cond()) { delay(20); if (++n > 250) error("timed out") } }
            val key = FormDrafts.todo("general")
            assertNull(s.formDraft(key))
            s.saveFormDraft(key, mapOf("title" to "Book the room"))
            s.saveFormDraft(FormDrafts.CHANNEL, mapOf("name" to "Garden", "about" to ""))
            until { s.formDraft(FormDrafts.CHANNEL) != null }
            assertEquals(mapOf("title" to "Book the room"), s.formDraft(key))
            // a message's draft of the same channel is another thing
            assertNull(s.draft(ComposerDraft.channel("general")))
            // sent: forgotten
            s.saveFormDraft(key, null)
            until { s.formDraft(key) == null }
            assertEquals("Garden", s.formDraft(FormDrafts.CHANNEL)!!["name"])
            s.stop()
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }
}
