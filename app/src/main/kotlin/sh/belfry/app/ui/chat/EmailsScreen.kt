package sh.belfry.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.Tabs
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.Actions
import sh.belfry.core.model.CardStatus
import sh.belfry.core.model.InboxThread
import sh.belfry.core.model.World

/** The four lists of a channel's e-mails (one-shape/inbox-emails), like the to-dos'. */
enum class MailTab(val label: String) { OPEN("Open"), MINE("Mine"), ANSWERED("Answered"), CLOSED("Closed") }

fun World.mailTab(groupId: String, tab: MailTab): List<InboxThread> {
    val all = mailThreads(groupId).list.asReversed() // newest first
    return when (tab) {
        MailTab.OPEN -> all.filter { it.state.status == CardStatus.OPEN }
        MailTab.MINE -> all.filter { it.state.assignee == me && it.state.status != CardStatus.CLOSED }
        MailTab.ANSWERED -> all.filter { it.state.status == CardStatus.WAITING }
        MailTab.CLOSED -> all.filter { it.state.status == CardStatus.CLOSED }
    }
}

/**
 * A channel's e-mails, pushed from its pull-down's "E-mails" row: Open / Mine / Answered / Closed,
 * each row the subject, sender · time, and who has it; Claim takes one tap (it locks, then the row
 * says "You have it"). A row opens its thread.
 */
@Composable
fun EmailsScreen(groupId: String) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val nav = LocalNav.current
    var tab by rememberSaveable(groupId) { mutableIntStateOf(0) }
    val tabs = MailTab.entries
    Column(Modifier.fillMaxSize().background(c.bg).testTag("place-emails")) {
        TopBar("E-mails", subtitle = world.group(groupId)?.name, navigation = { BackButton { nav.back() } })
        Tabs(tabs.map { t -> world.mailTab(groupId, t).size.let { if (it > 0 && t != MailTab.ANSWERED && t != MailTab.CLOSED) "${t.label} $it" else t.label } }, tab, { tab = it }, Modifier.testTag("emails-tabs"))
        val list = world.mailTab(groupId, tabs[tab])
        if (list.isEmpty()) {
            EmptyState(
                when (tabs[tab]) { MailTab.OPEN -> "No open e-mails"; MailTab.MINE -> "Nothing on you"; MailTab.ANSWERED -> "Nothing answered yet"; MailTab.CLOSED -> "Nothing closed" },
                icon = BelfryIcons.Mail,
            )
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            items(list, key = { it.id }) { t ->
                MailListRow(groupId, t) { nav.go(Routes.thread(groupId, t.id)) }
                Hairline(Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun MailListRow(groupId: String, t: InboxThread, onOpen: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val who = t.name.ifEmpty { t.address }
    val owner = t.state.assignee
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open the e-mail", onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp).testTag("emails-row")) {
        Text(t.subject.ifEmpty { "(no subject)" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$who · ${ChatFormat.short(world, t.last, session.now())}", fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (owner != null) {
                Avatar(world.face(owner), size = 20.dp)
                Spacer(Modifier.width(6.dp))
                Text(if (owner == world.me) "You have it" else "${world.displayName(owner)} has it", fontSize = 14.sp, fontWeight = if (owner == world.me) FontWeight.SemiBold else FontWeight.Normal, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else {
                Text("Nobody has it yet", fontSize = 14.sp, color = c.muted, modifier = Modifier.weight(1f))
                if (t.state.status != CardStatus.CLOSED) {
                    BusyButton("Claim", onClick = { session.send(Actions.assignCard(groupId, t.id, world.me)) }, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("emails-claim"))
                }
            }
        }
    }
}
