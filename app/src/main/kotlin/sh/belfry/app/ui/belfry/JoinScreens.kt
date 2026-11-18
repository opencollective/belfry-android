package sh.belfry.app.ui.belfry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import kotlinx.coroutines.launch
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.Agreement
import sh.belfry.core.model.charter

/**
 * A guest asks to become a member (collective-belfry guestapply.py; SPEC 19.32): a few words, the
 * charter's agreements ticked on the way, then the request's state ("1 of 2 vouches", "Waiting
 * for a steward", "You're a member now").
 */
@Composable
fun ApplyScreen() {
    val c = Belfry.colors
    val session = LocalSession.current
    val nav = LocalNav.current
    val apply by session.members.apply.collectAsState()
    val rules by session.members.publicCharter.collectAsState()
    val guest by session.guest.collectAsState()
    val world = LocalWorld.current
    // never to a member (a member link, an admin's decision): the belfry is theirs already
    if (!world.mayApply(guest?.reading == true)) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    LaunchedEffect(Unit) {
        try { session.members.refreshPublicCharter() } catch (e: Exception) { }
        try { session.members.refreshApply() } catch (e: Exception) { }
    }
    var answer by remember { mutableStateOf("") }
    val ticked = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    val open = apply?.application?.isOpen == true
    Column(Modifier.fillMaxSize().background(c.bg).testTag("apply")) {
        TopBar("Become a member", subtitle = session.community.name, navigation = { BackButton { nav.back() } })
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            val line = apply?.line
            if (line != null) {
                Text("Your request", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text(line, fontSize = 16.sp, color = c.text, modifier = Modifier.padding(top = 4.dp).testTag("apply-state"))
                Spacer(Modifier.height(16.dp))
            }
            if (open || apply?.application?.state == "accepted") return@Column
            Text("Tell the stewards who you are and why you'd like to be a member.", fontSize = 16.sp, color = c.text)
            Spacer(Modifier.height(10.dp))
            BelfryTextField(answer, { answer = it.take(2000) }, Modifier.fillMaxWidth().testTag("apply-answer"), label = "A few words", singleLine = false, minLines = 4)
            if (rules.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("The charter", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text("Members agree to these; tick the ones you agree to.", fontSize = 14.sp, color = c.muted)
                for (a in rules) TickRow(a, a.id in ticked) { on -> if (on) ticked.add(a.id) else ticked.remove(a.id) }
            }
            error?.let { Text(it, color = c.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
            BusyButton("Send my request", onClick = {
                try {
                    session.members.guestApply(answer, ticked.toList()); error = null
                } catch (e: ActionException) {
                    // `not_guest` from a member: no error, the belfry opens again as theirs
                    if (e.code == sh.belfry.core.model.GuestMode.ALREADY_MEMBER) { error = null; nav.back() } else error = e.message
                }
            }, enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("apply-send"))
        }
    }
}

@Composable
private fun TickRow(a: Agreement, on: Boolean, onTick: (Boolean) -> Unit) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Checkbox(on, onTick, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent))
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            sh.belfry.app.ui.kit.AgreementText(a.title, a.about)
        }
    }
}

/**
 * A new member's first minutes (web welcomeDue; SPEC 19.32): Welcome (the belfry's name and
 * description) → each agreement ticked ("4 of 6 agreed") → name and photo, with Skip.
 */
@Composable
fun WelcomeScreen() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    var step by remember { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rules = world.charter()
    Column(Modifier.fillMaxSize().background(c.bg).testTag("welcome-$step")) {
        TopBar(if (step == 0) "Welcome" else if (step == 1) "The charter" else "You", navigation = { BackButton { nav.back() } })
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            when (step) {
                0 -> {
                    Text("Welcome to ${session.community.name}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.text)
                    Spacer(Modifier.height(10.dp))
                    Text("You are a member now. First, the agreements everyone here ticks.", fontSize = 16.sp, color = c.muted, modifier = Modifier.padding(top = 12.dp))
                    Spacer(Modifier.height(24.dp))
                    BusyButton("Read the charter", onClick = { step = if (rules.isEmpty()) 2 else 1 }, modifier = Modifier.fillMaxWidth().testTag("welcome-next"))
                }
                1 -> {
                    val agreed = rules.count { world.me in it.agreed }
                    Text("$agreed of ${rules.size} agreed", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    for (a in rules) TickRow(a, world.me in a.agreed) { on -> scope.launch { try { session.members.agree(a, on) } catch (e: Exception) { } } }
                    Spacer(Modifier.height(20.dp))
                    BusyButton("Next", onClick = { step = 2 }, enabled = agreed == rules.size, modifier = Modifier.fillMaxWidth().testTag("welcome-next"))
                    BusyButton("Skip", onClick = { step = 2 }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                else -> {
                    Text("How people see you", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Text("Your name and a photo help people recognise you.", fontSize = 16.sp, color = c.muted, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(20.dp))
                    BusyButton("Add my name and photo", onClick = { nav.back(); nav.go(Routes.ME) }, modifier = Modifier.fillMaxWidth().testTag("welcome-profile"))
                    BusyButton("Skip", onClick = { nav.back() }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}

