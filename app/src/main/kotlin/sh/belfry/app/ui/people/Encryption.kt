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
            d.name, sub = SealedText.addedLine(d, now, world.ctx.zone, rememberH24()), badge = if (d.thisDevice) "This device" else null,
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
    val nav = LocalNav.current
    val waiting = st.earlierLocked || st.waitingKeys
    Page("Encryption") {
        AddedDeviceCards()
        // a device not added yet: say so first, who can open it and where things stand (canvas Messages v5
        // messages/encryption-devices-waiting); added, the key on its way: "Added … · open here in a moment"
        if (st.earlierLocked) {
            EarlierLockedBanner(SealedText.CANT_READ_YET, st) { nav.go(Routes.UNLOCK_STEPS) }
            Spacer(Modifier.height(8.dp))
        } else if (st.waitingKeys) {
            AddedHereBanner(st)
            Spacer(Modifier.height(8.dp))
        }
        when {
            seal != null -> EncryptionContent(seal, st.myDevices, world, session.now())
            waiting -> {
                // this device first, "Not allowed yet · asked …", then my others (canvas v182)
                if (st.myDevices.isNotEmpty()) {
                    SectionTitle("Your devices")
                    ActionGroup { MyDeviceRows(st.myDevices, session.now(), world) }
                }
                Sentence(BelfryIcons.Shield, SealedText.SERVER)
                if (sh.belfry.core.mail.MailThreads.isMail(conv)) Sentence(BelfryIcons.Mail, SealedText.MAIL)
            }
            else -> Hint("This conversation isn't locked.")
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

/** The device's own clock: 24-hour or AM / PM, as set in Android. */
@Composable
internal fun rememberH24(): Boolean = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)

/** A sentence whose device names are set in bold ("Open **Chat · Safari on Mac**. …"): never colour alone. */
@Composable
internal fun NamedText(parts: List<Pair<String, Boolean>>, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp, color: androidx.compose.ui.graphics.Color = Belfry.colors.text) {
    val text = androidx.compose.ui.text.buildAnnotatedString {
        for ((t, name) in parts) {
            if (name) { pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)); append(t); pop() } else append(t)
        }
    }
    Text(text, fontSize = fontSize, lineHeight = (fontSize.value + 5).sp, color = color, modifier = modifier)
}

/**
 * What every waiting view says (canvas Messages v5, note n-e2e-locked-named): which device holds the
 * key, by its session name; where things stand ("Asked 9:40 PM · waiting for one of your devices");
 * that there is nothing to approve here; and "No other device?" (the way to Start fresh).
 */
@Composable
internal fun WaitingLines(st: sh.belfry.core.model.SealState, onNoOther: (() -> Unit)?) {
    val c = Belfry.colors
    val session = LocalSession.current
    val world = LocalWorld.current
    NamedText(SealedText.openParts(st.unlockers), Modifier.padding(top = 2.dp).testTag("seal-open-device"))
    Text(
        SealedText.askedLine(st.askedAt, session.now(), world.ctx.zone, rememberH24()), fontSize = 14.sp, lineHeight = 19.sp,
        fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(top = 6.dp).testTag("seal-asked"),
    )
    Text(SealedText.NOTHING_TO_APPROVE, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.padding(top = 2.dp))
    if (onNoOther != null) NoOtherLink(onNoOther)
}

@Composable
private fun NoOtherLink(onClick: () -> Unit) {
    Text(
        SealedText.NO_OTHER_LINK, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.orange,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp).testTag("seal-show-how"),
    )
}

/**
 * messages/encryption-new-device (+ -waiting-list, -devices-waiting): the amber banner on a device not
 * added yet. With STATE (this device waits): the named device, the state line, nothing to approve.
 * Without (in my head, an older key missing): the plain sentence.
 */
@Composable
internal fun EarlierLockedBanner(title: String = SealedText.BANNER_TITLE, state: sh.belfry.core.model.SealState? = null, onShowHow: () -> Unit) {
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
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            if (state != null) WaitingLines(state, onShowHow) else {
                Text(SealedText.BANNER_TEXT, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.padding(top = 2.dp))
                NoOtherLink(onShowHow)
            }
        }
    }
}

