package sh.belfry.app.ui.signin

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.core.engine.PendingCode
import sh.belfry.core.net.Community
import sh.belfry.core.net.MemberAddress
import sh.belfry.core.net.NativeSignIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.AppState
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.OnboardingException

/** Opens an address in the system browser's Custom Tab (never an embedded web view: SPEC 2). */
fun openInBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: Exception) {
            // No browser on this device.
        }
    }
}

/**
 * Signed out. Screen 1: the member's address (`name@domain`); the app finds the belfry at that
 * domain and asks it to mail a 6-digit code. Screen 2 ([CodeScreen]): the code. No browser. The
 * browser sign-in (the belfry's own page) stays as a second way, and is what an older belfry gets.
 */
@Composable
fun SignInScreen(state: AppState, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var offerBrowser by remember { mutableStateOf(false) }
    val message by state.message.collectAsState()
    val finishing by state.finishing.collectAsState()
    val pending by state.pendingCode.collectAsState()
    val waiting = pending
    var scanning by rememberSaveable { mutableStateOf(false) }
    // "Sign in instead" from guest mode: the address when it is one of this belfry, else a hint
    var hint by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { state.takePrefill()?.let { p -> if (p.address.isNotEmpty()) address = p.address; hint = p.hint } }
    if (scanning) {
        // a valid invitation goes where a link the app is opened with goes: the guest-join flow
        sh.belfry.app.scan.ScanScreen(onInvite = { scanning = false; state.onInviteLink(it) }, onClose = { scanning = false })
        return
    }
    // reopened while a code was on its way: the address comes back with it
    LaunchedEffect(waiting) { if (waiting != null && address.isBlank()) address = waiting.address }
    if (waiting != null) {
        CodeScreen(state, waiting)
        return
    }
    if (onBack != null) BackHandler(onBack = onBack)
    val browser: suspend () -> Unit = {
        error = null
        val typed = address.trim()
        val domain = MemberAddress.parse(typed)?.domain ?: Community.cleanDomain(typed).takeIf(Community::isDomain)
        if (domain == null) {
            error = "Type your address first, like name@belfry.sh."
        } else {
            try {
                openInBrowser(context, state.beginSignIn(state.discover(domain)))
            } catch (e: OnboardingException) {
                error = e.message
            }
        }
    }
    SignInContent(
        address = address,
        onAddress = { address = it.take(320); error = null; offerBrowser = false; state.clearMessage() },
        error = error ?: message,
        hint = hint,
        finishing = finishing,
        offerBrowser = offerBrowser,
        onContinue = {
            error = null
            try {
                state.requestCode(address)
            } catch (e: OnboardingException) {
                error = e.message
                offerBrowser = e.code == "browser"
            } catch (e: Exception) {
                error = "Not signed in. Try again."
            }
        },
        onBrowser = browser,
        onDemo = { state.demo() },
        onScan = { scanning = true },
        onBack = onBack,
    )
}

