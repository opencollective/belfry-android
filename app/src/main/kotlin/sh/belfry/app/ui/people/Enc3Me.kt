package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.SwitchRow
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.enc.EncDeviceText
import sh.belfry.core.engine.WhoText
import sh.belfry.core.net.SessionInfo

/*
 * Format 3, part 6 (SPEC 19.86; Encrypted canvas encrypted/devices, device, devices-agent, agent-access, agent-access-confirm,
 * agent-access-on; the web's WhoCanRead.tsx EncDevicesScreen / AgentAccess / RemoveSheet, f76fec5): Me › Devices on a format-3
 * belfry, One device, my agent's access on its profile. Everything is read from my current list (`Enc3DeviceState.devices`,
 * `readers`): never from what this device did. Names are the devices' own (`/me/sessions`) or the agent's member name, never a
 * key. E-ink: This device / New are words in a frame; the switch's state is also said in words under it.
 */

/** What a trash icon removes: another device of my list (by its own name), or my agent's access. */
internal data class Enc3Removing(val key: String, val name: String?, val agent: Boolean)

/**
 * The one confirmation of every Remove of format 3 (the card, the first screen, Who can read this, Me › Devices, One device):
 * encrypted/card-remove's words for a device; the web's for my agent's access.
 */
@Composable
internal fun Enc3RemoveSheet(r: Enc3Removing, onConfirm: suspend () -> Unit, onDismiss: () -> Unit, confirmTag: String) {
    val agentName = r.name ?: EncDeviceText.AGENT
    ConfirmSheet(
        title = if (r.agent) EncDeviceText.agentRemoveTitle(agentName) else EncDeviceText.removeTitle(r.name),
        confirmLabel = EncDeviceText.REMOVE,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        message = if (r.agent) EncDeviceText.agentRemoveText(agentName) else EncDeviceText.REMOVE_TEXT,
        cancelLabel = EncDeviceText.CANCEL,
        confirmTag = confirmTag,
    )
}

/** My sessions (names, last active), read when the screen opens and after each removal. */
@Composable
private fun rememberMySessions(round: Int): List<SessionInfo> {
    val session = LocalSession.current
    var rows by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
    LaunchedEffect(round) {
        rows = try { session.enc3View?.sessions().orEmpty() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        try { session.enc3Names() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
    }
    return rows
}

private fun byClientKey(rows: List<SessionInfo>): Map<String, SessionInfo> =
    rows.filter { !it.clientPubkey.isNullOrEmpty() }.associateBy { it.clientPubkey!! }

/**
 * Me › Devices on a format-3 belfry (encrypted/devices, devices-agent): every device on my current list (the list says
 * which exist; `/me/sessions` names them and says when each was last active), This device first and marked in words, then
 * the newest; my agent when it reads (a reader on my list). A trash icon on every other row asks first; This device has
 * none (signing out is Me's). A tap opens One device (my agent: its profile, where its access is).
 */
@Composable
fun Enc3DevicesScreen() {
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    val st by rememberEnc3Device()
    var round by remember { mutableIntStateOf(0) }
    val byKey = byClientKey(rememberMySessions(round))
    var removing by remember { mutableStateOf<Enc3Removing?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val now = session.now()
    val zone = world.ctx.zone
    val here = st.here
    val list = st.devices.sortedWith(compareBy<String> { if (it == here) 0 else 1 }.thenByDescending { st.added[it] ?: 0L })
    Page(EncDeviceText.DEVICES) {
        Hint(EncDeviceText.DEVICES_TEXT, Modifier.padding(bottom = 10.dp))
        ActionGroup(Modifier.testTag("enc3-devices")) {
            for (k in list) {
                val s = byKey[k]
                val name = WhoText.deviceName(s)
                val isHere = k == here
                DeviceRow(
                    icon = deviceIcon(s?.device ?: s?.applicationName), title = name,
                    marks = listOfNotNull(WhoText.THIS_DEVICE.takeIf { isHere }, EncDeviceText.NEW.takeIf { !isHere && EncDeviceText.isNew(st.added[k], now) }),
                    sub = EncDeviceText.deviceLine(st.added[k], EncDeviceText.isoSeconds(s?.lastActivity), now, zone),
                    tag = if (isHere) "enc3-device-here" else "enc3-device-row",
                    onClick = { nav.go(Routes.device(k)) },
                    trash = if (isHere) null else ({ error = null; done = null; removing = Enc3Removing(k, s?.let { WhoText.deviceName(it) }, false) }),
                    trashLabel = "Remove $name",
                )
            }
            for (a in st.readers) {
                val name = world.displayName(a)
                DeviceRow(
                    icon = BelfryIcons.User, title = name, marks = emptyList(), sub = EncDeviceText.agentLine(st.added[a], now, zone),
                    tag = "enc3-device-agent", onClick = { nav.go(Routes.profile(a)) },
                    trash = { error = null; done = null; removing = Enc3Removing(a, name, true) }, trashLabel = EncDeviceText.agentRemoveTitle(name).removeSuffix("?"),
                )
            }
            if (st.devices.isEmpty()) DeviceRow(icon = BelfryIcons.Lock, title = null, marks = emptyList(), sub = EncDeviceText.NOT_SET_UP, tag = "enc3-devices-not-set-up")
        }
        if (st.devices.size > 1) Hint(EncDeviceText.DEVICES_FOOT, Modifier.padding(top = 10.dp))
        if (st.readers.isNotEmpty()) Hint(EncDeviceText.AGENT_FOOT, Modifier.padding(top = 4.dp))
        done?.let {
            Text(it, fontSize = 14.sp, lineHeight = 19.sp, color = Belfry.colors.text,
                modifier = Modifier.padding(top = 10.dp).testTag("enc3-devices-done").semantics { liveRegion = LiveRegionMode.Polite })
        }
        ErrorLine(error)
    }
    removing?.let { r ->
        Enc3RemoveSheet(r, onConfirm = {
            try {
                session.enc3RemoveListed(r.key, r.agent)
                done = if (r.agent) EncDeviceText.agentOff(r.name ?: EncDeviceText.AGENT) else EncDeviceText.removed(r.name)
                round++
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.shown(EncDeviceText.FAILED) }
            removing = null
        }, onDismiss = { removing = null }, confirmTag = "enc3-devices-remove-confirm")
    }
}

/** A row of Me › Devices: the icon, the name with its marks (words in a frame), the line under it, the trash icon. */
@Composable
private fun DeviceRow(
    icon: ImageVector, title: String?, marks: List<String>, sub: String, tag: String,
    onClick: (() -> Unit)? = null, trash: (() -> Unit)? = null, trashLabel: String = "",
) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = if (trash != null) 2.dp else 14.dp, top = 10.dp, bottom = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                for (m in marks) Mark(m)
            }
            if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
        }
        if (trash != null) IconAction(BelfryIcons.Trash, trashLabel, trash, modifier = Modifier.testTag("$tag-trash"))
    }
}

