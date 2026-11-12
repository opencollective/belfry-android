package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
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

/**
 * A form's fields kept on this device while it is being written (web common.tsx `useFormDraft`,
 * 2026-11-11): leaving the form and opening it again brings them back; sending clears them.
 * Keys as the web's: `event:<visibility>`, `proposal:<parent>`, `todo:<channel>`, `channel`,
 * `meeting:<channel>`. Field values are text (the form reads them back itself). Thirty days.
 */
object FormDrafts {
    const val MAX_FIELD = 20_000
    fun storeKey(key: String): String = "form:$key"
    fun event(visibility: String): String = "event:$visibility"
    fun proposal(parent: String): String = "proposal:$parent"
    fun todo(groupId: String): String = "todo:$groupId"
    const val CHANNEL = "channel"
    fun meeting(channel: String): String = "meeting:$channel"

    fun toJsonString(fields: Map<String, String>, at: Long): String = buildJsonObject {
        put("at", JsonPrimitive(at))
        put("v", buildJsonObject { for ((k, v) in fields) put(k, JsonPrimitive(v.take(MAX_FIELD))) })
    }.toString()

    /** Null for anything that is not a form's draft, or one older than 30 days at NOW (Unix seconds). */
    fun parse(text: String?, now: Long): Map<String, String>? {
        val o = parseJsonOrNull(text ?: return null) as? JsonObject ?: return null
        val at = o["at"].long() ?: return null
        if (now - at >= ComposerDraft.KEEP_SECONDS) return null
        val v = o["v"].obj() ?: return null
        val out = LinkedHashMap<String, String>()
        for ((k, x) in v) x.str()?.let { out[k] = it.take(MAX_FIELD) }
        return out.takeIf { it.isNotEmpty() }
    }
}
