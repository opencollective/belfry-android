package sh.belfry.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import sh.belfry.app.scan.CameraPreview
import sh.belfry.app.scan.ScanContent
import sh.belfry.app.scan.ScanUi
import sh.belfry.app.scan.cameraGranted
import sh.belfry.app.scan.hasCamera
import sh.belfry.app.scan.openAppSettings
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.info.ErrorLine
import sh.belfry.app.ui.info.Hint
import sh.belfry.app.ui.info.InfoPage
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.SwitchRow
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.World
import sh.belfry.core.net.RemoteApp
import sh.belfry.core.net.RemoteAppCode
import sh.belfry.core.net.RemoteAppPreview

/*
 * Sign in to another app (SPEC 19.47; canvas v71 home/signer-*-android, note n-signer, DRAFT for
 * Xavier): Accounts → Sign in to another app → scanner (or "Paste a link instead", from the
 * clipboard) → confirm → done → Connected apps. The belfry's server answers the app as its remote
 * signer; this phone holds no key for it. Never shown: a key, an npub, a relay address, the code.
 * The app's name and address are its own claim ("The app says it is …", "Name not verified by
 * Belfry"); its picture is never loaded: its first letter stands in.
 */

private const val TITLE = "Sign in to another app"

/** The fixed lines shown in place of the confirm screen (home/signer-errors-android), each with one way on. */
internal enum class SignerProblem(val title: String, val text: String, val action: String) {
    EXPIRED("This code has expired or was already used", "Ask the app for a new code.", "Scan again"),
    NOT_A_CODE("This isn’t a sign-in code", "Look for “remote signer” or “bunker” on the app’s sign-in screen.", "Scan again"),
    ANOTHER_ACCOUNT("This app is already connected to another account", "Disconnect it from that account first, then scan again.", "Scan again"),
    GUEST("Guests can’t do this yet", "Join as a member first.", "How to join");

    companion object {
        /** Which line a refusal of the belfry gets, or null: shown as a line on the screen instead. */
        fun of(e: Throwable): SignerProblem? {
            val code = (e as? ActionException)?.code ?: return null
            return when (code) {
                "guest" -> GUEST
                "key_taken" -> ANOTHER_ACCOUNT
                "invalid_uri" -> if (e.message == sh.belfry.core.net.RemoteAppsApi.INVALID["bad_scheme"]) NOT_A_CODE else EXPIRED
                else -> null
            }
        }
    }
}

private enum class Step { SCAN, LOOKING, CONFIRM, PROBLEM, DONE }

/** The app as it calls itself: "gitworkshop", else "An app at <address>", else "An app". */
internal fun RemoteAppPreview.called(): String = name ?: host?.let { "An app at $it" } ?: "An app"
private fun RemoteAppPreview.short(): String = name ?: host ?: "the app"

