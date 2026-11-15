package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.SignerTimeout
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.read.ReadState
import sh.belfry.core.store.Store

/**
 * The read state shared by a person's devices (SPEC 19.27): kept in the [Store], merged with every
 * `belfry:read` event of mine that arrives, published (encrypted to myself, through the outbox)
 * once the community relay has answered this session and only when the relay's newest copy lacks
 * something. Guests and the demo keep it on the device ([Host.shared] false).
 */
class ReadSync(
    private val store: Store,
    private val me: String,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val host: Host,
    /** The marks of the old per-device DM store: seeded once, on the first run. */
    seedD: () -> Map<String, Long>,
) {
    interface Host {
        /** False: keep it on the device (guests, guest mode, the demo). */
        fun shared(): Boolean
        suspend fun encryptToSelf(text: String): String
        suspend fun decryptFromSelf(payload: String): String
        /** Queues the kind 30078 for the community relay, created at least at [minCreatedAt], replacing an unsent earlier one. */
        suspend fun publish(content: String, minCreatedAt: Long)
    }

    private val lock = Any()
    private val _state: MutableStateFlow<ReadState>
    val state: StateFlow<ReadState> get() = _state

    /** Told on every change (before the flow), from the thread that made it. */
    @Volatile var onChange: ((ReadState) -> Unit)? = null

    // the newest copy on the relay as far as this device knows (what it published counts too)
    private var remote: ReadState = ReadState.EMPTY
    private var remoteAt = 0L
    @Volatile private var checked = false
    private val handled = HashSet<String>()
    private val backlog = ArrayList<Event>()
    private val busy = Mutex()
    private var timer: Job? = null
    private var lastPublishMs = 0L

    init {
        val kept = store.get(K_STATE)
        val local = if (kept != null) ReadState.parse(kept) else {
            // first run (SPEC 19.27 migration): channels start read, DMs keep their marks
            var r = ReadState(s = clock())
            for ((conv, at) in seedD()) r = r.mark('d', conv, at)
            store.set(K_STATE, r.serialize())
            r
        }
        parseJsonOrNull(store.get(K_REMOTE) ?: "").obj()?.let { o ->
            remoteAt = o["at"].long() ?: 0
            remote = ReadState.parse(o.s("state") ?: "")
        }
        _state = MutableStateFlow(local)
    }

    /** Read SEC/KEY up to AT (never backwards). */
    fun mark(sec: Char, key: String, at: Long) {
        val next = synchronized(lock) {
            val cur = _state.value
            val n = cur.mark(sec, key, at)
            if (n === cur) return
            n
        }
        set(next)
        schedule()
    }

    private fun set(r: ReadState) {
        synchronized(lock) { _state.value = r }
        try { store.set(K_STATE, r.serialize()) } catch (e: Exception) { /* a full disk: kept in memory */ }
        onChange?.invoke(r)
    }

    /** Events that arrived: my `belfry:read` events (exact `d`) wait for [process]. */
    fun offer(events: Collection<Event>) {
        var any = false
        synchronized(lock) {
            for (e in events) {
                if (e.kind != ReadState.KIND || e.pubkey != me || e.tag("d") != ReadState.READ_D || e.id in handled) continue
                if (backlog.none { it.id == e.id }) { backlog.add(e); any = true }
            }
        }
        if (any) scope.launch(Dispatchers.Default) { try { process() } catch (e: Exception) { /* next event or wake-up */ } }
    }

    /** Decrypts and merges what waits. A signer that does not answer leaves the rest for later. */
    suspend fun process() = busy.withLock {
        val todo = synchronized(lock) { backlog.sortedBy { it.createdAt } }
        if (todo.isEmpty()) return@withLock
        for (e in todo) {
            val got = try {
                ReadState.parse(host.decryptFromSelf(e.content))
            } catch (x: SignerTimeout) {
                break
            } catch (x: Exception) {
                null // not readable: ignored
            }
            synchronized(lock) { handled.add(e.id); backlog.removeAll { it.id == e.id } }
            if (got == null) continue
            val next = synchronized(lock) {
                if (e.createdAt >= remoteAt) {
                    remoteAt = e.createdAt
                    remote = got
                    saveRemote()
                }
                ReadState.merge(_state.value, got)
            }
            if (next.serialize() != _state.value.serialize()) set(next)
        }
        schedule()
    }

    /** The community relay answered the subscription (EOSE): publishing may start. */
    fun relayAnswered() {
        if (checked) return
        checked = true
        scope.launch(Dispatchers.Default) {
            try { process() } catch (e: Exception) { }
            schedule()
        }
    }

    private fun needed(): Boolean = synchronized(lock) { !ReadState.covers(remote, _state.value, clock()) }

    /** 3 s after the last change, at most one every 10 s. */
    private fun schedule() {
        if (!checked || !host.shared() || !needed()) return
        synchronized(lock) {
            timer?.cancel()
            timer = scope.launch(Dispatchers.Default) {
                delay(DEBOUNCE_MS)
                val wait = lastPublishMs + MIN_GAP_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                publishNow()
            }
        }
    }

    /** The app goes to the background: publish now what waits (still only after the relay answered). */
    fun flush() {
        if (!checked || !host.shared() || !needed()) return
        synchronized(lock) { timer?.cancel(); timer = null }
        scope.launch(Dispatchers.Default) { publishNow() }
    }

    private suspend fun publishNow() {
        if (!checked || !host.shared() || !needed()) return
        val (out, minAt) = synchronized(lock) { ReadState.merge(_state.value, remote).prune(clock()) to maxOf(remoteAt + 1, clock()) }
        val content = try { host.encryptToSelf(out.serialize()) } catch (e: Exception) { schedule(); return }
        try { host.publish(content, minAt) } catch (e: Exception) { return }
        lastPublishMs = System.currentTimeMillis()
        synchronized(lock) {
            remote = out
            remoteAt = minAt
            saveRemote()
        }
    }

    private fun saveRemote() {
        try {
            store.set(K_REMOTE, buildJsonObject { put("at", JsonPrimitive(remoteAt)); put("state", JsonPrimitive(remote.serialize())) }.toString())
        } catch (e: Exception) { }
    }

    companion object {
        const val K_STATE = "read-state"
        const val K_REMOTE = "read-state-remote"
        const val DEBOUNCE_MS = 3_000L
        const val MIN_GAP_MS = 10_000L
    }
}
