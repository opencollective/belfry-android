package sh.belfry.app.ui.info

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.belfry.app.push.AndroidPush
import sh.belfry.app.push.PushState
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.people.rememberTurnOnNotifications
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.NotifyChoice
import sh.belfry.core.push.NotificationSettings
import sh.belfry.core.push.PushLevel

/**
 * A channel's notifications in one sheet (round 6, board channels/notifications; SPEC 19.41):
 * Everything / When it's for me / Nothing, applied at once (a light haptic; put back with a fixed
 * line when the belfry refuses), then "Also e-mail me its announcements". The pull-down's one row
 * that opens a sheet rather than a screen.
 */
@Composable
fun NotifySheet(groupId: String, onDismiss: () -> Unit) {
    BelfrySheet(onDismiss = onDismiss, skipPartiallyExpanded = false, modifier = Modifier.testTag("notify-sheet")) {
        NotifyBody(groupId)
    }
}

/** The sheet's body. [push], [follows] and [state] are for tests (null = asked from the belfry / the phone). */
@Composable
fun NotifyBody(groupId: String, push: NotificationSettings? = null, follows: String? = null, state: PushState? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val label = channelLabel(world.group(groupId), groupId)

    var settings by remember { mutableStateOf(push) }
    var stored by remember { mutableStateOf(push?.channels?.get(groupId)?.key ?: push?.let { "default" }) }
    var follow by remember { mutableStateOf(follows) }
    var followKnown by remember { mutableStateOf(follows != null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var savingMail by remember { mutableStateOf(false) }

    LaunchedEffect(groupId) {
        if (settings == null) {
            try {
                val s = session.notificationSettings()
                settings = s
                stored = s.channels[groupId]?.key ?: "default"
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = (e as? ActionException)?.message ?: "Your notification settings could not be loaded."
            }
        }
        if (!followKnown) {
            try { follow = session.follows().byChannel[groupId] ?: "stop"; followKnown = true } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
    }
    if (state == null) LaunchedEffect(Unit) { AndroidPush.refreshState(context, session) }
    val pushState = state ?: AndroidPush.state.collectAsState().value
    val turnOn = rememberTurnOnNotifications()

    Text("Notify me about $label", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
    if (pushState == PushState.NEEDS_PERMISSION) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Text("Notifications aren’t on for this phone yet.", fontSize = 15.sp, color = c.muted, modifier = Modifier.weight(1f))
            TextButton(onClick = turnOn, modifier = Modifier.testTag("notify-turn-on")) { Text("Turn on", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
        }
    }

    val current = stored?.let { NotifyChoice.of(it) }
    NotifyChoice.entries.forEach { choice ->
        val on = choice == current
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(14.dp))
                // e-ink: the chosen one is told by a thick border and a filled circle, not a shade
                .border(BorderStroke(if (on) 2.dp else 1.dp, if (on) c.text else c.line), RoundedCornerShape(14.dp))
                .semantics { selected = on }
                .clickable(enabled = settings != null && !saving, role = Role.RadioButton) {
                    if (on) return@clickable
                    val before = stored
                    stored = choice.level
                    saving = true
                    error = null
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch {
                        try {
                            val s = session.setNotifications(NotificationSettings.setLevel(groupId, PushLevel.of(choice.level)))
                            settings = s
                            stored = s.channels[groupId]?.key ?: "default"
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            stored = before
                            error = (e as? ActionException)?.message ?: "Not saved. Try again."
                        } finally { saving = false }
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("notify-${choice.level}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (on) c.text else c.surface),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when (choice) {
                    NotifyChoice.EVERYTHING -> Icons.Outlined.NotificationsActive
                    NotifyChoice.FOR_ME -> BelfryIcons.At
                    NotifyChoice.NOTHING -> Icons.Outlined.NotificationsOff
                }
                Icon(icon, contentDescription = null, tint = if (on) c.bg else c.text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(choice.title, fontSize = 17.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = c.text)
                Text(choice.line(stored), fontSize = 14.sp, lineHeight = 19.sp, color = c.muted)
            }
            if (on && saving) Spinner(size = 18.dp)
        }
    }
    if (settings == null && error == null) Row(Modifier.padding(vertical = 8.dp)) { Spinner(); Spacer(Modifier.width(10.dp)); Hint("Loading…") }
    error?.let { ErrorLine(it, Modifier.padding(top = 6.dp)) }
    if (pushState == PushState.NOT_IN_BUILD || pushState == PushState.DEMO) {
        Hint(pushState.line + " The choice still applies to your other devices.", Modifier.padding(top = 6.dp))
    }

    Spacer(Modifier.height(10.dp))
    val f = follow
    SwitchRow(
        "Also e-mail me its announcements",
        checked = f in sh.belfry.core.net.FOLLOW_FREQUENCIES,
        onCheckedChange = { want ->
            savingMail = true
            scope.launch {
                try { follow = session.setFollow(groupId, if (want) "asap" else "stop") ?: "stop" } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    error = (e as? ActionException)?.message ?: "Not saved. Try again."
                } finally { savingMail = false }
            }
        },
        sub = "What stewards mark as an announcement" + (if (f == "monthly") " · once a month" else if (f == "weekly") " · once a week" else ""),
        enabled = followKnown,
        busy = savingMail,
        modifier = Modifier.testTag("notify-mail"),
    )
    Spacer(Modifier.height(8.dp))
}
