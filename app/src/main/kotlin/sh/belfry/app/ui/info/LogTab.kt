package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.copyText
import sh.belfry.app.ui.kit.rememberDevMode
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.ChannelLog
import sh.belfry.core.model.Group
import sh.belfry.core.model.LogGo
import sh.belfry.core.model.LogItem
import sh.belfry.core.model.channelLog
import sh.belfry.core.model.logWho
import sh.belfry.core.model.prettyJson
import sh.belfry.core.nostr.Event
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FULL = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss", Locale.ENGLISH)

private class LogDay(val key: String, val label: String, val items: List<LogItem>, /** Where its heading sits in the list. */ val index: Int)

/**
 * Everything that happened in the channel, newest first (web 2026-11-12): one line per Nostr event
 * in the group: who did what, an icon for the kind of thing, the time. A day per section, with
 * the days as chips to jump. A tap shows more and a way to go there; the channel's admins in
 * developer mode (Me) can look at the event itself. [devMode]: for tests.
 */
@Composable
fun LogTab(g: Group, modifier: Modifier = Modifier, onTab: (Int) -> Unit = {}, devMode: Boolean = false) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val now = session.now()
    val items = remember(world, g.id) { world.channelLog(g.id) }
    val dev = rememberDevMode(devMode).value && world.canAdmin(g)
    var open by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableStateOf<Event?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (items.isEmpty()) {
        Box(modifier) { EmptyState("Nothing happened here yet.", icon = BelfryIcons.Clock) }
        return
    }
    val days = remember(items, now / 3600) {
        val out = ArrayList<LogDay>()
        var index = 0
        var i = 0
        while (i < items.size) {
            val day = world.dayOf(items[i].ev.createdAt)
            var j = i
            while (j < items.size && world.dayOf(items[j].ev.createdAt) == day) j++
            out.add(LogDay(day.toString(), ChatFormat.day(world, items[i].ev.createdAt, now), items.subList(i, j), index))
            index += 1 + (j - i)
            i = j
        }
        out
    }

    Column(modifier.testTag("log")) {
        // the days, as chips to jump (the row scrolls sideways when there are many; the page does not)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(days, key = { it.key }) { d ->
                Row(
                    Modifier.clip(RoundedCornerShape(99.dp)).background(c.surface2)
                        .clickable(role = Role.Button, onClickLabel = "Go to ${d.label}") { scope.launch { list.animateScrollToItem(d.index) } }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(d.label, fontSize = 13.sp, color = c.text, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Text(d.items.size.toString(), fontSize = 12.sp, color = c.muted, maxLines = 1)
                }
            }
        }
        Hairline()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            for (d in days) {
                item(key = "day:" + d.key, contentType = "day") {
                    Text(d.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
                }
                itemsIndexed(d.items, key = { _, line -> "ev:" + line.ev.id }, contentType = { _, _ -> "line" }) { _, line ->
                    val isOpen = open == line.ev.id
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (isOpen) "Show less" else "Show more") { open = if (isOpen) null else line.ev.id }
                                .padding(horizontal = 16.dp, vertical = 9.dp).testTag("log-row"),
                        ) {
                            Text(line.icon, fontSize = 16.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.width(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(world.logWho(g.id, line.ev.pubkey)) }
                                        append(" " + line.text)
                                    },
                                    fontSize = 14.sp, lineHeight = 20.sp, color = c.text,
                                )
                                val detail = line.detail
                                if (!detail.isNullOrEmpty() && !isOpen) {
                                    Text(ChannelLog.short(detail, 60), fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(ChatFormat.time(world, line.ev.createdAt), fontSize = 12.sp, lineHeight = 20.sp, color = c.muted, maxLines = 1)
                        }
                        if (isOpen) {
                            Column(Modifier.fillMaxWidth().padding(start = 50.dp, end = 16.dp, bottom = 12.dp)) {
                                val detail = line.detail
                                if (!detail.isNullOrEmpty()) Text(detail, fontSize = 14.sp, lineHeight = 20.sp, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
                                Text(FULL.format(Instant.ofEpochSecond(line.ev.createdAt).atZone(world.ctx.zone)), fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
                                val go = line.go
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (go != null) {
                                        BusyButton(
                                            "Go there",
                                            onClick = {
                                                when (go) {
                                                    is LogGo.Message -> nav.openChannel(g.id, go.id)
                                                    is LogGo.Thread -> nav.go(Routes.thread(g.id, go.root))
                                                    LogGo.Todos -> onTab(InfoTab.TODOS)
                                                    LogGo.Members -> onTab(InfoTab.MEMBERS)
                                                }
                                            },
                                            small = true, modifier = Modifier.testTag("log-go"),
                                        )
                                    }
                                    if (dev) BusyButton("Inspect the Nostr event", onClick = { raw = line.ev }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("log-inspect"))
                                }
                            }
                        }
                        Hairline()
                    }
                }
            }
            item(key = "end", contentType = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }

    raw?.let { ev ->
        BelfrySheet(onDismiss = { raw = null }, title = "Nostr event · kind ${ev.kind}") {
            Text(
                ev.prettyJson(), fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, color = c.text,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(10.dp).testTag("log-raw"),
            )
            Spacer(Modifier.height(12.dp))
            BusyButton("Copy", onClick = { copyText(context, ev.toJsonString(), "Nostr event") }, kind = ButtonKind.Ghost, small = true, icon = BelfryIcons.Copy)
        }
    }
}
