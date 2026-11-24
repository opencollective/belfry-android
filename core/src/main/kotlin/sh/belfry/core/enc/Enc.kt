package sh.belfry.core.enc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.crypto.Nip44
import sh.belfry.core.crypto.Random
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.Tag
import sh.belfry.core.nostr.isValid
import sh.belfry.core.nostr.unixNow

/**
 * Encrypted conversations, format 3 (collective-belfry docs/encrypted.md, with §16–§26): one key
 * per conversation. A port of the web's `src/core/enc/core.ts` (branch enc-v3): change both
 * together. Pure rules: no storage, no network. Checked against vectors/encrypted.json
 * (`EncryptedVectorsTest`). SPEC 19.81.
 *
 * Never log or throw a secret or a rumor's text: errors here say what failed only.
 */
object Enc {
    const val LIST = 8570
    const val KEY = 8571
    const val MSG = 8572
    const val PACK = 8573
    const val ASK = 8574
    const val ANSWER = 8575
    const val SEAL = 13

    /** Devices on one person's list (readers aside). */
    const val MAX_LIST = 20
    /** A people conversation over this is not encrypted (no lock), or held when it once was. */
    const val MAX_PEOPLE_DEVICES = 100
    /** A private group over this refuses to send. */
    const val MAX_GROUP_DEVICES = 1000
    const val KEY_AGE = 30L * 86400
    /** §6: a dropped device's messages up to 10 minutes after the list that dropped it. */
    const val GRACE = 600L
    /** §16.1: bytes of content in one envelope event. */
    const val MAX_ENVELOPE = 60_000
    /** §7.5: bytes of the key list in one pack. */
    const val MAX_PACK = 40_000
    const val MAX_RUMOR = 40_000
    /** §17.8: a key dated more than 15 minutes ahead is refused. */
    const val FUTURE = 900L
    /** §18.2 / §21: a rumor is never newer than its outer event by more than 5 minutes; the start-of-listing grace. */
    const val SKEW = 300L

    val SILENT: Tag = listOf("silent", "1")

    fun isKey(v: String?): Boolean = v != null && Hex.isKey(v)

    /** A signed event whose id is its hash and whose signature verifies (sealedcore `validEvent`). */
    fun valid(e: Event?): Boolean = e != null && Hex.isKey(e.id) && Hex.isKey(e.pubkey) && e.isValid()

    /** Values (index 1) of the tags named NAME. */
    fun values(e: Event, name: String): List<String> = e.tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }

    /** The value of tag NAME when the event has exactly one. */
    fun one(e: Event, name: String): String? = values(e, name).let { if (it.size == 1) it[0] else null }

    fun pub(secret: ByteArray): String = Hex.encode(Keys.publicKey(secret))

    internal fun conv(secret: ByteArray, pub: String): ByteArray = Nip44.conversationKey(secret, Hex.decode(pub))

    internal fun now(): Long = unixNow()

    internal fun utf8(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** Signed with SECRET (sealedcore `signWith`); AUX fixed only by the vectors. */
    fun signWith(secret: ByteArray, kind: Int, createdAt: Long, tags: List<Tag>, content: String, aux: ByteArray? = null): Event {
        val pk = pub(secret)
        val id = Event.computeId(pk, createdAt, kind, tags, content)
        val sig = Keys.sign(Hex.decode(id), secret, aux ?: Random.bytes(32))
        return Event(id, pk, createdAt, kind, tags, content, Hex.encode(sig))
    }

    /** RUMOR with its id computed from its fields. */
    fun withId(r: Event): Event = r.copy(id = Event.computeId(r.pubkey, r.createdAt, r.kind, r.tags, r.content), sig = "")
}

/** Raised when something does not open or is not what it says; the message names what failed, never a secret or a text. */
class EncException(message: String) : Exception(message)

/** What would otherwise be random (the vectors fix it). NONCE(i): the i-th NIP-44 nonce of one call. */
class Fixed(
    val at: Long? = null,
    val sealAt: Long? = null,
    val oneTime: ByteArray? = null,
    val nonce: ((Int) -> ByteArray)? = null,
    val aux: ByteArray? = null,
)