@Composable
fun SignInToAppScreen() {
    val nav = LocalNav.current
    val session = LocalSession.current
    val world = LocalWorld.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // the code holds a secret: kept in memory only while this screen is open (never saved, never shown)
    var code by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(Step.SCAN) }
    var preview by remember { mutableStateOf<RemoteAppPreview?>(null) }
    var problem by remember { mutableStateOf<SignerProblem?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var badAt by remember { mutableLongStateOf(0L) }
    var bad by remember { mutableStateOf(false) }
    LaunchedEffect(badAt) { if (badAt != 0L) { bad = true; delay(3000); bad = false } }

    fun show(p: SignerProblem) { code = null; problem = p; step = Step.PROBLEM }
    fun take(text: String): Boolean {
        val c = RemoteAppCode.accept(text) ?: return false
        if (world.amGuest) { show(SignerProblem.GUEST); return true }
        code = c; line = null; step = Step.LOOKING
        return true
    }
    fun restart() { code = null; preview = null; problem = null; line = null; step = Step.SCAN }

    LaunchedEffect(step) {
        if (step != Step.LOOKING) return@LaunchedEffect
        val c = code ?: run { step = Step.SCAN; return@LaunchedEffect }
        try {
            preview = session.remoteApps.preview(c)
            step = Step.CONFIRM
        } catch (e: Exception) {
            val p = SignerProblem.of(e)
            if (p != null) show(p) else { code = null; line = e.message ?: "The code could not be read. Try again."; step = Step.SCAN }
        }
    }

    val pasteButton: @Composable () -> Unit = {
        BusyButton("Paste a link instead", onClick = {
            val text = clipboard.getText()?.text.orEmpty()
            if (!take(text)) show(SignerProblem.NOT_A_CODE)
        }, kind = ButtonKind.Ghost, modifier = Modifier.testTag("remote-app-paste"))
    }

    when (step) {
        Step.SCAN -> {
            var ui by remember {
                mutableStateOf(if (!hasCamera(context)) ScanUi.NO_CAMERA else if (cameraGranted(context)) ScanUi.SCANNING else ScanUi.ASKING)
            }
            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
                ui = if (granted) ScanUi.SCANNING else ScanUi.DENIED
            }
            LaunchedEffect(Unit) { if (ui == ScanUi.ASKING) launcher.launch(android.Manifest.permission.CAMERA) }
            BackHandler { nav.back() }
            ScanContent(
                ui = ui,
                message = if (bad) SignerProblem.NOT_A_CODE.title else line,
                onClose = { nav.back() },
                onAllow = { if (cameraGranted(context)) ui = ScanUi.SCANNING else launcher.launch(android.Manifest.permission.CAMERA) },
                onSettings = { openAppSettings(context) },
                onRetry = { ui = if (cameraGranted(context)) ScanUi.SCANNING else ScanUi.ASKING },
                title = TITLE,
                hint = "Scan the code the app shows. On the other app, choose to sign in with a remote signer or “bunker”.",
                footer = pasteButton,
            ) {
                CameraPreview(
                    onText = { text -> if (step == Step.SCAN && !take(text)) badAt = System.nanoTime() },
                    onFailed = { ui = ScanUi.FAILED },
                )
            }
        }
        Step.LOOKING -> InfoPage(TITLE) {
            Hint("Asking the belfry about this app…", Modifier.padding(top = 16.dp).testTag("remote-app-looking"))
        }
        Step.CONFIRM -> {
            val p = preview ?: return
            BackHandler { restart() }
            ConfirmStep(p, line, onCancel = { restart() }) { decrypt ->
                val c = code ?: return@ConfirmStep
                // done when the belfry has answered the app; no answer in 15 s: a line, scan again
                val call = scope.async { runCatching { session.remoteApps.connect(c, decrypt) } }
                val r = withTimeoutOrNull(15_000) { call.await() }
                when {
                    r == null -> line = "The app didn’t answer. Check it is still open and scan again."
                    r.isSuccess -> { code = null; line = null; step = Step.DONE }
                    else -> {
                        val e = r.exceptionOrNull()!!
                        val pr = SignerProblem.of(e)
                        if (pr != null) show(pr) else line = e.message ?: "That did not work. Show a new code and try again."
                    }
                }
            }
        }
        Step.PROBLEM -> {
            val p = problem ?: return
            BackHandler { restart() }
            ProblemStep(p) {
                if (p == SignerProblem.GUEST) { session.askToJoin(); nav.back(); nav.go(Routes.APPLY) } else restart()
            }
        }
        Step.DONE -> {
            val name = preview?.short() ?: "the app"
            DoneStep(name, world.realName(world.me).ifEmpty { world.myAddress ?: "you" }, onDone = { nav.back() }, onApps = { nav.back(); nav.go(Routes.REMOTE_APPS) })
        }
    }
}

