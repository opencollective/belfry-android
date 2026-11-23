package sh.belfry.core.preview

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.model.Links
import sh.belfry.core.model.References
import sh.belfry.core.nostr.Tag
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import kotlinx.coroutines.sync.withLock
import java.net.URI

/**
 * The preview of an outside page (SPEC 19.80; collective-belfry docs/link-previews.md; canvas
 * channels/og-*). [url] is the link as the person wrote it (what a tap opens), [host] its host
 * without `www.`; the words are the belfry's, plain text. [image] is set only when the belfry
 * itself serves it. [demoPicture]: the demo's stand-in for a picture (nothing is loaded).
 */
data class LinkPreview(
    val url: String,
    val title: String,
    val description: String,
    val site: String,
    val host: String,
    val image: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val demoPicture: Boolean = false,
) {
    val hasPicture: Boolean get() = image != null || demoPicture

    /** "ToolShare Journal · toolshare.example"; the host alone when the page names no site (or names itself by its host). */
    val siteLine: String get() = listOf(site, host).filter { it.isNotEmpty() }.distinct().joinToString(" · ")

    /** The picture's box, width over height: reserved before the picture is there. */
    val ratio: Float get() = LinkPreviews.ratio(imageWidth, imageHeight)

    fun toJson(): JsonObject = buildJsonObject {
        put("url", JsonPrimitive(url)); put("title", JsonPrimitive(title)); put("description", JsonPrimitive(description))
        put("site", JsonPrimitive(site)); put("host", JsonPrimitive(host))
        put("image", image?.let { JsonPrimitive(it) } ?: JsonNull)
        imageWidth?.let { put("w", JsonPrimitive(it)) }
        imageHeight?.let { put("h", JsonPrimitive(it)) }
    }
}

/** Where a message is read or written: only [CHANNEL] ever asks for a preview. */
enum class PreviewPlace {
    /** A channel, an open or closed group, an event's or a proposal's chat, their threads. */
    CHANNEL,
    /** A private message, Notes, a sealed conversation: asking would tell the belfry which link was sent. Never. */
    PRIVATE,
}

/** The pure rules (SPEC 19.80): which link, the picture's origin, `["preview","none"]`, how long an answer is kept. */
object LinkPreviews {
    /** The tag of a message sent with the composer's ✕: no preview for anyone. */
    val NO_PREVIEW: Tag = listOf("preview", "none")

    /** A found preview is kept a day, "nothing to show" an hour (docs/link-previews.md "When to ask"). */
    const val FOUND_SECONDS = 24 * 3600L
    const val NONE_SECONDS = 3600L
    /** After a 429 / 503 nothing is asked for this long; doubled each time in a row, up to [BACKOFF_MAX_SECONDS]. */
    const val BACKOFF_SECONDS = 60L
    const val BACKOFF_MAX_SECONDS = 900L
    /** Any other failure (no connection, 401, 403, 5xx): that link is not asked again for this long. */
    const val FAILED_SECONDS = 600L
    /** How many links are asked of the belfry at once, and how many answers are kept. */
    const val AT_ONCE = 3
    const val KEPT = 200

    const val TITLE_MAX = 200
    const val DESCRIPTION_MAX = 300
    const val SITE_MAX = 100

    fun noPreview(tags: List<Tag>): Boolean = tags.any { it.size >= 2 && it[0] == "preview" && it[1] == "none" }

    // the web's MEDIA (src/core/linkpreview.ts): pictures and files show as themselves
    private val MEDIA = Regex("\\.(jpe?g|png|gif|webp|avif|heic|svg|mp4|mov|webm|m4a|mp3|ogg|wav|pdf)(\\?|#|$)", RegexOption.IGNORE_CASE)

    private fun hostOf(url: String): String? = try {
        val u = URI(url)
        if (!"https".equals(u.scheme, ignoreCase = true) || u.rawUserInfo != null) null else u.host?.lowercase()?.trimEnd('.')?.ifEmpty { null }
    } catch (e: Exception) { null }

