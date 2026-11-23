package sh.belfry.app.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import sh.belfry.core.model.ChannelAccess
import sh.belfry.core.model.ChannelType
import sh.belfry.core.model.channelAccess
import sh.belfry.core.model.mayClose
import sh.belfry.core.model.setVisibility
import sh.belfry.core.model.webSettings
import kotlinx.coroutines.launch
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
import sh.belfry.core.model.GroupJoin
import sh.belfry.core.model.ChannelPosts
import sh.belfry.core.model.PostKind
import sh.belfry.app.ui.cards.GroupJoinChoices
import sh.belfry.core.model.World

/** The tabs of a channel's page, in the web's order (c6579a0). */
object InfoTab {
    const val ABOUT = 0
    const val MEMBERS = 1
    const val TODOS = 2
    const val FILES = 3
    const val LOG = 4
    val LABELS = listOf("About", "Members", "To-dos", "Files", "Log")

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
                    IconAction(BelfryIcons.Settings, if (g.isPod) "Group settings" else "Channel settings", { settings = true }, modifier = Modifier.testTag("info-settings"))
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
                Text("Group in", fontSize = 15.sp, color = c.text, modifier = Modifier.padding(vertical = 4.dp))
                LinkWord(channelLabel(parent, parent.id)) { nav.openChannel(parent.id) }
                Dot()
                Text(when { g.isPrivate -> "private: only invited people"; g.closed -> "closed: apply, admins admit"; else -> "open: any member joins" }, fontSize = 15.sp, color = c.text, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        if (!compact && g.closed && !g.isPrivate) Text("Question: " + (g.prompt?.ifBlank { null } ?: "—"), fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = gap)
        Row(gap) {
            when {
                member -> BusyButton("Leave group", onClick = { leaving = true }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("about-leave"))
                // a Private group has no Join / Ask to join (SPEC 19.59)
                g.isPrivate -> {}
                !g.closed -> BusyButton("Join group", onClick = { session.send(Actions.join(g.id)) }, small = true)
                g.applications.any { it.pubkey == world.me } -> Text("You asked to join: its admins will answer.", fontSize = 15.sp, color = c.muted)
                else -> BusyButton("Ask to join", onClick = { applying = true }, small = true)
            }
        }
    }

    // the plain slug (bugs@ rather than bugs-c3e5@) when no other channel answers to it
    val address = world.shownMailAddress(g)
    var copied by remember { mutableStateOf(false) }
    // pulled down (compact): no address line (SPEC 19.60: it lives in Settings → E-mail)
    if (!compact) Text(
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
        Text((if (g.isPod) "Group" else "Channel") + " id ", fontSize = 13.sp, color = c.muted)
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
            message = "You stop being a member of this group. You can join again later.",
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
    BelfrySheet(onClose, title = if (g.isPod) "Group settings" else "Channel settings") {
        ChannelSettingsForm(g, onClose)
    }
}

@Composable
fun ChannelSettingsForm(g: Group, onClose: () -> Unit, askPublicAtOnce: Boolean = false, pin: (@Composable () -> Unit)? = null) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val wasInbox = remember(g.id) { world.isInbox(g) }
    // its own name, never the on-brand one shown for a default place (SPEC 19.31: display only)
    val raw = g.rawName.ifEmpty { g.name }
    var name by remember(g.id) { mutableStateOf(raw) }
    var about by remember(g.id) { mutableStateOf(g.about) }
    var prompt by remember(g.id) { mutableStateOf(g.prompt ?: "") }
    var vouches by remember(g.id) { mutableStateOf(g.vouches) }
    // a group: who can join, the same three choices as New group (SPEC 19.59)
    var join by remember(g.id) { mutableStateOf(GroupJoin.of(g)) }
    var openMail by remember(g.id) { mutableStateOf(wasInbox) }
    // touched = sent on Save even when it ends where it started (the start may have been read stale)
    var mailTouched by remember(g.id) { mutableStateOf(false) }
    // who reads it (round 6, SPEC 19.41): members only / public (asks first) / closed
    // a group: who reads its page (members only / public), next to who can join (SPEC 19.59 as ruled at d85f408)
    val wasAccess = remember(g.id) { if (g.isPod) (if (world.isPublicChannel(g)) ChannelAccess.PUBLIC else ChannelAccess.MEMBERS) else world.channelAccess(g) }
    var access by remember(g.id) { mutableStateOf(wasAccess) }
    var askPublic by remember(g.id) { mutableStateOf(askPublicAtOnce) }
    // Posts (SPEC 19.60, channels/settings): the kinds it takes, all on by default, one at least
    val wasAccepts = remember(g.id) { world.webPage(g)?.accepts ?: ChannelPosts.ALL }
    var accepts by remember(g.id) { mutableStateOf(wasAccepts) }
    // E-mail: the channel's address through the belfry (SPEC 19.60)
    val address = if (g.type == ChannelType.CHANNEL) rememberAddressEdit(g) else null
    var saveError by remember { mutableStateOf<String?>(null) }
    val busy = rememberBusy()
    val kind = if (g.isPod) "group" else "channel"

