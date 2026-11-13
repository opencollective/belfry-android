package sh.belfry.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import sh.belfry.app.ui.chat.ThreadScreen
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.theme.Belfry

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
 * Home: the channel drawer and the open channel. A phone gets a modal drawer (☰, edge swipe,
 * back closes it); 840 dp and wider keeps the channel list at the left, like the web's laptop
 * layout; 1200 dp and wider opens a thread in a third column beside its channel (web 4d5682d).
 */
@Composable
fun HomeScreen(shell: ShellState, drawerOpen: Boolean = false) {
    // a newer version of the app: a slim line above everything (update/UpdateUi.kt)
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        sh.belfry.app.update.UpdateBar()
        Box(Modifier.weight(1f)) { HomeBody(shell, drawerOpen) }
    }
}

@Composable
private fun HomeBody(shell: ShellState, drawerOpen: Boolean) {
    val c = Belfry.colors
    val nav = LocalNav.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val width = LocalConfiguration.current.screenWidthDp
    val wide = navColumnStays() // the channel list is AppNav's left column then, on every screen
    val three = width >= 1200

    // Remember the open channel for next time.
    LaunchedEffect(shell.channel) {
        if (shell.touched) withContext(Dispatchers.IO) { session.store.set(ShellState.K_LAST_CHANNEL, shell.channel) }
    }

    if (wide) {
        val thread = shell.thread
        Row(Modifier.fillMaxSize()) {
            ChannelScreen(
                shell.channel, modifier = Modifier.weight(1f), focusMessage = shell.focusMessage, onFocusDone = { shell.focusMessage = null },
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
