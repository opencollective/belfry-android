package sh.belfry.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.core.engine.Session
import sh.belfry.core.model.Media
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.Chip
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.MarkdownText
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.PodShape
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.EventStatus
import sh.belfry.core.model.stateLabel
import sh.belfry.core.model.Actions
import sh.belfry.core.model.ChannelLine
import sh.belfry.core.model.LineAction
import sh.belfry.core.model.LineIcon
import sh.belfry.core.model.Task
import sh.belfry.app.ui.kit.rememberNow
import sh.belfry.app.ui.kit.WorkRing
import sh.belfry.core.model.Topics
import sh.belfry.core.model.channelFacts
import sh.belfry.core.model.channelLine
import sh.belfry.core.model.lineDay
import sh.belfry.core.model.lineWhen
import sh.belfry.core.model.needsMyVote
import sh.belfry.core.model.peopleLine
import sh.belfry.core.model.todoWho
import sh.belfry.core.model.latestTasks
import sh.belfry.app.ui.info.AboutBody
import sh.belfry.app.ui.info.ChannelSettingsSheet
import sh.belfry.app.ui.info.InfoTab
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Under a channel's name there is exactly one line: the most useful thing about the channel
 * right now (see `World.channelLine`), small counts at the right, and a chevron. Tapping it or
 * dragging it down opens [PullDown] with the same things in full.
 */
