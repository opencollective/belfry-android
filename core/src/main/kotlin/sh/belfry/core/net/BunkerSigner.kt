package sh.belfry.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Nip04
import sh.belfry.core.crypto.Random
import sh.belfry.core.nostr.CryptoException
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.SignerTimeout
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.unixNow
import java.net.URLDecoder

/** bunker://<remote signer pubkey>?relay=wss://…&relay=…&secret=… */
data class BunkerUrl(val remotePubkey: String, val relays: List<String>, val secret: String?) {
    companion object {
        fun parse(text: String): BunkerUrl? {
            val s = text.trim()
            if (!s.startsWith("bunker://")) return null
            val rest = s.removePrefix("bunker://")
            val host = rest.substringBefore('?').trimEnd('/').lowercase()
            if (!Hex.isKey(host)) return null
            val query = rest.substringAfter('?', "")
            val relays = ArrayList<String>()
            var secret: String? = null
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val name = pair.substringBefore('=')
                val value = try {
                    URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                } catch (e: IllegalArgumentException) {
                    continue
                }
                if (name == "relay" && (value.startsWith("wss://") || value.startsWith("ws://"))) relays.add(value)
                if (name == "secret" && secret == null) secret = value
            }
            if (relays.isEmpty()) return null
            return BunkerUrl(host, relays, secret)
        }
    }
}

/** The signer answered with an error (its own text: for logs and for telling causes apart, never for display). */
class SignerRefused(message: String) : Exception(message)

/**
 * NIP-46 remote signer (SPEC 3): the member's key stays in the belfry's signer; this device holds
 * only a client key. Requests are kind 24133, NIP-44 encrypted JSON-RPC, [timeoutMs] each
 * (connection included). A request that gets no answer drops the connection and is asked once
 * more on a fresh one (a deploy restarts the belfry's proxy and every socket drops; signing or
 * decrypting twice is harmless: web auth.ts, 2026-11-11); then [SignerTimeout] (the outbox tries
 * again later).
 *
 * @param clientSecretHex this device's key for the bunker (kept in Keystore-encrypted storage)
 * @param pubkey the member's key if known already (cached so the app opens offline)
 */
