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

/** SPEC 4 / 19.50: reconnect, and try a send again, after 1 s, 2 s, 4 s, 8 s, 16 s, then every 30 s. */
object Backoff {
    fun delayMs(attempt: Int): Long = minOf(30_000L, 1_000L shl (attempt - 1).coerceIn(0, 15))
}

/** SPEC 4: does this refusal mean "no access (yet)"? Only the groups relay's verdict counts. */
object Access {
    private val REFUSAL = Regex("^(restricted|blocked)|pass|member", RegexOption.IGNORE_CASE)
    fun isRefusal(reason: String): Boolean = REFUSAL.containsMatchIn(reason)

    // "Almost in" only on a definite answer (SPEC 19.79; web 3fc34bd sync.ts `aboutMembership`; Xavier
    // 2026-11-23: a member saw it after "restricted: connection rate limit exceeded"): a refusal is
    // about membership only when its words say so and not a limit, a connection or a timeout.
    private val MEMBERSHIP = Regex("^(restricted|blocked)\\b|\\bpass\\b|member|invite|not allowed|not permitted", RegexOption.IGNORE_CASE)
    private val NOT_MEMBERSHIP = Regex("rate|limit|too many|slow down|connection|timeout|timed out|later|busy|overload|error|network", RegexOption.IGNORE_CASE)
    fun aboutMembership(reason: String): Boolean = MEMBERSHIP.containsMatchIn(reason) && !NOT_MEMBERSHIP.containsMatchIn(reason)

    private val RATE = Regex("rate.?limit|too many|slow down", RegexOption.IGNORE_CASE)
    /** Refused for rate ("rate-limited: slow down", "restricted: connection rate limit exceeded"): asked again after a back-off, never failed for it. */
    fun rateLimited(reason: String): Boolean = RATE.containsMatchIn(reason)
}

/**
 * What to do when a relay ends the live subscription with CLOSED (SPEC 19.79; web 3fc34bd, 92e5012
 * sync.ts `resubscribe`). Without a new subscription nothing arrives until a restart.
 */
object Reopen {
    enum class How {
        /** `auth-required` (NIP-42 runs, the subscriptions are sent again after it), or a refusal about membership: the register is asked (groups), nothing is opened again. */
        NEVER,
        /** An ordinary close: again 2 s later, then 4, 8… up to a minute if the relay keeps closing it. */
        SOON,
        /** A refusal that can pass (a rate limit; the community relay's "not in whitelist"): 2 s, doubling up to 2 minutes, so the relay is never hammered. */
        BACKED_OFF,
    }

    /** MAIN: the community relay (else the groups relay). */
    fun how(main: Boolean, reason: String): How = when {
        reason.startsWith("auth-required") -> How.NEVER
        // the community relay's whitelist (a guest, a removed member, a member just let in): exactly this reason
        main && sh.belfry.core.outbox.Outbox.notListed(reason) -> How.BACKED_OFF
        Access.aboutMembership(reason) -> How.NEVER
        // a refusal in the relay's words that is not about membership (a limit, a connection, an error), or any rate limit
        Access.isRefusal(reason) || Access.rateLimited(reason) -> How.BACKED_OFF
        else -> How.SOON
    }

    /** The N-th wait after an ordinary close (N from 0): 2 s, 4, 8, 16, 32, then a minute. */
    fun soonMs(n: Int): Long = minOf(60_000L, 2_000L shl n.coerceIn(0, 5))

    /** The N-th wait after a refusal that can pass (N from 0): 2 s doubling to 2 minutes. */
    fun backedOffMs(n: Int): Long = minOf(120_000L, 2_000L shl n.coerceIn(0, 6))
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
    /** An event of the live subscriptions this device holds already: skipped before its signature is checked (a replay costs little). */
    @Volatile var known: ((String) -> Boolean)? = null

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

    /** NIP-42 done on this socket (an EOSE before it may hold only what the relay shows anyone). */
    val isAuthenticated: Boolean get() = synchronized(lock) { authed }

    /** The relay's last answer to our NIP-42 sign-in on any socket (Diagnostics; SPEC 19.91): null = none yet. */
    @Volatile var signIn: SignInNote? = null
        private set

