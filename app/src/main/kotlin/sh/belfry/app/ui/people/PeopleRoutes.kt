package sh.belfry.app.ui.people

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.nav.arg
import sh.belfry.app.ui.nav.optArg

/**
 * People: profiles, Me and settings, sessions, the members list, direct messages and e-mail.
 * The route strings live in Routes.kt.
 */
fun NavGraphBuilder.peopleRoutes() {
    composable(Routes.PROFILE, listOf(Routes.optional("group"))) { entry -> ProfileScreen(entry.arg("pubkey"), group = entry.optArg("group")) }
    composable(Routes.ME) { MeScreen() }
    composable(Routes.SESSIONS) { SessionsScreen() }
    composable(Routes.NOTIFICATIONS) { NotificationsScreen() }
    composable(Routes.BRANDING) { BrandingScreen() }
    composable(Routes.MEMBERS) { MembersScreen() }
    composable(Routes.MESSAGES) { MessagesScreen() }
    composable(Routes.CONVERSATION) { entry -> ConversationScreen(entry.arg("conv")) }
    composable(Routes.NEW_CONVERSATION) { NewConversationScreen() }
}
