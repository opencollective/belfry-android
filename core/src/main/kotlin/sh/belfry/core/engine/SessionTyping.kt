package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.model.World
import sh.belfry.core.model.isArchived
import sh.belfry.core.net.Relay
import sh.belfry.core.net.RelayException
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.Signer
import sh.belfry.core.outbox.Target
import sh.belfry.core.typing.Typing
import sh.belfry.core.typing.TypingBoard
import sh.belfry.core.typing.TypingPlace
import sh.belfry.core.typing.TypingSender
import sh.belfry.core.typing.TypingState

/**
 * A session's typing indicators (docs/typing.md): who types in the places I have open, and my
 * own typing events, best effort (never the outbox, never retried, the signer gets 3 s). The
 * groups relay is asked for kind 20100 in the groups on screen ([watch]); the community relay for
 * the 21059 wraps to me (always, for the conversations list).
 */
internal class SessionTyping(
    private val scope: CoroutineScope,
    private val me: String,
    private val signer: Signer,
    private val clock: () -> Long,
    private val relay: (Target) -> Relay?,
    private val world: () -> World,
    /** False in guest mode (reading guests have no name), the demo, or after stop. */
    private val canSend: () -> Boolean,
) {
    private val board = TypingBoard()
    private val sender = TypingSender()
    private val _state = MutableStateFlow(TypingState())
    val state: StateFlow<TypingState> = _state

    /** The device's "Show others when I'm typing" (on by default). */
    @Volatile var enabled: Boolean = true

    private val watched = HashMap<String, Int>()
    private val pausedUntil = HashMap<Target, Long>()
    private var expiry: Job? = null

    // ---- subscriptions ----

    /** The groups relay sends typing events of [group] while at least one screen watches it. */
    fun watch(group: String): AutoCloseable {
        synchronized(watched) { watched[group] = (watched[group] ?: 0) + 1 }
        resubscribeGroups()
        return AutoCloseable {
            synchronized(watched) { val n = (watched[group] ?: 1) - 1; if (n <= 0) watched.remove(group) else watched[group] = n }
            resubscribeGroups()
        }
    }

    private fun resubscribeGroups() {
        val r = relay(Target.GROUPS) ?: return
        val groups = synchronized(watched) { watched.keys.sorted() }
        if (groups.isEmpty()) { r.unsubscribe(SUB_GROUPS); return }
        r.subscribe(SUB_GROUPS, listOf(filter(Typing.KIND, "#h", groups)))
    }

    /** The community relay sends the typing wraps for me (called once the session starts). */
    fun start() {
        relay(Target.MAIN)?.subscribe(SUB_DMS, listOf(filter(Typing.WRAP_KIND, "#p", listOf(me))))
        resubscribeGroups()
    }

    private fun filter(kind: Int, tag: String, values: List<String>): JsonObject = buildJsonObject {
        put("kinds", buildJsonArray { add(JsonPrimitive(kind)) })
        put(tag, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        put("since", clock() - Typing.SINCE_S)
    }

    // ---- reading ----

    /** An event from a relay: true when it was a typing event (handled here, never stored). */
    fun offer(e: Event): Boolean = when (e.kind) {
        Typing.KIND -> { onGroupEvent(e); true }
        Typing.WRAP_KIND -> { onWrap(e); true }
        else -> false
    }

    private fun onGroupEvent(e: Event) {
        if (e.pubkey == me) return // any device of mine
        val place = Typing.placeOfGroupEvent(e) ?: return
        val w = world()
        val g = w.group(place.group!!) ?: return // a group I don't know
        if (e.pubkey in w.readingOnly) return
        if (g.members.isNotEmpty() && g.closed && e.pubkey !in g.members) return
        if (board.apply(place, e.pubkey, e.createdAt, Typing.isStop(e.tags), clock())) changed()
    }

    private fun onWrap(e: Event) {
        if (!e.hasTag("p", me) || !Typing.fresh(e.createdAt, clock())) return // replays cost no signer call
        scope.launch(Dispatchers.Default) {
            try {
                val opened = withTimeout(Typing.SIGNER_MS) { GiftWrap.unwrap(e, signer) } ?: return@launch
                val r = opened.rumor
                if (r.pubkey == me) return@launch
                val place = Typing.placeOfRumor(r, me) ?: return@launch
                if (board.apply(place, r.pubkey, r.createdAt, Typing.isStop(r.tags), clock())) changed()
            } catch (x: kotlinx.coroutines.CancellationException) {
                if (x is kotlinx.coroutines.TimeoutCancellationException) return@launch
                throw x
            } catch (x: Exception) { /* not for me, or broken */ }
        }
    }

    private fun changed() {
        val now = clock()
        board.prune(now)
        _state.value = board.snapshot(now)
        val next = board.nextChange(now) ?: return
        synchronized(this) {
            expiry?.cancel()
            expiry = scope.launch(Dispatchers.Default) {
                delay(((next - clock()).coerceAtLeast(0) * 1000) + 50)
                changed()
            }
        }
    }

    /** The demo and the screenshot tests: [pubkey] types in [place] now. */
    fun show(place: TypingPlace, pubkey: String) {
        if (board.apply(place, pubkey, clock(), stop = false, now = clock())) changed()
    }

    // ---- writing ----

    /** A keystroke left [text] in the field of [place]; [restored] = a draft put back or an edit (never sent for). */
    fun typed(place: TypingPlace, text: String, restored: Boolean = false) {
        if (!allowed(place)) return
        if (sender.onText(place, text, clock(), restored, enabled)) send(place, stop = false)
    }

    /** The field was emptied without sending. */
    fun cleared(place: TypingPlace) {
        if (sender.onCleared(place, clock(), enabled) && allowed(place)) send(place, stop = true)
    }

    /** The message went: no stop needed. */
    fun sent(place: TypingPlace) = sender.onSent(place)

    private fun allowed(place: TypingPlace): Boolean {
        if (!canSend()) return false
        val g = place.group
        if (g != null && world().isArchived(g)) return false
        if (place.conv != null && Typing.recipients(place.conv, me).isEmpty()) return false
        return true
    }

    private fun send(place: TypingPlace, stop: Boolean) {
        val target = if (place.isDm) Target.MAIN else Target.GROUPS
        scope.launch(Dispatchers.Default) {
            val now = clock()
            if ((pausedUntil[target] ?: 0) > now) return@launch
            val r = relay(target)?.takeIf { it.isUp } ?: return@launch // best effort: the socket is down
            try {
                val events = withTimeout(Typing.SIGNER_MS) {
                    if (place.isDm) {
                        val to = Typing.recipients(place.conv!!, me)
                        val rumor = Typing.dmRumor(me, to, stop, now)
                        to.map { GiftWrap.wrapEphemeral(rumor, it, signer, now) }
                    } else {
                        listOf(signer.sign(Typing.groupTemplate(place, stop, now)))
                    }
                }
                for (ev in events) r.publish(ev, timeoutMs = PUBLISH_MS)
            } catch (x: RelayException) {
                if (x.reason.startsWith("rate-limited")) pausedUntil[target] = clock() + Typing.PAUSE_S
            } catch (x: kotlinx.coroutines.CancellationException) {
                if (x !is kotlinx.coroutines.TimeoutCancellationException) throw x
            } catch (x: Exception) { /* dropped */ }
        }
    }

    companion object {
        const val SUB_GROUPS = "typing"
        const val SUB_DMS = "typing-dm"
        const val PUBLISH_MS = 3_000L
    }
}