    private val lock = Any()
    private var socket: WebSocket? = null
    private var isOpen = false
    private var opening: CompletableDeferred<Unit>? = null
    private val pendingOk = HashMap<String, CompletableDeferred<Unit>>()
    private val subs = LinkedHashMap<String, List<JsonObject>>()
    private var authEventId: String? = null
    private var authed = false
    /** An AUTH challenge came on the current socket (NIP-42). */
    private var challenged = false
    /** The socket on which a wait for AUTH ended with no challenge at all: a relay that doesn't ask; never waited on again. */
    private var noChallengeOn: WebSocket? = null
    private val authWaiters = ArrayList<CompletableDeferred<Unit>>()
    private val queries = HashMap<String, QueryBox>()

    private var keep = false
    private var probing = false
    /** When something last came on the socket (or it opened), ms on a clock that only goes on. */
    @Volatile private var heardAt = 0L

    /** How long a probe waits for an answer (a seam for tests). */
    @Volatile var probeMs: Long = PROBE_MS
    /** How long a one-shot read waits for the relay's AUTH (a seam for tests; SPEC 19.91). */
    @Volatile var authWaitMs: Long = AUTH_WAIT_MS
    /** A socket quiet longer than this is asked before a send goes into it (a seam for tests). */
    @Volatile var sendQuietMs: Long = SEND_QUIET_MS

    /** How long the open socket has been quiet (ms); 0 when it is not open. */
    fun quietMs(): Long = if (openSocket() == null) 0 else nowMs() - heardAt

