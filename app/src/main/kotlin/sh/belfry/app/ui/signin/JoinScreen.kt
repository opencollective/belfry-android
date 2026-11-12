package sh.belfry.app.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.AppState
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.OnboardingException
import sh.belfry.core.model.GuestMode
import sh.belfry.core.net.InviteLink

/**
 * Opened from a guest link with no account in that belfry (SPEC 19.26): no question. This device
 * makes its key and uses the link in guest mode; the loading screen ([OpeningScreen], drawn over
 * everything by AppRoot) stays until the channel is there. Only a link that can't be used gets a
 * screen of its own. Nothing shown comes from the link's text.
 */
@Composable
fun JoinScreen(state: AppState, link: InviteLink) {
    var error by remember(link) { mutableStateOf<String?>(null) }
    var closed by remember(link) { mutableStateOf(false) }
    var attempt by remember(link) { mutableIntStateOf(0) }
    LaunchedEffect(link, attempt) {
        error = null
        try {
            state.openInvite(link)
        } catch (e: OnboardingException) {
            closed = e.code == "closed"
            error = e.message
        } catch (e: Exception) {
            error = "No connection to the belfry. Try again."
        }
    }
    val shown = error
    if (shown == null) {
        // the same screen AppRoot draws on top; here so that no frame is ever empty
        OpeningScreen(null)
        return
    }
    InviteUnusable(
        message = shown,
        closed = closed,
        onRetry = { attempt += 1 },
        onSignIn = { if (closed) state.forgetInvite() else state.signInInstead() },
        onClose = { state.forgetInvite() },
    )
}

/** From the link to the channel: one calm screen, a spinner and "Opening # channel…". */
@Composable
fun OpeningScreen(channelName: String?) {
    val c = Belfry.colors
    Box(Modifier.fillMaxSize().background(c.bg).testTag("opening"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spinner(size = 28.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                GuestMode.openingLine(channelName), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.text, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** The link can't be used: why (a fixed text), Try again (when it may work later), sign in, close. */
@Composable
fun InviteUnusable(message: String, closed: Boolean, onRetry: () -> Unit, onSignIn: () -> Unit, onClose: () -> Unit) {
    val c = Belfry.colors
    androidx.activity.compose.BackHandler(onBack = onClose)
    SignedOutPage {
        Logo()
        Spacer(Modifier.height(40.dp))
        Text("This invite can’t be used", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 15.sp, color = c.muted)
        Spacer(Modifier.height(24.dp))
        if (!closed) {
            BusyButton("Try again", onClick = { onRetry() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        BusyButton("I have an account: sign in", onClick = { onSignIn() }, modifier = Modifier.fillMaxWidth(), kind = if (closed) ButtonKind.Primary else ButtonKind.Ghost)
        Spacer(Modifier.height(12.dp))
        Text(
            "Close", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClose).padding(vertical = 10.dp),
        )
    }
}
