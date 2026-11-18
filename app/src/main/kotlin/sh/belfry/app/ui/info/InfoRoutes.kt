package sh.belfry.app.ui.info

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.nav.arg
import sh.belfry.app.ui.nav.optArg

/**
 * Channel information and tools: search, channel info (about, members, todos, files, log, settings),
 * invite, topics, files and drop box links, follow. The route strings live in Routes.kt.
 */
fun NavGraphBuilder.infoRoutes() {
    composable(Routes.SEARCH, arguments = listOf(Routes.optional("channel"))) { entry ->
        SearchScreen(entry.optArg("channel"))
    }
    // the tabbed channel info is gone (iOS / web 2026-11-17): an old link opens the matching screen
    composable(Routes.CHANNEL_INFO, arguments = listOf(Routes.optional("tab"))) { entry ->
        val key = when (InfoTab.of(entry.optArg("tab"))) {
            InfoTab.TODOS -> sh.belfry.app.ui.nav.ChannelPlaceKey.TODOS
            InfoTab.FILES -> sh.belfry.app.ui.nav.ChannelPlaceKey.PHOTOS
            InfoTab.LOG -> sh.belfry.app.ui.nav.ChannelPlaceKey.LOG
            else -> sh.belfry.app.ui.nav.ChannelPlaceKey.MEMBERS
        }
        ChannelPlaceScreen(entry.arg("id"), key)
    }
    composable(Routes.CHANNEL_PLACE) { entry -> ChannelPlaceScreen(entry.arg("id"), entry.arg("key")) }
    composable(Routes.TODO) { entry -> TodoScreen(entry.arg("id"), entry.arg("task")) }
    composable(Routes.INVITE, listOf(Routes.optional("members"))) { entry -> InviteScreen(entry.arg("id"), toCommunity = entry.optArg("members") == "1") }
    composable(Routes.TOPICS) { entry -> TopicsScreen(entry.arg("id")) }
    composable(Routes.FILES) { entry -> FilesScreen(entry.arg("id")) }
    composable(Routes.DROP) { entry -> DropScreen(entry.arg("id")) }
    composable(Routes.FOLLOW) { entry -> FollowScreen(entry.arg("id")) }
}
