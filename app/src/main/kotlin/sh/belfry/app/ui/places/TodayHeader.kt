package sh.belfry.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyState
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.AccountsUi
import sh.belfry.app.ui.nav.LocalAccounts
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.shell.AccountsSheet
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.TodayHeader

/**
 * Today's header (SPEC 19.90; Home canvas home/today-belfry, -dark): level with ☰ and centred, the
 * belfry's mark, its name with a small ⌄, and under it my address here. A tap drops the accounts
 * menu (home/today-belfry-tap). Only Today puts the belfry in its header.
 */
@Composable
fun TodayHeaderBar(onMenu: (() -> Unit)?, menuBadge: Int = 0, onCreate: (() -> Unit)? = null, startOpen: Boolean = false) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val accounts = LocalAccounts.current
    val belfry = world.ctx.communityName.ifEmpty { session.community.name }
    val address = TodayHeader.address(world.myAddress, session.community.domain)
    var open by rememberSaveable { mutableStateOf(startOpen) }
    var accountsOpen by remember { mutableStateOf(false) }
    val screen = LocalConfiguration.current.screenWidthDp.dp
    val menuWidth = minOf(screen - 32.dp, 440.dp)
    Box(Modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).testTag("today-header-bar")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp)) { if (onMenu != null) IconAction(BelfryIcons.Menu, "The town", onMenu, badge = menuBadge) }
            Column(
                Modifier.weight(1f).padding(horizontal = 4.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "Switch account or belfry") { open = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .semantics(mergeDescendants = true) { contentDescription = TodayHeader.label(belfry, address) }
                    .testTag("today-header"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    BelfryMarkView(session.branding.logo, 22.dp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        belfry, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = c.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("today-header-name"),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(BelfryIcons.ChevronDown, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
                }
                Text(
                    address, fontSize = 12.sp, lineHeight = 15.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp).testTag("today-header-address"),
                )
            }
            Box(Modifier.width(48.dp)) { if (onCreate != null) IconAction(BelfryIcons.Plus, "Create", onCreate) }
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            offset = DpOffset((screen - menuWidth) / 2, 4.dp),
            shape = RoundedCornerShape(18.dp), containerColor = c.bg, shadowElevation = 12.dp, tonalElevation = 0.dp,
            modifier = Modifier.width(menuWidth).testTag("today-accounts-menu"),
        ) {
            TodayAccountsMenu(accounts, onDismiss = { open = false }, onAccounts = { open = false; accountsOpen = true })
        }
    }
    // "Accounts ›": the full list behind ⇅ in the drawer (SPEC 19.24)
    if (accountsOpen) AccountsSheet(onDismiss = { accountsOpen = false }, go = { nav.go(it) })
}

/**
 * The accounts menu's rows (home/today-belfry-tap): one per account on this phone (the belfry's
 * mark and name, my address there and my role), the open one first and checked, a dot on one with
 * news this phone already knows of (a notification came for it: [AccountsUi.unread]; nothing is
 * asked of the other belfries); then Add an account and Accounts ›. A tap switches: every row
 * locks, the tapped one shows a spinner, and the app lands on that belfry's Today.
 */
@Composable
fun TodayAccountsMenu(accounts: AccountsUi, onDismiss: () -> Unit, onAccounts: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val busy = rememberBusy()
    val belfry = world.ctx.communityName.ifEmpty { session.community.name }
    val guestInfo by session.guest.collectAsState()
    val guest = world.amGuest || guestInfo != null
    val myLine = TodayHeader.line(TodayHeader.address(world.myAddress, session.community.domain), TodayHeader.role(world.isCommunityAdmin, guest))
    Column {
        MenuAccountRow(session.branding.logo, belfry, myLine, current = true, unread = false, busy = busy, onClick = { onDismiss() }, modifier = Modifier.testTag("today-account-current"))
        for (a in accounts.others) {
            MenuLine()
            MenuAccountRow(
                a.logo, a.community.ifEmpty { a.domain }, a.menuLine, current = false, unread = a.id in accounts.unread, busy = busy,
                onClick = { accounts.switchTo(a.id) }, modifier = Modifier.testTag("today-account-other"),
            )
        }
        MenuLine()
        MenuActionRow(Icons.Outlined.PersonAdd, TodayHeader.ADD, chevron = false, enabled = !busy.busy, modifier = Modifier.testTag("today-account-add")) { onDismiss(); accounts.add() }
        MenuLine()
        MenuActionRow(BelfryIcons.Settings, TodayHeader.ACCOUNTS, chevron = true, enabled = !busy.busy, modifier = Modifier.testTag("today-accounts")) { onAccounts() }
    }
}

@Composable
private fun MenuLine() = Box(Modifier.fillMaxWidth().padding(start = 52.dp).heightIn(min = 1.dp, max = 1.dp).background(Belfry.colors.line))

@Composable
private fun MenuAccountRow(
    logo: String, name: String, line: String, current: Boolean, unread: Boolean, busy: BusyState,
    onClick: suspend () -> Unit, modifier: Modifier = Modifier,
) {
    val c = Belfry.colors
    var mine by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().background(if (current) c.surface else Color.Transparent)
            .clickable(enabled = !busy.busy, role = Role.Button) { mine = true; busy.run(block = onClick) }
            .semantics { selected = current }
            .defaultMinSize(minHeight = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BelfryMarkView(logo, 26.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, fontSize = 13.sp, lineHeight = 17.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        when {
            busy.busy && mine -> Spinner(size = 20.dp, modifier = Modifier.testTag("today-account-spinner"))
            // a check, a shape (not a colour): it reads on an e-ink screen
            current -> Icon(BelfryIcons.Check, contentDescription = TodayHeader.OPEN_NOW, tint = c.text, modifier = Modifier.size(20.dp).testTag("today-account-check"))
            unread -> Box(Modifier.size(10.dp).clip(CircleShape).background(c.badge).semantics { contentDescription = TodayHeader.NEWS }.testTag("today-account-news"))
        }
    }
}

@Composable
private fun MenuActionRow(icon: ImageVector, title: String, chevron: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Belfry.colors
    Row(
        modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 16.sp, color = if (enabled) c.text else c.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chevron) Icon(BelfryIcons.Chevron, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
    }
}

/** A belfry's mark: its logo (https, when pictures load), else the default crest in the text colour. */
@Composable
fun BelfryMarkView(logo: String?, width: Dp) {
    val pictures = !LocalInspectionMode.current && LocalNetworkImages.current
    val url = logo?.takeIf { it.startsWith("https://") && pictures }
    val height = width * (34f / 30f)
    if (url != null) {
        coil.compose.AsyncImage(
            model = url, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.size(width, height).clip(RoundedCornerShape(4.dp)),
        )
    } else {
        Icon(Crest, contentDescription = null, tint = Belfry.colors.text, modifier = Modifier.size(width, height))
    }
}

/** The default crest of the boards (a shield with a door), 30 × 34. */
private val Crest: ImageVector by lazy {
    ImageVector.Builder(name = "crest", defaultWidth = 30.dp, defaultHeight = 34.dp, viewportWidth = 30f, viewportHeight = 34f)
        .addPath(addPathNodes("M2 2h26v16c0 8-7 13-13 15C9 31 2 26 2 18zM10 9v12h10V9z"), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        .addPath(addPathNodes("M13 21v-5h4v5z"), fill = SolidColor(Color.Black))
        .build()
}
