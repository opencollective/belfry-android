package sh.belfry.core.push

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import sh.belfry.core.Vectors
import sh.belfry.core.crypto.B64
import sh.belfry.core.crypto.Nip44
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Push payloads (vectors/push.json, made with the web chat's nostr-tools). */
class PushTest {
    private val v = Vectors.load("push.json")
    private val key = v.s("key")!!
    private val domain = v.s("signed_in")!!

    @Test
    fun aRawConversationKeyIsNip44WithoutEcdh() {
        val k = B64.decode(key)
        val nonce = ByteArray(32) { it.toByte() }
        val sealed = Nip44.encryptWithKey("hello", k, nonce)
        assertEquals(Nip44.encrypt("hello", k, nonce), sealed)
        assertEquals("hello", Nip44.decryptWithKey(sealed, k))
        assertFailsWith<Exception> { Nip44.decryptWithKey(sealed, ByteArray(16)) }
        // a new device key is 32 random bytes in base64
        assertEquals(32, B64.decode(Push.newDeviceKey()).size)
        assertTrue(Push.newDeviceKey() != Push.newDeviceKey())
    }

    @Test
    fun eachPayloadBecomesItsNotification() {
        val cases = (v["cases"] as JsonArray).map { it as JsonObject }
        assertTrue(cases.size >= 8)
        for (c in cases) {
            val name = c.s("name")
            val note = Push.open(c.s("payload")!!, key)
            val e = c["expect"]
            if (e == null || e is JsonNull) { assertNull(note, name); continue }
            val want = e.obj()!!
            assertNotNull(note, name)
            assertEquals(want.s("channel"), note.androidChannel.id, name)
            assertEquals(want.s("title"), note.title, name)
            assertEquals(want.s("body"), note.body, name)
            val open = want["open"].obj()!!
            val got = Push.target(note, domain)
            when (open.s("kind")) {
                "channel" -> assertEquals(PushOpen.Channel(open.s("channel")!!, open["message"]?.takeIf { it !is JsonNull }?.let { open.s("message") }), got, name)
                "thread" -> assertEquals(PushOpen.Thread(open.s("channel")!!, open.s("root")!!), got, name)
                "dms" -> assertEquals(PushOpen.Dms, got, name)
                else -> assertEquals(PushOpen.Home, got, name)
            }
        }
    }

    @Test
    fun myOwnDmCopyAndAnOpenConversationStayQuiet() {
        val cases = (v["quiet"] as JsonArray).map { it as JsonObject }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val note = PushNote(c.s("type")!!, domain, "t", "b", null, null, null)
            val got = Push.quiet(note, c["now_ms"].long()!!, c["last_dm_sent_ms"].long()!!, c["dms_open"].bool()!!)
            assertEquals(c["quiet"].bool(), got, c.toString())
        }
    }

    @Test
    fun anotherDevicesKeyOpensNothing() {
        val first = (v["cases"] as JsonArray)[0] as JsonObject
        assertNull(Push.open(first.s("payload")!!, v.s("wrong_key")!!))
        assertNull(Push.open(first.s("payload")!!, "not base64!"))
        assertNull(Push.open("garbage", key))
    }

    @Test
    fun settingsMergeLikeTheBelfry() {
        val s = NotificationSettings()
        assertEquals(PushLevel.DEFAULT, s.level("general"))
        val a = s.merged(NotificationSettings.setLevel("general", PushLevel.ALL)).merged(NotificationSettings.setSwitch("dms", false))
        assertEquals(PushLevel.ALL, a.level("general"))
        assertEquals(false, a.dms)
        assertEquals(true, a.mentions)
        val b = a.merged(NotificationSettings.setLevel("general", PushLevel.DEFAULT))
        assertEquals(PushLevel.DEFAULT, b.level("general"))
        assertEquals(a, NotificationSettings.parse(a.toJson()))
        assertEquals(PushLevel.OFF, NotificationSettings.parse(sh.belfry.core.nostr.parseJsonOrNull("""{"channels":{"random":"off","bad id!":"all"}}""") as JsonObject).level("random"))
    }
}
