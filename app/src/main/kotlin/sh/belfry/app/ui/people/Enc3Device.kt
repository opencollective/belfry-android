package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.enc.DeviceCard
import sh.belfry.core.enc.Enc3DeviceState
import sh.belfry.core.enc.EncDeviceText
import sh.belfry.core.enc.FirstRow
import sh.belfry.core.enc.RemovedHere
import java.time.ZoneId

/*
 * Format 3, part 5 (SPEC 19.85; Encrypted canvas: encrypted/new-*, transition, card*, laptop-card, edge-added-removed,
 * edge-two-lists, first-screen, removed-device, signout-only, lost-all*): screens only, filled by `Session.enc3Device`
 * (core `Enc3Devices`; the demo sets one by hand). Names are the devices' own names, never a key or an id; another
 * person's devices are never shown. E-ink: every state is said in words, never by a colour or an animation alone.
 */

/** A rounded block on the surface: an icon, a bold title, the rest under it (the cards and lines of this part). */
@Composable
private fun Block(icon: ImageVector, title: String, tag: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val c = Belfry.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            content()
        }
    }
}

@Composable
private fun Said(text: String, muted: Boolean = false) {
    val c = Belfry.colors
    Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = if (muted) c.muted else c.text, modifier = Modifier.padding(top = 2.dp))
}

/** The part-5 state the screens read: the demo's or the runtime's; nothing on a belfry that isn't format 3 here. */
@Composable
internal fun rememberEnc3Device(): androidx.compose.runtime.State<Enc3DeviceState> = LocalSession.current.enc3Device.collectAsState()

/**
 * On top of Messages (encrypted/card, card-sharing, card-shared, card-remove, card-removed, card-answered, laptop-card;
 * edge-added-removed, edge-two-lists): the warnings that don't go by themselves, one card per device added to my list
 * (answered once for every device and conversation; no "Not now"), and the lines said once after a card went.
 * Readers' cards (an agent given access) are part 6.
 */
@Composable
internal fun Enc3DeviceCards(modifier: Modifier = Modifier) {
    val st by rememberEnc3Device()
    val session = LocalSession.current
    LaunchedEffect(st.on, st.cards.size) { if (st.on && st.cards.isNotEmpty()) try { session.enc3Names() } catch (e: Exception) { } }
    val nav = LocalNav.current
    if (st.on) Column(modifier) {
        if (st.fork) Block(BelfryIcons.Shield, EncDeviceText.FORK_TITLE, "enc3-fork", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Said(EncDeviceText.FORK_TEXT)
            DevicesLink { nav.go(Routes.DEVICES) }
        }
        if (st.strange) Block(BelfryIcons.Shield, EncDeviceText.STRANGE_TITLE, "enc3-strange") {
            Said(EncDeviceText.STRANGE_TEXT)
            DevicesLink { nav.go(Routes.DEVICES) }
        }
        for (card in st.cards) {
            // part 6 (SPEC 19.86, encrypted/edge-agent-card): my agent was given access
            if (card.reader) ReaderCard(card) else if (card.gone) GoneCard(card) else AddedCard(card)
        }
        for (n in st.notices) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(Belfry.colors.surface).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).testTag("enc3-notice")
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(BelfryIcons.Check, contentDescription = null, tint = Belfry.colors.text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(n, fontSize = 14.sp, lineHeight = 19.sp, color = Belfry.colors.text, modifier = Modifier.weight(1f))
                BusyButton(EncDeviceText.OK, { session.enc3NoticeSeen(n) }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("enc3-notice-ok"))
            }
        }
    }
}

@Composable
private fun DevicesLink(onClick: () -> Unit) {
    Row(Modifier.padding(top = 8.dp)) {
        BusyButton(EncDeviceText.YOUR_DEVICES, { onClick() }, kind = ButtonKind.Ghost, small = true, icon = BelfryIcons.Chevron, modifier = Modifier.testTag("enc3-your-devices"))
    }
}

