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
}
