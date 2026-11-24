package sh.belfry.core.preview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** The system's resolver (blocking, on the IO pool; cancelled with the fetch). */
object SystemResolver : Resolver {
    override suspend fun resolve(host: String): List<InetAddress> =
        runInterruptible(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }
}

/**
 * [PreviewNet] over OkHttp: no redirects followed, no proxy, no cookies, no cache, and a DNS that
 * answers only the host asked with only the addresses [SafeFetch] approved (OkHttp connects to
 * those and nothing else; TLS still checks the certificate for the name). No identifying headers:
 * a generic User-Agent, no Referer, nothing about the person.
 */
class OkPreviewNet(base: OkHttpClient) : PreviewNet {
    private val client: OkHttpClient = base.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .retryOnConnectionFailure(false)
        .apply { interceptors().clear(); networkInterceptors().clear() }
        .build()

    override suspend fun get(url: String, host: String, addresses: List<InetAddress>, accept: String, maxBytes: Int, timeoutMs: Long): NetAnswer? {
        val c = client.newBuilder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname.equals(host, ignoreCase = true)) addresses else throw UnknownHostException(hostname)
            })
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val req = try {
            Request.Builder().url(url).get()
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en")
                .build()
        } catch (e: IllegalArgumentException) { return null }
        val call = c.newCall(req)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val a = try { response.use { read(it, maxBytes) } } catch (e: Exception) { null }
                    if (cont.isActive) cont.resume(a)
                }
            })
        }
    }

    private fun read(r: Response, maxBytes: Int): NetAnswer {
        val type = r.header("Content-Type")
        val length = r.body?.contentLength()?.takeIf { it >= 0 }
        if (r.code != 200) return NetAnswer(r.code, r.header("Location"), type, length)
        val body = r.body ?: return NetAnswer(r.code, null, type, length)
        val out = java.io.ByteArrayOutputStream()
        var overflow = false
        if (length != null && length > maxBytes && !(type ?: "").startsWith("text/")) {
            overflow = true // a picture announced too large: not downloaded at all
        } else body.byteStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val room = maxBytes - out.size()
                if (n > room) { out.write(buf, 0, room); overflow = true; break }
                out.write(buf, 0, n)
            }
        }
        return NetAnswer(r.code, null, type, length, out.toByteArray(), overflow)
    }

    companion object {
        /** Generic on purpose: nothing about the person or the community. */
        const val USER_AGENT = "Mozilla/5.0 (compatible; BelfryLinkPreview/1.0)"
    }
}
