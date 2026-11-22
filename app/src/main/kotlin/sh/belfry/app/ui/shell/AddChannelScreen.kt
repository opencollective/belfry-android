package sh.belfry.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Group
import sh.belfry.core.model.OneShape
import sh.belfry.core.model.World
import sh.belfry.core.model.isLocked
import sh.belfry.core.model.townChannels
import sh.belfry.core.model.townList

/** What the field holds, as a name to look for: no leading #, trimmed, lower case. */
internal fun channelQuery(typed: String): String = typed.trim().trimStart('#').trim().lowercase()

/** The channels whose name holds [query] (all of them, by name, when it is empty). Runs on the device: it works offline. */
internal fun World.channelsMatching(query: String): List<Group> {
    val all = townChannels().filter { !isLocked(it.id) }
    val q = query.trim()
    return if (q.isEmpty()) all.sortedBy { it.name.lowercase() }
    else all.filter { it.name.lowercase().contains(q) }
        .sortedWith(compareBy<Group> { !it.name.lowercase().startsWith(q) }.thenBy { it.name.lowercase() })
}

/**
 * "Add a channel" (nav2/channel-new, channel-new-typing; Navigation v2): the ＋ on CHANNELS. One
 * field "# Name"; who reads channels in one sentence (with a link to start a group instead); the
 * existing channels, filtered live with the typed part in bold and underlined; "Create #name" comes
 * last. A channel in my menu opens in Town; one that is not opens pushed, as a preview (back comes
 * here). Any member may create a channel.
 */
@Composable
fun AddChannelScreen() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    var typed by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pins by session.pins.collectAsState()
    val now = session.now()
    val list = remember(world, pins, now / 3600) { world.townList(pins.c, now) }
    val q = channelQuery(typed)
    val found = remember(world, q) { world.channelsMatching(q) }
    val exact = found.any { it.name.trim().trimStart('#').lowercase() == q }
    val name = belfryName(world, session)
    val guest = world.amGuest || session.guest.collectAsState().value?.reading == true

    Column(Modifier.fillMaxSize().background(c.bg).imePadding().testTag("add-channel")) {
        TopBar("Add a channel", navigation = { BackButton { nav.back() } })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            item(key = "<field>") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    BelfryTextField(
                        typed, { typed = it; error = null }, Modifier.fillMaxWidth().testTag("add-channel-name"),
                        placeholder = "# Name, e.g. repair-cafe",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    )
                    Spacer(Modifier.size(10.dp))
                    WhoReads(name) { nav.go(Routes.createPod(OneShape.MAIN)) }
                }
            }
            item(key = "<head>") {
                Text(
                    (if (q.isEmpty()) "Existing channels" else "Already there") + " · ${found.size}",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, color = c.muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).testTag("add-channel-count"),
                )
            }
            for (g in found) item(key = g.id) {
                FoundRow(world, g, q, inMenu = g.id in list.list, pinned = g.id in list.pinned, now = now) {
                    if (g.id in list.list) nav.openChannel(g.id) else nav.go(Routes.chat(g.id))
                }
            }
            if (q.isNotEmpty() && !exact && !guest) item(key = "<create>") {
                Hairline()
                Text("New", fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, color = c.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val shown = typed.trim().trimStart('#').trim()
                    BusyButton(
                        "Create #$shown",
                        onClick = {
                            val id = session.createChannel(shown, "")
                            nav.openChannel(id)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("add-channel-create"),
                        icon = BelfryIcons.Plus,
                        onError = { error = it.message ?: "Not created. Try again." },
                    )
                    Text("Only if none of the above fits", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 6.dp))
                    error?.let { Text(it, fontSize = 13.sp, color = c.danger, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}

private fun belfryName(world: World, session: sh.belfry.core.engine.Session): String = world.ctx.communityName.ifEmpty { session.community.name }

/** "Everyone in <belfry> can read channels. For a smaller circle, start a group." The link opens New group. */
@Composable
private fun WhoReads(name: String, onGroup: () -> Unit) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "Start a group", onClick = onGroup).testTag("add-channel-group")) {
        Text(
            buildAnnotatedString {
                append("Everyone in $name can read channels. For a smaller circle, ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline, color = c.text)) { append("start a group") }
                append(".")
            },
            fontSize = 14.sp, color = c.muted,
        )
    }
}

/** NAME with every occurrence of Q in bold and underlined (e-ink: not colour alone). */
internal fun highlighted(name: String, q: String): AnnotatedString = buildAnnotatedString {
    if (q.isEmpty()) { append(name); return@buildAnnotatedString }
    val low = name.lowercase()
    var i = 0
    while (i < name.length) {
        val at = low.indexOf(q, i)
        if (at < 0) { append(name.substring(i)); break }
        append(name.substring(i, at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(name.substring(at, at + q.length)) }
        i = at + q.length
    }
}

/** "8 messages this week", "1 message this month", "No messages this month". */
internal fun activityLine(world: World, id: String, now: Long): String {
    val msgs = world.messages(id)
    val week = msgs.count { it.at > now - 7 * 86_400 }
    if (week > 0) return "$week ${if (week == 1) "message" else "messages"} this week"
    val month = msgs.count { it.at > now - 30 * 86_400 }
    return if (month > 0) "$month ${if (month == 1) "message" else "messages"} this month" else "No messages this month"
}

@Composable
private fun FoundRow(world: World, g: Group, q: String, inMenu: Boolean, pinned: Boolean, now: Long, onClick: () -> Unit) {
    val c = Belfry.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("add-channel-row-${g.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BelfryIcons.Hash, contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(highlighted(g.name, q), fontSize = 16.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((if (pinned) "Pinned · " else "") + activityLine(world, g.id, now), fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (inMenu) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(c.surface).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text("In your menu", fontSize = 12.sp, color = c.muted)
            }
        }
    }
}
