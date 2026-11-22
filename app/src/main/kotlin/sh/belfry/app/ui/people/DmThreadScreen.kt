package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.push.AndroidPush
import sh.belfry.app.ui.chat.Composer
import sh.belfry.app.ui.chat.rememberComposerState
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconRow
import sh.belfry.app.ui.kit.RowItem
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmSnapshot

/** Who may start a thread on a direct message (docs/one-shape.md 3). */
internal object DmThreadRules {
    /** Not an e-mail, not a reply itself, out (or on its way), and the conversation can be written to here. */
    fun canReply(d: Dm, lockedHere: Boolean): Boolean =
        !d.isMail && d.root == null && d.heldSince == null && d.failed == null && !lockedHere

    /** The thread's title: the beginning of the message that started it (thread names come later). */
    fun title(root: Dm, dms: DmSnapshot): String {
        val text = root.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.replace("**", "")?.replace("__", "")
        return text ?: dms.fileLine(root) ?: "Thread"
    }

    fun repliesLabel(n: Int): String = if (n == 1) "1 reply" else "$n replies"
}

/** "💬 2 replies" under a message whose thread has replies; a tap opens the thread. */
@Composable
internal fun RepliesLink(n: Int, onOpen: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier.padding(top = 3.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "Open the thread", onClick = onOpen)
            .heightIn(min = 32.dp).padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("dm-replies"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BelfryIcons.Thread, contentDescription = null, tint = c.text, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        // e-ink: semi-bold and the icon say it is a link, not a colour
        Text(DmThreadRules.repliesLabel(n), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
    }
}

/**
 * A thread started from a direct message (canvas one-shape/dm-thread): two header lines (the
 * beginning of the message, then 👥 people · 💬 replies), the message on top, the replies below,
 * "Reply in the thread". A reply is a kind 14 with ["e", root, "", "root"], sent like any other.
 */
@Composable
fun DmThreadScreen(conv: String, root: String) {
    DisposableEffect(Unit) {
        AndroidPush.dmScreens.incrementAndGet()
        onDispose { AndroidPush.dmScreens.decrementAndGet() }
    }
    val c = Belfry.colors
    val world = LocalWorld.current
    val dms = LocalDms.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val first = dms.thread(conv).firstOrNull { it.rid == root }
    val replies = dms.replies(conv, root)
    val peers = dms.peers(conv)
    val people = (peers + world.me).distinct().size
    val sealState by session.encryption.state.collectAsState()
    val seal = sealState.seal(conv)
    val lockedHere = seal != null && conv in sealState.locked
    val composer = rememberComposerState("$conv#$root")
    val state = rememberLazyListState()
    var acting by remember(conv, root) { mutableStateOf<Pair<String, Boolean>?>(null) }
    val all = listOfNotNull(first) + replies

    LaunchedEffect(conv, replies.size) { if (all.isNotEmpty()) state.scrollToItem(all.size) }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        // two lines (Xavier, 22 Nov: "too crowded to have 3 lines in the header")
        Column(Modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton { nav.back() }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        if (first != null) DmThreadRules.title(first, dms) else "Thread",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() }.testTag("dm-thread-title"),
                    )
                    IconRow(
                        listOfNotNull(
                            RowItem("people", BelfryIcons.Users, "$people", "$people people"),
                            RowItem("comments", BelfryIcons.Thread, "${replies.size}", DmThreadRules.repliesLabel(replies.size)).takeIf { replies.isNotEmpty() },
                        ),
                    )
                }
            }
            Hairline()
        }
        if (first == null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState("This message is not on this device", text = "It may come back once this device has caught up.", icon = BelfryIcons.Thread)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("dm-thread"), state = state, contentPadding = PaddingValues(top = 8.dp, bottom = 10.dp + sh.belfry.app.ui.chat.TYPING_ROOM)) {
                item(key = "root") {
                    Bubble(first, dms, world, group = peers.size > 1 || !first.mine, onMenu = { emojiOnly -> acting = first.rid to emojiOnly })
                }
                item(key = "count") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (replies.isEmpty()) "No replies yet" else DmThreadRules.repliesLabel(replies.size),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted,
                        )
                        Hairline(Modifier.weight(1f))
                    }
                }
                items(replies, key = { it.rid }) { r ->
                    Bubble(r, dms, world, group = peers.size > 1 || !r.mine, onMenu = { emojiOnly -> acting = r.rid to emojiOnly })
                }
            }
        }
        if (first != null) {
            sh.belfry.app.ui.chat.TypingLine(sh.belfry.core.typing.TypingPlace.dm(conv)) { 0L }
        }
        acting?.let { (rid, emojiOnly) ->
            val d = all.firstOrNull { it.rid == rid }
            // inside a thread, Reply is the box below: the menu has Copy text and the reactions
            if (d == null) acting = null else DmMessageSheet(d, dms.reactionsOf(d.rid), emojiOnly, onClose = { acting = null })
        }
        Hairline()
        if (first == null) {
            Spacer(Modifier.navigationBarsPadding())
        } else if (lockedHere) {
            Text(
                sh.belfry.core.model.SealedText.WRITE_LATER, fontSize = 14.sp, color = c.muted,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp),
            )
        } else {
            Composer(
                composer,
                placeholder = "Reply in the thread",
                onSend = { text, _, _, _ -> session.sendDm(conv, text, root = root) },
                modifier = Modifier.navigationBarsPadding(),
                photos = false,
                typingPlace = sh.belfry.core.typing.TypingPlace.dm(conv),
            )
        }
    }
}
