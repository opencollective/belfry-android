package sh.belfry.app.ui.cards

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.nav.arg
import sh.belfry.app.ui.nav.optArg

/** Pods, events, proposals and the create screens. The route strings live in Routes.kt. */
fun NavGraphBuilder.cardsRoutes() {
    composable(Routes.EVENTS, arguments = listOf(Routes.optional("channel"))) { entry -> EventsScreen(entry.optArg("channel")) }
    composable(Routes.EVENT, listOf(Routes.optional("t"))) { entry -> EventScreen(entry.arg("coord"), entry.optArg("t")) }
    composable(Routes.CHECK_IN) { entry -> CheckInScreen(entry.arg("coord")) }
    composable(Routes.POD) { entry -> PodScreen(entry.arg("id")) }
    // round 6: a proposal opens as its chat with the proposal on top, at full; the old page while its text syncs
    composable(Routes.PROPOSAL) { entry ->
        val id = entry.arg("id")
        val nav = sh.belfry.app.ui.nav.LocalNav.current
        if (sh.belfry.app.ui.nav.LocalWorld.current.proposalFor(id) != null && sh.belfry.app.ui.nav.LocalWorld.current.group(id) != null) {
            sh.belfry.app.ui.chat.ChannelScreen(id, onBack = { nav.back() }, proposalStart = EventLevel.FULL)
        } else ProposalScreen(id)
    }
    composable(Routes.CREATE_CHANNEL, listOf(Routes.optional("category"))) { e -> CreateChannelScreen(e.optArg("category")) }
    composable(Routes.CREATE_POD) { entry -> CreatePodScreen(entry.arg("parent")) }
    composable(Routes.CREATE_EVENT) { entry -> CreateEventScreen(entry.arg("channel")) }
    composable(Routes.CREATE_MEETING) { entry -> CreateMeetingScreen(entry.arg("channel")) }
    composable(Routes.EDIT_EVENT) { entry -> CreateEventScreen(Routes.NO_CHANNEL, edit = entry.arg("coord")) }
    composable(Routes.CREATE_PROPOSAL, arguments = listOf(Routes.optional("from"), Routes.optional("in"))) { entry ->
        CreateProposalScreen(entry.arg("parent"), entry.optArg("from"), entry.optArg("in"))
    }
}
