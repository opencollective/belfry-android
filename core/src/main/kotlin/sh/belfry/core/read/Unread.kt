package sh.belfry.core.read

import sh.belfry.core.model.World
import sh.belfry.core.nostr.Event

/** What is unread, worked out from the shared read state (SPEC 19.27 "What is unread", "Marking"). */
object Unread {
    /** A channel's top-level messages by someone else after its mark. */
    fun channel(w: World, r: ReadState, groupId: String, now: Long): Int {
        val upTo = r.readUpTo('c', groupId, now)
        val list = w.messages(groupId)
        var n = 0
        for (i in list.indices.reversed()) {
            val m = list[i]
            if (m.ev.createdAt <= upTo) break // oldest first: nothing older is unread
            if (m.ev.pubkey != w.me) n++
        }
        return n
    }

    /**
     * A thread's replies by someone else after its mark, only when I am in it: I wrote the message or
     * a reply, or I opened it before (`t[root]` above its floor). 0 otherwise.
     */
    fun thread(w: World, r: ReadState, groupId: String, rootId: String, now: Long): Int {
        val replies = w.threadReplies(groupId, rootId)
        if (replies.isEmpty()) return 0
        val mine = w.message(groupId, rootId)?.ev?.pubkey == w.me || replies.any { it.ev.pubkey == w.me } ||
            (r.t[rootId] ?: 0) > r.floor('t', now)
        if (!mine) return 0
        val upTo = r.readUpTo('t', rootId, now)
        return replies.count { it.ev.pubkey != w.me && it.ev.createdAt > upTo }
    }

    /** A mention (kind 9 or 1111) is new after the @Mentions mark and its channel's / thread's. */
    fun mentionIsNew(r: ReadState, e: Event, now: Long): Boolean {
        val place = when (e.kind) {
            9 -> e.tag("h")?.let { r.readUpTo('c', it, now) } ?: 0
            1111 -> (e.tag("E") ?: e.tag("e"))?.let { r.readUpTo('t', it, now) } ?: 0
            else -> 0
        }
        return e.createdAt > maxOf(r.readUpTo('m', "", now), place)
    }

    /** What reading a channel marks: its newest kind 9 (mine included), null when it has none. */
    fun channelMark(w: World, groupId: String): Long? = w.messages(groupId).maxOfOrNull { it.ev.createdAt }

    /** What reading a thread marks: its newest reply, else the message itself. */
    fun threadMark(w: World, groupId: String, rootId: String): Long? =
        w.threadReplies(groupId, rootId).maxOfOrNull { it.ev.createdAt } ?: w.message(groupId, rootId)?.ev?.createdAt
}
