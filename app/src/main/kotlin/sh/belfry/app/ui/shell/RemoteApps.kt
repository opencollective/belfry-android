package sh.belfry.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.scan.CameraPreview
import sh.belfry.app.scan.ScanContent
import sh.belfry.app.scan.ScanUi
import sh.belfry.app.scan.cameraGranted
import sh.belfry.app.scan.hasCamera
import sh.belfry.app.scan.openAppSettings
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.info.ErrorLine
import sh.belfry.app.ui.info.Hint
import sh.belfry.app.ui.info.InfoPage
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.SwitchRow
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.net.RemoteApp
import sh.belfry.core.net.RemoteAppCode
import sh.belfry.core.net.RemoteAppPreview

/*
 * Sign in to another app (SPEC 19.47; the lead's contract 2026-11-20, Xavier: Android first):
 * scan (or paste) the app's nostrconnect:// code → what the belfry reads from it → Connect →
 * done → Connected apps. The belfry's server answers the app as its remote signer; this phone
 * holds no key for it. Never shown: a key, an npub, a relay address, the code itself. The app's
 * name, address and picture are its own claim: the picture is never loaded, the address is shown
 * as said ("It says it is …").
 */

private enum class Step { SCAN, PASTE, LOOKING, CONFIRM, DONE }

/** Not an app sign-in code (the scanner keeps scanning). */
private const val NOT_A_CODE = "This isn’t an app sign-in code."

@Composable
fun SignInToAppScreen() {
    val nav = LocalNav.current
    val session = LocalSession.current
    val context = LocalContext.current
    // the code holds a secret: kept in memory only while this screen is open (never saved, never shown)
    var code by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(Step.SCAN) }
    var preview by remember { mutableStateOf<RemoteAppPreview?>(null) }
    var connected by remember { mutableStateOf<RemoteApp?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var badAt by remember { mutableLongStateOf(0L) }
    var bad by remember { mutableStateOf(false) }
    LaunchedEffect(badAt) { if (badAt != 0L) { bad = true; delay(3000); bad = false } }

    fun take(text: String): Boolean {
        val c = RemoteAppCode.accept(text) ?: return false
        code = c; error = null; step = Step.LOOKING
        return true
    }
    LaunchedEffect(step) {
        if (step == Step.LOOKING) {
            val c = code ?: run { step = Step.SCAN; return@LaunchedEffect }
            try {
                preview = session.remoteApps.preview(c)
                step = Step.CONFIRM
            } catch (e: Exception) {
                code = null
                error = e.message ?: "The code could not be read. Try again."
                step = Step.PASTE
            }
        }
    }
    fun restart() { code = null; preview = null; error = null; step = Step.SCAN }

    when (step) {
        Step.SCAN -> {
            var ui by remember {
                mutableStateOf(if (!hasCamera(context)) ScanUi.NO_CAMERA else if (cameraGranted(context)) ScanUi.SCANNING else ScanUi.ASKING)
            }
            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
                ui = if (granted) ScanUi.SCANNING else ScanUi.DENIED
            }
            LaunchedEffect(Unit) { if (ui == ScanUi.ASKING) launcher.launch(android.Manifest.permission.CAMERA) }
            LaunchedEffect(ui) { if (ui == ScanUi.NO_CAMERA) step = Step.PASTE }
            BackHandler { nav.back() }
            ScanContent(
                ui = ui,
                message = if (bad) NOT_A_CODE else null,
                onClose = { nav.back() },
                onAllow = { if (cameraGranted(context)) ui = ScanUi.SCANNING else launcher.launch(android.Manifest.permission.CAMERA) },
                onSettings = { openAppSettings(context) },
                onRetry = { ui = if (cameraGranted(context)) ScanUi.SCANNING else ScanUi.ASKING },
                title = "Sign in to an app",
                hint = "In the other app, choose to log in with a remote signer (Nostr Connect) and point the camera at its QR code.",
                footer = {
                    BusyButton("Paste a code instead", onClick = { step = Step.PASTE }, kind = ButtonKind.Ghost, modifier = Modifier.testTag("remote-app-paste"))
                },
            ) {
                CameraPreview(
                    onText = { text -> if (step == Step.SCAN && !take(text)) badAt = System.nanoTime() },
                    onFailed = { ui = ScanUi.FAILED },
                )
            }
        }
        Step.PASTE -> PasteStep(error, onBack = { if (hasCamera(context)) restart() else nav.back() }, onCode = { if (!take(it)) error = NOT_A_CODE })
        Step.LOOKING -> InfoPage("Sign in to an app") {
            Hint("Asking the belfry about this app…", Modifier.padding(top = 16.dp).testTag("remote-app-looking"))
        }
        Step.CONFIRM -> {
            val p = preview ?: return
            BackHandler { restart() }
            ConfirmStep(p, error, onCancel = { restart() }) { decrypt ->
                val c = code ?: return@ConfirmStep
                try {
                    connected = session.remoteApps.connect(c, decrypt)
                    code = null
                    step = Step.DONE
                } catch (e: Exception) {
                    error = e.message ?: "That did not work. Show a new code and try again."
                }
            }
        }
        Step.DONE -> InfoPage("Sign in to an app") {
            val label = connected?.label ?: preview?.let { appLabel(it) } ?: "The app"
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You’re signed in to $label", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text, textAlign = TextAlign.Center, modifier = Modifier.testTag("remote-app-done"))
                Spacer(Modifier.height(8.dp))
                Text("Go back to it: it is ready. You can disconnect it at any time from Connected apps.", fontSize = 15.sp, lineHeight = 21.sp, color = Belfry.colors.muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                BusyButton("See connected apps", onClick = { nav.back(); nav.go(Routes.REMOTE_APPS) }, modifier = Modifier.fillMaxWidth().testTag("remote-app-see"))
            }
        }
    }
}

