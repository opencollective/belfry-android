package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.Enc3View
import sh.belfry.core.engine.WhoCanRead
import sh.belfry.core.engine.WhoText
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.model.World
import sh.belfry.core.net.SessionInfo

/*
 * Format 3, the screens of encrypted people conversations (SPEC 19.84; Encrypted canvas encrypted/conversation,
 * who-can-read, who-agent, email-who, laptop-who). Words: core `WhoText` (the web's WhoCanRead.tsx, enc-v3).
 * Never another person's devices: a count only. No state by colour alone (e-ink): the lock has its words.
 */

/** Where the conversation shows the lock of format 3: a people conversation encrypted here, or e-mail on format 3. */
internal fun Enc3View.locks(conv: String, mail: Boolean, newMail: Boolean): Boolean = when {
    newMail -> false
    mail -> format3
    else -> encrypted(conv)
}

/** The lock next to the conversation's name (never in the Messages list): opens Who can read this. */
@Composable
internal fun Enc3Lock(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = WhoText.LOCK }
            .testTag("enc3-lock"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BelfryIcons.Lock, contentDescription = null, tint = Belfry.colors.text, modifier = Modifier.size(16.dp))
    }
}

/** Who can read this beside the conversation: only when there is room for both (a wide window); else the sheet. */
@Composable
internal fun whoBeside(): Boolean = LocalConfiguration.current.screenWidthDp >= 1000

/** Who can read this as a sheet (ModalBottomSheet). */
@Composable
internal fun WhoCanReadSheet(conv: String, onClose: () -> Unit) {
    BelfrySheet(onDismiss = onClose, title = WhoText.TITLE, modifier = Modifier.testTag("enc3-who-sheet")) { WhoCanReadBody(conv) }
}

/** Who can read this as a panel beside the conversation (encrypted/laptop-who): its own title and Close. */
@Composable
internal fun WhoCanReadPanel(conv: String, onClose: () -> Unit) {
    val c = Belfry.colors
    Row(Modifier.fillMaxHeight()) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
        Column(Modifier.width(360.dp).fillMaxHeight().background(c.bg).statusBarsPadding().testTag("enc3-who-panel")) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(WhoText.TITLE, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f))
                IconAction(BelfryIcons.Close, "Close", onClose)
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) { WhoCanReadBody(conv) }
        }
    }
}

/** The content: the lead line, the others (a count each), my devices by name, then what it protects and doesn't. */
@Composable
internal fun ColumnScope.WhoCanReadBody(conv: String) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val dms = sh.belfry.app.ui.nav.LocalDms.current
    val view = session.enc3View ?: return
    var sessions by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
    LaunchedEffect(Unit) { sessions = view.sessions() }
    val mail = conv.startsWith("m:")
    val others = if (mail) emptyList() else MailThreads.peers(conv).filter { it != world.me }
    val who = WhoCanRead.of(view, mail, others, sessions) { world.displayName(it) }
    val mailName = if (mail) dms.convName(conv, world).ifEmpty { dms.mailAddress(conv) } else ""
    WhoContent(who, world, mailName)
}

