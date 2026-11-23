package sh.belfry.app.ui.signin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.AppState
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.net.Community

// A members-only event opened by someone who is not a member (SPEC 19.71; canvas
// events/members-only-join, events/members-only-login; the web's chat 3884008): the screen says so,
// "I have an account: sign in" opens the belfry's e-mail log-in at once, "Apply to join ‹belfry›"
// its application. Signed out it is a screen of its own (SignedOutFlow); a guest sees the same
// words on the event's page (ui/cards EventScreen).

/** The words and the two ways in, centred. COMMUNITY is the belfry's name (never its address when a name is known). */
@Composable
fun MembersOnlyBody(community: String, onSignIn: () -> Unit, onApply: () -> Unit, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 56.dp).testTag("members-only"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // a lock in a ring: an outline, not a shade (e-ink)
            Box(Modifier.size(64.dp).clip(CircleShape).background(c.surface2).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                Icon(BelfryIcons.Lock, contentDescription = null, tint = c.text, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("This event is for members", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Members see the details, answer and join the conversation.", fontSize = 16.sp, lineHeight = 22.sp, color = c.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            BusyButton("I have an account: sign in", onClick = { onSignIn() }, modifier = Modifier.fillMaxWidth().testTag("members-signin"))
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onApply, modifier = Modifier.testTag("members-apply")) {
                Text(if (community.isBlank()) "Apply to join" else "Apply to join $community", fontSize = 16.sp, lineHeight = 22.sp, color = c.text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Signed out: the belfry's name on top (no back arrow: nothing is under it), the words under it. */
@Composable
fun MembersOnlyScreen(community: String, onSignIn: () -> Unit, onApply: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg).navigationBarsPadding()) {
        TopBar(community)
        Hairline()
        MembersOnlyBody(community, onSignIn, onApply, Modifier.weight(1f))
    }
}

/** While the belfry is asked about the event's address (the web's words). */
@Composable
fun OpeningEvent() {
    val c = Belfry.colors
    Box(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().testTag("event-opening"), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner()
            Spacer(Modifier.width(10.dp))
            Text("Opening the event…", fontSize = 16.sp, color = c.muted)
        }
    }
}

private enum class Way { LOGIN, APPLY, NATIVE, OLD_APPLY, SENT }

/**
 * The way in to community C opened directly, with no Welcome first: its e-mail log-in ("Log in ·
 * We send a 6-digit code to your e-mail. No password."), or its application when [apply]. The same
 * screens and calls as from the Welcome (an older belfry gets the native sign-in / the older
 * form). [onBack] pops to where it was opened from.
 */
@Composable
fun MemberWayIn(state: AppState, c: Community, apply: Boolean, onBack: () -> Unit) {
    var way by remember(c.domain, apply) { mutableStateOf(if (apply) Way.APPLY else Way.LOGIN) }
    val api = remember(c.domain) { state.wayIn(c) }
    when (way) {
        Way.LOGIN -> WayInFlow(
            c, WayInStart.LOGIN, api,
            charter = { state.publicCharter(c) },
            onSigned = { signed -> state.finishWayIn(c, signed) },
            onOlder = { way = Way.NATIVE },
            onSignInInstead = { way = Way.NATIVE },
            onPending = { on -> state.setPendingApplication(if (on) c.domain else null) },
            onBack = onBack,
        )
        Way.APPLY -> WayInFlow(
            c, WayInStart.APPLY, api,
            charter = { state.publicCharter(c) },
            onSigned = { signed -> state.finishWayIn(c, signed) },
            onOlder = { way = Way.OLD_APPLY },
            onSignInInstead = { way = Way.NATIVE },
            onPending = { on -> state.setPendingApplication(if (on) c.domain else null) },
            onBack = onBack,
        )
        Way.NATIVE -> SignInScreen(state, community = c, onLeave = onBack)
        Way.OLD_APPLY -> {
            BackHandler(onBack = onBack)
            ApplyFlow(c, charter = { state.publicCharter(c) }, apply = { e, w, a -> state.applyToJoin(c, e, w, a) },
                onBack = onBack, onLogIn = { way = Way.LOGIN }, onSent = { way = Way.SENT })
        }
        Way.SENT -> {
            BackHandler(onBack = onBack)
            SentContent(onDone = onBack)
        }
    }
}
