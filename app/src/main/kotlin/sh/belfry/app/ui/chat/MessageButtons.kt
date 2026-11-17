package sh.belfry.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.kit.rememberNow
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.Buttons
import sh.belfry.core.model.Message
import sh.belfry.core.model.MessageButton

/**
 * The buttons under a message (SPEC 19.36): filled for primary, outlined for plain, error
 * colours for danger. A tap locks them all with a spinner on the one pressed until the handler
 * answers (or 20 s pass: "No answer yet."); the answer to my press shows under them; once a
 * trusted answer closes them they are replaced by its line for everyone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageButtons(groupId: String, m: Message) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val buttons = world.buttons(m)
    if (buttons.isEmpty()) return
    val state = world.buttonState(m)
    val waitingNow = state.myPress != null && state.mine == null && !state.closed
    val now by rememberNow(if (waitingNow) 1_000 else 30_000)
    val busy = rememberBusy()
    var tapped by remember(m.id) { mutableStateOf<String?>(null) }
    var sendError by remember(m.id) { mutableStateOf<String?>(null) }
    var asking by remember(m.id) { mutableStateOf<MessageButton?>(null) }

    Column(Modifier.padding(top = 6.dp, bottom = 2.dp).testTag("buttons-" + m.id)) {
        if (state.closed) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("buttons-closed")) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(state.closedText.orEmpty(), fontSize = 14.sp, color = c.muted)
            }
            return@Column
        }
        val waiting = state.waiting(now)
        val locked = busy.busy || waiting
        val spinning = if (busy.busy) tapped else if (waiting) state.pressedButton else null

        fun press(b: MessageButton) {
            sendError = null
            tapped = b.id
            busy.run(onError = { e -> if (!(e is ActionException && e.code == sh.belfry.core.model.GuestMode.REFUSED)) sendError = (e as? ActionException)?.message ?: Buttons.NOT_SENT }) {
                session.pressButton(groupId, m, b)
            }
        }
        fun tap(b: MessageButton) {
            if (session.reading) { session.askToJoin(); return }
            if (b.belfry || b.danger) asking = b else press(b)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in buttons) {
                val enabled = !locked && !b.unknown
                val content: @Composable () -> Unit = {
                    if (spinning == b.id) {
                        Spinner(size = 16.dp, color = if (b.primary) c.onAccent else c.muted)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(b.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                val mod = Modifier.heightIn(min = 40.dp).testTag("button-" + b.id)
                when {
                    b.primary -> Button(
                        onClick = { tap(b) }, enabled = enabled, modifier = mod,
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                    ) { content() }
                    b.danger -> OutlinedButton(
                        onClick = { tap(b) }, enabled = enabled, modifier = mod,
                        border = BorderStroke(1.dp, if (enabled) c.danger else c.line),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.danger),
                    ) { content() }
                    else -> OutlinedButton(
                        onClick = { tap(b) }, enabled = enabled, modifier = mod,
                        border = BorderStroke(1.dp, c.line),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                    ) { content() }
                }
            }
        }
        val mine = state.mine
        val failed = sendError
        val line: Pair<String, Boolean>? = when {
            failed != null -> failed to true
            busy.busy || waiting -> null
            mine != null && !mine.ok -> mine.text to true
            mine != null && mine.text.isNotEmpty() -> mine.text to false
            state.noAnswer(now) -> Buttons.TIMEOUT_TEXT to false
            buttons.any { it.unknown } -> Buttons.UPDATE_APP to false
            else -> null
        }
        if (line != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).testTag("buttons-line")) {
                if (line.second) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = c.danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(line.first, fontSize = 14.sp, color = if (line.second) c.danger else c.muted)
            }
        }

        asking?.let { b ->
            val question = if (b.belfry) Buttons.confirmText(b.action, world.ctx.domain) else "${b.label}?"
            AlertDialog(
                onDismissRequest = { asking = null },
                text = { Text(question ?: "${b.label}?", fontSize = 16.sp) },
                confirmButton = {
                    TextButton(onClick = { asking = null; press(b) }, modifier = Modifier.testTag("button-confirm")) {
                        Text(if (b.belfry) Buttons.confirmLabel(b.action) else b.label, color = if (b.danger) c.danger else c.text, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel", color = c.text) } },
                containerColor = c.bg,
            )
        }
    }
}
