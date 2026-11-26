package sh.belfry.core.net

import kotlinx.coroutines.runBlocking
import sh.belfry.core.model.World
import sh.belfry.core.model.WorldContext
import sh.belfry.core.nostr.Event
import sh.belfry.core.store.MemoryStore
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC 19.94: a group's relay metadata (39000–39003) counts only when signed by the groups relay's own key, its NIP-11
 * `self` (read once per relay URL, kept, refreshed at most daily); unknown → shown as before, never trusted.
 */
class RelayKeyTest {
    private val relay = "d".repeat(64)
    private val operator = "c".repeat(64)
    private val me = "a".repeat(64)
    private val ben = "b".repeat(64)
    private val mallory = "e".repeat(64)
    private val g = "pod-garden-00"
    private var n = 0

    private fun ev(pk: String, kind: Int, at: Long, tags: List<List<String>>): Event {
        n++
        return Event(n.toString(16).padStart(64, '0'), pk, at, kind, tags, "")
    }

    private fun world(events: List<Event>, key: String?) =
        World(events, emptyList(), WorldContext(me = me, domain = "belfry.example", zone = ZoneId.of("UTC"), groupsRelayKey = key), null)

    /** The relay's own metadata of a private group, and a member's newer forgeries of all four kinds. */
    private fun events(): List<Event> = listOf(
        ev(relay, 39000, 100, listOf(listOf("d", g), listOf("name", "Garden"), listOf("closed"), listOf("private"))),
        ev(relay, 39001, 100, listOf(listOf("d", g), listOf("p", me, "admin"))),
        ev(relay, 39002, 100, listOf(listOf("d", g), listOf("p", me), listOf("p", ben))),
        ev(relay, 39003, 100, listOf(listOf("d", g), listOf("role", "admin"))),
        ev(mallory, 39000, 200, listOf(listOf("d", g), listOf("name", "Hijacked"), listOf("open"), listOf("public"))),
        ev(mallory, 39001, 200, listOf(listOf("d", g), listOf("p", mallory, "admin"))),
        ev(mallory, 39002, 200, listOf(listOf("d", g), listOf("p", me), listOf("p", ben), listOf("p", mallory))),
        ev(mallory, 39003, 200, listOf(listOf("d", g), listOf("role", "owner"))),
    )

    @Test fun withTheRelayKeyKnownOnlyItsMetadataCounts() {
        val w = world(events(), relay)
        assertTrue(w.groupMetaTrusted)
        val grp = w.group(g)!!
        assertEquals("Garden", grp.name, "the member's newer 39000 is ignored")
        assertTrue(grp.closed && grp.isPrivate, "access and private from the relay's 39000 only")
        assertEquals(setOf(me, ben), grp.members, "the member's newer 39002 is ignored")
        assertFalse(mallory in grp.admins, "the member's 39001 is ignored")
        assertTrue(me in grp.admins)
        assertEquals(100L to setOf(me, ben), w.relayMembers(g))
        assertEquals(relay, w.metaAuthors[g])
        assertTrue(w.all.none { it.pubkey == mallory }, "none of 39000–39003 by another author is in the world at all")
    }

    @Test fun theRelaySignedNewerOneCounts() {
        val newer = ev(relay, 39002, 300, listOf(listOf("d", g), listOf("p", me)))
        val w = world(events() + newer, relay)
        assertEquals(setOf(me), w.group(g)!!.members)
        assertEquals(300L to setOf(me), w.relayMembers(g))
    }

    @Test fun keyUnknownReadsAsBeforeButIsNotTrusted() {
        val w = world(events(), null)
        assertFalse(w.groupMetaTrusted)
        assertEquals("Hijacked", w.group(g)!!.name, "today's reading: the newest wins")
        assertTrue(mallory in w.group(g)!!.members)
        assertNull(w.relayMembers(g), "no trusted member list")
        // another key known: nothing of this relay's counts either
        val other = world(events(), operator)
        assertTrue(other.all.none { it.kind in 39000..39003 })
    }

    // ---- NIP-11 ---------------------------------------------------------------------------------------------

