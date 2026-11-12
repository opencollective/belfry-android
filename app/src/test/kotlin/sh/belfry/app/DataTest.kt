package sh.belfry.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import sh.belfry.app.data.Secrets
import sh.belfry.app.data.SqliteStore
import sh.belfry.core.nostr.Event
import sh.belfry.core.outbox.Attachment
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.Target

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DataTest {
    private fun ev(id: String, kind: Int, at: Long, content: String = "hi") =
        Event(id, "ab".repeat(32), at, kind, listOf(listOf("h", "general"), listOf("t", "é\"x")), content, "00".repeat(64))

    /** Half an hour of audio is 11 MB; Android reads a row through a window of about 2 MB. */
    @Test
    fun aBigBlobIsReadWhole() {
        val store = SqliteStore(ApplicationProvider.getApplicationContext(), name = null)
        val big = ByteArray(3 * 1024 * 1024 + 123) { (it * 31 + it / 1000).toByte() }
        store.putBlob("big", big)
        assertArrayEquals(big, store.blob("big"))
        val exact = ByteArray(1024 * 1024) { it.toByte() }
        store.putBlob("exact", exact)
        assertArrayEquals(exact, store.blob("exact"))
        store.putBlob("empty", ByteArray(0))
        assertArrayEquals(ByteArray(0), store.blob("empty"))
        assertNull(store.blob("none"))
        store.close()
    }

    @Test
    fun sqliteStoreRoundTrip() {
        val store = SqliteStore(ApplicationProvider.getApplicationContext(), name = null)
        val a = ev("a".repeat(64), 9, 100, "line\nnext 🎉")
        val b = ev("b".repeat(64), 7, 50)
        assertEquals(listOf(a, b), store.addEvents(listOf(a, b)))
        // known ids are not stored twice and not reported as new
        val c = ev("c".repeat(64), 9, 300)
        assertEquals(listOf(c), store.addEvents(listOf(a, c)))
        assertEquals(listOf(b, a, c), store.allEvents())
        assertEquals(300, store.latestCreatedAt(listOf(9)))
        assertEquals(50, store.latestCreatedAt(listOf(7)))
        assertEquals(0, store.latestCreatedAt(listOf(1111)))
        assertEquals(0, store.latestCreatedAt(emptyList()))
        store.deleteEvents(listOf(c.id))
        assertEquals(listOf(b, a), store.allEvents())

        val later = OutboxItem(
            "l2", Target.MAIN, 1059, "x", listOf(listOf("p", "k")), 20, status = OutboxStatus.FAILED, error = "blocked: no", tries = 2,
            signed = a, dm = "dm1",
        )
        val first = OutboxItem("l1", Target.GROUPS, 9, "hello", listOf(listOf("h", "general")), 10, attachments = listOf(Attachment("blob1", "a.png", "image/png", 3, 4, 5)))
        store.putOutbox(later)
        store.putOutbox(first)
        assertEquals(listOf(first, later), store.outbox())
        store.putOutbox(first.copy(tries = 1))
        assertEquals(1, store.outbox().first().tries)
        store.dropOutbox("l1")
        assertEquals(listOf(later), store.outbox())

        store.putBlob("blob1", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.blob("blob1"))
        store.dropBlob("blob1")
        assertNull(store.blob("blob1"))

        store.set("k", "v1")
        store.set("k", "v2")
        assertEquals("v2", store.get("k"))
        store.remove("k")
        assertNull(store.get("k"))

        store.set("k", "v")
        store.putBlob("x", byteArrayOf(9))
        store.wipe()
        assertTrue(store.allEvents().isEmpty())
        assertTrue(store.outbox().isEmpty())
        assertNull(store.blob("x"))
        assertNull(store.get("k"))
        store.close()
    }

    /**
     * Robolectric has no AndroidKeyStore provider, so the encryption itself cannot run here: the
     * test stops (skipped, not passed) when the Keystore is missing. It needs a device or an
     * emulator to be verified for real.
     */
    @Test
    fun secretsRoundTripWhenTheKeystoreExists() {
        val secrets = Secrets(ApplicationProvider.getApplicationContext())
        assertNull(secrets.get("nothing"))
        try {
            secrets.set(Secrets.SESSION_KEY, "s3cret")
        } catch (e: Exception) {
            assumeNoException("No Android Keystore under Robolectric", e)
        }
        assertEquals("s3cret", secrets.get(Secrets.SESSION_KEY))
        secrets.remove(Secrets.SESSION_KEY)
        assertNull(secrets.get(Secrets.SESSION_KEY))
        secrets.wipe()
    }
}
