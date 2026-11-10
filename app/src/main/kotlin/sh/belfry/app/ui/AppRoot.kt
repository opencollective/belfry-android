package sh.belfry.app.ui

import androidx.compose.foundation.layout.Spacer
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
import sh.belfry.app.ui.nav.AppNavigator
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalSignOut
import sh.belfry.app.ui.nav.LocalStatus
import sh.belfry.app.ui.nav.SessionScope
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.app.ui.signin.JoinScreen
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
    when (val p = phase) {
        is Phase.Loading -> Splash()
        is Phase.SignedOut -> SignInScreen(state)
        is Phase.Joining -> JoinScreen(state, p.link)
        is Phase.SignedIn -> key(p.session) { SignedIn(state, p.session) }
    }
}

@Composable
private fun SignedIn(state: AppState, session: Session) {
    val controller = rememberNavController()
    val shell = remember { ShellState(session.guest.value?.channel ?: "general") }
    val nav = remember { AppNavigator(controller, shell) }
    val signOut = remember { suspend { state.signOut() } }
    // The channel that was open last time.
    LaunchedEffect(Unit) {
        val last = withContext(Dispatchers.IO) { session.store.get(ShellState.K_LAST_CHANNEL) }
        if (last != null && session.guest.value == null) shell.restore(last)
    }
    CompositionLocalProvider(LocalSignOut provides signOut) {
        SessionScope(session, nav) {
            if (LocalStatus.current.access == AccessState.REFUSED) {
                NoAccessScreen()
            } else {
                AppNav(controller, shell)
                JoinOffer(state)
            }
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
            !sameCommunity -> "This invite is for another community. Sign out first to use it."
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
