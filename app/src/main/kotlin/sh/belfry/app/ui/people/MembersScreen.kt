package sh.belfry.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.PlaceNames
import sh.belfry.core.model.World
import sh.belfry.core.model.fold
import sh.belfry.core.model.groupRequests
import sh.belfry.core.model.mayModerate
import sh.belfry.core.model.memberKeys
import sh.belfry.core.model.vouchesFor
import sh.belfry.core.net.DirMember
import sh.belfry.core.net.Role as BelfryRole

/**
 * Members (web Directory.tsx; iOS MembersDirectory): my card; at the top, only when something
 * waits, the requests (agents asking to join, people asking to join the belfry, requests to my
 * groups, with vouching); then everyone in the belfry with their role and their own agents, by
 * name, with search. Invite for those who may invite members.
 */
@Composable
fun MembersScreen() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    val session = LocalSession.current
    val dir by session.members.directory.collectAsState()
    val roles by session.members.roles.collectAsState()
    val membership by session.members.membership.collectAsState()
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        session.recheckPeople(world.guestJoinKeys)
        try { session.members.refreshDirectory() } catch (e: Exception) { failed = true }
        try { session.members.refreshRequests() } catch (e: Exception) { }
        try { session.members.refreshMembership() } catch (e: Exception) { }
        try { session.members.refreshRoles() } catch (e: Exception) { }
    }
    var q by rememberSaveable { mutableStateOf("") }
    // the belfry's list when it answered, else the people this device knows to be members
    val list: List<DirMember> = dir.ifEmpty {
        world.memberKeys().map { pk -> DirMember(pk, world.realName(pk), roles.members[pk] ?: if (world.isBelfryAdmin(pk)) BelfryRole.ADMIN else null, world.ctx.agents.filterValues { it == pk }.keys.map { it to world.realName(it) }) }
    }
    val words = fold(q).split(' ').filter { it.isNotEmpty() }
    val shown = list.filter { m -> words.all { fold(name(world, m)).contains(it) } }.sortedBy { fold(name(world, it)) }
    val mayInvite = world.isCommunityAdmin || (membership.invite == "members" && membership.mayInviteMembers)
    Column(Modifier.fillMaxSize().background(c.bg).testTag("members")) {
        TopBar(
            PlaceNames.MEMBERS, subtitle = "${list.size} ${if (list.size == 1) "member" else "members"}",
            navigation = { BackButton { nav.back() } },
            actions = { if (mayInvite) IconAction(BelfryIcons.Plus, "Invite", { nav.go(Routes.invite("general")) }, modifier = Modifier.testTag("members-invite")) },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            item(key = "<search>") {
                BelfryTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("members-search"), placeholder = "Search")
            }
            if (q.isEmpty()) {
                item(key = "<me>") {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.go(Routes.profile(world.me)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(world.face(world.me), size = 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(world.realName(world.me).ifEmpty { "You" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                            Text("My card", fontSize = 14.sp, color = c.muted)
                        }
                    }
                    Hairline()
                }
                item(key = "<requests>") { RequestSections() }
            }
            if (dir.isEmpty() && failed && list.isEmpty()) item(key = "<failed>") {
                Text("The list could not be loaded.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(16.dp))
            }
            var letter = ""
            for (m in shown) {
                val l = name(world, m).firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"
                if (l != letter) {
                    letter = l
                    item(key = "<l:$l>") { Text(l, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)) }
                }
                item(key = m.pubkey) { MemberRow(world, m, roles) { nav.go(Routes.profile(it)) } }
            }
            item(key = "<count>") {
                Text("${shown.size} ${if (shown.size == 1) "member" else "members"}", fontSize = 13.sp, color = c.muted, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    }
}

private fun name(world: World, m: DirMember): String = world.realName(m.pubkey).ifEmpty { m.name }.ifEmpty { World.shortKey(m.pubkey) }

@Composable
private fun MemberRow(world: World, m: DirMember, roles: sh.belfry.core.net.RolesInfo, open: (String) -> Unit) {
    val c = Belfry.colors
    val role = m.role?.takeIf { it != BelfryRole.MEMBER }?.let { roles.role(it)?.label ?: if (it == BelfryRole.ADMIN) PlaceNames.STEWARD else null }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(m.pubkey) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(world.face(m.pubkey), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name(world, m), fontSize = 16.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (role != null) Text(role, fontSize = 13.sp, color = c.muted)
            }
        }
        for ((pk, n) in m.agents) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(pk) }.padding(start = 68.dp, end = 16.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(world.face(pk), size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text(world.realName(pk).ifEmpty { n.ifEmpty { "Agent" } } + " · agent", fontSize = 14.sp, color = c.muted, maxLines = 1)
            }
        }
    }
}