/** Screen 2 with its state: the digits typed, the 30 s lock of "Send a new code", what went wrong. */
@Composable
private fun CodeScreen(state: AppState, pending: PendingCode) {
    var digits by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var canRetry by remember { mutableStateOf(false) }
    var sends by remember { mutableIntStateOf(0) }
    var wait by remember { mutableIntStateOf(RESEND_SECONDS) }
    val busy = rememberBusy()
    LaunchedEffect(sends) {
        wait = RESEND_SECONDS
        while (wait > 0) { delay(1000); wait = (wait - 1).coerceAtLeast(0) }
    }
    BackHandler { state.cancelCode() }
    val submit: (String) -> Unit = { code ->
        busy.run(onError = { e ->
            val known = e as? OnboardingException
            error = known?.message ?: "Not signed in. Try again."
            // a wrong code: empty boxes; no connection or a slow signer: the same digits can go again
            canRetry = known != null && known.code != "wrong_code" && known.code != "signer_down"
            if (!canRetry) digits = ""
            // the belfry's signer is not answering: the code is spent, a new one can be asked at once
            if (known?.code == "signer_down") wait = 0
        }) {
            error = null; note = null
            state.verifyCode(code)
        }
    }
    CodeContent(
        to = pending.to,
        address = pending.address,
        digits = digits,
        onDigits = { raw ->
            val d = NativeSignIn.digits(raw)
            if (d != digits && !busy.busy) {
                digits = d; error = null; canRetry = false
                if (d.length == 6) submit(d)
            }
        },
        verifying = busy.busy,
        error = error,
        note = note,
        resendIn = wait,
        onRetry = if (canRetry && digits.length == 6) ({ submit(digits) }) else null,
        onResend = {
            error = null; note = null
            try {
                state.resendCode()
                digits = ""; canRetry = false; sends += 1
                note = "A new code is on its way."
            } catch (e: OnboardingException) {
                error = e.message
            } catch (e: Exception) {
                error = "The code could not be sent. Try again."
            }
        },
        onBack = { state.cancelCode() },
    )
}

private const val RESEND_SECONDS = 30

@Composable
fun Logo(sub: String = "Your community's own chat") {
    val c = Belfry.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(BelfryIcons.Shield, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Belfry", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(sub, fontSize = 13.sp, color = c.muted)
        }
    }
}

/** The page frame of the signed-out screens: scrolls, keeps a readable width on tablets. */
@Composable
fun SignedOutPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Belfry.colors.bg).systemBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp)) {
            content()
        }
    }
}

@Composable
fun SignInContent(
    address: String,
    onAddress: (String) -> Unit,
    error: String?,
    finishing: Boolean,
    offerBrowser: Boolean,
    onContinue: suspend () -> Unit,
    onBrowser: suspend () -> Unit,
    onDemo: suspend () -> Unit,
    onScan: (() -> Unit)? = null,
    /** Given while adding an account to a device that has one: a back arrow that returns to it. */
    onBack: (() -> Unit)? = null,
    /** A fixed line under the explanation (after "Sign in instead" in guest mode). */
    hint: String? = null,
) {
    val c = Belfry.colors
    val busy = rememberBusy()
    SignedOutPage {
        if (onBack != null) {
            Box(Modifier.padding(bottom = 12.dp).testTag("signin-back")) { sh.belfry.app.ui.kit.BackButton(onBack) }
        }
        Logo()
        Spacer(Modifier.height(40.dp))
        Text(if (onBack != null) "Add an account" else "Sign in to your community", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(
            if (onBack != null) "Another address in this community, or your address in another one. A 6-digit code goes to your e-mail; no password. The account you are in stays on this device."
            else "Type your address in your community. A 6-digit code goes to your e-mail; no password.",
            fontSize = 15.sp, color = c.muted,
        )
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(hint, fontSize = 15.sp, color = c.text, modifier = Modifier.testTag("signin-hint"))
        }
        Spacer(Modifier.height(24.dp))
        if (finishing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Spacer(Modifier.width(10.dp))
                Text("Signing you in…", fontSize = 15.sp, color = c.text)
            }
        } else {
            val main = if (offerBrowser) onBrowser else onContinue
            BelfryTextField(
                address, onAddress, Modifier.fillMaxWidth(),
                label = "Your address", placeholder = "name@belfry.sh", error = error,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) busy.run(block = main) }),
            )
            Spacer(Modifier.height(16.dp))
            BusyButton(
                if (offerBrowser) "Sign in with the browser" else "Continue",
                onClick = main, modifier = Modifier.fillMaxWidth(), enabled = address.isNotBlank(), busy = busy,
            )
            Spacer(Modifier.height(32.dp))
            ActionGroup {
                if (!offerBrowser) BusyRow("Sign in with the browser instead", onClick = onBrowser, icon = BelfryIcons.Globe, sub = "On your community’s own sign-in page")
                if (onScan != null) ActionRow("Scan an invitation", onClick = onScan, icon = BelfryIcons.Qr, sub = "The QR code of a guest link", modifier = Modifier.testTag("signin-scan"))
                BusyRow("Try the demo", onClick = onDemo, icon = BelfryIcons.Users, sub = "A made-up community. Everything in it stays on this device.")
            }
        }
    }
}

