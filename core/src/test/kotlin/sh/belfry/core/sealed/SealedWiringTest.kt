package sh.belfry.core.sealed

import sh.belfry.core.engine.MemoryVault
import sh.belfry.core.engine.SealedKeyStore
import sh.belfry.core.engine.Slots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wiring's pure parts: the key store per account (sealed.md, 294c94f (11)). */
class SealedWiringTest {
    private val pair = KeyChain.newLetterbox()

    @Test fun keysNameTheirAccount() {
        val vault = MemoryVault()
        val name = Slots.key(SealedKeyStore.V_KEYS, "a2")
        val alice = "aa".repeat(32); val bob = "bb".repeat(32)
        SealedKeyStore(vault, name, alice).save(mapOf(pair))
        assertEquals(mapOf(pair), SealedKeyStore(vault, name, alice).load())
        assertTrue(SealedKeyStore(vault, name, bob).load().isEmpty(), "another account's keys are never used")
    }

    @Test fun wipedAtSignOutAndNoLateWrite() {
        val vault = MemoryVault()
        val ks = SealedKeyStore(vault, SealedKeyStore.V_KEYS, "aa".repeat(32))
        ks.save(mapOf(pair))
        ks.wipe()
        assertNull(vault.get(SealedKeyStore.V_KEYS))
        ks.save(mapOf(pair)) // a task asleep before a rotation wakes up after sign-out
        assertNull(vault.get(SealedKeyStore.V_KEYS), "never written back after sign-out")
    }

    /** 6e0bc8e: never saved or loaded before the account key is known. */
    @Test fun noOwnerNoKeys() {
        val vault = MemoryVault()
        SealedKeyStore(vault, SealedKeyStore.V_KEYS, "").save(mapOf(pair))
        assertNull(vault.get(SealedKeyStore.V_KEYS))
    }

    /** The review: paging by 300 from 0 on a relay that caps limit; stops on a page with nothing new; CLOSED = not complete. */
    @Test fun pagingOnACappedRelay() = kotlinx.coroutines.runBlocking<Unit> {
        val relay = SealedEngineTest.FakeRelay()
        val author = sh.belfry.core.nostr.LocalSigner.random()
        repeat(750) { relay.add(author.signNow(sh.belfry.core.nostr.EventTemplate(1059, "x$it", emptyList(), 1_795_000_000L + it / 3))) }
        val got = sh.belfry.core.engine.Paging.all { f -> relay.page(f) }(sh.belfry.core.net.Filters.of(kinds = listOf(1059)))
        assertEquals(750, got.events.size, "every event, past the 300 cap")
        assertTrue(got.complete)
        relay.closing = true
        assertTrue(!sh.belfry.core.engine.Paging.all { f -> relay.page(f) }(sh.belfry.core.net.Filters.of(kinds = listOf(1059))).complete, "CLOSED is no answer")
    }

    /** The review's 12: revoking a device whose session is already gone is no error. */
    @Test fun revokeGoneSession() = kotlinx.coroutines.runBlocking<Unit> {
        val ports = sh.belfry.core.engine.SessionSealedPorts({ null }, { t -> sh.belfry.core.nostr.LocalSigner.random().signNow(t) }, { emptyList() }, { error("not called") },
            SealedKeyStore(MemoryVault(), "k", "aa".repeat(32)))
        ports.revoke("bb".repeat(32))
        assertNull(sh.belfry.core.engine.SessionSealedPorts({ null }, { t -> sh.belfry.core.nostr.LocalSigner.random().signNow(t) }, { error("offline") }, { }, SealedKeyStore(MemoryVault(), "k", "aa".repeat(32))).sessions(), "no answer is not \"no sessions\"")
    }

    /** belfry.json `"sealed": {"self_join": true}` (sealed.md §5.7): only the JSON `true` turns it on; a port that fails says "not read". */
    @Test fun selfJoinFlag() = kotlinx.coroutines.runBlocking<Unit> {
        fun flag(doc: String) = sh.belfry.core.net.Community.parse("belfry.example", doc).sealedSelfJoin
        assertTrue(flag("""{"sealed":{"self_join":true}}"""))
        for (off in listOf("""{}""", """{"sealed":{}}""", """{"sealed":{"self_join":false}}""", """{"sealed":{"self_join":"true"}}""", """{"sealed":{"self_join":1}}""", """{"sealed":true}""", """{"sealed":[{"self_join":true}]}""", """{"self_join":true}""", "not json"))
            assertTrue(!flag(off), off)
        val on = sh.belfry.core.net.Community.parse("belfry.example", """{"sealed":{"self_join":true}}""")
        assertTrue(sh.belfry.core.net.Community.fromJsonString(on.toJsonString())!!.sealedSelfJoin, "kept in the stored copy (the engine never reads it from there)")
        fun ports(now: suspend () -> Boolean?) = sh.belfry.core.engine.SessionSealedPorts({ null }, { t -> sh.belfry.core.nostr.LocalSigner.random().signNow(t) }, { emptyList() }, { },
            SealedKeyStore(MemoryVault(), "k", "aa".repeat(32)), selfJoinNow = now)
        assertEquals(true, ports { true }.selfJoin())
        assertEquals(false, ports { false }.selfJoin())
        assertNull(ports { throw java.io.IOException("offline") }.selfJoin(), "unreachable is not off: not in this pass")
    }
}
