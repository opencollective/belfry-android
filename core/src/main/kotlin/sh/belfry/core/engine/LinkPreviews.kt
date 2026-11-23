package sh.belfry.core.engine

import kotlinx.coroutines.awaitCancellation
import sh.belfry.core.preview.LinkPreview
import sh.belfry.core.preview.LinkPreviews
import sh.belfry.core.preview.PreviewLoader

/**
 * Previews of outside pages (SPEC 19.80; collective-belfry docs/link-previews.md): the belfry
 * fetches the page, this device only ever asks the belfry (`GET <api>/link-preview?url=`, NIP-98
 * as me, redirects not followed). The rules and the gentleness are [PreviewLoader]; the answers
 * are kept in the store under [K_CACHE]. A screen asks [known] first (no jump when the answer is
 * there) and [get] from the row on screen. The demo answers from [DemoPreviews], with no network.
 *
 * Never call this for a private conversation: which link gets a preview is
 * [LinkPreviews.linkFor], which answers none there.
 */
class LinkPreviewService internal constructor(private val s: Session) {
    private val origin: String get() = LinkPreviews.belfryOrigin(s.community.domain)

    private val loader: PreviewLoader by lazy {
        PreviewLoader(
            origin = origin,
            now = { s.now() },
            online = { s.status.value.online },
            read = { s.store.get(K_CACHE) },
            write = { s.store.set(K_CACHE, it) },
            ask = { url ->
                val endpoint = LinkPreviews.endpoint(s.community.apiBase, s.community.domain, url)
                if (endpoint == null) 0 to null else s.signedGetNoRedirect(endpoint)
            },
        )
    }

    private val lookedUp: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    /** Every link a screen looked up here, the demo's too (tests: a private conversation leaves it empty). */
    val asked: List<String> get() = synchronized(lookedUp) { lookedUp.toList() }

    /** The belfry's API is on `https://belfry.<domain>`: the only place asked. Else there are no previews. */
    private val enabled: Boolean get() = s.localOnly || LinkPreviews.endpoint(s.community.apiBase, s.community.domain, "https://x.example/") != null

    /** What is known of URL without asking. */
    fun known(url: String): PreviewLoader.Known {
        if (s.localOnly) lookedUp.add(url)
        if (s.localOnly) return when {
            url == DemoPreviews.LOADING -> PreviewLoader.Known.Unknown
            else -> DemoPreviews.of(url)?.let { PreviewLoader.Known.Found(it) } ?: PreviewLoader.Known.None
        }
        return if (enabled) loader.known(url) else PreviewLoader.Known.None
    }

    /** The preview of URL, asked of the belfry at most once while its answer is kept. Null: the link stays a link. */
    suspend fun get(url: String): LinkPreview? {
        synchronized(lookedUp) { if (lookedUp.size >= 200) lookedUp.remove(lookedUp.first()); lookedUp.add(url) }
        if (s.localOnly) {
            if (url == DemoPreviews.LOADING) awaitCancellation()
            return DemoPreviews.of(url)
        }
        return if (enabled) loader.get(url) else null
    }

    companion object {
        const val K_CACHE = "link-previews"
    }
}

/** The demo's outside pages (canvas channels/og-*): with a picture, without one, a long title with no description, one that never answers. */
object DemoPreviews {
    const val PICTURE = "https://toolshare.example/stories/street-library"
    const val NO_PICTURE = "https://repair.example/guide"
    const val LONG_TITLE = "https://www.townpaper.example/2026/11/council-votes"
    /** Stays "loading" for ever (the grey box). */
    const val LOADING = "https://slow.example/loading"
    /** A page that gives nothing: just the link. */
    const val NOTHING = "https://plain.example/notes.txt"

    private val all = listOf(
        LinkPreview(
            PICTURE, "How a street tool library lends 400 drills a year",
            "Neighbours in a small street started lending their tools from a garage. Two years later they share drills, ladders and saws with half the town, and nobody keeps a list.",
            "ToolShare Journal", "toolshare.example", imageWidth = 1200, imageHeight = 630, demoPicture = true,
        ),
        LinkPreview(NO_PICTURE, "The repair café guide", "How to start one in your street.", "Repair", "repair.example"),
        LinkPreview(
            LONG_TITLE,
            "The council votes on Thursday on whether the old tram depot becomes a shared workshop, a covered market, or stays closed for another winter while the roof is surveyed",
            "", "", "townpaper.example",
        ),
    ).associateBy { it.url }

    fun of(url: String): LinkPreview? = all[url]
}
