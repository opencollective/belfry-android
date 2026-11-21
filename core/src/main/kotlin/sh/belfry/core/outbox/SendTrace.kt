package sh.belfry.core.outbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/** A send that is not out yet: since when (ms, [SendTrace]'s clock) and what it is doing now. */
data class SendProgress(val since: Long, val step: String)

/** The line under a message that is not out yet; [retry]: show Retry next to it. */
data class SendLine(val text: String, val retry: Boolean = false)

/**
 * What a send that is not out says (SPEC 19.50): "Sending…"; after [STILL_MS] "Still sending…"
 * and what it is doing; after [NOT_YET_MS] "Not sent yet" with Retry. Offline: waiting for the
 * connection. Refused for good: "Not sent" (the screens add Retry there too).
 */
object SendState {
    const val STILL_MS = 5_000L
    const val NOT_YET_MS = 30_000L
    const val STEP_PREPARE = "preparing"
    const val STEP_SIGN = "signing"
    const val STEP_UPLOAD = "uploading"
    const val STEP_AGAIN = "trying again"

    /**
     * The words people see for a step (the same on the three apps, the lead 2026-11-21): connecting,
     * encrypting, uploading 2.2 of 4.8 MB, waiting for the server. Never "signer" or "relay": the
     * trace keeps the exact step.
     */
    fun shown(step: String): String = when {
        step == "checking the connection" || step == "connecting" || step == STEP_AGAIN -> "connecting"
        step == STEP_PREPARE -> "encrypting"
        step.startsWith(STEP_UPLOAD) -> step
        else -> "waiting for the server" // signing, waiting for the relay, the API's answer
    }

    /** "uploading 2.2 of 4.8 MB" */
    fun uploading(sent: Long, total: Long): String = "$STEP_UPLOAD ${mb(sent)} of ${mb(total)} MB"

    private fun mb(n: Long): String = String.format(java.util.Locale.ROOT, "%.1f", n / 1_000_000.0)

    fun line(failed: Boolean, tries: Int, p: SendProgress?, nowMs: Long, online: Boolean): SendLine {
        if (failed) return SendLine("Not sent", retry = true)
        if (!online) return SendLine("Waiting for the connection…")
        // tried before and not going now (after a restart, the demo's held one): it waits for the connection
        if (p == null) return SendLine(if (tries > 0) "Waiting for the connection…" else "Sending…")
        val first = if (tries > 0) "Sending again…" else "Sending…"
        val waited = nowMs - p.since
        return when {
            waited < STILL_MS -> SendLine(first)
            waited < NOT_YET_MS -> SendLine("Still sending… · ${shown(p.step)}")
            else -> SendLine("Not sent yet", retry = true)
        }
    }

    /** In how many ms [line] says something else without any news (null: never). */
    fun nextChangeMs(p: SendProgress?, nowMs: Long): Long? {
        p ?: return null
        val waited = nowMs - p.since
        return when {
            waited < STILL_MS -> STILL_MS - waited
            waited < NOT_YET_MS -> NOT_YET_MS - waited
            else -> null
        }
    }
}

/**
 * Each send's timing (SPEC 19.50): the steps it went through and how long each took, the last
 * [KEEP] attempts, for About Belfry → Diagnostics. Never any content: what kind of thing it was,
 * the step names, durations and the outcome's class.
 */
class SendTrace(private val nowMs: () -> Long, private val wallMs: () -> Long = System::currentTimeMillis, private val zone: ZoneId = ZoneId.systemDefault()) {
    private class Run(val label: String, val startedAt: Long, val wallAt: Long) {
        val parts = ArrayList<String>()
        var step: String? = null
        var stepAt: Long = startedAt
    }

    private val lock = Any()
    private val runs = HashMap<String, Run>()
    private val lines = ArrayDeque<String>()
    private val _progress = MutableStateFlow<Map<String, SendProgress>>(emptyMap())

    /** Sends not out yet, by message (a direct message's id, else the outbox item's local id). */
    val progress: StateFlow<Map<String, SendProgress>> = _progress

