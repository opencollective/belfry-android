package sh.belfry.app.ui.shell

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.chat.ChannelScreen
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.theme.Belfry

/**
 * Home: the channel drawer and the open channel. A phone gets a modal drawer (☰, edge swipe,
 * back closes it); 840 dp and wider keeps the channel list at the left, like the web's laptop
 * layout.
 */
@Composable
fun HomeScreen(shell: ShellState, drawerOpen: Boolean = false) {
    val c = Belfry.colors
    val nav = LocalNav.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val wide = LocalConfiguration.current.screenWidthDp >= 840

    // Remember the open channel for next time.
    LaunchedEffect(shell.channel) {
        if (shell.touched) withContext(Dispatchers.IO) { session.store.set(ShellState.K_LAST_CHANNEL, shell.channel) }
    }

    if (wide) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(Modifier.width(320.dp), drawerContainerColor = c.bg, drawerContentColor = c.text) {
                    ChannelDrawer(shell.channel, onPick = { shell.open(it) }, go = nav::go)
                }
            },
        ) {
            ChannelScreen(shell.channel, focusMessage = shell.focusMessage, onFocusDone = { shell.focusMessage = null })
        }
    } else {
        val drawer = rememberDrawerState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed)
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(Modifier.widthIn(max = 320.dp), drawerShape = RectangleShape, drawerContainerColor = c.bg, drawerContentColor = c.text) {
                    ChannelDrawer(
                        shell.channel,
                        onPick = { shell.open(it); scope.launch { drawer.close() } },
                        go = { route -> scope.launch { drawer.close() }; nav.go(route) },
                    )
                }
            },
        ) {
            ChannelScreen(
                shell.channel,
                onMenu = { scope.launch { drawer.open() } },
                focusMessage = shell.focusMessage,
                onFocusDone = { shell.focusMessage = null },
            )
        }
    }
}
