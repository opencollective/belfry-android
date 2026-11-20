package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.Vectors
import sh.belfry.core.nostr.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC 19.44: references in a message (vectors/references.json, shared with iOS and the web). */
class ReferencesTest {
    private val v = Vectors.load("references.json")

    private fun json(t: RefTarget): JsonObject = buildJsonObject {
        when (t) {
            is RefTarget.Group -> { put("type", JsonPrimitive("group")); put("id", JsonPrimitive(t.id)) }
            is RefTarget.Event -> { put("type", JsonPrimitive("event")); t.coord?.let { put("coord", JsonPrimitive(it)) }; t.link?.let { put("link", JsonPrimitive(it)) } }
            is RefTarget.Proposal -> { put("type", JsonPrimitive("proposal")); put("coord", JsonPrimitive(t.coord)) }
            is RefTarget.Todo -> { put("type", JsonPrimitive("todo")); put("group", JsonPrimitive(t.group)); put("task", JsonPrimitive(t.task)) }
            is RefTarget.Message -> { put("type", JsonPrimitive("message")); t.group?.let { put("group", JsonPrimitive(it)) }; t.link?.let { put("link", JsonPrimitive(it)) }; put("id", JsonPrimitive(t.id)) }
            is RefTarget.Person -> { put("type", JsonPrimitive("person")); put("pubkey", JsonPrimitive(t.pubkey)) }
            is RefTarget.Drop -> { put("type", JsonPrimitive("drop")); put("token", JsonPrimitive(t.token)) }
        }
    }

    private fun json(p: RefPart): JsonObject = when (p) {
        is RefPart.Text -> buildJsonObject { put("text", JsonPrimitive(p.text)) }
        is RefPart.Ref -> buildJsonObject { put("ref", json(p.target)) }
        is RefPart.Box -> buildJsonObject { put("box", json(p.target)) }
    }

    @Test
    fun partsAsTheVectorsSay() {
        val domain = v.s("domain")!!
        val cases = (v["cases"] as JsonArray).map { it as JsonObject }
        assertTrue(cases.size >= 43)
        for (c in cases) {
            val got = References.parts(c.s("text")!!, domain).map { json(it) }
            assertEquals(c["parts"], JsonArray(got), c.s("name"))
        }
    }

    /** One-line previews: each reference as its known name (`known`, by refKey), else its generic word. */
    @Test
    fun plainAsTheVectorsSay() {
        val domain = v.s("domain")!!
        val known = (v["known"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        val cases = (v["cases"] as JsonArray).map { it as JsonObject }
        assertTrue(cases.size >= 43)
        for (c in cases) {
            val got = References.plain(c.s("text")!!, domain) { known[References.refKey(it)] }
            assertEquals(c.s("plain"), got, c.s("name"))
        }
    }

    @Test
    fun endBoxGivesTheTextBefore() {
        val (before, box) = References.endBox("Seed talk carries on here https://chat.riverside.example/#/c/garden  ", "riverside.example")!!
        assertEquals("Seed talk carries on here", before)
        assertEquals(RefTarget.Group("garden"), box.target)
    }

    @Test
    fun anATagForEventAndProposalNaddrs() {
        val ev = "nostr:" + sh.belfry.core.crypto.Nip19.naddr(sh.belfry.core.crypto.Nip19.Addr(31923, "a".repeat(64), "2026-ab12cd"))
        val tags = Actions.postMessage("g", "Come $ev").tags
        assertTrue(tags.contains(listOf("a", "31923:${"a".repeat(64)}:2026-ab12cd")))
    }
}
