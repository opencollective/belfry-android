package sh.belfry.core.engine

import kotlinx.coroutines.awaitCancellation
import sh.belfry.core.preview.NetAnswer
import sh.belfry.core.preview.PreviewNet
import sh.belfry.core.preview.Resolver
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The demo's outside web (SPEC 19.87): a few fixed pages and one picture, served on the device.
 * Every name resolves to one public documentation-free address that nothing ever connects to.
 * Nothing here touches the network.
 */
object DemoPreviews {
    /** A page with a picture (its og:image is relative). */
    const val PICTURE = "https://toolshare.example/stories/street-library"
    /** A page with no picture. */
    const val NO_PICTURE = "https://toolshare.example/how-to-start"
    /** A page with nothing usable: no preview. */
    const val NOTHING = "https://notes.example/empty"
    /** A page that never answers (the composer's "building" line stays). */
    const val SLOW = "https://slow.example/report"

    private const val PICTURE_PATH = "https://toolshare.example/og/street-library.png"

    val resolver = Resolver { _ -> listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 215.toByte(), 14))) }

    private fun html(s: String) = NetAnswer(200, contentType = "text/html; charset=utf-8", body = s.toByteArray())

    val net = object : PreviewNet {
        override suspend fun get(url: String, host: String, addresses: List<InetAddress>, accept: String, maxBytes: Int, timeoutMs: Long): NetAnswer? = when (url) {
            PICTURE -> html(
                """<!doctype html><html><head><title>Street library | ToolShare</title>
                <meta property="og:title" content="How a street tool library lends 400 drills a year">
                <meta property="og:description" content="Neighbours in a small street started lending their tools from a garage. Two years later they share drills, ladders and saws with half the town, and nobody keeps a list.">
                <meta property="og:site_name" content="ToolShare Journal">
                <meta property="og:image" content="/og/street-library.png">
                </head><body>…</body></html>""",
            )
            NO_PICTURE -> html(
                """<html><head><title>How to start a tool library &mdash; ToolShare</title>
                <meta name="description" content="A shed, a list of who has what, and one evening a week: the five steps most street libraries took.">
                </head></html>""",
            )
            NOTHING -> html("<html><body>Nothing here</body></html>")
            SLOW -> awaitCancellation()
            PICTURE_PATH -> NetAnswer(200, contentType = "image/png", body = picture)
            else -> NetAnswer(404)
        }
    }

    /** A 600 × 315 green gradient as a PNG (made here: no file in the repository). */
    val picture: ByteArray by lazy { png(600, 315) }

    fun png(w: Int, h: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) {
                val t = (x + y).toFloat() / (w + h)
                raw.write((127 - 81 * t).toInt()); raw.write((168 - 74 * t).toInt()); raw.write((139 - 72 * t).toInt())
            }
        }
        val d = Deflater(6).apply { setInput(raw.toByteArray()); finish() }
        val z = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) { val n = d.deflate(buf); z.write(buf, 0, n) }
        d.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun chunk(type: String, data: ByteArray) {
            out.write(int(data.size))
            val td = type.toByteArray(Charsets.US_ASCII) + data
            out.write(td)
            out.write(int(CRC32().apply { update(td) }.value.toInt()))
        }
        chunk("IHDR", int(w) + int(h) + byteArrayOf(8, 2, 0, 0, 0))
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
