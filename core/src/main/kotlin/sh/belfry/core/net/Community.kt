package sh.belfry.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.belfry.core.crypto.Hex
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str
import java.io.IOException
import java.net.URLEncoder

/** JavaScript's encodeURIComponent (what the web chat uses in links and thread keys). */
fun encodeURIComponent(s: String): String =
    URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20").replace("%21", "!").replace("%27", "'")
        .replace("%28", "(").replace("%29", ")").replace("%7E", "~")

/**
 * Everything a client needs to know about a belfry, from its domain (SPEC 1): the discovery
 * document `https://<domain>/.well-known/belfry.json` over the fallbacks.
 */
data class Community(
    val domain: String,
    val name: String,
    val groupsRelay: String,
    val mainRelay: String,
    val signerRelays: List<String>,
    val blossom: String,
    val apiBase: String,
    val channelInvites: String,
    val follows: String,
    val authorize: String,
    val token: String,
    val revoke: String,
    val logout: String,
    /** Community admins (64-hex keys only). */
    val admins: List<String>,
    /** The chat on the web, with a trailing slash. */
    val chatUrl: String,
    /** The belfry's own key, when the document gives it. */
    val belfryPubkey: String? = null,
    /** Members' own agents (belfry.json `agents`): agent key → its member's key, 64-hex only. */
    val agents: Map<String, String> = emptyMap(),
    /** The belfry's branding (belfry.json `branding`, web 2026-11-11): its primary colour, #rrggbb. */
    val brandPrimary: String? = null,
    /** Its logo (https only). */
    val brandLogo: String? = null,
    /** Its cover image (https only) and its secondary colour (#rrggbb): badges and highlights. */
    val brandCover: String? = null,
    val brandSecondary: String? = null,
    /**
     * Where the phone apps sign in without a browser (belfry.json `sign_in.sessions.api`, the full
     * address of `…/session`; SPEC 19.20). Null: this belfry does not say it has it.
     */
    val sessionApi: String? = null,
) {
    /** The four branding values as the belfry's `/branding` takes them ("" = none). */
    val branding: Branding get() = Branding(brandLogo.orEmpty(), brandCover.orEmpty(), brandPrimary.orEmpty(), brandSecondary.orEmpty())

    fun withBranding(b: Branding): Community = copy(
        brandLogo = b.logo.ifEmpty { null }, brandCover = b.cover.ifEmpty { null },
        brandPrimary = b.primary.ifEmpty { null }, brandSecondary = b.secondary.ifEmpty { null },
    )

    /** The web chat's link to a channel (the member invite link). */
    fun channelLink(id: String): String = chatUrl + "#/c/" + encodeURIComponent(id)

    /** My address here for a username. */
    fun address(username: String): String = "$username@$domain"

    fun toJson(): JsonObject = buildJsonObject {
        put("domain", JsonPrimitive(domain))
        put("name", JsonPrimitive(name))
        put("groupsRelay", JsonPrimitive(groupsRelay))
        put("mainRelay", JsonPrimitive(mainRelay))
        put("signerRelays", buildJsonArray { signerRelays.forEach { add(JsonPrimitive(it)) } })
        put("blossom", JsonPrimitive(blossom))
        put("apiBase", JsonPrimitive(apiBase))
        put("channelInvites", JsonPrimitive(channelInvites))
        put("follows", JsonPrimitive(follows))
        put("authorize", JsonPrimitive(authorize))
        put("token", JsonPrimitive(token))
        put("revoke", JsonPrimitive(revoke))
        put("logout", JsonPrimitive(logout))
        put("admins", buildJsonArray { admins.forEach { add(JsonPrimitive(it)) } })
        put("chatUrl", JsonPrimitive(chatUrl))
        if (belfryPubkey != null) put("belfryPubkey", JsonPrimitive(belfryPubkey))
        if (agents.isNotEmpty()) put("agents", buildJsonObject { agents.forEach { (a, o) -> put(a, JsonPrimitive(o)) } })
        if (sessionApi != null) put("sessionApi", JsonPrimitive(sessionApi))
        // kept so the colours and the logo are there before the belfry answers (and offline)
        val b = branding
        if (b != Branding()) put("branding", b.toJson())
    }

    /** For the offline cache. */
    fun toJsonString(): String = toJson().toString()

    companion object {
        fun cleanDomain(raw: String): String =
            raw.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')

        /** A plausible domain (letters, digits, dots, hyphens; at least one dot). */
        fun isDomain(s: String): Boolean {
            val d = s.lowercase()
            return d.length in 4..253 && DOMAIN.matches(d)
        }

        private val DOMAIN = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

        fun defaults(rawDomain: String): Community {
            val d = cleanDomain(rawDomain)
            val api = "https://belfry.$d/api/v1"
            return Community(
                domain = d, name = d,
                groupsRelay = "wss://groups-relay.$d", mainRelay = "wss://relay.$d", signerRelays = listOf("wss://bunker.$d"),
                blossom = "https://blossom.$d", apiBase = api, channelInvites = "$api/channel-invites", follows = "$api/me/follows",
                authorize = "https://belfry.$d/connect/authorize", token = "https://belfry.$d/connect/token",
                revoke = "https://belfry.$d/connect/revoke", logout = "https://belfry.$d/connect/logout",
                admins = emptyList(), chatUrl = "https://$d/chat/",
            )
        }

        /** The discovery document over the defaults (the SPEC 1 table). A null document = the defaults. */
        fun parse(domain: String, doc: JsonObject?): Community {
            var c = defaults(domain)
            if (doc == null) return c
            fun clean(v: String?): String? = v?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            fun https(v: String?): String? = clean(v)?.takeIf { it.startsWith("https://") || it.startsWith("http://localhost") }
            fun wss(v: String?): String? = clean(v)?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }

            doc.s("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { c = c.copy(name = it) }
            c = c.withBranding(Branding.clean(doc["branding"].obj()))
            val relays = doc["relays"].obj()
            wss(relays?.s("groups"))?.let { c = c.copy(groupsRelay = it) }
            wss(relays?.s("community"))?.let { c = c.copy(mainRelay = it) }
            relays?.get("signer").arr()?.mapNotNull { wss(it.str()) }?.takeIf { it.isNotEmpty() }?.let { c = c.copy(signerRelays = it) }
            https(doc.s("blossom"))?.let { c = c.copy(blossom = it) }
            val api = doc["api"].obj()
            https(api?.s("base"))?.let { c = c.copy(apiBase = it, channelInvites = "$it/channel-invites", follows = "$it/me/follows") }
            https(api?.s("channel_invites"))?.let { c = c.copy(channelInvites = it) }
            https(api?.s("follows"))?.let { c = c.copy(follows = it) }
            val connect = doc["sign_in"].obj()?.get("connect").obj()
            https(connect?.s("authorize"))?.let { c = c.copy(authorize = it) }
            https(connect?.s("token"))?.let { c = c.copy(token = it) }
            https(connect?.s("logout"))?.let { c = c.copy(logout = it) }
            https(connect?.s("revoke"))?.let { c = c.copy(revoke = it) }
            https(doc["sign_in"].obj()?.get("sessions").obj()?.s("api"))?.let { c = c.copy(sessionApi = it) }
            val admins = LinkedHashSet<String>()
            doc["admins"].arr()?.forEach { a -> a.str()?.takeIf(Hex::isKey)?.let(admins::add) }
            doc["access"].obj()?.get("admins").arr()?.forEach { a -> a.str()?.takeIf(Hex::isKey)?.let(admins::add) }
            c = c.copy(admins = admins.toList())
            val chat = doc["apps"].arr()?.mapNotNull { it.obj() }?.firstOrNull { it.s("name")?.lowercase() == "chat" }?.s("url")
            if (chat != null && chat.startsWith("https://")) c = c.copy(chatUrl = if (chat.endsWith("/")) chat else "$chat/")
            doc["nostr"].obj()?.s("pubkey")?.takeIf(Hex::isKey)?.let { c = c.copy(belfryPubkey = it) }
            c = c.copy(agents = agentsOf(doc["agents"].obj()))
            return c
        }

        fun parse(domain: String, document: String): Community = parse(domain, parseJsonOrNull(document) as? JsonObject)

        /** From [toJsonString]; null when the text is not one. */
        fun fromJsonString(text: String): Community? {
            val o = parseJsonOrNull(text) as? JsonObject ?: return null
            val domain = o.s("domain") ?: return null
            val d = defaults(domain)
            fun list(key: String): List<String>? = (o[key] as? JsonArray)?.mapNotNull { it.str() }
            return Community(
                domain = d.domain,
                name = o.s("name") ?: d.name,
                groupsRelay = o.s("groupsRelay") ?: d.groupsRelay,
                mainRelay = o.s("mainRelay") ?: d.mainRelay,
                signerRelays = list("signerRelays")?.takeIf { it.isNotEmpty() } ?: d.signerRelays,
                blossom = o.s("blossom") ?: d.blossom,
                apiBase = o.s("apiBase") ?: d.apiBase,
                channelInvites = o.s("channelInvites") ?: d.channelInvites,
                follows = o.s("follows") ?: d.follows,
                authorize = o.s("authorize") ?: d.authorize,
                token = o.s("token") ?: d.token,
                revoke = o.s("revoke") ?: d.revoke,
                logout = o.s("logout") ?: d.logout,
                admins = list("admins")?.filter(Hex::isKey) ?: emptyList(),
                chatUrl = o.s("chatUrl") ?: d.chatUrl,
                belfryPubkey = o.s("belfryPubkey")?.takeIf(Hex::isKey),
                agents = agentsOf(o["agents"].obj()),
                sessionApi = o.s("sessionApi"),
            ).withBranding(Branding.clean(o["branding"].obj()))
        }

        /** `{agent key: member key}`, both 64-hex; anything else dropped. */
        fun agentsOf(o: JsonObject?): Map<String, String> =
            o?.entries?.mapNotNull { (a, v) -> v.str()?.takeIf { Hex.isKey(a) && Hex.isKey(it) }?.let { a to it } }?.toMap() ?: emptyMap()
    }
}

