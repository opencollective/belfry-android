package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.dm.DmNewFile
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.store.MemoryStore
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Files in a direct message through the outbox (the demo plays the storage): encrypt, upload, wrap, read back. */
class DmFilesSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun tearDown() { scope.cancel() }

    private fun session(): Session {
        val s = Session(Community.defaults("example.org"), MemoryStore(), LocalSigner.random(), OkHttpClient(), scope, true, { 1_800_000_000L }, null, ZoneOffset.UTC)
        s.start()
        return s
    }

    private suspend fun until(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) { if (System.currentTimeMillis() > end) fail("timed out: $what"); delay(20) }
    }

    @Test
    fun picturesWithACaptionGoAndReadBack() = runBlocking {
        val s = session()
        val peer = LocalSigner.random().pubkey
        val conv = "n:$peer"
        val a = ByteArray(3000) { (it % 251).toByte() }
        val b = ByteArray(2000) { (it % 13).toByte() }
        s.sendDmFiles(conv, listOf(DmNewFile("a.jpg", "image/jpeg", a, 40, 30), DmNewFile("b.jpg", "image/jpeg", b, 30, 40)), "The seedlings came up!")
        s.flush()
        until("both files out, known by their rumor ids") {
            val t = s.dms.value.thread(conv)
            t.count { it.isFile && !it.pending && it.file!!.url.startsWith(Session.LOCAL_BLOB) } == 2
        }
        val t = s.dms.value.thread(conv)
        val files = t.filter { it.isFile }.sortedBy { it.at }
        val caption = t.single { it.kind == 14 }
        assertNotNull(files[0].group)
        assertEquals(files[0].group, files[1].group)
        assertEquals(files[0].group, caption.group)
        assertTrue(files[0].at < files[1].at && files[1].at < caption.at, "created_at strictly increasing, caption last")
        assertContentEquals(a, s.dmFile(files[0]))
        assertContentEquals(b, s.dmFile(files[1]))
        assertEquals("2 pictures", s.dms.value.fileLine(files[1]))
        assertTrue(s.store.outbox().isEmpty(), "nothing left in the outbox")
        assertTrue(s.trace.lines().any { it.contains("uploading") && it.contains("→ OK") }, s.trace.lines().joinToString("\n"))
        s.stop()
    }

    @Test
    fun aFileNamedAndARefusedSize() = runBlocking {
        val s = session()
        val conv = "n:" + LocalSigner.random().pubkey
        s.sendDmFiles(conv, listOf(DmNewFile("../Garden plan 2027.pdf", "application/pdf", "%PDF-1.4".toByteArray())))
        s.flush()
        until("out") { s.dms.value.thread(conv).any { it.isFile && !it.pending && it.file!!.url.isNotEmpty() } }
        val d = s.dms.value.thread(conv).single { it.isFile }
        assertEquals("Garden plan 2027.pdf", d.file!!.name)
        assertEquals("File: Garden plan 2027.pdf", s.dms.value.fileLine(d))
        val e = assertFailsWith<ActionException> { s.sendDmFiles(conv, listOf(DmNewFile("big.mov", "video/quicktime", ByteArray(DmFiles.MAX + 1)))) }
        assertEquals("This file is too large (25 MB at most)", e.message)
        s.stop()
    }
}
