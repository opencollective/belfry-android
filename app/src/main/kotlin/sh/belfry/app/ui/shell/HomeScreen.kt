package sh.belfry.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.chat.ThreadScreen
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.places.CalendarPlace
import sh.belfry.app.ui.places.CreateSheet
import sh.belfry.app.ui.places.GroupsPlace
import sh.belfry.app.ui.places.HomePlace
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.PlaceNames
import sh.belfry.core.model.welcomeDue

/**
 * Whether the channel list stays as a left column (on every screen, AppNav): 840 dp and wider,
 * and any landscape window from 600 dp (Xavier, 2026-11-11: "in landscape mode, the left column
 * should always stay there for navigation").
 */
@Composable
fun navColumnStays(): Boolean {
    val conf = LocalConfiguration.current
    return conf.screenWidthDp >= 840 || (conf.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && conf.screenWidthDp >= 600)
}

/**
 * The five places (SPEC 19.31, Navigation v2 / nav2/channel): a bottom bar Today · Calendar ·
 * Messages · Groups · Town (guests: Today · Calendar · Town), no ＋ in it (creating is the
 * composer's ＋). Each place's root is drawn here with ☰ and the menu; everything pushed (a
 * profile, an event, a thread…) covers the bar and shows only ‹. A wide window keeps the menu as
 * AppNav's left column on every screen and has no bar: the places are rows there.
 */
@Composable
fun HomeScreen(shell: ShellState, drawerOpen: Boolean = false) {
    // a newer version of the app: a slim line above everything (update/UpdateUi.kt)
    sh.belfry.app.update.UnderUpdateBar {
        androidx.compose.runtime.CompositionLocalProvider(LocalShell provides shell) { PlacesHost(shell, drawerOpen) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlacesHost(shell: ShellState, drawerOpen: Boolean) {
    val c = Belfry.colors
    val nav = LocalNav.current
    val session = LocalSession.current
    val world = LocalWorld.current
    val scope = rememberCoroutineScope()
    val wide = navColumnStays()
    val guestInfo by session.guest.collectAsState()
    val guest = world.amGuest || guestInfo?.reading == true
    val counts = rememberPlaceCounts()

    // Remember the open channel for next time.
    LaunchedEffect(shell.channel) {
        if (shell.touched) withContext(Dispatchers.IO) { session.store.set(ShellState.K_LAST_CHANNEL, shell.channel) }
    }
    // a new member's first minutes (SPEC 19.32 / web welcomeDue): once, on a device that saw them as a guest
    LaunchedEffect(world.amGuest, world.charterTicked) {
        withContext(Dispatchers.IO) {
            if (world.amGuest) session.store.set(K_SAW_GUEST, "1")
            val saw = session.store.get(K_SAW_GUEST) == "1"
            if (session.store.get(K_WELCOMED) == null && world.welcomeDue(saw)) {
                session.store.set(K_WELCOMED, "1")
                withContext(Dispatchers.Main) { nav.go(sh.belfry.app.ui.nav.Routes.WELCOME) }
            }
        }
    }
    // a guest has no Groups place
    LaunchedEffect(guest) { if (guest && (shell.place == ShellState.Place.GROUPS || shell.place == ShellState.Place.MESSAGES)) shell.place = ShellState.Place.HOME }

    if (wide) {
        Box(Modifier.fillMaxSize()) { PlaceBody(shell, onMenu = null, counts = counts, wide = true) }
    } else {
        val drawer = rememberDrawerState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed)
        val actions = DrawerActions(
            channel = { id -> shell.open(id); scope.launch { drawer.close() } },
            today = { shell.place = ShellState.Place.HOME; scope.launch { drawer.close() } },
            place = { p -> shell.place = p; scope.launch { drawer.close() } },
            go = { route -> scope.launch { drawer.close() }; nav.go(route) },
        )
        // every place's root is top level: ☰ and the menu (nav2/dm-header)
        val hasDrawer = true
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = hasDrawer || drawer.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    Modifier.widthIn(max = 320.dp), drawerShape = RectangleShape, drawerContainerColor = c.bg, drawerContentColor = c.text,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) { ChannelDrawer(shell, actions) }
            },
        ) {
            // the keyboard is up when it takes room at the bottom (isImeVisible says true under Robolectric)
            val keyboard = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            Column(Modifier.fillMaxSize().background(c.bg)) {
                Box(Modifier.weight(1f).then(if (!keyboard) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
                    PlaceBody(shell, onMenu = { scope.launch { drawer.open() } }, counts = counts, wide = false)
                }
                // the bar hides with the keyboard (it would sit between the composer and the keys)
                if (!keyboard) PlacesBar(shell, guest, counts)
            }
        }
    }
    if (shell.creating) CreateSheet(shell, onDismiss = { shell.creating = false })
}