/**
 * The belfry's branding (web core/branding.ts, 2026-11-11): admins set it (Me → Branding,
 * `POST <api>/branding`), the belfry keeps it in its own key's profile and lists it in
 * belfry.json `branding`. "" = not set. Addresses are https only, colours `#rrggbb`.
 */
data class Branding(val logo: String = "", val cover: String = "", val primary: String = "", val secondary: String = "") {
    fun toJson(): JsonObject = buildJsonObject {
        put("logo", JsonPrimitive(logo)); put("cover", JsonPrimitive(cover))
        put("primary", JsonPrimitive(primary)); put("secondary", JsonPrimitive(secondary))
    }

    companion object {
        private val HEX = Regex("^#[0-9a-fA-F]{6}$")
        private val URL = Regex("^https://[^\\s\"<>]+$")
        fun isColour(s: String): Boolean = HEX.matches(s)
        fun isImageUrl(s: String): Boolean = s.length <= 2000 && URL.matches(s)

        /** Only well-formed values survive; anything else reads as not set. */
        fun clean(o: JsonObject?): Branding = Branding(
            logo = o?.s("logo")?.trim()?.takeIf(::isImageUrl).orEmpty(),
            cover = o?.s("cover")?.trim()?.takeIf(::isImageUrl).orEmpty(),
            primary = o?.s("primary")?.trim()?.takeIf(::isColour)?.lowercase().orEmpty(),
            secondary = o?.s("secondary")?.trim()?.takeIf(::isColour)?.lowercase().orEmpty(),
        )
    }
}

