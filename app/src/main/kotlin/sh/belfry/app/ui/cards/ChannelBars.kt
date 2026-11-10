package sh.belfry.app.ui.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Group

/**
 * Bars under a channel's top bar: the answer by e-mail that is counting down (Undo), and, for a
 * pod I am not in, the join / apply bar. (The channel's events, pods and proposals show in the
 * summary line of the channel header, not here.)
 */
@Composable
fun ChannelBars(groupId: String) {
    val world = LocalWorld.current
    val g = world.group(groupId)
    Column {
        UndoSendHost(groupId)
        if (g != null && g.isPod && world.me !in g.members) JoinBar(g)
        if (g != null && g.isEvent) EventChatBar(groupId)
    }
}

/** Not in this pod: join it (open), or apply by answering its question (closed). */
@Composable
internal fun JoinBar(g: Group) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val applied = g.applications.any { it.pubkey == world.me }
    var asking by remember(g.id) { mutableStateOf(false) }
    BarRow(Modifier.testTag("join-bar")) {
        val text = when {
            !g.closed -> "You're not in this pod yet."
            applied -> "Request sent. The pod’s admins will answer."
            else -> "This pod is closed: apply to join."
        }
        Text(text, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp, color = Belfry.colors.text)
        if (!g.closed) {
            GapW(8)
            BusyButton("Join", onClick = { session.send(Actions.join(g.id)) }, small = true)
        } else if (!applied) {
            GapW(8)
            BusyButton("Apply", onClick = { asking = true }, small = true)
        }
    }
    if (asking) ApplySheet(g) { asking = false }
}

/** A closed pod asks its question; the answer goes with the request to join. */
@Composable
internal fun ApplySheet(g: Group, onDismiss: () -> Unit) {
    val session = LocalSession.current
    var answer by remember(g.id) { mutableStateOf("") }
    BelfrySheet(onDismiss = onDismiss, title = "Apply to ${g.name}") {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(g.prompt?.takeIf { it.isNotBlank() } ?: Actions.DEFAULT_PROMPT, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = Belfry.colors.text)
            Gap(10)
            BelfryTextField(answer, { answer = it }, Modifier.fillMaxWidth().testTag("apply-answer"), placeholder = "Your answer", singleLine = false, minLines = 4, maxLines = 8)
            Gap(12)
            BusyButton(
                "Send my application",
                onClick = {
                    session.send(Actions.join(g.id, answer.trim()))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().testTag("apply-send"),
                enabled = answer.isNotBlank(),
            )
            Gap(10)
            Muted("The pod’s admins see your answer and admit you, or not.")
        }
    }
}
