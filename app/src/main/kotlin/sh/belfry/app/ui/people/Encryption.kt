package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Chip
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.AccessAsk
import sh.belfry.core.model.ConvSeal
import sh.belfry.core.model.DeviceKind
import sh.belfry.core.model.DeviceNotice
import sh.belfry.core.model.MyDevice
import sh.belfry.core.model.SealedText
import sh.belfry.core.model.World

/*
 * End-to-end encrypted conversations (canvas "Messages · encryption", approved 2026-11-20):
 * screens only, filled by the demo (core/engine/Encryption.kt). Never a key, npub or id as text;
 * only my own devices are named.
 */

internal fun DeviceKind.icon(): ImageVector = when (this) {
    DeviceKind.PHONE -> BelfryIcons.Phone
    DeviceKind.TABLET -> BelfryIcons.Tablet
    DeviceKind.COMPUTER -> BelfryIcons.Computer
}

/** The small lock by a sealed conversation's name: opens its Encryption screen. */
@Composable
internal fun SealLock(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Encryption" }
            .testTag("seal-lock"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BelfryIcons.Lock, contentDescription = null, tint = Belfry.colors.muted, modifier = Modifier.size(16.dp))
    }
}

/**
 * A device line in a sealed thread, in the style of a channel's join line. Others' lines name no
 * device; mine do. The amber one (a new device that can't read earlier messages) opens the
 * share sheet, the others the Encryption screen.
 */
@Composable
internal fun DeviceNoticeRow(n: DeviceNotice, world: World, onClick: () -> Unit) {
    val c = Belfry.colors
    val mine = n.who == world.me
    val amber = n.kind == DeviceNotice.Kind.NEW_LOCKED
    val text = SealedText.notice(n, if (mine) "You" else world.displayName(n.who), mine)
    val tint = if (amber) c.orange else c.faint
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(if (amber) "seal-notice-amber" else "seal-notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BelfryIcons.Lock, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, lineHeight = 17.sp, color = if (amber) c.orange else c.muted, fontWeight = if (amber) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Icon(BelfryIcons.Chevron, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
    }
}

/** One row of the Encryption screen's groups: a face or an icon, two lines, something at the end. */
@Composable
private fun SealRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    leading: @Composable () -> Unit,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Belfry.colors
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier.fillMaxWidth().background(c.surface).then(click).defaultMinSize(minHeight = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Chip(badge)
                }
            }
            if (!sub.isNullOrEmpty()) Text(sub, fontSize = 13.sp, lineHeight = 17.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
private fun DeviceIcon(kind: DeviceKind) {
    Icon(kind.icon(), contentDescription = null, tint = Belfry.colors.muted, modifier = Modifier.size(22.dp))
}

@Composable
private fun MyDeviceRows(devices: List<MyDevice>, now: Long, world: World) {
    devices.forEach { d ->
        SealRow(
            d.name, sub = SealedText.addedLine(d, now, world.ctx.zone), badge = if (d.thisDevice) "This device" else null,
            leading = { DeviceIcon(d.kind) }, modifier = Modifier.testTag("seal-my-device"),
        )
    }
}

@Composable
private fun Sentence(icon: ImageVector, text: String) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = c.muted, modifier = Modifier.weight(1f))
    }
}

/** messages/encryption-devices (+ -group, -email): a pushed screen opened by the lock or a device line. */
@Composable
fun EncryptionScreen(conv: String) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val st by session.encryption.state.collectAsState()
    val seal = st.seal(conv)
    androidx.compose.runtime.LaunchedEffect(conv) { try { session.encryption.refresh() } catch (e: Exception) { } }
    Page("Encryption") {
        if (seal == null) {
            Hint("This conversation isn't locked.")
        } else {
            EncryptionContent(seal, st.myDevices, world, session.now())
        }
    }
}

