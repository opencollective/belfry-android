package sh.belfry.app.ui.cards

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import sh.belfry.app.ui.nav.NotBuiltYet
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.nav.arg
import sh.belfry.app.ui.nav.optArg

/**
 * Pods, events, proposals and the create screens. Each route's body is replaced here as its
 * screen is built; the route strings live in Routes.kt.
 */
fun NavGraphBuilder.cardsRoutes() {
    composable(Routes.EVENTS, arguments = listOf(Routes.optional("channel"))) { entry ->
        @Suppress("UNUSED_VARIABLE") val channel = entry.optArg("channel")
        NotBuiltYet("Events")
    }
    composable(Routes.EVENT) { entry ->
        @Suppress("UNUSED_VARIABLE") val coord = entry.arg("coord")
        NotBuiltYet("Event")
    }
    composable(Routes.POD) { NotBuiltYet("Pod") }
    composable(Routes.PROPOSAL) { NotBuiltYet("Proposal") }
    composable(Routes.CREATE_CHANNEL) { NotBuiltYet("New channel") }
    composable(Routes.CREATE_POD) { NotBuiltYet("New pod") }
    composable(Routes.CREATE_EVENT) { NotBuiltYet("New event") }
    composable(Routes.CREATE_PROPOSAL) { NotBuiltYet("New proposal") }
}
