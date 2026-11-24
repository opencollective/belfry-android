package sh.belfry.app

import androidx.compose.ui.test.onAllNodesWithTag
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.chat.ComposerState
import sh.belfry.app.ui.chat.ThreadScreen
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.DemoPreviews
import sh.belfry.core.preview.PreviewMode

/**
 * Pictures of link previews built by the sender (SPEC 19.87; canvas channels/og-composer, og-message,
 * og-no-image, og-thread, og-dm, og-laptop). Taken by hand: `-f shots='*PreviewsV2ShotsTest*'`.
 */
class PreviewsV2ShotsTest : DemoShots() {
    private val general = Demo.GENERAL
    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /** og-composer while the page is asked: one quiet line with ✕. */
    @Test fun composerBuilding() {
        val composer = ComposerState().apply { set("The council's report is out ${DemoPreviews.SLOW}") }
        demoShot("previews-v2-composer-building", act = { compose.waitUntil(15_000) { count("og-composer-building") == 1 } }) {
            ChannelScreen(general, chat = ChatState(general), composer = composer)
        }
    }

    /** og-composer once built: the picture, the title, the site and ✕. */
    @Test fun composerReady() {
        val composer = ComposerState().apply { set("This is what we could become: ${DemoPreviews.PICTURE}") }
        demoShot("previews-v2-composer-ready", act = { compose.waitUntil(15_000) { count("og-composer") == 1 } }) {
            ChannelScreen(general, chat = ChatState(general), composer = composer)
        }
    }

    /** og-message: the box with its picture under the message. */
    @Test fun messageWithPicture() {
        val p = PreviewsV2Fixture.build(session, DemoPreviews.PICTURE)
        PreviewsV2Fixture.post(this, "This is what we could become: ${DemoPreviews.PICTURE}", p.tags())
        demoShot("previews-v2-message", act = { compose.waitUntil(10_000) { count("og-picture") == 1 } }) { ChannelScreen(general) }
    }

    /** og-no-image: site, title and description only; and a message sent with ✕ (just the link). */
    @Test fun messageWithoutPicture() {
        val p = PreviewsV2Fixture.build(session, DemoPreviews.NO_PICTURE)
        PreviewsV2Fixture.post(this, "How to start one: ${DemoPreviews.NO_PICTURE}", p.tags())
        PreviewsV2Fixture.post(this, "My notes, no box: https://notes.example/tool-library", listOf(listOf("preview", "none")))
        demoShot("previews-v2-no-picture", act = { compose.waitUntil(10_000) { count("og-card") == 1 } }) { ChannelScreen(general) }
    }

    /** og-thread: the same box under a reply. */
    @Test fun inAThread() {
        val root = myMessage("Ideas for the tool library?")
        val p = PreviewsV2Fixture.build(session, DemoPreviews.PICTURE)
        runBlocking { session.replyInThread(general, root.ev, "This is what we could become: ${DemoPreviews.PICTURE}", preview = p.tags()) }
        settle()
        demoShot("previews-v2-thread", act = { compose.waitUntil(10_000) { count("og-picture") == 1 } }) { ThreadScreen(general, root.id) }
    }

    /** og-dm: under the bubble of a direct message, its picture encrypted like the conversation's files. */
    @Test fun inAnEncryptedConversation() {
        val conv = "n:" + Demo.maya.pubkey
        val p = PreviewsV2Fixture.build(session, DemoPreviews.PICTURE, PreviewMode.ENCRYPTED)
        runBlocking { session.sendDm(conv, "Did you see this? ${DemoPreviews.PICTURE}", preview = p.tags()) }
        demoShot("previews-v2-dm", act = { compose.waitUntil(10_000) { count("og-picture") == 1 } }) { ConversationScreen(conv) }
    }

    /** og-laptop: on a wide screen the box keeps a readable width. */
    @Config(qualifiers = "w1280dp-h800dp-land-240dpi")
    @Test fun wide() {
        val p = PreviewsV2Fixture.build(session, DemoPreviews.PICTURE)
        PreviewsV2Fixture.post(this, "This is what we could become: ${DemoPreviews.PICTURE}", p.tags())
        demoShot("previews-v2-wide", act = { compose.waitUntil(10_000) { count("og-picture") == 1 } }) { ChannelScreen(general) }
    }
}
