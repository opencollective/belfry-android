package sh.belfry.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.crypto.Bech32
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.nostr.Event
import sh.belfry.core.model.World
import sh.belfry.core.model.refLabel

/** The messages and thread replies that name me (p tag or my npub), newest first (web Mentions.tsx). */
fun World.mentionsOfMe(limit: Int = 100): List<Event> {
    val npub = "nostr:" + runCatching { Bech32.npub(me) }.getOrDefault("-")
    return all.asSequence()
        .filter { (it.kind == 9 || it.kind == 1111) && it.pubkey != me && it.tag("h") != null }
        .filter { e -> e.tags.any { it.size > 1 && it[0] == "p" && it[1] == me } || e.content.contains(npub) }
        .distinctBy { it.id }
        .sortedByDescending { it.createdAt }
        .take(limit)
        .toList()
}

/** @Mentions (web 2026-11-11): where people named me, in every channel; a tap opens the message. */
@Composable
fun MentionsScreen() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val list = world.mentionsOfMe()
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar("@Mentions", subtitle = "Where people named you", navigation = { BackButton { nav.back() } })
        Hairline()
        if (list.isEmpty()) {
            Text("No mentions yet. When someone writes @you in a channel, it shows here.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { e ->
                val h = e.tag("h")!!
                val root = e.tag("E") ?: e.tag("e")
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        if (e.kind == 1111 && root != null) nav.go(Routes.thread(h, root)) else nav.openChannel(h, e.id)
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Avatar(world.face(e.pubkey), size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${world.displayName(e.pubkey)} in ${world.group(h)?.name ?: h}" + if (e.kind == 1111) " · thread" else "",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val text = Markdown.plain(e.content, channel = { world.refLabel(it) }) { npub ->
                            "@" + (Bech32.npubToHex(npub)?.let { world.displayName(it) } ?: "…")
                        }
                        Text(text.ifEmpty { "a photo" }, fontSize = 15.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(ChatFormat.day(world, e.createdAt, session.now()), fontSize = 12.sp, color = c.faint)
                }
                Hairline()
            }
        }
    }
}
