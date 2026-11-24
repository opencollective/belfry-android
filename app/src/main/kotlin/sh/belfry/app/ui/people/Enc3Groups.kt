package sh.belfry.app.ui.people

import androidx.compose.material.icons.outlined.Info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.enc.EncDeviceText
import sh.belfry.core.enc.EncText
import sh.belfry.core.enc.EncWrite
import sh.belfry.core.enc.HoldReason
import sh.belfry.core.engine.Session
import sh.belfry.core.engine.WhoCanRead
import sh.belfry.core.engine.WhoText
import sh.belfry.core.model.FeedItem
import sh.belfry.core.model.GroupEncText
import sh.belfry.core.model.GroupLine
import sh.belfry.core.model.GroupLines
import sh.belfry.core.model.Special
import sh.belfry.core.model.World
import sh.belfry.core.net.SessionInfo
import sh.belfry.core.outbox.OutboxItem

/*
 * Format 3, private groups (SPEC 19.88; Encrypted canvas encrypted/group, group-who, group-who-cant-check,
 * group-joined, group-joined-others, group-removed, group-removed-self, group-removed-info, group-email,
 * group-too-big). Words: core `GroupEncText` and `WhoText`. Other people are a count, never devices; nothing is
 * said by colour alone (e-ink).
 */

/** H is drawn as a private group in format 3 (the lock, the lines, the rules). */
internal fun Session.groupLocked(h: String): Boolean = enc3View?.groupPrivate(h) == true

/** The lock in a private group's header: opens Who can read this for the group. */
@Composable
fun GroupLock(groupId: String) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = GroupEncText.LOCK }.testTag("enc3-group-lock"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BelfryIcons.Lock, contentDescription = null, tint = Belfry.colors.text, modifier = Modifier.size(15.dp))
    }
    if (open) GroupWhoSheet(groupId) { open = false }
}

@Composable
fun GroupWhoSheet(groupId: String, onClose: () -> Unit) {
    BelfrySheet(onDismiss = onClose, title = WhoText.TITLE, modifier = Modifier.testTag("enc3-group-who")) { GroupWhoBody(groupId) }
}

/** One other member in Who can read this: their device count (null while their list isn't known). */
internal data class GroupMember(val account: String, val count: Int?)

/** What Who can read this shows for a private group (the web's GroupWho). */
internal data class GroupWho(val name: String, val people: Int, val cant: Boolean, val mine: WhoCanRead, val others: List<GroupMember>, val mail: Boolean)

@Composable
internal fun ColumnScope.GroupWhoBody(groupId: String) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val view = session.enc3View ?: return
    var sessions by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
    LaunchedEffect(Unit) { sessions = view.sessions() }
    val g = world.group(groupId)
    val members = g?.members.orEmpty()
    val others = members.filter { it != world.me }.sortedBy { world.displayName(it).lowercase() }.map { GroupMember(it, view.count(it)) }
    val who = GroupWho(
        name = g?.name ?: "", people = (members + world.me).size, cant = view.groupCantCheck(groupId),
        mine = WhoCanRead.of(view, false, emptyList(), sessions) { world.displayName(it) }, others = others,
        mail = world.messages(groupId).any { it.special is Special.InboxCard },
    )
    GroupWhoContent(who, world, onRemove = { r -> session.enc3RemoveListed(r.key, r.agent); sessions = view.sessions() })
}

