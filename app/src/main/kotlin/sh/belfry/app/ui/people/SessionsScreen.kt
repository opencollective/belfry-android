package sh.belfry.app.ui.people

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) { delay(1500); copied = false }
    Page("Signed-in devices", actions = { if (!demo) IconAction(BelfryIcons.Retry, "Reload", onReload) }) {
        SectionTitle("Your key", Modifier.padding(top = 0.dp))
        ActionGroup {
            ActionRow(
                shortNpub(me), onClick = { copyText(context, npubOf(me), "Your key"); copied = true }, icon = BelfryIcons.Copy,
                sub = if (demo) "Demo key, kept on this device only." else "$community keeps this key for you. The sessions below sign with it.",
                value = if (copied) "Copied" else "Copy",
            )
        }
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
