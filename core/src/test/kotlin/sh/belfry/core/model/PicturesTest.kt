package sh.belfry.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which attachments show as pictures in the message (Xavier 2026-11-22; canvas v179 n-email-pictures). */
class PicturesTest {
    @Test fun byType() {
        for (t in listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif", "IMAGE/JPEG; name=a.jpg")) assertTrue(Pictures.isPicture(t, null), t)
        assertFalse(Pictures.isPicture("image/svg+xml", "logo.svg"), "SVG stays a file")
        assertFalse(Pictures.isPicture("image/tiff", "scan.tif"))
        assertFalse(Pictures.isPicture("application/pdf", "photo.jpg"), "a named type wins over the name")
    }

    @Test fun byNameWhenTheTypeSaysNothing() {
        assertTrue(Pictures.isPicture("application/octet-stream", "IMG_0042.HEIC"))
        assertTrue(Pictures.isPicture("", "https://files.example/ab12.webp"))
        assertTrue(Pictures.isPicture(null, "https://files.example/ab12.jpg?x=1"))
        assertFalse(Pictures.isPicture("", "https://files.example/ab12"))
        assertFalse(Pictures.isPicture("", "jpg"), "a bare word is no extension")
        assertFalse(Pictures.isPicture(null, null))
    }

    @Test fun channelMediaWithoutType() {
        assertTrue(Media("https://files.example/9f.png", "").isImage)
        assertTrue(Media("https://files.example/9f", "", name = "garden.jpg").isImage)
        assertFalse(Media("https://files.example/9f.pdf", "").isImage)
        assertTrue(Media("https://files.example/9f", "image/png").isImage)
    }

    /** canvas v180 one-shape/todo-conversation: a picture link in a to-do comment is the picture. */
    @Test fun todoCommentPictures() {
        val (text, pics) = todoCommentPictures("The shelf, before: https://files.example/a1.jpg. And the plan https://files.example/p.pdf")
        assertEquals("The shelf, before: . And the plan https://files.example/p.pdf", text)
        assertEquals(listOf("https://files.example/a1.jpg"), pics.map { it.url })
        val (only, two) = todoCommentPictures("https://files.example/a1.jpg https://files.example/b2.HEIC")
        assertEquals("", only)
        assertEquals(2, two.size)
        assertTrue(two.all { it.isImage })
    }
}
