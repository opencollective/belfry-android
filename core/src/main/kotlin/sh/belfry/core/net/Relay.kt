package sh.belfry.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import sh.belfry.core.crypto.Random
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.Nip98
import sh.belfry.core.nostr.Signer
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.str
import java.util.concurrent.TimeUnit

enum class RelayState { DOWN, CONNECTING, AUTH, UP }

/**
 * A relay said no, or did not answer. [reason] is the relay's own machine-readable text
 * ("restricted: …", "auth-required: …", "duplicate: …") or one of ours starting with "timeout:".
 * The outbox classifies it (SPEC 5); screens show fixed texts.
 */
class RelayException(message: String) : Exception(message) {
    val reason: String get() = message ?: ""
}

/** Builders for NIP-01 filters. */
object Filters {
    fun of(
        kinds: List<Int>? = null,
        authors: List<String>? = null,
        ids: List<String>? = null,
        since: Long? = null,
        until: Long? = null,
        limit: Int? = null,
        tags: Map<String, List<String>> = emptyMap(),
    ): JsonObject = buildJsonObject {
        if (ids != null) put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
        if (authors != null) put("authors", buildJsonArray { authors.forEach { add(JsonPrimitive(it)) } })
        if (kinds != null) put("kinds", buildJsonArray { kinds.forEach { add(JsonPrimitive(it)) } })
        for ((name, values) in tags) {
            put(if (name.startsWith("#")) name else "#$name", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        }
        if (since != null) put("since", JsonPrimitive(since))
        if (until != null) put("until", JsonPrimitive(until))
        if (limit != null) put("limit", JsonPrimitive(limit))
    }
}

/** SPEC 4: reconnect after min(30 s, 0.8 s × 2^attempt). */
object Backoff {
    fun delayMs(attempt: Int): Long = minOf(30_000L, 800L * (1L shl attempt.coerceIn(0, 16)))
}

/** SPEC 4: does this refusal mean "no access (yet)"? Only the groups relay's verdict counts. */
object Access {
    private val REFUSAL = Regex("^(restricted|blocked)|pass|member", RegexOption.IGNORE_CASE)
    fun isRefusal(reason: String): Boolean = REFUSAL.containsMatchIn(reason)
}

/**
 * One relay connection over an OkHttp WebSocket (SPEC 4): REQ / EVENT / OK / EOSE / CLOSED / AUTH,
 * NIP-42 authentication with [authSigner], publish with a timeout, one-off queries.
 * Subscriptions are remembered and sent again after every (re)connect and after AUTH.
 * Callbacks run on OkHttp's reader thread: keep them short.
 */
class Relay(
    val url: String,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    /** The signer for NIP-42 (null: no AUTH). */
    @Volatile var authSigner: Signer? = null
    /** Drop events whose id or signature does not check out (SPEC 15). */
    @Volatile var verifySignatures: Boolean = true

    @Volatile var onEvent: ((sub: String, event: Event) -> Unit)? = null
    @Volatile var onEose: ((sub: String) -> Unit)? = null
    /** The relay closed a subscription: (sub, reason). */
    @Volatile var onClosed: ((sub: String, reason: String) -> Unit)? = null
    /** The relay refused our NIP-42 sign-in (reason), or signing it failed. */
    @Volatile var onAuthRefused: ((reason: String) -> Unit)? = null
    /** NIP-42 went through: subscriptions were sent again. */
    @Volatile var onAuthenticated: (() -> Unit)? = null
    /** The socket opened (subscriptions were sent again). */
    @Volatile var onConnected: (() -> Unit)? = null
    /** The socket closed or failed (not after an explicit [close]). */
    @Volatile var onDisconnect: (() -> Unit)? = null

    private val _state = MutableStateFlow(RelayState.DOWN)
    val state: StateFlow<RelayState> = _state
    val isUp: Boolean get() = _state.value == RelayState.UP || _state.value == RelayState.AUTH

    private val lock = Any()
    private var socket: WebSocket? = null
    private var isOpen = false
    private var opening: CompletableDeferred<Unit>? = null
    private val pendingOk = HashMap<String, CompletableDeferred<Unit>>()
    private val subs = LinkedHashMap<String, List<JsonObject>>()
    private var authEventId: String? = null
    private var authed = false
    private val authWaiters = ArrayList<CompletableDeferred<Unit>>()
    private val queries = HashMap<String, QueryBox>()

    private var keep = false
    private var probing = false
    private var attempts = 0
    private var reconnectJob: Job? = null

    // ---- connecting -----------------------------------------------------------------------------

    /** Opens the socket (no-op when it is open). Throws [RelayException] "timeout: …" or "connection closed". */
    suspend fun connect(timeoutMs: Long = 15_000) {
        val waiter: CompletableDeferred<Unit> = synchronized(lock) {
            if (socket != null && isOpen) return
            val existing = opening
            if (existing != null && socket != null) {
                existing
            } else {
                val d = CompletableDeferred<Unit>()
                opening = d
                isOpen = false
                authed = false
                authEventId = null
                _state.value = RelayState.CONNECTING
                socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
                d
            }
        }
        val ok = withTimeoutOrNull(timeoutMs) { waiter.await() }
        if (ok == null) {
            close()
            throw RelayException("timeout: no connection to the relay")
        }
    }

    /** Keep the connection up: reconnect with the SPEC 4 backoff whenever it drops. */
    fun keepConnected() {
        synchronized(lock) { keep = true }
        if (!isUp) scope.launch { tryConnect() }
    }

    /** A wake-up (online again, foreground, watchdog): forget the backoff and reconnect now if down. */
    fun wake() {
        val wanted = synchronized(lock) { attempts = 0; keep }
        if (wanted && !isUp) {
            reconnectJob?.cancel()
            scope.launch { tryConnect() }
        }
    }

    /**
     * Is the socket really alive? A phone that slept can keep a socket that says "open" and is
     * dead: nothing arrives and nothing says so until the next ping times out. Asks something tiny;
     * no answer in [timeoutMs] = dead: the socket is dropped and, when [keepConnected], opened again
     * (the caller's [onDisconnect] runs, as for any drop). Returns false when it was dropped.
     */
    suspend fun probe(timeoutMs: Long = 6_000): Boolean {
        val ws = synchronized(lock) { if (isOpen && !probing) { probing = true; socket } else null } ?: return true
        try {
            val id = "p" + Random.hex(4)
            val box = QueryBox()
            synchronized(lock) { if (socket === ws) queries[id] = box }
            ws.send(req(id, listOf(Filters.of(kinds = listOf(39000), limit = 1))))
            val answered = withTimeoutOrNull(timeoutMs) { box.done.await() } != null
            val still = synchronized(lock) { queries.remove(id) != null }
            if (answered) { if (still) ws.send(closeMsg(id)); return true }
            if (synchronized(lock) { socket !== ws }) return true // dropped meanwhile: handled there
            ws.cancel() // → onFailure → dropped(): waiters fail, onDisconnect, reconnect
            dropped(ws)
            return false
        } finally {
            synchronized(lock) { probing = false }
        }
    }

    /** Stop reconnecting and close. */
    fun stop() {
        synchronized(lock) { keep = false }
        reconnectJob?.cancel()
        close()
    }

    private suspend fun tryConnect() {
        try {
            connect()
            synchronized(lock) { attempts = 0 }
        } catch (e: RelayException) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        val wait = synchronized(lock) {
            if (!keep) return
            attempts++
            Backoff.delayMs(attempts)
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            if (synchronized(lock) { keep } && !isUp) tryConnect()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val all: Map<String, List<JsonObject>> = synchronized(lock) {
                if (socket !== webSocket) return
                isOpen = true
                opening?.complete(Unit)
                opening = null
                LinkedHashMap(subs)
            }
            _state.value = RelayState.UP
            for ((id, f) in all) webSocket.send(req(id, f))
            onConnected?.invoke()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (synchronized(lock) { socket !== webSocket }) return
            try {
                handle(webSocket, text)
            } catch (e: Exception) {
                // a malformed frame must never take the connection down
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            dropped(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
    }

    private class Waiters(val list: List<CompletableDeferred<Unit>>, val boxes: List<QueryBox>, val wasOpen: Boolean)

    /** Under the lock: forget the socket and hand back everyone who waits on it. */
    private fun detach(): Waiters {
        val w = ArrayList<CompletableDeferred<Unit>>()
        opening?.let { w.add(it) }
        opening = null
        w.addAll(pendingOk.values)
        pendingOk.clear()
        w.addAll(authWaiters)
        authWaiters.clear()
        val boxes = queries.values.toList()
        queries.clear()
        val was = isOpen
        socket = null
        isOpen = false
        authed = false
        authEventId = null
        return Waiters(w, boxes, was)
    }

    private fun dropped(ws: WebSocket) {
        val w = synchronized(lock) {
            if (socket !== ws) return
            detach()
        }
        w.list.forEach { it.completeExceptionally(RelayException("connection closed")) }
        w.boxes.forEach { it.done.complete(Unit) }
        _state.value = RelayState.DOWN
        if (w.wasOpen) {
            onDisconnect?.invoke()
            scheduleReconnect()
        }
    }

    /** Closes the socket. Subscriptions are kept for the next connect. */
    fun close() {
        var ws: WebSocket? = null
        val w = synchronized(lock) {
            ws = socket
            detach()
        }
        ws?.cancel()
        w.list.forEach { it.completeExceptionally(RelayException("connection closed")) }
        w.boxes.forEach { it.done.complete(Unit) }
        _state.value = RelayState.DOWN
    }

    // ---- sending --------------------------------------------------------------------------------

    private fun req(id: String, filters: List<JsonObject>): String = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(id))
        filters.forEach { add(it) }
    }.toString()

    private fun closeMsg(id: String): String = buildJsonArray {
        add(JsonPrimitive("CLOSE"))
        add(JsonPrimitive(id))
    }.toString()

    private fun openSocket(): WebSocket? = synchronized(lock) { if (isOpen) socket else null }

    /** Subscribes (replacing a subscription with the same id). Remembered across reconnects. */
    fun subscribe(id: String, filters: List<JsonObject>) {
        synchronized(lock) { subs[id] = filters }
        openSocket()?.send(req(id, filters))
    }

    fun unsubscribe(id: String) {
        synchronized(lock) { subs.remove(id) }
        openSocket()?.send(closeMsg(id))
    }

    private fun resubscribe(ws: WebSocket) {
        val all = synchronized(lock) { LinkedHashMap(subs) }
        for ((id, f) in all) ws.send(req(id, f))
    }

    /**
     * Publishes the event and waits for OK. Connects first if needed ([timeoutMs] for the
     * connection, [timeoutMs] for the answer; SPEC 5: 15 s each). On `auth-required:` it
     * authenticates and publishes once more. Throws [RelayException] with the relay's reason.
     */
    suspend fun publish(event: Event, timeoutMs: Long = 15_000) {
        if (openSocket() == null) connect(timeoutMs)
        try {
            publishOnce(event, timeoutMs)
        } catch (e: RelayException) {
            if (!e.reason.startsWith("auth-required") || authSigner == null) throw e
            waitForAuth(timeoutMs)
            publishOnce(event, timeoutMs)
        }
    }

    private suspend fun publishOnce(event: Event, timeoutMs: Long) {
        val d = CompletableDeferred<Unit>()
        val ws = synchronized(lock) {
            val s = if (isOpen) socket else null
            if (s != null) {
                pendingOk.remove(event.id)?.completeExceptionally(RelayException("timeout: replaced"))
                pendingOk[event.id] = d
            }
            s
        } ?: throw RelayException("timeout: no connection to the relay")
        ws.send("[\"EVENT\"," + event.toJsonString() + "]")
        try {
            withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            synchronized(lock) { if (pendingOk[event.id] === d) pendingOk.remove(event.id) }
            throw RelayException("timeout: the relay did not answer")
        }
    }

    private suspend fun waitForAuth(timeoutMs: Long) {
        val d = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (authed) return
            authWaiters.add(d)
        }
        try {
            withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            synchronized(lock) { authWaiters.remove(d) }
            throw RelayException("timeout: sign-in to the relay")
        }
    }

    /** One-off query: the events up to EOSE (or what came before the timeout). Empty when offline. */
    suspend fun query(filters: List<JsonObject>, timeoutMs: Long = 8_000): List<Event> {
        if (openSocket() == null) {
            try { connect(timeoutMs) } catch (e: RelayException) { return emptyList() }
        }
        val id = "q" + Random.hex(4)
        val box = QueryBox()
        val ws = synchronized(lock) {
            val s = if (isOpen) socket else null
            if (s != null) queries[id] = box
            s
        } ?: return emptyList()
        ws.send(req(id, filters))
        withTimeoutOrNull(timeoutMs) { box.done.await() }
        val still = synchronized(lock) { queries.remove(id) != null }
        if (still) ws.send(closeMsg(id))
        return box.events()
    }

    suspend fun queryOnce(filter: JsonObject, timeoutMs: Long = 8_000): List<Event> = query(listOf(filter), timeoutMs)

    // ---- receiving ------------------------------------------------------------------------------

    private fun handle(ws: WebSocket, text: String) {
        val arr = parseJsonOrNull(text) as? JsonArray ?: return
        when (arr.getOrNull(0).str()) {
            "EVENT" -> {
                val sub = arr.getOrNull(1).str() ?: return
                val ev = arr.getOrNull(2)?.let { Event.fromJson(it) } ?: return
                if (verifySignatures && !ev.isValid()) return
                val q = synchronized(lock) { queries[sub] }
                if (q != null) q.add(ev) else onEvent?.invoke(sub, ev)
            }
            "EOSE" -> {
                val sub = arr.getOrNull(1).str() ?: return
                val q = synchronized(lock) { queries[sub] }
                if (q != null) q.done.complete(Unit) else onEose?.invoke(sub)
            }
            "OK" -> {
                val id = arr.getOrNull(1).str() ?: return
                val ok = (arr.getOrNull(2) as? JsonPrimitive)?.booleanOrNull ?: false
                val reason = arr.getOrNull(3).str() ?: ""
                var authAnswer = false
                var waiters: List<CompletableDeferred<Unit>> = emptyList()
                var pending: CompletableDeferred<Unit>? = null
                synchronized(lock) {
                    if (id == authEventId) {
                        authAnswer = true
                        authEventId = null
                        authed = ok
                        waiters = authWaiters.toList()
                        authWaiters.clear()
                    } else {
                        pending = pendingOk.remove(id)
                    }
                }
                if (authAnswer) {
                    _state.value = RelayState.UP
                    if (ok) {
                        resubscribe(ws)
                        waiters.forEach { it.complete(Unit) }
                        onAuthenticated?.invoke()
                    } else {
                        val why = reason.ifEmpty { "restricted: sign-in refused" }
                        waiters.forEach { it.completeExceptionally(RelayException(why)) }
                        onAuthRefused?.invoke(why)
                    }
                    return
                }
                if (ok) pending?.complete(Unit) else pending?.completeExceptionally(RelayException(reason.ifEmpty { "error: refused" }))
            }
            "CLOSED" -> {
                val sub = arr.getOrNull(1).str() ?: return
                val reason = arr.getOrNull(2).str() ?: ""
                val q = synchronized(lock) { queries[sub] }
                if (q != null) q.done.complete(Unit) else onClosed?.invoke(sub, reason)
            }
            "AUTH" -> {
                val challenge = arr.getOrNull(1).str() ?: return
                val signer = authSigner ?: return
                _state.value = RelayState.AUTH
                scope.launch {
                    try {
                        val ev = signer.sign(Nip98.relayAuth(url, challenge))
                        val current = synchronized(lock) {
                            if (socket === ws) { authEventId = ev.id; true } else false
                        }
                        if (current) ws.send("[\"AUTH\"," + ev.toJsonString() + "]")
                    } catch (e: Exception) {
                        val waiters = synchronized(lock) { val w = authWaiters.toList(); authWaiters.clear(); w }
                        waiters.forEach { it.completeExceptionally(RelayException("timeout: sign-in to the relay")) }
                        if (synchronized(lock) { socket === ws }) _state.value = RelayState.UP
                        onAuthRefused?.invoke("timeout: sign-in to the relay")
                    }
                }
            }
            else -> Unit // NOTICE and anything unknown
        }
    }

    private class QueryBox {
        private val list = ArrayList<Event>()
        val done = CompletableDeferred<Unit>()
        @Synchronized fun add(e: Event) { list.add(e) }
        @Synchronized fun events(): List<Event> = list.toList()
    }

    companion object {
        /** A client suited to relays: pings keep phone connections honest. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
