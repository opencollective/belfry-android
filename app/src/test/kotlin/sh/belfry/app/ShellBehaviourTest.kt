package sh.belfry.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import sh.belfry.app.ui.cards.CreateChannelScreen
import sh.belfry.app.ui.chat.ImageViewerContent
import sh.belfry.app.ui.chat.ViewerImage
import sh.belfry.core.model.ComposerDraft
import sh.belfry.core.model.FormDrafts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.chat.Composer
import sh.belfry.app.ui.chat.ComposerState
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.channelRef

/** The owner's rules, checked by driving the screens (demo mode). */
class ShellBehaviourTest : DemoShots() {
    private val general = Demo.GENERAL

    @Test
    fun sendCannotFireTwiceOnADoubleTap() {
        var calls = 0
        val state = ComposerState()
        compose.setContent {
            BelfryTheme {
                InDemo {
                    Composer(state, "Message", onSend = { _, _, _, _ -> calls++; delay(400) })
                }
            }
        }
        compose.onNodeWithTag("composer-field").performTextInput("Hello")
        val send = compose.onNodeWithTag("composer-send")
        send.performClick()
        send.performClick()
        send.performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { state.value.text.isEmpty() }
        assertEquals("the send button fired more than once", 1, calls)
    }

    @Test
    fun aChannelPickedAfterAHashIsSentAsItsReference() {
        val composer = ComposerState()
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(general, chat = ChatState(general), composer = composer) } } }
        compose.onNodeWithTag("composer-field").performTextInput("Prices in #kit")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("channel-picker").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("kitchen") and hasAnyAncestor(hasTestTag("channel-picker"))).performClick()
        compose.waitUntil(5_000) { composer.value.text == "Prices in #kitchen " }
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { composer.value.text.isEmpty() }
        compose.waitUntil(5_000) { world.messages(general).any { it.pubkey == session.me && it.text.startsWith("Prices in") } }
        val sent = world.messages(general).last { it.pubkey == session.me && it.text.startsWith("Prices in") }
        val ref = world.channelRef(Demo.KITCHEN)
        assertNotNull(ref)
        assertEquals("Prices in $ref", sent.text)
        // a reference is no topic
        assertTrue(sent.topics.isEmpty())
    }

    @Test
    fun editChangesTheMessageAndMarksItEdited() {
        started()
        val mine = myMessage("See you on Thurdsay")
        val chat = ChatState(general).apply { menu(mine) }
        val composer = ComposerState()
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(general, chat = chat, composer = composer) } } }
        compose.onNodeWithTag("menu-edit").performClick()
        compose.waitUntil(5_000) { chat.editing != null && chat.acting == null }
        assertEquals("the composer holds the message to edit", "See you on Thurdsay", composer.value.text)
        compose.onNodeWithTag("composer-field").performTextReplacement("See you on Thursday")
        compose.waitUntil(5_000) { composer.value.text == "See you on Thursday" }
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(10_000) { chat.editing == null && composer.value.text.isEmpty() }
        try {
            compose.waitUntil(10_000) { world.message(general, mine.id)?.text == "See you on Thursday" }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val w = world
            throw AssertionError(
                "the edit did not show: message=" + w.message(general, mine.id)?.text + " pending=" + w.isPending(mine.id) +
                    " outbox=" + w.outboxItems.map { it.kind.toString() + ":" + it.status + ":" + it.error } +
                    " edits=" + w.all.filter { it.kind == 1010 }.map { it.createdAt - NOW } + " msgAt=" + (mine.at - NOW),
            )
        }
        val edited = world.message(general, mine.id)
        assertNotNull(edited?.edited)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("edited").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("See you on Thursday").assertIsDisplayed()
    }

    /** Editing borrows the message box: the channel's own unsent text is still there afterwards. */
    @Test
    fun sendingAnEditKeepsTheChannelsDraft() {
        started()
        val mine = myMessage("See you on Thurdsay")
        val key = ComposerDraft.channel(general)
        session.saveDraft(key, "Half a thought")
        compose.waitUntil(5_000) { runBlocking { session.draft(key) } != null }
        val chat = ChatState(general).apply { menu(mine) }
        val composer = ComposerState()
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(general, chat = chat, composer = composer) } } }
        compose.onNodeWithTag("menu-edit").performClick()
        compose.waitUntil(5_000) { chat.editing != null && chat.acting == null }
        compose.onNodeWithTag("composer-field").performTextReplacement("See you on Thursday")
        compose.waitUntil(5_000) { composer.value.text == "See you on Thursday" }
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(10_000) { chat.editing == null }
        // the draft is back in the box, and still kept
        compose.waitUntil(10_000) { composer.value.text == "Half a thought" }
        settle()
        assertEquals("Half a thought", runBlocking { session.draft(key) }?.text)
        compose.waitUntil(10_000) { world.message(general, mine.id)?.text == "See you on Thursday" }
    }

    /** A new channel's fields come back when the form is opened again, and go once it is made. */
    @Test
    fun aFormKeepsItsFieldsUntilItIsSent() {
        started()
        var shown by mutableStateOf(true)
        compose.setContent { BelfryTheme { InDemo { if (shown) CreateChannelScreen() } } }
        compose.onNodeWithTag("create-name").performTextInput("Orchard")
        compose.waitUntil(5_000) { runBlocking { session.formDraft(FormDrafts.CHANNEL) }?.get("name") == "Orchard" }
        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Orchard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("create-submit").performClick()
        compose.waitUntil(10_000) { world.groups.any { it.name == "Orchard" } }
        compose.waitUntil(5_000) { runBlocking { session.formDraft(FormDrafts.CHANNEL) } == null }
    }

    @Test
    fun aPhotoZoomsOnADoubleTapAndClosesOnASwipeDown() {
        var closed = 0
        val picture = ImageBitmap(40, 30)
        compose.setContent { BelfryTheme { InDemo { ImageViewerContent(ViewerImage(null, picture, "A photo")) { closed++ } } } }
        val photo = compose.onNodeWithTag("viewer-photo")
        photo.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Whole"))
        photo.performTouchInput { doubleClick() }
        compose.waitUntil(5_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoomed in")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("zooming closes nothing", 0, closed)
        // zoomed: a drag moves the photo, it does not close it
        photo.performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(0, closed)
        photo.performTouchInput { doubleClick() }
        compose.waitUntil(5_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Whole")).fetchSemanticsNodes().isNotEmpty() }
        photo.performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { closed == 1 }
    }

    @Test
    fun deleteAsksFirstAndShowsTheMessage() {
        started()
        val mine = myMessage("Wrong channel, sorry")
        val chat = ChatState(general).apply { menu(mine) }
        compose.setContent { BelfryTheme { InDemo { ChannelScreen(general, chat = chat) } } }
        compose.onNodeWithTag("menu-delete").performClick()
        compose.waitForIdle()
        // Nothing is deleted yet: the app asks, showing the message.
        compose.onNodeWithText("Delete this message?").assertIsDisplayed()
        assertNotNull(world.message(general, mine.id))
        // (the sheet slides in: wait for its preview rather than assert at once)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Wrong channel, sorry").fetchSemanticsNodes().size >= 2 }
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10_000) { world.message(general, mine.id) == null }
    }
}
