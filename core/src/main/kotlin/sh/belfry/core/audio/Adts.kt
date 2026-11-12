package sh.belfry.core.audio

import java.io.ByteArrayOutputStream
import java.io.InputStream

// Audio recordings are made as AAC in ADTS (.aac): a plain run of frames, each with its own 7-byte
// header, written as it is recorded. A file cut anywhere (a crash, a kill, a dead battery) is
// whole up to its last full frame; nothing has to be closed. What is posted is an .m4a made from
// those frames afterwards ([M4a]), because that is what every browser plays and seeks in.

/** What an ADTS stream holds: AAC object type (2 = LC), sampling-frequency index, channels. */
data class AdtsConfig(val objectType: Int, val frequencyIndex: Int, val channels: Int) {
    val sampleRate: Int get() = Adts.RATES[frequencyIndex]
}

/** One frame in a buffer: where its AAC payload starts (after the header) and its length. */
class AdtsFrame(val start: Int, val length: Int)

class AdtsScan(val frames: List<AdtsFrame>, val config: AdtsConfig?) {
    /** Recorded time: 1024 samples a frame. */
    val durationMs: Long get() = config?.let { frames.size * 1024L * 1000L / it.sampleRate } ?: 0L
}

object Adts {
    val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    const val SAMPLES_PER_FRAME = 1024

    private class Header(val config: AdtsConfig, val headerLength: Int, val frameLength: Int)

    /** The header at [at], or null when this is not the start of a frame. */
    private fun header(b: ByteArray, at: Int, limit: Int): Header? {
        if (at + 7 > limit) return null
        val b1 = b[at + 1].toInt() and 0xFF
        if ((b[at].toInt() and 0xFF) != 0xFF || (b1 and 0xF6) != 0xF0) return null
        val b2 = b[at + 2].toInt() and 0xFF
        val b3 = b[at + 3].toInt() and 0xFF
        val b4 = b[at + 4].toInt() and 0xFF
        val b5 = b[at + 5].toInt() and 0xFF
        val objectType = ((b2 shr 6) and 3) + 1
        val frequencyIndex = (b2 shr 2) and 15
        val channels = ((b2 and 1) shl 2) or ((b3 shr 6) and 3)
        val frameLength = ((b3 and 3) shl 11) or (b4 shl 3) or ((b5 shr 5) and 7)
        val headerLength = if (b1 and 1 == 1) 7 else 9
        if (frameLength < headerLength || frequencyIndex >= RATES.size) return null
        return Header(AdtsConfig(objectType, frequencyIndex, channels), headerLength, frameLength)
    }

    /**
     * The whole frames at the start of [b]. Stops at the first thing that is not one: a frame cut
     * short (the recording was killed while writing it), other bytes, or a frame of another kind.
     */
    fun scan(b: ByteArray, length: Int = b.size): AdtsScan {
        val frames = ArrayList<AdtsFrame>()
        var config: AdtsConfig? = null
        var i = 0
        while (true) {
            val h = header(b, i, length) ?: break
            if (i + h.frameLength > length) break
            if (config == null) config = h.config else if (h.config != config) break
            frames.add(AdtsFrame(i + h.headerLength, h.frameLength - h.headerLength))
            i += h.frameLength
        }
        return AdtsScan(frames, config)
    }

    /** What reading a file gave: how many whole frames, and of what. */
    class Summary(val frames: Long, val config: AdtsConfig?, val bytes: Long) {
        val durationMs: Long get() = config?.let { frames * 1024L * 1000L / it.sampleRate } ?: 0L
        fun parts(maxFrames: Int): Int = if (frames == 0L) 0 else ((frames + maxFrames - 1) / maxFrames).toInt()
    }

    /**
     * Reads an ADTS file from its start, frame by frame, without holding it in memory. Each run of
     * up to [maxFrames] whole frames is given to [onPart] as ADTS bytes (a file that plays on its
     * own); pass null to only count. Stops quietly at a cut or damaged frame, like [scan].
     */
    fun read(input: InputStream, maxFrames: Int = Int.MAX_VALUE, onPart: ((ByteArray) -> Unit)? = null): Summary {
        val head = ByteArray(9)
        var frame = ByteArray(8192)
        var config: AdtsConfig? = null
        var count = 0L
        var bytes = 0L
        var inPart = 0
        val part = if (onPart != null) ByteArrayOutputStream() else null
        fun flush() {
            if (part != null && inPart > 0) { onPart!!(part.toByteArray()); part.reset() }
            inPart = 0
        }
        while (true) {
            if (!fill(input, head, 0, 7)) break
            val h = header(head, 0, 7) ?: break
            if (config == null) config = h.config else if (h.config != config) break
            if (frame.size < h.frameLength) frame = ByteArray(h.frameLength)
            System.arraycopy(head, 0, frame, 0, 7)
            if (!fill(input, frame, 7, h.frameLength - 7)) break
            part?.write(frame, 0, h.frameLength)
            count++; bytes += h.frameLength; inPart++
            if (inPart >= maxFrames) flush()
        }
        flush()
        return Summary(count, config, bytes)
    }

    /** Reads exactly [n] bytes; false when the stream ends first. */
    private fun fill(input: InputStream, into: ByteArray, at: Int, n: Int): Boolean {
        var got = 0
        while (got < n) {
            val r = input.read(into, at + got, n - got)
            if (r < 0) return false
            got += r
        }
        return true
    }
}
