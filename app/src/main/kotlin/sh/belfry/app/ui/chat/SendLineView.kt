package sh.belfry.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalStatus
import sh.belfry.core.net.Relay
import sh.belfry.core.outbox.SendLine
import sh.belfry.core.outbox.SendState

/**
 * The line under a message of mine that is not out yet (SPEC 19.50): "Sending…", after 5 s
 * "Still sending… · <step>", after 30 s "Not sent yet" with Retry. ID: a direct message's id, else
 * the outbox item's local id. Redraws only when the words change (no ticking clock).
 */
@Composable
fun rememberSendLine(id: String, tries: Int): SendLine {
    val session = LocalSession.current
    val sending by session.sending.collectAsState()
    val online = LocalStatus.current.online
    val p = sending[id]
    var now by remember { mutableLongStateOf(Relay.nowMs()) }
    LaunchedEffect(p) {
        while (true) {
            now = Relay.nowMs()
            val wait = SendState.nextChangeMs(p, now) ?: break
            delay(wait + 50)
        }
    }
    return SendState.line(failed = false, tries = tries, p = p, nowMs = now, online = online)
}
