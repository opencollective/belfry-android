package sh.belfry.app.ui.people

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import sh.belfry.app.push.AndroidPush
import sh.belfry.app.push.PushState
import sh.belfry.app.ui.chat.glyph
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.SwitchRow
import sh.belfry.app.ui.kit.icon
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.push.NotificationSettings
import sh.belfry.core.push.PushLevel

/**
 * Asks for the notification permission (Android 13+) right when the person chose notifications,
 * then registers this phone. Below Android 13 (or after "Don't ask again") it opens the app's
 * notification settings instead. Never called at launch.
 */
@Composable
fun rememberTurnOnNotifications(): () -> Unit {
    val context = LocalContext.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { AndroidPush.register(context.applicationContext, session) }
        else AndroidPush.refreshState(context, session)
    }
    return remember(launcher) {
        {
            if (AndroidPush.permitted(context)) {
                scope.launch { AndroidPush.register(context.applicationContext, session) }
            } else if (AndroidPush.needsRuntimePermission) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                try {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { }
            }
        }
    }
}

private val SWITCHES = listOf(
    Triple("dms", "Direct messages", "Direct messages and e-mail threads"),
    Triple("mentions", "Mentions and replies", "When someone mentions you or replies to you"),
    Triple("announcements", "Announcements", "Announcements in your channels"),
    Triple("pins", "Pinned messages", "When an admin pins a message"),
)

private fun NotificationSettings.switch(name: String): Boolean = when (name) {
    "dms" -> dms; "mentions" -> mentions; "announcements" -> announcements; else -> pins
}

/**
 * Me → Notifications: this phone's state (with "Turn on notifications" when the permission is
 * missing), the four switches, and each of my channels with its push level. Every change is one
 * `POST /notifications {set}`; a control locks until the belfry answered. [load] / [save] are the
 * belfry calls (tests pass their own).
 */
@Composable
fun NotificationsScreen(
    load: (suspend () -> NotificationSettings)? = null,
    save: (suspend (JsonObject) -> NotificationSettings)? = null,
    state: PushState? = null,
) {
    val context = LocalContext.current
    val world = LocalWorld.current
    val session = LocalSession.current
    var settings by remember { mutableStateOf<NotificationSettings?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var levelOf by remember { mutableStateOf<String?>(null) }
    val turnOn = rememberTurnOnNotifications()
    LaunchedEffect(Unit) { AndroidPush.refreshState(context, session) }
    val pushState = state ?: AndroidPush.state.collectAsState().value

    suspend fun reload() {
        failed = null
        try {
            settings = if (load != null) load() else session.notificationSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = (e as? ActionException)?.message ?: "Your notification settings could not be loaded."
        }
    }
    LaunchedEffect(Unit) { reload() }
    suspend fun apply(set: JsonObject) {
        error = null
        settings = if (save != null) save(set) else session.setNotifications(set)
    }

    Page("Notifications") {
        SectionTitle("This phone")
        ActionGroup {
            ActionRow(pushState.line, onClick = null, icon = if (pushState == PushState.ON) BelfryIcons.BellFilled else BelfryIcons.Bell, modifier = Modifier.testTag("push-state"))
            if (pushState == PushState.NEEDS_PERMISSION) {
                Hairline()
                ActionRow(
                    "Turn on notifications", onClick = turnOn, icon = BelfryIcons.Bell,
                    sub = "Get direct messages, mentions and announcements on this phone, even when the app is closed.",
                    chevron = true, modifier = Modifier.testTag("push-turn-on"),
                )
            }
        }
        val s = settings
        if (s == null) {
            Spacer(Modifier.height(16.dp))
            if (failed != null) ErrorLine(failed) else Spinner()
            return@Page
        }
        SectionTitle("Notify me about")
        ActionGroup {
            SWITCHES.forEachIndexed { i, (name, label, sub) ->
                if (i > 0) Hairline()
                val busy = rememberBusy()
                SwitchRow(
                    label, s.switch(name),
                    onCheckedChange = { on ->
                        busy.run(onError = { e -> error = (e as? ActionException)?.message ?: "Not saved. Try again." }) { apply(NotificationSettings.setSwitch(name, on)) }
                    },
                    icon = when (name) { "dms" -> BelfryIcons.Mail; "mentions" -> BelfryIcons.At; "announcements" -> BelfryIcons.Megaphone; else -> BelfryIcons.Pin },
                    sub = sub, busy = busy.busy, modifier = Modifier.testTag("push-switch-$name"),
                )
            }
        }
        ErrorLine(error, Modifier.padding(top = 8.dp))
        val mine = world.groups.filter { world.me in it.members && !it.isProposal }
        if (mine.isNotEmpty()) {
            SectionTitle("Your channels")
            ActionGroup {
                mine.forEachIndexed { i, g ->
                    if (i > 0) Hairline()
                    ActionRow(
                        g.name, onClick = { levelOf = g.id }, icon = g.glyph().icon(), value = s.level(g.id).label, chevron = true,
                        modifier = Modifier.testTag("push-channel-${g.id}"),
                    )
                }
            }
            Hint("The e-mail follows of a channel (its bell) are separate from these.", Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
    levelOf?.let { id ->
        val s = settings ?: return@let
        BelfrySheet(onDismiss = { levelOf = null }, title = "Push from ${world.group(id)?.name ?: id}") {
            PushLevelRows(s.level(id), onPick = { level -> apply(NotificationSettings.setLevel(id, level)); levelOf = null })
        }
    }
}

/**
 * The four push levels of a channel as a list (Discord-style rows, the current one ticked). A pick
 * locks every row until [onPick] returns; an error is shown under the list.
 */
@Composable
fun PushLevelRows(current: PushLevel, onPick: suspend (PushLevel) -> Unit) {
    val c = Belfry.colors
    val busy = rememberBusy()
    var picking by remember { mutableStateOf<PushLevel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    ActionGroup {
        PushLevel.entries.forEachIndexed { i, level ->
            if (i > 0) Hairline()
            ActionRow(
                level.label,
                onClick = {
                    if (level != current) {
                        picking = level
                        error = null
                        busy.run(onError = { e -> error = (e as? ActionException)?.message ?: "Not saved. Try again."; picking = null }) { onPick(level); picking = null }
                    }
                },
                icon = when (level) { PushLevel.ALL -> BelfryIcons.BellFilled; PushLevel.OFF -> BelfryIcons.Close; PushLevel.MENTIONS -> BelfryIcons.At; else -> BelfryIcons.Bell },
                sub = level.sub, enabled = !busy.busy, modifier = Modifier.testTag("push-level-${level.key}"),
                trailing = {
                    if (picking == level) Spinner(size = 18.dp)
                    else if (level == current) Icon(BelfryIcons.Check, "Selected", tint = c.text, modifier = Modifier.size(20.dp))
                },
            )
        }
    }
    ErrorLine(error, Modifier.padding(top = 8.dp))
}
