package sh.belfry.core

import sh.belfry.core.crypto.Bech32
import sh.belfry.core.nostr.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoundationTest {
    @Test
    fun vectorsAreThere() {
        assertTrue(Vectors.load("events.json").isNotEmpty())
    }

    @Test
    fun bech32RoundTrip() {
        val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        val npub = Bech32.npub(hex)
        assertEquals("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6", npub)
        assertEquals(hex, Bech32.npubToHex(npub))
        assertNull(Bech32.npubToHex(npub.dropLast(1) + "q"))
    }

    @Test
    fun serializationEscapesLikeJavaScript() {
        val s = Event.serialize("ab", 1, 9, listOf(listOf("h", "a\"b")), "line\nnext\u0001 é 😀 </x>")
        assertEquals("[0,\"ab\",1,9,[[\"h\",\"a\\\"b\"]],\"line\\nnext\\u0001 é 😀 </x>\"]", s)
    }
}
