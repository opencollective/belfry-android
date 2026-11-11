package sh.belfry.core.push

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.B64
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Nip44
import sh.belfry.core.crypto.Random
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import java.net.URI

// Push notifications (collective-belfry docs/push.md; SPEC 20). The belfry encrypts each
// notification with NIP-44 v2 under a random 32-byte device key K that this phone made and
// registered, used directly as the conversation key (no ECDH). FCM carries it as data.b; the app
// decrypts it and posts the notification itself. Vectors: vectors/push.json.

/** The Android notification channels, one per kind of notification. */
enum class PushChannel(val id: String, val title: String) {
    DMS("dms", "Direct messages"),
    MENTIONS("mentions", "Mentions and replies"),
    ANNOUNCEMENTS("announcements", "Announcements"),
    PINS("pins", "Pinned"),
    MESSAGES("messages", "All messages");

    companion object {
        /** The payload's `type` → its channel (an unknown type: All messages). */
        fun of(type: String): PushChannel = when (type) {
            "dm" -> DMS
            "mention", "reply" -> MENTIONS
            "announcement" -> ANNOUNCEMENTS
            "pin" -> PINS
            else -> MESSAGES
        }
    }
}

/** What a tap on a notification opens. */
sealed interface PushOpen {
    data class Channel(val channel: String, val message: String?) : PushOpen
    data object Dms : PushOpen
    data object Home : PushOpen
}

/** A decrypted notification (docs/push.md §4), ready to show. */
data class PushNote(
    val type: String,
    val domain: String,
    val title: String,
    val body: String,
    val channel: String?,
    val event: String?,
    val url: String?,
) {
    val androidChannel: PushChannel get() = PushChannel.of(type)
    /** Notifications of one conversation replace each other: `dm`, else the group id. */
    val collapse: String get() = if (type == "dm") "dm" else channel ?: type
}

object Push {
    private val GROUP = Regex("^[A-Za-z0-9_.-]{1,64}$")

    /** A new device key K: base64 of 32 random bytes (what `POST /push/devices` takes as `key`). */
    fun newDeviceKey(): String = B64.encode(Random.bytes(32))

    /**
     * Decrypts data.b with the device key ([keyB64]) and reads it; null when it is not for this key,
     * not a version-1 payload, or not JSON. Title and body are cut to what a notification shows.
     */
    fun open(payload: String, keyB64: String): PushNote? {
        val k = B64.decodeOrNull(keyB64)?.takeIf { it.size == 32 } ?: return null
        val text = try { Nip44.decryptWithKey(payload, k) } catch (e: Exception) { return null }
        val o = parseJsonOrNull(text) as? JsonObject ?: return null
        if (o["v"].int() != 1) return null
        val type = o.s("type") ?: return null
        return PushNote(
            type = type,
            domain = o.s("domain") ?: "",
            title = (o.s("title") ?: "").take(120),
            body = (o.s("body") ?: "").take(400),
            channel = o.s("channel")?.takeIf { GROUP.matches(it) },
            event = o.s("event")?.takeIf(Hex::isKey),
            url = o.s("url"),
        )
    }

    /**
     * Where a tap goes. The url is never followed: its host must be `chat.<domain>` of the belfry
     * I am signed in to (and the payload's domain that belfry); then the channel / message opens
     * by id, or the conversation list for a DM. Anything else opens home.
     */
    fun target(note: PushNote, signedInDomain: String): PushOpen {
        val d = signedInDomain.lowercase()
        if (d.isEmpty() || note.domain.lowercase() != d) return PushOpen.Home
        val host = try { note.url?.let { URI(it) }?.takeIf { it.scheme == "https" }?.host?.lowercase() } catch (e: Exception) { null }
        if (host != "chat.$d") return PushOpen.Home
        if (note.type == "dm") return PushOpen.Dms
        val ch = note.channel ?: return PushOpen.Home
        return PushOpen.Channel(ch, note.event)
    }
}

/** Per-channel push levels (docs/push.md §3). A channel not listed is `default`. */
enum class PushLevel(val key: String, val label: String, val sub: String) {
    ALL("all", "All messages", "Every message and thread reply"),
    DEFAULT("default", "Default", "Mentions, replies, announcements and pinned messages"),
    MENTIONS("mentions", "Mentions only", "Mentions of you and replies to you"),
    OFF("off", "Off", "Nothing from this channel");

    companion object {
        fun of(key: String?): PushLevel = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** `POST /notifications` settings: the four switches and the channels' levels. */
data class NotificationSettings(
    val dms: Boolean = true,
    val mentions: Boolean = true,
    val announcements: Boolean = true,
    val pins: Boolean = true,
    val channels: Map<String, PushLevel> = emptyMap(),
) {
    fun level(channel: String): PushLevel = channels[channel] ?: PushLevel.DEFAULT

    /** Merges a `set` the way the belfry does (the demo keeps settings on the phone). */
    fun merged(set: JsonObject): NotificationSettings {
        val ch = channels.mapValues { it.value.key }.toMutableMap()
        set["channels"].obj()?.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { ch[k] = it } }
        return parse(JsonObject(toJson() + set.filterKeys { it != "channels" }), ch)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("dms", JsonPrimitive(dms)); put("mentions", JsonPrimitive(mentions))
        put("announcements", JsonPrimitive(announcements)); put("pins", JsonPrimitive(pins))
        put("channels", buildJsonObject { channels.forEach { (k, v) -> put(k, JsonPrimitive(v.key)) } })
    }

    companion object {
        /** The belfry's answer; missing fields take the defaults. */
        fun parse(o: JsonObject?, channelsOverride: Map<String, String>? = null): NotificationSettings {
            val d = NotificationSettings()
            if (o == null) return d
            val ch = channelsOverride ?: o["channels"].obj()?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap() ?: emptyMap()
            return NotificationSettings(
                dms = o["dms"].bool() ?: d.dms,
                mentions = o["mentions"].bool() ?: d.mentions,
                announcements = o["announcements"].bool() ?: d.announcements,
                pins = o["pins"].bool() ?: d.pins,
                channels = ch.filterKeys { GROUP_ID.matches(it) }.mapValues { PushLevel.of(it.value) }.filterValues { it != PushLevel.DEFAULT },
            )
        }

        private val GROUP_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")

        /** A `set` that changes one switch. */
        fun setSwitch(name: String, on: Boolean): JsonObject = buildJsonObject { put(name, JsonPrimitive(on)) }

        /** A `set` that changes one channel's level. */
        fun setLevel(channel: String, level: PushLevel): JsonObject =
            buildJsonObject { put("channels", buildJsonObject { put(channel, JsonPrimitive(level.key)) }) }
    }
}
