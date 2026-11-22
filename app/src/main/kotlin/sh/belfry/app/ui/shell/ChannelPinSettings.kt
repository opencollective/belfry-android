package sh.belfry.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.ChannelType
import sh.belfry.core.model.Charter
import sh.belfry.core.model.Group
import sh.belfry.core.model.OneShape
import sh.belfry.core.model.everyonePins
import sh.belfry.core.model.isLocked
import sh.belfry.core.push.NotificationSettings
import sh.belfry.core.push.PushLevel

/**
 * The top of a channel's settings (nav2/channel-pin; Navigation v2): "In your menu" with Pin for
 * me and, for belfry admins, Pin for everyone; then "Notify me of everything" (the bell: push level
 * `all` for the channel, off = `default`). The main channel is always first in everyone's menu: its
 * pin rows are on and locked. Groups get the notification row only.
 */
@Composable
fun ChannelPinSettings(g: Group, notifications: NotificationSettings? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val town = g.type == ChannelType.CHANNEL && g.id != Charter.ID
    val main = g.id == OneShape.MAIN
    val pins by session.pins.collectAsState()
    val guestMode = session.guest.collectAsState().value?.reading == true

    if (town) {
        Header("In your menu")
        ActionGroup {
            SwitchRow(
                BelfryIcons.Pin, "Pin for me",
                sub = if (main) "The main channel is always first in everyone's menu" else "Always in your menu, at the top",
                on = main || g.id in pins.c, enabled = !main, tag = "pin-me",
            ) { session.togglePin(g.id) }
            if (world.isCommunityAdmin && !guestMode) {
                val busy = rememberBusy()
                var error by remember { mutableStateOf<String?>(null) }
                val everyone = main || g.id in world.everyonePins()
                SwitchRow(
                    BelfryIcons.Users, "Pin for everyone",
                    sub = error ?: "Admins only: at the top of everyone's menu",
                    on = everyone, enabled = !main && !busy.busy, busy = busy.busy, tag = "pin-everyone",
                ) { want ->
                    error = null
                    busy.run(onError = { error = (it as? ActionException)?.message ?: "Not saved. Try again." }) { session.setEveryonePin(g.id, want) }
                }
            }
        }
    }

    if (!world.isLocked(g.id) && !world.amGuest) {
        var settings by remember(g.id) { mutableStateOf(notifications) }
        var level by remember(g.id) { mutableStateOf(notifications?.channels?.get(g.id)?.key ?: notifications?.let { "default" }) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(g.id) {
            if (settings == null) try {
                val s = session.notificationSettings()
                settings = s
                level = s.channels[g.id]?.key ?: "default"
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = (e as? ActionException)?.message ?: "Your notification settings could not be loaded."
            }
        }
        Header("Notifications")
        ActionGroup {
            SwitchRow(
                Icons.Outlined.NotificationsActive, "Notify me of everything",
                sub = error ?: if (level == PushLevel.ALL.key) "On: every message and thread reply" else "Off: you hear about mentions and replies to you only",
                on = level == PushLevel.ALL.key, enabled = settings != null && !saving, busy = saving, tag = "notify-everything",
            ) { want ->
                val before = level
                level = if (want) PushLevel.ALL.key else PushLevel.DEFAULT.key
                saving = true
                error = null
                scope.launch {
                    try {
                        val s = session.setNotifications(NotificationSettings.setLevel(g.id, if (want) PushLevel.ALL else PushLevel.DEFAULT))
                        settings = s
                        level = s.channels[g.id]?.key ?: "default"
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        level = before
                        error = (e as? ActionException)?.message ?: "Not saved. Try again."
                    } finally { saving = false }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Belfry.colors.muted, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))
}

/** A row with a line icon, a title, a line under it and a switch; the whole row toggles. The thumb carries a check when on (e-ink). */
@Composable
private fun SwitchRow(icon: ImageVector, title: String, sub: String, on: Boolean, enabled: Boolean, tag: String, busy: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().background(c.bg)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = c.text)
            Text(sub, fontSize = 13.sp, color = c.muted)
        }
        Spacer(Modifier.width(10.dp))
        if (busy) Box(Modifier.padding(end = 8.dp)) { Spinner(size = 16.dp) }
        Switch(
            checked = on, onCheckedChange = null, enabled = enabled,
            thumbContent = if (on) ({ Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }) else null,
            colors = SwitchDefaults.colors(checkedTrackColor = c.text, checkedThumbColor = c.bg, checkedIconColor = c.text, uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surface, uncheckedBorderColor = c.muted),
        )
    }
}
