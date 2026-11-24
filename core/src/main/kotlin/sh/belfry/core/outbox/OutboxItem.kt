package sh.belfry.core.outbox

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.Tag
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.s

/**
 * Which relay an event goes to: the NIP-29 groups relay or the community ("main") relay; or
 * [API]: a signed call to the belfry's HTTP API queued like an event (`OutboxItem.path` +
 * `body`, NIP-98 signed when it is sent; SPEC 19.37).
 */
enum class Target(val key: String) {
    GROUPS("groups"), MAIN("main"), API("api");

    companion object {
        fun of(key: String): Target = when (key) { "main" -> MAIN; "api" -> API; else -> GROUPS }
    }
}

/** A file waiting to be uploaded with its message. The bytes live in the store under [blobKey]. */
data class Attachment(
    val blobKey: String,
    val name: String,
    val mime: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("blobKey", JsonPrimitive(blobKey))
        put("name", JsonPrimitive(name))
        put("mime", JsonPrimitive(mime))
        put("size", JsonPrimitive(size))
        if (width != null) put("width", JsonPrimitive(width))
        if (height != null) put("height", JsonPrimitive(height))
    }

    companion object {
        fun fromJson(o: JsonObject): Attachment = Attachment(
            blobKey = o.s("blobKey") ?: "",
            name = o.s("name") ?: "",
            mime = o.s("mime") ?: "application/octet-stream",
            size = o["size"].long() ?: 0,
            width = o["width"].int(),
            height = o["height"].int(),
        )
    }
}

enum class OutboxStatus(val key: String) { QUEUED("queued"), FAILED("failed") }

/**
 * A write saved locally before anything goes on the network (SPEC 5). Shown in the feed at once
 * as if published (see [asEvent]); removed when the relay says OK.
 */
data class OutboxItem(
    val localId: String,
    val target: Target,
    val kind: Int,
    val content: String,
    val tags: List<Tag>,
    val createdAt: Long,
    val attachments: List<Attachment> = emptyList(),
    val status: OutboxStatus = OutboxStatus.QUEUED,
    val error: String? = null,
    val tries: Int = 0,
    /** An event signed already (a gift wrap, by its one-time key): published as is. */
    val signed: Event? = null,
    /** The direct message or e-mail it carries, told when it is out. */
    val dm: String? = null,
    /** [Target.API]: the call's path under the API base (`/group-meta`), POSTed with [body] (JSON). */
    val path: String? = null,
    val body: String? = null,
    /** When the community relay first refused it with "not in whitelist" (seconds; 0 = never): tried again for 10 minutes from then (SPEC 19.79). */
    val notListedAt: Long = 0,
    /**
     * Signed at sending by this DEVICE's own key (the session's NIP-46 client key), never through the signer and
     * never by the account: format 3's device-signed events (SPEC 19.82; the `belfry:device:reads` marker). Dated
     * when it is signed. With no device key it fails.
     */
    val byDevice: Boolean = false,
) {
    /** The item as it shows in the log before it is signed: id = localId, no signature. */
    fun asEvent(me: String): Event = signed ?: Event(localId, me, createdAt, kind, tags, content, "")

    fun toJson(): JsonObject = buildJsonObject {
        put("localId", JsonPrimitive(localId))
        put("target", JsonPrimitive(target.key))
        put("kind", JsonPrimitive(kind))
        put("content", JsonPrimitive(content))
        put("tags", Event.tagsJson(tags))
        put("created_at", JsonPrimitive(createdAt))
        put("attachments", buildJsonArray { attachments.forEach { add(it.toJson()) } })
        put("status", JsonPrimitive(status.key))
        if (error != null) put("error", JsonPrimitive(error))
        put("tries", JsonPrimitive(tries))
        if (signed != null) put("signed", signed.toJson())
        if (dm != null) put("dm", JsonPrimitive(dm))
        if (path != null) put("path", JsonPrimitive(path))
        if (body != null) put("body", JsonPrimitive(body))
        if (notListedAt != 0L) put("not_listed_at", JsonPrimitive(notListedAt))
        if (byDevice) put("by_device", JsonPrimitive(true))
    }

    companion object {
        fun fromJson(o: JsonObject): OutboxItem = OutboxItem(
            localId = o.s("localId") ?: "",
            target = Target.of(o.s("target") ?: "groups"),
            kind = o["kind"].int() ?: 0,
            content = o.s("content") ?: "",
            tags = (o["tags"] as? JsonArray)?.map { t -> (t as JsonArray).map { (it as JsonPrimitive).content } } ?: emptyList(),
            createdAt = o["created_at"].long() ?: 0,
            attachments = o["attachments"].arr()?.mapNotNull { it.obj()?.let(Attachment::fromJson) } ?: emptyList(),
            status = if (o.s("status") == "failed") OutboxStatus.FAILED else OutboxStatus.QUEUED,
            error = o.s("error"),
            tries = o["tries"].int() ?: 0,
            signed = o["signed"]?.let { Event.fromJson(it) },
            dm = o.s("dm"),
            path = o.s("path"),
            body = o.s("body"),
            notListedAt = o["not_listed_at"].long() ?: 0,
            byDevice = (o["by_device"] as? JsonPrimitive)?.content == "true",
        )
    }
}
