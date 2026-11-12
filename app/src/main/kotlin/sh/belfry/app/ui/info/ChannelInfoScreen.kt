package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.signin.openInBrowser
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.Group
import sh.belfry.core.model.PodAccess
import sh.belfry.core.model.World

/** The tabs of a channel's page, in the web's order (c6579a0). */
object InfoTab {
    const val ABOUT = 0
    const val MEMBERS = 1
    const val TODOS = 2
    const val FILES = 3
    const val LOG = 4
    val LABELS = listOf("About", "Members", "Todos", "Files", "Log")

    /** The names a link uses (`Routes.channelInfo(id, "todos")`; the web's `c/<id>/todos`…). */
    const val KEY_MEMBERS = "members"
    const val KEY_TODOS = "todos"
    const val KEY_FILES = "files"
    const val KEY_LOG = "log"
    fun of(key: String?): Int = when (key) {
        KEY_MEMBERS -> MEMBERS
        KEY_TODOS, "tasks" -> TODOS
        KEY_FILES, "photos", "images" -> FILES
        KEY_LOG -> LOG
        else -> ABOUT
    }
}

/**
 * Everything about a channel (web ChannelInfo.tsx, c6579a0): About (no boxes: the description,
 * who is active, the e-mail line, its page on the web), Members, Todos, Files and a Log of
 * everything that happened in it. Its admins get a gear in the top bar for the settings.
 */
@Composable
fun ChannelInfoScreen(id: String, initialTab: Int = InfoTab.ABOUT, settingsOpen: Boolean = false) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val g = world.group(id)
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var settings by remember { mutableStateOf(settingsOpen) }
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg)) {
        TopBar(
            channelLabel(g, id),
            subtitle = world.activeLine(id, session.now()),
            navigation = { BackButton { nav.back() } },
            actions = {
                if (g != null && world.canAdmin(g)) {
                    IconAction(BelfryIcons.Settings, if (g.isPod) "Pod settings" else "Channel settings", { settings = true }, modifier = Modifier.testTag("info-settings"))
                }
            },
        )
        Tabs(InfoTab.LABELS, tab, { tab = it })
        if (g == null) {
            EmptyState("This channel is not here", text = "It may have been removed.", icon = BelfryIcons.Hash)
        } else if (tab == InfoTab.LOG) {
            // its own lazy list: a channel's log can be a thousand lines
            LogTab(g, Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), onTab = { tab = it })
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                when (tab) {
                    InfoTab.TODOS -> TasksTab(id)
                    InfoTab.MEMBERS -> { MembersSummary(g); MembersTab(g) }
                    InfoTab.FILES -> FilesTab(id)
                    else -> AboutBody(g, onMembers = { tab = InfoTab.MEMBERS }, onSettings = { settings = true })
                }
            }
        }
    }
    if (settings && g != null) ChannelSettingsSheet(g) { settings = false }
}

/** The top of the Members tab: their faces and how many they are. */
@Composable
private fun MembersSummary(g: Group) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val all = g.members.toList()
    val n = 9
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in all.take(n)) {
            Avatar(world.face(p), Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = world.displayName(p)) { nav.go(Routes.profile(p)) }, size = 28.dp)
        }
        if (all.size > n) Text("+${all.size - n}", fontSize = 13.sp, color = c.muted)
        Spacer(Modifier.width(4.dp))
        Text("${all.size} ${if (all.size == 1) "member" else "members"}", fontSize = 13.sp, color = c.muted, maxLines = 1)
    }
}

/** A word that is a link, in a line of text. */
@Composable
private fun LinkWord(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Belfry.colors.text,
        modifier = modifier.clip(RoundedCornerShape4).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
    )
}

private val RoundedCornerShape4 = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)

@Composable
private fun Dot() = Text("·", fontSize = 15.sp, color = Belfry.colors.muted, modifier = Modifier.padding(vertical = 4.dp))