@Composable
fun SummaryLine(groupId: String, chat: ChatState, now: Long, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val line = world.channelLine(groupId, now, chat.topic, chat.hiddenPins)
    val toggle = { chat.toggleLine() }
    Row(
        modifier
            .fillMaxWidth()
            .background(c.bg)
            .pointerInput(groupId) {
                var moved = 0f
                detectVerticalDragGestures(onDragStart = { moved = 0f }, onDragEnd = {
                    if (moved > 24f) chat.pullLine() else if (moved < -24f) chat.pushLine()
                }) { _, dy -> moved += dy }
            }
            .clickable(role = Role.Button, onClickLabel = if (chat.lineOpen) "Close the channel summary" else "Open the channel summary", onClick = toggle)
            .heightIn(min = 40.dp)
            .padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
            .testTag("channel-line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineGlyph(line, if (line.quiet) c.faint else c.muted)
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(line.bold) }
                append(line.text)
            },
            fontSize = 13.sp, color = if (line.quiet) c.muted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("channel-line-text"),
        )
        val pill = line.pill
        if (pill != null) {
            Spacer(Modifier.width(8.dp))
            val act = line.action
            LinePill(pill, line.ask, onClick = if (act == null) null else ({
                when (act) {
                    LineAction.ShowAll -> { chat.topic = null }
                    LineAction.Open -> nav.go(Routes.proposal(groupId))
                    LineAction.Review -> nav.go(Routes.channelInfo(groupId, InfoTab.KEY_MEMBERS))
                    is LineAction.OpenProposal -> nav.go(Routes.proposal(act.groupId))
                    is LineAction.OpenEvent -> nav.go(Routes.event(act.coord))
                }
            }))
        }
        for (n in line.counts) {
            Spacer(Modifier.width(8.dp))
            Row(Modifier.semantics { contentDescription = "${n.n} ${n.label}" }, verticalAlignment = Alignment.CenterVertically) {
                when (n.kind) {
                    "events" -> Icon(BelfryIcons.Calendar, null, tint = c.faint, modifier = Modifier.size(13.dp))
                    "proposals" -> Icon(BelfryIcons.Proposal, null, tint = c.faint, modifier = Modifier.size(13.dp))
                    "pods" -> PodShape(6, size = 13.dp, color = c.faint)
                    "photos" -> Icon(BelfryIcons.Image, null, tint = c.faint, modifier = Modifier.size(13.dp))
                    else -> Icon(MoreIcons.Todo, null, tint = c.faint, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(3.dp))
                Text((if (n.plus) "+" else "") + n.n + (n.of?.let { "/$it" } ?: ""), fontSize = 12.sp, color = c.muted, maxLines = 1)
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(if (chat.lineOpen) MoreIcons.ChevronUp else BelfryIcons.ChevronDown, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LineGlyph(line: ChannelLine, tint: androidx.compose.ui.graphics.Color) {
    when (line.icon) {
        LineIcon.PEOPLE -> Icon(BelfryIcons.Users, null, tint = tint, modifier = Modifier.size(16.dp))
        LineIcon.TOPIC -> Icon(BelfryIcons.Hash, null, tint = tint, modifier = Modifier.size(16.dp))
        LineIcon.PROPOSAL -> Icon(BelfryIcons.Proposal, null, tint = tint, modifier = Modifier.size(16.dp))
        LineIcon.EVENT -> Icon(BelfryIcons.Calendar, null, tint = tint, modifier = Modifier.size(16.dp))
        LineIcon.PIN -> Icon(BelfryIcons.Pin, null, tint = Belfry.colors.orange, modifier = Modifier.size(16.dp))
        LineIcon.POD -> PodShape(line.podMembers, size = 17.dp, color = tint)
    }
}

/** A question to answer (filled) or my answer (outlined). */
@Composable
fun LinePill(text: String, ask: Boolean, onClick: (() -> Unit)? = null) {
    val c = Belfry.colors
    val shape = CircleShape
    val base = Modifier.clip(shape).then(if (ask) Modifier.background(c.accent) else Modifier.border(1.dp, c.line, shape))
    Box(
        base.then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (ask) c.onAccent else c.muted, maxLines = 1)
    }
}

/**
 * The line pulled down: everything it summarises, in the web's order (the description, Pinned,
 * Coming up, To decide, To do, Pods, photos, members, topics). Scrolls on its own; never sideways.
 * [full]: pulled all the way (the web's second level): the same summary showing more (the latest
 * 20 todos with the done ones and a field to add one, a mosaic of photos, bigger faces with
 * names), then the rest about the channel (its whole description, e-mail, invite, settings).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PullDown(groupId: String, chat: ChatState, now: Long, onShowMessage: (String) -> Unit, modifier: Modifier = Modifier, full: Boolean = false) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val f = world.channelFacts(groupId, now)
    val g = f.group ?: return
    val close = { chat.closeLine() }
    val go: (String) -> Unit = { close(); nav.go(it) }
    var settings by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(c.bg).verticalScroll(rememberScrollState()).padding(bottom = 4.dp).testTag("pull-down")) {
        // the description first (web 2026-11-12): its first line, plain; the same at both levels
        val about = g.about.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().replace(Regex("[*_`#>]"), "").take(200)
        if (about.isNotEmpty()) {
            Text(about, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp).testTag("pull-about"))
        }
        val parent = g.parent?.let { world.group(it) }
        if ((g.isPod || g.isProposal) && parent != null) {
            Text(
                "${if (g.isProposal) "Proposal" else "Pod"} in ${if (parent.isPod) "" else "# "}${parent.name}", fontSize = 13.sp, color = c.muted,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { close(); nav.openChannel(parent.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        val own = f.own
        if (own != null) {
            PullTitle("This proposal")
            PullRow(onClick = { go(Routes.proposal(groupId)) }, title = own.title, sub = "${own.status.label} · version ${own.version}") {
                if (world.needsMyVote(own)) LinePill("Vote", true) else if (own.votingNow && own.poll?.votes?.get(world.me) != null) LinePill("Voted", false)
            }
        }
        if (g.isPod && world.canAdmin(g) && g.applications.isNotEmpty()) {
            PullTitle("Asking to join", g.applications.size)
            PullRow(
                onClick = { go(Routes.channelInfo(groupId, InfoTab.KEY_MEMBERS)) },
                title = g.applications.take(3).joinToString(", ") { world.displayName(it.pubkey) } + if (g.applications.size > 3) "…" else "",
            ) { LinePill("Review", true) }
        }
        if (f.pins.isNotEmpty()) {
            PullTitle("Pinned")
            for (m in f.pins) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Icon(BelfryIcons.Pin, null, tint = c.orange, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        if (m.text.isNotEmpty()) MarkdownText(m.text, fontSize = 14.sp, maxLines = 8) else Text("A photo or file", fontSize = 14.sp, color = c.text)
                        Text("${world.displayName(m.pubkey)} · ${world.lineDay(m.at, now)}", fontSize = 12.sp, color = c.muted)
                        Row {
                            LinkText("Show in the channel") { close(); onShowMessage(m.id) }
                            if (m.id !in chat.hiddenPins) {
                                Spacer(Modifier.width(16.dp))
                                LinkText("Got it, hide") { chat.hidePin(m.id) }
                            }
                        }
                    }
                }
            }
        }
        if (f.events.isNotEmpty()) {
            PullTitle("Coming up")
            for (e in f.events.take(3)) {
                val mine = world.stateLabel(e)
                PullRow(onClick = { go(Routes.event(e.coord)) }, title = e.title, sub = "${world.lineDay(e.start, now)} · ${world.lineWhen(e)} · " + if (e.status == EventStatus.CANCELLED) "Cancelled" else "${e.going} going") {
                    LinePill(mine ?: "RSVP", mine == null)
                }
            }
            if (f.events.size > 3) LinkText("All ${f.events.size} events", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { go(Routes.events(groupId)) }
        }
        if (f.proposals.isNotEmpty()) {
            PullTitle("To decide")
            for (p in f.proposals) {
                PullRow(onClick = { go(Routes.proposal(p.groupId)) }, title = p.title, sub = "${p.status.label} · version ${p.version}") {
                    if (world.needsMyVote(p)) LinePill("Vote", true) else if (p.votingNow && p.poll?.votes?.get(world.me) != null) LinePill("Voted", false)
                }
            }
        }
        if (f.todosAll > 0) {
            PullTitle("To do", f.todosDone, of = f.todosAll)
            // a to-do opens its thread (the message it was made from), like the web; pulled all the
            // way: the latest 20, the done ones after the open ones
            val shown = if (full) latestTasks(f.allTasks) else f.todos.take(4)
            for (t in shown) {
                TodoRow(groupId, t, onOpen = {
                    val source = t.source
                    if (source != null) go(Routes.thread(groupId, source)) else if (!full) chat.fullLine()
                })
            }
            if (!full && f.todosAll > minOf(4, f.todos.size)) {
                LinkText(if (f.todos.isNotEmpty()) "All todos" else "Nothing left to do · show the done ones", Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("pull-all-todos")) { chat.fullLine() }
            }
            if (full) NewTodoRow(groupId)
        }
        if (f.pods.isNotEmpty()) {
            PullTitle("Pods")
            for (p in f.pods) {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { close(); nav.openChannel(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PodShape(p.members.size, size = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(p.name, fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(p.members.size.toString(), fontSize = 12.sp, color = c.muted)
                }
            }
        }
        if (f.photos.isNotEmpty()) PhotosRow(f.photos, chat, full) { go(Routes.files(groupId)) }
        PullTitle("Members", f.members.size)
        FacesRow(f.members, full) { go(Routes.profile(it)) }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LinkText("All members") { go(Routes.channelInfo(groupId, InfoTab.KEY_MEMBERS)) }
            if (f.files > 0) LinkText("${f.files} ${if (f.files == 1) "file" else "files"}") { go(Routes.files(groupId)) }
            if (f.todosAll > 0) LinkText("Todos") { go(Routes.channelInfo(groupId, InfoTab.KEY_TODOS)) }
            LinkText("Log") { go(Routes.channelInfo(groupId, InfoTab.KEY_LOG)) }
            if (!full) LinkText("More", Modifier.testTag("pull-more")) { chat.fullLine() }
        }
        val topics = world.topicsBar(groupId, chat.topic)
        val announcements = world.hasAnnouncements(groupId)
        if (topics.isNotEmpty() || announcements || chat.topic != null) {
            PullTitle("Topics")
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip("All", selected = chat.topic == null, onClick = { chat.topic = null; close() })
                if (announcements || chat.topic == Topics.ANNOUNCE) {
                    Chip("Announcements", selected = chat.topic == Topics.ANNOUNCE, icon = BelfryIcons.Megaphone, onClick = { chat.topic = Topics.ANNOUNCE; close() })
                }
                for (t in topics) Chip("#$t", selected = chat.topic == t, onClick = { chat.topic = if (chat.topic == t) null else t; close() })
            }
        }
        if (full) {
            // the rest: the whole description, invite, settings, the e-mail line, its page, its id
            Hairline(Modifier.padding(top = 12.dp))
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("pull-about-more")) {
                AboutBody(g, compact = true, onSettings = { settings = true }, onLeave = close)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    if (settings) ChannelSettingsSheet(g) { settings = false }
}

/** "Add a todo…" under the list pulled all the way. */
@Composable
private fun NewTodoRow(groupId: String) {
    val session = LocalSession.current
    var title by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        BelfryTextField(title, { title = it.take(300) }, Modifier.weight(1f).testTag("pull-todo-field"), placeholder = "Add a todo…")
        Spacer(Modifier.width(8.dp))
        BusyButton(
            "Add",
            onClick = {
                val t = title.trim()
                if (t.isNotEmpty()) {
                    session.send(Actions.addTask(groupId, t))
                    title = ""
                }
            },
            small = true, enabled = title.isNotBlank(), modifier = Modifier.testTag("pull-todo-add"),
        )
    }
}

/**
 * The latest photos (a tap opens the viewer, which swipes through them): one row of six, or
 * pulled all the way a mosaic of twelve.
 */
@Composable
private fun PhotosRow(photos: List<Media>, chat: ChatState, full: Boolean, onAll: () -> Unit) {
    val c = Belfry.colors
    val network = LocalNetworkImages.current
    val shown = photos.take(if (full) 12 else 6)
    val urls = photos.take(24).map { it.url }.filter { it.startsWith("https://") && !it.startsWith(Session.LOCAL_BLOB) }
    PullTitle("Latest photos", photos.size)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("pull-photos"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in shown.chunked(if (full) 4 else 6)) {
            val per = if (full) 4 else 6
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in line) {
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(c.surface2)
                            .clickable(role = Role.Image, onClickLabel = "Open the photo") {
                                if (m.url.startsWith(Session.LOCAL_BLOB)) onAll()
                                else chat.viewer = ViewerImage(m.url, null, m.name, urls, urls.indexOf(m.url).coerceAtLeast(0))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (network && m.url.startsWith("https://") && !m.url.startsWith(Session.LOCAL_BLOB)) {
                            AsyncImage(model = m.url, contentDescription = m.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(BelfryIcons.Image, contentDescription = m.name ?: "Photo", tint = c.faint, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                repeat(per - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (photos.size > shown.size) LinkText("All ${photos.size} photos", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), onAll)
}

/**
 * Members as faces (a tap opens the profile): a row of eight with "+N", or pulled all the way up
 * to 24 bigger ones with their names.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacesRow(members: List<String>, full: Boolean, onProfile: (String) -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    if (full) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("pull-faces"),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (p in members.take(24)) {
                Column(
                    Modifier.width(64.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onProfile(p) }.padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(world.face(p), size = 44.dp)
                    Text(world.displayName(p), fontSize = 11.sp, lineHeight = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }
    val shown = members.take(8)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("pull-faces"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in shown) {
            Avatar(world.face(p), Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = world.displayName(p)) { onProfile(p) }, size = 30.dp)
        }
        if (members.size > shown.size) Text("+${members.size - shown.size}", fontSize = 13.sp, color = c.muted)
    }
}

@Composable
private fun PullTitle(text: String, count: Int? = null, of: Int? = null) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString() + (of?.let { "/$it" } ?: ""), fontSize = 13.sp, color = c.faint)
        }
    }
}

@Composable
private fun PullRow(onClick: () -> Unit, title: String, sub: String? = null, trailing: @Composable () -> Unit = {}) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun LinkText(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Belfry.colors.text,
        modifier = modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
    )
}

/** A to-do with the box that ticks it off, or opens it again when it is done (locks while it is being sent). */
@Composable
private fun TodoRow(groupId: String, t: Task, onOpen: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val busy = rememberBusy()
    val now by rememberNow()
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clickable(enabled = !busy.busy, role = Role.Checkbox, onClickLabel = if (t.done) "Reopen \"${t.title}\"" else "Mark \"${t.title}\" as done") {
                busy.run { session.send(Actions.taskAction(groupId, t.id, if (t.done) "undone" else "done")) }
            }.testTag("pull-todo-" + t.id),
            contentAlignment = Alignment.Center,
        ) {
            val box = RoundedCornerShape(6.dp)
            if (busy.busy) Spinner(size = 16.dp, color = c.muted)
            else if (t.done) Box(Modifier.size(20.dp).clip(box).background(c.accent), contentAlignment = Alignment.Center) { Icon(BelfryIcons.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp)) }
            else if (t.isWorking(now)) WorkRing(t.since ?: now, now, size = 22.dp)
            else Box(Modifier.size(20.dp).clip(box).border(1.5.dp, if (t.assignee != null) c.text else c.faint, box))
        }
        Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 6.dp)) {
            Text(t.title, fontSize = 14.sp, color = if (t.pending || t.done) c.muted else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((if (t.done) "Done" else world.todoWho(t, now)) + (if (t.source != null) " · thread" else ""), fontSize = 12.sp, color = c.muted)
        }
    }
}