/**
 * Screen 2: six boxes for the code. [to] is the masked e-mail address when the belfry gave a
 * well-formed one (never other server text). The sixth digit submits ([onDigits] gets whatever
 * was typed or pasted). [resendIn] seconds until "Send a new code" unlocks.
 */
@Composable
fun CodeContent(
    to: String?,
    address: String,
    digits: String,
    onDigits: (String) -> Unit,
    verifying: Boolean,
    error: String?,
    note: String?,
    resendIn: Int,
    onRetry: (() -> Unit)?,
    onResend: suspend () -> Unit,
    onBack: () -> Unit,
) {
    val c = Belfry.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) { } }
    SignedOutPage {
        Logo()
        Spacer(Modifier.height(40.dp))
        Text("Enter the code", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text("We sent a 6-digit code to ${to ?: "your e-mail address"} to sign in as $address. It works for 10 minutes.", fontSize = 15.sp, color = c.muted)
        Spacer(Modifier.height(24.dp))
        CodeBoxes(digits, onDigits, enabled = !verifying, error = error != null, focus = focus)
        Spacer(Modifier.height(12.dp))
        when {
            verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Spacer(Modifier.width(10.dp))
                Text("Signing you in…", fontSize = 15.sp, color = c.text)
            }
            error != null -> Text(error, fontSize = 14.sp, lineHeight = 19.sp, color = c.danger)
            note != null -> Text(note, fontSize = 14.sp, lineHeight = 19.sp, color = c.muted)
        }
        if (onRetry != null && !verifying) {
            Spacer(Modifier.height(12.dp))
            BusyButton("Try again", onClick = { onRetry() }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(28.dp))
        ActionGroup {
            BusyRow(
                if (resendIn > 0) "Send a new code (in $resendIn s)" else "Send a new code",
                onClick = onResend, icon = BelfryIcons.Mail, enabled = resendIn <= 0 && !verifying,
            )
            ActionRow("Use another address", onClick = onBack, icon = BelfryIcons.Back, enabled = !verifying)
        }
    }
}

/**
 * Six digit boxes over one number field: typing moves on by itself, backspace goes back, a pasted
 * code fills them. 24 sp digits (a field under 16 sp makes some keyboards zoom).
 */
@Composable
fun CodeBoxes(digits: String, onDigits: (String) -> Unit, enabled: Boolean, error: Boolean, focus: FocusRequester, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Belfry.buttonRadius)
    BasicTextField(
        value = TextFieldValue(digits, selection = TextRange(digits.length)),
        onValueChange = { onDigits(sh.belfry.core.net.NativeSignIn.next(digits, it.text)) }, // a pasted code fills all six, also over a full row
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(fontSize = 24.sp, color = c.text),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().focusRequester(focus).testTag("code-boxes").semantics { contentDescription = "6-digit code" },
        decorationBox = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { i ->
                    val here = focused && enabled && i == digits.length.coerceAtMost(5)
                    val border = when {
                        error -> c.danger
                        here -> c.text
                        else -> c.line
                    }
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(shape).background(c.bg).border(if (here || error) 1.5.dp else 1.dp, border, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(digits.getOrNull(i)?.toString() ?: "", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) c.text else c.muted)
                    }
                }
            }
        },
    )
}

@Composable
fun Splash() {
    Box(Modifier.fillMaxSize().background(Belfry.colors.bg), contentAlignment = Alignment.Center) { Logo() }
}