/** Drawn from a [GroupWho] (the screenshot tests draw it directly). */
@Composable
internal fun ColumnScope.GroupWhoContent(who: GroupWho, world: World, onRemove: (suspend (sh.belfry.core.engine.MyReader) -> Unit)? = null) {
    val c = Belfry.colors
    var removing by remember { mutableStateOf<sh.belfry.core.engine.MyReader?>(null) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(BelfryIcons.Shield, contentDescription = null, tint = c.text, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (who.cant) {
                Text(GroupEncText.CANT_CHECK, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.testTag("enc3-group-cant"))
                Text(GroupEncText.cantCheckText(who.name), fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
            } else {
                Text(GroupEncText.lead(who.people, who.name), fontSize = 16.sp, lineHeight = 22.sp, color = c.text, modifier = Modifier.testTag("enc3-group-lead"))
            }
        }
    }
    val me = world.realName(world.me).ifEmpty { world.displayName(world.me) }
    WhoHead(WhoText.you(me), end = who.mine.myCount?.let { WhoText.devices(it) })
    WhoCard {
        for (d in who.mine.mine) {
            val trash: (@Composable () -> Unit)? = if (onRemove == null || d.here) null else ({ IconAction(BelfryIcons.Trash, "Remove ${d.name ?: "this device"}", { removing = d }) })
            if (d.agent) WhoRow(title = world.displayName(d.key), sub = WhoText.YOUR_AGENT, tag = "enc3-who-agent", trailing = trash)
            else WhoRow(title = d.name ?: "A device", mark = if (d.here) WhoText.THIS_DEVICE else null, sub = d.addedAt?.let { "Added " + addedDay(world, it) }, tag = if (d.here) "enc3-who-here" else "enc3-who-mine", trailing = trash)
        }
        if (who.mine.notSetUp) WhoRow(title = null, sub = WhoText.NOT_SET_UP, tag = "enc3-who-not-set-up")
    }
    if (!who.cant) {
        val with = who.others.filter { it.count != 0 }
        val none = who.others.filter { it.count == 0 }
        if (with.isNotEmpty()) {
            WhoHead(WhoText.OTHERS)
            WhoCard { for (o in with) WhoRow(lead = { Avatar(world.face(o.account), size = 32.dp) }, title = world.displayName(o.account), end = o.count?.let { WhoText.devices(it) } ?: "…", tag = "enc3-group-other") }
            Text(WhoText.ONLY_COUNTS, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }
        // §23: a member signed in nowhere gets nothing until they are
        if (none.isNotEmpty()) {
            WhoHead(GroupEncText.NOT_SIGNED_IN)
            WhoCard { for (o in none) WhoRow(lead = { Avatar(world.face(o.account), size = 32.dp) }, title = world.displayName(o.account), sub = GroupEncText.GETS_LATER, tag = "enc3-group-none") }
        }
    }
    Spacer(Modifier.height(18.dp))
    if (who.mail) {
        Text(WhoText.MAIL, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text(GroupEncText.MAIL_NOTE, fontSize = 14.sp, lineHeight = 20.sp, color = c.text, modifier = Modifier.testTag("enc3-group-mail"))
        Spacer(Modifier.height(12.dp))
    }
    Text(WhoText.HOW, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
    Spacer(Modifier.height(6.dp))
    Text(WhoText.HOW_1, fontSize = 14.sp, lineHeight = 20.sp, color = c.text)
    Spacer(Modifier.height(8.dp))
    Text(WhoText.HOW_2, fontSize = 14.sp, lineHeight = 20.sp, color = c.text)
    Spacer(Modifier.height(12.dp))
    removing?.let { d ->
        Enc3RemoveSheet(Enc3Removing(d.key, if (d.agent) world.displayName(d.key) else d.name, d.agent), onConfirm = {
            try { onRemove?.invoke(d) } catch (e: Exception) { }; removing = null
        }, onDismiss = { removing = null }, confirmTag = "enc3-who-remove-confirm")
    }
}

/** The feed's extra lines of a private group (joined, removed, can read from here, no longer): none elsewhere. */
fun groupFeedLines(session: Session, world: World, groupId: String): List<FeedItem> {
    val view = session.enc3View ?: return emptyList()
    if (!view.groupPrivate(groupId)) return emptyList()
    val g = world.group(groupId)
    val candidates = (g?.members.orEmpty() + g?.admins.orEmpty() + world.groupEventsOf(groupId)).toSet()
    return GroupLines.of(world, groupId, view.groupChanges(groupId, candidates)).map { FeedItem.EncLine(it) }
}

/** Everyone a 9000 / 9001 of GROUP_ID names (people who were members once: their keys map entry keys to names). */
private fun World.groupEventsOf(groupId: String): List<String> =
    GroupLines.of(this, groupId, emptyList()).map { it.who }

/** One line of a private group's feed (encrypted/group-joined …): an icon, the words, the time, the (i) for a removal. */
@Composable
fun GroupLineRow(groupId: String, line: GroupLine) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val name = world.group(groupId)?.name ?: ""
    val nameOf = { k: String -> world.displayName(k) }
    var info by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("enc3-group-line"), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (line.kind == GroupLine.Kind.JOINED || line.kind == GroupLine.Kind.CAN_READ) BelfryIcons.User else BelfryIcons.Lock, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(GroupEncText.title(line, world.me, nameOf, name), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f, fill = false))
                Text(" · " + ChatFormat.time(world, line.at), fontSize = 12.sp, color = c.faint)
            }
            Text(GroupEncText.small(line, world.me), fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
        }
        if (GroupEncText.hasInfo(line, world.me)) IconAction(androidx.compose.material.icons.Icons.Outlined.Info, "What this means", { info = true })
    }
    if (info) BelfrySheet(onDismiss = { info = false }, title = GroupEncText.infoTitle(line, nameOf), modifier = Modifier.testTag("enc3-group-info")) {
        for (p in GroupEncText.info(line, world.me, nameOf, name)) {
            Text(p, fontSize = 15.sp, lineHeight = 21.sp, color = c.text)
            Spacer(Modifier.height(10.dp))
        }
        BusyButton(GroupEncText.OK, onClick = { info = false }, modifier = Modifier.fillMaxWidth().testTag("enc3-group-info-ok"))
        Spacer(Modifier.height(8.dp))
    }
}