/** One device added to my list: Share earlier messages (the button; progress while it works) and Remove this device (asks first). */
@Composable
private fun AddedCard(card: DeviceCard) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val share = rememberBusy()
    var progress by remember(card.device) { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember(card.device) { mutableStateOf<String?>(null) }
    var removing by remember(card.device) { mutableStateOf(false) }
    Block(BelfryIcons.Shield, EncDeviceText.cardTitle(card.name, card.at, session.now(), world.ctx.zone, rememberH24()), "enc3-card") {
        if (share.busy) {
            val p = progress
            Text(EncDeviceText.sharing(p?.first ?: 0, p?.second ?: 0), fontSize = 14.sp, lineHeight = 19.sp, color = Belfry.colors.text,
                modifier = Modifier.padding(top = 2.dp).testTag("enc3-sharing").semantics { liveRegion = LiveRegionMode.Polite })
        } else Said(EncDeviceText.CARD_TEXT)
        error?.let { Text(it, fontSize = 14.sp, color = Belfry.colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp).testTag("enc3-card-error")) }
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BusyButton(
                if (share.busy) EncDeviceText.SHARING else EncDeviceText.SHARE,
                onClick = {
                    error = null
                    try { session.enc3Share(card) { d, t -> progress = d to t } }
                    catch (e: Exception) { error = e.shown(EncDeviceText.FAILED) }
                    finally { progress = null }
                },
                busy = share, modifier = Modifier.fillMaxWidth().testTag("enc3-share"),
            )
            BusyButton(
                EncDeviceText.REMOVE_DEVICE, onClick = { error = null; removing = true }, kind = ButtonKind.Ghost, icon = BelfryIcons.Trash,
                enabled = !share.busy, modifier = Modifier.fillMaxWidth().testTag("enc3-remove"),
            )
        }
    }
    if (removing) Enc3RemoveSheet(Enc3Removing(card.device, card.name, false), onConfirm = {
        try { session.enc3Remove(card) } catch (e: Exception) { error = e.shown(EncDeviceText.FAILED) }
        removing = false
    }, onDismiss = { removing = false }, confirmTag = "enc3-remove-confirm")
}

/**
 * Part 6 (SPEC 19.86; §17.9; encrypted/edge-agent-card, the web's EarlierLine.tsx): my agent was given access to my private
 * messages, on all my devices: "‹agent› can now read your private messages", when, then OK (answered for every device, as
 * the web) and Remove (asks first; access off). No Share: an agent gets no earlier messages.
 */
@Composable
private fun ReaderCard(card: DeviceCard) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val name = world.displayName(card.device)
    val named = card.copy(name = name)
    var removing by remember(card.device) { mutableStateOf(false) }
    var error by remember(card.device) { mutableStateOf<String?>(null) }
    Block(BelfryIcons.User, EncDeviceText.readerTitle(name), "enc3-reader-card") {
        Said(EncDeviceText.time(card.at, session.now(), world.ctx.zone, rememberH24()), muted = true)
        error?.let { Text(it, fontSize = 14.sp, color = Belfry.colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp).testTag("enc3-reader-error")) }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BusyButton(EncDeviceText.OK, {
                error = null
                try { session.enc3ReaderOk(named) } catch (e: Exception) { error = e.shown(EncDeviceText.FAILED) }
            }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("enc3-reader-ok"))
            BusyButton(EncDeviceText.REMOVE, { error = null; removing = true }, kind = ButtonKind.Ghost, small = true, icon = BelfryIcons.Trash, modifier = Modifier.testTag("enc3-reader-remove"))
        }
    }
    if (removing) Enc3RemoveSheet(Enc3Removing(card.device, name, true), onConfirm = {
        try { session.enc3Remove(named) } catch (e: Exception) { error = e.shown(EncDeviceText.FAILED) }
        removing = false
    }, onDismiss = { removing = false }, confirmTag = "enc3-reader-remove-confirm")
}

/** encrypted/edge-added-removed: added and removed while this device was away. A notice, OK only. */
@Composable
private fun GoneCard(card: DeviceCard) {
    val session = LocalSession.current
    val world = LocalWorld.current
    Block(BelfryIcons.Shield, EncDeviceText.goneTitle(card.name), "enc3-gone") {
        Said(EncDeviceText.goneWhen(card.at, card.goneAt, session.now(), world.ctx.zone, rememberH24()), muted = true)
        Said(EncDeviceText.GONE_TEXT)
        Row(Modifier.padding(top = 8.dp)) {
            BusyButton(EncDeviceText.OK, { session.enc3GoneOk(card.device) }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("enc3-gone-ok"))
        }
    }
}

