package sh.belfry.core.preview

import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Names to addresses (the system's resolver in the app, a fixed table in tests and the demo). Empty or a throw: unknown. */
fun interface Resolver {
    suspend fun resolve(host: String): List<InetAddress>
}

/** One answer of [PreviewNet]: BODY holds at most the bytes asked for; [overflow] = there were more. */
class NetAnswer(
    val status: Int,
    val location: String? = null,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val body: ByteArray = ByteArray(0),
    val overflow: Boolean = false,
)

/**
 * One GET (SPEC 19.87), never following a redirect, connecting ONLY to [addresses] for [host]
 * (what [SafeFetch] resolved and approved: a second lookup could answer differently), no cookies,
 * no credentials, no proxy, within [timeoutMs]. Reads at most [maxBytes] of a 200's body (and says
 * when there was more). Null: no answer (no connection, timeout, TLS).
 */
interface PreviewNet {
    suspend fun get(url: String, host: String, addresses: List<InetAddress>, accept: String, maxBytes: Int, timeoutMs: Long): NetAnswer?
}

/** Which addresses and names a preview may be fetched from (SPEC 19.87 "the SSRF rules"). Pure. */
object FetchRules {
    private val IPV4 = Regex("^[0-9]+(\\.[0-9]+){3}$")
    private val NUMERIC = Regex("^[0-9.]+$|^0x[0-9a-f.x]+$", RegexOption.IGNORE_CASE)
    private val BLOCKED_SUFFIXES = listOf(".localhost", ".local", ".internal", ".arpa", ".home.arpa", ".lan", ".onion")

    /**
     * URL when a preview may ask it, as a parsed address, else null: `https` only, port 443, no
     * user name or password, a name (not an IP literal, not a bare number), with a dot (no
     * single-label name), not `localhost` or a local-only suffix.
     */
    fun url(url: String): HttpUrl? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (!u.isHttps || u.port != 443) return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return null
        val h = u.host.lowercase().trimEnd('.')
        if (h.isEmpty() || h.contains(':') || IPV4.matches(h) || NUMERIC.matches(h)) return null
        if (!h.contains('.')) return null
        if (h == "localhost" || BLOCKED_SUFFIXES.any { h.endsWith(it) }) return null
        // a raw address that HttpUrl turned into its canonical form ("[::1]", "127.0.0.1")
        if (u.toString().substringAfter("://").startsWith("[")) return null
        return u
    }

    private fun v4Allowed(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xff
        val c = b[1].toInt() and 0xff
        val d = b[2].toInt() and 0xff
        return when {
            a == 0 -> false // "this network"
            a == 10 -> false // private
            a == 100 && c in 64..127 -> false // shared (CGNAT)
            a == 127 -> false // loopback
            a == 169 && c == 254 -> false // link-local
            a == 172 && c in 16..31 -> false // private
            a == 192 && c == 0 && d == 0 -> false // IETF protocol assignments
            a == 192 && c == 0 && d == 2 -> false // documentation
            a == 192 && c == 88 && d == 99 -> false // 6to4 relay
            a == 192 && c == 168 -> false // private
            a == 198 && (c == 18 || c == 19) -> false // benchmarking
            a == 198 && c == 51 && d == 100 -> false // documentation
            a == 203 && c == 0 && d == 113 -> false // documentation
            a >= 224 -> false // multicast, reserved, broadcast
            else -> true
        }
    }

    /** ADDRESS may be connected to for a preview: public unicast only. */
    fun allowed(address: InetAddress): Boolean {
        val b = address.address
        if (address is Inet4Address || b.size == 4) return v4Allowed(b)
        if (address !is Inet6Address || b.size != 16) return false
        fun u(i: Int) = b[i].toInt() and 0xff
        val first10zero = (0 until 10).all { u(it) == 0 }
        if (first10zero && u(10) == 0xff && u(11) == 0xff) return v4Allowed(b.copyOfRange(12, 16)) // ::ffff:a.b.c.d
        if ((0 until 12).all { u(it) == 0 }) return false // ::, ::1, ::a.b.c.d (IPv4-compatible)
        if (u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xff && u(3) == 0x9b && (4 until 12).all { u(it) == 0 }) return v4Allowed(b.copyOfRange(12, 16)) // 64:ff9b::/96 (NAT64)
        if (u(0) == 0x01 && u(1) == 0x00 && (2 until 8).all { u(it) == 0 }) return false // 100::/64 discard
        if (u(0) == 0x20 && u(1) == 0x01 && u(2) == 0x00 && u(3) == 0x00) return false // 2001::/32 Teredo
        if (u(0) == 0x20 && u(1) == 0x01 && u(2) == 0x0d && u(3) == 0xb8) return false // 2001:db8::/32 documentation
        if (u(0) == 0x20 && u(1) == 0x02) return v4Allowed(b.copyOfRange(2, 6)) // 2002::/16 (6to4)
        if ((u(0) and 0xfe) == 0xfc) return false // fc00::/7 unique local
        if (u(0) == 0xfe && (u(1) and 0xc0) == 0x80) return false // fe80::/10 link-local
        if (u(0) == 0xfe && (u(1) and 0xc0) == 0xc0) return false // fec0::/10 site-local
        if (u(0) == 0xff) return false // multicast
        return true
    }

    /** Every address the name resolved to must be allowed (one bad one refuses the name), and there must be one. */
    fun allAllowed(addresses: List<InetAddress>): Boolean = addresses.isNotEmpty() && addresses.all { allowed(it) }

    val REDIRECTS = setOf(301, 302, 303, 307, 308)
}

