package sh.belfry.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.BuildConfig
import sh.belfry.app.ui.chat.authorName
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.chat.hasProfile
import sh.belfry.app.ui.chat.glyph
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.ChannelGlyph
import sh.belfry.app.ui.kit.ChannelIcon
import sh.belfry.app.ui.kit.PodIcon
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.crypto.Bech32
import sh.belfry.core.model.Group
import sh.belfry.core.model.Message
import sh.belfry.core.model.World

private class DrawerRow(val g: Group, val depth: Int)

/** Channels as the web chat's drawer orders them: pods and proposals nest under where they were posted. */
private fun tree(world: World): Pair<List<DrawerRow>, List<Group>> {
    val all = world.groups
    val chans = all.filter { !it.isEvent && !it.isPod && !it.isProposal }
    val events = all.filter { it.isEvent }
    val pods = all.filter { it.isPod || it.isProposal }
    val ids = all.map { it.id }.toSet()
    val out = ArrayList<DrawerRow>()
    fun add(g: Group, depth: Int, seen: Set<String>) {
        out.add(DrawerRow(g, depth))
        for (x in pods) if (x.parent == g.id && x.id !in seen) add(x, depth + 1, seen + g.id)
    }
    for (g in chans) add(g, 0, emptySet())
    for (g in pods) if (g.parent == null || g.parent !in ids) add(g, 0, emptySet())
    return out to events
}

/**
 * The drawer: the community's name, Messages, All events, the channels, and you at the bottom.
 * [onPick] opens a channel; [go] pushes another screen (the caller closes the drawer).
 */
@Composable
fun ChannelDrawer(current: String, onPick: (String) -> Unit, go: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val dms = LocalDms.current
    val (rows, events) = tree(world)
    val name = world.ctx.communityName.ifEmpty { session.community.name }
    Column(modifier.fillMaxSize().background(c.bg)) {
        TopBar(name, actions = { IconAction(BelfryIcons.Plus, "New channel", { go(Routes.CREATE_CHANNEL) }) })
        Hairline()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item(key = "<messages>") {
                DrawerItem(
                    icon = { ChannelIcon(ChannelGlyph.Mail) }, title = "Messages",
                    sub = "Direct messages and e-mail" + (world.myAddress?.let { " · $it" } ?: ""),
                    badge = dms.unreadTotal, onClick = { go(Routes.MESSAGES) },
                )
            }
            item(key = "<events>") {
                DrawerItem(icon = { ChannelIcon(ChannelGlyph.Event) }, title = "All events", sub = "Every channel's events", onClick = { go(Routes.events(null)) })
            }
            item(key = "<channels>") { Section("Channels", rows.size) }
            for (r in rows) {
                item(key = r.g.id) { ChannelItem(world, r.g, r.depth, r.g.id == current) { onPick(r.g.id) } }
            }
            if (rows.isEmpty()) {
                item(key = "<none>") {
                    Text("No channels yet. They appear here once the belfry answers.", fontSize = 14.sp, color = c.muted, modifier = Modifier.padding(16.dp))
                }
            }
            if (events.isNotEmpty()) {
                item(key = "<event-chats>") { Section("Event chats", events.size) }
                for (g in events) item(key = g.id) { ChannelItem(world, g, 0, g.id == current) { onPick(g.id) } }
            }
            item(key = "<create>") {
                Box(Modifier.padding(16.dp)) {
                    BusyButton("Create a channel", onClick = { go(Routes.CREATE_CHANNEL) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, icon = BelfryIcons.Plus)
                }
            }
            if (BuildConfig.DEBUG) {
                item(key = "<kit>") {
                    Text("Kit gallery (debug)", fontSize = 13.sp, color = c.faint, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { go(Routes.KIT) }.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
        Hairline()
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "You") { go(Routes.ME) }.padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding().testTag("drawer-me"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(world.face(world.me), size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(world.realName(world.me).ifEmpty { "You" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = world.myAddress ?: if (session.localOnly) "Demo: nothing leaves this device" else session.community.domain
                Text(sub, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Section(title: String, count: Int) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted)
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), fontSize = 13.sp, color = c.faint)
    }
}

@Composable
private fun ChannelItem(world: World, g: Group, depth: Int, on: Boolean, onClick: () -> Unit) {
    val last = world.messages(g.id).lastOrNull()
    val sub = if (last != null) "${if (last.hasProfile()) world.displayName(last.pubkey) else world.authorName(g.id, last)}: ${preview(world, last)}" else g.about
    DrawerItem(icon = { if (g.isPod) PodIcon(g.members.size) else ChannelIcon(g.glyph()) }, title = g.name, sub = sub, on = on, depth = depth, onClick = onClick)
}

/** A message as one line without Markdown marks; mentions as @Name. */
private fun preview(world: World, m: Message): String {
    val text = m.body.ifEmpty { m.text }
    if (text.isNotEmpty()) {
        return Markdown.plain(text) { npub ->
            val name = Bech32.npubToHex(npub)?.let { world.realName(it) }.orEmpty()
            "@" + name.ifEmpty { "…" }
        }
    }
    if (m.media.isNotEmpty()) return "Photo"
    return m.created?.let { "Created $it" } ?: ""
}

@Composable
private fun DrawerItem(icon: @Composable () -> Unit, title: String, sub: String, onClick: () -> Unit, on: Boolean = false, depth: Int = 0, badge: Int = 0) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().background(if (on) c.surface else c.bg).clickable(role = Role.Button, onClick = onClick)
            .padding(start = (16 + depth * 18).dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge > 0) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(22.dp).clip(CircleShape).background(c.orange), contentAlignment = Alignment.Center) {
                Text(if (badge > 99) "99" else badge.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
