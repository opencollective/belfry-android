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
import sh.belfry.core.model.needsJoinBar

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
        // the first open after a printed card (SPEC 19.72): its welcome, once
        sh.belfry.app.ui.printed.CardWelcomeBar(groupId)
        // a group I'm not in; a closed channel I'm not in and don't run (round 6, SPEC 19.41)
        if (g != null && world.needsJoinBar(g)) JoinBar(g)
        if (g != null && g.isEvent) EventChatBar(groupId)
    }
}

/** Not in this group: join it (open); a closed group or channel: ask to join, answering its question. */
@Composable
internal fun JoinBar(g: Group) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val applied = g.applications.any { it.pubkey == world.me }
    val kind = if (g.isPod) "group" else "channel"
    var asking by remember(g.id) { mutableStateOf(false) }
    BarRow(Modifier.testTag("join-bar")) {
        val text = when {
            !g.closed -> "You're not in this group yet."
            applied -> "Pending · The $kind’s stewards will answer."
            else -> "People ask to join this $kind."
        }
        Text(text, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 20.sp, color = Belfry.colors.text)
        if (!g.closed) {
            GapW(8)
            BusyButton("Join", onClick = { session.send(Actions.join(g.id)) }, small = true)
        } else if (!applied) {
            GapW(8)
            BusyButton("Ask to join", onClick = { asking = true }, small = true, modifier = Modifier.testTag("ask-to-join"))
        }
    }
    if (asking) ApplySheet(g) { asking = false }
}

/** A closed group or channel asks its question; the answer goes with the request to join (a 9021). */
@Composable
internal fun ApplySheet(g: Group, onDismiss: () -> Unit) {
    val session = LocalSession.current
    var answer by remember(g.id) { mutableStateOf("") }
    BelfrySheet(onDismiss = onDismiss, title = "Ask to join ${if (g.isPod) g.name else "#" + g.name}") {
        ApplyBody(g, answer, { answer = it }) {
            session.send(Actions.join(g.id, answer.trim()))
            onDismiss()
        }
    }
}

/** The ask sheet's body (tests render it on its own). */
@Composable
internal fun ApplyBody(g: Group, answer: String, onAnswer: (String) -> Unit, onSend: suspend () -> Unit) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(g.prompt?.takeIf { it.isNotBlank() } ?: Actions.DEFAULT_PROMPT, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = Belfry.colors.text)
        Gap(10)
        BelfryTextField(answer, onAnswer, Modifier.fillMaxWidth().testTag("apply-answer"), placeholder = "Your answer", singleLine = false, minLines = 4, maxLines = 8)
        Gap(12)
        BusyButton("Send my request", onClick = onSend, modifier = Modifier.fillMaxWidth().testTag("apply-send"), enabled = answer.isNotBlank())
        Gap(10)
        Muted("The ${if (g.isPod) "group" else "channel"}’s stewards see your answer and let you in, or not.")
    }
}
