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
    /** Anything else (timeouts, auth-required, rate-limited, no connection, an upload that may pass later): try again later. */
    RETRY("retry"),
}

object Outbox {
    private val PERMANENT_PREFIX = Pattern.compile("^(blocked|invalid|restricted|pow|duplicate|error):")
    private val PERMANENT_WORDS = Pattern.compile("not allowed|denied|unauthori[sz]ed", Pattern.CASE_INSENSITIVE)
    // "upload failed (<status>…)": a file the storage refuses (too big, a type it does not take,
    // not allowed) is refused again, so it fails that one message instead of holding back every
    // later one to the relay. 401, 408 and 429 can pass on a later try (web sync.ts, 1834584).
    private val REFUSED_UPLOAD = Pattern.compile("^upload failed \\((4\\d\\d)")
    private val UPLOAD_MAY_PASS = setOf("401", "408", "429")

    fun classify(message: String): ErrorClass = when {
        message.startsWith("duplicate:") -> ErrorClass.DUPLICATE
        PERMANENT_PREFIX.matcher(message).find() || PERMANENT_WORDS.matcher(message).find() || refusedUpload(message) -> ErrorClass.FAILED
        else -> ErrorClass.RETRY
    }

    private fun refusedUpload(message: String): Boolean {
        val m = REFUSED_UPLOAD.matcher(message)
        return m.find() && m.group(1) !in UPLOAD_MAY_PASS
    }

    private val HTTP_MAY_PASS = setOf(401, 408, 429)

    /**
     * How a queued API call went (SPEC 19.37): 2xx sent; a 4xx other than 401 / 408 / 429 is
     * refused for good (`invalid: http <status> <code>`, fails that item); anything else is
     * tried again later.
     */
    fun httpOutcome(status: Int, code: String?): SendOutcome = when {
        status in 200..299 -> SendOutcome.Ok(null)
        status in 400..499 && status !in HTTP_MAY_PASS -> SendOutcome.Error("invalid: http $status" + (code?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""))
        else -> SendOutcome.Error("http $status")
    }

    /** created_at strictly increasing per client: `max(last + 1, now)`. */
    fun nextCreatedAt(last: Long, now: Long = System.currentTimeMillis() / 1000): Long = maxOf(last + 1, now)

    /** The line under a message that is not out yet; null once nothing needs saying. */
    fun stateText(item: OutboxItem, connected: Boolean = false): String = when {
        item.status == OutboxStatus.FAILED -> "Not sent"
        // the relay is connected: a send that is tried again says so (web 2026-11-11)
        item.tries > 0 && connected -> "Sending again…"
        item.tries > 0 -> "Waiting for the connection…"
        else -> "Sending…"
    }

    /**
     * Which items keep their order among themselves (SPEC 19.50): a direct message's wraps by whom
     * they go to, a group's events and API calls by the group (`h`), anything else by its kind
     * (and `d`). A retrying item holds back only its own lane, so a message never waits behind a
     * read marker or a settings call.
     */
    fun lane(item: OutboxItem): String {
        if (item.dm != null) return "dm:" + (item.signed?.tag("p") ?: item.dm)
        val h = item.tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1)
        if (h != null) return "h:$h"
        val d = item.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
        return "k:${item.kind}" + (d?.let { ":$it" } ?: "")
    }

    /** After this many transient failures an item holds back only its own lane, never its whole relay. */
    const val STALLS_RELAY_UNTIL = 3

    /** The signer did not answer: nothing else that needs a signature goes in this pass. */
    fun signerDown(message: String): Boolean = message.startsWith("timeout: the signer")

    /** The relay (or the API) did not answer or is not reachable: its other items would wait the same. */
    fun connectionDown(message: String): Boolean = message.startsWith("timeout:") || message == "connection closed"

    /**
     * One flush pass over [items]. [send] signs (unless pre-signed), uploads and publishes one
     * item and says how it went. Items are walked by created_at; failed ones are skipped. After a
     * retryable error (SPEC 5, 19.50):
     * - the item's lane ([lane]) waits for the rest of the pass (order kept within it);
     * - a signer that did not answer holds back every item that still needs signing;
     * - a relay that did not answer holds back its other items, unless the item failed
     *   [STALLS_RELAY_UNTIL] times already (then it stops holding back the others), and never a
     *   direct message for another item's sake.
     */
    suspend fun run(items: List<OutboxItem>, send: suspend (OutboxItem) -> SendOutcome): PassResult {
        val box = items.toMutableList()
        val attempted = ArrayList<String>()
        val sent = ArrayList<Sent>()
        val failed = ArrayList<OutboxItem>()
        val stalled = HashSet<Target>()
        val lanes = HashSet<String>()
        val relayDown = HashSet<Target>() // by a direct message: holds back everything on that relay
        val relayDownForOthers = HashSet<Target>() // by anything else: direct messages still go
        var signerOut = false
        fun replace(next: OutboxItem) {
            val i = box.indexOfFirst { it.localId == next.localId }
            if (i >= 0) box[i] = next
        }
        for (item in items.sortedBy { it.createdAt }) {
            if (item.status == OutboxStatus.FAILED) continue
            val lane = item.target.key + "/" + lane(item)
            val isDm = item.dm != null
            val held = lane in lanes || item.target in relayDown || (!isDm && item.target in relayDownForOthers) ||
                (signerOut && item.signed == null)
            if (held) { stalled.add(item.target); continue }
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
                        lanes.add(lane)
                        when {
                            signerDown(outcome.message) -> signerOut = true
                            connectionDown(outcome.message) && item.tries < STALLS_RELAY_UNTIL ->
                                if (isDm) relayDown.add(item.target) else relayDownForOthers.add(item.target)
                        }
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
    /** Relays with an item left to try again (it failed for now, or waited behind one that did): when not empty, run again soon, not at once. */
    val stalled: Set<Target>,
)