/** The body of the Encryption screen (tests render it with the demo's people). */
@Composable
fun EncryptionContent(seal: ConvSeal, mine: List<MyDevice>, world: World, now: Long) {
    val c = Belfry.colors
    val people = seal.others.size + seal.mail.size
    val group = people > 1
    var open by rememberSaveable { mutableStateOf(true) }
    Text(SealedText.ONLY_THESE, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.testTag("seal-title"))
    if (group && seal.mail.isEmpty()) {
        Text(
            SealedText.peopleLine(people + 1, mine.size + seal.others.sumOf { it.count }), fontSize = 14.sp, color = c.muted,
            modifier = Modifier.padding(top = 4.dp).testTag("seal-people"),
        )
    }
    val me = world.me
    if (group) {
        SectionTitle("You")
        ActionGroup {
            SealRow(
                world.face(me).name + " (you)", leading = { Avatar(world.face(me), size = 36.dp) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(SealedText.devices(mine.size), fontSize = 14.sp, color = c.muted)
                        Spacer(Modifier.width(6.dp))
                        Icon(if (open) BelfryIcons.ChevronUp else BelfryIcons.ChevronDown, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
                    }
                },
                onClick = { open = !open }, modifier = Modifier.testTag("seal-me"),
            )
            if (open) MyDeviceRows(mine, now, world)
        }
    } else {
        Spacer(Modifier.height(16.dp))
        ActionGroup {
            SealRow(world.face(me).name + " (you)", leading = { Avatar(world.face(me), size = 36.dp) }, modifier = Modifier.testTag("seal-me"))
            MyDeviceRows(mine, now, world)
        }
    }
    if (people > 0) {
        SectionTitle("Others")
        ActionGroup {
            seal.others.sortedBy { world.displayName(it.pubkey).lowercase() }.forEach { p ->
                val change = p.change?.takeIf { SealedText.recent(it, now) }
                SealRow(
                    world.displayName(p.pubkey), sub = change?.let { SealedText.changeLine(it, now, world.ctx.zone) },
                    leading = { Avatar(world.face(p.pubkey), size = 36.dp) },
                    trailing = { Text(SealedText.devices(p.count), fontSize = 14.sp, color = c.muted) },
                    modifier = Modifier.testTag("seal-person"),
                )
            }
            seal.mail.forEach { name ->
                SealRow(
                    "$name · by e-mail", sub = SealedText.NOT_HERE,
                    leading = { Icon(BelfryIcons.Mail, contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp)) },
                    modifier = Modifier.testTag("seal-mail"),
                )
            }
        }
        if (seal.others.isNotEmpty()) Hint(SealedText.COUNTS_ONLY, Modifier.padding(top = 8.dp).testTag("seal-counts-only"))
    }
    Spacer(Modifier.height(10.dp))
    Sentence(BelfryIcons.Shield, SealedText.SERVER)
    Sentence(BelfryIcons.Lock, if (group) SealedText.LOCK_CHANGES_GROUP else SealedText.LOCK_CHANGES)
    if (seal.mail.isNotEmpty()) Box(Modifier.testTag("seal-mail-sentence")) { Sentence(BelfryIcons.Mail, SealedText.MAIL) }
}

/** messages/encryption-new-device: the amber banner at the top of Messages on a new device. */
@Composable
internal fun EarlierLockedBanner(onShowHow: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.orange.copy(alpha = if (c.isDark) 0.18f else 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("seal-banner"),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(BelfryIcons.Lock, contentDescription = null, tint = c.orange, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(SealedText.BANNER_TITLE, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(SealedText.BANNER_TEXT, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.padding(top = 2.dp))
            Text(
                "Show how", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.orange,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onShowHow)
                    .padding(vertical = 6.dp).testTag("seal-show-how"),
            )
        }
    }
}

/** "Show how": unlock with another device, three steps (a pushed screen). */
@Composable
fun UnlockStepsScreen() {
    Page("Unlock with another device") { UnlockStepsContent() }
}

@Composable
fun UnlockStepsContent() {
    val c = Belfry.colors
    SealedText.UNLOCK_STEPS.forEachIndexed { i, step ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                Text("${i + 1}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            Spacer(Modifier.width(12.dp))
            Text(step, fontSize = 16.sp, lineHeight = 22.sp, color = c.text, modifier = Modifier.weight(1f).padding(top = 3.dp))
        }
    }
    Hint(SealedText.UNLOCK_NONE, Modifier.padding(top = 12.dp))
}

/**
 * messages/encryption-allow: on my other devices, over whatever is open, a new device of mine
 * asks for access. Allow / Not me lock together while working; the first device to answer wins
 * (the ask then leaves the state and the dialog goes). Ignored, it comes back next time.
 */
