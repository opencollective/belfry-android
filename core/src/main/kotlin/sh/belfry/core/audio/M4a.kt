package sh.belfry.core.audio

import java.io.ByteArrayOutputStream

/**
 * Makes an .m4a (MPEG-4 audio, AAC) from ADTS frames, without touching the audio: the frames are
 * copied as they are, and the table that says where each one is (the `moov` box) is written
 * first, so a browser can show the length and seek at once. The file is made only from a
 * recording that is on the device already, in one go, in memory: there is never a half-written
 * .m4a. `tools/adts2m4a.py` is the same writer in Python, checked with ffmpeg; the tests hold
 * this one to its output byte for byte.
 */
object M4a {
    /** Frames per chunk in the sample table (about a second). */
    private const val PER_CHUNK = 32

    private class Out {
        val b = ByteArrayOutputStream()
        fun u8(v: Int) = apply { b.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v shr 8); u8(v) }
        fun u32(v: Long) = apply { u8((v shr 24).toInt()); u8((v shr 16).toInt()); u8((v shr 8).toInt()); u8(v.toInt()) }
        fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
        fun zeros(n: Int) = apply { repeat(n) { b.write(0) } }
        fun ascii(s: String) = apply { for (c in s) b.write(c.code) }
        fun bytes(x: ByteArray) = apply { b.write(x, 0, x.size) }
        fun done(): ByteArray = b.toByteArray()
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        val o = Out().u32(size).ascii(type)
        for (p in parts) o.bytes(p)
        return o.done()
    }

    private fun full(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray =
        box(type, Out().u32((version shl 24) or flags).done(), *parts)

    private val MATRIX: ByteArray = Out().apply { for (v in longArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)) u32(v) }.done()

    /**
     * The .m4a of this ADTS recording ([adts], its first [length] bytes); null when it holds no
     * whole frame. A cut last frame is left out.
     */
    fun mux(adts: ByteArray, length: Int = adts.size): ByteArray? {
        val scan = Adts.scan(adts, length)
        val cfg = scan.config ?: return null
        val frames = scan.frames
        if (frames.isEmpty()) return null
        val rate = cfg.sampleRate
        val n = frames.size
        val duration = n.toLong() * Adts.SAMPLES_PER_FRAME
        var total = 0L
        for (f in frames) total += f.length
        if (duration > 0xFFFFFFFFL || total > 0x7FFF0000L) return null
        val bitrate = total * 8 * rate / duration

        // esds: what the decoder needs to know (AAC, its object type, rate and channels)
        val asc = Out().u16((cfg.objectType shl 11) or (cfg.frequencyIndex shl 7) or (cfg.channels shl 3)).done()
        val dsi = Out().u8(0x05).u8(asc.size).bytes(asc).done()
        val dcdBody = Out().u8(0x40).u8(0x15).zeros(3).u32(bitrate).u32(bitrate).bytes(dsi).done()
        val dcd = Out().u8(0x04).u8(dcdBody.size).bytes(dcdBody).done()
        val slc = byteArrayOf(0x06, 0x01, 0x02)
        val esBody = Out().u16(0).u8(0).bytes(dcd).bytes(slc).done()
        val es = Out().u8(0x03).u8(esBody.size).bytes(esBody).done()
        val esds = full("esds", 0, 0, es)
        val mp4a = box(
            "mp4a", Out().zeros(6).u16(1).zeros(8).u16(cfg.channels).u16(16).u16(0).u16(0).u32(rate.toLong() shl 16).done(), esds,
        )
        val stsd = full("stsd", 0, 0, Out().u32(1).done(), mp4a)
        val stts = full("stts", 0, 0, Out().u32(1).u32(n).u32(Adts.SAMPLES_PER_FRAME).done())
        val chunks = (n + PER_CHUNK - 1) / PER_CHUNK
        val rem = n - (chunks - 1) * PER_CHUNK
        val stsc = if (rem == PER_CHUNK || chunks == 1) {
            full("stsc", 0, 0, Out().u32(1).u32(1).u32(if (chunks == 1) rem else PER_CHUNK).u32(1).done())
        } else {
            full("stsc", 0, 0, Out().u32(2).u32(1).u32(PER_CHUNK).u32(1).u32(chunks).u32(rem).u32(1).done())
        }
        val stsz = full("stsz", 0, 0, Out().u32(0).u32(n).apply { for (f in frames) u32(f.length) }.done())

        fun moov(offsets: LongArray): ByteArray {
            val stco = full("stco", 0, 0, Out().u32(offsets.size).apply { for (o in offsets) u32(o) }.done())
            val stbl = box("stbl", stsd, stts, stsc, stsz, stco)
            val dinf = box("dinf", full("dref", 0, 0, Out().u32(1).done(), full("url ", 0, 1)))
            val minf = box("minf", full("smhd", 0, 0, Out().u16(0).u16(0).done()), dinf, stbl)
            val mdhd = full("mdhd", 0, 0, Out().u32(0).u32(0).u32(rate).u32(duration).u16(0x55C4).u16(0).done())
            val hdlr = full("hdlr", 0, 0, Out().u32(0).ascii("soun").zeros(12).ascii("SoundHandler").u8(0).done())
            val mdia = box("mdia", mdhd, hdlr, minf)
            val tkhd = full(
                "tkhd", 0, 7,
                Out().u32(0).u32(0).u32(1).u32(0).u32(duration).zeros(8).u16(0).u16(0).u16(0x0100).u16(0).bytes(MATRIX).u32(0).u32(0).done(),
            )
            val mvhd = full(
                "mvhd", 0, 0,
                Out().u32(0).u32(0).u32(rate).u32(duration).u32(0x10000).u16(0x0100).zeros(10).bytes(MATRIX).zeros(24).u32(2).done(),
            )
            return box("moov", mvhd, box("trak", tkhd, mdia))
        }

        val ftyp = box("ftyp", Out().ascii("M4A ").u32(0).ascii("M4A ").ascii("mp42").ascii("isom").done())
        // the table's size does not depend on the offsets in it: measure it, then fill it in
        val head = ftyp.size + moov(LongArray(chunks)).size + 8L
        val offsets = LongArray(chunks)
        var pos = head
        for (k in frames.indices) {
            if (k % PER_CHUNK == 0) offsets[k / PER_CHUNK] = pos
            pos += frames[k].length
        }
        val table = moov(offsets)
        val out = ByteArray((head + total).toInt())
        var at = 0
        System.arraycopy(ftyp, 0, out, at, ftyp.size); at += ftyp.size
        System.arraycopy(table, 0, out, at, table.size); at += table.size
        val mdat = Out().u32(8 + total).ascii("mdat").done()
        System.arraycopy(mdat, 0, out, at, 8); at += 8
        for (f in frames) { System.arraycopy(adts, f.start, out, at, f.length); at += f.length }
        return out
    }
}
