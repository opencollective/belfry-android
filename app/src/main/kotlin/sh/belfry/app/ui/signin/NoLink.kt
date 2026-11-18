package sh.belfry.app.ui.signin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import sh.belfry.app.AppState
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.OnboardingException
import sh.belfry.core.net.ApiException
import sh.belfry.core.net.ApplyForm
import sh.belfry.core.net.Community
import sh.belfry.core.net.InviteLink
import sh.belfry.core.net.MemberAddress
import sh.belfry.core.net.PublicAgreement

/** Where the signed-out flow is (round 6 no-link boards): find a belfry, its Welcome, log in, apply, sent. */
sealed interface NoLinkStep {
    data object Find : NoLinkStep
    data class Welcome(val c: Community) : NoLinkStep
    data class LogIn(val c: Community) : NoLinkStep
    data class Apply(val c: Community) : NoLinkStep
    data class Sent(val c: Community) : NoLinkStep
}

/**
 * Signed out with no link (boards onboarding/no-link-belfry, no-link, apply-form, apply-sent): the
 * app asks for a belfry's address (a `name@address` goes straight to the code), then shows that
 * belfry's Welcome: Log in · Apply to join · I have an invite code. Applying sends `POST /apply`
 * (no account; the answer comes by e-mail). A code on its way or a sign-in finishing shows the
 * sign-in screen as before.
 */
@Composable
fun SignedOutFlow(state: AppState) {
    val pending by state.pendingCode.collectAsState()
    val finishing by state.finishing.collectAsState()
    var step by remember { mutableStateOf<NoLinkStep>(NoLinkStep.Find) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    // "Sign in instead" from guest mode, a code on its way, a sign-in finishing: the sign-in screen as before
    val prefilled = remember { state.hasPrefill() }
    if (pending != null || finishing || prefilled) { SignInScreen(state); return }
    if (scanning) {
        sh.belfry.app.scan.ScanScreen(onInvite = { scanning = false; state.onInviteLink(it) }, onClose = { scanning = false })
        return
    }
    when (val s = step) {
        NoLinkStep.Find -> FindScreen(state, onCommunity = { step = NoLinkStep.Welcome(it) }, onScan = { scanning = true })
        is NoLinkStep.Welcome -> {
            BackHandler { step = NoLinkStep.Find }
            var inviting by remember { mutableStateOf(false) }
            WelcomeContent(s.c, onBack = { step = NoLinkStep.Find }, onLogIn = { step = NoLinkStep.LogIn(s.c) }, onApply = { step = NoLinkStep.Apply(s.c) }, onInviteCode = { inviting = true })
            if (inviting) InviteCodeSheet(s.c, onLink = { inviting = false; state.onInviteLink(it) }, onScan = { inviting = false; scanning = true }, onDismiss = { inviting = false })
        }
        is NoLinkStep.LogIn -> SignInScreen(state, community = s.c, onLeave = { step = NoLinkStep.Welcome(s.c) })
        is NoLinkStep.Apply -> {
            BackHandler { step = NoLinkStep.Welcome(s.c) }
            ApplyScreen(state, s.c, onBack = { step = NoLinkStep.Welcome(s.c) }, onLogIn = { step = NoLinkStep.LogIn(s.c) }, onSent = { step = NoLinkStep.Sent(s.c) })
        }
        is NoLinkStep.Sent -> {
            BackHandler { step = NoLinkStep.Welcome(s.c) }
            SentContent(onDone = { step = NoLinkStep.Welcome(s.c) })
        }
    }
}

@Composable
private fun FindScreen(state: AppState, onCommunity: (Community) -> Unit, onScan: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    FindContent(
        address = address,
        onAddress = { address = it.take(320); error = null },
        error = error,
        onContinue = {
            error = null
            val typed = address.trim()
            try {
                // a member's address goes straight to the code; a belfry's address to its Welcome
                if (MemberAddress.parse(typed) != null) state.requestCode(typed)
                else onCommunity(state.discover(typed))
            } catch (e: CancellationException) { throw e } catch (e: OnboardingException) {
                error = e.message
            } catch (e: Exception) {
                error = "No belfry answered at this address. Check the address and your connection."
            }
        },
        onScan = onScan,
        onDemo = { state.demo() },
    )
}

/** onboarding/no-link-belfry: the belfry's address (or name@address), Scan an invitation, Try the demo. */
@Composable
fun FindContent(address: String, onAddress: (String) -> Unit, error: String?, onContinue: suspend () -> Unit, onScan: () -> Unit, onDemo: suspend () -> Unit) {
    val c = Belfry.colors
    val busy = rememberBusy()
    SignedOutPage {
        BelfryMark()
        Spacer(Modifier.height(28.dp))
        Text("Find your community", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text("A belfry is your community’s own place. Type its address, or scan the QR code someone shows you.", fontSize = 16.sp, lineHeight = 22.sp, color = c.muted)
        Spacer(Modifier.height(24.dp))
        BelfryTextField(
            address, onAddress, Modifier.fillMaxWidth().testTag("find-address"),
            label = "Belfry address", placeholder = "belfry.sh", error = error,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) busy.run(block = onContinue) }),
        )
        Spacer(Modifier.height(16.dp))
        BusyButton("Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("find-continue"), enabled = address.isNotBlank(), busy = busy)
        Spacer(Modifier.height(28.dp))
        ActionGroup {
            ActionRow("Scan an invitation", onClick = onScan, icon = BelfryIcons.Qr, sub = "The QR code someone shows you", modifier = Modifier.testTag("signin-scan"))
            BusyRow("Try the demo", onClick = onDemo, icon = BelfryIcons.Users, sub = "A made-up community, kept on this device")
        }
        Spacer(Modifier.height(16.dp))
        Text("Got an invitation link? Open it on this phone.", fontSize = 14.sp, color = c.muted)
    }
}

