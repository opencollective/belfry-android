package sh.belfry.app.ui.signin

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.AppState
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.OnboardingException

/** Opens an address in the system browser's Custom Tab (never an embedded web view: SPEC 2). */
fun openInBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: Exception) {
            // No browser on this device.
        }
    }
}

/** Signed out: the community's address, then its own sign-in page in the browser; or the demo. */
@Composable
fun SignInScreen(state: AppState) {
    val context = LocalContext.current
    var domain by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val message by state.message.collectAsState()
    val finishing by state.finishing.collectAsState()
    SignInContent(
        domain = domain,
        onDomain = { domain = it; error = null; state.clearMessage() },
        error = error ?: message,
        finishing = finishing,
        onContinue = {
            error = null
            try {
                val community = state.discover(domain)
                openInBrowser(context, state.beginSignIn(community))
            } catch (e: OnboardingException) {
                error = e.message
            }
        },
        onDemo = { state.demo() },
    )
}

@Composable
fun Logo(sub: String = "Your community's own chat") {
    val c = Belfry.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(BelfryIcons.Shield, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Belfry", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(sub, fontSize = 13.sp, color = c.muted)
        }
    }
}

/** The page frame of the signed-out screens: scrolls, keeps a readable width on tablets. */
@Composable
fun SignedOutPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Belfry.colors.bg).systemBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp)) {
            content()
        }
    }
}

@Composable
fun SignInContent(
    domain: String,
    onDomain: (String) -> Unit,
    error: String?,
    finishing: Boolean,
    onContinue: suspend () -> Unit,
    onDemo: suspend () -> Unit,
) {
    val c = Belfry.colors
    SignedOutPage {
        Logo()
        Spacer(Modifier.height(40.dp))
        Text("Sign in to your community", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text("Type your community's address. You sign in on its own page with your e-mail; no password.", fontSize = 15.sp, color = c.muted)
        Spacer(Modifier.height(24.dp))
        if (finishing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Spacer(Modifier.width(10.dp))
                Text("Signing you in…", fontSize = 15.sp, color = c.text)
            }
        } else {
            BelfryTextField(
                domain, onDomain, Modifier.fillMaxWidth(),
                label = "Community address", placeholder = "belfry.sh", error = error,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(16.dp))
            BusyButton("Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth(), enabled = domain.isNotBlank())
            Spacer(Modifier.height(32.dp))
            BusyButton("Try the demo", onClick = onDemo, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
            Spacer(Modifier.height(8.dp))
            Text("The demo is a made-up community. Everything in it stays on this device.", fontSize = 13.sp, color = c.muted)
        }
    }
}

@Composable
fun Splash() {
    Box(Modifier.fillMaxSize().background(Belfry.colors.bg), contentAlignment = Alignment.Center) { Logo() }
}
