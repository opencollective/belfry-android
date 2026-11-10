package sh.belfry.app.ui.signin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.AppState
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.Invitation
import sh.belfry.core.engine.OnboardingException
import sh.belfry.core.net.InviteLink

/**
 * Opened from a guest link, not signed in: who invites to what, a name, and you're in.
 * Everything shown comes from the belfry's answer about the invite, never from the link's text.
 */
@Composable
fun JoinScreen(state: AppState, link: InviteLink) {
    var invitation by remember(link) { mutableStateOf<Invitation?>(null) }
    var error by remember(link) { mutableStateOf<String?>(null) }
    LaunchedEffect(link) {
        try {
            invitation = state.inviteInfo(link)
        } catch (e: OnboardingException) {
            error = e.message
        } catch (e: Exception) {
            error = "No connection to the belfry. Try again."
        }
    }
    JoinContent(
        invitation = invitation,
        error = error,
        onJoin = { name ->
            val inv = invitation
            if (inv != null) {
                error = null
                try {
                    state.joinAsNew(inv, name)
                } catch (e: OnboardingException) {
                    error = e.message
                } catch (e: Exception) {
                    error = "No connection to the belfry. Try again."
                }
            }
        },
        onSignIn = { closed -> if (closed) state.forgetInvite() else state.signInInstead() },
        onClose = { state.forgetInvite() },
    )
}

/** [onSignIn] gets true when the invite is closed (it is dropped rather than kept for after sign-in). */
@Composable
fun JoinContent(invitation: Invitation?, error: String?, onJoin: suspend (name: String) -> Unit, onSignIn: (closed: Boolean) -> Unit, onClose: () -> Unit) {
    val c = Belfry.colors
    var name by rememberSaveable { mutableStateOf("") }
    val info = invitation?.info
    val closed = info?.closedMessage
    SignedOutPage {
        Logo(invitation?.community?.name ?: "Your community's own chat")
        Spacer(Modifier.height(40.dp))
        if (info == null || closed != null) {
            if (closed != null || error != null) {
                Text("This invite can’t be used", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Spacer(Modifier.height(8.dp))
                Text(closed ?: error ?: "", fontSize = 15.sp, color = c.muted)
                Spacer(Modifier.height(24.dp))
                BusyButton("I have an account: sign in", onClick = { onSignIn(closed != null) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                LinkLine("Close", onClose)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner()
                    Spacer(Modifier.width(10.dp))
                    Text("Opening the invite…", fontSize = 15.sp, color = c.text)
                }
            }
        } else {
            Text("${info.inviter.ifEmpty { "A member" }} invited you to # ${info.channelName}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            if (info.about.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(info.about, fontSize = 15.sp, color = c.muted)
            }
            Spacer(Modifier.height(24.dp))
            BelfryTextField(
                name, { name = it.take(100) }, Modifier.fillMaxWidth(), label = "Your name", error = error,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            Spacer(Modifier.height(16.dp))
            BusyButton(
                "Join # ${info.channelName}",
                onClick = { onJoin(name.trim().replace(Regex("\\s+"), " ")) },
                modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "You’ll be a guest of ${info.community} for ${info.days} days: you see and post in this channel only. No e-mail needed now.",
                fontSize = 13.sp, color = c.muted,
            )
            Spacer(Modifier.height(28.dp))
            LinkLine("Already have an account? Sign in") { onSignIn(false) }
        }
    }
}

@Composable
private fun LinkLine(text: String, onClick: () -> Unit) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Belfry.colors.text, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
    )
}