enum class EarlierWhere { LIST, THREAD }

/** A device's icon from the name its session gave itself (a guess for the eye only; the name says it in words). */
internal fun deviceIcon(name: String?): ImageVector {
    val n = name.orEmpty()
    return when {
        listOf("Mac", "Windows", "Linux", "Laptop", "Desktop").any { n.contains(it, ignoreCase = true) } -> BelfryIcons.Computer
        listOf("iPad", "Tablet", "Tab").any { n.contains(it, ignoreCase = true) } -> BelfryIcons.Tablet
        else -> BelfryIcons.Phone
    }
}

/**
 * A new device (encrypted/new-list, new-thread, new-asked, new-arrived, transition, lost-all, lost-all-thread): one line at
 * the bottom of Messages (LIST) and above an encrypted conversation's history (THREAD). It can't know which conversation a
 * message it can't open belongs to (the alias hides it), so it speaks generally.
 */
@Composable
internal fun Enc3EarlierLine(where: EarlierWhere) {
    val st by rememberEnc3Device()
    val dms = LocalDms.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val world = LocalWorld.current
    val v3 = st.demoMissing + dms.unreadableCount
    val v1 = st.demoV1Missing + dms.lockedCount
    val list = where == EarlierWhere.LIST
    val names = st.older.map { it.name }
    val by = st.sharedBy
    when {
        !st.on || !st.listed -> {}
        // encrypted/new-arrived: one quiet line in the conversation
        v3 + v1 == 0 -> if (!list && by != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("enc3-shared-by"), verticalAlignment = Alignment.CenterVertically) {
            Icon(BelfryIcons.Check, contentDescription = null, tint = Belfry.colors.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(EncDeviceText.sharedBy(by.name), fontSize = 13.sp, color = Belfry.colors.muted)
        }
        // lost every device: said once (OK in Messages), plainly
        st.older.isEmpty() -> if (!(list && st.lostOk)) Block(BelfryIcons.Lock, EncDeviceText.LOST_TITLE, "enc3-lost") {
            Said(EncDeviceText.LOST_TEXT)
            if (list) Row(Modifier.padding(top = 8.dp)) {
                BusyButton(EncDeviceText.OK, { session.enc3LostOk() }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("enc3-lost-ok"))
            }
        }
        st.askedAt != null -> Block(BelfryIcons.Clock, EncDeviceText.ASKED_TITLE, "enc3-asked", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Said(EncDeviceText.askedText(names))
            DevicesLink { nav.go(Routes.DEVICES) }
        }
        else -> Block(BelfryIcons.Lock, EncDeviceText.EARLIER_TITLE, "enc3-earlier") {
            if (v3 == 0) Said(EncDeviceText.transitionText(st.since3, world.ctx.zone, names)) // encrypted/transition
            else if (list) Said(EncDeviceText.earlierText(names))
            Row(Modifier.padding(top = 8.dp)) {
                BusyButton(EncDeviceText.ASK, { session.enc3Ask() }, kind = ButtonKind.Primary, small = true, modifier = Modifier.testTag("enc3-ask"))
            }
        }
    }
}

/**
 * encrypted/first-screen (§18.3): after sign-in on a format-3 belfry, the devices already on my list (and my agent), each
 * with the date it was added and Remove (asks first), then Continue. Over the app, once per device.
 */
@Composable
fun Enc3FirstScreenHost() {
    val st by rememberEnc3Device()
    val session = LocalSession.current
    if (st.on && st.firstScreen.isNotEmpty()) Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Belfry.colors.bg)) {
            Enc3FirstScreenContent(st.firstScreen, LocalWorld.current.ctx.zone, onRemove = { session.enc3RemoveFirst(it) }, onContinue = { session.enc3FirstScreenOk() })
        }
    }
}

