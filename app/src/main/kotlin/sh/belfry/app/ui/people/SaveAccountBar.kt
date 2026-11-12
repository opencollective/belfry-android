package sh.belfry.app.ui.people

import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.nav.LocalWorldOrNull
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.Session

/**
 * Shown above the composer to a guest who joined with a name only (an event's guest, an older
 * link), once they did something (a message, a reply, a reaction), or at once for a co-host link:
 * "Save your account". Save opens the same sheet as joining from guest mode ([JoinSheet]): the
 * e-mail, then the code sent to it. Never shown in guest mode (the composer's place is the
 * [GuestBar] there). Gone once saved, or for this visit after "Not now".
 */
@Composable
fun SaveAccountBar(session: Session) {
    val guest by session.guest.collectAsState()
    val world = LocalWorldOrNull.current ?: return
    var hidden by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    var savedAs by rememberSaveable { mutableStateOf("") }
    if (savedAs.isNotEmpty()) {
        SaveAccountLine(SAVED_LINE.format(savedAs), button = "OK", onButton = { savedAs = "" }, onHide = null)
        return
    }
    val g = guest ?: return
    // a co-host link opened by someone new: the bar shows at once, with its reason (events)
    val cohost = g.coHost != null
    if (g.saved || g.reading || (hidden && !cohost)) return
    val me = session.me
    val acted = world.all.any { it.pubkey == me && (it.kind == 9 || it.kind == 1111 || it.kind == 7) }
    if (!acted && !cohost) return
    SaveAccountLine(if (cohost) COHOST_LINE else SAVE_LINE, button = "Save", onButton = { open = true }, onHide = { hidden = true })
    if (open) JoinSheet(session, onClose = { open = false }, saveOnly = true, onSaved = { savedAs = it })
}

/**
 * The card on the Me screen of a guest whose account is not saved yet: Save opens the e-mail
 * sheet; in guest mode, Join opens the Join sheet (name and e-mail).
 */
@Composable
fun SaveAccountCard(session: Session) {
    val guest by session.guest.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    var savedAs by rememberSaveable { mutableStateOf("") }
    if (savedAs.isNotEmpty()) {
        SaveAccountLine(SAVED_LINE.format(savedAs), button = null, onButton = {}, onHide = null, bar = false)
        return
    }
    val g = guest ?: return
    if (g.saved) return
    if (g.reading) {
        SaveAccountLine(READING_LINE, button = "Join", onButton = { session.askToJoin() }, onHide = null, bar = false)
        return
    }
    SaveAccountLine(SAVE_LINE, button = "Save", onButton = { open = true }, onHide = null, bar = false)
    if (open) JoinSheet(session, onClose = { open = false }, saveOnly = true, onSaved = { savedAs = it })
}

private const val SAVE_LINE = "Save your account: add your e-mail to come back from any device."
private const val COHOST_LINE = "You were invited as a co-host: save your account with your e-mail to become one."
private const val READING_LINE = "You are reading as a guest. Join with your name and e-mail to write."
private const val SAVED_LINE = "Saved. Sign in with %s on any device."

/** The one line of the bar or the card: its words, one button, and (the bar) "Not now". */
@Composable
fun SaveAccountLine(line: String, button: String?, onButton: () -> Unit, onHide: (() -> Unit)?, bar: Boolean = true) {
    val c = Belfry.colors
    Column(Modifier.fillMaxWidth().background(c.surface).testTag("save-account")) {
        if (bar) Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.weight(1f))
            if (button != null) {
                Spacer(Modifier.width(10.dp))
                BusyButton(button, onClick = { onButton() }, small = true)
            }
            if (onHide != null) IconAction(BelfryIcons.Close, "Not now", onHide)
        }
    }
}
