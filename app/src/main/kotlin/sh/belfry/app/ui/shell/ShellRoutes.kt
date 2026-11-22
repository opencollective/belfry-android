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
import sh.belfry.app.ui.nav.optArg
import sh.belfry.app.ui.theme.Belfry

/** The shell's own routes: home (drawer + channel), a thread, the debug kit gallery. */
fun NavGraphBuilder.shellRoutes(shell: ShellState) {
    composable(Routes.HOME) { HomeScreen(shell) }
    composable(Routes.MENTIONS) { MentionsScreen() }
    composable(Routes.ADD_CHANNEL) { AddChannelScreen() }
    composable(Routes.ABOUT) { AboutScreen() }
    composable(Routes.LINK_DEVICE) { LinkDeviceScreen() }
    composable(Routes.REMOTE_APP_SCAN) { SignInToAppScreen() }
    composable(Routes.REMOTE_APPS) { ConnectedAppsScreen() }
    composable(Routes.THREAD) { entry -> ThreadScreen(entry.arg("id"), entry.arg("root"), focus = entry.optArg("m")) }
    // a channel pushed over a place (an archived one from its list, a card, a link): a back arrow
    composable(Routes.CHAT, listOf(Routes.optional("m"))) { entry ->
        val nav = sh.belfry.app.ui.nav.LocalNav.current
        sh.belfry.app.ui.chat.ChannelScreen(entry.arg("id"), onBack = { nav.back() }, focusMessage = entry.optArg("m"))
    }
    composable(Routes.MY_TODOS) { sh.belfry.app.ui.places.MyTodosScreen() }
    composable(Routes.ARCHIVED) { sh.belfry.app.ui.places.ArchivedScreen() }
    composable(Routes.CHARTER) { sh.belfry.app.ui.belfry.CharterScreen() }
    composable(Routes.CHARTER_WHO) { entry -> sh.belfry.app.ui.belfry.CharterWhoScreen(entry.arg("id")) }
    composable(Routes.APPLY) { sh.belfry.app.ui.belfry.ApplyScreen() }
    composable(Routes.WELCOME) { sh.belfry.app.ui.belfry.WelcomeScreen() }
    composable(Routes.BELFRY_SETTINGS) { sh.belfry.app.ui.belfry.BelfrySettingsScreen() }
    composable(Routes.SETTINGS_ROLES) { sh.belfry.app.ui.belfry.RolesSettingsScreen() }
    composable(Routes.SETTINGS_MEMBERSHIP) { sh.belfry.app.ui.belfry.MembershipSettingsScreen() }
    composable(Routes.SETTINGS_CHANNELS) { sh.belfry.app.ui.belfry.ChannelsSettingsScreen() }
    composable(Routes.KIT) {
        Column(Modifier.fillMaxSize().background(Belfry.colors.bg)) { KitGallery.Page() }
    }
}