    /** [host] is the belfry's own: its domain or any name under it (chat., belfry., blossom., the website). */
    fun isOwn(host: String, domain: String): Boolean {
        val d = domain.lowercase()
        return d.isNotEmpty() && (host == d || host.endsWith(".$d"))
    }

    /**
     * The first link to an outside page in TEXT, or null: `https://` only, not something of this
     * belfry (its chat, its API, its files, its website: those have their own cards and names), not
     * a file (a message's attachments in its `imeta` tags, anything under [files], an address that
     * ends like a picture, a recording or a PDF), not one of [skip] (event links shown as cards).
     * Null too when the message is tagged `["preview","none"]`. The address is returned as written.
     */
    fun outsideLink(text: String, tags: List<Tag>, domain: String, files: String = "", skip: Collection<String> = emptyList()): String? {
        if (text.isEmpty() || noPreview(tags)) return null
        val attached = tags.filter { it.isNotEmpty() && it[0] == "imeta" }.flatMap { t -> t.filter { it.startsWith("url ") }.map { it.substring(4) } }.toSet()
        val fileHost = if (files.isEmpty()) null else hostOf(files)
        for (f in Links.find(text)) {
            val u = f.url
            if (!u.startsWith("https://")) continue
            if (u in attached || u in skip || MEDIA.containsMatchIn(u)) continue
            if (domain.isNotEmpty() && References.target(u, domain) != null) continue
            val host = hostOf(u) ?: continue
            if (isOwn(host, domain) || host == fileHost) continue
            return u
        }
        return null
    }

    /** [outsideLink] where the message stands: never in a private conversation (no request is made for those). */
    fun linkFor(place: PreviewPlace, text: String, tags: List<Tag>, domain: String, files: String = "", skip: Collection<String> = emptyList()): String? =
        if (place == PreviewPlace.PRIVATE) null else outsideLink(text, tags, domain, files, skip)

    /** The belfry's origin for DOMAIN: the only place previews are asked of and pictures are shown from. */
    fun belfryOrigin(domain: String): String = "https://belfry.${domain.lowercase()}"

    /**
     * Where the preview of URL is asked: `<apiBase>/link-preview?url=<encoded>`, and only when the
     * API is on `https://belfry.<domain>` itself (a belfry that keeps its API elsewhere gets no
     * previews: the picture rule could not hold). Null: don't ask.
     */
    fun endpoint(apiBase: String, domain: String, url: String): String? {
        if (domain.isEmpty() || originOf(apiBase) != belfryOrigin(domain)) return null
        return apiBase.trimEnd('/') + "/link-preview?url=" + java.net.URLEncoder.encode(url, "UTF-8").replace("+", "%20")
    }

    /** `https://host` of an https address on the default port with no credentials, else null. */
    fun originOf(url: String): String? = try {
        val u = URI(url)
        val host = u.host?.lowercase()
        if (!"https".equals(u.scheme, ignoreCase = true) || host.isNullOrEmpty() || u.rawUserInfo != null || (u.port != -1 && u.port != 443)) null
        else "https://$host"
    } catch (e: Exception) { null }

    /** IMAGE when the belfry at ORIGIN serves it (exactly that origin, https), else null: no picture. */
    fun imageFrom(image: String?, origin: String): String? =
        image?.trim()?.takeIf { it.isNotEmpty() && origin.isNotEmpty() && originOf(it) == origin && it.none { c -> c.isWhitespace() } }

    private fun words(v: String?, max: Int): String = (v ?: "").replace(Regex("\\s+"), " ").trim().take(max)

