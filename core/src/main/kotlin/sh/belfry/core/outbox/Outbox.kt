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

    // The community relay (rnostr main, 2026-11-23; web 92e5012): an authenticated key that is not on
    // its whitelist gets "restricted: pubkey not in whitelist". A member just let in is listed within
    // seconds, so a send to THAT relay refused with THAT reason is tried again, with the back-off,
    // for 10 minutes from its first such refusal; then it counts as refused. Every other
    // "restricted:" is refused for good, as before (SPEC 19.79).
    private val NOT_LISTED = Pattern.compile("^restricted\\b.*whitelist", Pattern.CASE_INSENSITIVE)
    const val NOT_LISTED_RETRY_S = 600L

    /** The community relay's "restricted: pubkey not in whitelist". */
    fun notListed(message: String): Boolean = NOT_LISTED.matcher(message).find()

    /** ITEM, for the community relay, was told "not in whitelist" less than 10 minutes after the first time (NOW in seconds). */
    fun notListedYet(message: String, item: OutboxItem, now: Long): Boolean =
        item.target == Target.MAIN && notListed(message) && (item.notListedAt == 0L || now - item.notListedAt < NOT_LISTED_RETRY_S)

    /** ITEM and NOW (seconds): given, the community relay's whitelist refusal is a retry for its 10 minutes. */
    fun classify(message: String, item: OutboxItem? = null, now: Long = 0): ErrorClass = when {
        message.startsWith("duplicate:") -> ErrorClass.DUPLICATE
        item != null && notListedYet(message, item, now) -> ErrorClass.RETRY
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
     * - (NOW, in seconds, dates the community relay's "not in whitelist": [notListedYet]);
     * - a relay that did not answer holds back its other items, unless the item failed
     *   [STALLS_RELAY_UNTIL] times already (then it stops holding back the others), and never a
     *   direct message for another item's sake.
     */
    suspend fun run(items: List<OutboxItem>, now: Long = System.currentTimeMillis() / 1000, send: suspend (OutboxItem) -> SendOutcome): PassResult {
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
                is SendOutcome.Error -> when (classify(outcome.message, item, now)) {
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
                        // the first "not in whitelist" from the community relay starts that item's 10 minutes
                        val listedAt = if (item.notListedAt == 0L && item.target == Target.MAIN && notListed(outcome.message)) now else item.notListedAt
                        replace(item.copy(tries = item.tries + 1, status = OutboxStatus.QUEUED, error = outcome.message, notListedAt = listedAt))
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