/** messages/encryption-locked-added: this device was added, its key is on the way. Goes by itself. */
@Composable
internal fun AddedHereBanner(st: sh.belfry.core.model.SealState) {
    val c = Belfry.colors
    val session = LocalSession.current
    val world = LocalWorld.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp).testTag("seal-added-here"),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(BelfryIcons.Shield, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(SealedText.addedHereTitle(st.addedAt, session.now(), world.ctx.zone, rememberH24()), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(SealedText.ADDED_HERE_TEXT, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * canvas Messages v8 messages/encryption-waiting-device (sealed.md §5.7): on an older device, the newer messages
 * wait for the device that added itself. A quiet line (a clock, no button); everything else works. N: how many
 * messages wait, on top of Messages; null: the line inside a conversation.
 */
@Composable
internal fun WaitingForLine(st: sh.belfry.core.model.SealState, n: Int? = null, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("seal-waiting-for"), verticalAlignment = Alignment.Top) {
        Icon(BelfryIcons.Clock, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (n != null) SealedText.waitingForMessages(n, st.waitingFor) else SealedText.waitingFor(st.waitingFor), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            if (n != null) Text(SealedText.WAITING_FOR_TEXT, fontSize = 14.sp, lineHeight = 19.sp, color = c.muted)
        }
    }
}

/** messages/encryption-waiting: in place of the composer while this device, added, waits for its key. */
@Composable
internal fun WaitingKeysLines(st: sh.belfry.core.model.SealState, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val session = LocalSession.current
    val world = LocalWorld.current
    Column(modifier) {
        Text(SealedText.askedLine(st.askedAt, session.now(), world.ctx.zone, rememberH24()), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        NamedText(SealedText.openParts(st.unlockers, short = true), Modifier.padding(top = 2.dp), color = c.muted)
    }
}

/**
 * canvas v183 messages/encryption-device-added (sealed.md §5.2 step 2a): a device of mine added without
 * a prompt here. One card per device: "‹name› was added just now · It can read your conversations",
 * then "Not you? Remove it" (asks first; sign out + the lock changes) and "It was me". Stays until answered.
 */
@Composable
internal fun AddedDeviceCards(modifier: Modifier = Modifier) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val st by session.encryption.state.collectAsState()
    if (st.added.isEmpty() && st.removedJoinerAt == 0L) return
    val c = Belfry.colors
    var removing by remember { mutableStateOf<sh.belfry.core.model.AddedDevice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        if (st.removedJoinerAt > 0L) {
            // sealed.md §5.7: a device that added itself with no session, removed by this device at once
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp).testTag("seal-removed-joiner"),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(BelfryIcons.Shield, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp).padding(top = 1.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(SealedText.removedJoinerTitle(st.removedJoinerAt, session.now(), world.ctx.zone), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        Text(SealedText.REMOVED_JOINER_TEXT, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    BusyButton(SealedText.REMOVED_JOINER_OK, onClick = { session.encryption.removedJoinerSeen() }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("seal-removed-joiner-ok"))
                }
            }
        }
        for (a in st.added) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp).testTag("seal-added"),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(BelfryIcons.Shield, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp).padding(top = 1.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(SealedText.addedTitle(a.name, a.at, session.now(), world.ctx.zone), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        Text(SealedText.ADDED_TEXT, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BusyButton(SealedText.ADDED_REMOVE, onClick = { error = null; removing = a }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.weight(1f).testTag("seal-added-remove"))
                    BusyButton(SealedText.ADDED_MINE, onClick = { session.encryption.addedWasMe(a.id) }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.weight(1f).testTag("seal-added-mine"))
                }
            }
        }
        error?.let { Hint(it, Modifier.padding(horizontal = 16.dp)) }
    }
    removing?.let { a ->
        sh.belfry.app.ui.kit.ConfirmSheet(
            title = "Remove ${a.name}?", confirmLabel = "Remove",
            onConfirm = {
                try { session.encryption.removeAdded(a.id) } catch (e: sh.belfry.core.engine.ActionException) { error = e.message }
                removing = null
            },
            onDismiss = { removing = null },
            message = SealedText.REMOVE_TEXT,
        )
    }
}

/** "No other device?" (was "Show how"): the device that can open this one, sharing, and Start fresh (a pushed screen). */
@Composable
fun UnlockStepsScreen() {
    val session = LocalSession.current
    val nav = LocalNav.current
    val st by session.encryption.state.collectAsState()
    var confirming by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page(SealedText.UNLOCK_TITLE) {
        UnlockStepsContent(canStartFresh = st.earlierLocked, onNoOther = { error = null; confirming = true }, unlockers = st.unlockers)
        if (error != null) Text(error!!, fontSize = 14.sp, color = Belfry.colors.danger, modifier = Modifier.padding(top = 10.dp).testTag("seal-fresh-error"))
    }
    if (confirming) {
        sh.belfry.app.ui.kit.ConfirmSheet(
            title = SealedText.START_FRESH_TITLE,
            confirmLabel = SealedText.START_FRESH,
            message = SealedText.START_FRESH_TEXT + "\n\n" + SealedText.START_FRESH_SEEN,
            onConfirm = {
                try {
                    session.encryption.startFresh()
                    confirming = false
                    nav.back()
                } catch (e: sh.belfry.core.engine.ActionException) {
                    error = e.message
                    confirming = false
                }
            },
            onDismiss = { confirming = false },
            confirmTag = "seal-fresh-confirm",
        )
    }
}

/**
 * canvas Messages v5 messages/encryption-start-fresh: with another device (named; nothing to approve,
 * no Allow step), the people I write to can share, and (on a device not in my head) "I don't have
 * another device · Start fresh on this device": the only way in to Start fresh.
 */
@Composable
fun UnlockStepsContent(canStartFresh: Boolean = false, onNoOther: () -> Unit = {}, unlockers: List<String> = emptyList()) {
    SectionTitle(SealedText.UNLOCK_WITH)
    NamedText(SealedText.unlockParts(unlockers), Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("seal-unlock-with"), fontSize = 16.sp)
    Hint(SealedText.UNLOCK_NONE, Modifier.padding(top = 12.dp))
    if (canStartFresh) {
        Spacer(Modifier.height(18.dp))
        ActionGroup {
            sh.belfry.app.ui.kit.ActionRow(
                SealedText.NO_OTHER, onClick = onNoOther, sub = SealedText.START_FRESH_ROW, danger = true,
                modifier = Modifier.testTag("seal-no-other"),
            )
        }
    }
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
    onDevices: () -> Unit = {}, startFull: Boolean = false, startWaiting: Boolean = false,
) {
    val c = Belfry.colors
    val busy = rememberBusy()
    var error by remember { mutableStateOf<String?>(null) }
    // canvas v90 / sealed.md §5.2: at most 20 devices; the new device stays locked
    var full by remember { mutableStateOf(startFull) }
    // canvas v96: allowed only once another of my devices hands the key over; stays open until done
    var waitingOther by remember { mutableStateOf(startWaiting) }
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
            if (waitingOther) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("seal-allow-waiting")) {
                    sh.belfry.app.ui.kit.Spinner(size = 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(SealedText.ALLOW_WAITING, fontSize = 13.sp, color = c.muted)
                }
            } else BusyButton(
                "Allow", onClick = { onAllow() }, busy = busy, small = true,
                onError = {
                    when ((it as? sh.belfry.core.engine.ActionException)?.code) {
                        "too_many_devices" -> full = true
                        "waiting_other" -> waitingOther = true
                        else -> error = it.shown()
                    }
                },
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
internal fun LockedBlock(state: sh.belfry.core.model.SealState, onShowHow: () -> Unit) {
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
            // who can open it and where things stand (canvas Messages v5 messages/encryption-locked-thread)
            WaitingLines(state, onShowHow)
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

/**
 * canvas v117 messages/encryption-only-device (note n-e2e-only-device): this device is the only one
 * in my roster and I'm about to lose it (sign out). A Material dialog in the app, as it blocks a step
 * that loses data: Link a device (opens Link a phone), <Sign out> anyway (red, shows progress), Cancel.
 */
@Composable
fun OnlyDeviceDialog(how: SealedText.Leaving, onLink: () -> Unit, onAnyway: suspend () -> Unit, onDismiss: () -> Unit) {
    val busy = rememberBusy()
    val c = Belfry.colors
    AlertDialog(
        onDismissRequest = { if (!busy.busy) onDismiss() },
        title = { Text(SealedText.ONLY_DEVICE_TITLE, modifier = Modifier.testTag("seal-only-device")) },
        text = {
            Column {
                Text(SealedText.onlyDeviceText(how), fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
                Spacer(Modifier.height(8.dp))
                Text(SealedText.ONLY_DEVICE_KEEP, fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
            }
        },
        // three stacked buttons, the safe one first (canvas v117)
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BusyButton(SealedText.ONLY_DEVICE_LINK, onClick = { onLink() }, modifier = Modifier.fillMaxWidth().testTag("seal-only-link"), kind = ButtonKind.Primary, enabled = !busy.busy)
                BusyButton(how.anyway, onClick = onAnyway, modifier = Modifier.fillMaxWidth().testTag("seal-only-anyway"), kind = ButtonKind.Danger, busy = busy)
                BusyButton("Cancel", onClick = { onDismiss() }, modifier = Modifier.fillMaxWidth().testTag("seal-only-cancel"), kind = ButtonKind.Ghost, enabled = !busy.busy)
            }
        },
        containerColor = c.bg,
    )
}

/**
 * An old device of mine, still signed in, after my sealed conversations were started fresh on another
 * device (canvas v122 messages/encryption-fresh-elsewhere + -done, approved by the lead): never quiet,
 * since it is also what someone who got into the account would do. A Material dialog over any screen,
 * back at every start until a button is tapped; no Cancel, no tap-outside.
 */
@Composable
fun FreshElsewhereHost() {
    val session = LocalSession.current
    val nav = LocalNav.current
    val st by session.encryption.state.collectAsState()
    var done by remember { mutableStateOf<List<String>?>(null) }
    val d = done
    if (d != null) {
        FreshDoneDialog(d, onShowHow = { done = null; nav.go(Routes.UNLOCK_STEPS) }, onDone = { done = null })
        return
    }
    val f = st.freshElsewhere ?: return
    FreshElsewhereDialog(f, session.now(), LocalWorld.current.ctx.zone, onSignOut = { done = session.encryption.signOutOthers() }, onMine = { session.encryption.freshElsewhereMine() })
}

@Composable
fun FreshElsewhereDialog(f: sh.belfry.core.model.FreshElsewhere, now: Long, zone: java.time.ZoneId, onSignOut: suspend () -> Unit, onMine: () -> Unit) {
    val c = Belfry.colors
    val busy = rememberBusy()
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = {}, // answered, not dismissed
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Column(Modifier.testTag("seal-fresh-elsewhere")) {
                Text(SealedText.freshElsewhereTitle(f.device))
                Text(SealedText.freshElsewhereWhen(f.at, now, zone), fontSize = 14.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp))
            }
        },
        text = {
            Column {
                Text(SealedText.FRESH_ELSEWHERE_BODY, fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
                Spacer(Modifier.height(8.dp))
                Text(SealedText.FRESH_ELSEWHERE_BOLD, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BusyButton(
                    SealedText.FRESH_ELSEWHERE_SIGN_OUT,
                    onClick = { error = null; try { onSignOut() } catch (e: sh.belfry.core.engine.ActionException) { error = SealedText.FRESH_ELSEWHERE_FAILED } },
                    modifier = Modifier.fillMaxWidth().testTag("seal-fresh-elsewhere-signout"), kind = ButtonKind.Danger, busy = busy,
                )
                if (error != null) Text(error!!, fontSize = 14.sp, color = c.danger, modifier = Modifier.testTag("seal-fresh-elsewhere-error"))
                BusyButton(SealedText.FRESH_ELSEWHERE_ME, onClick = { onMine() }, modifier = Modifier.fillMaxWidth().testTag("seal-fresh-elsewhere-me"), kind = ButtonKind.Ghost, enabled = !busy.busy)
            }
        },
        containerColor = c.bg,
    )
}

/** canvas v122 messages/encryption-fresh-elsewhere-done: the same dialog, replaced. */
@Composable
fun FreshDoneDialog(names: List<String>, onShowHow: () -> Unit, onDone: () -> Unit, email: String? = null) {
    val c = Belfry.colors
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(SealedText.FRESH_DONE_TITLE, modifier = Modifier.testTag("seal-fresh-elsewhere-done")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(SealedText.freshDoneNames(names), fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
                Text(SealedText.freshDoneEmail(email), fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
                Text(SealedText.FRESH_DONE_START, fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BusyButton(SealedText.FRESH_DONE_SHOW_HOW, onClick = { onShowHow() }, modifier = Modifier.fillMaxWidth().testTag("seal-fresh-done-show-how"), kind = ButtonKind.Primary)
                BusyButton(SealedText.FRESH_DONE_OK, onClick = { onDone() }, modifier = Modifier.fillMaxWidth().testTag("seal-fresh-done-ok"), kind = ButtonKind.Ghost)
            }
        },
        containerColor = c.bg,
    )
}