class BunkerSigner(
    val bunker: BunkerUrl,
    clientSecretHex: String,
    pubkey: String?,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    var timeoutMs: Long = 10_000,
) : Signer {
    private val client = LocalSigner(clientSecretHex)
    val clientPubkey: String get() = client.pubkey
    /** This device's key: also its sealed-conversations device key (sealed.md §2). Never leaves the device. */
    val device: LocalSigner get() = client

    @Volatile private var memberKey: String = pubkey ?: ""
    /** Empty until [ensurePubkey] has run, unless it was given. */
    override val pubkey: String get() = memberKey

    private val connectLock = Mutex()
    private val lock = Any()
    private var connected = false

    /**
     * The one socket to the signer's relay, made once and opened again as needed. A session hands
     * it to its [Connections] (SPEC 19.50), which asks it on foreground and network changes.
     */
    val relay: Relay = Relay(bunker.relays[0], http, scope).also { r ->
        r.authSigner = client
        r.onEvent = { _, ev -> handle(ev) }
        r.onDisconnect = { reset(dropped = true) }
    }
    private val waiting = HashMap<String, CompletableDeferred<String>>()

    /** Asks the signer for the member's key if it is not known yet. */
    suspend fun ensurePubkey(): String {
        if (memberKey.isNotEmpty()) return memberKey
        val pk = request("get_public_key", emptyList())
        if (!Hex.isKey(pk)) throw SignerRefused("the signer sent no key")
        memberKey = pk
        return pk
    }

    /**
     * The socket, open and said hello to. Requests queue here while it (re)connects. One that was
     * quiet for more than [Relay.sendQuietMs] is asked first: after a sleep it can look open and
     * be dead, and every request would wait its whole timeout (SPEC 19.50).
     */
    private suspend fun ensureConnected(): Relay = connectLock.withLock {
        val r = relay
        if (synchronized(lock) { connected } && r.isUp) {
            if (!r.quietFor(r.sendQuietMs) || r.probe()) return@withLock r
            // dead: probe dropped it (onDisconnect reset us); a fresh one below
        }
        r.close()
        synchronized(lock) { connected = false }
        try {
            r.connect(timeoutMs)
            r.subscribe(
                "nip46",
                listOf(Filters.of(kinds = listOf(24133), tags = mapOf("p" to listOf(client.pubkey)), since = unixNow() - 10)),
            )
            rpc(r, "connect", listOf(bunker.remotePubkey, bunker.secret ?: ""))
            synchronized(lock) { connected = true }
        } catch (e: Throwable) {
            synchronized(lock) { connected = false }
            r.close()
            throw e
        }
        r
    }

    /**
     * Forgets the connection and fails who waits ([Dropped]: they are asked again on a fresh one).
     * DROPPED = the socket is gone already; else it is closed here.
     */
    private fun reset(dropped: Boolean = false) {
        val w: List<CompletableDeferred<String>> = synchronized(lock) {
            connected = false
            val list = waiting.values.toList()
            waiting.clear()
            list
        }
        if (!dropped) relay.close()
        w.forEach { it.completeExceptionally(Dropped()) }
    }

    /** The socket went while a request waited. */
    private class Dropped : Exception("timeout: the signer's connection dropped")

    private fun handle(ev: Event) {
        if (ev.kind != 24133 || ev.pubkey != bunker.remotePubkey) return
        val text = try {
            client.nip44DecryptNow(ev.pubkey, ev.content)
        } catch (e: Exception) {
            try { Nip04.decrypt(client.secretHex, ev.pubkey, ev.content) } catch (e2: Exception) { return }
        }
        val obj = parseJsonOrNull(text) as? JsonObject ?: return
        val id = obj.s("id") ?: return
        val result = obj.s("result")
        val error = obj.s("error")
        if (error.isNullOrEmpty() && result == "auth_url") return // not used by Belfry's signer
        val d = synchronized(lock) { waiting.remove(id) } ?: return
        when {
            !error.isNullOrEmpty() -> d.completeExceptionally(SignerRefused("signer: $error"))
            result != null -> d.complete(result)
            else -> d.completeExceptionally(SignerRefused("signer: empty answer"))
        }
    }

    private suspend fun rpc(r: Relay, method: String, params: List<String>): String {
        val id = Random.hex(8)
        val body = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", buildJsonArray { params.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        val content = client.nip44EncryptNow(bunker.remotePubkey, body)
        val ev = client.signNow(EventTemplate(24133, content, listOf(listOf("p", bunker.remotePubkey)), unixNow()))
        val d = CompletableDeferred<String>()
        synchronized(lock) { waiting[id] = d }
        scope.launch {
            try {
                r.publish(ev, timeoutMs)
            } catch (e: Exception) {
                // the answer may still come; a dead connection ends in the timeout below
            }
        }
        try {
            return d.await()
        } finally {
            synchronized(lock) { waiting.remove(id) }
        }
    }

    private suspend fun request(method: String, params: List<String>): String {
        val start = Relay.nowMs()
        try {
            return try {
                once(method, params, retry = true)
            } catch (e: RetryOnFresh) {
                once(method, params, retry = false)
            }
        } finally {
            onRequest?.invoke(method, Relay.nowMs() - start)
        }
    }

    /** Hears each request and how long it took (ms): the send trace (SPEC 19.50). No content. */
    @Volatile var onRequest: ((method: String, ms: Long) -> Unit)? = null

    private class RetryOnFresh : Exception()

    private suspend fun once(method: String, params: List<String>, retry: Boolean): String {
        try {
            return withTimeout(timeoutMs) {
                val r = ensureConnected()
                rpc(r, method, params)
            }
        } catch (e: TimeoutCancellationException) {
            // no answer: the socket is dead; drop it, the next request (this one again) opens a fresh one
            reset()
            if (retry) throw RetryOnFresh()
            throw SignerTimeout("timeout: the signer did not answer")
        } catch (e: Dropped) {
            if (retry) throw RetryOnFresh()
            throw SignerTimeout("timeout: the signer did not answer")
        } catch (e: RelayException) {
            reset()
            throw SignerTimeout("timeout: no connection to the signer")
        }
    }

    override suspend fun sign(template: EventTemplate): Event {
        val pk = ensurePubkey()
        val at = if (template.createdAt > 0) template.createdAt else unixNow()
        val unsigned = buildJsonObject {
            put("kind", JsonPrimitive(template.kind))
            put("content", JsonPrimitive(template.content))
            put("tags", Event.tagsJson(template.tags))
            put("created_at", JsonPrimitive(at))
            put("pubkey", JsonPrimitive(pk))
        }.toString()
        val result = request("sign_event", listOf(unsigned))
        val ev = Event.fromJsonString(result)
        if (ev == null || ev.pubkey != pk || ev.kind != template.kind || ev.content != template.content || !ev.isValid()) {
            throw CryptoException("signer: the signature does not check out")
        }
        return ev
    }

    override suspend fun nip44Encrypt(peer: String, plaintext: String): String = request("nip44_encrypt", listOf(peer, plaintext))
    override suspend fun nip44Decrypt(peer: String, payload: String): String = request("nip44_decrypt", listOf(peer, payload))
    override suspend fun nip04Decrypt(peer: String, payload: String): String = request("nip04_decrypt", listOf(peer, payload))

    /** Closes the connection to the signer (sign-out, or going to the background for long). */
    fun disconnect() = reset()

    /** Opens the connection ahead of the next request (the app came to the foreground); failures are quiet. */
    suspend fun warm() {
        try { withTimeout(timeoutMs) { ensureConnected() } } catch (e: Exception) { }
    }
}
