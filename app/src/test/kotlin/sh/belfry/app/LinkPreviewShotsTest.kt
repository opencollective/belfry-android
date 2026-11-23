package sh.belfry.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.chat.ComposerState
import sh.belfry.app.ui.chat.ThreadScreen
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.DemoPreviews
import sh.belfry.core.preview.LinkPreviews

/**
 * Previews of outside pages (SPEC 19.80; canvas channels/og-message, og-no-image, og-no-preview,
 * og-several, og-loading, og-thread, og-composer, og-laptop). The demo answers from
 * `DemoPreviews`: nothing here touches the network.
 */
class LinkPreviewShotsTest : DemoShots() {
    private val general = Demo.GENERAL
    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /** og-message + og-several: the picture, site · host, title, two lines of description; only the first link. */
    @Test fun message() {
        started()
        myMessage("This is what we could become: ${DemoPreviews.PICTURE} (and ${DemoPreviews.NO_PICTURE} for the how)")
        demoShot("og-message") { ChannelScreen(general, onMenu = {}) }
        assertEquals("only the first link gets a preview", 1, count("og-card"))
        assertTrue(DemoPreviews.PICTURE in session.linkPreviews.asked)
        assertFalse("the second link is never looked up", DemoPreviews.NO_PICTURE in session.linkPreviews.asked)
    }

    /** og-no-image, a long title with no description, and og-no-preview (a page that gives nothing: just the link). */
    @Test fun withoutPicture() {
        started()
        myMessage("How to start one: ${DemoPreviews.NO_PICTURE}")
        myMessage("Thursday's vote ${DemoPreviews.LONG_TITLE}")
        myMessage("My notes are at ${DemoPreviews.NOTHING}")
        demoShot("og-no-image") { ChannelScreen(general, onMenu = {}) }
        assertEquals(2, count("og-card"))
        assertEquals(0, count("og-loading"))
    }

    /** og-loading: a box of the card's size while the belfry is asked. */
    @Test fun loading() {
        started()
        myMessage("Slow one ${DemoPreviews.LOADING}")
        demoShot("og-loading") { ChannelScreen(general, onMenu = {}) }
        assertEquals(1, count("og-loading"))
    }

    /** og-thread: the same card under a reply. */
    @Test fun thread() {
        started()
        val root = myMessage("Ideas for the tool library?")
        runBlocking { session.replyInThread(general, root.ev, "This is what we could become: ${DemoPreviews.PICTURE}") }
        settle()
        demoShot("og-thread") { ThreadScreen(general, root.id) }
        assertEquals(1, count("og-card"))
    }

    /** og-composer: the preview above the field with ✕, before sending. */
    @Test fun composer() {
        val composer = ComposerState().apply { set("This is what we could become: ${DemoPreviews.PICTURE}") }
        demoShot("og-composer") { ChannelScreen(general, onMenu = {}, chat = ChatState(general), composer = composer) }
        assertEquals(1, count("og-composer"))
    }

    /** og-laptop: on a wide screen the card keeps a readable width. */
    @Config(qualifiers = "w1280dp-h800dp-land-240dpi")
    @Test fun wide() {
        started()
        myMessage("This is what we could become: ${DemoPreviews.PICTURE}")
        demoShot("og-wide") { ChannelScreen(general, onMenu = {}) }
        assertEquals(1, count("og-card"))
    }

