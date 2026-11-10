package sh.belfry.app.ui.info

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.nav.arg
import sh.belfry.app.ui.nav.optArg

/**
 * Channel information and tools: search, channel info (about, to-dos, members, files, settings),
 * invite, topics, files and drop box links, follow. The route strings live in Routes.kt.
 */
fun NavGraphBuilder.infoRoutes() {
    composable(Routes.SEARCH, arguments = listOf(Routes.optional("channel"))) { entry ->
        SearchScreen(entry.optArg("channel"))
    }
    composable(Routes.CHANNEL_INFO) { entry -> ChannelInfoScreen(entry.arg("id")) }
    composable(Routes.INVITE) { entry -> InviteScreen(entry.arg("id")) }
    composable(Routes.TOPICS) { entry -> TopicsScreen(entry.arg("id")) }
    composable(Routes.FILES) { entry -> FilesScreen(entry.arg("id")) }
    composable(Routes.DROP) { entry -> DropScreen(entry.arg("id")) }
    composable(Routes.FOLLOW) { entry -> FollowScreen(entry.arg("id")) }
}
