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

    @Volatile private var memberKey: String = pubkey ?: ""
    /** Empty until [ensurePubkey] has run, unless it was given. */
    override val pubkey: String get() = memberKey

    private val connectLock = Mutex()
    private val lock = Any()
    private var relay: Relay? = null
    private var connected = false
    private val waiting = HashMap<String, CompletableDeferred<String>>()

    /** Asks the signer for the member's key if it is not known yet. */
    suspend fun ensurePubkey(): String {
        if (memberKey.isNotEmpty()) return memberKey
        val pk = request("get_public_key", emptyList())
        if (!Hex.isKey(pk)) throw SignerRefused("the signer sent no key")
        memberKey = pk
        return pk
    }

    private suspend fun ensureConnected(): Relay = connectLock.withLock {
        val current = synchronized(lock) { if (connected) relay else null }
        if (current != null && current.isUp) return@withLock current
        synchronized(lock) { relay }?.close()
        val r = Relay(bunker.relays[0], http, scope)
        r.authSigner = client
        r.onEvent = { _, ev -> handle(ev) }
        r.onDisconnect = { reset(r) }
        try {
            r.connect(timeoutMs)
            r.subscribe(
                "nip46",
                listOf(Filters.of(kinds = listOf(24133), tags = mapOf("p" to listOf(client.pubkey)), since = unixNow() - 10)),
            )
            synchronized(lock) { relay = r; connected = false }
            rpc(r, "connect", listOf(bunker.remotePubkey, bunker.secret ?: ""))
            synchronized(lock) { connected = true }
        } catch (e: Throwable) {
            synchronized(lock) { if (relay === r) { relay = null; connected = false } }
            r.close()
            throw e
        }
        r
    }

    /** Drops the connection (ONLY when it is still R, or any when R is null) and fails who waits. */
    private fun reset(r: Relay?) {
        var old: Relay? = null
        val w: List<CompletableDeferred<String>> = synchronized(lock) {
            if (r != null && relay !== r) return
            old = relay
            relay = null
            connected = false
            val list = waiting.values.toList()
            waiting.clear()
            list
        }
        old?.close()
        w.forEach { it.completeExceptionally(SignerTimeout("timeout: the signer did not answer")) }
    }

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

    private suspend fun request(method: String, params: List<String>): String =
        try {
            once(method, params, retry = true)
        } catch (e: RetryOnFresh) {
            once(method, params, retry = false)
        }

    private class RetryOnFresh : Exception()

    private suspend fun once(method: String, params: List<String>, retry: Boolean): String {
        try {
            return withTimeout(timeoutMs) {
                val r = ensureConnected()
                rpc(r, method, params)
            }
        } catch (e: TimeoutCancellationException) {
            reset(null)
            if (retry) throw RetryOnFresh()
            throw SignerTimeout("timeout: the signer did not answer")
        } catch (e: RelayException) {
            reset(null)
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
    fun disconnect() = reset(null)
}
