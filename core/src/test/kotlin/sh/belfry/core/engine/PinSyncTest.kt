package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import sh.belfry.core.model.OneShape
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.store.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** My own pins (docs/one-shape.md §1): encrypted to myself, newest wins, guests keep them on the device. */
class PinSyncTest {
    private val signer = LocalSigner("5e".repeat(32))
    private val relay = ArrayList<Event>()
    private var now = 1_763_800_000L

    private inner class Device(var shared: Boolean = true) {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = ArrayList<Event>()
        val sync = PinSync(store, signer.pubkey, { now }, scope, object : PinSync.Host {
            override fun shared() = shared
            override suspend fun encryptToSelf(text: String) = signer.nip44Encrypt(signer.pubkey, text)
            override suspend fun decryptFromSelf(payload: String) = signer.nip44Decrypt(signer.pubkey, payload)
            override suspend fun publish(content: String, minCreatedAt: Long) {
                val e = signer.sign(EventTemplate(PinSync.KIND, content, listOf(listOf("d", OneShape.PINS_D)), maxOf(minCreatedAt, now)))
                synchronized(relay) { relay.add(e) }
                synchronized(published) { published.add(e) }
            }
        })
        fun stop() = scope.cancel()
    }

    private suspend fun until(what: String, ok: () -> Boolean) {
        try { withTimeout(20_000) { while (!ok()) delay(50) } } catch (e: Exception) { throw AssertionError("timed out: $what") }
    }

    private fun relayCopy() = synchronized(relay) { relay.toList() }

    @Test
    fun encryptedRoundTripBetweenTwoDevices() = runBlocking {
        val phone = Device()
        val tablet = Device()
        try {
            phone.sync.toggle("garden")
            phone.sync.toggle("kitchen")
            assertEquals(listOf("garden", "kitchen"), phone.sync.state.value.c)
            // nothing leaves before the community relay answered
            delay(2_000)
            assertTrue(phone.published.isEmpty())
            phone.sync.relayAnswered()
            until("phone publishes") { phone.published.isNotEmpty() }
            val e = phone.published.last()
            // the content is not readable on the relay, and decrypts to the agreed shape
            assertTrue(!e.content.contains("garden"))
            assertEquals(OneShape.pinsContent(listOf("garden", "kitchen")), signer.nip44Decrypt(signer.pubkey, e.content))
            assertEquals(listOf("garden", "kitchen"), PinSync.parse(signer.nip44Decrypt(signer.pubkey, e.content)))
            // the tablet takes it
            tablet.sync.relayAnswered()
            tablet.sync.offer(relayCopy())
            until("tablet has the pins") { tablet.sync.state.value.c == listOf("garden", "kitchen") }
            // and has nothing newer to publish
            delay(2_000)
            assertTrue(tablet.published.isEmpty())
            // a restart keeps them (the store)
            val again = PinSync(phone.store, signer.pubkey, { now }, phone.scope, object : PinSync.Host {
                override fun shared() = false
                override suspend fun encryptToSelf(text: String) = text
                override suspend fun decryptFromSelf(payload: String) = payload
                override suspend fun publish(content: String, minCreatedAt: Long) {}
            })
            assertEquals(listOf("garden", "kitchen"), again.state.value.c)
        } finally { phone.stop(); tablet.stop() }
    }

    @Test
    fun newestWins() = runBlocking {
        val phone = Device()
        val tablet = Device()
        try {
            phone.sync.relayAnswered(); tablet.sync.relayAnswered()
            phone.sync.toggle("garden")
            until("phone publishes") { phone.published.isNotEmpty() }
            now += 60
            // the tablet unpins it later (after it saw the phone's copy), and pins another
            tablet.sync.offer(relayCopy())
            until("tablet has garden") { tablet.sync.state.value.c == listOf("garden") }
            tablet.sync.toggle("garden")
            tablet.sync.toggle("hello")
            until("tablet publishes") { tablet.published.isNotEmpty() }
            phone.sync.offer(relayCopy())
            until("phone takes the newer list") { phone.sync.state.value.c == listOf("hello") }
            // an older copy arriving late changes nothing
            val old = signer.sign(EventTemplate(PinSync.KIND, signer.nip44Encrypt(signer.pubkey, OneShape.pinsContent(listOf("random"))), listOf(listOf("d", OneShape.PINS_D)), now - 3_600))
            phone.sync.offer(listOf(old))
            delay(1_000)
            assertEquals(listOf("hello"), phone.sync.state.value.c)
            // someone else's event, or another d, is ignored
            val other = LocalSigner("6f".repeat(32))
            val theirs = other.sign(EventTemplate(PinSync.KIND, other.nip44Encrypt(other.pubkey, OneShape.pinsContent(listOf("x"))), listOf(listOf("d", OneShape.PINS_D)), now + 999))
            phone.sync.offer(listOf(theirs))
            delay(500)
            assertEquals(listOf("hello"), phone.sync.state.value.c)
            assertNotEquals(0L, phone.sync.state.value.at)
        } finally { phone.stop(); tablet.stop() }
    }

    @Test
    fun guestsKeepThemOnTheDevice() = runBlocking {
        val guest = Device(shared = false)
        try {
            guest.sync.relayAnswered()
            guest.sync.toggle("general")
            delay(2_500)
            assertTrue(guest.published.isEmpty())
            assertEquals(listOf("general"), guest.sync.state.value.c)
            guest.sync.toggle("general")
            assertEquals(emptyList(), guest.sync.state.value.c)
        } finally { guest.stop() }
    }
}
