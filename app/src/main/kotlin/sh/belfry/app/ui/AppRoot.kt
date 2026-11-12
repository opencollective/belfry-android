package sh.belfry.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import sh.belfry.app.Adding
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.AccountsUi
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.shell.AccountRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.belfry.app.AppState
import sh.belfry.app.Phase
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.nav.AppNav
import sh.belfry.core.push.PushOpen
import sh.belfry.app.ui.nav.AppNavigator
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalSignOut
import sh.belfry.app.ui.nav.LocalStatus
import sh.belfry.app.ui.nav.SessionScope
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.app.ui.signin.JoinScreen
import sh.belfry.app.ui.signin.OpeningScreen
import sh.belfry.app.ui.people.GuestJoinHost
import sh.belfry.app.ui.signin.NoAccessScreen
import sh.belfry.app.ui.signin.SignInScreen
import sh.belfry.app.ui.signin.Splash
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.AccessState
import sh.belfry.core.engine.ActionException
import sh.belfry.core.engine.Invitation
import sh.belfry.core.engine.OnboardingException
import sh.belfry.core.engine.Session
import sh.belfry.core.net.InviteLink

/** The whole app under the theme: which of the four phases shows. */
@Composable
fun AppRoot(state: AppState) {
    val phase by state.phase.collectAsState()
    val adding by state.adding.collectAsState()
    val opening by state.opening.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Phases(state, phase, adding)
        // A guest link being opened (SPEC 19.26): one loading screen over everything, from the link
        // until the channel can be drawn in full; nothing under it shows in between.
        opening?.let { o -> Over { OpeningScreen(o.channelName) } }
    }
}

@Composable
private fun Phases(state: AppState, phase: Phase, adding: Adding?) {
    when (val p = phase) {
        is Phase.Loading -> Splash()
        is Phase.SignedOut -> SignInScreen(state)
        is Phase.Joining -> JoinScreen(state, p.link)
        is Phase.SignedIn -> Box(Modifier.fillMaxSize()) {
            key(p.session) { SignedIn(state, p.session) }
            // Adding another account: the sign-in screens over the app, which stays as it was under them.
            when (val a = adding) {
                null -> Unit
                is Adding.SignIn -> Over { SignInScreen(state, onBack = { state.cancelAdd() }) }
                is Adding.Join -> Over { JoinScreen(state, a.link) }
            }
        }
    }
}

/** A full screen over the app that takes every touch (nothing under it can be tapped). */
@Composable
private fun Over(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Belfry.colors.bg).pointerInput(Unit) { detectTapGestures { } }.testTag("add-account")) { content() }
}

@Composable
private fun SignedIn(state: AppState, session: Session) {
    val controller = rememberNavController()
    val shell = remember { ShellState(session.guest.value?.channel ?: "general") }
    val nav = remember { AppNavigator(controller, shell) }
    val signOut = remember { suspend { state.signOut() } }
    val view by state.accounts.collectAsState()
    val accounts = remember(view, session) {
        AccountsUi(
            others = view.list.accounts.filter { it.id != session.accountId },
            unread = view.unread,
            switchTo = { state.switchTo(it) },
            add = { state.addAccount() },
        )
    }
    // The channel that was open last time (not when a channel waits to be opened after signing in).
    LaunchedEffect(Unit) {
        val last = withContext(Dispatchers.IO) { session.store.get(ShellState.K_LAST_CHANNEL) }
        if (last != null && session.guest.value == null && state.openAfter.value?.first != session.community.domain) shell.restore(last)
    }
    // A guest link just used: the loading screen stays until the relay has let this key in and sent
    // what it has (or said no), at most 10 s more.
    val openingAll by state.opening.collectAsState()
    val opening = openingAll?.takeIf { it.account == session.accountId }
    val status by session.status.collectAsState()
    LaunchedEffect(opening != null, status.access) {
        if (opening == null) return@LaunchedEffect
        if (session.localOnly || status.access != AccessState.UNKNOWN) state.doneOpening()
        else { kotlinx.coroutines.delay(10_000); state.doneOpening() }
    }
    CompositionLocalProvider(LocalSignOut provides signOut, LocalAccounts provides accounts) {
        SessionScope(session, nav) {
            if (opening != null) {
                // under the loading screen: nothing is drawn (no empty channel, no home)
            } else if (LocalStatus.current.access == AccessState.REFUSED) {
                NoAccessScreen()
            } else {
                AppNav(controller, shell)
                GuestJoinHost(onSignIn = { email, channel, moved, nip05 -> state.signInInstead(email, channel, moved, nip05) })
                OpenAfterSignIn(state)
                JoinOffer(state)
                ChooseAccount(state)
                EventLinkOpener(state)
                PushOpener(state)
            }
        }
    }
}

