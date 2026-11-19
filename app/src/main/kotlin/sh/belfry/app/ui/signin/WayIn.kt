package sh.belfry.app.ui.signin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.WayIn
import sh.belfry.core.net.Community
import sh.belfry.core.net.NativeSignIn
import sh.belfry.core.net.PublicAgreement
import sh.belfry.core.net.WayInApi

// The web's way in (canvas onboarding/login, login-unknown, apply-email, apply-code, apply-name,
// username-states, apply-pending-name, apply-declined; membership.md 2026-11-19; vectors/way-in.json).
// Log in and Apply both start by proving the e-mail with a code (login/start, login/verify); an
// address nobody has comes back "unknown" with a proof, and the application is sent with it (no
// second code). Every call is signed by this phone's applicant key for that belfry.

enum class WayInStart { LOGIN, APPLY, PENDING }

private sealed interface Step {
    data object Email : Step
    data class Code(val email: String, val forApply: Boolean = false) : Step
    data class Unknown(val email: String, val proof: String) : Step
    data class Form(val email: String, val proof: String?) : Step
    data class Pending(val info: Map<String, String>?) : Step
    data object Declined : Step
}

/**
 * [onSigned]: a session came (store it, open the chat). [onOlder]: this belfry lacks these calls
 * (the native sign-in / the older form). [onSignInInstead]: the account holds its own key, or the
 * approved session was handed out already: the native sign-in with that address.
 */
@Composable
fun WayInFlow(
    c: Community,
    start: WayInStart,
    api: WayInApi,
    charter: suspend () -> List<PublicAgreement>,
    onSigned: suspend (WayIn.Signed) -> Unit,
    onOlder: () -> Unit,
    onSignInInstead: (String?) -> Unit,
    onPending: (Boolean) -> Unit,
    onBack: () -> Unit,
    /** Off in screenshot tests: the pending screen asks /apply/status every 5 s while open. */
    poll: Boolean = true,
) {
    var step by remember(c.domain) { mutableStateOf<Step>(if (start == WayInStart.PENDING) Step.Pending(null) else Step.Email) }
    var error by remember { mutableStateOf<String?>(null) }
    fun go(s: Step) { error = null; step = s }

    suspend fun handle(o: WayIn.Outcome, email: String, forApply: Boolean) {
        when (o.screen) {
            WayIn.Screen.CODE -> go(Step.Code(email, forApply))
            WayIn.Screen.SIGNED_IN -> o.signed?.let { onSigned(it) } ?: run { error = WayIn.TRY_AGAIN }
            WayIn.Screen.PENDING -> { onPending(true); go(Step.Pending(o.pending)) }
            WayIn.Screen.UNKNOWN -> go(if (start == WayInStart.APPLY) Step.Form(email, o.proof) else Step.Unknown(email, o.proof!!))
            WayIn.Screen.DECLINED -> { onPending(false); go(Step.Declined) }
            WayIn.Screen.START -> { onPending(false); go(Step.Email) }
            WayIn.Screen.LOG_IN, WayIn.Screen.SIGN_IN_INSTEAD -> { onPending(false); onSignInInstead(o.nip05) }
            WayIn.Screen.WITHOUT_PROOF -> Unit // handled by the form
            WayIn.Screen.OLDER -> onOlder()
            WayIn.Screen.STAY -> error = o.message
        }
    }

    when (val s = step) {
        Step.Email -> {
            BackHandler(onBack = onBack)
            WayInEmail(
                heading = if (start == WayInStart.APPLY) "Your e-mail" else "Log in",
                intro = if (start == WayInStart.APPLY) "We send a 6-digit code to check it's yours. The stewards' answer comes here too." else "We send a 6-digit code to your e-mail. No password.",
                error = error, onBack = onBack,
                onSend = { email -> handle(api.loginStart(email), email.trim(), false) },
            )
        }
        is Step.Code -> {
            BackHandler { go(Step.Email) }
            WayInCode(
                to = s.email, error = error,
                onVerify = { code -> val o = if (s.forApply) api.applyVerify(s.email, code) else api.loginVerify(s.email, code); handle(o, s.email, s.forApply); o.clearsCode },
                onResend = { val o = api.loginStart(s.email); if (o.screen != WayIn.Screen.CODE) error = o.message },
                onOther = { go(Step.Email) },
            )
        }
        is Step.Unknown -> {
            BackHandler { go(Step.Email) }
            WayInUnknown(c, s.email, onApply = { go(Step.Form(s.email, s.proof)) }, onOther = { go(Step.Email) })
        }
        is Step.Form -> {
            BackHandler { go(Step.Email) }
            WayInForm(c, s.email, api, charter, error, onLogIn = onSignInInstead.let { f -> { f(null) } }) { name, username, why, agreed ->
                val o = api.applyStart(s.email, why, agreed, name, username, s.proof)
                if (o.screen == WayIn.Screen.WITHOUT_PROOF) {
                    handle(api.applyStart(s.email, why, agreed, name, username, null), s.email, true)
                    null
                } else { handle(o, s.email, true); o.suggestions }
            }
        }
        is Step.Pending -> {
            WayInPending(c, s.info)
            if (poll) {
                val owner = LocalLifecycleOwner.current
                // every 5 s while the screen is open and the app in front, and at once on coming back
                LaunchedEffect(owner) {
                    owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (true) {
                            val o = api.applyStatus()
                            if (o.screen == WayIn.Screen.PENDING) { if (o.pending != null && s.info == null) step = Step.Pending(o.pending) }
                            else if (o.screen != WayIn.Screen.STAY) handle(o, "", false)
                            delay(5_000)
                        }
                    }
                }
            }
        }
        Step.Declined -> WayInDeclined(c, onDone = onBack)
    }
}

