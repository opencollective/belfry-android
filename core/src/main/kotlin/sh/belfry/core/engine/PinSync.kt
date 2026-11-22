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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.model.OneShape
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.SignerTimeout
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.str
import sh.belfry.core.store.Store

/**
 * The channels I pinned for myself (docs/one-shape.md §1, SPEC 19.53): one kind 30078
 * `d=belfry:pins` per person on the community relay, its content NIP-44 v2 encrypted to myself,
 * `{"v":1,"c":[group ids]}`. Unlike the read state there is nothing to merge: the newest copy wins
 * (mine on this device included, by the time it was changed). Published through the outbox once
 * the community relay has answered this session, and only when this device holds something newer
 * than the relay's copy. Guests, guest mode and the demo keep it on the device ([Host.shared] false).
 */
class PinSync(
    private val store: Store,
    private val me: String,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        /** False: keep it on the device (guests, guest mode, the demo). */
        fun shared(): Boolean
        suspend fun encryptToSelf(text: String): String
        suspend fun decryptFromSelf(payload: String): String
        /** Queues the kind 30078 for the community relay, created at least at [minCreatedAt], replacing an unsent earlier one. */
        suspend fun publish(content: String, minCreatedAt: Long)
    }

    /** The list and when it was set (Unix seconds; 0 = never). */
    data class Pins(val c: List<String> = emptyList(), val at: Long = 0)

    private val lock = Any()
    private val _state: MutableStateFlow<Pins>
    /** My pins, in the order I pinned them. */
    val state: StateFlow<Pins> get() = _state

    // the relay's newest copy as far as this device knows (what it published counts too)
    private var remoteAt = 0L
    @Volatile private var checked = false
    private val handled = HashSet<String>()
    private val backlog = ArrayList<Event>()
    private val busy = Mutex()
    private var timer: Job? = null

    init {
        val o = parseJsonOrNull(store.get(K_STATE) ?: "").obj()
        val local = if (o == null) Pins() else Pins(o["c"].arr()?.mapNotNull { it.str() }.orEmpty().distinct(), o["at"].long() ?: 0)
        remoteAt = store.get(K_REMOTE_AT)?.toLongOrNull() ?: 0
        _state = MutableStateFlow(local)
    }

    /** Pins [channel] for me, or unpins it. */
    fun toggle(channel: String) {
        synchronized(lock) {
            val cur = _state.value
            set(Pins(OneShape.togglePin(cur.c, channel), maxOf(clock(), cur.at + 1, remoteAt + 1)))
        }
        schedule()
    }

    private fun set(p: Pins) {
        synchronized(lock) { _state.value = p }
        try {
            store.set(K_STATE, buildJsonObject { put("at", JsonPrimitive(p.at)); put("c", JsonArray(p.c.map { JsonPrimitive(it) })) }.toString())
        } catch (e: Exception) { /* a full disk: kept in memory */ }
    }

    /** Events that arrived: my `belfry:pins` events (exact `d`) wait for [process]. */
    fun offer(events: Collection<Event>) {
        var any = false
        synchronized(lock) {
            for (e in events) {
                if (e.kind != KIND || e.pubkey != me || e.tag("d") != OneShape.PINS_D || e.id in handled) continue
                if (backlog.none { it.id == e.id }) { backlog.add(e); any = true }
            }
        }
        if (any) scope.launch(Dispatchers.Default) { try { process() } catch (e: Exception) { /* next event or wake-up */ } }
    }

    /** Decrypts what waits; a copy newer than this device's list replaces it. A signer that does not answer leaves the rest for later. */
    suspend fun process() = busy.withLock {
        val todo = synchronized(lock) { backlog.sortedBy { it.createdAt } }
        for (e in todo) {
            val got = try {
                parse(host.decryptFromSelf(e.content))
            } catch (x: SignerTimeout) {
                break
            } catch (x: Exception) {
                null // not readable: ignored
            }
            synchronized(lock) {
                handled.add(e.id); backlog.removeAll { it.id == e.id }
                if (got != null) {
                    if (e.createdAt > remoteAt) { remoteAt = e.createdAt; saveRemote() }
                    // newest wins; at the same second the relay's copy (what every device sees) wins
                    if (e.createdAt >= _state.value.at) set(Pins(got, e.createdAt))
                }
            }
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

    private fun needed(): Boolean = synchronized(lock) { _state.value.at > remoteAt }

    private fun schedule() {
        if (!checked || !host.shared() || !needed()) return
        synchronized(lock) {
            timer?.cancel()
            timer = scope.launch(Dispatchers.Default) {
                delay(DEBOUNCE_MS)
                publishNow()
            }
        }
    }

    private suspend fun publishNow() {
        if (!checked || !host.shared() || !needed()) return
        val p = _state.value
        val content = try { host.encryptToSelf(OneShape.pinsContent(p.c)) } catch (e: Exception) { return }
        try { host.publish(content, p.at) } catch (e: Exception) { return }
        synchronized(lock) {
            if (p.at > remoteAt) { remoteAt = p.at; saveRemote() }
        }
    }

    private fun saveRemote() {
        try { store.set(K_REMOTE_AT, remoteAt.toString()) } catch (e: Exception) { }
    }

    companion object {
        const val KIND = 30078
        const val K_STATE = "pins"
        const val K_REMOTE_AT = "pins-remote-at"
        const val DEBOUNCE_MS = 1_500L

        /** `{"v":1,"c":[ids]}` → the ids (strings, first of each); null when it is not that shape. */
        fun parse(text: String): List<String>? {
            val o = parseJsonOrNull(text).obj() ?: return null
            val arr = o["c"] as? JsonArray ?: return null
            return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.distinct()
        }

        /** What the demo (or a test) keeps on the device. */
        fun localContent(c: List<String>, at: Long): String =
            buildJsonObject { put("at", JsonPrimitive(at)); put("c", JsonArray(c.map { JsonPrimitive(it) })) }.toString()
    }
}
