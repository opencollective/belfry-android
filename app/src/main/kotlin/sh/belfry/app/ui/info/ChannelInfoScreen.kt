package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.chat.glyph
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Group
import sh.belfry.core.model.PodAccess

/** The tabs of a channel's page, in order. */
object InfoTab {
    const val ABOUT = 0
    const val TODOS = 1
    const val MEMBERS = 2
    const val FILES = 3
    val LABELS = listOf("About", "To-dos", "Members", "Files")
}

/**
 * Everything about a channel: About (description, who is active, e-mail, events, pods, the
 * actions), To-dos, Members, Files. The web shows this as the pulled-down channel header; here it
 * is a page behind the channel's title.
 */
@Composable
fun ChannelInfoScreen(id: String, initialTab: Int = InfoTab.ABOUT, settingsOpen: Boolean = false) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val g = world.group(id)
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg)) {
        TopBar(
            channelLabel(g, id),
            subtitle = world.activeLine(id, session.now()),
            navigation = { BackButton { nav.back() } },
            actions = { IconAction(BelfryIcons.Search, "Search this channel", { nav.go(Routes.search(id)) }) },
        )
        Tabs(InfoTab.LABELS, tab, { tab = it })
        if (g == null) {
            EmptyState("This channel is not here", text = "It may have been removed.", icon = BelfryIcons.Hash)
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                when (tab) {
                    InfoTab.TODOS -> TasksTab(id)
                    InfoTab.MEMBERS -> MembersTab(g)
                    InfoTab.FILES -> FilesTab(id)
                    else -> AboutTab(g, onMembers = { tab = InfoTab.MEMBERS }, settingsOpen = settingsOpen)
                }
            }
        }
    }
}

private fun ago(at: Long, now: Long): String {
    val d = (now - at) / 86400
    return if (d < 1) "today" else if (d == 1L) "yesterday" else "$d days ago"
}

