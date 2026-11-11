package sh.belfry.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import sh.belfry.app.ui.cards.cardsRoutes
import sh.belfry.app.ui.info.infoRoutes
import sh.belfry.app.ui.people.peopleRoutes
import sh.belfry.app.ui.shell.ShellState
import sh.belfry.app.ui.shell.shellRoutes
import sh.belfry.app.ui.theme.Belfry
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import sh.belfry.app.ui.shell.ChannelDrawer
import sh.belfry.app.ui.shell.navColumnStays

/** The real [Nav]: navigation-compose behind it. Back only ever pops. */
class AppNavigator(private val controller: NavHostController, private val shell: ShellState) : Nav {
    override fun go(route: String) {
        controller.navigate(route) { launchSingleTop = true }
    }

    override fun back() {
        if (controller.previousBackStackEntry != null) controller.popBackStack()
    }

    override fun openChannel(id: String, messageId: String?) {
        shell.open(id, messageId)
        controller.popBackStack(Routes.HOME, inclusive = false)
    }
}

/** The navigation graph: one registration function per owner (see Routes.kt). */
@Composable
fun AppNav(controller: NavHostController, shell: ShellState) {
    val graph: @Composable (Modifier) -> Unit = { m ->
        NavHost(controller, startDestination = Routes.HOME, modifier = m.background(Belfry.colors.bg)) {
            shellRoutes(shell)
            infoRoutes()
            peopleRoutes()
            cardsRoutes()
        }
    }
    // a wide or landscape window keeps the channel list at the left on every screen
    if (navColumnStays()) {
        val nav = LocalNav.current
        val narrow = LocalConfiguration.current.screenWidthDp < 840
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(if (narrow) 280.dp else 320.dp).fillMaxHeight()) {
                ChannelDrawer(shell.channel, onPick = { nav.openChannel(it) }, go = nav::go)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Belfry.colors.line))
            graph(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        graph(Modifier.fillMaxSize())
    }
}