    @Test fun nip11Parse() {
        assertEquals(RelayInfo.Read.Key(relay), RelayInfo.parse("""{"name":"groups","pubkey":"$operator","self":"$relay"}"""), "both fields: self")
        assertEquals(RelayInfo.Read.Key(relay), RelayInfo.parse("""{"self":"${relay.uppercase()}"}"""), "hex in any case, kept lower case")
        assertEquals(RelayInfo.Read.NoKey, RelayInfo.parse("""{"name":"groups","pubkey":"$operator"}"""), "only pubkey (the operator): unknown")
        assertEquals(RelayInfo.Read.NoKey, RelayInfo.parse("""{"self":"abc"}"""))
        assertEquals(RelayInfo.Read.NoKey, RelayInfo.parse("""{"self":"${"g".repeat(64)}"}"""))
        assertEquals(RelayInfo.Read.NoKey, RelayInfo.parse("""{"self":42}"""))
        assertEquals(RelayInfo.Read.NoKey, RelayInfo.parse("""{"self":["$relay"]}"""))
        assertEquals(RelayInfo.Read.NoAnswer, RelayInfo.parse("<html>not json</html>"))
        assertEquals(RelayInfo.Read.NoAnswer, RelayInfo.parse("""["$relay"]"""))
        assertEquals(RelayInfo.Read.NoAnswer, RelayInfo.parse(""))
    }

    @Test fun nip11Address() {
        assertEquals("https://groups-relay.belfry.example/", RelayInfo.httpUrl("wss://groups-relay.belfry.example"))
        assertEquals("https://relay.example/groups", RelayInfo.httpUrl("wss://relay.example/groups"))
        assertNull(RelayInfo.httpUrl("ws://localhost:1234"), "a plain ws:// relay is never trusted")
        assertNull(RelayInfo.httpUrl("https://relay.example"))
        assertNull(RelayInfo.httpUrl(""))
    }

    @Test fun theCacheKeepsRefreshesDailyAndBacksOff() = runBlocking<Unit> {
        val store = MemoryStore()
        var now = 1_000_000_000L
        var answer: RelayInfo.Read = RelayInfo.Read.NoAnswer
        var reads = 0
        fun cache() = RelayKeyCache(store, { now }) { reads++; answer }
        val url = "wss://groups-relay.belfry.example"
        val c = cache()
        assertNull(c.known(url))
        // offline: unknown, and not asked again before the back-off
        assertNull(c.refresh(url))
        assertEquals(1, reads)
        assertNull(c.refresh(url))
        assertEquals(1, reads, "backing off")
        now += RelayKeyCache.FIRST_RETRY_MS
        answer = RelayInfo.Read.Key(relay)
        assertEquals(relay, c.refresh(url))
        assertEquals(2, reads)
        // kept: a new session (a restart) knows it without asking
        assertEquals(relay, cache().known(url))
        assertEquals(relay, cache().refresh(url))
        assertEquals(2, reads, "read at most once a day")
        // a day later it is read again; a failure then keeps the key known
        now += RelayKeyCache.REFRESH_MS
        answer = RelayInfo.Read.NoAnswer
        assertEquals(relay, c.refresh(url))
        assertEquals(3, reads)
        // the back-off grows: 30 s, then 60 s
        now += RelayKeyCache.FIRST_RETRY_MS
        assertEquals(relay, c.refresh(url))
        assertEquals(4, reads)
        now += RelayKeyCache.FIRST_RETRY_MS
        c.refresh(url)
        assertEquals(4, reads, "the second wait is longer")
        now += RelayKeyCache.FIRST_RETRY_MS
        // a document that answers without a valid `self` forgets the key
        answer = RelayInfo.Read.NoKey
        assertNull(c.refresh(url))
        assertEquals(5, reads)
        assertNull(cache().known(url))
        // one URL's key is never another's
        now += RelayKeyCache.MAX_RETRY_MS
        answer = RelayInfo.Read.Key(relay)
        c.refresh(url)
        assertNull(c.known("wss://other.example"))
        // a stored value that isn't a key is no key
        store.set(RelayKeyCache.K_PREFIX + "wss://other.example", """{"self":"nope","at":1}""")
        assertNull(c.known("wss://other.example"))
    }
}
