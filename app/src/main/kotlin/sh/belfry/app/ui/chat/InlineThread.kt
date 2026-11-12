package sh.belfry.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Message

/**
 * A thread unfolded under its message (SPEC 8): the latest 3 replies, "Show N earlier replies",
 * a reply box, Hide and Open thread.
 */
@Composable
fun InlineThread(groupId: String, root: Message, chat: ChatState, focus: Boolean, onHide: () -> Unit, onOpen: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    var all by remember(root.id) { mutableStateOf(false) }
    val (shown, hidden) = world.inlineThread(groupId, root.id, all)
    val composer = rememberComposerState(root.id)
    val line = c.line
    Box(Modifier.fillMaxWidth().padding(start = 62.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)) {
        Column(
            Modifier.fillMaxWidth().drawBehind { drawLine(line, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx()) }.padding(start = 12.dp),
        ) {
            if (hidden > 0) {
                Text(
                    "Show $hidden earlier ${if (hidden == 1) "reply" else "replies"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                    modifier = Modifier.clickable(role = Role.Button) { all = true }.padding(vertical = 4.dp),
                )
            }
            shown.forEachIndexed { i, r ->
                val prev = shown.getOrNull(i - 1)
                val first = prev == null || prev.pubkey != r.pubkey || r.at - prev.at > 300
                MessageRow(groupId, r, first, chat, compact = true)
            }
            // guest mode: no reply box (the channel's bar says Join)
            if (session.guest.collectAsState().value?.reading != true) Composer(
                composer,
                placeholder = if (shown.isNotEmpty() || hidden > 0) "Reply in the thread" else "Reply (starts a thread)",
                onSend = { text, files, picked, _ -> session.replyInThread(groupId, root.ev, text, files, picked) },
                compact = true,
                autoFocus = focus,
                groupId = groupId,
                onInvite = { pk -> inviteSaid(session.inviteMember(groupId, pk)) },
            )
            Row {
                Text("Hide", fontSize = 13.sp, color = c.muted, modifier = Modifier.clickable(role = Role.Button, onClick = onHide).padding(vertical = 4.dp))
                Spacer(Modifier.width(16.dp))
                Text("Open thread", fontSize = 13.sp, color = c.muted, modifier = Modifier.clickable(role = Role.Button, onClick = onOpen).padding(vertical = 4.dp))
            }
        }
    }
}