@Composable
private fun Heading(text: String, intro: String?) {
    val c = Belfry.colors
    Text(text, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = c.text)
    if (intro != null) { Spacer(Modifier.height(8.dp)); Text(intro, fontSize = 16.sp, lineHeight = 22.sp, color = c.muted) }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun ErrorBox(error: String?) {
    val c = Belfry.colors
    if (error == null) return
    Spacer(Modifier.height(12.dp))
    Text(error, fontSize = 15.sp, color = c.danger, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.danger.copy(alpha = 0.1f)).padding(12.dp).testTag("wayin-error"))
}

@Composable
internal fun WayInEmail(heading: String, intro: String, error: String?, onBack: () -> Unit, onSend: suspend (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    SignedOutPage {
        Box(Modifier.padding(bottom = 12.dp)) { BackButton(onBack) }
        Heading(heading, intro)
        BelfryTextField(email, { email = it.take(320) }, Modifier.fillMaxWidth().testTag("wayin-email"), label = "Your e-mail", placeholder = "you@example.org",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email))
        ErrorBox(error)
        Spacer(Modifier.height(14.dp))
        BusyButton("Send me a code", onClick = { onSend(email) }, enabled = email.contains('@'), modifier = Modifier.fillMaxWidth().testTag("wayin-send"))
    }
}

@Composable
internal fun WayInCode(to: String, error: String?, onVerify: suspend (String) -> Boolean, onResend: suspend () -> Unit, onOther: () -> Unit) {
    val c = Belfry.colors
    var digits by remember { mutableStateOf("") }
    var wait by remember { mutableIntStateOf(30) }
    var sends by remember { mutableIntStateOf(0) }
    val busy = rememberBusy()
    val focus = remember { FocusRequester() }
    LaunchedEffect(sends) { wait = 30; while (wait > 0) { delay(1000); wait-- } }
    SignedOutPage {
        Heading("Check your e-mail", null)
        Text(androidx.compose.ui.text.buildAnnotatedString {
            append("We sent a 6-digit code to ")
            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(to) }
            append(". It can take a minute.")
        }, fontSize = 16.sp, lineHeight = 22.sp, color = c.muted)
        Spacer(Modifier.height(20.dp))
        CodeBoxes(digits, { raw ->
            val d = NativeSignIn.next(digits, raw)
            if (d != digits && !busy.busy) {
                digits = d
                if (d.length == 6) busy.run { if (onVerify(d)) digits = "" }
            }
        }, enabled = !busy.busy, error = error != null, focus = focus)
        ErrorBox(error)
        Spacer(Modifier.height(16.dp))
        ActionGroup {
            ActionRow(if (wait > 0) "Send a new code (in $wait s)" else "Send a new code", onClick = { if (wait == 0) busy.run { onResend(); digits = ""; sends++ } }, icon = BelfryIcons.Mail, enabled = wait == 0 && !busy.busy, modifier = Modifier.testTag("wayin-resend"))
            ActionRow("Use another address", onClick = onOther, icon = BelfryIcons.Back)
        }
    }
}

