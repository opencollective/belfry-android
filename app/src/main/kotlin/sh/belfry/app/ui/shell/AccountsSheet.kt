package sh.belfry.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyState
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.AccountsUi
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry

/**
 * One account in a list: its face, name, and its address (or its community). A check on the open
 * one, a dot when something came for it while another was open. Tapping locks every row of the
 * list ([busy] is shared) and turns this one's check into a spinner until the switch is done.
 */
@Composable
fun AccountRow(
    pubkey: String,
    name: String,
    line: String,
    picture: String?,
    current: Boolean,
    unread: Boolean,
    busy: BusyState,
    onClick: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Belfry.colors
    var mine by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().background(c.surface)
            .clickable(enabled = !busy.busy, role = Role.Button) { mine = true; busy.run(block = onClick) }
            .defaultMinSize(minHeight = 56.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(pubkey, name, picture = picture?.takeIf { it.isNotEmpty() }, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, fontSize = 13.sp, lineHeight = 17.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        when {
            busy.busy && mine -> Spinner(size = 18.dp)
            current -> Icon(BelfryIcons.Check, contentDescription = "Open now", tint = c.text, modifier = Modifier.size(20.dp))
            unread -> Box(Modifier.size(10.dp).clip(CircleShape).background(c.badge).semantics { contentDescription = "Something new" })
        }
    }
}

/**
 * The list behind "you" at the drawer's bottom (Discord's account switcher): the accounts on this
 * device, Add an account, and Me (the open account's profile and settings).
 */
@Composable
fun AccountsSheet(onDismiss: () -> Unit, go: (String) -> Unit) {
    BelfrySheet(onDismiss = onDismiss, title = "Accounts") { AccountsList(LocalAccounts.current, onDismiss, go) }
}

/** The sheet's rows (screenshot tests draw them in a [SheetContent]). */
@Composable
fun AccountsList(accounts: AccountsUi, onDismiss: () -> Unit, go: (String) -> Unit) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val busy = rememberBusy()
    val me = world.profiles[world.me]
    // "Link a phone or tablet" (canvas home/accounts + home/link-device): the open account's login link
    val loginLink = myLoginLink()
    Column {
        ActionGroup {
            AccountRow(
                pubkey = world.me, name = world.realName(world.me).ifEmpty { "You" },
                line = world.myAddress ?: if (session.localOnly) "Demo: nothing leaves this device" else world.ctx.communityName.ifEmpty { session.community.name },
                picture = me?.picture, current = true, unread = false, busy = busy, onClick = { onDismiss() },
                modifier = Modifier.testTag("account-current"),
            )
            for (a in accounts.others) {
                AccountRow(
                    pubkey = a.pubkey, name = a.name.ifEmpty { a.community.ifEmpty { a.domain } }, line = a.line, picture = a.picture,
                    current = false, unread = a.id in accounts.unread, busy = busy,
                    onClick = { accounts.switchTo(a.id) },
                    modifier = Modifier.testTag("account-other"),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ActionGroup {
            ActionRow(
                "Add an account", onClick = { onDismiss(); accounts.add() }, icon = BelfryIcons.Plus,
                sub = "Another identity here, or your account in another community", enabled = !busy.busy,
                modifier = Modifier.testTag("account-add"),
            )
            if (loginLink != null) ActionRow(
                "Link a phone or tablet", onClick = { onDismiss(); go(Routes.LINK_DEVICE) }, icon = BelfryIcons.Qr,
                sub = "Scan a QR code there to log in as ${world.myAddress}", enabled = !busy.busy,
                modifier = Modifier.testTag("account-link-device"),
            )
            // Sign in to another app (SPEC 19.47, canvas home/signer-entry-android): under "Link a phone
            // or tablet"; a guest sees it too (scanning says guests can't yet). Connected apps · N once one exists.
            ActionRow(
                "Sign in to another app", onClick = { onDismiss(); go(Routes.REMOTE_APP_SCAN) }, icon = BelfryIcons.Qr,
                sub = "Scan the code an app shows to sign in with your Belfry account", enabled = !busy.busy,
                modifier = Modifier.testTag("account-remote-sign-in"),
            )
            val apps by session.remoteApps.apps.collectAsState()
            if (!world.amGuest) androidx.compose.runtime.LaunchedEffect(Unit) { try { session.remoteApps.refresh() } catch (e: Exception) { } }
            val n = apps?.size ?: 0
            if (n > 0) ActionRow(
                "Connected apps", onClick = { onDismiss(); go(Routes.REMOTE_APPS) }, icon = BelfryIcons.Globe, value = n.toString(), enabled = !busy.busy,
                modifier = Modifier.testTag("account-remote-apps"),
            )
        }
        Spacer(Modifier.height(12.dp))
        ActionGroup {
            ActionRow(
                "Me", onClick = { onDismiss(); go(Routes.ME) }, icon = BelfryIcons.User,
                sub = "Your profile, notifications, sessions, sign out", chevron = true, enabled = !busy.busy,
                modifier = Modifier.testTag("account-me"),
            )
            ActionRow(
                "About Belfry", onClick = { onDismiss(); go(Routes.ABOUT) }, icon = BelfryIcons.Shield,
                sub = "Version ${sh.belfry.app.BuildConfig.VERSION_NAME}" + (sh.belfry.app.update.Updater.available.value?.let { " · ${it.version} is out" } ?: ""),
                chevron = true, enabled = !busy.busy, modifier = Modifier.testTag("account-about"),
            )
        }
    }
}

/** The open account's login link, `https://chat.<domain>/login/<username>`, when it has a username here. */
@Composable
internal fun myLoginLink(): String? {
    val world = LocalWorld.current
    val session = LocalSession.current
    return world.myAddress
        ?.takeIf { !session.localOnly && !world.amGuest && it.endsWith("@" + session.community.domain) }
        ?.substringBefore('@')?.let { "https://chat.${session.community.domain}/login/$it" }
        ?.takeIf { sh.belfry.core.net.LoginLink.parse(it) != null }
}

/** canvas home/link-device (v61: a pushed screen, the web's home/link-phone). */
@Composable
fun LinkDeviceScreen() {
    val world = LocalWorld.current
    val link = myLoginLink()
    val session = LocalSession.current
    val nav = sh.belfry.app.ui.nav.LocalNav.current
    // canvas messages/encryption-link-qr: once the new device is in, scanning was the confirmation
    val linked = session.encryption.state.collectAsState().value.linked
    sh.belfry.app.ui.info.InfoPage("Link a phone or tablet") {
        if (linked != null) sh.belfry.app.ui.people.LinkedContent(linked) { session.encryption.linkedSeen(); nav.back() }
        else if (link != null) {
            // sealed.md §5.2: a fresh one-time secret each time the screen opens, ONLY in the QR (not in any text or copy)
            val secret = remember { session.encryption.linkSecret() }
            LinkDeviceBody(link, world.myAddress!!, qr = secret?.let { "$link#l=$it" } ?: link)
        }
        else sh.belfry.app.ui.info.Hint("This account has no username here yet.")
    }
}

/**
 * The QR of `https://chat.<domain>/login/<username>` for the open account. No secret in it: the
 * other device confirms with the code mailed to the person's own e-mail (onboarding/scan-login).
 */
@Composable
internal fun LinkDeviceBody(link: String, address: String, qr: String = link) {
    val c = sh.belfry.app.ui.theme.Belfry.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        sh.belfry.app.ui.kit.QrCode(qr, Modifier.testTag("link-device-qr"))
        Spacer(Modifier.height(14.dp))
        androidx.compose.material3.Text(
            "Open the Belfry app on the other device and scan this code. It logs in as $address after a 6-digit code sent to your e-mail.",
            fontSize = 15.sp, lineHeight = 21.sp, color = c.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.Text(link, fontSize = 13.sp, color = c.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
    }
}
