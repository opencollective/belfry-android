package sh.belfry.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Applying with no link (onboarding/apply-form; Belfry 8b0331d POST /apply): the body and the words for each refusal. */
class ApplyFormTest {
    @Test fun theBody() {
        val b = ApplyForm.body(" lina@example.org ", " I live on Mill Street ", listOf("a1", "b2"))
        assertEquals("lina@example.org", (b["email"] as JsonPrimitive).content)
        assertEquals("I live on Mill Street", (b["motivation"] as JsonPrimitive).content)
        assertEquals(listOf("a1", "b2"), (b["agreed"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals("", (b["website"] as JsonPrimitive).content)
        assertEquals(200, (ApplyForm.body("x".repeat(500), "y", emptyList())["email"] as JsonPrimitive).content.length)
    }

    @Test fun theWords() {
        fun err(code: String, message: String? = null) = buildJsonObject { put("error", JsonPrimitive(code)); if (message != null) put("message", JsonPrimitive(message)) }
        assertEquals(ApplyForm.UNTICKED, ApplyForm.error(400, err("unticked", "server words")))
        assertEquals(ApplyForm.MISSING, ApplyForm.error(400, err("missing")))
        assertEquals(ApplyForm.MISSING, ApplyForm.error(400, err("bad_request")))
        // refused: the register's own words, as they come (plain text, at most 300 characters)
        assertEquals("This address can't receive mail.", ApplyForm.error(400, err("refused", " This address can't receive mail. ")))
        assertEquals(300, ApplyForm.error(400, err("refused", "z".repeat(900))).length)
        assertEquals(ApplyForm.OTHER, ApplyForm.error(400, err("refused")))
        assertEquals(ApplyForm.BUSY, ApplyForm.error(429, null))
        assertEquals(ApplyForm.OTHER, ApplyForm.error(500, null))
    }
}
