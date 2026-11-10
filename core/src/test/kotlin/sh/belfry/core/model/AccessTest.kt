package sh.belfry.core.model

import sh.belfry.core.nostr.Event
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guest, agent or member: join and label events, overruled by the register (whois) when it answered. */
class AccessTest {
    private val me = "aa".repeat(32)
    private val groupsKey = "99".repeat(32)
    private val ana = "a1".repeat(32)   // a guest of #general
    private val bot = "b1".repeat(32)   // a guest agent
    private val kofi = "c1".repeat(32)  // a member
    private val wren = "d1".repeat(32)  // kofi's own agent

    private fun ev(id: String, kind: Int, tags: List<List<String>>, pubkey: String = groupsKey, at: Long = 10) = Event(id, pubkey, at, kind, tags, "")
    private val events = listOf(
        ev("m", 39000, listOf(listOf("d", "general"), listOf("name", "general"))),
        ev("p", 39000, listOf(listOf("d", "pod-x"), listOf("name", "Pod"), listOf("closed"))),
        ev("j1", 9000, listOf(listOf("h", "general"), listOf("p", ana, "member"), listOf("invited-by", kofi))),
        ev("j2", 9000, listOf(listOf("h", "general"), listOf("p", bot, "member"), listOf("invited-by", kofi), listOf("agent", "1"))),
        ev("j3", 9000, listOf(listOf("h", "general"), listOf("p", wren, "member"), listOf("invited-by", kofi), listOf("agent", "1"))),
        ev("l3", 1985, listOf(listOf("h", "general"), listOf("L", World.GUEST_NS), listOf("l", "member", World.GUEST_NS), listOf("p", wren))),
        ev("k", 9, listOf(listOf("h", "general")), pubkey = kofi, at = 20),
    )
    private fun world(kinds: Map<String, String> = emptyMap()) =
        World(events, emptyList(), WorldContext(me = me, agents = mapOf(wren to kofi), kinds = kinds, zone = ZoneOffset.UTC))

    @Test
    fun eventsDecideWithoutTheRegister() {
        val w = world()
        assertTrue(w.isGuest(ana))
        assertTrue(w.isGuest(bot) && w.isAgent(bot))
        assertFalse(w.isGuest(wren))
        assertTrue(w.isAgent(wren))
        assertEquals(kofi, w.agentOwner(wren))
        assertFalse(w.isGuest(kofi))
        assertEquals(setOf(ana, bot, wren), w.guestJoinKeys)
        assertEquals("Agent, a guest", w.kindLabel(bot))
        assertEquals("Guest", w.kindLabel(ana))
        // an open channel: members read it, guests of other channels don't; a closed one: only its people
        assertTrue(w.inChannel(w.group("general"), kofi))
        assertFalse(w.inChannel(w.group("pod-x"), kofi))
    }

    @Test
    fun theRegisterOverrulesTheEvents() {
        // ana became a member without a label; the bot's guest pass ended (left out of the answer)
        val w = world(mapOf(ana to "member", wren to "agent", kofi to "member"))
        assertFalse(w.isGuest(ana))
        assertEquals(null, w.kindLabel(ana))
        assertTrue(w.guestJoins("general").first { it.pubkey == ana }.member)
        assertTrue(w.guestJoinsOf(ana).isEmpty())
        assertTrue(w.isGuest(bot))
        // the register says guest although a label said member
        val v = world(mapOf(wren to "guest"))
        assertTrue(v.isGuest(wren))
        assertFalse(v.guestJoins("general").first { it.pubkey == wren }.member)
    }
}