/** "This device", "New": words in a frame, never a colour alone (e-ink). */
@Composable
private fun Mark(text: String) {
    val c = Belfry.colors
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(c.surface2).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * One device (encrypted/device): its own name, Added, Last active, Earlier messages (Shared / Not shared: was its card
 * answered "shared"), then Remove this device (asks first; revoke with `by` = this device). This device: no Remove.
 */
@Composable
fun Enc3OneDeviceScreen(key: String) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    val st by rememberEnc3Device()
    val s = byClientKey(rememberMySessions(0))[key]
    val name = WhoText.deviceName(s)
    val here = key == st.here
    val now = session.now()
    val zone = world.ctx.zone
    val h24 = rememberH24()
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page(name) {
        ActionGroup(Modifier.testTag("enc3-one-device")) {
            ActionRow(EncDeviceText.ADDED, onClick = null, value = st.added[key]?.let { EncDeviceText.time(it, now, zone, h24) } ?: "—")
            ActionRow(EncDeviceText.LAST_ACTIVE, onClick = null, value = EncDeviceText.isoSeconds(s?.lastActivity)?.let { EncDeviceText.dayWord(it, now, zone) } ?: "—")
            if (!here) ActionRow(EncDeviceText.EARLIER, onClick = null, value = if (key in st.sharedWith) EncDeviceText.SHARED else EncDeviceText.NOT_SHARED, modifier = Modifier.testTag("enc3-one-earlier"))
        }
        Spacer(Modifier.height(18.dp))
        if (here) Hint(EncDeviceText.HERE_NOTE, Modifier.testTag("enc3-one-here"))
        else {
            ActionGroup {
                ActionRow(EncDeviceText.REMOVE_DEVICE, onClick = { error = null; removing = true }, icon = BelfryIcons.Trash, danger = true, modifier = Modifier.testTag("enc3-one-remove"))
            }
            Hint(EncDeviceText.ONE_REMOVE_TEXT, Modifier.padding(top = 8.dp))
        }
        ErrorLine(error)
    }
    if (removing) Enc3RemoveSheet(Enc3Removing(key, s?.let { WhoText.deviceName(it) }, false), onConfirm = {
        try {
            session.enc3RemoveListed(key, false)
            removing = false
            nav.back()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.shown(EncDeviceText.FAILED); removing = false }
    }, onDismiss = { removing = false }, confirmTag = "enc3-one-remove-confirm")
}

/**
 * On my own agent's profile, format 3 only (encrypted/agent-access, -confirm, -on): "Give access to my private messages and
 * private groups", off by default. On is what my list says (a `reader` entry for this agent); after a call, "Waiting for the
 * belfry…" until the list agrees. Turning it on asks first, then `POST /agents {pubkey, reads: true}`; off asks first (the
 * agent's Remove words), then `reads: false`.
 */
@Composable
internal fun AgentAccess(agent: String) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val st by rememberEnc3Device()
    val listed = agent in st.readers
    var pending by remember(agent) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(listed, pending) { if (pending == listed) pending = null }
    var asking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val name = world.displayName(agent)
    SectionTitle(EncDeviceText.ACCESS_SECTION)
    ActionGroup {
        SwitchRow(
            EncDeviceText.ACCESS, pending ?: listed,
            { on -> error = null; if (on) asking = true else removing = true },
            sub = when { pending != null -> EncDeviceText.ACCESS_WAIT; listed -> EncDeviceText.accessOn(name); else -> EncDeviceText.ACCESS_OFF },
            enabled = pending == null, icon = BelfryIcons.Lock, modifier = Modifier.testTag("enc3-agent-access"),
        )
    }
    ErrorLine(error)
    if (asking) ConfirmSheet(
        title = EncDeviceText.giveTitle(name), confirmLabel = EncDeviceText.GIVE,
        onConfirm = {
            try { session.enc3AgentReads(agent, true); pending = true }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.shown(EncDeviceText.FAILED) }
            asking = false
        },
        onDismiss = { asking = false }, message = EncDeviceText.GIVE_TEXT, danger = false, cancelLabel = EncDeviceText.CANCEL, confirmTag = "enc3-give-access",
    )
    if (removing) Enc3RemoveSheet(Enc3Removing(agent, name, true), onConfirm = {
        try { session.enc3AgentReads(agent, false); pending = false }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.shown(EncDeviceText.FAILED) }
        removing = false
    }, onDismiss = { removing = false }, confirmTag = "enc3-access-off-confirm")
}
