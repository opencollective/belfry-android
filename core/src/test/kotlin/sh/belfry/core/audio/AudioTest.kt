package sh.belfry.core.audio

import java.io.ByteArrayInputStream
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recording's file formats. `audio/sample.aac` is 2.56 s of AAC-LC in ADTS (ffmpeg, 32 kHz,
 * one channel); `audio/sample.m4a` is what `tools/adts2m4a.py` makes of it, which ffmpeg decodes
 * to the same samples as the .aac. The Kotlin writer must give the same bytes.
 */
class AudioTest {
    private fun res(name: String): ByteArray = javaClass.getResourceAsStream("/audio/$name")!!.use { it.readBytes() }
    private val aac = res("sample.aac")

    @Test
    fun adtsFramesAreCounted() {
        val scan = Adts.scan(aac)
        assertEquals(80, scan.frames.size)
        assertEquals(AdtsConfig(2, 5, 1), scan.config)
        assertEquals(32_000, scan.config!!.sampleRate)
        assertEquals(2560, scan.durationMs)
        assertEquals(aac.size, scan.frames.last().let { it.start + it.length })
    }

    @Test
    fun theM4aIsTheCheckedOne() {
        val m4a = M4a.mux(aac)
        assertNotNull(m4a)
        assertContentEquals(res("sample.m4a"), m4a)
        // the table first, then the audio: ftyp, moov, mdat
        assertEquals("ftyp", String(m4a, 4, 4, Charsets.US_ASCII))
        val moovAt = 28
        assertEquals("moov", String(m4a, moovAt + 4, 4, Charsets.US_ASCII))
    }

    /** A recording cut anywhere (killed while writing): everything up to the last whole frame is there. */
    @Test
    fun aCutFileKeepsItsWholeFrames() {
        val whole = Adts.scan(aac)
        for (cut in listOf(9000, aac.size - 1, whole.frames[1].start + 3, 8, 3, 0)) {
            val scan = Adts.scan(aac, cut)
            val expected = whole.frames.count { it.start + it.length <= cut }
            assertEquals(expected, scan.frames.size, "cut at $cut")
            val m4a = M4a.mux(aac, cut)
            if (expected == 0) assertNull(m4a) else {
                // the same as the m4a of exactly those frames
                val end = whole.frames[expected - 1].let { it.start + it.length }
                assertContentEquals(M4a.mux(aac.copyOf(end)), m4a)
            }
            val read = Adts.read(ByteArrayInputStream(aac, 0, cut))
            assertEquals(expected.toLong(), read.frames, "streamed, cut at $cut")
        }
        assertEquals(44, Adts.scan(aac, 9000).frames.size)
    }

    @Test
    fun junkIsNotAudio() {
        assertNull(M4a.mux(ByteArray(0)))
        assertNull(M4a.mux(ByteArray(500) { 0x41 }))
        assertEquals(0, Adts.read(ByteArrayInputStream(ByteArray(500) { 0x41 })).frames)
        // audio, then junk (a damaged file): the audio before it
        val damaged = aac.copyOf()
        val third = Adts.scan(aac).frames[3]
        damaged[third.start - 7] = 0
        assertEquals(3, Adts.scan(damaged).frames.size)
        assertEquals(3, Adts.read(ByteArrayInputStream(damaged)).frames)
    }

    @Test
    fun aLongRecordingIsReadInParts() {
        val parts = ArrayList<ByteArray>()
        val sum = Adts.read(ByteArrayInputStream(aac), maxFrames = 30) { parts.add(it) }
        assertEquals(80, sum.frames)
        assertEquals(aac.size.toLong(), sum.bytes)
        assertEquals(2560, sum.durationMs)
        assertEquals(3, sum.parts(30))
        assertEquals(1, sum.parts(Recordings.PART_FRAMES))
        assertEquals(listOf(30, 30, 20), parts.map { Adts.scan(it).frames.size })
        // the parts are the file, in order, and each plays on its own
        assertContentEquals(aac, parts.reduce { a, b -> a + b })
        for (p in parts) assertNotNull(M4a.mux(p))
        // only counting
        assertEquals(80, Adts.read(ByteArrayInputStream(aac)).frames)
        assertEquals(0, Adts.Summary(0, null, 0).parts(30))
    }

    @Test
    fun namesAndTextsAreTheWebs() {
        assertEquals("0:07", Recordings.clock(7_900))
        assertEquals("12:34", Recordings.clock(754_000))
        assertEquals("1:02:03", Recordings.clock(3_723_000))
        assertEquals("🎙 Recording · 12:34", Recordings.text(754_000, 1))
        assertEquals("🎙 Recording · 1:02:03 · 3 parts", Recordings.text(3_723_000, 3))
        assertEquals("🎙 Recording · 2:10:00 · part 2 of 5", Recordings.textOfPart(7_800_000, 2, 5))
        val at = 1_794_468_420_000L // 2026-11-12 07:27 UTC
        assertEquals("recording-2026-11-12-0727.m4a", Recordings.fileName(at, ZoneOffset.UTC, 1, 1, "m4a"))
        assertEquals("recording-2026-11-12-0727-part2.m4a", Recordings.fileName(at, ZoneOffset.UTC, 2, 3, "m4a"))
        assertEquals(56_250, Recordings.PART_FRAMES)
        assertTrue(Recordings.PART_FRAMES.toLong() * 1024 / 32_000 == 1800L)
    }
}
