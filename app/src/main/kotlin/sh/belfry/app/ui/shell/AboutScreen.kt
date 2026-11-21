package sh.belfry.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.BuildConfig
import sh.belfry.app.ui.info.ErrorLine
import sh.belfry.app.ui.info.Hint
import sh.belfry.app.ui.info.InfoPage
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.signin.ApplyFlow
import sh.belfry.app.ui.signin.SentContent
import sh.belfry.app.ui.signin.openInBrowser
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.app.update.UpdateRows
import sh.belfry.app.update.Updater
import sh.belfry.core.net.Community

/** The belfry of the Belfry project itself. */
const val BELFRY_HOME = "belfry.sh"

/**
 * About Belfry (from the account switcher, Xavier 2026-11-18): this app's version and whether a
 * newer one is out (Update downloads it and opens the installer), the project's website, and the
 * belfry of Belfry: switch to it when an account here is in it, else apply to join it.
 */
@Composable
fun AboutScreen() {
    val c = Belfry.colors
    val context = LocalContext.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val accounts = LocalAccounts.current
    var home by remember { mutableStateOf<Community?>(null) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val h = home
    if (h != null) {
        BackHandler { home = null; sent = false }
        if (sent) SentContent(onDone = { home = null; sent = false })
        else ApplyFlow(
            h, charter = { accounts.charterOf(h) }, apply = { e, w, a -> accounts.applyTo(h, e, w, a) },
            onBack = { home = null }, onLogIn = { home = null; accounts.add() }, onSent = { sent = true },
        )
        return
    }

    InfoPage("About Belfry") {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(BelfryIcons.Shield, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Belfry for Android", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text)
            Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 15.sp, color = c.muted, modifier = Modifier.testTag("about-version"))
        }
        if (Updater.enabled) ActionGroup { UpdateRows() }
        else Hint("This build doesn't update itself: the signed releases do.")
        Spacer(Modifier.height(16.dp))
        ActionGroup {
            ActionRow("belfry.sh", onClick = { openInBrowser(context, "https://$BELFRY_HOME") }, icon = BelfryIcons.Globe, sub = "The project's website", chevron = true, modifier = Modifier.testTag("about-website"))
            // the belfry of Belfry: this account, another account on this device, or apply to join it
            val here = session.community.domain == BELFRY_HOME
            val other = accounts.others.firstOrNull { it.domain == BELFRY_HOME }
            when {
                here -> ActionRow("The belfry of Belfry", onClick = { nav.openChannel("general") }, icon = BelfryIcons.Users, sub = "You're in it with this account", chevron = true, modifier = Modifier.testTag("about-join"))
                other != null -> BusyRow("Switch to the belfry of Belfry", onClick = { accounts.switchTo(other.id) }, icon = BelfryIcons.Users, sub = other.name.ifEmpty { BELFRY_HOME }, modifier = Modifier.testTag("about-join"))
                else -> BusyRow(
                    "Join the belfry of Belfry", onClick = {
                        error = null
                        try { home = accounts.discover(BELFRY_HOME) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = "No connection to $BELFRY_HOME. Try again." }
                    },
                    icon = BelfryIcons.Users, sub = "Apply to join $BELFRY_HOME", modifier = Modifier.testTag("about-join"),
                )
            }
        }
        error?.let { ErrorLine(it, Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Diagnostics()
        Hint("Belfry is open source software a community runs on its own server.", Modifier.padding(top = 16.dp))
    }
}

/**
 * Diagnostics (SPEC 19.50): each socket's state and the last 20 sends' steps and timing, to copy
 * into a bug report. Never any message, name or key: the session writes only kinds, steps, times.
 */
@Composable
private fun Diagnostics() {
    val c = Belfry.colors
    val session = LocalSession.current
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    ActionGroup {
        ActionRow(
            "Diagnostics", onClick = { text = session.diagnostics(); copied = false; open = !open }, icon = BelfryIcons.Clock,
            sub = "Connections and how long the last sends took", chevron = true, modifier = Modifier.testTag("about-diagnostics"),
        )
        if (open) {
            ActionRow(
                if (copied) "Copied" else "Copy", onClick = { clipboard.setText(AnnotatedString(text)); copied = true },
                icon = BelfryIcons.Copy, modifier = Modifier.testTag("about-diagnostics-copy"),
            )
        }
    }
    if (open) {
        Text(
            text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.text,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("about-diagnostics-text"),
        )
    }
}
