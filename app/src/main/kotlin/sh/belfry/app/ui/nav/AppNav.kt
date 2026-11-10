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
    NavHost(controller, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize().background(Belfry.colors.bg)) {
        shellRoutes(shell)
        infoRoutes()
        peopleRoutes()
        cardsRoutes()
    }
}