@Composable
private fun AboutTab(g: Group, onMembers: () -> Unit, settingsOpen: Boolean) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val now = session.now()
    val active = remember(world, g.id) { world.recentlyActive(g.id, now) }
    val admin = world.canAdmin(g)
    val member = world.me in g.members
    var settings by remember { mutableStateOf(settingsOpen) }
    var leaving by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }

    if (g.about.isNotBlank()) {
        MarkdownText(g.about, rich = false)
    } else {
        Text("No description yet.", fontSize = 15.sp, color = c.muted)
    }

    if (active.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clickable(onClick = onMembers), verticalAlignment = Alignment.CenterVertically) {
            AvatarStack(active.take(6).map { world.face(it.pubkey) }, size = 26.dp, max = 6)
            Spacer(Modifier.width(8.dp))
            Text(
                (if (active.size > 6) "+${active.size - 6} · " else "") + "${active.size} active this month",
                fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    ActionGroup {
        ActionRow("Invite people", { nav.go(Routes.invite(g.id)) }, icon = BelfryIcons.Users, chevron = true, modifier = Modifier.testTag("info-invite"))
        ActionRow("Announcements by e-mail", { nav.go(Routes.follow(g.id)) }, icon = BelfryIcons.Bell, chevron = true)
        ActionRow("Topics", { nav.go(Routes.topics(g.id)) }, icon = BelfryIcons.Pin, chevron = true)
        ActionRow("Search", { nav.go(Routes.search(g.id)) }, icon = BelfryIcons.Search, chevron = true)
        if (admin) {
            ActionRow(
                if (g.isPod) "Pod settings" else "Channel settings", { settings = true },
                icon = BelfryIcons.Settings, chevron = true, modifier = Modifier.testTag("info-settings"),
            )
        }
    }

    if (g.isPod) {
        val parent = world.group(g.parent ?: "general")
        Spacer(Modifier.height(12.dp))
        ActionGroup {
            ActionRow(
                "Pod in " + channelLabel(parent, g.parent ?: "general"), { nav.openChannel(g.parent ?: "general") },
                icon = BelfryIcons.Pod, chevron = true,
                sub = (if (g.closed) "Closed: apply, admins admit" else "Open: any member joins") +
                    (if (g.closed) "\nQuestion: " + (g.prompt?.ifBlank { null } ?: "—") else ""),
            )
            if (member) {
                ActionRow("Leave pod", { leaving = true }, icon = BelfryIcons.Logout, danger = true)
            } else if (!g.closed) {
                BusyRow("Join pod", { session.send(Actions.join(g.id)) }, icon = BelfryIcons.Plus)
            } else if (g.applications.any { it.pubkey == world.me }) {
                ActionRow("You asked to join", null, icon = BelfryIcons.Clock, sub = "Its admins will answer.", enabled = false)
            } else {
                ActionRow("Ask to join", { applying = true }, icon = BelfryIcons.Plus, chevron = true)
            }
        }
    }

    val upcoming = remember(world, g.id) { world.upcoming(g.id, now) }
    if (upcoming.isNotEmpty()) {
        SectionTitle("Next events")
        ActionGroup {
            upcoming.take(3).forEach { e ->
                ActionRow(
                    e.title, { nav.go(Routes.event(e.coord)) }, icon = BelfryIcons.Calendar, chevron = true,
                    sub = ChatFormat.short(world, e.start, now) + (if (e.going > 0) " · ${e.going} going" else ""),
                )
            }
            if (upcoming.size > 3) ActionRow("All events", { nav.go(Routes.events(g.id)) }, value = upcoming.size.toString(), chevron = true)
        }
    }

    val pods = world.pods(g.id)
    if (pods.isNotEmpty()) {
        SectionTitle("Pods")
        ActionGroup {
            pods.forEach { p ->
                ActionRow(
                    p.name, { nav.go(Routes.pod(p.id)) }, icon = BelfryIcons.Pod, chevron = true,
                    sub = "${p.members.size} " + (if (p.members.size == 1) "member" else "members") + (if (p.closed) " · closed" else ""),
                )
            }
        }
    }

    val proposals = world.proposalsIn(g.id)
    if (proposals.isNotEmpty()) {
        SectionTitle("Proposals")
        ActionGroup {
            proposals.forEach { p ->
                ActionRow(p.title, { nav.go(Routes.proposal(p.groupId)) }, icon = BelfryIcons.Proposal, chevron = true, sub = p.status.label)
            }
        }
    }

    SectionTitle("Active in the last 30 days · ${active.size}")
    if (active.isEmpty()) {
        Hint("Nobody opened this channel yet.")
    } else {
        ActionGroup {
            active.forEach { a ->
                val f = world.face(a.pubkey)
                Row(
                    Modifier.fillMaxWidth().background(c.surface).clickable { nav.go(Routes.profile(a.pubkey)) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(f, size = 28.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(world.displayName(a.pubkey), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(ago(a.at, now), fontSize = 13.sp, color = c.muted)
                }
            }
        }
    }
    Hint("Opening a channel tells its members you saw it (at most every few hours).", Modifier.padding(top = 6.dp))

    SectionTitle("E-mail")
    val address = world.mailAddress(g.id)
    var copied by remember { mutableStateOf(false) }
    ActionGroup {
        ActionRow(
            address, { copyText(context, address, "E-mail address"); copied = true }, icon = BelfryIcons.Mail,
            sub = if (world.isInbox(g)) "Anyone can e-mail this address: mails land here as cards, and the channel's admins answer as the channel."
            else "Members can e-mail this address: their mail shows as their message.",
            trailing = { Text(if (copied) "Copied" else "Copy", fontSize = 14.sp, color = c.muted) },
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChannelIcon(g.glyph(), size = 22.dp)
        Spacer(Modifier.width(8.dp))
        Text((if (g.isPod) "Pod" else "Channel") + " id ", fontSize = 13.sp, color = c.muted)
        Text(g.id, fontSize = 13.sp, color = c.muted, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(24.dp))

    if (settings) ChannelSettingsSheet(g) { settings = false }
    if (leaving) {
        ConfirmSheet(
            "Leave ${g.name}?", "Leave",
            onConfirm = { session.send(Actions.leave(g.id)); leaving = false },
            onDismiss = { leaving = false },
            message = "You stop being a member of this pod. You can join again later.",
        )
    }
    if (applying) {
        var answer by remember { mutableStateOf("") }
        BelfrySheet({ applying = false }, title = "Ask to join ${g.name}") {
            Text(g.prompt?.ifBlank { null } ?: Actions.DEFAULT_PROMPT, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            BelfryTextField(answer, { answer = it }, placeholder = "Your answer", singleLine = false, minLines = 3)
            Spacer(Modifier.height(12.dp))
            BusyButton(
                "Send", { session.send(Actions.join(g.id, answer.trim())); applying = false },
                modifier = Modifier.fillMaxWidth(), enabled = answer.isNotBlank(),
            )
        }
    }
}

/** Name, description, a pod's access, and the shared-inbox switch (admins). */
@Composable
fun ChannelSettingsSheet(g: Group, onClose: () -> Unit) {
    BelfrySheet(onClose, title = if (g.isPod) "Pod settings" else "Channel settings") {
        ChannelSettingsForm(g, onClose)
    }
}

@Composable
fun ChannelSettingsForm(g: Group, onClose: () -> Unit) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val wasInbox = remember(g.id) { world.isInbox(g) }
    var name by remember(g.id) { mutableStateOf(g.name) }
    var about by remember(g.id) { mutableStateOf(g.about) }
    var closed by remember(g.id) { mutableStateOf(g.closed) }
    var prompt by remember(g.id) { mutableStateOf(g.prompt ?: "") }
    var openMail by remember(g.id) { mutableStateOf(wasInbox) }
    val busy = rememberBusy()

    BelfryTextField(name, { name = it }, label = "Name", enabled = !busy.busy)
    Spacer(Modifier.height(12.dp))
    BelfryTextField(
        about, { about = it }, label = "Description", hint = "Markdown works, links become clickable",
        singleLine = false, minLines = 3, enabled = !busy.busy,
    )
    if (g.isPod) {
        Spacer(Modifier.height(12.dp))
        Hint("Who can join")
        Spacer(Modifier.height(6.dp))
        Segmented(listOf("Open", "Closed"), if (closed) 1 else 0, { closed = it == 1 }, enabled = !busy.busy)
        Hint(if (closed) "People apply by answering a question; admins admit them." else "Any member joins.", Modifier.padding(top = 6.dp))
        if (closed) {
            Spacer(Modifier.height(10.dp))
            BelfryTextField(prompt, { prompt = it }, label = "Question for applicants", placeholder = Actions.DEFAULT_PROMPT, singleLine = false, enabled = !busy.busy)
        }
    }
    Spacer(Modifier.height(14.dp))
    ActionGroup {
        SwitchRow(
            "E-mails from anyone", openMail, { openMail = it }, icon = BelfryIcons.Inbox, enabled = !busy.busy,
            sub = "Mail to ${world.mailAddress(g.id)} from outside lands here as cards; admins answer as the channel. Off: only members' mail comes in.",
        )
    }
    Spacer(Modifier.height(16.dp))
    BusyButton(
        "Save",
        onClick = {
            val n = name.trim()
            val a = about.trim()
            if (n != g.name || a != g.about || g.isPod) {
                session.send(if (g.isPod) Actions.editPod(g.id, n, a, PodAccess(closed, prompt)) else Actions.editChannel(g.id, n, a))
            }
            if (openMail != wasInbox) session.send(Actions.setInbox(g.id, openMail))
            onClose()
        },
        modifier = Modifier.fillMaxWidth().testTag("settings-save"), enabled = name.isNotBlank(), busy = busy,
    )
}