/** A guest link: `https://chat.<domain>/join/<token>` (also the web's `…/#/join/<token>`). */
/**
 * An event's address on the chat host, `https://chat.<domain>/events/<link>` (the belfry
 * redirects it to the web chat's `#/events/<link>`; the app opens the event page). Only the
 * domain and the link are read; nothing of the address is ever displayed.
 */
data class EventLink(val domain: String, val link: String) {
    companion object {
        private val LINK = Regex("^https://chat\\.([a-z0-9.-]+)/events/([A-Za-z0-9_-]{1,80})/?$")

        fun parse(url: String): EventLink? {
            val m = LINK.matchEntire(url.trim()) ?: return null
            val domain = m.groupValues[1]
            if (!Community.isDomain(domain)) return null
            return EventLink(domain, m.groupValues[2])
        }
    }
}

data class InviteLink(val domain: String, val token: String) {
    companion object {
        private val LINK = Regex("^https://chat\\.([a-z0-9.-]+)/(?:#/)?join/([A-Za-z0-9_-]{20,})/?$")

        /** Null when the address is not a guest link. Nothing of the address is ever displayed. */
        fun parse(url: String): InviteLink? {
            val m = LINK.matchEntire(url.trim()) ?: return null
            val domain = m.groupValues[1]
            if (!Community.isDomain(domain)) return null
            return InviteLink(domain, m.groupValues[2])
        }

        fun isToken(s: String): Boolean = Regex("^[A-Za-z0-9_-]{20,}$").matches(s)
    }
}