/**
 * The preview's fetches (SPEC 19.87): every hop checked by [FetchRules.url], its name resolved
 * by [resolver] and refused unless every address is public ([FetchRules.allAllowed]), then asked
 * of exactly those addresses through [net]; at most [LinkPreviews.REDIRECTS] redirects, each
 * checked the same way; [LinkPreviews.TIMEOUT_MS] for the whole of one fetch.
 */
class SafeFetch(private val net: PreviewNet, private val resolver: Resolver, private val timeoutMs: Long = LinkPreviews.TIMEOUT_MS) {
    /** A fetched address: where it ended and what it said. */
    class Got(val finalUrl: String, val answer: NetAnswer)

    /** Why a fetch gave nothing (tests and the trace). */
    enum class Why { URL, RESOLVE, ADDRESS, NO_ANSWER, STATUS, REDIRECTS, TIMEOUT, TYPE, SIZE }

    sealed interface Result {
        class Ok(val got: Got) : Result
        class Refused(val why: Why) : Result
    }

    private suspend fun follow(url: String, accept: String, maxBytes: Int): Result {
        val end = System.nanoTime() / 1_000_000 + timeoutMs
        var current = url
        for (hop in 0..LinkPreviews.REDIRECTS) {
            val u = FetchRules.url(current) ?: return Result.Refused(Why.URL)
            val addresses = try { resolver.resolve(u.host) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (addresses.isEmpty()) return Result.Refused(Why.RESOLVE)
            if (!FetchRules.allAllowed(addresses)) return Result.Refused(Why.ADDRESS)
            val left = (end - System.nanoTime() / 1_000_000).coerceAtLeast(1)
            val a = net.get(u.toString(), u.host, addresses, accept, maxBytes, left) ?: return Result.Refused(Why.NO_ANSWER)
            if (a.status in FetchRules.REDIRECTS) {
                if (hop == LinkPreviews.REDIRECTS) return Result.Refused(Why.REDIRECTS)
                val next = a.location?.trim()?.takeIf { it.isNotEmpty() }?.let { u.resolve(it) } ?: return Result.Refused(Why.STATUS)
                current = next.toString()
                continue
            }
            if (a.status != 200) return Result.Refused(Why.STATUS)
            return Result.Ok(Got(u.toString(), a))
        }
        return Result.Refused(Why.REDIRECTS)
    }

    private suspend fun timed(block: suspend () -> Result): Result = withTimeoutOrNull(timeoutMs) { block() } ?: Result.Refused(Why.TIMEOUT)

    /** The page: `text/html` only, its first [LinkPreviews.PAGE_MAX] bytes (a longer page is read that far). */
    suspend fun page(url: String): Result = timed {
        when (val r = follow(url, "text/html", LinkPreviews.PAGE_MAX)) {
            is Result.Refused -> r
            is Result.Ok -> {
                val type = r.got.answer.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (type != "text/html") Result.Refused(Why.TYPE) else r
            }
        }
    }

    /** The picture: at most [LinkPreviews.PICTURE_IN_MAX] bytes (more: refused), a PNG, JPEG, WebP or GIF by its first bytes. */
    suspend fun picture(url: String): Result = timed {
        when (val r = follow(url, "image/png,image/jpeg,image/webp,image/gif", LinkPreviews.PICTURE_IN_MAX)) {
            is Result.Refused -> r
            is Result.Ok -> {
                val a = r.got.answer
                if (a.overflow || a.body.size > LinkPreviews.PICTURE_IN_MAX || (a.contentLength ?: 0) > LinkPreviews.PICTURE_IN_MAX) Result.Refused(Why.SIZE)
                else if (PreviewImages.kind(a.body) == null) Result.Refused(Why.TYPE)
                else r
            }
        }
    }
}
