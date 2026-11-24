package sh.belfry.app

import kotlinx.coroutines.runBlocking
import sh.belfry.app.preview.AndroidPictureEncoder
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.DemoPreviews
import sh.belfry.core.engine.Session
import sh.belfry.core.model.Message
import sh.belfry.core.preview.LinkPreview
import sh.belfry.core.preview.PreviewMode
import sh.belfry.core.preview.PreviewPicture

/** SPEC 19.87 in the demo: previews built the composer's way (the demo's pages, Android's encoder), and messages that carry them. */
internal object PreviewsV2Fixture {
    fun build(s: Session, url: String, mode: PreviewMode = PreviewMode.PLAIN): LinkPreview =
        runBlocking { s.linkPreviews.build(url, mode, AndroidPictureEncoder) } ?: error("no preview for $url")

    /** A message of mine with PREVIEW's tags, once it is out of the demo outbox. */
    fun post(t: DemoShots, text: String, preview: List<List<String>>, channel: String = Demo.GENERAL): Message {
        runBlocking { t.session.postMessage(channel, text, preview = preview) }
        repeat(200) {
            t.settle()
            val w = t.world
            val m = w.messages(channel).lastOrNull { it.pubkey == t.session.me && it.text == text }
            if (m != null && !w.isPending(m.id)) return m
            Thread.sleep(25)
        }
        error("the message never left the demo outbox")
    }

    /**
     * An encrypted picture made by hand (the sender's plain copy is NOT kept on this device, so a reader's path
     * runs: the stored ciphertext read, x checked, decrypted, ox checked).
     */
    fun encryptedPicture(s: Session): PreviewPicture {
        val plain = DemoPreviews.picture
        val sealed = DmFiles.seal(plain, "a1".repeat(32), "b2".repeat(12))
        val key = "ogt:" + sealed.x
        s.store.putBlob(key, sealed.ciphertext)
        return PreviewPicture(Session.LOCAL_BLOB + key, "image/png", 600, 315, sealed.x, sealed.ox, sealed.key, sealed.nonce)
    }
}
