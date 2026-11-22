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
 * A channel's Pin (SPEC 19.60; canvas channels/settings, settings-townsquare): "Pin for me" and,
 * for the belfry's admins, "Pin for everyone". The main channel shows one plain line instead (not
 * a disabled control: Xavier read that as a bug). Nothing about notifications: the pull-down has
 * their own row. Groups have no pin.
 */
@Composable
fun ChannelPinSettings(g: Group) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val town = g.type == ChannelType.CHANNEL && g.id != Charter.ID
    val main = g.id == OneShape.MAIN
    val pins by session.pins.collectAsState()
    val guestMode = session.guest.collectAsState().value?.reading == true
    if (!town) return
    Header("Pin")
    if (main) {
        ActionGroup(Modifier.testTag("pin-main")) {
            Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(BelfryIcons.Pin, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("${g.name} is always first in everyone's menu.", fontSize = 15.sp, color = c.text)
            }
        }
        return
    }
    ActionGroup {
        SwitchRow(
            BelfryIcons.Pin, "Pin for me", sub = "Always in your menu, at the top",
            on = g.id in pins.c, enabled = true, tag = "pin-me",
        ) { session.togglePin(g.id) }
        if (world.isCommunityAdmin && !guestMode) {
            val busy = rememberBusy()
            var error by remember { mutableStateOf<String?>(null) }
            SwitchRow(
                BelfryIcons.Users, "Pin for everyone",
                sub = error ?: "Admins only: at the top of everyone's menu",
                on = g.id in world.everyonePins(), enabled = !busy.busy, busy = busy.busy, tag = "pin-everyone",
            ) { want ->
                error = null
                busy.run(onError = { error = (it as? ActionException)?.message ?: "Not saved. Try again." }) { session.setEveryonePin(g.id, want) }
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
