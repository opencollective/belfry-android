package sh.belfry.app.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.BuildConfig
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.copyText
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalSignOut
import sh.belfry.app.ui.nav.LocalStatus
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.signin.openInBrowser
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException

const val SOURCE_URL = "https://github.com/opencollective/belfry-android"

/** You: your card, your address and key, the devices signed in as you, the app, sign out. */
@Composable
fun MeScreen() {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val status = LocalStatus.current
    val nav = LocalNav.current
    val signOut = LocalSignOut.current
    val context = LocalContext.current
    val me = world.me
    val p = world.profiles[me]
    val guest by session.guest.collectAsState()
    val domain = session.community.domain
    val address = world.myAddress
    val own = address != null && address.endsWith("@" + domain.lowercase())
    var copied by remember { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) { delay(1500); copied = false }

    Page("You") {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { nav.go(Routes.profile(me)) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(world.face(me), size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p?.name?.takeIf { it.isNotEmpty() } ?: "You", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = address ?: p?.nip05 ?: if (world.amGuest) "Guest" else ""
                if (line.isNotEmpty()) Text(line, fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (guest?.saved == false) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.clip(RoundedCornerShape(12.dp))) { SaveAccountCard(session) }
        }
        Spacer(Modifier.height(12.dp))
        ActionGroup {
            ActionRow("Your profile", onClick = { nav.go(Routes.profile(me)) }, icon = BelfryIcons.User, sub = "Name, picture, about, tags, where you are", chevron = true)
            if (own) {
                ActionRow("Username", onClick = { renaming = true }, icon = BelfryIcons.At, sub = "Your address here: $address", value = "Change")
            }
            ActionRow(
                shortNpub(me),
                onClick = { copyText(context, npubOf(me), "Your key"); copied = true },
                icon = BelfryIcons.Copy, sub = "Your key",
                value = if (copied) "Copied" else "Copy",
                modifier = Modifier.testTag("me-key"),
            )
        }
        SectionTitle("Community")
        ActionGroup {
            ActionRow("Members", onClick = { nav.go(Routes.MEMBERS) }, icon = BelfryIcons.Users, sub = "Everyone in ${session.community.name}", chevron = true)
            ActionRow("Messages", onClick = { nav.go(Routes.MESSAGES) }, icon = BelfryIcons.Mail, sub = "Direct messages and e-mail", chevron = true)
            ActionRow("Notifications", onClick = { nav.go(Routes.NOTIFICATIONS) }, icon = BelfryIcons.Bell, sub = "On this phone: messages, mentions, announcements", chevron = true, modifier = Modifier.testTag("me-notifications"))
            ActionRow("Signed-in devices", onClick = { nav.go(Routes.SESSIONS) }, icon = BelfryIcons.Lock, sub = "The apps and devices signed in as you", chevron = true)
        }
        if (status.queued > 0 || status.failed > 0) {
            SectionTitle("Not sent yet")
            ActionGroup {
                if (status.queued > 0) ActionRow("${status.queued} waiting to be sent", onClick = null, icon = BelfryIcons.Clock, sub = "Saved on this device; they go out when the connection is back.")
                if (status.failed > 0) ActionRow("${status.failed} not sent", onClick = null, icon = BelfryIcons.Retry, sub = "Open the channel to retry or discard them.")
            }
        }
        SectionTitle("App")
        ActionGroup {
            ActionRow("Appearance", onClick = null, value = "Follows the system")
            ActionRow("Community", onClick = null, icon = BelfryIcons.Globe, value = domain)
            ActionRow("Version", onClick = null, value = BuildConfig.VERSION_NAME + if (status.localOnly) " · demo" else "")
            ActionRow("Source code", onClick = { openInBrowser(context, SOURCE_URL) }, icon = BelfryIcons.Link, chevron = true)
        }
        if (status.localOnly) {
            Spacer(Modifier.height(8.dp))
            Hint("Demo: a made-up community on this device. Nothing leaves it.")
        }
        Spacer(Modifier.height(18.dp))
        ActionGroup {
            ActionRow("Sign out", onClick = { leaving = true }, icon = BelfryIcons.Logout, danger = true, modifier = Modifier.testTag("me-signout"))
        }
    }
    if (leaving) {
        val unsent = status.queued + status.failed
        ConfirmSheet(
            title = "Sign out?",
            confirmLabel = "Sign out",
            onConfirm = {
                leaving = false
                signOut()
            },
            onDismiss = { leaving = false },
            message = if (unsent > 0) {
                "$unsent ${if (unsent == 1) "message is" else "messages are"} not sent yet and will be lost. Sign out anyway?"
            } else if (guest?.saved == false) {
                "Your account is not saved: without an e-mail you cannot come back to it. Sign out anyway?"
            } else {
                "This device forgets your messages. They stay on the belfry."
            },
        )
    }
    if (renaming) UsernameSheet(address?.substringBeforeLast('@') ?: "", domain, onClose = { renaming = false })
}

/** Change your address here (`<username>@<domain>`). */
@Composable
private fun UsernameSheet(current: String, domain: String, onClose: () -> Unit) {
    val session = LocalSession.current
    var user by rememberSaveable { mutableStateOf(current) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    BelfrySheet(onDismiss = onClose, title = "Your username") {
        BelfryTextField(
            user, { v -> user = v.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }.take(30); error = null },
            label = "Your address here",
            error = error,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            trailing = { Text("@$domain", fontSize = 15.sp, color = Belfry.colors.muted, maxLines = 1) },
        )
        Spacer(Modifier.height(14.dp))
        BusyButton(
            "Save",
            onClick = {
                try {
                    if (user != current) session.setUsername(user)
                    onClose()
                } catch (e: ActionException) {
                    error = e.shown()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = user.length >= 2,
        )
    }
}