/** Signed in after guest mode said "you have an account here": the channel they were reading. */
@Composable
private fun OpenAfterSignIn(state: AppState) {
    val open = state.openAfter.collectAsState().value ?: return
    val session = LocalSession.current
    val nav = LocalNav.current
    val guest by session.guest.collectAsState()
    if (open.first != session.community.domain || guest?.reading == true) return
    LaunchedEffect(open) {
        state.forgetOpenAfter()
        nav.openChannel(open.second)
    }
}

/** A guest link for a belfry where this device has several accounts: which one joins? */
@Composable
private fun ChooseAccount(state: AppState) {
    val link = state.chooseFor.collectAsState().value ?: return
    val session = LocalSession.current
    val view by state.accounts.collectAsState()
    val here = view.list.onDomain(link.domain)
    val busy = rememberBusy()
    BelfrySheet(onDismiss = { state.forgetChoice() }, title = "Join with which account?") {
        Text("You have ${here.size} accounts in this community. The invitation adds a channel to the one you pick.", fontSize = 15.sp, color = Belfry.colors.muted)
        Spacer(Modifier.height(16.dp))
        ActionGroup {
            for (a in here) {
                AccountRow(
                    pubkey = a.pubkey, name = a.name.ifEmpty { "Account" }, line = a.line, picture = a.picture,
                    current = a.id == session.accountId, unread = false, busy = busy, onClick = { state.chooseAccount(a.id) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        BusyButton("Not now", onClick = { state.forgetChoice() }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
    }
}

/**
 * Opened from an event's address (`https://chat.<domain>/events/<link>`): its page, which says
 * "There is no such event here." when the belfry doesn't know it. Another community's event: a
 * fixed message.
 */
@Composable
private fun EventLinkOpener(state: AppState) {
    val link = state.eventLink.collectAsState().value ?: return
    val session = LocalSession.current
    val nav = LocalNav.current
    if (link.domain == session.community.domain) {
        LaunchedEffect(link) {
            state.forgetEventLink()
            nav.go(Routes.event(link.link))
        }
        return
    }
    BelfrySheet(onDismiss = { state.forgetEventLink() }, title = "Another community’s event") {
        Text("This event is in a community you have no account in on this device. Add an account there to open it here, or open it in the browser.", fontSize = 15.sp, color = Belfry.colors.muted)
        Spacer(Modifier.height(16.dp))
        BusyButton("OK", onClick = { state.forgetEventLink() }, modifier = Modifier.fillMaxWidth())
    }
}

/** A notification was tapped: the channel at the message, the conversation list, or home. */
@Composable
private fun PushOpener(state: AppState) {
    val open = state.pushOpen.collectAsState().value ?: return
    val nav = LocalNav.current
    LaunchedEffect(open) {
        state.forgetPushOpen()
        when (open) {
            is PushOpen.Channel -> nav.openChannel(open.channel, open.message)
            is PushOpen.Thread -> { nav.openChannel(open.channel); nav.go(Routes.thread(open.channel, open.root)) }
            PushOpen.Dms -> nav.go(Routes.MESSAGES)
            PushOpen.Home -> {}
        }
    }
}

/** Signed in and opened from a guest link: join that channel as yourself (no new account). */
@Composable
private fun JoinOffer(state: AppState) {
    val link: InviteLink = state.joinOffer.collectAsState().value ?: return
    val c = Belfry.colors
    val session = LocalSession.current
    val nav = LocalNav.current
    var invitation by remember(link) { mutableStateOf<Invitation?>(null) }
    var error by remember(link) { mutableStateOf<String?>(null) }
    val sameCommunity = link.domain == session.community.domain
    LaunchedEffect(link) {
        if (sameCommunity) {
            try {
                invitation = state.inviteInfo(link)
            } catch (e: OnboardingException) {
                error = e.message
            } catch (e: Exception) {
                error = "No connection to the belfry. Try again."
            }
        }
    }
    BelfrySheet(onDismiss = { state.forgetInvite() }, title = "Join with your account?") {
        val info = invitation?.info
        val text = when {
            !sameCommunity -> "This invite is for another community."
            info == null -> if (error == null) "Opening the invite…" else ""
            info.closedMessage != null -> info.closedMessage ?: ""
            else -> "${info.inviter.ifEmpty { "A member" }} invited you to # ${info.channelName}. You join as yourself: no new account."
        }
        if (text.isNotEmpty()) Text(text, fontSize = 15.sp, color = c.muted)
        error?.let { Text(it, fontSize = 14.sp, color = c.danger) }
        Spacer(Modifier.height(16.dp))
        BusyButton(
            if (info != null && info.isOpen) "Join # ${info.channelName}" else "Join",
            onClick = {
                error = null
                val joined = session.joinWithAccount(link.token)
                state.forgetInvite()
                nav.openChannel(joined.channel)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = sameCommunity && info != null && info.isOpen,
            onError = { error = (it as? ActionException)?.message ?: "Could not join. Try again." },
        )
        Spacer(Modifier.height(8.dp))
        BusyButton("Not now", onClick = { state.forgetInvite() }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
    }
}