/** Drawn from a [WhoCanRead] (the screenshot tests draw it directly). */
@Composable
internal fun ColumnScope.WhoContent(who: WhoCanRead, world: World, mailName: String) {
    val c = Belfry.colors
    val first = who.others.map { o -> (world.realName(o.account).ifEmpty { world.displayName(o.account) }).split(' ').first() }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(BelfryIcons.Shield, contentDescription = null, tint = c.text, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(WhoText.lead(who.mail, first), fontSize = 16.sp, lineHeight = 22.sp, color = c.text, modifier = Modifier.weight(1f).testTag("enc3-who-lead"))
    }
    if (who.mail || who.others.isNotEmpty()) {
        WhoHead(WhoText.OTHERS)
        WhoCard {
            if (who.mail) {
                WhoRow(lead = { Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(c.surface2), contentAlignment = Alignment.Center) { Icon(BelfryIcons.Mail, contentDescription = null, tint = c.text, modifier = Modifier.size(16.dp)) } },
                    title = mailName, sub = WhoText.MAIL_WHO, tag = "enc3-who-mail")
            } else for (o in who.others) {
                WhoRow(lead = { Avatar(world.face(o.account), size = 32.dp) }, title = world.displayName(o.account),
                    end = o.count?.let { WhoText.devices(it) } ?: "…", tag = "enc3-who-other")
            }
        }
        if (!who.mail) Text(WhoText.ONLY_COUNTS, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    }
    val me = world.realName(world.me).ifEmpty { world.displayName(world.me) }
    WhoHead(WhoText.you(me), end = who.myCount?.let { WhoText.devices(it) })
    WhoCard {
        for (d in who.mine) {
            if (d.agent) {
                WhoRow(title = world.displayName(d.key), sub = WhoText.YOUR_AGENT + (d.addedAt?.let { " · access given " + addedDay(world, it) } ?: ""), tag = "enc3-who-agent")
            } else {
                WhoRow(title = d.name ?: "A device", mark = if (d.here) WhoText.THIS_DEVICE else null,
                    sub = d.addedAt?.let { "Added " + addedDay(world, it) }, tag = if (d.here) "enc3-who-here" else "enc3-who-mine")
            }
        }
        if (who.notSetUp) WhoRow(title = null, sub = WhoText.NOT_SET_UP, tag = "enc3-who-not-set-up")
    }
    Spacer(Modifier.height(18.dp))
    if (who.mail) {
        Text(WhoText.MAIL, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text(WhoText.mailText(mailName), fontSize = 14.sp, lineHeight = 20.sp, color = c.text, modifier = Modifier.testTag("enc3-who-mail-text"))
    } else {
        Text(WhoText.HOW, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text(WhoText.HOW_1, fontSize = 14.sp, lineHeight = 20.sp, color = c.text, modifier = Modifier.testTag("enc3-who-how"))
        Spacer(Modifier.height(8.dp))
        Text(WhoText.HOW_2, fontSize = 14.sp, lineHeight = 20.sp, color = c.text)
    }
    Spacer(Modifier.height(12.dp))
}

private fun addedDay(world: World, at: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH).format(java.time.Instant.ofEpochSecond(at).atZone(world.ctx.zone))

@Composable
private fun WhoHead(text: String, end: String? = null) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.weight(1f))
        if (end != null) Text(end, fontSize = 13.sp, color = c.muted)
    }
}

@Composable
private fun WhoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Belfry.colors.surface), content = content)
}

@Composable
private fun WhoRow(title: String?, sub: String? = null, end: String? = null, mark: String? = null, tag: String, lead: (@Composable () -> Unit)? = null) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) { lead(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text, modifier = Modifier.weight(1f, fill = false))
                // "This device": words in a frame, never a colour alone (e-ink)
                if (mark != null) Text(
                    mark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(c.surface2).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            if (sub != null) Text(sub, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
        }
        if (end != null) Text(end, fontSize = 14.sp, color = c.muted, modifier = Modifier.padding(start = 8.dp))
    }
}

/** encrypted/not-before: one line where the unencrypted messages end. */
@Composable
internal fun NotBeforeLine() {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("enc3-not-before"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(16.dp).height(1.dp).background(c.line))
        Icon(BelfryIcons.Lock, contentDescription = null, tint = c.muted, modifier = Modifier.padding(horizontal = 8.dp).size(12.dp))
        Text(WhoText.NOT_BEFORE, fontSize = 12.sp, lineHeight = 16.sp, color = c.muted, modifier = Modifier.weight(1f))
    }
}

/** The place of the not-before line: before the first message dated from the first key, when unencrypted ones came before. */
internal object NotBefore

/** Rows with [NotBefore] before the first message at or after FIRST, when an unencrypted (plain) one came before it. */
internal fun withNotBefore(rows: List<Any>, first: Long?, at: (Any) -> Long?, plainBefore: (Any) -> Boolean): List<Any> {
    if (first == null) return rows
    val i = rows.indexOfFirst { r -> at(r)?.let { it >= first } == true && (r is sh.belfry.core.dm.Dm || r is DmGroup) }
    if (i <= 0) return rows
    if (rows.subList(0, i).none { r -> at(r)?.let { it < first } == true && plainBefore(r) }) return rows
    return rows.subList(0, i) + NotBefore + rows.subList(i, rows.size)
}
