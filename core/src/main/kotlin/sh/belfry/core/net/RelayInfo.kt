package sh.belfry.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.belfry.core.crypto.Hex
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.str
import sh.belfry.core.store.Store
import java.io.IOException

/**
 * SPEC 19.94: the groups relay's own key, from its NIP-11 document. The relay (zooid) signs every 39000–39003 with
 * the key in the document's `self` field (`pubkey` is the operator's, never used here). Only that one field is read;
 * nothing else of the document is kept or shown.
 */
object RelayInfo {
    /** The relay's NIP-11 address: its wss URL with https, the same host and path. Null: not a wss URL (a plain ws:// relay is never trusted: its key stays unknown). */
    fun httpUrl(relay: String): String? {
        val r = relay.trim()
        val http = when {
            r.startsWith("wss://", ignoreCase = true) -> "https://" + r.substring(6)
            else -> return null
        }
        return http.toHttpUrlOrNull()?.toString()
    }

    /** What a NIP-11 answer said: its `self` (64 hex, lower case), [NoKey] (a document without a valid `self`), or [NoAnswer]. */
    sealed interface Read {
        data class Key(val self: String) : Read
        data object NoKey : Read
        data object NoAnswer : Read
    }

    /** BODY of a 200 answer read: a JSON object whose `self` is 64 hex → its key; an object without one → [Read.NoKey]; else no answer. */
    fun parse(body: String): Read {
        val doc = parseJsonOrNull(body) as? JsonObject ?: return Read.NoAnswer
        val self = (doc["self"] as? JsonPrimitive)?.takeIf { it.isString }?.str()?.trim()?.lowercase()
        return if (self != null && Hex.isKey(self)) Read.Key(self) else Read.NoKey
    }

    /** At most this much of the document is read. */
    const val MAX_BYTES = 64L * 1024

    /** GET RELAY's NIP-11 document (`Accept: application/nostr+json`). Offline, a non-200 or an unreadable body: no answer. */
    suspend fun fetch(relay: String, http: OkHttpClient): Read {
        val url = httpUrl(relay) ?: return Read.NoAnswer
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).header("Accept", "application/nostr+json").build()).execute().use { res ->
                    if (res.code != 200) Read.NoAnswer else parse(res.peekBody(MAX_BYTES).string())
                }
            }
        } catch (e: IOException) {
            Read.NoAnswer
        } catch (e: IllegalArgumentException) {
            Read.NoAnswer
        }
    }
}

/**
 * SPEC 19.94: the groups relay's key per relay URL, kept in the account's store (`relay-self:<url>` → `{self, at}`) so
 * it survives restarts. Read again at most once a day ([REFRESH_MS]); a failure leaves what was known (or unknown)
 * and is tried again later with a back-off ([FIRST_RETRY_MS] doubling to [MAX_RETRY_MS]; memory only). A document
 * that answers without a valid `self` forgets the key (unknown: private-group writes hold). Never on the main thread:
 * [refresh] is a suspend call made by the session in the background; [known] reads the store.
 */
class RelayKeyCache(
    private val store: Store,
    private val nowMs: () -> Long,
    private val read: suspend (relay: String) -> RelayInfo.Read,
) {
    private class Backoff(val fails: Int, val nextMs: Long)
    private val backoff = HashMap<String, Backoff>()

    private fun storeKey(relay: String) = K_PREFIX + relay.trim().trimEnd('/')

    private fun stored(relay: String): Pair<String, Long>? {
        val o = parseJsonOrNull(store.get(storeKey(relay)) ?: return null) as? JsonObject ?: return null
        val self = (o["self"] as? JsonPrimitive)?.str() ?: return null
        if (!Hex.isKey(self)) return null
        return self to ((o["at"] as? JsonPrimitive)?.longOrNull ?: 0L)
    }

    /** RELAY's key as last read (from the store), or null: not known. */
    fun known(relay: String): String? = stored(relay)?.first

    /** RELAY's document should be read now: never read, or read over a day ago, and not backing off after a failure. */
    fun due(relay: String): Boolean {
        val now = nowMs()
        synchronized(backoff) { backoff[relay]?.let { if (now < it.nextMs) return false } }
        val s = stored(relay) ?: return true
        return now - s.second >= REFRESH_MS || s.second > now
    }

    /** Reads RELAY's document when [due] (FORCE: whatever the day, still never inside a back-off); the key known after. */
    suspend fun refresh(relay: String, force: Boolean = false): String? {
        if (relay.isBlank()) return null
        if (!force && !due(relay)) return known(relay)
        if (force) synchronized(backoff) { backoff[relay]?.let { if (nowMs() < it.nextMs) return known(relay) } }
        val got = try { read(relay) } catch (e: Exception) { RelayInfo.Read.NoAnswer }
        when (got) {
            is RelayInfo.Read.Key -> {
                store.set(storeKey(relay), buildJsonObject { put("self", JsonPrimitive(got.self)); put("at", JsonPrimitive(nowMs())) }.toString())
                synchronized(backoff) { backoff.remove(relay) }
            }
            RelayInfo.Read.NoKey -> { store.remove(storeKey(relay)); failed(relay) }
            RelayInfo.Read.NoAnswer -> failed(relay)
        }
        return known(relay)
    }

    private fun failed(relay: String) = synchronized(backoff) {
        val n = (backoff[relay]?.fails ?: 0) + 1
        val wait = (FIRST_RETRY_MS shl minOf(n - 1, 10)).coerceAtMost(MAX_RETRY_MS)
        backoff[relay] = Backoff(n, nowMs() + wait)
    }

    companion object {
        const val K_PREFIX = "relay-self:"
        const val REFRESH_MS = 24 * 3600 * 1000L
        const val FIRST_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 3600 * 1000L
    }
}