    /** The socket is not known to be alive: open and quiet for longer than MS. */
    fun quietFor(ms: Long): Boolean = openSocket() != null && quietMs() > ms
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
                challenged = false
                authEventId = null
                _state.value = RelayState.CONNECTING
                socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
                socketsMade++
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
    suspend fun probe(timeoutMs: Long = probeMs): Boolean {
        val ws = synchronized(lock) { if (isOpen && !probing) { probing = true; socket } else null } ?: return true
        try {
            val id = "p" + Random.hex(4)
            val box = QueryBox()
            synchronized(lock) { if (socket === ws) queries[id] = box }
            val asked = nowMs()
            ws.send(req(id, listOf(Filters.of(kinds = listOf(39000), limit = 1))))
            // its answer, or anything else that came meanwhile: the socket lives
            val answered = withTimeoutOrNull(timeoutMs) { box.done.await() } != null || heardAt >= asked
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

    /** Drops the socket as dead (a publish or a request that got no answer): who waits fails, [onDisconnect] runs, and it reconnects when kept. */
    fun drop() {
        val ws = synchronized(lock) { socket } ?: return
        ws.cancel()
        dropped(ws)
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
                heardAt = nowMs()
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
            heardAt = nowMs()
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
        challenged = false
        authEventId = null
        return Waiters(w, boxes, was)
    }

    /** Drops seen: (handled, ignored as not the current socket, handled while not yet open). Tests print them. */
    internal val dropCounts = IntArray(3)
    /** Sockets opened by this client. */
    @Volatile internal var socketsMade = 0

    private fun dropped(ws: WebSocket) {
        val w = synchronized(lock) {
            if (socket !== ws) { dropCounts[1]++; return }
            detach()
        }
        if (w.wasOpen) dropCounts[0]++ else dropCounts[2]++
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
     * Publishes the event and waits for OK. A socket quiet for more than [sendQuietMs] is asked
     * first ([probe]): a dead one is dropped before the event goes into it (SPEC 19.50). Connects
     * first if needed ([timeoutMs] for the connection, [timeoutMs] for the answer; SPEC 5: 15 s
     * each). No answer in time drops the socket (it is dead). On `auth-required:` it authenticates
     * and publishes once more. Throws [RelayException] with the relay's reason. [step] hears what
     * it is doing ("checking the connection", "connecting", "waiting for the relay").
     */
    suspend fun publish(event: Event, timeoutMs: Long = 15_000, step: (String) -> Unit = {}) {
        if (quietFor(sendQuietMs)) { step(STEP_CHECK); probe() }
        if (openSocket() == null) { step(STEP_CONNECT); connect(timeoutMs) }
        step(STEP_WAIT)
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
            val same = synchronized(lock) { if (pendingOk[event.id] === d) pendingOk.remove(event.id); socket === ws }
            // an open socket that does not answer is dead (a phone that slept, a proxy restart): drop it now,
            // it reconnects in a second and the next try goes on a fresh one (SPEC 19.50)
            if (same) { ws.cancel(); dropped(ws) }
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
    suspend fun query(filters: List<JsonObject>, timeoutMs: Long = 8_000): List<Event> = ask(filters, timeoutMs)?.first ?: emptyList()

    /**
     * The events the relay has for FILTERS, or null when it didn't finish (no EOSE within
     * TIMEOUT_MS, no connection): "no answer" is not "nothing there".
     */
    suspend fun queryUntilEose(filters: List<JsonObject>, timeoutMs: Long = 8_000): List<Event>? =
        ask(filters, timeoutMs)?.takeIf { it.second }?.first

    /**
     * For sealed conversations: the events, and whether the answer is COMPLETE knowledge (EOSE
     * reached after NIP-42 on this same socket; sealed.md 294c94f (7)). Null without a connection.
     */
    suspend fun queryKnown(filters: List<JsonObject>, timeoutMs: Long = 8_000): Pair<List<Event>, Boolean>? {
        // the relay asked for AUTH and the sign-in is under way: the REQ goes out once it is done (SPEC 19.91). Before
        // any challenge it goes at once: an answer sent before AUTH is never complete knowledge (below)
        if (openSocket() == null) { try { connect(timeoutMs) } catch (e: RelayException) { return null } }
        if (authSigner != null && synchronized(lock) { challenged && !authed }) awaitSignIn(minOf(timeoutMs, authWaitMs))
        val box = QueryBox()
        val before = synchronized(lock) { socket }
        val a = ask(filters, timeoutMs, box) ?: return null
        // complete = EOSE (a CLOSED is no answer), the REQ sent after AUTH, on the same socket still
        val complete = a.second && !box.closed && box.authedAtSend && synchronized(lock) { socket === before && socket != null }
        return a.first to complete
    }

    /** What arrived, and whether EOSE came; null without a connection. */
    private suspend fun ask(filters: List<JsonObject>, timeoutMs: Long, given: QueryBox? = null): Pair<List<Event>, Boolean>? {
        if (openSocket() == null) {
            try { connect(timeoutMs) } catch (e: RelayException) { return null }
        }
        val id = "q" + Random.hex(4)
        val box = given ?: QueryBox()
        val ws = synchronized(lock) {
            val s = if (isOpen) socket else null
            if (s != null) { queries[id] = box; box.authedAtSend = authed }
            s
        } ?: return null
        ws.send(req(id, filters))
        // a CLOSED (auth-required, restricted…) ends the wait but is no answer: never "finished" (web b5c78fa)
        val finished = withTimeoutOrNull(timeoutMs) { box.done.await() } != null && !box.closed
        val still = synchronized(lock) { queries.remove(id) != null }
        if (still) ws.send(closeMsg(id))
        return box.events() to finished
    }

    suspend fun queryOnce(filter: JsonObject, timeoutMs: Long = 8_000): List<Event> = query(listOf(filter), timeoutMs)

    /**
     * A one-shot read that counts only on a signed-in socket (SPEC 19.91; the web's `queryAuthed`, b5c78fa / 9640d99):
     * a REQ sent before NIP-42 AUTH is CLOSED "auth-required" on the belfries' relays, and that is never "nothing
     * there". It waits for AUTH first (a relay that never asks, within [AUTH_WAIT_MS], is read as it is), then takes
     * only an EOSE on the same socket, after AUTH. Tried [tries] times ([retryMs] × n apart). Null: no complete
     * answer, so nothing is known (the caller asks again later and never takes it for empty).
     */
    suspend fun queryAuthed(filters: List<JsonObject>, timeoutMs: Long = 8_000, tries: Int = 3, retryMs: Long = 2_500): List<Event>? {
        for (i in 0 until tries) {
            if (i > 0) delay(retryMs * i)
            readSignedIn(filters, timeoutMs)?.let { return it }
        }
        return null
    }

    private suspend fun readSignedIn(filters: List<JsonObject>, timeoutMs: Long): List<Event>? {
        if (openSocket() == null) { try { connect(timeoutMs) } catch (e: RelayException) { return null } }
        val before = synchronized(lock) { socket } ?: return null
        val signedIn = awaitSignIn(minOf(timeoutMs, authWaitMs))
        val asks = synchronized(lock) { socket !== before || challenged || authEventId != null }
        // refused, or a challenge not answered in time, or the socket changed meanwhile: nothing known
        if (authSigner != null && !signedIn && asks) return null
        val box = QueryBox()
        val a = ask(filters, timeoutMs, box) ?: return null
        val same = synchronized(lock) { socket === before }
        if (!a.second || box.closed || !same) return null
        // the relay asked for AUTH after the REQ went out: the answer may hold only its public part
        if (authSigner != null && !box.authedAtSend && synchronized(lock) { challenged }) return null
        return a.first
    }

    /**
     * Waits for NIP-42 on the open socket when this relay has a signer and isn't signed in yet. True: signed in. A wait
     * that ends with no challenge at all marks the socket as one whose relay doesn't ask (no second wait on it).
     */
    private suspend fun awaitSignIn(ms: Long): Boolean {
        if (authSigner == null) return false
        val ws = synchronized(lock) {
            if (authed) return true
            val s = socket ?: return false
            if (noChallengeOn === s) return false
            s
        }
        return try {
            waitForAuth(ms)
            true
        } catch (e: RelayException) {
            synchronized(lock) { if (socket === ws && !challenged && authEventId == null && !authed) noChallengeOn = ws }
            synchronized(lock) { authed && socket === ws }
        }
    }

    // ---- receiving ------------------------------------------------------------------------------

    private fun handle(ws: WebSocket, text: String) {
        val arr = parseJsonOrNull(text) as? JsonArray ?: return
        when (arr.getOrNull(0).str()) {
            "EVENT" -> {
                val sub = arr.getOrNull(1).str() ?: return
                val ev = arr.getOrNull(2)?.let { Event.fromJson(it) } ?: return
                val q = synchronized(lock) { queries[sub] }
                if (q == null && known?.invoke(ev.id) == true) return
                if (verifySignatures && !ev.isValid()) return
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
                    signIn = SignInNote(ok, System.currentTimeMillis(), if (ok) "" else SignInNote.word(reason))
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
                if (q != null) { q.closed = true; q.done.complete(Unit) } else onClosed?.invoke(sub, reason)
            }
            "AUTH" -> {
                val challenge = arr.getOrNull(1).str() ?: return
                val signer = authSigner ?: return
                synchronized(lock) { if (socket === ws) challenged = true }
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
                        signIn = SignInNote(false, System.currentTimeMillis(), "timeout")
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
        /** The relay CLOSED it: not an answer (sealed.md §5.1). */
        @Volatile var closed = false
        /** NIP-42 was done on this socket when the REQ went out. */
        @Volatile var authedAtSend = false
        @Synchronized fun add(e: Event) { list.add(e) }
        @Synchronized fun events(): List<Event> = list.toList()
    }

    companion object {
        /** An answer to a probe within this, or the socket is dead (SPEC 19.50). */
        const val PROBE_MS = 5_000L
        /** How long a one-shot read waits for the relay's AUTH before it goes out (SPEC 19.91). */
        const val AUTH_WAIT_MS = 5_000L
        /** A socket quiet for longer is asked before a send goes into it. */
        const val SEND_QUIET_MS = 10_000L
        const val STEP_CHECK = "checking the connection"
        const val STEP_CONNECT = "connecting"
        const val STEP_WAIT = "waiting for the relay"

        /** A clock for durations: never jumps with the wall clock. */
        fun nowMs(): Long = System.nanoTime() / 1_000_000

        /** A client suited to relays: pings keep phone connections honest. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

/** How the relay answered our NIP-42 sign-in: OK or refused, when (wall clock, ms), and the refusal's machine word only (never its text). */
data class SignInNote(val ok: Boolean, val atMs: Long, val why: String) {
    companion object {
        /** "restricted: not you" → "restricted": the NIP-01 prefix, never the relay's words (UI-18). */
        fun word(reason: String): String = reason.substringBefore(':').trim().lowercase().filter { it in 'a'..'z' || it == '-' }.take(30).ifEmpty { "refused" }
    }
}
