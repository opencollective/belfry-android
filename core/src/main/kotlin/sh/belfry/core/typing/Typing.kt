package sh.belfry.core.typing

import sh.belfry.core.crypto.Hex
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.Tag
import kotlin.math.abs

/**
 * Where someone types: a channel / group / event chat / proposal ([group]), a thread in it
 * ([root] = the thread's root message), or a direct-message conversation ([conv], `n:…`).
 */
data class TypingPlace(val group: String? = null, val root: String? = null, val conv: String? = null) {
    val isDm: Boolean get() = conv != null

    companion object {
        fun channel(group: String) = TypingPlace(group = group)
        fun thread(group: String, root: String) = TypingPlace(group = group, root = root)
        fun dm(conv: String) = TypingPlace(conv = conv)
    }
}

/**
 * Typing indicators (Xavier, 2026-11-21; collective-belfry docs/typing.md @7750419). Ephemeral
 * kind 20100, empty content: in groups signed by the person on the groups relay with `h` (and `e`
 * = the thread's root); in DMs an unsigned rumor tagged `p` for each other participant, sealed and
 * put in a kind 21059 gift wrap (real `created_at`) to each of them on the community relay.
 * Best effort: never through the outbox, never retried.
 */
object Typing {
    const val KIND = 20100
    const val WRAP_KIND = 21059
    /** At most one typing event per place every 4 s. */
    const val GAP_S = 4L
    /** Someone shows as typing for 6 s after their latest event. */
    const val SHOW_S = 6L
    /** Events more than 10 s in the past or the future are ignored (relays replay ephemeral events). */
    const val SKEW_S = 10L
    /** Subscriptions ask from 5 s back. */
    const val SINCE_S = 5L
    /** The signer gets 3 s for a typing event; then it is dropped. */
    const val SIGNER_MS = 3_000L
    /** A relay that refused one (rate limited) gets none for 30 s. */
    const val PAUSE_S = 30L

    const val STOP = "stop"

    fun fresh(createdAt: Long, now: Long): Boolean = abs(now - createdAt) <= SKEW_S

    fun isStop(tags: List<Tag>): Boolean = tags.any { it.size >= 2 && it[0] == "status" && it[1] == STOP }

    // ---- writing ----

    /** The groups-relay event for a channel or a thread (signed by the person). */
    fun groupTemplate(place: TypingPlace, stop: Boolean = false, now: Long): EventTemplate {
        val group = requireNotNull(place.group)
        val tags = buildList {
            add(listOf("h", group))
            place.root?.let { add(listOf("e", it)) }
            if (stop) add(listOf("status", STOP))
        }
        return EventTemplate(KIND, "", tags, now)
    }

    /** The other participants of a DM conversation (none for e-mail or notes to myself). */
    fun recipients(conv: String, me: String): List<String> =
        if (MailThreads.isMail(conv)) emptyList() else MailThreads.peers(conv).filter { it != me && Hex.isKey(it) }

    /** The DM rumor (unsigned): `p` for every other participant. */
    fun dmRumor(me: String, to: List<String>, stop: Boolean = false, now: Long): Event =
        GiftWrap.rumor(KIND, me, to.map { listOf("p", it) } + if (stop) listOf(listOf("status", STOP)) else emptyList(), "", now)

    // ---- reading ----

    /** Where a groups-relay typing event is, or null when it isn't one. */
    fun placeOfGroupEvent(e: Event): TypingPlace? {
        if (e.kind != KIND) return null
        val h = e.tag("h")?.takeIf { it.isNotEmpty() } ?: return null
        val root = e.tag("e")?.takeIf { it.isNotEmpty() }
        return TypingPlace(group = h, root = root)
    }

    /** Where an opened DM typing rumor is: the conversation of its author and `p` tags (as for kind 14). */
    fun placeOfRumor(rumor: Event, me: String): TypingPlace? {
        if (rumor.kind != KIND) return null
        val conv = MailThreads.nostrConv(rumor.pubkey, rumor.tagValues("p"), me)
        if (MailThreads.peers(conv).isEmpty()) return null
        return TypingPlace.dm(conv)
    }

    // ---- showing ----

    /** "Lina is typing…", "Lina and Tom are typing…", "Several people are typing…", or null. */
    fun line(names: List<String>): String? = when (names.size) {
        0 -> null
        1 -> "${names[0]} is typing…"
        2 -> "${names[0]} and ${names[1]} are typing…"
        else -> "Several people are typing…"
    }

    /** The conversations list's word in place of a DM's preview. */
    const val LIST_WORD = "typing…"
}

/**
 * When to send (docs/typing.md §2). Pure: the caller says what the field holds and when; the
 * answer is what to send now, if anything.
 */
class TypingSender {
    private val lastSent = HashMap<TypingPlace, Long>()