@Composable
private fun PlaceBody(shell: ShellState, onMenu: (() -> Unit)?, counts: PlaceCounts, wide: Boolean) {
    val c = Belfry.colors
    val three = LocalConfiguration.current.screenWidthDp >= 1200
    when (shell.place) {
        ShellState.Place.HOME -> HomePlace(onMenu = onMenu, menuBadge = counts.menu)
        ShellState.Place.CALENDAR -> CalendarPlace(onMenu = onMenu, menuBadge = counts.menu)
        ShellState.Place.MESSAGES -> sh.belfry.app.ui.people.MessagesScreen(place = true, onMenu = onMenu, menuBadge = counts.menu)
        ShellState.Place.GROUPS -> GroupsPlace(onMenu = onMenu, menuBadge = counts.menu)
        ShellState.Place.TOWN -> {
            if (wide) {
                val thread = shell.thread
                Row(Modifier.fillMaxSize()) {
                    ChannelScreen(
                        shell.channel, modifier = Modifier.weight(1f), focusMessage = shell.focusMessage, onFocusDone = { shell.focusMessage = null }, newestTick = shell.newestTick,
                        // another thread replaces the open one (no history entry per thread)
                        onOpenThread = if (three) ({ shell.thread = it }) else null,
                    )
                    if (three && thread != null) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                        Box(Modifier.width(400.dp).fillMaxHeight()) {
                            ThreadScreen(shell.channel, thread, onClose = { shell.thread = null })
                        }
                    }
                }
            } else {
                ChannelScreen(
                    shell.channel,
                    onMenu = onMenu,
                    menuBadge = counts.menu,
                    focusMessage = shell.focusMessage,
                    onFocusDone = { shell.focusMessage = null },
                    newestTick = shell.newestTick,
                )
            }
        }
    }
}

private const val K_SAW_GUEST = "ui:saw-guest"
private const val K_WELCOMED = "ui:welcomed"

/**
 * Material's bottom navigation bar (nav2/channel): Today · Calendar · Messages · Groups · Town.
 * Messages counts the conversations with new messages, Groups the new messages in my groups; Town
 * has none (☰ counts the channels). Guests: Today · Calendar · Town.
 */
@Composable
private fun PlacesBar(shell: ShellState, guest: Boolean, counts: PlaceCounts) {
    val c = Belfry.colors
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = c.text, selectedTextColor = c.text, indicatorColor = c.surface2,
        unselectedIconColor = c.muted, unselectedTextColor = c.muted,
    )
    NavigationBar(containerColor = c.bg, contentColor = c.text, tonalElevation = 0.dp, modifier = Modifier.testTag("places-bar")) {
        PlaceItem(shell, ShellState.Place.HOME, PlaceNames.TODAY, Icons.Outlined.WbSunny, Icons.Filled.WbSunny, 0, colors)
        PlaceItem(shell, ShellState.Place.CALENDAR, PlaceNames.CALENDAR, Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth, 0, colors)
        if (!guest) {
            PlaceItem(shell, ShellState.Place.MESSAGES, PlaceNames.MESSAGES, Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble, counts.dmConvs, colors)
            PlaceItem(shell, ShellState.Place.GROUPS, PlaceNames.GROUPS, Icons.Outlined.Groups, Icons.Filled.Groups, counts.groups, colors)
        }
        PlaceItem(shell, ShellState.Place.TOWN, PlaceNames.TOWN_PLACE, Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance, 0, colors)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PlaceItem(
    shell: ShellState, place: ShellState.Place, label: String, icon: ImageVector, selectedIcon: ImageVector, badge: Int,
    colors: androidx.compose.material3.NavigationBarItemColors,
) {
    val on = shell.place == place
    NavigationBarItem(
        selected = on,
        onClick = { if (place == ShellState.Place.TOWN) shell.townTapped() else shell.place = place },
        icon = {
            // a count, not a dot: colour alone says nothing on an e-ink screen
            BadgedBox(badge = { if (badge > 0) Badge(containerColor = Belfry.colors.badge, contentColor = Belfry.colors.onBadge) { Text(if (badge > 99) "99+" else badge.toString()) } }) {
                Icon(if (on) selectedIcon else icon, contentDescription = null)
            }
        },
        label = { Text(label) },
        colors = colors,
        modifier = Modifier.testTag("place-" + place.name.lowercase()),
    )
}
