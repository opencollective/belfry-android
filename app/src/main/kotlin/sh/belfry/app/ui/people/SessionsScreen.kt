package sh.belfry.app.ui.people

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.MyDevice
import sh.belfry.core.model.SealedText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Chip
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.copyText
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.core.engine.ActionException
import sh.belfry.core.net.SessionInfo
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Your key and every session open on it: each one an app or device that can sign as you. */
@Composable
fun SessionsScreen() {
    val session = LocalSession.current
    var rows by remember { mutableStateOf<List<SessionInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var ending by remember { mutableStateOf<SessionInfo?>(null) }
    LaunchedEffect(round) {
        if (session.localOnly) return@LaunchedEffect
        error = null
        try {
            rows = session.sessions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.shown("The list of sessions is not available right now.")
        }
    }
    SessionsContent(
        me = session.me,
        demo = session.localOnly,
        community = session.community.name,
        rows = rows,
        error = error,
        isThis = { session.isThisSession(it) },
        onReload = { round++ },
        onEnd = { ending = it },
    )
    val e = ending
    if (e != null) {
        ConfirmSheet(
            title = "End the session of ${e.applicationName}?",
            confirmLabel = "End session",
            onConfirm = {
                try {
                    session.revokeSession(e.bunkerPubkey)
                    round++
                } catch (x: ActionException) {
                    error = x.shown("Not ended. Try again.")
                }
                ending = null
            },
            onDismiss = { ending = null },
            message = "It will no longer sign as you.",
        )
    }
}

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

internal fun sessionDay(s: String?): String {
    if (s.isNullOrEmpty()) return ""
    val instant = try {
        OffsetDateTime.parse(s).toInstant()
    } catch (e: Exception) {
        try { Instant.parse(s) } catch (e2: Exception) { null }
    }
    return if (instant != null) DAY.format(instant.atZone(ZoneId.systemDefault())) else s.take(10)
}

/** The screen without a session behind it (tests render it with made-up rows). */
@Composable
fun SessionsContent(
    me: String,
    demo: Boolean,
    community: String,
    rows: List<SessionInfo>?,
    error: String?,
    isThis: (SessionInfo) -> Boolean,
    onReload: () -> Unit,
    onEnd: (SessionInfo) -> Unit,
    /** The devices that read sealed conversations (canvas messages/encryption-remove-device), when there are any. */
    devices: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) { delay(1500); copied = false }
    Page("Signed-in devices", actions = { if (!demo) IconAction(BelfryIcons.Retry, "Reload", onReload) }) {
        SectionTitle("Your key", Modifier.padding(top = 0.dp))
        ActionGroup {
            ActionRow(
                "Copy your key", onClick = { copyText(context, npubOf(me), "Your key"); copied = true }, icon = BelfryIcons.Copy,
                sub = if (demo) "Demo key, kept on this device only." else "$community keeps this key for you. The sessions below sign with it.",
                value = if (copied) "Copied" else "Copy",
            )
        }
        devices?.invoke()
        SectionTitle("Sessions")
        when {
            demo -> Hint("Demo mode has no sessions.")
            rows == null && error == null -> Hint("Loading…")
            rows != null && rows.isEmpty() -> Hint("No sessions.")
        }
        if (!rows.isNullOrEmpty()) {
            ActionGroup {
                rows.forEach { s ->
                    val here = isThis(s)
                    val since = "Since ${sessionDay(s.createdAt)}" + (if (!s.lastActivity.isNullOrEmpty()) " · last used ${sessionDay(s.lastActivity)}" else "")
                    val device = s.device
                    ActionRow(
                        s.applicationName, onClick = null, icon = BelfryIcons.Lock,
                        sub = if (!device.isNullOrEmpty()) "$device\n$since" else since,
                        trailing = {
                            if (here) Chip("This device") else BusyButton("End", onClick = { onEnd(s) }, kind = ButtonKind.Ghost, small = true)
                        },
                    )
                }
            }
        }
        ErrorLine(error)
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Me → Devices (canvas v87 / v88): where I'm signed in, each device named in full. Until this
 * device has a device list (the sealed wiring), the sessions of the account stand in for it.
 */
@Composable
fun DevicesScreen() {
    val session = LocalSession.current
    val st by session.encryption.state.collectAsState()
    LaunchedEffect(Unit) { try { session.encryption.refresh() } catch (e: Exception) { } }
    if (st.myDevices.isEmpty()) SessionsScreen() else Page("Devices") { SealDevices() }
}

/**
 * canvas messages/encryption-remove-device: the devices that read my sealed conversations, each
 * named in full. Remove asks first (Remove locks while working); this device is Sign out's.
 */
@Composable
internal fun SealDevices() {
    val session = LocalSession.current
    val world = sh.belfry.app.ui.nav.LocalWorld.current
    val st by session.encryption.state.collectAsState()
    if (st.myDevices.isEmpty()) {
        Hint("No devices yet.")
        return
    }
    var removing by remember { mutableStateOf<MyDevice?>(null) }
    // canvas v82: once confirmed, the row shows progress and no other row can be removed
    var working by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val now = session.now()
    val c = Belfry.colors
    Hint("Signed in as ${world.face(session.me).name}", Modifier.padding(bottom = 8.dp))
    ActionGroup {
        st.myDevices.forEach { d ->
            ActionRow(
                d.name, onClick = null, icon = d.kind.icon(), sub = SealedText.activeLine(d, now, world.ctx.zone),
                modifier = Modifier.testTag("seal-device"),
                trailing = {
                    if (d.thisDevice) {
                        Chip("This device")
                    } else if (st.earlierLocked) {
                        // canvas v90: a device that is itself locked offers no Remove
                    } else if (working == d.id) {
                        sh.belfry.app.ui.kit.Spinner(Modifier.padding(horizontal = 12.dp).testTag("seal-removing"))
                    } else {
                        TextButton(onClick = { error = null; removing = d }, enabled = working == null, modifier = Modifier.testTag("seal-remove-${d.id}")) {
                            Text("Remove", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (working == null) c.danger else c.faint)
                        }
                    }
                },
            )
        }
    }
    Hint("This device can't be removed here: use Sign out.", Modifier.padding(top = 8.dp))
    ErrorLine(error)
    val r = removing
    if (r != null) RemoveDeviceDialog(r, onDismiss = { removing = null }, onRemove = {
        removing = null
        working = r.id
        scope.launch {
            try { session.encryption.removeDevice(r.id) } catch (e: Exception) { error = e.shown("Not removed. Try again.") }
            working = null
        }
    })
}

@Composable
fun RemoveDeviceDialog(d: MyDevice, onDismiss: () -> Unit, onRemove: suspend () -> Unit) {
    val busy = rememberBusy()
    AlertDialog(
        onDismissRequest = { if (!busy.busy) onDismiss() },
        title = { Text("Remove ${d.name}?", modifier = Modifier.testTag("seal-remove-title")) },
        text = { Text(SealedText.REMOVE_TEXT) },
        confirmButton = { BusyButton("Remove", onClick = onRemove, kind = ButtonKind.Danger, small = true, busy = busy, modifier = Modifier.testTag("seal-remove")) },
        dismissButton = { BusyButton("Cancel", onClick = { onDismiss() }, kind = ButtonKind.Ghost, small = true, enabled = !busy.busy) },
        containerColor = Belfry.colors.bg,
    )
}