    /**
     * The field of [place] changed to [text] by a keystroke at [now]. [restored]: a draft put back on
     * open, or an edit of an older message (never sent for). Returns true when a typing event goes now.
     */
    fun onText(place: TypingPlace, text: String, now: Long, restored: Boolean = false, enabled: Boolean = true): Boolean {
        if (!enabled || restored) return false
        if (text.isBlank()) return false
        val last = lastSent[place]
        if (last != null && now - last < Typing.GAP_S) return false
        lastSent[place] = now
        return true
    }

    /** The field was emptied without sending: true when a stop goes (a typing event went out in the last 6 s). */
    fun onCleared(place: TypingPlace, now: Long, enabled: Boolean = true): Boolean {
        val last = lastSent.remove(place) ?: return false
        return enabled && now - last < Typing.SHOW_S
    }

    /** The message was sent: no stop needed (the message clears it), and the next keystroke may send at once. */
    fun onSent(place: TypingPlace) { lastSent.remove(place) }
}

/**
 * Who types where (docs/typing.md §3): from a typing event until 6 s after it, their stop, or a
 * message of theirs in that place (the caller says when their latest one was).
 */
class TypingBoard {
    private class Seen(val at: Long, val heard: Long)
    private val byPlace = HashMap<TypingPlace, HashMap<String, Seen>>()

    /**
     * A typing event from [pubkey] in [place], made at [createdAt], heard at [now]. False when it is
     * ignored (too old or too far ahead).
     */
    @Synchronized
    fun apply(place: TypingPlace, pubkey: String, createdAt: Long, stop: Boolean, now: Long): Boolean {
        if (!Typing.fresh(createdAt, now)) return false
        val m = byPlace.getOrPut(place) { HashMap() }
        val old = m[pubkey]
        if (old != null && old.at > createdAt) return false // an older one arriving late
        if (stop) m.remove(pubkey) else m[pubkey] = Seen(createdAt, now)
        if (m.isEmpty()) byPlace.remove(place)
        return true
    }

    /**
     * Who is typing in [place] at [now], in the order they started; [lastMessageAt] = the
     * created_at of that person's latest message there (0: none). A message made at or after their
     * typing event ends it.
     */
    @Synchronized
    fun typers(place: TypingPlace, now: Long, lastMessageAt: (String) -> Long = { 0L }): List<String> {
        val m = byPlace[place] ?: return emptyList()
        return m.entries
            .filter { (pk, s) -> now - s.heard < Typing.SHOW_S && lastMessageAt(pk) < s.at }
            .sortedBy { it.value.at }
            .map { it.key }
    }

    /** Who shows where at [now] (before the message rule, which [TypingState.who] applies). */
    @Synchronized
    fun snapshot(now: Long): TypingState = TypingState(
        byPlace.mapValues { (_, m) -> m.entries.filter { now - it.value.heard < Typing.SHOW_S }.sortedBy { it.value.at }.map { TypingSeen(it.key, it.value.at) } }
            .filterValues { it.isNotEmpty() },
    )

    /** Every place with someone typing at [now] (for the conversations list). */
    @Synchronized
    fun places(now: Long): Set<TypingPlace> = byPlace.filterValues { m -> m.values.any { now - it.heard < Typing.SHOW_S } }.keys.toSet()

    /** Forgets what has run out. */
    @Synchronized
    fun prune(now: Long) {
        val it = byPlace.values.iterator()
        while (it.hasNext()) {
            val m = it.next()
            m.values.removeAll { now - it.heard >= Typing.SHOW_S }
            if (m.isEmpty()) it.remove()
        }
    }

    /** When the next person stops showing (to redraw then), or null when nobody shows. */
    @Synchronized
    fun nextChange(now: Long): Long? = byPlace.values.flatMap { it.values }.map { it.heard + Typing.SHOW_S }.filter { it > now }.minOrNull()
}

/** Someone typing, with the created_at of their latest typing event. */
data class TypingSeen(val pubkey: String, val at: Long)

/** What [sh.belfry.core.engine.Session.typing] holds: who types where, right now. */
data class TypingState(val places: Map<TypingPlace, List<TypingSeen>> = emptyMap()) {
    /**
     * Who types in [place], in the order they started, but not someone whose latest message there
     * ([lastMessageAt], created_at; 0 = none) was made at or after their typing event.
     */
    fun who(place: TypingPlace, lastMessageAt: (String) -> Long = { 0L }): List<String> =
        places[place].orEmpty().filter { lastMessageAt(it.pubkey) < it.at }.map { it.pubkey }

    /** DM conversations with someone typing (the list's "typing…"). */
    val dmConvs: Set<String> get() = places.keys.mapNotNullTo(HashSet()) { it.conv }
}