    /** ✕ sends the message with ["preview","none"], and it shows no preview; without ✕ there is no tag and the card shows. */
    @Test fun closingThePreviewSendsItWithout() {
        val composer = ComposerState()
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(general, chat = ChatState(general), composer = composer) } } }
        compose.waitForIdle()
        val before = count("og-card")
        compose.onNodeWithTag("composer-field").performTextInput("Read ${DemoPreviews.PICTURE}")
        compose.waitUntil(5_000) { count("og-composer") == 1 }
        compose.onNodeWithTag("og-composer-drop").performClick()
        compose.waitUntil(5_000) { count("og-composer") == 0 }
        assertEquals(DemoPreviews.PICTURE, composer.noPreviewFor)
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { composer.value.text.isEmpty() }
        compose.waitUntil(5_000) { world.messages(general).any { it.pubkey == session.me && it.text.startsWith("Read ") } }
        val sent = world.messages(general).last { it.pubkey == session.me && it.text.startsWith("Read ") }
        assertTrue("the message carries preview none", sent.ev.tags.contains(LinkPreviews.NO_PREVIEW))
        compose.waitForIdle()
        assertEquals("a message sent with ✕ shows no preview", before, count("og-card"))
        assertEquals("", composer.noPreviewFor)

        // the same link sent as it is: no tag
        compose.onNodeWithTag("composer-field").performTextInput("Again ${DemoPreviews.PICTURE}")
        compose.waitUntil(5_000) { count("og-composer") == 1 }
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { world.messages(general).any { it.pubkey == session.me && it.text.startsWith("Again ") } }
        val again = world.messages(general).last { it.pubkey == session.me && it.text.startsWith("Again ") }
        assertFalse(LinkPreviews.noPreview(again.ev.tags))
    }

    /** A message that came tagged ["preview","none"] has no preview for anyone, and its link is never looked up. */
    @Test fun aMessageTaggedNoneHasNoPreview() {
        started()
        runBlocking { session.postMessage(general, "Read ${DemoPreviews.PICTURE}", extra = listOf(LinkPreviews.NO_PREVIEW)) }
        settle()
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(general, onMenu = {}) } } }
        compose.waitForIdle()
        assertEquals(0, count("og-card"))
        assertFalse(DemoPreviews.PICTURE in session.linkPreviews.asked)
    }

    /** ✕ is for the link it was tapped on: another link typed after gets its preview and no tag. */
    @Test fun closingIsForThatLinkOnly() {
        val composer = ComposerState().apply { set("Read ${DemoPreviews.PICTURE}") }
        composer.noPreviewFor = DemoPreviews.PICTURE
        assertEquals(listOf(LinkPreviews.NO_PREVIEW), composer.previewTags(Demo.DOMAIN))
        composer.set("Read ${DemoPreviews.NO_PICTURE}")
        assertEquals(emptyList<List<String>>(), composer.previewTags(Demo.DOMAIN))
        composer.noPreviewFor = DemoPreviews.PICTURE
        assertEquals(emptyList<List<String>>(), composer.previewTags(Demo.DOMAIN))
    }

    /** A tap on the card opens the link, as a tap on the link does. */
    @Test fun aTapOpensTheLink() {
        started()
        myMessage("This is what we could become: ${DemoPreviews.PICTURE}")
        val opened = ArrayList<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened.add(uri) } }
        compose.setContent { BelfryTheme { InDemo { CompositionLocalProvider(LocalUriHandler provides handler) { ChannelScreen(general, onMenu = {}) } } } }
        compose.waitUntil(5_000) { count("og-card") == 1 }
        compose.onNodeWithTag("og-card", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf(DemoPreviews.PICTURE), opened)
    }

    /** A private conversation: no preview under a message, none above the field, and nothing is ever looked up. */
    @Test fun noneInAPrivateConversation() {
        started()
        val conv = "n:" + Demo.maya.pubkey
        val text = "Look at this: ${DemoPreviews.PICTURE}"
        runBlocking { session.sendDm(conv, text) }
        compose.setContent { BelfryTheme { InDemo { ConversationScreen(conv) } } }
        compose.waitUntil(10_000) { session.dms.value.thread(conv).any { it.text == text } }
        compose.onNodeWithTag("composer-field").performTextInput("and ${DemoPreviews.NO_PICTURE}")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals(0, count("og-card"))
        assertEquals(0, count("og-loading"))
        assertEquals(0, count("og-composer"))
        assertEquals("nothing is asked for a private conversation", emptyList<String>(), session.linkPreviews.asked)
    }
}