internal fun Fixed?.nonceOf(i: Int): ByteArray = this?.nonce?.invoke(i) ?: Random.bytes(32)

/** JSON as JavaScript's JSON.stringify writes it, and strict readers (the web takes `unknown` and checks). */
object EncJson {
    fun stringify(e: JsonElement): String = StringBuilder().also { write(it, e) }.toString()

    private fun write(sb: StringBuilder, e: JsonElement) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) quote(sb, e.content) else sb.append(e.content)
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in e) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k); sb.append(':'); write(sb, v)
                }
                sb.append('}')
            }
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000c' -> sb.append("\\f")
                ch.code < 0x20 -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                ch.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { sb.append(ch).append(s[i + 1]); i++ }
                ch.isSurrogate() -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) // a lone surrogate, as well-formed JSON.stringify
                else -> sb.append(ch)
            }
            i++
        }
        sb.append('"')
    }

    fun tags(tags: List<Tag>): JsonArray = Event.tagsJson(tags)

    /** A rumor as it goes into a seal: id, pubkey, created_at, kind, tags, content. */
    fun rumorJson(r: Event): String = stringify(buildJsonObject {
        put("id", JsonPrimitive(r.id)); put("pubkey", JsonPrimitive(r.pubkey)); put("created_at", JsonPrimitive(r.createdAt))
        put("kind", JsonPrimitive(r.kind)); put("tags", tags(r.tags)); put("content", JsonPrimitive(r.content))
    })

    /** A signed event as signWith returns it: kind, created_at, tags, content, pubkey, id, sig. */
    fun signedObject(e: Event): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(e.kind)); put("created_at", JsonPrimitive(e.createdAt)); put("tags", tags(e.tags))
        put("content", JsonPrimitive(e.content)); put("pubkey", JsonPrimitive(e.pubkey)); put("id", JsonPrimitive(e.id)); put("sig", JsonPrimitive(e.sig))
    }

    fun signedJson(e: Event): String = stringify(signedObject(e))

    fun parse(s: String): JsonElement? = try { Json.parseToJsonElement(s) } catch (e: Exception) { null }

    private fun string(el: JsonElement?): String? = (el as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Number.isInteger: a JSON number with no fraction. */
    private fun integer(el: JsonElement?): Long? {
        val p = el as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: return null
        return if (d.isFinite() && d % 1.0 == 0.0 && Math.abs(d) <= 9.007199254740991E15) d.toLong() else null
    }

    private fun tagList(el: JsonElement?): List<Tag>? {
        val a = el as? JsonArray ?: return null
        return a.map { t -> (t as? JsonArray)?.map { string(it) ?: return null } ?: return null }
    }

    private fun fields(el: JsonElement?): Event? {
        val o = el as? JsonObject ?: return null
        val pubkey = string(o["pubkey"]) ?: return null
        val at = integer(o["created_at"]) ?: return null
        val kind = integer(o["kind"])?.takeIf { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt() ?: return null
        val content = string(o["content"]) ?: return null
        val tags = tagList(o["tags"]) ?: return null
        return Event(string(o["id"]) ?: "", pubkey, at, kind, tags, content, string(o["sig"]) ?: "")
    }

    /** A signed event read strictly (every field of the right type); null otherwise. Its signature is checked by [Enc.valid], not here. */
    fun event(el: JsonElement?): Event? {
        val o = el as? JsonObject ?: return null
        if (string(o["id"]) == null || string(o["sig"]) == null) return null
        return fields(o)
    }

    fun event(s: String): Event? = parse(s)?.let { event(it) }

    /** sealedcore `withId`: a rumor (author a key, the fields of the right type) with its id computed; a given id or sig is ignored. */
    fun rumor(el: JsonElement?): Event? {
        val e = fields(el) ?: return null
        if (!Hex.isKey(e.pubkey)) return null
        return Enc.withId(e)
    }
}
