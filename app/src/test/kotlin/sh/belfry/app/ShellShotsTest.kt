package sh.belfry.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ChatState
import sh.belfry.app.ui.chat.ComposerState
import sh.belfry.app.ui.chat.Photos
import sh.belfry.app.ui.chat.ThreadScreen
import sh.belfry.app.ui.shell.HomeScreen
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.app.ui.signin.InviteUnusable
import sh.belfry.app.ui.signin.OpeningScreen
import sh.belfry.app.ui.signin.NoAccessScreen
import sh.belfry.app.ui.signin.SignInContent
import sh.belfry.app.ui.signin.CodeContent
import sh.belfry.core.net.NativeSignIn
import sh.belfry.core.engine.Demo
import sh.belfry.core.model.FeedItem
import sh.belfry.core.model.Mentions
import sh.belfry.core.net.InviteInfo

/** The shell's screens in demo mode: sign-in, drawer, feed, threads, menus, composer. */
class ShellShotsTest : DemoShots() {
    private val general = Demo.GENERAL

    @Test fun signIn() = shot("signin") { SignInContent("", {}, null, false, false, {}, {}, {}, onScan = {}) }

    /** The scanner while another QR code is in view (the camera is a grey box here). */
    @Test fun scanOtherCode() = shot("scan-not-an-invitation") {
        sh.belfry.app.scan.ScanContent(sh.belfry.app.scan.ScanUi.SCANNING, sh.belfry.core.net.InviteScan.NOT_AN_INVITE, {}, {}, {}, {}) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF555555)))
        }
    }

    @Test fun scanDenied() = shot("scan-denied") { sh.belfry.app.scan.ScanContent(sh.belfry.app.scan.ScanUi.DENIED, null, {}, {}, {}, {}) {} }

    @Test fun scanNoCamera() = shot("scan-no-camera") { sh.belfry.app.scan.ScanContent(sh.belfry.app.scan.ScanUi.NO_CAMERA, null, {}, {}, {}, {}) {} }

    @Test fun signInError() = shot("signin-error") {
        SignInContent("maya@belfry.exmaple", {}, "There is no belfry at belfry.exmaple. Check the address and your connection.", false, false, {}, {}, {})
    }

    /** An older belfry (no /session API): the main button becomes the browser sign-in. */
    @Test fun signInOlderBelfry() = shot("signin-browser") {
        SignInContent("maya@example.org", {}, "This belfry can’t sign you in here yet. Sign in with the browser instead.", false, true, {}, {}, {})
    }

    @Test fun signInCode() = shot("signin-code") {
        CodeContent("m•••@example.org", "maya@belfry.sh", "", {}, false, null, null, 24, null, {}, {})
    }

    @Test fun signInCodeTyped() = shot("signin-code-typed") {
        CodeContent(null, "maya@belfry.sh", "4821", {}, false, null, "A new code is on its way.", 0, null, {}, {})
    }

    @Test fun signInCodeWrong() = shot("signin-code-wrong") {
        CodeContent("m•••@example.org", "maya@belfry.sh", "", {}, false, "That code is not right. 3 tries left.", null, 0, null, {}, {})
    }

    @Test fun signInCodeVerifying() = shot("signin-code-verifying") {
        CodeContent("m•••@example.org", "maya@belfry.sh", "482193", {}, true, null, null, 0, null, {}, {})
    }

    /** Typing or pasting six digits submits by itself; fewer do not. */
    @Test fun codeBoxesSubmitOnTheSixthDigit() {
        var digits by mutableStateOf("")
        val sent = ArrayList<String>()
        compose.setContent {
            BelfryTheme {
                CodeContent(null, "maya@belfry.sh", digits, { raw ->
                    val d = NativeSignIn.digits(raw)
                    digits = d
                    if (d.length == 6) sent.add(d)
                }, false, null, null, 0, null, {}, {})
            }
        }
        val field = compose.onNodeWithTag("code-boxes")
        field.performTextInput("12")
        compose.waitForIdle()
        assertEquals("12", digits)
        assertEquals(emptyList<String>(), sent)
        field.performTextReplacement("Your code: 123 456")
        compose.waitForIdle()
        assertEquals("123456", digits)
        assertEquals(listOf("123456"), sent)
    }

    /** A guest link being opened: the one loading screen (SPEC 19.26). */
    @Test fun join() = shot("join") { OpeningScreen("garden") }

    @Test fun joinClosed() = shot("join-closed") {
        InviteUnusable(InviteInfo(Demo.NAME, Demo.GARDEN, "garden", "", "Maya Okafor", "expired", 30).closedMessage!!, closed = true, onRetry = {}, onSignIn = {}, onClose = {})
    }

    @Test fun noAccess() = demoShot("no-access") { NoAccessScreen() }

    @Test fun drawer() {
        val shell = ShellState(general)
        demoShot("drawer") { HomeScreen(shell, drawerOpen = true) }
    }

    /** The end of #general: the latest messages, the queued and the refused one. */
    @Test fun feedEnd() = demoShot("feed") { ChannelScreen(general, onMenu = {}) }

    /** Further up, opened on a message (as a search result does). */
    @Test fun feedFocus() {
        val rows = world.feedRows(general)
        val target = rows[minOf(9, rows.size - 1)].item.key
        demoShot("feed-earlier") { ChannelScreen(general, onMenu = {}, focusMessage = target) }
    }

    @Test fun otherChannels() {
        demoShot("feed-garden") { ChannelScreen(Demo.GARDEN, onMenu = {}) }
        // the shared inbox: e-mail cards
    }

    @Test fun inbox() = demoShot("feed-inbox") { ChannelScreen(Demo.HELLO, onMenu = {}) }

    @Test fun inlineThread() {
        val root = world.threadsOf(general).maxByOrNull { it.value.count }!!.key
        val chat = ChatState(general).apply { openThreads[root] = false }
        demoShot("thread-inline") { ChannelScreen(general, onMenu = {}, focusMessage = root, chat = chat) }
    }

    @Test fun threadScreen() {
        val root = world.threadsOf(general).maxByOrNull { it.value.count }!!.key
        demoShot("thread") { ThreadScreen(general, root) }
    }

    @Test fun messageMenu() {
        val other = world.messages(general).last { it.pubkey != session.me && it.text.isNotEmpty() }
        val chat = ChatState(general).apply { menu(other) }
        demoScreenShot("message-menu") { ChannelScreen(general, onMenu = {}, chat = chat) }
    }

    @Test fun messageMenuMine() {
        val mine = myMessage("I can bring the big pot for Thursday.")
        val chat = ChatState(general).apply { menu(mine) }
        demoScreenShot("message-menu-mine") { ChannelScreen(general, onMenu = {}, chat = chat) }
    }

    @Test fun deleteConfirm() {
        val mine = myMessage("I can bring the big pot for Thursday.")
        val chat = ChatState(general).apply { deleting = mine }
        demoScreenShot("message-delete") { ChannelScreen(general, onMenu = {}, chat = chat) }
    }

    @Test fun mentionPicker() {
        val composer = ComposerState().apply { value = TextFieldValue("Thanks @ma", TextRange(10)) }
        demoShot("composer-mention") { ChannelScreen(general, onMenu = {}, composer = composer) }
    }

    @Test fun composerPhoto() {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(196, 210, 180))
            drawCircle(460f, 150f, 70f, Paint().apply { color = Color.rgb(244, 220, 130) })
            drawRect(0f, 330f, 640f, 480f, Paint().apply { color = Color.rgb(96, 140, 90) })
        }
        val composer = ComposerState().apply {
            photos.add(Photos.fromBitmap(bitmap, "garden.jpg"))
            value = TextFieldValue("The beans are up!", TextRange(17))
        }
        demoShot("composer-photo") { ChannelScreen(general, onMenu = {}, composer = composer) }
    }

    @Test fun editMode() {
        val mine = myMessage("I can bring the big pot for Thurdsay.")
        val chat = ChatState(general).apply { editing = mine }
        val (text, picks) = Mentions.unsubstitute(mine.text) { world.realName(it) }
        val composer = ComposerState().apply { set(text, picks) }
        demoShot("message-edit") { ChannelScreen(general, onMenu = {}, chat = chat, composer = composer) }
    }

    @Test fun plusSheet() {
        val chat = ChatState(general).apply { plusOpen = true }
        demoScreenShot("composer-plus") { ChannelScreen(general, onMenu = {}, chat = chat) }
    }

    @Test fun emptyChannel() {
        val id = runBlockingCreate()
        demoShot("feed-empty") { ChannelScreen(id, onMenu = {}) }
    }

    private fun runBlockingCreate(): String {
        val id = kotlinx.coroutines.runBlocking { session.createChannel("tool library", "Who has which tool.") }
        settle()
        return id
    }

    @Test fun seedHasWhatTheShotsNeed() {
        // Guards the demo seed: these screens are only worth a screenshot with this content.
        val rows = world.feedRows(general)
        check(rows.any { it.item is FeedItem.Join }) { "no guest join in #general" }
        check(world.threadsOf(general).isNotEmpty()) { "no thread in #general" }
        check(world.outboxItems.isNotEmpty()) { "no queued item" }
    }

    /** A recording in a message: play / pause, how far it is (nothing is loaded before it is played). */
    @Test
    fun audioRow() = demoShot("audio-row") {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
            sh.belfry.app.ui.chat.AudioRow(sh.belfry.core.model.Media("https://blossom.belfry.example/abc.m4a", "audio/mp4", "Garden meeting, part 1.m4a"))
        }
    }
}
