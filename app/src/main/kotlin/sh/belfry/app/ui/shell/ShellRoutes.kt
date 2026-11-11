package sh.belfry.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import sh.belfry.app.ui.chat.ThreadScreen
import sh.belfry.app.ui.kit.KitGallery
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.nav.arg
import sh.belfry.app.ui.theme.Belfry

/** The shell's own routes: home (drawer + channel), a thread, the debug kit gallery. */
fun NavGraphBuilder.shellRoutes(shell: ShellState) {
    composable(Routes.HOME) { HomeScreen(shell) }
    composable(Routes.MENTIONS) { MentionsScreen() }
    composable(Routes.THREAD) { entry -> ThreadScreen(entry.arg("id"), entry.arg("root")) }
    composable(Routes.KIT) {
        Column(Modifier.fillMaxSize().background(Belfry.colors.bg)) { KitGallery.Page() }
    }
}