/**
 * About a channel, without boxes (web 2026-11-12): its description, "2/12 active members" with
 * Invite, what a pod is in and how one joins, the e-mail line, its page on the web, its id.
 * [compact]: under the channel's pull-down pulled all the way, which shows the description's
 * first line, the members and the photos itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutBody(g: Group, compact: Boolean = false, onMembers: () -> Unit = {}, onSettings: () -> Unit = {}, onLeave: () -> Unit = {}) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val now = session.now()
    val active = remember(world, g.id) { world.recentlyActive(g.id, now) }
    val admin = world.canAdmin(g)
    val member = world.me in g.members
    var leaving by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    val gap = Modifier.padding(bottom = 12.dp)

    if (!compact) {
        if (g.about.isNotBlank()) MarkdownText(g.about, gap, rich = false)
        else Text("No description yet.", fontSize = 15.sp, color = c.muted, modifier = gap)
    } else if (g.about.trim().contains('\n')) {
        // the pull-down shows its first line: here is the whole text
        MarkdownText(g.about, gap, rich = false)
    }

    FlowRow(gap.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact) {
            Row(
                Modifier.clip(RoundedCornerShape4).clickable(role = Role.Button, onClickLabel = "See the members", onClick = onMembers).testTag("about-members"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active.isNotEmpty()) {
                    AvatarStack(active.take(6).map { world.face(it.pubkey) }, size = 24.dp, max = 6)
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("${active.size}/${g.members.size}") }
                        append(" active members")
                    },
                    fontSize = 15.sp, color = c.text, maxLines = 1, modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            Dot()
            LinkWord("Invite", Modifier.testTag("info-invite")) { nav.go(Routes.invite(g.id)) }
        } else {
            LinkWord("Invite people", Modifier.testTag("info-invite")) { nav.go(Routes.invite(g.id)) }
            if (admin) {
                Dot()
                LinkWord("Settings", onClick = onSettings)
            }
        }
    }

    if (g.isPod) {
        val parent = g.parent?.let { world.group(it) }
        if (!compact && parent != null) {
            FlowRow(gap.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pod in", fontSize = 15.sp, color = c.text, modifier = Modifier.padding(vertical = 4.dp))
                LinkWord(channelLabel(parent, parent.id)) { nav.openChannel(parent.id) }
                Dot()
                Text(if (g.closed) "closed: apply, admins admit" else "open: any member joins", fontSize = 15.sp, color = c.text, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        if (!compact && g.closed) Text("Question: " + (g.prompt?.ifBlank { null } ?: "—"), fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = gap)
        Row(gap) {
            when {
                member -> BusyButton("Leave pod", onClick = { leaving = true }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("about-leave"))
                !g.closed -> BusyButton("Join pod", onClick = { session.send(Actions.join(g.id)) }, small = true)
                g.applications.any { it.pubkey == world.me } -> Text("You asked to join: its admins will answer.", fontSize = 15.sp, color = c.muted)
                else -> BusyButton("Ask to join", onClick = { applying = true }, small = true)
            }
        }
    }

    // the plain slug (bugs@ rather than bugs-c3e5@) when no other channel answers to it
    val address = world.shownMailAddress(g)
    var copied by remember { mutableStateOf(false) }
    Text(
        buildAnnotatedString {
            append(if (world.isInbox(g)) "✉️ Anyone can e-mail " else "✉️ Members can e-mail ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(address) }
            append(
                if (world.isInbox(g)) ": mails land here as cards, and the channel's admins answer as the channel."
                else ": their mail shows as their message.",
            )
            if (copied) withStyle(SpanStyle(color = c.muted)) { append(" Copied.") }
        },
        fontSize = 15.sp, lineHeight = 21.sp, color = c.text,
        modifier = gap.clickable(role = Role.Button, onClickLabel = "Copy the address") { copyText(context, address, "E-mail address"); copied = true }.testTag("about-mail"),
    )

    val page = world.webPage(g)
    if (page != null && page.on && page.slug.isNotEmpty()) {
        val url = "https://${session.community.domain}/${page.slug}"
        Text(
            buildAnnotatedString {
                append((if (page.visibility == "members") "Members only" else "Public") + " · web page: ")
                withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Medium)) { append(url.removePrefix("https://")) }
            },
            fontSize = 13.sp, lineHeight = 18.sp, color = c.muted,
            modifier = gap.clickable(role = Role.Button, onClickLabel = "Open the page") { openInBrowser(context, url) }.testTag("info-web-page"),
        )
    }

    Row(gap, verticalAlignment = Alignment.CenterVertically) {
        Text((if (g.isPod) "Pod" else "Channel") + " id ", fontSize = 13.sp, color = c.muted)
        Text(g.id, fontSize = 13.sp, color = c.muted, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    if (!compact) {
        // not in the web's About (it has them elsewhere): the pages that have no other door here
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkWord("Topics") { nav.go(Routes.topics(g.id)) }
            Dot()
            LinkWord("Search") { nav.go(Routes.search(g.id)) }
            Dot()
            LinkWord("Announcements by e-mail") { nav.go(Routes.follow(g.id)) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (leaving) {
        ConfirmSheet(
            "Leave ${g.name}?", "Leave",
            onConfirm = { session.send(Actions.leave(g.id)); leaving = false; onLeave() },
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
    // its visibility and its page on the web (web 2026-11-11): one setting, kept in one event
    val wasPage = remember(g.id) { world.webPage(g) }
    var public by remember(g.id) { mutableStateOf(wasPage?.visibility != "members") }
    var webOn by remember(g.id) { mutableStateOf(wasPage?.on == true) }
    var slug by remember(g.id) { mutableStateOf(wasPage?.slug?.ifEmpty { null } ?: World.slugify(g.name)) }
    var slugError by remember(g.id) { mutableStateOf<String?>(null) }
    val domain = session.community.domain
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
            sub = "Mail from outside lands here as cards; admins answer as the channel.",
        )
    }
    Hint("The address is ${world.shownMailAddress(g)}. Off: only members' mail comes in, as their message.", Modifier.padding(top = 6.dp))
    if (!g.isPod) {
        Spacer(Modifier.height(14.dp))
        Hint("Visibility")
        Spacer(Modifier.height(6.dp))
        Segmented(listOf("Public", "Members only"), if (public) 0 else 1, { public = it == 0 }, enabled = !busy.busy, modifier = Modifier.testTag("settings-visibility"))
        Hint(if (public) "Anyone reads its threads on the web." else "People from outside see only their own thread.", Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))
        ActionGroup {
            SwitchRow(
                "Threads from the web", webOn, { webOn = it; slugError = null }, icon = BelfryIcons.Globe, enabled = !busy.busy,
                sub = "A page at $domain/${World.slugify(slug).ifEmpty { "…" }} where anyone opens a thread: it lands here as a card, and members' replies are mailed back to its author.",
                modifier = Modifier.testTag("settings-web"),
            )
        }
        if (webOn) {
            Spacer(Modifier.height(10.dp))
            BelfryTextField(
                slug, { slug = it.lowercase().take(60); slugError = null }, label = "Page address", error = slugError, enabled = !busy.busy,
                hint = "$domain/${World.slugify(slug).ifEmpty { "…" }}",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                modifier = Modifier.testTag("settings-slug"),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    BusyButton(
        "Save",
        onClick = {
            val n = name.trim()
            val a = about.trim()
            val address = World.slugify(slug)
            if (!g.isPod && webOn) {
                val problem = world.webSlugProblem(g, address)
                if (problem != null) { slugError = problem; return@BusyButton }
            }
            if (!g.isPod) {
                val visibility = if (public) "public" else "members"
                val keptSlug = if (webOn) address else wasPage?.slug.orEmpty()
                val changed = webOn != (wasPage?.on == true) || keptSlug != wasPage?.slug.orEmpty() || visibility != (wasPage?.visibility ?: "public")
                if (changed) session.send(Actions.setWebPage(g.id, webOn, keptSlug, visibility, wasPage?.posts))
            }
            if (n != g.name || a != g.about || g.isPod) {
                session.send(if (g.isPod) Actions.editPod(g.id, n, a, PodAccess(closed, prompt)) else Actions.editChannel(g.id, n, a))
            }
            if (openMail != wasInbox) session.send(Actions.setInbox(g.id, openMail))
            onClose()
        },
        modifier = Modifier.fillMaxWidth().testTag("settings-save"), enabled = name.isNotBlank(), busy = busy,
    )
}
