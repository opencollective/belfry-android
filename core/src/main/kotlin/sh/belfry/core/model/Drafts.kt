package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str

/**
 * Unsent text of a composer, kept on this device per conversation (web Chat.tsx drafts,
 * 2026-11-11): a channel (`c:<group id>`), a thread (`t:<root id>`). With the people and channels
 * picked in it, so "@Name" and "#Name" are still sent as references. Thirty days, 20 000 characters.
 */
data class ComposerDraft(
    val text: String,
    /** (name, pubkey) of the people picked with @. */
    val picked: List<Pair<String, String>> = emptyList(),
    /** ("#Name", `nostr:naddr…`) of the channels picked with #. */
    val channels: List<Pair<String, String>> = emptyList(),
    val at: Long = 0,
) {
    fun toJsonString(): String = buildJsonObject {
        put("text", JsonPrimitive(text.take(MAX)))
        put("picked", pairs(picked))
        put("chans", pairs(channels))
        put("at", JsonPrimitive(at))
    }.toString()

    companion object {
        const val MAX = 20_000
        const val KEEP_SECONDS = 30 * 86400L
        /** The store key of a draft. */
        fun storeKey(key: String): String = "draft:$key"
        fun channel(groupId: String): String = "c:$groupId"
        fun thread(rootId: String): String = "t:$rootId"

        private fun pairs(list: List<Pair<String, String>>): JsonArray = buildJsonArray {
            for ((a, b) in list) add(buildJsonArray { add(JsonPrimitive(a)); add(JsonPrimitive(b)) })
        }

        private fun pairsOf(o: JsonObject, key: String): List<Pair<String, String>> =
            o[key].arr()?.mapNotNull { p -> p.arr()?.takeIf { it.size == 2 }?.let { (it[0].str() ?: return@let null) to (it[1].str() ?: return@let null) } } ?: emptyList()

        /** Null for anything that is not a draft, an empty one, or one older than 30 days at NOW (Unix seconds). */
        fun parse(text: String?, now: Long): ComposerDraft? {
            val o = parseJsonOrNull(text ?: return null) as? JsonObject ?: return null
            val t = o.s("text")?.takeIf { it.isNotBlank() } ?: return null
            val at = o["at"].long() ?: 0
            if (now - at >= KEEP_SECONDS) return null
            return ComposerDraft(t.take(MAX), pairsOf(o, "picked"), pairsOf(o, "chans"), at)
        }
    }
}