/** What waits for me (web Requests.tsx RequestSections, docs/membership.md): only the sections with something in them. */
@Composable
fun RequestSections() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val people by session.members.requests.collectAsState()
    val agents by session.members.agentRequests.collectAsState()
    val membership by session.members.membership.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val admin = world.isCommunityAdmin
    val vouching = membership.accept == "vouches"
    val onError: (Throwable) -> Unit = { error = it.message ?: "That did not work. Try again." }
    Column(Modifier.fillMaxWidth().testTag("requests")) {
        error?.let { Text(it, fontSize = 14.sp, color = c.danger, modifier = Modifier.padding(16.dp)) }
        if (admin && agents.isNotEmpty()) {
            Header("Agents asking to join · ${agents.size}")
            for (a in agents) {
                RequestCard(a.pubkey, a.name.ifEmpty { "An agent" }, if (a.forMe) "Asks to be your agent" else "Asks to join", a.about) {
                    BusyButton("Let it in as my agent", onClick = { session.members.decideAgent(a, true) }, small = true, onError = onError)
                    BusyButton("Decline", onClick = { session.members.decideAgent(a, false) }, kind = ButtonKind.Ghost, small = true, onError = onError)
                }
            }
        }
        if (people.isNotEmpty()) {
            Header("Asking to join ${session.community.name} · ${people.size}")
            for (p in people) {
                val line = listOfNotNull(
                    if (p.isGuest) guestLine(p) else null,
                    if (admin) p.email else null,
                    if (vouching) "${p.vouches} of ${membership.vouches} ${if (membership.vouches == 1) "vouch" else "vouches"}" else null,
                ).joinToString(" · ")
                RequestCard(p.pubkey, p.name.ifEmpty { "Someone" }, line, p.motivation) {
                    if (admin) {
                        BusyButton("Accept", onClick = { session.members.decide(p, true) }, small = true, onError = onError, modifier = Modifier.testTag("request-accept"))
                        BusyButton("Decline", onClick = { session.members.decide(p, false) }, kind = ButtonKind.Ghost, small = true, onError = onError)
                    }
                    if (vouching || !admin) {
                        BusyButton(if (p.vouched) "Take back my vouch" else "Vouch", onClick = { session.members.vouch(p, !p.vouched) }, kind = ButtonKind.Ghost, small = true, onError = onError)
                    }
                }
            }
        }
        for (x in world.groupRequests()) {
            Text(
                x.group.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.muted,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.go(Routes.pod(x.group.id)) }.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            for (a in x.list) {
                val moderate = world.mayModerate(x.group)
                val who = world.vouchesFor(x.group, a)
                val line = if (moderate && x.group.vouches == 0) "Asks to join" else "${who.size} of ${x.group.vouches} ${if (x.group.vouches == 1) "vouch" else "vouches"}" +
                    if (who.isNotEmpty()) " · " + who.joinToString(", ") { world.displayName(it) } else ""
                RequestCard(a.pubkey, world.displayName(a.pubkey), line, a.answer) {
                    if (moderate) {
                        BusyButton("Accept", onClick = { session.send(Actions.admit(x.group.id, a.pubkey)) }, small = true, onError = onError)
                        BusyButton("Decline", onClick = { session.send(Actions.decline(x.group.id, a.pubkey)) }, kind = ButtonKind.Ghost, small = true, onError = onError)
                    } else {
                        val mine = world.me in who
                        BusyButton(if (mine) "Take back my vouch" else "Vouch", onClick = { session.members.vouchInGroup(x.group, a, !mine) }, kind = ButtonKind.Ghost, small = true, onError = onError)
                    }
                }
            }
        }
    }
}

/** "Guest in #garden since 2026-11-12, invited by Ana" (guestapply.py's card for stewards). */
private fun guestLine(p: sh.belfry.core.net.BelfryRequest): String {
    var s = "Guest"
    if (p.channels.isNotEmpty()) s += " in " + p.channels.joinToString(", ") { "#$it" }
    if (p.since.length >= 10) s += " since " + p.since.take(10)
    if (p.inviter.isNotEmpty()) s += ", invited by " + p.inviter
    return s
}

@Composable
private fun Header(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun RequestCard(pubkey: String?, name: String, line: String, text: String, buttons: @Composable () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("request-card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pubkey != null) { Avatar(world.face(pubkey), size = 32.dp); Spacer(Modifier.width(10.dp)) }
            Column {
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                if (line.isNotEmpty()) Text(line, fontSize = 13.sp, color = c.muted)
            }
        }
        if (text.isNotEmpty()) Text(text, fontSize = 15.sp, color = c.text, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
    Hairline(Modifier.padding(start = 16.dp))
}