private fun appLabel(p: RemoteAppPreview): String = p.name ?: p.host ?: "this app"

@Composable
private fun PasteStep(error: String?, onBack: () -> Unit, onCode: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    InfoPage("Sign in to an app") {
        Hint("In the other app, choose to log in with a remote signer (Nostr Connect), copy its code (it starts with nostrconnect://) and paste it here.", Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        BelfryTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("remote-app-code"), label = "The app’s code", placeholder = "nostrconnect://…")
        if (error != null) ErrorLine(error, Modifier.padding(top = 8.dp).testTag("remote-app-error"))
        Spacer(Modifier.height(16.dp))
        BusyButton("Continue", onClick = { onCode(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("remote-app-continue"))
    }
}

/** What the app asks, as the belfry read it from its code, then Connect. */
@Composable
internal fun ConfirmStep(p: RemoteAppPreview, error: String?, onCancel: () -> Unit, onConnect: suspend (decrypt: Boolean) -> Unit) {
    val world = LocalWorld.current
    val c = Belfry.colors
    var decrypt by remember { mutableStateOf(true) }
    InfoPage("Sign in to an app") {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sign in to ${appLabel(p)}?", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.testTag("remote-app-title"))
            if (p.host != null) {
                Spacer(Modifier.height(4.dp))
                Text("It says it is ${p.host}. Belfry can’t check that.", fontSize = 14.sp, lineHeight = 19.sp, color = c.muted, textAlign = TextAlign.Center)
            }
            if (p.known) {
                Spacer(Modifier.height(6.dp))
                Text("It is already connected to your account.", fontSize = 14.sp, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.testTag("remote-app-known"))
            }
        }
        Spacer(Modifier.height(16.dp))
        val me = world.myAddress ?: world.realName(world.me).ifEmpty { "you" }
        ActionGroup {
            ActionRow("Act as $me", null, icon = BelfryIcons.User, sub = "It can post, react and sign in as you, through your belfry")
            if (p.asksDecrypt) SwitchRow(
                "Read your private messages", decrypt, { decrypt = it }, icon = BelfryIcons.Lock,
                sub = "It asks to read the messages sent to you", modifier = Modifier.testTag("remote-app-decrypt"),
            )
        }
        Hint("It stays connected until you disconnect it, or after 30 days without use.", Modifier.padding(top = 10.dp))
        if (error != null) ErrorLine(error, Modifier.padding(top = 10.dp).testTag("remote-app-error"))
        Spacer(Modifier.height(18.dp))
        BusyButton("Connect", onClick = { onConnect(p.asksDecrypt && decrypt) }, modifier = Modifier.fillMaxWidth().testTag("remote-app-connect"))
        Spacer(Modifier.height(8.dp))
        BusyButton("Cancel", onClick = { onCancel() }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
    }
}

/** The apps this account is signed in to through the belfry; each can be disconnected. */
@Composable
fun ConnectedAppsScreen() {
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    val apps by session.remoteApps.apps.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<RemoteApp?>(null) }
    LaunchedEffect(Unit) {
        try { session.remoteApps.refresh(); error = null } catch (e: Exception) { error = e.message ?: "Your connected apps could not be loaded. Try again." }
    }
    val now = session.now()
    InfoPage("Connected apps") {
        ActionGroup {
            ActionRow("Sign in to an app", { nav.go(Routes.REMOTE_APP_SCAN) }, icon = BelfryIcons.Qr, sub = "Scan its code to sign in there as you", modifier = Modifier.testTag("remote-apps-add"))
        }
        if (error != null) ErrorLine(error!!, Modifier.padding(top = 10.dp))
        val list = apps
        when {
            list == null && error == null -> Hint("Loading…", Modifier.padding(top = 14.dp))
            list.isNullOrEmpty() -> Hint("No app is signed in with your account.", Modifier.padding(top = 14.dp).testTag("remote-apps-none"))
            else -> {
                Spacer(Modifier.height(14.dp))
                ActionGroup {
                    for (a in list) {
                        val used = a.used?.let { "used " + ChatFormat.day(world, it, now).lowercase() } ?: "not used yet"
                        val sub = listOfNotNull(a.host?.takeIf { it != a.label }, used, if (a.decrypt) "reads your private messages" else null).joinToString(" · ")
                        ActionRow(a.label, { confirming = a }, icon = BelfryIcons.Globe, sub = sub, modifier = Modifier.testTag("remote-app-row"))
                    }
                }
                Hint("An app is disconnected after 30 days without use, and when your membership ends.", Modifier.padding(top = 10.dp))
            }
        }
    }
    val a = confirming
    if (a != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Disconnect ${a.label}?") },
            text = { Text("It won’t be able to act as you any more. To use it again, sign in to it again.") },
            confirmButton = {
                BusyButton("Disconnect", onClick = {
                    try { session.remoteApps.disconnect(a.id); confirming = null } catch (e: Exception) { error = e.message; confirming = null }
                }, kind = ButtonKind.Danger, modifier = Modifier.testTag("remote-app-disconnect"))
            },
            dismissButton = { BusyButton("Cancel", onClick = { confirming = null }, kind = ButtonKind.Ghost) },
        )
    }
}
