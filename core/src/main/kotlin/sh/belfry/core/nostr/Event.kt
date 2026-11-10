package sh.belfry.core.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.crypto.sha256Hex

typealias Tag = List<String>

/**
 * A Nostr event (NIP-01). `sig` is empty for rumors (unsigned events inside gift wraps) and for
 * outbox items shown before they are signed.
 */
data class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<Tag>,
    val content: String,
    val sig: String = "",
) {
    /** First value of the first tag with this name. */
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    /** Values (index 1) of all tags with this name. */
    fun tagValues(name: String): List<String> = tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }

    fun hasTag(name: String, value: String): Boolean = tags.any { it.size >= 2 && it[0] == name && it[1] == value }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("pubkey", JsonPrimitive(pubkey))
        put("created_at", JsonPrimitive(createdAt))
        put("kind", JsonPrimitive(kind))
        put("tags", tagsJson(tags))
        put("content", JsonPrimitive(content))
        if (sig.isNotEmpty()) put("sig", JsonPrimitive(sig))
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        /** Parses an event object; null when a field is missing or of the wrong type. */
        fun fromJson(el: JsonElement): Event? {
            val o = el as? JsonObject ?: return null
            return try {
                Event(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    pubkey = o["pubkey"]?.jsonPrimitive?.contentOrNull ?: return null,
                    createdAt = o["created_at"]?.jsonPrimitive?.longOrNull ?: return null,
                    kind = o["kind"]?.jsonPrimitive?.intOrNull ?: return null,
                    tags = (o["tags"] as? JsonArray)?.map { t -> t.jsonArray.map { it.jsonPrimitive.content } } ?: emptyList(),
                    content = o["content"]?.jsonPrimitive?.contentOrNull ?: "",
                    sig = o["sig"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        fun fromJsonString(s: String): Event? = try {
            fromJson(Json.parseToJsonElement(s))
        } catch (e: Exception) {
            null
        }

        fun tagsJson(tags: List<Tag>): JsonArray = buildJsonArray {
            for (t in tags) add(buildJsonArray { for (v in t) add(JsonPrimitive(v)) })
        }

        /** NIP-01 serialization: `[0,pubkey,created_at,kind,tags,content]`, UTF-8, no extra whitespace. */
        fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: List<Tag>, content: String): String {
            val sb = StringBuilder()
            sb.append("[0,")
            quote(sb, pubkey)
            sb.append(',').append(createdAt).append(',').append(kind).append(",[")
            tags.forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append('[')
                t.forEachIndexed { j, v ->
                    if (j > 0) sb.append(',')
                    quote(sb, v)
                }
                sb.append(']')
            }
            sb.append("],")
            quote(sb, content)
            sb.append(']')
            return sb.toString()
        }

        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<Tag>, content: String): String =
            sha256Hex(serialize(pubkey, createdAt, kind, tags, content))

        /** Same escaping as JavaScript's JSON.stringify (what every Nostr client hashes). */
        private fun quote(sb: StringBuilder, s: String) {
            sb.append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000c' -> sb.append("\\f")
                    else -> if (ch.code < 0x20) {
                        sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
            sb.append('"')
        }
    }
}

/** An event before it has an author's signature: what actions build and the outbox keeps. */
data class EventTemplate(
    val kind: Int,
    val content: String,
    val tags: List<Tag>,
    val createdAt: Long = 0,
)

/** Small readers for JSON trees (kotlinx.serialization without the compiler plugin). */
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
fun JsonObject.s(key: String): String? = this[key].str()
fun parseJsonOrNull(text: String): JsonElement? = try { Json.parseToJsonElement(text) } catch (e: Exception) { null }
@Suppress("unused")
fun JsonElement.asObject(): JsonObject = jsonObject