/** Messages of the group waiting here: opening (their key or lists are coming), or with no copy for this device (+ Ask). */
@Composable
fun GroupWaitingLine(groupId: String) {
    val session = LocalSession.current
    val view = session.enc3View ?: return
    if (!view.groupPrivate(groupId)) return
    val (opening, unreadable) = view.groupWaiting(groupId)
    if (opening == 0 && unreadable == 0) return
    val c = Belfry.colors
    val busy = rememberBusy()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("enc3-group-waiting")) {
        if (opening > 0) Text(EncText.opening(opening), fontSize = 13.sp, color = c.muted)
        if (unreadable > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(EncText.unreadable(unreadable) + " · ", fontSize = 13.sp, color = c.muted, modifier = Modifier.weight(1f, fill = false))
            Text(
                if (busy.busy) "Asking…" else EncDeviceText.ASK, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                modifier = Modifier.clickable(enabled = !busy.busy, role = Role.Button) { busy.run { session.enc3Ask() } }.testTag("enc3-group-ask"),
            )
        }
    }
}

/** §25: a guest's session in a private group: no composer, the words instead. */
@Composable
fun PrivateGuestBar() {
    val c = Belfry.colors
    Hairline()
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp).testTag("enc3-group-guest"), verticalAlignment = Alignment.CenterVertically) {
        Icon(BelfryIcons.Lock, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(GroupEncText.GUEST, fontSize = 14.sp, color = c.text)
    }
}

/** encrypted/group-removed-self: I was removed: no composer (what I could read stays). */
@Composable
fun RemovedFromGroupBar() {
    val c = Belfry.colors
    Hairline()
    Text(GroupEncText.REMOVED_BAR, fontSize = 14.sp, lineHeight = 20.sp, color = c.muted,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp).testTag("enc3-group-removed-bar"))
}

/** The hold a group write waits on, read from its outbox item (`held: enc3:<REASON>[:who]`), or null. */
fun heldOf(item: OutboxItem): EncWrite.Hold? {
    val e = item.error ?: return null
    val rest = when {
        e.startsWith("held: " + Session.HELD_PREFIX) -> e.removePrefix("held: " + Session.HELD_PREFIX)
        e.startsWith("invalid: " + Session.HELD_PREFIX) -> e.removePrefix("invalid: " + Session.HELD_PREFIX)
        else -> return null
    }
    val parts = rest.split(":")
    val reason = HoldReason.entries.firstOrNull { it.name == parts[0] } ?: return null
    return EncWrite.Hold(reason, parts.getOrNull(1))
}

/** The words under a held group message (group-too-big for too many devices). */
fun heldWords(h: EncWrite.Hold, world: World): String =
    if (h.reason == HoldReason.TOO_MANY) GroupEncText.TOO_BIG else EncText.held(h, { world.displayName(it) }, group = true)
