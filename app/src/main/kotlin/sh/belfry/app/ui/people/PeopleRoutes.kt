package sh.belfry.app.ui.people

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import sh.belfry.app.ui.nav.NotBuiltYet
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.nav.arg

/**
 * People: profiles, Me and settings, sessions, the members list, direct messages and e-mail.
 * Each route's body is replaced here as its screen is built; the route strings live in Routes.kt.
 */
fun NavGraphBuilder.peopleRoutes() {
    composable(Routes.PROFILE) { entry ->
        @Suppress("UNUSED_VARIABLE") val pubkey = entry.arg("pubkey")
        NotBuiltYet("Profile")
    }
    composable(Routes.ME) { NotBuiltYet("You") }
    composable(Routes.SESSIONS) { NotBuiltYet("Signed-in devices") }
    composable(Routes.MEMBERS) { NotBuiltYet("Members") }
    composable(Routes.MESSAGES) { NotBuiltYet("Messages") }
    composable(Routes.CONVERSATION) { entry ->
        @Suppress("UNUSED_VARIABLE") val conv = entry.arg("conv")
        NotBuiltYet("Conversation")
    }
    composable(Routes.NEW_CONVERSATION) { NotBuiltYet("New message") }
}
