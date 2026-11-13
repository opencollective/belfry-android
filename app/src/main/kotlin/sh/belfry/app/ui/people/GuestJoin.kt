package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorldOrNull
import sh.belfry.app.ui.signin.CodeBoxes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.GuestJoinException
import sh.belfry.core.engine.Session
import sh.belfry.core.model.GuestMode
import sh.belfry.core.net.NativeSignIn

// Guest mode (SPEC 19.26): the bar that stands where the composer would be, and the Join sheet
// (name and e-mail, then the code that was sent by e-mail). The web's ui/GuestJoin.tsx.

/** "# general" (or the pod's / event's mark) of a channel, as the guest-mode texts name it. */
@Composable
private fun channelLabel(groupId: String): String {
    val g = LocalWorldOrNull.current?.group(groupId)
    return if (g == null) "# $groupId" else "${g.icon} ${g.name}"
}

/** Where the composer would be, in guest mode: you are reading; Join opens the sheet. */
@Composable
fun GuestBar(groupId: String, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val session = LocalSession.current
    val guest by session.guest.collectAsState()
    if (guest?.reading != true) return
    val label = channelLabel(groupId)
    Column(modifier.fillMaxWidth().background(c.surface).testTag("guest-bar")) {
        Hairline()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Guest mode") }
                    append(" · ")
                    append(GuestMode.barLine(label))
                },
                fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            BusyButton("Join", onClick = { session.askToJoin() }, small = true)
        }
    }
}

/**
 * Mounted once over the signed-in app: the Join sheet, opened by the bar or by anything that
 * writes in guest mode. [onSignIn]: the address has an account here ("Sign in instead").
 */
@Composable
fun GuestJoinHost(onSignIn: suspend (email: String, channel: String, moved: Boolean, nip05: String?) -> Unit) {
    val session = LocalSession.current
    val asked by session.joinAsked.collectAsState()
    val guest by session.guest.collectAsState()
    if (!asked || guest == null) return
    JoinSheet(session, onClose = { session.closeJoin() }, onSignIn = onSignIn)
}

private enum class Step { WHO, CODE, TAKEN }

private const val RESEND_SECONDS = 30

/**
 * Join (guest mode: name + e-mail) or save the account ([saveOnly]: a guest who has a name
 * already, the e-mail alone), then the code step, which says where the code went.
 */