    BelfryTextField(name, { name = it }, label = "Name", enabled = !busy.busy)
    Spacer(Modifier.height(12.dp))
    BelfryTextField(
        about, { about = it }, label = "Description", hint = "Markdown works, links become clickable",
        singleLine = false, minLines = 3, enabled = !busy.busy,
    )
    // started from a printed card: "The card", for its stewards (SPEC 19.76; canvas cards/steward)
    sh.belfry.app.ui.cards.StewardCardSection(g)
    if (g.isPod) {
        Spacer(Modifier.height(14.dp))
        Hint("Who can join")
        Spacer(Modifier.height(6.dp))
        GroupJoinChoices(join, { join = it }, enabled = !busy.busy, modifier = Modifier.testTag("settings-access"))
        // groups/settings-closed: the request question and who accepts, for Closed only
        if (join == GroupJoin.CLOSED) {
            Spacer(Modifier.height(14.dp))
            Hint("Requests")
            Spacer(Modifier.height(6.dp))
            BelfryTextField(prompt, { prompt = it }, label = "Question people answer when they ask", placeholder = Actions.DEFAULT_PROMPT, singleLine = false, enabled = !busy.busy, modifier = Modifier.testTag("settings-prompt"))
            Spacer(Modifier.height(12.dp))
            Hint("Requests are accepted")
            Spacer(Modifier.height(6.dp))
            ActionGroup(Modifier.testTag("settings-vouch")) {
                ActionRow(
                    "By its stewards", onClick = { vouches = 0 }, icon = BelfryIcons.Users, enabled = !busy.busy,
                    sub = "A steward accepts or declines each request.",
                    modifier = Modifier.testTag("vouch-stewards").semantics { selected = vouches == 0 },
                    trailing = { if (vouches == 0) Icon(BelfryIcons.Check, "Selected", tint = c.text, modifier = Modifier.size(22.dp)) },
                )
                Hairline()
                ActionRow(
                    "When members vouch", onClick = { if (vouches == 0) vouches = 2 }, icon = Icons.Outlined.PersonAdd, enabled = !busy.busy,
                    sub = "Once enough of its members vouch (not guests, not agents), they’re in. Stewards can still answer.",
                    modifier = Modifier.testTag("vouch-members").semantics { selected = vouches > 0 },
                    trailing = { if (vouches > 0) Icon(BelfryIcons.Check, "Selected", tint = c.text, modifier = Modifier.size(22.dp)) },
                )
            }
            if (vouches > 0) {
                Spacer(Modifier.height(8.dp))
                Segmented((1..5).map { if (it == 1) "1 vouch" else "$it" }, vouches - 1, { vouches = it + 1 }, enabled = !busy.busy)
            }
        }
        // who reads its page, as before 0.8 (same wire, same words); a Private group has no Public
        val isPriv = join == GroupJoin.PRIVATE
        val shown = if (isPriv) ChannelAccess.MEMBERS else access
        Spacer(Modifier.height(14.dp))
        Hint("Who can read its page")
        Spacer(Modifier.height(6.dp))
        ActionGroup(Modifier.testTag("settings-page")) {
            val rows = buildList {
                add(Triple(ChannelAccess.MEMBERS, BelfryIcons.Lock, "Members only" to "Everyone with a pass in this belfry"))
                if (!isPriv) add(Triple(ChannelAccess.PUBLIC, BelfryIcons.Globe, "Public" to "Anyone on the web can read it. Only members write."))
            }
            rows.forEachIndexed { i, (a, icon, words) ->
                if (i > 0) Hairline()
                ActionRow(
                    words.first,
                    onClick = { if (a != access) { if (a == ChannelAccess.PUBLIC) askPublic = true else access = a } },
                    icon = icon, sub = words.second, enabled = !busy.busy,
                    modifier = Modifier.testTag("access-${a.key}").semantics { selected = shown == a },
                    trailing = { if (shown == a) Icon(BelfryIcons.Check, "Selected", tint = c.text, modifier = Modifier.size(22.dp)) },
                )
            }
        }
        Hint(
            if (isPriv && access == ChannelAccess.PUBLIC) "A private group's page is for its members only: it stops being public when you save."
            else "A change counts from now on. Messages keep the visibility they were posted with.",
            Modifier.padding(top = 6.dp),
        )
    }
    if (g.type == ChannelType.CHANNEL) {
        Spacer(Modifier.height(14.dp))
        Hint("Who can read it")
        Spacer(Modifier.height(6.dp))
        ActionGroup(Modifier.testTag("settings-access")) {
            val rows = buildList {
                add(Triple(ChannelAccess.MEMBERS, BelfryIcons.Lock, "Members only" to "Everyone with a pass in this belfry"))
                add(Triple(ChannelAccess.PUBLIC, BelfryIcons.Globe, "Public" to "Anyone on the web can read it. Only members write."))
                if (mayClose(g)) add(Triple(ChannelAccess.CLOSED, Icons.Outlined.PersonAdd, "Closed" to "People ask to join. A steward of this $kind approves."))
            }
            rows.forEachIndexed { i, (a, icon, words) ->
                if (i > 0) Hairline()
                ActionRow(
                    words.first,
                    onClick = { if (a != access) { if (a == ChannelAccess.PUBLIC) askPublic = true else access = a } },
                    icon = icon, sub = words.second, enabled = !busy.busy,
                    modifier = Modifier.testTag("access-${a.key}").semantics { selected = access == a },
                    trailing = { if (access == a) Icon(BelfryIcons.Check, "Selected", tint = c.text, modifier = Modifier.size(22.dp)) },
                )
            }
        }
        Hint("A change counts from now on. Messages keep the visibility they were posted with.", Modifier.padding(top = 6.dp))
    }
    if (g.type == ChannelType.CHANNEL) {
        Spacer(Modifier.height(14.dp))
        Hint("Posts")
        Spacer(Modifier.height(6.dp))
        ActionGroup(Modifier.testTag("settings-posts")) {
            for (k in PostKind.entries) {
                val on = k in accepts
                SwitchRow(
                    k.label, on, { want -> accepts = ChannelPosts.toggle(accepts, k, want) },
                    // the last one on can't be switched off
                    enabled = !busy.busy && ChannelPosts.mayTurnOff(accepts, k),
                    sub = k.line, modifier = Modifier.testTag("posts-${k.key}"),
                )
            }
        }
        Hint("All on by default; at least one stays on.", Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(14.dp))
    Hint("E-mail")
    Spacer(Modifier.height(6.dp))
    if (address != null) {
        AddressSection(g, address, enabled = !busy.busy)
        Spacer(Modifier.height(10.dp))
    }
    ActionGroup {
        SwitchRow(
            "E-mails from anyone", openMail, { openMail = it; mailTouched = true }, icon = BelfryIcons.Inbox, enabled = !busy.busy,
            sub = if (openMail) "Mail from outside lands here as cards; admins answer as the $kind." else "Off: only members can e-mail it",
        )
    }
    if (g.type == ChannelType.CHANNEL) MailForwardRow(g.id)
    if (pin != null) {
        Spacer(Modifier.height(14.dp))
        pin()
    }
    saveError?.let { Text(it, fontSize = 13.sp, color = c.danger, modifier = Modifier.padding(top = 10.dp).testTag("settings-error")) }
    Spacer(Modifier.height(16.dp))
    BusyButton(
        "Save",
        onClick = {
            val n = name.trim()
            val a = about.trim()
            saveError = null
            // the address first: a refusal (taken, reserved…) keeps the form open with its words
            address?.save(session)
            if (g.type == ChannelType.CHANNEL) {
                // public / members only first, then closed (going closed from public writes both)
                // one web-settings event for who reads it and its type of post
                Actions.webSettings(g, world, session.now(), access == ChannelAccess.PUBLIC, world.webPage(g)?.posts, accepts = accepts)?.let { session.send(it) }
            }
            if (g.isPod) {
                // its page: members only / public as before 0.8; a Private group's page is members only
                Actions.webSettings(g, world, session.now(), access == ChannelAccess.PUBLIC && join != GroupJoin.PRIVATE, world.webPage(g)?.posts)?.let { session.send(it) }
                // a group (SPEC 19.59): Open / Closed (+ its question, who accepts) / Private
                val joinChanged = join != GroupJoin.of(g)
                val closedRules = join == GroupJoin.CLOSED && (prompt.trim() != (g.prompt ?: "") || vouches != g.vouches)
                if (n != raw || a != g.about || joinChanged || closedRules) {
                    session.members.saveGroupMeta(
                        g, name = n, about = a,
                        closed = if (joinChanged) join.closed else null,
                        prompt = if (join == GroupJoin.CLOSED) prompt.trim() else null,
                        vouches = if (join == GroupJoin.CLOSED) vouches else null,
                        isPrivate = if (joinChanged) join.isPrivate else null,
                    )
                }
            } else {
            val closed = access == ChannelAccess.CLOSED
            val closedChanged = g.type == ChannelType.CHANNEL && closed != g.closed
            if (n != raw || a != g.about || closedChanged) {
                // one 9002 keeping every other setting (picture, archived: SPEC 19.30)
                session.members.saveGroupMeta(
                    g, name = n, about = a,
                    closed = if (closedChanged) closed else null,
                    prompt = if (closed && closedChanged) g.prompt ?: "" else null,
                    vouches = if (closedChanged) 0 else null,
                )
            }
            }
            if (mailTouched || openMail != wasInbox) session.send(Actions.setInbox(g.id, openMail))
            onClose()
        },
        modifier = Modifier.fillMaxWidth().testTag("settings-save"), enabled = name.isNotBlank(), busy = busy,
        onError = { saveError = (it as? sh.belfry.core.engine.ActionException)?.message ?: "Not saved. Try again." },
    )
    if (askPublic) {
        AlertDialog(
            onDismissRequest = { askPublic = false },
            title = { Text("Make ${channelLabel(g, g.id)} public?") },
            text = { Text("From now on, anyone on the web can read what is posted here, with the names of the people who post. Earlier messages stay for members only.") },
            confirmButton = {
                TextButton(onClick = { access = ChannelAccess.PUBLIC; askPublic = false }, modifier = Modifier.testTag("make-public")) {
                    Text("Make it public", color = c.text, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { askPublic = false }, modifier = Modifier.testTag("keep-members")) { Text("Keep it for members", color = c.text) }
            },
            containerColor = c.bg,
            modifier = Modifier.testTag("make-public-dialog"),
        )
    }
}

/**
 * "Forward all e-mails to" (channels/settings; collective-belfry docs/mail.md "Forwarding"): the
 * belfry's own setting, asked and saved at once through the API (never an event: every member
 * would read the address). Nothing is forwarded until the outside address confirms.
 */
@Composable
private fun MailForwardRow(channel: String) {
    val c = Belfry.colors
    val session = LocalSession.current
    var loaded by remember(channel) { mutableStateOf(false) }
    var fwd by remember(channel) { mutableStateOf<sh.belfry.core.net.MailForward?>(null) }
    var on by remember(channel) { mutableStateOf(false) }
    var address by remember(channel) { mutableStateOf("") }
    var note by remember(channel) { mutableStateOf<String?>(null) }
    var error by remember(channel) { mutableStateOf<String?>(null) }
    var saving by remember(channel) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun failed(e: Exception) { error = (e as? sh.belfry.core.engine.ActionException)?.message ?: "That did not work. Try again." }
    LaunchedEffect(channel) {
        try { fwd = session.members.mailForward(channel); on = fwd != null; address = fwd?.address ?: "" } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed(e) }
        loaded = true
    }
    ActionGroup(Modifier.padding(top = 10.dp)) {
        SwitchRow(
            "Forward all e-mails to", on, { want ->
                error = null; note = null
                if (want) on = true
                else {
                    saving = true
                    scope.launch {
                        try { fwd = session.members.setMailForward(channel, null); on = false; address = "" } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed(e) }
                        saving = false
                    }
                }
            },
            icon = BelfryIcons.Mail, sub = "E-mails still show up here. A copy goes to this address.", enabled = loaded, busy = saving,
            modifier = Modifier.testTag("settings-forward"),
        )
    }
    if (on) {
        val f = fwd
        if (f != null) Text(
            if (f.on) "Forwarding to ${f.address}" else "Waiting for ${f.address} to confirm",
            fontSize = 14.sp, color = c.text, modifier = Modifier.padding(top = 8.dp).testTag("forward-state"),
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BelfryTextField(
                address, { address = it.take(200); error = null; note = null }, Modifier.weight(1f).testTag("forward-address"), placeholder = "name@example.org",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
            )
            Spacer(Modifier.width(8.dp))
            BusyButton("Save", onClick = {
                error = null; note = null
                try {
                    val n = session.members.setMailForward(channel, address.trim())
                    fwd = n
                    if (n != null && !n.on) note = "We sent a confirmation e-mail. Forwarding starts once it is confirmed."
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed(e) }
            }, small = true, enabled = address.isNotBlank() && !address.trim().equals(f?.address ?: "", ignoreCase = true), modifier = Modifier.testTag("forward-save"))
        }
        if (f != null && !f.on) {
            BusyButton("Send the confirmation again", onClick = {
                error = null
                try { fwd = session.members.resendMailForward(channel); note = "Sent again." } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed(e) }
            }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.padding(top = 6.dp))
        }
        val dom = f?.address?.substringAfter('@', "").orEmpty()
        if (f != null && f.sameDomainSkipped && dom.isNotEmpty()) Hint("E-mails sent from @$dom addresses are not forwarded.", Modifier.padding(top = 6.dp))
        note?.let { Hint(it, Modifier.padding(top = 6.dp)) }
    }
    error?.let { ErrorLine(it, Modifier.padding(top = 6.dp)) }
}
