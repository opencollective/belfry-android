package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import sh.belfry.core.nostr.str

// A channel's Posts (SPEC 19.60; collective-belfry docs/one-shape.md "A channel's Settings",
// canvas channels/settings): which kinds a channel takes, one switch each, all on by default, at
// least one stays on. In the channel's `belfry:web:<channel>` setting, next to the old `posts`:
// `"accepts": ["message", "thread", "event", "proposal"]` (any non-empty subset; absent = all).

/** One kind of post a channel can take, with its row's words. */
enum class PostKind(val key: String, val label: String, val line: String) {
    MESSAGE("message", "Messages", "Plain messages, pictures and files"),
    THREAD("thread", "Threads", "Each post starts its own thread"),
    EVENT("event", "Events", "Dates people can answer"),
    PROPOSAL("proposal", "Proposals", "Texts to discuss and decide");

    companion object {
        fun of(key: String?): PostKind? = entries.firstOrNull { it.key == key }
    }
}

object ChannelPosts {
    val ALL: Set<PostKind> = PostKind.entries.toSet()

    /** The old single values that mean "that kind alone". */
    private val SINGLES = setOf(PostKind.THREAD, PostKind.EVENT, PostKind.PROPOSAL)

    /**
     * What a channel takes, read from its web settings' CONTENT: `accepts` first (its known kinds;
     * empty or unreadable counts as absent), else the old `posts` (`thread` / `event` / `proposal` =
     * that kind alone; `anything`, `request`, anything else or nothing = all four).
     */
    fun read(content: JsonObject): Set<PostKind> {
        val a = content["accepts"] as? JsonArray
        if (a != null) {
            val kinds = a.mapNotNull { x -> PostKind.of((x as? JsonPrimitive)?.takeIf { it.isString }?.content) }.toSet()
            if (kinds.isNotEmpty()) return order(kinds)
        }
        val single = PostKind.of(content["posts"]?.str())
        return if (single != null && single in SINGLES) setOf(single) else ALL
    }

    /** The `posts` value older apps read: the single kind when one of thread / event / proposal is alone, else "anything". */
    fun legacyPosts(kinds: Set<PostKind>): String {
        val one = kinds.singleOrNull()
        return if (one != null && one in SINGLES) one.key else "anything"
    }

    /**
     * CONTENT with `accepts` = KINDS (in the rows' order) and the matching `posts`; every other key
     * kept (settings never override each other: the caller passes the newest copy). A channel that
     * asked for requests from the web (`posts: request`) keeps that word while it takes all four.
     */
    fun write(content: JsonObject, kinds: Set<PostKind>): JsonObject {
        require(kinds.isNotEmpty()) { "at least one kind stays on" }
        val c = LinkedHashMap<String, JsonElement>(content)
        val keepRequest = content["posts"]?.str() == "request" && kinds == ALL
        c["accepts"] = JsonArray(order(kinds).map { JsonPrimitive(it.key) })
        c["posts"] = JsonPrimitive(if (keepRequest) "request" else legacyPosts(kinds))
        return JsonObject(c)
    }

    /** KINDS with KIND switched ON or off; the last one on stays on. */
    fun toggle(kinds: Set<PostKind>, kind: PostKind, on: Boolean): Set<PostKind> {
        val next = if (on) kinds + kind else kinds - kind
        return if (next.isEmpty()) kinds else order(next)
    }

    /** May KIND be switched off now? Not when it is the last one on. */
    fun mayTurnOff(kinds: Set<PostKind>, kind: PostKind): Boolean = kind !in kinds || kinds.size > 1

    /** With `message` off the composer's plain send is replaced: by a thread starter when threads are on, else by the kinds' own buttons. */
    fun plainSend(kinds: Set<PostKind>): Boolean = PostKind.MESSAGE in kinds || PostKind.THREAD in kinds

    private fun order(kinds: Set<PostKind>): Set<PostKind> = PostKind.entries.filterTo(LinkedHashSet()) { it in kinds }
}