@Composable
fun Enc3FirstScreenContent(rows: List<FirstRow>, zone: ZoneId, onRemove: suspend (FirstRow) -> Unit, onContinue: suspend () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    var removing by remember { mutableStateOf<FirstRow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp).testTag("enc3-first-screen")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            Icon(BelfryIcons.Shield, contentDescription = null, tint = c.text, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(14.dp))
            Text(EncDeviceText.FIRST_TITLE, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text(EncDeviceText.FIRST_TEXT, fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)) {
                rows.forEachIndexed { i, r ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(color = c.bg)
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp).testTag("enc3-first-row"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (r.reader) BelfryIcons.User else deviceIcon(r.name), contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            val name = if (r.reader) world.face(r.device).name else r.name ?: EncDeviceText.NEW_DEVICE
                            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                            Text(if (r.reader) EncDeviceText.agentSince(r.addedAt, zone) else EncDeviceText.added(r.addedAt, zone), fontSize = 13.sp, color = c.muted)
                        }
                        BusyButton(EncDeviceText.REMOVE, { error = null; removing = r }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("enc3-first-remove"))
                    }
                }
            }
            error?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(top = 10.dp)) }
        }
        BusyButton(EncDeviceText.CONTINUE, onContinue, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("enc3-first-continue"))
    }
    removing?.let { r ->
        val name = if (r.reader) world.face(r.device).name else r.name
        Enc3RemoveSheet(Enc3Removing(r.device, name, r.reader), onConfirm = {
            try { onRemove(r) } catch (e: Exception) { error = e.shown(EncDeviceText.FAILED) }; removing = null
        }, onDismiss = { removing = null }, confirmTag = "enc3-first-remove-confirm")
    }
}

/**
 * encrypted/removed-device (§16.6, §26): this account was wiped here because another of my devices removed this one. One
 * full screen: by which device and when (when the belfry said), Sign in again.
 */
@Composable
fun RemovedDeviceScreen(r: RemovedHere, now: Long, zone: ZoneId, onSignIn: () -> Unit) {
    val c = Belfry.colors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp).testTag("enc3-removed-device"), verticalArrangement = Arrangement.Center) {
        Icon(BelfryIcons.Phone, contentDescription = null, tint = c.text, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(16.dp))
        Text(EncDeviceText.REMOVED_TITLE, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(10.dp))
        Text(EncDeviceText.removedText(r, now, zone, rememberH24()), fontSize = 16.sp, lineHeight = 23.sp, color = c.text)
        Spacer(Modifier.height(24.dp))
        BusyButton(EncDeviceText.SIGN_IN_AGAIN, { onSignIn() }, modifier = Modifier.fillMaxWidth().testTag("enc3-sign-in-again"))
    }
}

/**
 * encrypted/signout-only: on format 3, signing out of my only device loses the earlier messages: said before it happens.
 * Add another device first (Link a phone or tablet), Sign out anyway (shows progress), Cancel.
 */
@Composable
fun Enc3OnlyDeviceDialog(onAdd: () -> Unit, onAnyway: suspend () -> Unit, onDismiss: () -> Unit) {
    val busy = rememberBusy()
    val c = Belfry.colors
    AlertDialog(
        onDismissRequest = { if (!busy.busy) onDismiss() },
        title = { Text(EncDeviceText.ONLY_TITLE, modifier = Modifier.testTag("enc3-only-device")) },
        text = {
            Column {
                Text(EncDeviceText.ONLY_TEXT, fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
                Spacer(Modifier.height(8.dp))
                Text(EncDeviceText.ONLY_NEW, fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BusyButton(EncDeviceText.ONLY_ADD, onClick = { onAdd() }, modifier = Modifier.fillMaxWidth().testTag("enc3-only-add"), enabled = !busy.busy)
                BusyButton(EncDeviceText.ONLY_ANYWAY, onClick = onAnyway, modifier = Modifier.fillMaxWidth().testTag("enc3-only-anyway"), kind = ButtonKind.Danger, busy = busy)
                BusyButton(EncDeviceText.CANCEL, onClick = { onDismiss() }, modifier = Modifier.fillMaxWidth().testTag("enc3-only-cancel"), kind = ButtonKind.Ghost, enabled = !busy.busy)
            }
        },
        containerColor = c.bg,
    )
}