@Composable
internal fun WayInUnknown(c: Community, email: String, onApply: () -> Unit, onOther: () -> Unit) {
    val col = Belfry.colors
    SignedOutPage {
        Heading("No account here yet", null)
        Text(androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = col.text)) { append(email) }
            append(" is confirmed, but it isn't a member of ${c.name}.")
        }, fontSize = 16.sp, lineHeight = 22.sp, color = col.muted, modifier = Modifier.testTag("wayin-unknown"))
        Spacer(Modifier.height(22.dp))
        BusyButton("Apply to join", onClick = { onApply() }, modifier = Modifier.fillMaxWidth().testTag("wayin-apply"))
        Spacer(Modifier.height(8.dp))
        BusyButton("Use another address", onClick = { onOther() }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
    }
}

/** onboarding/apply-name: the name, "Pick a username:" (from the first name, checked as typed), why, the charter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WayInForm(
    c: Community, email: String, api: WayInApi?, charter: suspend () -> List<PublicAgreement>, error: String?, onLogIn: () -> Unit,
    /** Sends; returns the suggestions to offer after a taken username (null: nothing to offer). */
    onSend: suspend (name: String, username: String, why: String, agreed: List<String>) -> List<String>?,
) {
    val col = Belfry.colors
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf("") }
    var ticked by remember { mutableStateOf(listOf<String>()) }
    var agreements by remember { mutableStateOf<List<PublicAgreement>?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var offers by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(c.domain) { agreements = try { charter().sortedBy { it.order ?: Int.MAX_VALUE } } catch (e: Exception) { emptyList() } }
    // filled from the first name until changed by hand (Lina Moreau → lina)
    LaunchedEffect(name) { if (!edited) username = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.take(30) }
    LaunchedEffect(username) {
        offers = emptyList()
        if (username.isEmpty() || api == null) { line = null; return@LaunchedEffect }
        line = "Checking…"
        delay(400)
        val got = api.usernameCheck(username)
        line = got?.second
    }
    SignedOutPage {
        Heading("Apply to join ${c.name}", null)
        BelfryTextField(name, { name = it.take(100) }, Modifier.fillMaxWidth().testTag("wayin-name"), label = "Your name", placeholder = "Lina Moreau",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Spacer(Modifier.height(14.dp))
        UsernameField(username, { edited = true; username = it.lowercase().take(30) }, c.domain, line, offers) { pick -> edited = true; username = pick }
        Spacer(Modifier.height(14.dp))
        BelfryTextField(why, { why = it.take(4000) }, Modifier.fillMaxWidth().testTag("wayin-why"), label = "Why would you like to join?", singleLine = false, minLines = 3, maxLines = 8)
        Spacer(Modifier.height(18.dp))
        Text("Our charter: tick each agreement", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = col.muted)
        Spacer(Modifier.height(8.dp))
        val list = agreements
        if (list == null) Row { Spinner(); Spacer(Modifier.width(10.dp)); Text("Loading the charter…", fontSize = 15.sp, color = col.muted) }
        else if (list.isNotEmpty()) ActionGroup {
            list.forEachIndexed { i, a ->
                if (i > 0) Hairline()
                val on = a.id in ticked
                Row(
                    Modifier.fillMaxWidth().semantics { selected = on }.clickable(role = Role.Checkbox) { ticked = if (on) ticked - a.id else ticked + a.id }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp).clip(CircleShape).then(if (on) Modifier.background(col.text) else Modifier.border(2.dp, col.muted, CircleShape)), contentAlignment = Alignment.Center) {
                        if (on) Icon(BelfryIcons.Check, contentDescription = "Ticked", tint = col.bg, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { AgreementText(a.title, a.about) }
                }
            }
        }
        ErrorBox(error)
        Spacer(Modifier.height(20.dp))
        val all = list != null && list.all { it.id in ticked }
        BusyButton(
            if (list != null && !all) "Tick each agreement (${list.count { it.id in ticked }} of ${list.size})" else "Send my request",
            onClick = { onSend(name, username, why, ticked)?.let { if (it.isNotEmpty()) offers = it } },
            enabled = all && name.isNotBlank() && why.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("wayin-request"),
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = onLogIn) { Text("I'm already a member · Log in", fontSize = 16.sp, color = col.text, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** "Pick a username:" with @domain after it, the line under it (onboarding/username-states) and up to three one-tap suggestions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UsernameField(username: String, onChange: (String) -> Unit, domain: String, line: String?, offers: List<String>, onPick: (String) -> Unit) {
    val col = Belfry.colors
    BelfryTextField(
        username, onChange, Modifier.fillMaxWidth().testTag("wayin-username"), label = "Pick a username:", placeholder = "lina",
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        trailing = { Text("@$domain", fontSize = 15.sp, color = col.muted) },
        hint = line,
    )
    if (offers.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (o in offers.take(3)) Text(
                o, fontSize = 14.sp, color = col.text,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, col.line, RoundedCornerShape(99.dp)).clickable(role = Role.Button) { onPick(o) }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("wayin-offer"),
            )
        }
    }
    if (username.isNotEmpty() && WayIn.isUsername(username)) {
        Spacer(Modifier.height(6.dp))
        Text("This is your address here: $username@$domain. You log in with it, people mention you as @$username, and it receives your direct messages and e-mails.", fontSize = 13.sp, lineHeight = 18.sp, color = col.muted)
    }
}

/** onboarding/apply-pending-name: what they sent, and what happens next. */
@Composable
internal fun WayInPending(c: Community, info: Map<String, String>?) {
    val col = Belfry.colors
    SignedOutPage {
        Heading("Your request is pending", null)
        if (info != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(info["username"] ?: "", info["name"] ?: "", size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text((info["name"] ?: "") + (info["username"]?.let { "  @$it" } ?: ""), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = col.text)
                    info["email"]?.let { Text(it, fontSize = 14.sp, color = col.muted) }
                }
            }
            info["motivation"]?.let {
                Spacer(Modifier.height(16.dp))
                Text("Your message", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = col.muted)
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 15.sp, lineHeight = 21.sp, color = col.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(col.surface).padding(12.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            "Once approved, you're logged in to ${c.name} on this phone. To log in on another device, enter " + (info?.get("email") ?: "your e-mail") + " there to receive a new 6-digit code.",
            fontSize = 15.sp, lineHeight = 21.sp, color = col.muted, modifier = Modifier.testTag("wayin-pending"),
        )
    }
}

/** onboarding/apply-declined. */
@Composable
internal fun WayInDeclined(c: Community, onDone: () -> Unit) {
    val col = Belfry.colors
    SignedOutPage {
        Heading("Not this time", "The stewards of ${c.name} didn't accept your request. You may ask again later.")
        BusyButton("OK", onClick = { onDone() }, modifier = Modifier.fillMaxWidth().testTag("wayin-declined"))
    }
}

/**
 * canvas onboarding/member-link-name: after a member link's code, once: the name and the username
 * (POST /welcome, signed by the new member's own key), then the chat. Nothing to pick (set already,
 * own key) also goes to the chat.
 */
@Composable
fun MemberNameScreen(
    communityName: String, domain: String, email: String?,
    check: suspend (String) -> Pair<String, List<String>>?,
    welcome: suspend (name: String, username: String) -> WayIn.Outcome,
    onDone: () -> Unit,
) {
    val col = Belfry.colors
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf<String?>(null) }
    var offers by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name) { if (!edited) username = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.take(30) }
    LaunchedEffect(username) {
        offers = emptyList()
        if (username.isEmpty()) { line = null; return@LaunchedEffect }
        line = "Checking…"
        delay(400)
        val got = check(username)
        line = got?.first
        offers = got?.second ?: emptyList()
    }
    SignedOutPage {
        Heading("Welcome to $communityName", (email?.let { "$it is confirmed. " } ?: "") + "One last thing: how people will know you here.")
        BelfryTextField(name, { name = it.take(100) }, Modifier.fillMaxWidth().testTag("welcome-name-field"), label = "Your name", placeholder = "Lina Moreau",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Spacer(Modifier.height(14.dp))
        UsernameField(username, { edited = true; username = it.lowercase().take(30) }, domain, line, offers) { pick -> edited = true; username = pick }
        ErrorBox(error)
        Spacer(Modifier.height(20.dp))
        BusyButton("Go to the chat", onClick = {
            error = null
            val o = welcome(name, username)
            if (o.screen == WayIn.Screen.SIGNED_IN) onDone() else { error = o.message; if (o.suggestions.isNotEmpty()) offers = o.suggestions }
        }, enabled = name.isNotBlank() && WayIn.isUsername(username), modifier = Modifier.fillMaxWidth().testTag("welcome-go"))
        Text("", color = col.muted)
    }
}