@Composable
fun JoinSheet(
    session: Session,
    onClose: () -> Unit,
    onSignIn: (suspend (email: String, channel: String, moved: Boolean, nip05: String?) -> Unit)? = null,
    saveOnly: Boolean = false,
    onSaved: (email: String) -> Unit = {},
) {
    val guest by session.guest.collectAsState()
    val channel = guest?.channel ?: ""
    val label = channelLabel(channel)
    var step by rememberSaveable { mutableStateOf(Step.WHO) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var sentTo by rememberSaveable { mutableStateOf("") } // what was typed, as it was when the code went out
    var moved by rememberSaveable { mutableStateOf(false) }
    var nip05 by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = rememberBusy()
    val title = when (step) {
        Step.CODE -> "Check your e-mail"
        Step.TAKEN -> "You have an account here"
        Step.WHO -> if (saveOnly) "Save your account" else "Join $label"
    }
    BelfrySheet(onDismiss = { if (!busy.busy) onClose() }, title = title, modifier = Modifier.testTag("join-sheet")) {
        when (step) {
            Step.WHO -> WhoStep(
                saveOnly = saveOnly, name = name, onName = { name = it }, email = email, onEmail = { email = it },
                domain = session.community.domain,
                send = {
                    session.guestSave(email, if (saveOnly) null else name)
                    sentTo = email.trim()
                    step = Step.CODE
                },
                onTaken = { m, n -> sentTo = email.trim(); moved = m; nip05 = n; step = Step.TAKEN },
            )
            Step.CODE -> CodeStep(
                to = sentTo,
                resend = { session.guestSave(sentTo, if (saveOnly) null else name) },
                verify = { code ->
                    session.guestVerify(sentTo, code, if (saveOnly) null else name)
                    onSaved(sentTo)
                    onClose()
                },
                onTaken = { m, n -> moved = m; nip05 = n; step = Step.TAKEN },
                onAnother = { step = Step.WHO },
            )
            Step.TAKEN -> TakenStep(
                to = sentTo, domain = session.community.domain, label = label, moved = moved,
                signIn = { onSignIn?.invoke(sentTo, channel, moved, nip05) },
                onAnother = { step = Step.WHO },
                busy = busy,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WhoStep(
    saveOnly: Boolean,
    name: String, onName: (String) -> Unit,
    email: String, onEmail: (String) -> Unit,
    domain: String,
    send: suspend () -> Unit,
    onTaken: (moved: Boolean, nip05: String?) -> Unit,
) {
    val c = Belfry.colors
    var error by remember { mutableStateOf<String?>(null) }
    Text(
        if (saveOnly) "Add your e-mail address to come back from any device. We’ll send a 6-digit code to it."
        else "Your name and e-mail address, and you can write here. We’ll send a 6-digit code to your e-mail to confirm it.",
        fontSize = 15.sp, lineHeight = 21.sp, color = c.muted,
    )
    Spacer(Modifier.height(16.dp))
    if (!saveOnly) {
        BelfryTextField(
            name, { onName(it.take(100)); error = null }, Modifier.fillMaxWidth().testTag("join-name"), label = "Your name",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        Spacer(Modifier.height(12.dp))
    }
    BelfryTextField(
        email, { onEmail(it.take(254)); error = null }, Modifier.fillMaxWidth().testTag("join-email"), label = "E-mail", placeholder = "you@example.org",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
    )
    ErrorLine(error)
    Spacer(Modifier.height(16.dp))
    BusyButton(
        "Continue",
        onClick = { error = null; send() },
        modifier = Modifier.fillMaxWidth().testTag("join-continue"),
        enabled = (saveOnly || name.isNotBlank()) && GuestMode.isEmail(email),
        onError = { e ->
            val f = e as? GuestJoinException
            if (f?.code == "taken") onTaken(f.moved, f.nip05) else error = e.shown()
        },
    )
    if (!saveOnly) {
        Spacer(Modifier.height(12.dp))
        Hint("Already have an account on $domain? Use its e-mail address.")
    }
}

@Composable
private fun CodeStep(
    to: String,
    resend: suspend () -> Unit,
    verify: suspend (code: String) -> Unit,
    onTaken: (moved: Boolean, nip05: String?) -> Unit,
    onAnother: () -> Unit,
) {
    val c = Belfry.colors
    var digits by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var sends by remember { mutableIntStateOf(0) }
    var wait by remember { mutableIntStateOf(RESEND_SECONDS) }
    val checking = rememberBusy()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) { } }
    LaunchedEffect(sends) {
        wait = RESEND_SECONDS
        while (wait > 0) { delay(1000); wait = (wait - 1).coerceAtLeast(0) }
    }
    Text(
        buildAnnotatedString {
            append("We sent a 6-digit code ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("by e-mail") }
            append(" to ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(to) }
            append(". It can take a minute.")
        },
        fontSize = 15.sp, lineHeight = 21.sp, color = c.muted, modifier = Modifier.testTag("join-code-line"),
    )
    Spacer(Modifier.height(16.dp))
    CodeBoxes(
        digits,
        { raw ->
            val d = NativeSignIn.digits(raw)
            if (d != digits && !checking.busy) {
                digits = d; error = null; note = null
                if (d.length == 6) checking.run(onError = { e ->
                    val f = e as? GuestJoinException
                    // a wrong or expired code is cleared; a failed connection keeps the digits for Try again
                    if (f?.code == "wrong_code" || f?.code == "expired") digits = ""
                    if (f?.code == "taken") onTaken(f.moved, f.nip05) else error = e.shown()
                }) { verify(d) }
            }
        },
        enabled = !checking.busy, error = error != null, focus = focus,
    )
    Spacer(Modifier.height(12.dp))
    when {
        checking.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner()
            Spacer(Modifier.width(10.dp))
            Text("Checking the code…", fontSize = 14.sp, color = c.muted)
        }
        error != null -> ErrorLine(error, Modifier.padding(top = 0.dp))
        note != null -> Hint(note ?: "")
        else -> Hint("Not there? Look in your spam folder.")
    }
    if (!checking.busy && error != null && digits.length == 6) {
        Spacer(Modifier.height(12.dp))
        BusyButton("Try again", onClick = {
            error = null
            try { verify(digits) } catch (e: Exception) {
                val f = e as? GuestJoinException
                if (f?.code == "wrong_code" || f?.code == "expired") digits = ""
                if (f?.code == "taken") onTaken(f.moved, f.nip05) else error = e.shown()
            }
        }, modifier = Modifier.fillMaxWidth().testTag("join-code-retry"))
    }
    Spacer(Modifier.height(20.dp))
    ActionGroup {
        BusyRow(
            if (wait > 0) "Send a new code (in $wait s)" else "Send a new code",
            onClick = {
                error = null; note = null
                resend()
                digits = ""; sends += 1
                note = "A new code is on its way to your e-mail."
            },
            icon = BelfryIcons.Mail, enabled = wait <= 0 && !checking.busy,
            onError = { e -> error = e.shown() },
        )
        ActionRow("Use another address", onClick = onAnother, icon = BelfryIcons.Back, enabled = !checking.busy)
    }
}

@Composable
private fun TakenStep(
    to: String, domain: String, label: String, moved: Boolean,
    signIn: suspend () -> Unit,
    onAnother: () -> Unit,
    busy: sh.belfry.app.ui.kit.BusyState,
) {
    val c = Belfry.colors
    var error by remember { mutableStateOf<String?>(null) }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(to) }
            append(" already has an account on $domain. ")
            append(if (moved) "Sign in with it: $label is waiting there." else "Sign in with it instead.")
        },
        fontSize = 15.sp, lineHeight = 21.sp, color = c.muted, modifier = Modifier.testTag("join-taken-line"),
    )
    Spacer(Modifier.height(16.dp))
    BusyButton("Sign in instead", onClick = { error = null; signIn() }, modifier = Modifier.fillMaxWidth(), icon = BelfryIcons.User, busy = busy, onError = { error = "That did not work. Try again." })
    ErrorLine(error)
    if (!moved) {
        Spacer(Modifier.height(8.dp))
        BusyButton("Use another address", onClick = { onAnother() }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
    }
}
