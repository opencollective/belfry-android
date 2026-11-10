package sh.belfry.core.outbox

import sh.belfry.core.nostr.Event
import java.util.regex.Pattern

// The outbox rules (SPEC 5): classified errors, created_at that only grows, and one flush pass.
// The pass is pure so it is tested against vectors/outbox.json; the engine runs it with real
// relays and writes the result to the store.

enum class ErrorClass(val key: String) {
    /** The relay has it already: counts as sent. */
    DUPLICATE("duplicate"),
    /** Refused for good: kept as failed with its reason, does not block later items. */
    FAILED("failed"),
    /** Anything else (timeouts, auth-required, rate-limited, no connection, upload errors): try again later. */
    RETRY("retry"),
}

object Outbox {
    private val PERMANENT_PREFIX = Pattern.compile("^(blocked|invalid|restricted|pow|duplicate|error):")
    private val PERMANENT_WORDS = Pattern.compile("not allowed|denied|unauthori[sz]ed", Pattern.CASE_INSENSITIVE)

    fun classify(message: String): ErrorClass = when {
        message.startsWith("duplicate:") -> ErrorClass.DUPLICATE
        PERMANENT_PREFIX.matcher(message).find() || PERMANENT_WORDS.matcher(message).find() -> ErrorClass.FAILED
        else -> ErrorClass.RETRY
    }

    /** created_at strictly increasing per client: `max(last + 1, now)`. */
    fun nextCreatedAt(last: Long, now: Long = System.currentTimeMillis() / 1000): Long = maxOf(last + 1, now)

    /** The line under a message that is not out yet; null once nothing needs saying. */
    fun stateText(item: OutboxItem): String = when {
        item.status == OutboxStatus.FAILED -> "Not sent"
        item.tries > 0 -> "Waiting for connection…"
        else -> "Sending…"
    }

    /**
     * One flush pass over [items]. [send] signs (unless pre-signed), uploads and publishes one
     * item and says how it went. Items are walked by created_at; failed ones are skipped; after a
     * retryable error the item's target is stalled for the rest of the pass (order kept per relay).
     */
    suspend fun run(items: List<OutboxItem>, send: suspend (OutboxItem) -> SendOutcome): PassResult {
        val box = items.toMutableList()
        val attempted = ArrayList<String>()
        val sent = ArrayList<Sent>()
        val failed = ArrayList<OutboxItem>()
        val stalled = HashSet<Target>()
        fun replace(next: OutboxItem) {
            val i = box.indexOfFirst { it.localId == next.localId }
            if (i >= 0) box[i] = next
        }
        for (item in items.sortedBy { it.createdAt }) {
            if (item.status == OutboxStatus.FAILED || item.target in stalled) continue
            attempted.add(item.localId)
            when (val outcome = send(item)) {
                is SendOutcome.Ok -> {
                    box.removeAll { it.localId == item.localId }
                    sent.add(Sent(item, outcome.event ?: item.signed))
                }
                is SendOutcome.Error -> when (classify(outcome.message)) {
                    ErrorClass.DUPLICATE -> {
                        box.removeAll { it.localId == item.localId }
                        sent.add(Sent(item, item.signed))
                    }
                    ErrorClass.FAILED -> {
                        val next = item.copy(tries = item.tries + 1, status = OutboxStatus.FAILED, error = outcome.message)
                        replace(next)
                        failed.add(next)
                    }
                    ErrorClass.RETRY -> {
                        replace(item.copy(tries = item.tries + 1, status = OutboxStatus.QUEUED, error = outcome.message))
                        stalled.add(item.target)
                    }
                }
            }
        }
        return PassResult(attempted, sent, failed, box, stalled)
    }
}

/** How publishing one item went. */
sealed interface SendOutcome {
    /** The relay said OK; [event] is the signed event (null in tests). */
    data class Ok(val event: Event? = null) : SendOutcome
    /** The relay's refusal text, or the error's message (timeout, no connection…). */
    data class Error(val message: String) : SendOutcome
}

data class Sent(val item: OutboxItem, val event: Event?)

data class PassResult(
    /** localIds tried in this pass, in order. */
    val attempted: List<String>,
    /** Items that are out (or were duplicates), with the signed event when there is one. */
    val sent: List<Sent>,
    /** Items refused for good in this pass (status failed, reason in `error`). */
    val failed: List<OutboxItem>,
    /** What is left in the outbox, in the order given. */
    val items: List<OutboxItem>,
    /** Relays that stalled: when not empty, do not run again at once. */
    val stalled: Set<Target>,
)
