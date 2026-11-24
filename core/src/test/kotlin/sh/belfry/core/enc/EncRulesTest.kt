package sh.belfry.core.enc

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Keys
import sh.belfry.core.nostr.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules of format 3 the vectors don't cover (SPEC 19.81). */
class EncRulesTest {
    private val someone = "a".repeat(64)

    private fun ev(kind: Int, tags: List<List<String>> = emptyList(), pubkey: String = someone) = Event("", pubkey, 1, kind, tags, "")

    @Test fun clearInAPrivateGroup() {
        val groupsKey = "b".repeat(64)
        for (k in listOf(9000, 9001, 9002, 9005, 9007, 9008, 9021, 9022, 39000, 39001, 39002, 39003)) assertTrue(EncRules.clearInGroup(ev(k), groupsKey), "$k")
        assertTrue(EncRules.clearInGroup(ev(1985, pubkey = groupsKey), groupsKey))
        assertFalse(EncRules.clearInGroup(ev(1985), groupsKey))
        assertFalse(EncRules.clearInGroup(ev(1985), "")) // an unknown groups key never matches
        assertTrue(EncRules.clearInGroup(ev(1985, listOf(listOf("L", "belfry/vouch"))), groupsKey))
        assertTrue(EncRules.clearInGroup(ev(30078, listOf(listOf("d", "belfry:inbox:general"))), groupsKey))
        assertTrue(EncRules.clearInGroup(ev(30078, listOf(listOf("d", "seen:general"))), groupsKey))
        assertFalse(EncRules.clearInGroup(ev(30078, listOf(listOf("d", "belfry:other"))), groupsKey))
        for (k in listOf(9, 1111, 7, 1010, 5, 31923, 1068)) assertFalse(EncRules.clearInGroup(ev(k), groupsKey), "$k")
    }

    @Test fun jsonAsJavaScriptWritesIt() {
        val o = buildJsonObject { put("b", JsonPrimitive("q\"\\\n\u0001é👍/")); put("a", JsonPrimitive(3100L)) }
        assertEquals("{\"b\":\"q\\\"\\\\\\n\\u0001é👍/\",\"a\":3100}", EncJson.stringify(o))
    }

    @Test fun strictEvents() {
        assertNull(EncJson.rumor(buildJsonObject { put("pubkey", JsonPrimitive(someone)); put("created_at", JsonPrimitive("1")); put("kind", JsonPrimitive(1)); put("content", JsonPrimitive("")); put("tags", EncJson.tags(emptyList())) }))
        assertNull(EncJson.rumor(buildJsonObject { put("pubkey", JsonPrimitive("x")); put("created_at", JsonPrimitive(1)); put("kind", JsonPrimitive(1)); put("content", JsonPrimitive("")); put("tags", EncJson.tags(emptyList())) }))
        val r = EncJson.rumor(buildJsonObject { put("pubkey", JsonPrimitive(someone)); put("created_at", JsonPrimitive(1)); put("kind", JsonPrimitive(1)); put("content", JsonPrimitive("")); put("tags", EncJson.tags(emptyList())); put("id", JsonPrimitive("wrong")) })
        assertEquals(Enc.withId(ev(1)).id, r?.id)
    }

    @Test fun aMessageTooLongIsRefusedWithoutItsText() {
        val device = Keys.generateSecret()
        val k = EncKeys.newKey()
        val r = Enc.withId(Event("", Enc.pub(device), 1, 14, emptyList(), "x".repeat(Enc.MAX_RUMOR)))
        val e = assertFailsWith<EncException> { EncMessages.peopleMessage(device, r, k.pub, listOf(r.pubkey), r.pubkey) }
        assertFalse(e.message!!.contains("xxxx"))
    }

    @Test fun aRoundTrip() {
        val device = Keys.generateSecret()
        val me = Enc.pub(device)
        val k = EncKeys.newKey()
        val r = Enc.withId(Event("", me, 1, 14, listOf(listOf("p", me)), "hello"))
        val copy = EncMessages.peopleMessage(device, r, k.pub, listOf(me), me).single()
        assertTrue(copy.tags.contains(Enc.SILENT))
        assertEquals(EncKeys.alias(k.pub, me), EncMessages.named(copy))
        val o = EncMessages.openPeopleMessage(k.secret, copy)
        assertEquals(r.id, o.rumor.id)
        assertFailsWith<EncException> { EncMessages.openPeopleMessage(EncKeys.newKey().secret, copy) }
    }
}
