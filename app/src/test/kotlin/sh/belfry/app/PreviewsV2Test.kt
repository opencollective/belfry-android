package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.chat.ComposerState
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.DemoPreviews
import sh.belfry.core.preview.LinkPreview
import sh.belfry.core.preview.LinkPreviews
import sh.belfry.core.preview.PreviewPicture

/**
 * Link previews built by the sender (SPEC 19.87), behaviour in demo mode: the composer builds the preview (the
 * demo's pages, Android's re-encoding), ✕ sends `["preview","none"]` and stops the fetch, readers draw the box
 * from the tags only, decrypt an encrypted picture, refuse a picture from anywhere but the belfry's Blossom.
 */
class PreviewsV2Test : DemoShots() {
    private val general = Demo.GENERAL
    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun channel(composer: ComposerState) {
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(general, chat = ChatState(general), composer = composer) } } }
        compose.waitForIdle()
    }

    private fun sent(prefix: String) = world.messages(general).lastOrNull { it.pubkey == session.me && it.text.startsWith(prefix) && !world.isPending(it.id) }

    @Test fun theComposerBuildsItAndTheMessageCarriesIt() {
        started()
        val composer = ComposerState()
        channel(composer)
        val cards = count("og-card")
        compose.onNodeWithTag("composer-field").performTextInput("This is what we could become: ${DemoPreviews.PICTURE}")
        compose.waitUntil(15_000) { count("og-composer") == 1 }
        val tags = composer.previewTags(world.ctx.domain, session.community.blossom)
        assertEquals("the preview tag and its picture's imeta", 2, tags.size)
        assertEquals("How a street tool library lends 400 drills a year", tags[0][2])
        assertTrue(LinkPreviews.isPreviewImeta(tags[1]))
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(10_000) { sent("This is what") != null }
        val m = sent("This is what")!!
        assertEquals(tags, LinkPreviews.wireTags(m.ev.tags))
        compose.waitUntil(10_000) { count("og-card") == cards + 1 }
        compose.waitUntil(10_000) { count("og-picture") >= 1 }
        assertEquals("the composer is empty again", 0, count("og-composer"))
        assertEquals("one page asked, once", 1, session.linkPreviews.built.get())
    }

    @Test fun closingItSendsPreviewNone() {
        started()
        val composer = ComposerState()
        channel(composer)
        val cards = count("og-card")
        compose.onNodeWithTag("composer-field").performTextInput("Read ${DemoPreviews.PICTURE}")
        compose.waitUntil(15_000) { count("og-composer") == 1 }
        compose.onNodeWithTag("og-composer-drop").performClick()
        compose.waitUntil(5_000) { count("og-composer") == 0 }
        assertEquals(DemoPreviews.PICTURE, composer.previewDropped)
        assertEquals(listOf(LinkPreviews.NO_PREVIEW), composer.previewTags(world.ctx.domain, session.community.blossom))
        // more typing after ✕: nothing is fetched again
        val built = session.linkPreviews.built.get()
        compose.onNodeWithTag("composer-field").performTextInput(" tonight")
        Thread.sleep(1_200); compose.waitForIdle()
        assertEquals(built, session.linkPreviews.built.get())
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(10_000) { sent("Read ") != null }
        assertEquals(listOf(LinkPreviews.NO_PREVIEW), LinkPreviews.wireTags(sent("Read ")!!.ev.tags))
        compose.waitForIdle()
        assertEquals("a message sent with ✕ shows no box", cards, count("og-card"))
        assertEquals("", composer.previewDropped)
    }

    @Test fun closingItWhileItIsBuilt() {
        started()
        val composer = ComposerState()
        channel(composer)
        compose.onNodeWithTag("composer-field").performTextInput("The report ${DemoPreviews.SLOW}")
        compose.waitUntil(15_000) { count("og-composer-building") == 1 }
        compose.onNodeWithTag("og-composer-drop").performClick()
        compose.waitUntil(5_000) { count("og-composer-building") == 0 }
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(10_000) { sent("The report") != null }
        assertEquals(listOf(LinkPreviews.NO_PREVIEW), LinkPreviews.wireTags(sent("The report")!!.ev.tags))
    }

    @Test fun sentWhileStillBuildingIsAPlainLink() {
        started()
        val composer = ComposerState()
        channel(composer)
        compose.onNodeWithTag("composer-field").performTextInput("Slow ${DemoPreviews.SLOW}")
        compose.waitUntil(15_000) { count("og-composer-building") == 1 }
        assertTrue(composer.previewTags(world.ctx.domain, session.community.blossom).isEmpty())
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(10_000) { sent("Slow ") != null }
        assertTrue(LinkPreviews.wireTags(sent("Slow ")!!.ev.tags).isEmpty())
    }

    @Test fun theBoxWithAndWithoutAPicture() {
        val with = PreviewsV2Fixture.build(session, DemoPreviews.PICTURE)
        val without = PreviewsV2Fixture.build(session, DemoPreviews.NO_PICTURE)
        assertNotNull(with.picture); assertNull(without.picture)
        PreviewsV2Fixture.post(this, "This is what we could become: ${DemoPreviews.PICTURE}", with.tags())
        PreviewsV2Fixture.post(this, "How to start one: ${DemoPreviews.NO_PICTURE}", without.tags())
        PreviewsV2Fixture.post(this, "Plain link https://toolshare.example/plain", emptyList())
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(general) } } }
        compose.waitUntil(10_000) { count("og-picture") == 1 }
        assertEquals("two boxes; the message without tags is a plain link", 2, count("og-card"))
    }

    @Test fun anEncryptedPictureIsDecrypted() {
        val conv = "n:" + Demo.maya.pubkey
        val pic = PreviewsV2Fixture.encryptedPicture(session)
        assertNull("not kept: the reader's path runs", session.linkPreviews.kept(pic))
        val p = LinkPreview(DemoPreviews.PICTURE, "How a street tool library lends 400 drills a year", "Neighbours lend.", "ToolShare Journal", pic)
        runBlocking { session.sendDm(conv, "Did you see this? ${DemoPreviews.PICTURE}", preview = p.tags()) }
        compose.setContent { BelfryTheme { InDemo { ConversationScreen(conv) } } }
        compose.waitUntil(10_000) { count("og-card") == 1 }
        compose.waitUntil(10_000) { count("og-picture") == 1 }
        assertNotNull("decrypted and kept", session.linkPreviews.kept(pic))
    }

    @Test fun aPictureFromAnotherOriginIsRefused() {
        val elsewhere = PreviewPicture("https://toolshare.example/og/street-library.jpg", "image/jpeg", 1200, 630, "0".repeat(64))
        val p = LinkPreview(DemoPreviews.PICTURE, "Street library", "Drills for all", "ToolShare", elsewhere)
        PreviewsV2Fixture.post(this, "Look ${DemoPreviews.PICTURE}", p.tags())
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(general) } } }
        compose.waitUntil(10_000) { count("og-card") == 1 }
        compose.waitForIdle()
        assertEquals("the box, without its picture", 0, count("og-picture"))
        assertTrue("never downloaded", session.linkPreviews.downloaded.isEmpty())
        assertFalse(session.linkPreviews.read("Look ${DemoPreviews.PICTURE}", p.tags())!!.picture != null)
    }

    @Test fun anEditKeepsTheBox() {
        started()
        val p = PreviewsV2Fixture.build(session, DemoPreviews.PICTURE)
        val m = PreviewsV2Fixture.post(this, "Look ${DemoPreviews.PICTURE}", p.tags())
        runBlocking { session.editMessage(general, m, "Look at this ${DemoPreviews.PICTURE}") }
        var k = 0
        while (world.messages(general).first { it.id == m.id }.edited == null && k++ < 200) { settle(); Thread.sleep(25) }
        val e = world.messages(general).first { it.id == m.id }
        assertEquals(p.tags(), e.preview)
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(general) } } }
        compose.waitUntil(10_000) { count("og-card") == 1 }
    }
}
