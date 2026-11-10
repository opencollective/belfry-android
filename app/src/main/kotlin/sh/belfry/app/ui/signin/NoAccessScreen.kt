package sh.belfry.app.ui.signin

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
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

/** Signed in, but the belfry's relay says no (SPEC 4: no pass, or not a member yet). */
@Composable
fun NoAccessScreen() {
    val c = Belfry.colors
    val session = LocalSession.current
    val signOut = LocalSignOut.current
    val context = LocalContext.current
    SignedOutPage {
        Logo(session.community.name)
        Spacer(Modifier.height(40.dp))
        Text("You’re not in yet", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "You are signed in, but ${session.community.name} does not let this account into its chat (yet). Ask a member for an invite, or an admin to check your membership.",
            fontSize = 15.sp, color = c.muted,
        )
        Spacer(Modifier.height(24.dp))
        BusyButton("Try again", onClick = { session.wake() }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        BusyButton(
            "Sign out",
            onClick = { signOut()?.let { openInBrowser(context, it) } },
            modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost,
        )
    }
}
