package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.ChannelGlyph
import sh.belfry.app.ui.kit.ChannelIcon
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.dm.DmThread
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.model.World

/** The face of a conversation: the other person, or an envelope for an e-mail thread. */
@Composable
internal fun ConvFace(conv: String, dms: DmSnapshot, world: World, size: Dp = 40.dp) {
    if (dms.isMail(conv)) {
        ChannelIcon(ChannelGlyph.Mail, size = size)
    } else {
        val peer = dms.peers(conv).firstOrNull() ?: world.me
        Avatar(world.face(peer), size = size)
    }
}

/**
 * Direct messages and e-mail in one place: members by NIP-17, anyone else by e-mail through the
 * belfry. Your address here is name@domain; mail to it lands here too.
 */
@Composable
fun MessagesScreen() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val dms = LocalDms.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val address = world.myAddress
    val list = dms.threads
    val now = session.now()
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar(
            "Messages", subtitle = if (address != null) "Your address: $address" else null,
            navigation = { BackButton { nav.back() } },
            actions = { IconAction(BelfryIcons.Plus, "New message", { nav.go(Routes.NEW_CONVERSATION) }) },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(vertical = 6.dp)) {
            if (list.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        "No messages yet",
                        text = "Members can write to you here, and anyone can e-mail you at ${address ?: "you@${session.community.domain}"}.",
                        icon = BelfryIcons.Mail,
                    )
                }
            }
            items(list, key = { it.conv }) { t -> ThreadRow(t, dms, world, now) { nav.go(Routes.conversation(t.conv)) } }
            item(key = "new") {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    BusyButton(
                        "New message", onClick = { nav.go(Routes.NEW_CONVERSATION) }, kind = ButtonKind.Ghost, icon = BelfryIcons.Plus,
                        modifier = Modifier.fillMaxWidth().testTag("messages-new"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(t: DmThread, dms: DmSnapshot, world: World, now: Long, onClick: () -> Unit) {
    val c = Belfry.colors
    val mail = dms.isMail(t.conv)
    val title = if (mail) dms.threadName(t.conv) else if (dms.peers(t.conv).let { it.isEmpty() || it == listOf(world.me) }) "Notes to yourself" else dms.convName(t.conv, world)
    val first = Markdown.plain(t.last.text).lineSequence().firstOrNull { it.isNotBlank() } ?: if (t.last.attachments.isNotEmpty()) "Attachment" else ""
    val preview = (if (mail) dms.convName(t.conv, world) + " · " else "") + (if (t.last.mine) "You: " else "") + first
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvFace(t.conv, dms, world)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = if (t.unread > 0) FontWeight.SemiBold else FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(preview, fontSize = 14.sp, color = if (t.unread > 0) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(ChatFormat.short(world, t.last.at, now), fontSize = 12.sp, color = c.muted, maxLines = 1)
            if (t.unread > 0) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(CircleShape).background(c.orange).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("${t.unread}", fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = c.bg) }
            }
        }
    }
}