/** Blocking OkHttp calls moved off the caller's thread. */
internal suspend fun OkHttpClient.fetch(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
    newCall(request).execute().use { res -> res.code to (res.body?.string() ?: "") }
}

/** belfry.json fetched again: a new document (with its ETag), or "the copy you have is current" (304). */
sealed interface Revalidated {
    data class Fresh(val community: Community, val etag: String?) : Revalidated
    data object NotModified : Revalidated
}

object Discovery {
    /** Fetches and parses belfry.json. Throws IOException when there is none (use the cached copy). */
    suspend fun fetch(domain: String, http: OkHttpClient): Community =
        (revalidate(domain, http, null) as Revalidated.Fresh).community

    /**
     * belfry.json, always asked of the belfry (`Cache-Control: no-cache`, never a stored answer).
     * With the [etag] of the copy the app holds, the belfry may answer 304: [Revalidated.NotModified].
     * Throws IOException when there is no document (offline: keep the copy).
     */
    suspend fun revalidate(domain: String, http: OkHttpClient, etag: String?): Revalidated {
        val d = Community.cleanDomain(domain)
        if (!Community.isDomain(d)) throw IOException("not a domain")
        val b = Request.Builder().url("https://$d/.well-known/belfry.json").header("Accept", "application/json").header("Cache-Control", "no-cache")
        if (!etag.isNullOrEmpty()) b.header("If-None-Match", etag)
        return withContext(Dispatchers.IO) {
            http.newCall(b.build()).execute().use { res ->
                if (res.code == 304 && !etag.isNullOrEmpty()) return@use Revalidated.NotModified
                val doc = parseJsonOrNull(res.body?.string() ?: "") as? JsonObject
                if (res.code != 200 || doc == null) throw IOException("no belfry.json")
                Revalidated.Fresh(Community.parse(d, doc), res.header("ETag"))
            }
        }
    }

    /** NIP-05: the key behind NAME@DOMAIN (`_smtp`, `_groups`, a username), or null. */
    suspend fun nip05(name: String, domain: String, http: OkHttpClient): String? {
        val url = "https://$domain/.well-known/nostr.json".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("name", name)?.build() ?: return null
        return try {
            val (code, body) = http.fetch(Request.Builder().url(url).build())
            if (code != 200) return null
            val names = (parseJsonOrNull(body) as? JsonObject)?.get("names").obj() ?: return null
            pick(names, name)
        } catch (e: IOException) {
            null
        }
    }

    internal fun pick(names: JsonObject, name: String): String? {
        val k = names.s(name) ?: names.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.str()
        return k?.lowercase()?.takeIf(Hex::isKey)
    }

    /** The mail bridge's key (SPEC 10). */
    suspend fun bridgeKey(domain: String, http: OkHttpClient): String? = nip05("_smtp", domain, http)

    /** The key that posts e-mail cards and guest joins. */
    suspend fun groupsKey(domain: String, http: OkHttpClient): String? = nip05("_groups", domain, http)
}
