package sh.belfry.app.ui.cards

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.nav.arg
import sh.belfry.app.ui.nav.optArg

/** Pods, events, proposals and the create screens. The route strings live in Routes.kt. */
fun NavGraphBuilder.cardsRoutes() {
    composable(Routes.EVENTS, arguments = listOf(Routes.optional("channel"))) { entry -> EventsScreen(entry.optArg("channel")) }
    composable(Routes.EVENT) { entry -> EventScreen(entry.arg("coord")) }
    composable(Routes.POD) { entry -> PodScreen(entry.arg("id")) }
    composable(Routes.PROPOSAL) { entry -> ProposalScreen(entry.arg("id")) }
    composable(Routes.CREATE_CHANNEL) { CreateChannelScreen() }
    composable(Routes.CREATE_POD) { entry -> CreatePodScreen(entry.arg("parent")) }
    composable(Routes.CREATE_EVENT) { entry -> CreateEventScreen(entry.arg("channel")) }
    composable(Routes.EDIT_EVENT) { entry -> CreateEventScreen(Routes.NO_CHANNEL, edit = entry.arg("coord")) }
    composable(Routes.CREATE_PROPOSAL, arguments = listOf(Routes.optional("from"), Routes.optional("in"))) { entry ->
        CreateProposalScreen(entry.arg("parent"), entry.optArg("from"), entry.optArg("in"))
    }
}