    /** ID is now at STEP (started now if it was not going). LABEL says what it is ("DM", "message"…). */
    fun step(id: String, label: String, step: String) = synchronized(lock) {
        val now = nowMs()
        val r = runs.getOrPut(id) { Run(label, now, wallMs()) }
        // the upload's progress: the same step goes on, only its words change (shown at most every 0.1 MB)
        if (step.startsWith(SendState.STEP_UPLOAD) && r.step?.startsWith(SendState.STEP_UPLOAD) == true) {
            val p = _progress.value[id]
            if (p != null && p.step != step) _progress.value = _progress.value + (id to p.copy(step = step))
            return@synchronized
        }
        close(r, now)
        r.step = step
        r.stepAt = now
        val p = _progress.value
        val since = p[id]?.since ?: now
        _progress.value = p + (id to SendProgress(since, step))
    }

    /** ID's attempt ended (OUTCOME: "OK", "failed", "retry: <reason class>"); LEFT: it still waits (shown, its time goes on). */
    fun end(id: String, outcome: String, left: Boolean) = synchronized(lock) {
        val now = nowMs()
        val r = runs.remove(id)
        if (r != null) {
            close(r, now)
            val at = DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(r.wallAt).atZone(zone))
            push("$at ${r.label} · " + (r.parts + "→ $outcome ${secs(now - r.startedAt)}").joinToString(" · "))
        }
        val p = _progress.value
        _progress.value = when {
            !left -> p - id
            outcome.startsWith("retry") -> p[id]?.let { p + (id to it.copy(step = SendState.STEP_AGAIN)) } ?: p
            else -> p
        }
    }

    /** Send FROM is now known as TO (a file's local id → its rumor's id): its run and its time go on. */
    fun rename(from: String, to: String) = synchronized(lock) {
        runs.remove(from)?.let { runs[to] = it }
        val p = _progress.value
        p[from]?.let { _progress.value = (p - from) + (to to it) }
    }

    /** Forgets ID (out, discarded). */
    fun forget(id: String) = synchronized(lock) {
        runs.remove(id)
        if (id in _progress.value) _progress.value = _progress.value - id
    }

    /** Keeps only the sends in IDS (what is left in the outbox). */
    fun keepOnly(ids: Set<String>) = synchronized(lock) {
        val p = _progress.value
        if (p.keys.any { it !in ids }) _progress.value = p.filterKeys { it in ids }
    }

    /** Everything that waits starts counting again (Retry, back online): a fresh 30 s. */
    fun restart() = synchronized(lock) {
        val now = nowMs()
        _progress.value = _progress.value.mapValues { it.value.copy(since = now) }
    }

    /** A line of its own (a slow signer request, a dropped socket). */
    fun note(text: String) = synchronized(lock) {
        val at = DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(wallMs()).atZone(zone))
        push("$at $text")
    }

    /** The last [KEEP] lines, oldest first. */
    fun lines(): List<String> = synchronized(lock) { lines.toList() }

    private fun close(r: Run, now: Long) {
        r.step?.let { r.parts.add((if (it.startsWith(SendState.STEP_UPLOAD)) SendState.STEP_UPLOAD else it) + " " + secs(now - r.stepAt)) }
        r.step = null
    }

    private fun push(line: String) {
        lines.addLast(line)
        while (lines.size > KEEP) lines.removeFirst()
    }

    companion object {
        const val KEEP = 20

        fun secs(ms: Long): String = String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0)

        /** What kind of thing an outbox item is, for the trace (never its content). */
        fun label(item: OutboxItem): String = when {
            item.dm != null -> "DM"
            item.target == Target.API -> "API ${item.path ?: ""}".trim()
            item.kind == 30078 -> "read state"
            item.kind == 9 || item.kind == 11 || item.kind == 1111 -> "message"
            item.kind == 7 -> "reaction"
            else -> "kind ${item.kind}"
        }
    }
}
