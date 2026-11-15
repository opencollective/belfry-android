package sh.belfry.core.read

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import sh.belfry.core.engine.ReadSync
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.store.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two devices of one person sharing their read state through a fake relay (SPEC 19.27). */
class ReadSyncTest {
    private val signer = LocalSigner("7f".repeat(32))
    private val relay = ArrayList<Event>() // what the community relay holds (all versions, for the test)
    private var now = 1_763_200_000L

    private inner class Device(seed: Map<String, Long> = emptyMap(), var shared: Boolean = true) {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = ArrayList<Event>()
        val sync = ReadSync(store, signer.pubkey, { now }, scope, object : ReadSync.Host {
            override fun shared() = shared
            override suspend fun encryptToSelf(text: String) = signer.nip44Encrypt(signer.pubkey, text)
            override suspend fun decryptFromSelf(payload: String) = signer.nip44Decrypt(signer.pubkey, payload)
            override suspend fun publish(content: String, minCreatedAt: Long) {
                val e = signer.sign(EventTemplate(ReadState.KIND, content, listOf(listOf("d", ReadState.READ_D)), maxOf(minCreatedAt, now)))
                synchronized(relay) { relay.add(e) }
                synchronized(published) { published.add(e) }
            }
        }, seedD = { seed })
    }

    private suspend fun until(what: String, ok: () -> Boolean) {
        try { withTimeout(20_000) { while (!ok()) delay(50) } } catch (e: Exception) { throw AssertionError("timed out: $what") }
    }

    @Test
    fun twoDevices() = runBlocking {
        val phone = Device(seed = mapOf("n:abc" to now - 500))
        val laptop = Device()
        try {
            // first run: s = now, the old DM marks seeded
            assertEquals(now, phone.sync.state.value.s)
            assertEquals(now - 500, phone.sync.state.value.d["n:abc"])
            // nothing is published before the relay answered
            phone.sync.mark('c', "general", now + 10)
            delay(300)
            assertTrue(phone.published.isEmpty())
            phone.sync.relayAnswered()
            until("phone publishes") { phone.published.isNotEmpty() }
            // the laptop gets it, merges, has its own mark, publishes the merge
            now += 20
            laptop.sync.relayAnswered()
            laptop.sync.offer(synchronized(relay) { relay.toList() })
            until("laptop merged") { laptop.sync.state.value.c["general"] == now - 10 }
            assertEquals(now - 520, laptop.sync.state.value.d["n:abc"])
            laptop.sync.mark('d', "n:abc", now)
            until("laptop publishes") { laptop.published.isNotEmpty() }
            phone.sync.offer(synchronized(relay) { relay.toList() })
            until("phone has the laptop's mark") { phone.sync.state.value.d["n:abc"] == now }
            // the phone's state now equals the relay's: nothing more to publish
            delay(3_500)
            assertEquals(1, phone.published.size)
            // a guest device never publishes
            val guest = Device(shared = false)
            guest.sync.relayAnswered()
            guest.sync.mark('c', "general", now + 99)
            delay(3_500)
            assertTrue(guest.published.isEmpty())
            guest.scope.cancel()
        } finally {
            phone.scope.cancel(); laptop.scope.cancel()
        }
    }
}
