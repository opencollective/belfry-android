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
        }
        Spacer(Modifier.height(12.dp))
        ActionGroup {
            ActionRow(
                "Me", onClick = { onDismiss(); go(Routes.ME) }, icon = BelfryIcons.User,
                sub = "Your profile, notifications, sessions, sign out", chevron = true, enabled = !busy.busy,
                modifier = Modifier.testTag("account-me"),
            )
        }
    }
}