    /**
     * The belfry's 200 answer for URL as a preview: kept only with a title or a description; the
     * picture only from ORIGIN ([imageFrom]); sizes only when both are sensible numbers.
     */
    fun parse(url: String, body: JsonObject?, origin: String): LinkPreview? {
        if (body == null) return null
        val title = words(body.s("title"), TITLE_MAX)
        val description = words(body.s("description"), DESCRIPTION_MAX)
        if (title.isEmpty() && description.isEmpty()) return null
        val host = hostOf(url)?.removePrefix("www.") ?: return null
        val image = imageFrom(body.s("image"), origin)
        val w = body["image_width"].int()?.takeIf { it in 1..20_000 }
        val h = body["image_height"].int()?.takeIf { it in 1..20_000 }
        val sized = image != null && w != null && h != null
        return LinkPreview(url, title, description, words(body.s("site_name"), SITE_MAX), host, image, w.takeIf { sized }, h.takeIf { sized })
    }

    /** The picture's box: its own shape between 3:2 and 12:5, 2:1 when the page gave no size (the boards' 300 × 150). */
    fun ratio(width: Int?, height: Int?): Float =
        if (width != null && height != null && width > 0 && height > 0) (width.toFloat() / height).coerceIn(1.5f, 2.4f) else 2f

    /** An answer of AT is still good at NOW: a day for a preview, an hour for nothing. */
    fun fresh(found: Boolean, at: Long, now: Long): Boolean = now >= at && now - at < (if (found) FOUND_SECONDS else NONE_SECONDS)
}

/**
 * The answers kept on the device (in memory, and as one JSON value in the store): the link as
 * written → when it was answered and what with (null: nothing to show). At most
 * [LinkPreviews.KEPT]: the oldest answers go first.
 */
class PreviewCache {
    data class Entry(val at: Long, val preview: LinkPreview?)

    private val entries = LinkedHashMap<String, Entry>()

    /** The answer for URL when it is still fresh. */
    @Synchronized fun get(url: String, now: Long): Entry? = entries[url]?.takeIf { LinkPreviews.fresh(it.preview != null, it.at, now) }

    @Synchronized fun put(url: String, preview: LinkPreview?, now: Long) {
        entries.remove(url)
        entries[url] = Entry(now, preview)
        // stale answers first, then the oldest
        if (entries.size > LinkPreviews.KEPT) entries.entries.removeAll { !LinkPreviews.fresh(it.value.preview != null, it.value.at, now) }
        while (entries.size > LinkPreviews.KEPT) entries.remove(entries.keys.first())
    }

    @get:Synchronized val size: Int get() = entries.size

    @Synchronized fun toJsonString(): String = JsonObject(entries.mapValues { (_, e) ->
        buildJsonObject { put("at", JsonPrimitive(e.at)); put("p", e.preview?.toJson() ?: JsonNull) }
    }).toString()

    companion object {
        /** The stored copy read back; pictures are kept only when they are still ORIGIN's (a stored value is not trusted more than an answer). */
        fun parse(text: String?, origin: String): PreviewCache {
            val c = PreviewCache()
            val o = parseJsonOrNull(text ?: "").obj() ?: return c
            for ((url, v) in o) {
                val e = v.obj() ?: continue
                val at = e["at"].long() ?: continue
                val p = e["p"].obj()
                val preview = if (p == null) null else {
                    val image = LinkPreviews.imageFrom(p.s("image"), origin)
                    LinkPreview(
                        url, p.s("title") ?: "", p.s("description") ?: "", p.s("site") ?: "", p.s("host") ?: continue,
                        image, p["w"].int().takeIf { image != null }, p["h"].int().takeIf { image != null },
                    ).takeIf { it.title.isNotEmpty() || it.description.isNotEmpty() } ?: continue
                }
                c.entries[url] = Entry(at, preview)
            }
            return c
        }
    }
}