/** onboarding/no-link: the belfry's Welcome. */
@Composable
fun WelcomeContent(c: Community, onBack: () -> Unit, onLogIn: () -> Unit, onApply: () -> Unit, onInviteCode: () -> Unit) {
    val col = Belfry.colors
    SignedOutPage {
        Box(Modifier.padding(bottom = 12.dp).testTag("welcome-back")) { BackButton(onBack) }
        Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val logo = c.brandLogo?.takeIf { it.startsWith("https://") }
            if (logo != null && LocalNetworkImages.current) {
                AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
            } else BelfryMark(64)
            Spacer(Modifier.height(20.dp))
            Text(c.name, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = col.text, textAlign = TextAlign.Center, modifier = Modifier.testTag("welcome-name"))
            Spacer(Modifier.height(8.dp))
            Text("Channels, events and files for its members. No password: a 6-digit code by e-mail.", fontSize = 16.sp, lineHeight = 22.sp, color = col.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            BusyButton("Log in", onClick = onLogIn, modifier = Modifier.fillMaxWidth().testTag("welcome-login"))
            Spacer(Modifier.height(10.dp))
            BusyButton("Apply to join", onClick = onApply, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth().testTag("welcome-apply"))
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onInviteCode, modifier = Modifier.testTag("welcome-code")) { Text("I have an invite code", fontSize = 16.sp, color = col.text, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** "I have an invite code": the invitation's link or its code, or scan it. Only what [InviteLink] takes goes on. */
@Composable
private fun InviteCodeSheet(c: Community, onLink: (InviteLink) -> Unit, onScan: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    BelfrySheet(onDismiss = onDismiss, title = "Your invitation") {
        BelfryTextField(
            text, { text = it.take(400); error = null }, Modifier.fillMaxWidth().testTag("invite-code"),
            label = "Invitation link or code", placeholder = "https://chat.${c.domain}/join/…", error = error,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(12.dp))
        BusyButton("Open the invitation", onClick = {
            val t = text.trim()
            val link = InviteLink.parse(t) ?: if (InviteLink.isToken(t)) InviteLink(c.domain, t) else null
            if (link == null) error = "That isn’t an invitation. Check the link or the code." else onLink(link)
        }, modifier = Modifier.fillMaxWidth(), enabled = text.isNotBlank())
        Spacer(Modifier.height(12.dp))
        ActionGroup { ActionRow("Scan its QR code", onClick = onScan, icon = BelfryIcons.Qr) }
    }
}

@Composable
private fun ApplyScreen(state: AppState, c: Community, onBack: () -> Unit, onLogIn: () -> Unit, onSent: () -> Unit) {
    var agreements by remember(c.domain) { mutableStateOf<List<PublicAgreement>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var email by rememberSaveable { mutableStateOf("") }
    var why by rememberSaveable { mutableStateOf("") }
    var ticked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        loadError = null
        try { agreements = state.publicCharter(c).sortedBy { it.order ?: Int.MAX_VALUE } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            loadError = (e as? ApiException)?.takeIf { it.code != "network" }?.message ?: ApplyForm.NETWORK
        }
    }
    LaunchedEffect(c.domain) { if (agreements == null) load() }
    ApplyContent(
        email, { email = it.take(200); error = null }, why, { why = it.take(4000); error = null },
        agreements, ticked, { id -> ticked = if (id in ticked) ticked - id else ticked + id }, error ?: loadError,
        onRetry = if (loadError != null) ({ load() }) else null,
        onSend = {
            error = null
            val list = agreements ?: return@ApplyContent
            if (email.isBlank() || why.isBlank()) { error = ApplyForm.MISSING; return@ApplyContent }
            try { state.applyToJoin(c, email, why, list.map { it.id }); onSent() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = (e as? ApiException)?.message ?: ApplyForm.OTHER
            }
        },
        onBack = onBack, onLogIn = onLogIn,
    )
}

/** onboarding/apply-form: e-mail, why, the charter in round ticks; the button counts the ticks until all are. */
@Composable
fun ApplyContent(
    email: String, onEmail: (String) -> Unit, why: String, onWhy: (String) -> Unit,
    agreements: List<PublicAgreement>?, ticked: List<String>, onTick: (String) -> Unit,
    error: String?, onRetry: (suspend () -> Unit)?, onSend: suspend () -> Unit, onBack: (() -> Unit)?, onLogIn: () -> Unit,
    /** Logged in already (the "Almost in" screen): its own heading and line, the e-mail the belfry knows locked, its own buttons under the form. */
    heading: String = "Apply to join",
    intro: String? = null,
    emailLocked: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    val c = Belfry.colors
    SignedOutPage {
        if (onBack != null) Box(Modifier.padding(bottom = 12.dp)) { BackButton(onBack) }
        Text(heading, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = c.text)
        if (intro != null) { Spacer(Modifier.height(8.dp)); Text(intro, fontSize = 16.sp, lineHeight = 22.sp, color = c.muted) }
        Spacer(Modifier.height(20.dp))
        BelfryTextField(email, onEmail, Modifier.fillMaxWidth().testTag("apply-email"), label = "Your e-mail", placeholder = "you@example.org", enabled = !emailLocked,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(14.dp))
        BelfryTextField(why, onWhy, Modifier.fillMaxWidth().testTag("apply-why"), label = "Why would you like to join?", singleLine = false, minLines = 3, maxLines = 8)
        Spacer(Modifier.height(18.dp))
        Text("Our charter: tick each agreement", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.muted)
        Spacer(Modifier.height(8.dp))
        when {
            agreements == null && onRetry == null -> Row { Spinner(); Spacer(Modifier.width(10.dp)); Text("Loading the charter…", fontSize = 15.sp, color = c.muted) }
            agreements != null && agreements.isEmpty() -> Text("This belfry has no charter yet.", fontSize = 15.sp, color = c.muted)
            agreements != null -> ActionGroup(Modifier.testTag("apply-charter")) {
                agreements.forEachIndexed { i, a ->
                    if (i > 0) Hairline()
                    val on = a.id in ticked
                    Row(
                        Modifier.fillMaxWidth().semantics { selected = on }.clickable(role = Role.Checkbox) { onTick(a.id) }.padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("apply-tick-$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // a round tick: filled with a check when ticked, an outline when not (e-ink: shape, not shade)
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).then(if (on) Modifier.background(c.text) else Modifier.border(2.dp, c.muted, CircleShape)),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(BelfryIcons.Check, contentDescription = "Ticked", tint = c.bg, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                            if (a.about.isNotEmpty()) Text(a.about, fontSize = 14.sp, color = c.muted)
                        }
                    }
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, fontSize = 15.sp, color = c.danger, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.danger.copy(alpha = 0.1f)).padding(12.dp).testTag("apply-error"))
        }
        if (onRetry != null) { Spacer(Modifier.height(10.dp)); BusyButton("Try again", onClick = onRetry, kind = ButtonKind.Ghost, small = true) }
        Spacer(Modifier.height(20.dp))
        val n = agreements?.size ?: 0
        val all = agreements != null && agreements.all { it.id in ticked }
        BusyButton(
            if (agreements != null && !all) "Tick each agreement (${agreements.count { it.id in ticked }} of $n)" else "Send my request",
            onClick = onSend, modifier = Modifier.fillMaxWidth().testTag("apply-send"),
            enabled = all && email.isNotBlank() && why.isNotBlank(),
        )
        Spacer(Modifier.height(12.dp))
        if (footer != null) footer() else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = onLogIn) { Text("I’m a member: log in", fontSize = 16.sp, color = c.text, fontWeight = FontWeight.SemiBold) } }
    }
}

/** onboarding/apply-sent. */
@Composable
fun SentContent(onDone: () -> Unit) {
    val c = Belfry.colors
    SignedOutPage {
        Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.text), contentAlignment = Alignment.Center) {
                Icon(BelfryIcons.Check, contentDescription = null, tint = c.bg, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Thank you", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.testTag("apply-sent"))
            Spacer(Modifier.height(10.dp))
            Text("Your request is in. You get a confirmation by e-mail now, and the answer by e-mail too.", fontSize = 16.sp, lineHeight = 22.sp, color = c.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            BusyButton("Done", onClick = onDone, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The Belfry shield, as the boards show it above a belfry's name. */
@Composable
private fun BelfryMark(size: Int = 52) {
    val c = Belfry.colors
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(c.accent), contentAlignment = Alignment.Center) {
        Icon(BelfryIcons.Shield, contentDescription = null, tint = c.onAccent, modifier = Modifier.size((size * 0.55f).dp))
    }
}
