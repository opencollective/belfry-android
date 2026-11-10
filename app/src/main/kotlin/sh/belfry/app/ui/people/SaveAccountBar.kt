package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.nav.LocalWorldOrNull
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.engine.Session

/**
 * Shown above the composer to a guest who joined with a name only, once they did something
 * (a message, a reply, a reaction): "Save your account" → e-mail → the 6-digit code.
 * Gone once the account is saved, or for this visit after "Not now".
 */
@Composable
fun SaveAccountBar(session: Session) {
    val guest by session.guest.collectAsState()
    val world = LocalWorldOrNull.current ?: return
    var hidden by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableStateOf("line") }
    var email by rememberSaveable { mutableStateOf("") }
    val g = guest ?: return
    if ((g.saved && step != "done") || hidden) return
    val me = session.me
    val acted = world.all.any { it.pubkey == me && (it.kind == 9 || it.kind == 1111 || it.kind == 7) }
    if (!acted) return
    SaveAccountFlow(
        step = step,
        email = email,
        onEmail = { email = it },
        sendCode = { session.guestSave(email.trim()); step = "code" },
        verify = { code -> session.guestVerify(email.trim(), code); step = "done" },
        onStart = { step = "email" },
        onHide = { hidden = true },
    )
}

/** The card on the Me screen for a guest whose account is not saved yet (always shown there). */
@Composable
fun SaveAccountCard(session: Session) {
    val guest by session.guest.collectAsState()
    var step by rememberSaveable { mutableStateOf("email") }
    var email by rememberSaveable { mutableStateOf("") }
    val g = guest ?: return
    if (g.saved && step != "done") return
    SaveAccountFlow(
        step = step,
        email = email,
        onEmail = { email = it },
        sendCode = { session.guestSave(email.trim()); step = "code" },
        verify = { code -> session.guestVerify(email.trim(), code); step = "done" },
        onStart = { step = "email" },
        onHide = null,
        bar = false,
    )
}

/** The steps, without a session: `line` → `email` → `code` → `done`. */
@Composable
fun SaveAccountFlow(
    step: String,
    email: String,
    onEmail: (String) -> Unit,
    sendCode: suspend () -> Unit,
    verify: suspend (code: String) -> Unit,
    onStart: () -> Unit,
    onHide: (() -> Unit)?,
    bar: Boolean = true,
) {
    val c = Belfry.colors
    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val onError: (Throwable) -> Unit = { error = (it as? ActionException)?.message ?: "That did not work. Try again." }
    Column(Modifier.fillMaxWidth().background(c.surface).testTag("save-account")) {
        if (bar) Hairline()
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            when (step) {
                "line" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Save your account: add your e-mail to come back from any device.", fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    BusyButton("Save", onClick = { onStart() }, small = true)
                    if (onHide != null) IconAction(BelfryIcons.Close, "Not now", onHide)
                }
                "email" -> {
                    if (!bar) Text("Save your account: add your e-mail to come back from any device.", fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.padding(bottom = 10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BelfryTextField(
                            email, { onEmail(it); error = null }, modifier = Modifier.weight(1f), placeholder = "you@example.org",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                        )
                        Spacer(Modifier.width(10.dp))
                        BusyButton("Send code", onClick = { error = null; sendCode() }, small = true, enabled = email.contains('@'), onError = onError)
                    }
                }
                "code" -> {
                    Text("We sent a 6-digit code to ${email.trim()}.", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BelfryTextField(
                            code, { v -> code = v.filter { it.isDigit() }.take(6); error = null }, modifier = Modifier.weight(1f), placeholder = "6-digit code",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        )
                        Spacer(Modifier.width(10.dp))
                        BusyButton("Confirm", onClick = { error = null; verify(code) }, small = true, enabled = code.length == 6, onError = onError)
                    }
                }
                else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved. Sign in with ${email.trim()} on any device.", fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.weight(1f))
                    if (onHide != null) {
                        Spacer(Modifier.width(10.dp))
                        BusyButton("OK", onClick = { onHide() }, small = true)
                    }
                }
            }
            ErrorLine(error)
        }
    }
}
