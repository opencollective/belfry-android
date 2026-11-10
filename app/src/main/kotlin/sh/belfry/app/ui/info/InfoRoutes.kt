package sh.belfry.app.ui.info

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import sh.belfry.app.ui.nav.NotBuiltYet
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.nav.arg
import sh.belfry.app.ui.nav.optArg

/**
 * Channel information and tools: search, channel info (about, active members, members, settings),
 * invite, topics, files and drop box links, follow. Each route's body is replaced here as its
 * screen is built; the route strings live in Routes.kt.
 */
fun NavGraphBuilder.infoRoutes() {
    composable(Routes.SEARCH, arguments = listOf(Routes.optional("channel"))) { entry ->
        @Suppress("UNUSED_VARIABLE") val channel = entry.optArg("channel")
        NotBuiltYet("Search")
    }
    composable(Routes.CHANNEL_INFO) { entry ->
        @Suppress("UNUSED_VARIABLE") val id = entry.arg("id")
        NotBuiltYet("Channel")
    }
    composable(Routes.INVITE) { NotBuiltYet("Invite people") }
    composable(Routes.TOPICS) { NotBuiltYet("Topics") }
    composable(Routes.FILES) { NotBuiltYet("Files") }
    composable(Routes.DROP) { NotBuiltYet("New drop box link") }
    composable(Routes.FOLLOW) { NotBuiltYet("Follow") }
}
