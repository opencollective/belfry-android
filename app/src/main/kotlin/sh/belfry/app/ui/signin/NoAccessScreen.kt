package sh.belfry.app.ui.signin

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.CancellationException
import sh.belfry.core.engine.ActionException
import sh.belfry.core.net.ApplyForm
import sh.belfry.core.net.PublicAgreement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalSignOut
import sh.belfry.app.ui.theme.Belfry

/**
 * Signed in, but the belfry's relay says no (SPEC 4: no pass, or not a member yet). Round 6
 * (onboarding/no-link, as the web's MembershipScreen): "Almost in" with the apply form, the
 * e-mail the belfry knows for this account filled in and locked; then Thank you.
 */
@Composable
fun NoAccessScreen() {
    val c = Belfry.colors
    val session = LocalSession.current
    val signOut = LocalSignOut.current
    val context = LocalContext.current
    val name = session.community.name
    var agreements by remember { mutableStateOf<List<PublicAgreement>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var known by remember { mutableStateOf<String?>(null) }
    var email by rememberSaveable { mutableStateOf("") }
    var why by rememberSaveable { mutableStateOf("") }
    var ticked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by rememberSaveable { mutableStateOf(false) }
    suspend fun load() {
        loadError = null
        try { agreements = session.members.charterToApply().sortedBy { it.order ?: Int.MAX_VALUE } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            loadError = (e as? ActionException)?.message ?: ApplyForm.NETWORK
        }
    }
    LaunchedEffect(Unit) {
        known = session.members.myEmail()?.also { email = it }
        if (agreements == null) load()
    }
    val logOut: suspend () -> Unit = { signOut()?.let { openInBrowser(context, it) } }
    if (sent) {
        SignedOutPage {
            Column(Modifier.fillMaxWidth().padding(top = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Thank you", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.testTag("apply-sent"))
                Spacer(Modifier.height(10.dp))
                Text("Your request is in. You get a confirmation by e-mail now, and the answer by e-mail too.", fontSize = 16.sp, lineHeight = 22.sp, color = c.muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                BusyButton("I’ve been let in: check again", onClick = { session.wake() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                BusyButton("Log out", onClick = logOut, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
            }
        }
        return
    }
    ApplyContent(
        email, { if (known == null) { email = it.take(200); error = null } }, why, { why = it.take(4000); error = null },
        agreements, ticked, { id -> ticked = if (id in ticked) ticked - id else ticked + id }, error ?: loadError,
        onRetry = if (loadError != null) ({ load() }) else null,
        onSend = {
            error = null
            val list = agreements ?: return@ApplyContent
            if (email.isBlank() || why.isBlank()) { error = ApplyForm.MISSING; return@ApplyContent }
            try { session.members.applyToJoin(email, why, list.map { it.id }); sent = true } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = (e as? ActionException)?.message ?: ApplyForm.OTHER
            }
        },
        onBack = null, onLogIn = {},
        heading = "Almost in",
        intro = "You’re logged in" + (known?.let { " as $it" } ?: "") + ", but you’re not a member of $name yet. Apply to join: the answer comes by e-mail.",
        emailLocked = known != null,
        footer = {
            Column(Modifier.fillMaxWidth()) {
                BusyButton("I’ve been let in: check again", onClick = { session.wake() }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth().testTag("noaccess-check"))
                Spacer(Modifier.height(6.dp))
                BusyButton("Log out", onClick = logOut, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}