/** The app's first letter on a tinted square: its picture is its own claim and never loaded. */
@Composable
private fun AppMark(label: String, size: Int = 56, onSurface: Boolean = false) {
    val c = Belfry.colors
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(if (onSurface) c.bg else c.surface2).border(1.dp, c.line, RoundedCornerShape((size / 4).dp)), contentAlignment = Alignment.Center) {
        Text(label.trim().take(1).lowercase().ifEmpty { "?" }, fontSize = (size * 0.45f).sp, fontWeight = FontWeight.SemiBold, color = c.text)
    }
}

/** What the app asks, as the belfry read it from its code; Sign in (home/signer-confirm-android). */
@Composable
internal fun ConfirmStep(p: RemoteAppPreview, error: String?, onCancel: () -> Unit, onConnect: suspend (decrypt: Boolean) -> Unit) {
    val world = LocalWorld.current
    val c = Belfry.colors
    var decrypt by remember { mutableStateOf(false) }
    val busy = sh.belfry.app.ui.kit.rememberBusy()
    InfoPage(TITLE) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppMark(p.name ?: p.host ?: "")
            Spacer(Modifier.height(12.dp))
            Text(if (p.known) "Already connected. Sign in again?" else "Sign in to ${p.short()}?", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.testTag("remote-app-title"))
            Spacer(Modifier.height(4.dp))
            val claim = when {
                p.name != null && p.host != null -> "The app says it is “${p.name}”, at ${p.host}"
                p.name != null -> "The app says it is “${p.name}”"
                else -> p.called()
            }
            Text(claim, fontSize = 14.sp, lineHeight = 19.sp, color = c.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Name not verified by Belfry", fontSize = 13.sp, color = c.text,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 3.dp).testTag("remote-app-unverified"),
            )
            if (p.known) {
                Spacer(Modifier.height(6.dp))
                Text("${p.short().replaceFirstChar { it.uppercase() }} is already connected to your account; signing in again replaces it.", fontSize = 14.sp, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.testTag("remote-app-known"))
            }
        }
        SectionTitle("As")
        val f = world.face(world.me)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(f, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(f.name.ifEmpty { "You" }, fontSize = 16.sp, color = c.text)
                world.myAddress?.let { Text(it, fontSize = 13.sp, color = c.muted) }
            }
        }
        SectionTitle("It will be able to")
        ActionGroup {
            ActionRow("Post and sign as you", null, icon = BelfryIcons.Edit, sub = "Messages, comments and changes it makes carry your name")
            if (p.asksDecrypt) SwitchRow(
                "Read your private messages", decrypt, { if (!busy.busy) decrypt = it }, icon = BelfryIcons.Lock,
                sub = "The app asks for this. Leave it off to refuse.", modifier = Modifier.testTag("remote-app-decrypt"),
            )
        }
        if (error != null) ErrorLine(error, Modifier.padding(top = 10.dp).testTag("remote-app-error"))
        Spacer(Modifier.height(18.dp))
        BusyButton(if (busy.busy) "Signing in…" else "Sign in", onClick = { onConnect(p.asksDecrypt && decrypt) }, modifier = Modifier.fillMaxWidth().testTag("remote-app-connect"), busy = busy)
        Spacer(Modifier.height(8.dp))
        BusyButton("Cancel", onClick = { onCancel() }, kind = ButtonKind.Ghost, enabled = !busy.busy, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun ProblemStep(p: SignerProblem, onAction: suspend () -> Unit) {
    val c = Belfry.colors
    InfoPage(TITLE) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp).testTag("remote-app-problem-${p.name}"), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(BelfryIcons.Close, contentDescription = null, tint = c.danger, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(p.title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(p.text, fontSize = 15.sp, lineHeight = 21.sp, color = c.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            BusyButton(p.action, onClick = onAction, modifier = Modifier.fillMaxWidth().testTag("remote-app-problem-action"))
        }
    }
}

@Composable
internal fun DoneStep(app: String, me: String, onDone: () -> Unit, onApps: () -> Unit) {
    val c = Belfry.colors
    InfoPage(TITLE) {
        Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(BelfryIcons.Check, contentDescription = null, tint = c.text, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("You’re signed in to $app", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.testTag("remote-app-done"))
            Spacer(Modifier.height(8.dp))
            Text("Go back to it: it is signed in as $me. Belfry signs for it until you disconnect it.", fontSize = 15.sp, lineHeight = 21.sp, color = c.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            BusyButton("Done", onClick = { onDone() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            BusyButton("Connected apps", onClick = { onApps() }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth().testTag("remote-app-see"))
        }
    }
}

/** "5 min ago", "3 hours ago", "3 weeks ago", else the day. */
internal fun ago(world: World, at: Long, now: Long): String {
    val s = (now - at).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> (s / 3600).let { "$it hour${if (it == 1L) "" else "s"} ago" }
        s < 7 * 86400 -> (s / 86400).let { "$it day${if (it == 1L) "" else "s"} ago" }
        s < 35 * 86400 -> (s / (7 * 86400)).let { "$it week${if (it == 1L) "" else "s"} ago" }
        else -> ChatFormat.day(world, at, now)
    }
}

/** The apps this account is signed in to through the belfry (home/signer-apps-android); each can be disconnected. */
@Composable
fun ConnectedAppsScreen() {
    val session = LocalSession.current
    val world = LocalWorld.current
    val apps by session.remoteApps.apps.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<RemoteApp?>(null) }
    LaunchedEffect(Unit) {
        try { session.remoteApps.refresh(); error = null } catch (e: Exception) { error = e.message ?: "Your connected apps could not be loaded. Try again." }
    }
    val now = session.now()
    val c = Belfry.colors
    InfoPage("Connected apps") {
        if (error != null) ErrorLine(error!!, Modifier.padding(top = 10.dp))
        val list = apps
        when {
            list == null && error == null -> Hint("Loading…", Modifier.padding(top = 14.dp))
            list.isNullOrEmpty() -> Hint("No app is signed in with your account.", Modifier.padding(top = 14.dp).testTag("remote-apps-none"))
            else -> {
                SectionTitle("Apps signed in as " + world.realName(world.me).ifEmpty { "you" })
                ActionGroup {
                    for (a in list) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).testTag("remote-app-row"), verticalAlignment = Alignment.CenterVertically) {
                            AppMark(a.label, size = 40, onSurface = true)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.label, fontSize = 16.sp, color = c.text)
                                val parts = listOfNotNull(
                                    a.host?.takeIf { it != a.label },
                                    a.created?.let { "connected " + ChatFormat.day(world, it, now).replaceFirstChar { ch -> ch.lowercase() } },
                                    a.used?.let { "last used " + ago(world, it, now) } ?: "not used yet",
                                    if (a.decrypt) "can read your private messages" else null,
                                )
                                Text(parts.joinToString(" · "), fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
                            }
                            Spacer(Modifier.width(8.dp))
                            // a red text button (the board's); it only asks first, the dialog does the work
                            androidx.compose.material3.TextButton(onClick = { confirming = a }, modifier = Modifier.testTag("remote-app-disconnect-${a.id}")) {
                                Text("Disconnect", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.danger)
                            }
                        }
                    }
                }
                Hint("The names are what each app says it is.", Modifier.padding(top = 10.dp))
            }
        }
    }
    val a = confirming
    if (a != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Disconnect ${a.label}?") },
            text = { Text("Belfry stops signing for it at once. To use it again, sign in to it again.") },
            confirmButton = {
                BusyButton("Disconnect", onClick = {
                    try { session.remoteApps.disconnect(a.id); confirming = null } catch (e: Exception) { error = e.message; confirming = null }
                }, kind = ButtonKind.Danger, modifier = Modifier.testTag("remote-app-disconnect"))
            },
            dismissButton = { BusyButton("Cancel", onClick = { confirming = null }, kind = ButtonKind.Ghost) },
        )
    }
}
