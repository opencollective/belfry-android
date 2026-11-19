package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Names, never ids (SPEC 19.43). */
class NamesTest {
    private val me = "a".repeat(64)
    private val ben = "b".repeat(64)
    private fun world(events: List<Event>) = World(events, emptyList(), WorldContext(me = me, domain = "belfry.example", communityName = "Belfry", zone = ZoneId.of("UTC")), null)

    @Test fun aGroupWithoutItsMetadataIsNotNamedByItsId() {
        val msg = Event("1".repeat(64), ben, 100, 9, listOf(listOf("h", "issues-5fce")), "hello")
        val g = world(listOf(msg)).group("issues-5fce")!!
        assertFalse(g.named)
        assertEquals("Channel", g.name)
        val meta = Event("2".repeat(64), "d".repeat(64), 50, 39000, listOf(listOf("d", "issues-5fce"), listOf("name", "issues")), "")
        val named = world(listOf(meta, msg)).group("issues-5fce")!!
        assertTrue(named.named)
        assertEquals("issues", named.name)
        assertEquals("this group", World.kindPhrase(ChannelType.POD))
    }

    @Test fun someoneWithoutAProfileIsSomebody() {
        assertEquals("Somebody", world(emptyList()).displayName(ben))
        assertEquals("You", world(emptyList()).displayName(me))
    }
}
