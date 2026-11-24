package sh.belfry.core.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.model.World
import sh.belfry.core.nostr.Tag
import sh.belfry.core.preview.LinkPreview
import sh.belfry.core.preview.LinkPreviews
import sh.belfry.core.preview.OkPreviewNet
import sh.belfry.core.preview.PictureEncoder
import sh.belfry.core.preview.PreviewBuilder
import sh.belfry.core.preview.PreviewMode
import sh.belfry.core.preview.PreviewNet
import sh.belfry.core.preview.PreviewPicture
import sh.belfry.core.preview.Resolver
import sh.belfry.core.preview.SafeFetch
import sh.belfry.core.preview.SystemResolver

/**
 * Link previews for one session (SPEC 19.87; docs/link-previews-v2.md).
 *
 * Writing: [build] fetches the outside page FROM THIS DEVICE while the person composes (only
 * then, only for the composer's first outside link, never offline), re-encodes its picture and
 * uploads it to the belfry's Blossom (encrypted for [PreviewMode.ENCRYPTED]). The composer sends
 * [LinkPreview.tags] with the message.
 *
 * Reading: [read] builds the box from a message's tags alone; [picture] downloads the picture
 * only from the belfry's Blossom (no redirect followed), checks it (x, decryption, ox) and keeps
 * it in the store. Nothing else is ever asked: never the page, never the belfry's preview endpoint.
 */
class LinkPreviewService internal constructor(private val s: Session) {
    /** The outside web as this session reaches it (tests and the demo replace it). */
    @Volatile var net: PreviewNet = if (s.localOnly) DemoPreviews.net else OkPreviewNet(s.httpClient)
    @Volatile var resolver: Resolver = if (s.localOnly) DemoPreviews.resolver else SystemResolver

    /** How many pages [build] asked (tests). */
    val built = java.util.concurrent.atomic.AtomicInteger()
    /** Pictures downloaded by readers (tests): never anything but the belfry's Blossom. */
    val downloaded: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    private val noRedirects: OkHttpClient by lazy { s.httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    private val local: String? get() = if (s.localOnly) Session.LOCAL_BLOB else null

    /**
     * The preview of URL for a message about to be written: null = none (nothing usable, offline,
     * a reading guest, or it failed). Uploads the picture (a write: guarded like every write).
     */
    suspend fun build(url: String, mode: PreviewMode, encoder: PictureEncoder?): LinkPreview? = withContext(Dispatchers.Default) {
        if (!s.localOnly && !s.status.value.online) return@withContext null
        try { s.guardWrite() } catch (e: Exception) { return@withContext null }
        built.incrementAndGet()
        val builder = PreviewBuilder(
            SafeFetch(net, resolver), encoder,
            upload = { bytes, mime ->
                if (s.localOnly) {
                    val k = "ogp:" + sha256Hex(bytes)
                    s.store.putBlob(k, bytes)
                    Session.LOCAL_BLOB + k
                } else s.previewUploadApi().upload(s.signer, bytes, if (mime.startsWith("image/")) "preview." + mime.substringAfter('/') else "preview", mime)
            },
            blossom = s.community.blossom, local = local,
            keep = { x, plain -> s.store.putBlob(KEPT + x, plain) },
        )
        try { builder.build(url, mode) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
    }

    /** What a message shows (TEXT as it reads now, its preview tags): null = a plain link. */
    fun read(text: String, tags: List<Tag>): LinkPreview? = LinkPreviews.read(text, tags, s.community.blossom, local)

    /** A picture already on this device (mine, or downloaded before). */
    fun kept(p: PreviewPicture): ByteArray? = s.store.blob(KEPT + p.x)

    /** The picture of a preview, checked; null = show the box without it. */
    suspend fun picture(p: PreviewPicture): ByteArray? = withContext(Dispatchers.IO) {
        kept(p)?.let { return@withContext it }
        val got: ByteArray? = if (local != null && p.url.startsWith(local!!)) {
            s.store.blob(p.url.removePrefix(Session.LOCAL_BLOB))
        } else {
            // the same check the tags were read with: exactly the belfry's Blossom
            val o = LinkPreviews.originOf(s.community.blossom)
            if (s.localOnly || o == null || LinkPreviews.originOf(p.url) != o) null else try {
                downloaded.add(p.url)
                fetch(p.url)
            } catch (e: Exception) { null }
        }
        val plain = got?.let { p.open(it) } ?: return@withContext null
        s.store.putBlob(KEPT + p.x, plain)
        plain
    }

    private fun fetch(url: String): ByteArray? =
        noRedirects.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (res.code != 200) return null
            val body = res.body ?: return null
            if (body.contentLength() > LinkPreviews.PICTURE_READ_MAX) return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > LinkPreviews.PICTURE_READ_MAX) return null
                }
            }
            out.toByteArray()
        }

    companion object {
        private const val KEPT = "ogpic:"

        /** What a send adds for a preview: at most one `preview` tag and one preview `imeta` entry (the composer's). */
        fun onSend(tags: List<Tag>): List<Tag> {
            if (tags.isEmpty()) return emptyList()
            if (LinkPreviews.noPreview(tags)) return listOf(LinkPreviews.NO_PREVIEW)
            val p = tags.firstOrNull { it.size >= 5 && it[0] == "preview" } ?: return emptyList()
            return listOfNotNull(p, tags.firstOrNull { LinkPreviews.isPreviewImeta(it) })
        }

        /**
         * The one rule: a preview's picture is stored the way a FILE of that conversation is. FILES_ENCRYPTED is the
         * conversation's own file-encryption decision (true: AES-256-GCM like its files, the key inside the message).
         */
        fun modeFor(filesEncrypted: Boolean): PreviewMode = if (filesEncrypted) PreviewMode.ENCRYPTED else PreviewMode.PLAIN

        /** A direct message (sealed version 1, format 3, or NIP-17): its files are always encrypted (vectors/dm-files.json). */
        fun modeForDm(): PreviewMode = modeFor(filesEncrypted = true)

        /**
         * A channel or group message of GROUP_ID. GROUP_FILES_ENCRYPTED: the group's own file decision (SPEC 19.88:
         * [Session.privateNow], a private group in format 3, encrypted or held until it can be; its files are sealed before
         * the upload, so its preview picture is too, and the preview tags travel inside the encrypted rumor). Callers ask
         * [Session.previewModeForGroup].
         */
        @Suppress("UNUSED_PARAMETER")
        fun modeForGroup(world: World, groupId: String, groupFilesEncrypted: Boolean = false): PreviewMode = modeFor(groupFilesEncrypted)
    }
}