/**
 * Asks the belfry for previews, gently (SPEC 19.80): one request per link (two rows showing the
 * same link wait for the same answer), at most [LinkPreviews.AT_ONCE] at a time, answers kept
 * ([PreviewCache]: a day, an hour for nothing), nothing asked offline, a 429 or a 503 stops
 * everything for a while ([LinkPreviews.BACKOFF_SECONDS], doubling), any other failure stops that
 * link for [LinkPreviews.FAILED_SECONDS]. Never retries by itself: a row asks when it comes on
 * screen. [ask] makes the one HTTP call (status + JSON body; it may throw: no connection).
 */
class PreviewLoader(
    private val origin: String,
    private val now: () -> Long,
    private val online: () -> Boolean,
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val ask: suspend (url: String) -> Pair<Int, JsonObject?>,
) {
    /** What is known of a link without asking. */
    sealed interface Known {
        data class Found(val preview: LinkPreview) : Known
        /** Nothing to show (answered so, or it can't be asked just now): the link stays a link. */
        data object None : Known
        /** Not asked yet, and it may be asked now. */
        data object Unknown : Known
    }

    private val cache: PreviewCache by lazy { PreviewCache.parse(try { read() } catch (e: Exception) { null }, origin) }
    private val gate = kotlinx.coroutines.sync.Semaphore(LinkPreviews.AT_ONCE)
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var pausedUntil = 0L
    @Volatile private var backoff = LinkPreviews.BACKOFF_SECONDS
    private val count = java.util.concurrent.atomic.AtomicInteger()

    /** How many requests were made (tests). */
    val requests: Int get() = count.get()
    /** Until when nothing is asked (unix seconds; 0: never paused). */
    val paused: Long get() = pausedUntil

    private fun mayAsk(url: String): Boolean {
        val t = now()
        return online() && t >= pausedUntil && t >= (failedUntil[url] ?: 0L)
    }

    fun known(url: String): Known {
        cache.get(url, now())?.let { return it.preview?.let { p -> Known.Found(p) } ?: Known.None }
        return if (mayAsk(url)) Known.Unknown else Known.None
    }

    /** The preview of URL: from the kept answers, else asked once. Null: nothing to show. */
    suspend fun get(url: String): LinkPreview? {
        cache.get(url, now())?.let { return it.preview }
        if (!mayAsk(url)) return null
        return locks.getOrPut(url) { kotlinx.coroutines.sync.Mutex() }.withLock {
            // answered while this one waited for the same link
            cache.get(url, now())?.let { return@withLock it.preview }
            if (!mayAsk(url)) return@withLock null
            gate.acquire()
            try {
                // a 429 arrived while this one waited its turn
                if (!mayAsk(url)) return@withLock null
                count.incrementAndGet()
                val (status, body) = try { ask(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { 0 to null }
                settle(url, status, body)
            } finally {
                gate.release()
            }
        }
    }

    private fun settle(url: String, status: Int, body: JsonObject?): LinkPreview? {
        val t = now()
        return when (status) {
            200 -> {
                val p = LinkPreviews.parse(url, body, origin)
                backoff = LinkPreviews.BACKOFF_SECONDS
                keep(url, p, t)
                p
            }
            // 404: nothing to show; 422: an address the belfry never fetches
            404, 422 -> { backoff = LinkPreviews.BACKOFF_SECONDS; keep(url, null, t); null }
            429, 503 -> {
                pausedUntil = t + backoff
                failedUntil[url] = pausedUntil
                backoff = (backoff * 2).coerceAtMost(LinkPreviews.BACKOFF_MAX_SECONDS)
                null
            }
            else -> {
                failedUntil[url] = t + LinkPreviews.FAILED_SECONDS
                // not signed in to this belfry, or no pass: no other link will fare better
                if (status == 401 || status == 403) pausedUntil = t + LinkPreviews.FAILED_SECONDS
                null
            }
        }
    }

    private fun keep(url: String, preview: LinkPreview?, t: Long) {
        cache.put(url, preview, t)
        failedUntil.remove(url)
        try { write(cache.toJsonString()) } catch (e: Exception) { }
    }
}