@Composable
fun SealAskHost() {
    val session = LocalSession.current
    val st by session.encryption.state.collectAsState()
    val ask = st.asks.firstOrNull() ?: return
    var gone by remember(ask.id) { mutableStateOf(false) }
    if (gone) return
    val nav = LocalNav.current
    AccessAskDialog(ask, LocalWorld.current.face(session.me).name, session.now(), onDismiss = { gone = true },
        onAllow = { session.encryption.allow(ask.id) }, onNotMe = { session.encryption.notMe(ask.id) }, onDevices = { nav.go(Routes.DEVICES) })
}

@Composable
fun AccessAskDialog(
    ask: AccessAsk, myName: String, now: Long, onDismiss: () -> Unit, onAllow: suspend () -> Unit, onNotMe: suspend () -> Unit,
    onDevices: () -> Unit = {}, startFull: Boolean = false,
) {
    val c = Belfry.colors
    val busy = rememberBusy()
    var error by remember { mutableStateOf<String?>(null) }
    // canvas v90 / sealed.md §5.2: at most 20 devices; the new device stays locked
    var full by remember { mutableStateOf(startFull) }
    if (full) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(ask.kind.icon(), contentDescription = null, tint = c.text) },
            title = { Text(SealedText.askTitle(ask.device)) },
            text = { Text(SealedText.TOO_MANY, fontSize = 15.sp, lineHeight = 21.sp, color = c.text, modifier = Modifier.testTag("seal-too-many")) },
            confirmButton = { BusyButton("OK", onClick = { onDismiss() }, small = true) },
            dismissButton = { BusyButton("Devices", onClick = { onDismiss(); onDevices() }, small = true, kind = ButtonKind.Ghost, modifier = Modifier.testTag("seal-too-many-devices")) },
            containerColor = c.bg,
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy.busy) onDismiss() },
        icon = { Icon(ask.kind.icon(), contentDescription = null, tint = c.text) },
        title = { Text(SealedText.askTitle(ask.device), modifier = Modifier.testTag("seal-ask")) },
        text = {
            Column {
                Text(SealedText.ASK_TEXT, fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
                Text("Signed in as $myName · ${SealedText.ago(ask.at, now)}", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                Text(SealedText.ASK_NOT_ME, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                ErrorLine(error)
            }
        },
        confirmButton = {
            BusyButton(
                "Allow", onClick = { onAllow() }, busy = busy, small = true,
                onError = { if ((it as? sh.belfry.core.engine.ActionException)?.code == "too_many_devices") full = true else error = it.shown() },
                modifier = Modifier.testTag("seal-allow"),
            )
        },
        dismissButton = {
            BusyButton("Not me", onClick = { onNotMe() }, busy = busy, small = true, kind = ButtonKind.Ghost, onError = { error = it.shown() }, modifier = Modifier.testTag("seal-not-me"))
        },
        containerColor = c.bg,
    )
}

/**
 * messages/encryption-share-history: from the amber line, share the conversation's history with
 * someone's new device that nobody of theirs could unlock. Share history / Not now lock together.
 */
@Composable
internal fun ShareHistorySheet(conv: String, seal: ConvSeal, onClose: () -> Unit) {
    val session = LocalSession.current
    val world = LocalWorld.current
    BelfrySheet(onDismiss = onClose) {
        ShareHistoryContent(seal, world, session.now(), onShare = { session.encryption.shareHistory(conv); onClose() }, onNotNow = onClose)
    }
}

@Composable
fun ShareHistoryContent(seal: ConvSeal, world: World, now: Long, onShare: suspend () -> Unit, onNotNow: () -> Unit) {
    val c = Belfry.colors
    val d = seal.waiting ?: return
    val name = world.displayName(d.pubkey)
    val group = seal.others.size + seal.mail.size > 1
    val busy = rememberBusy()
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("seal-share")) {
        Text(SealedText.shareTitle(name, group), fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Text(SealedText.shareBody(name), fontSize = 15.sp, lineHeight = 21.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        ActionGroup {
            SealRow(SealedText.newDeviceLine(d, now, world.ctx.zone), leading = { DeviceIcon(d.kind ?: DeviceKind.PHONE) })
        }
        ErrorLine(error)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BusyButton("Share history", onClick = onShare, busy = busy, onError = { error = it.shown() }, modifier = Modifier.fillMaxWidth().testTag("seal-share-go"))
            BusyButton("Not now", onClick = { onNotNow() }, busy = busy, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** messages/encryption-link-qr: inside Link a phone or tablet, once the new device is in. */
@Composable
fun LinkedContent(device: String, onDone: () -> Unit) {
    val c = Belfry.colors
    Column(Modifier.fillMaxWidth().padding(top = 32.dp).testTag("seal-linked"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            Icon(BelfryIcons.Check, contentDescription = null, tint = c.text, modifier = Modifier.size(30.dp))
        }
        Text("$device is linked", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(top = 16.dp))
        Text(SealedText.LINKED_TEXT, fontSize = 15.sp, lineHeight = 21.sp, color = c.text, modifier = Modifier.padding(top = 8.dp))
        Text(SealedText.LINKED_CONFIRM, fontSize = 14.sp, lineHeight = 20.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        BusyButton("Done", onClick = { onDone() }, modifier = Modifier.fillMaxWidth().testTag("seal-linked-done"))
    }
}

/** Opens a device line: the amber one → the share sheet (while it is still waiting), else Encryption. */
@Composable
internal fun rememberNoticeOpener(conv: String): (DeviceNotice) -> Unit {
    val nav = LocalNav.current
    val session = LocalSession.current
    val st by session.encryption.state.collectAsState()
    val seal = st.seal(conv)
    var sharing by remember(conv) { mutableStateOf(false) }
    if (sharing && seal?.waiting != null) ShareHistorySheet(conv, seal, onClose = { sharing = false })
    return { n -> if (n.kind == DeviceNotice.Kind.NEW_LOCKED && seal?.waiting != null) sharing = true else nav.go(Routes.encryption(conv)) }
}

/** The amber block in a conversation this device can't read yet (canvas messages/encryption-locked-thread). */
internal object LockedHere

@Composable
internal fun LockedBlock(since: Long?, world: World, now: Long, onShowHow: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.orange.copy(alpha = if (c.isDark) 0.18f else 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("seal-locked-block"),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(BelfryIcons.Lock, contentDescription = null, tint = c.orange, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(SealedText.LOCKED_BLOCK, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            if (since != null) {
                Text(SealedText.lockedFrom(since, now, world.ctx.zone), fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                "Show how", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.orange,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onShowHow).padding(vertical = 6.dp),
            )
        }
    }
}

/** canvas messages/encryption-shared-history: the grey line above a block someone shared with this device. */
@Composable
internal fun SharedByRow(b: sh.belfry.core.model.SharedBlock, world: World, onClick: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).testTag("seal-shared-by"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BelfryIcons.Share, contentDescription = null, tint = c.faint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(SealedText.sharedBy(world.displayName(b.by)), fontSize = 13.sp, color = c.muted, modifier = Modifier.weight(1f))
        Icon(BelfryIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(14.dp))
    }
}

/** A shared message: the bubble with a subtle rule on its left. */
@Composable
internal fun SharedRule(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(start = 12.dp).testTag("seal-shared-message")) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(Belfry.colors.line))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** canvas messages/encryption-shared-history-why: one sentence, OK closes it. */
@Composable
internal fun SharedWhySheet(b: sh.belfry.core.model.SharedBlock, world: World, onClose: () -> Unit) {
    BelfrySheet(onDismiss = onClose) { SharedWhyContent(world.displayName(b.by), onClose) }
}

@Composable
fun SharedWhyContent(name: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("seal-shared-why")) {
        Text(SealedText.sharedWhy(name), fontSize = 16.sp, lineHeight = 22.sp, color = Belfry.colors.text)
        Spacer(Modifier.height(16.dp))
        BusyButton("OK", onClick = { onClose() }, modifier = Modifier.fillMaxWidth())
    }
}

/** canvas v89: open lock + "Not sealed" after the time of a message that came the old way. */
@Composable
internal fun NotSealedMark(onClick: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 2.dp, vertical = 1.dp).testTag("seal-not-sealed"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BelfryIcons.Unlock, contentDescription = null, tint = c.muted, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text(SealedText.NOT_SEALED, fontSize = 11.sp, color = c.muted)
    }
}

@Composable
internal fun NotSealedSheet(onClose: () -> Unit) {
    BelfrySheet(onDismiss = onClose) { NotSealedContent(onClose) }
}

@Composable
fun NotSealedContent(onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("seal-not-sealed-why")) {
        Text(SealedText.NOT_SEALED_WHY, fontSize = 16.sp, lineHeight = 22.sp, color = Belfry.colors.text)
        Spacer(Modifier.height(16.dp))
        BusyButton("OK", onClick = { onClose() }, modifier = Modifier.fillMaxWidth())
    }
}
