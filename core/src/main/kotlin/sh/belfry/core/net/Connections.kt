package sh.belfry.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one owner of a session's sockets (SPEC 19.50): the groups relay, the community relay and
 * the remote signer's relay. Nothing else opens a socket for the session. It keeps them honest
 * the same way:
 * - [checkAll] (the app came to the foreground, the network changed): every open socket is asked
 *   at once; no answer within [Relay.PROBE_MS] = dead: dropped and opened again now;
 * - in the foreground, a socket quiet for [QUIET_MS] is asked the same way;
 * - before a send, a socket quiet for [Relay.SEND_QUIET_MS] is asked first ([Relay.publish]);
 * - a publish or a signer request that gets no answer drops its socket;
 * - reconnects after 1 s, 2 s, 4 s… up to 30 s ([Backoff]).
 */
class Connections(private val scope: CoroutineScope) {
    class Entry(val role: String, val relay: Relay)

    private val entries = CopyOnWriteArrayList<Entry>()
    private var loop: Job? = null

    /** The app is on screen: quiet sockets are asked ([QUIET_MS]); in the background the system decides. */
    @Volatile var foreground: Boolean = true

    /** Takes R into its care under ROLE ("groups", "main", "signer"). */
    fun add(role: String, r: Relay): Relay {
        entries.removeAll { it.role == role }
        entries.add(Entry(role, r))
        return r
    }

    fun relay(role: String): Relay? = entries.firstOrNull { it.role == role }?.relay

    val all: List<Entry> get() = entries.toList()

    /**
     * Every open socket is asked at once; a dead one is dropped. Then each kept socket that is
     * down reconnects now (the backoff forgotten). Returns when every answer is in (≤ [Relay.PROBE_MS]).
     */
    suspend fun checkAll() {
        val list = entries.toList()
        list.map { e -> scope.async(Dispatchers.Default) { if (e.relay.isUp) e.relay.probe() } }.awaitAll()
        for (e in list) e.relay.wake()
    }

    /** Starts the foreground check of quiet sockets. */
    fun start() {
        if (loop != null) return
        loop = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(TICK_MS)
                if (!foreground) continue
                for (e in entries) if (e.relay.quietFor(QUIET_MS)) launch { if (!e.relay.probe()) e.relay.wake() }
            }
        }
    }

    /** Closes every socket for good (the session ends). */
    fun stop() {
        loop?.cancel()
        loop = null
        for (e in entries) e.relay.stop()
    }

    companion object {
        /** In the foreground, a socket quiet this long is asked whether it lives. */
        const val QUIET_MS = 25_000L
        const val TICK_MS = 5_000L
    }
}
