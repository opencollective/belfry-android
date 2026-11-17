package sh.belfry.app.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import sh.belfry.app.ui.nav.LocalDms
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.core.model.Charter
import sh.belfry.core.model.charterToAgree
import sh.belfry.core.model.groupRequestCount
import sh.belfry.core.model.myTodos
import sh.belfry.core.read.Unread

/** What waits for me, as the places' badges count it (iOS AppModel+Home: homeCount, townUnread). */
data class PlaceCounts(
    val requests: Int,
    val toAgree: Int,
    val mentions: Int,
    val dms: Int,
    val todosOnMe: Int,
    /** Town channels with something unread (☰'s count in Town). */
    val town: Int,
) {
    /** The dot on Home: something waits for me (nothing for a guest). */
    fun home(guest: Boolean): Int = if (guest) 0 else requests + mentions + dms + todosOnMe + toAgree
}

/** The counts, from the snapshot and the stored copies; the request lists are refreshed in the background. */
@Composable
fun rememberPlaceCounts(): PlaceCounts {
    val world = LocalWorld.current
    val session = LocalSession.current
    val dms = LocalDms.current
    val read by session.readState.collectAsState()
    val people by session.members.requests.collectAsState()
    val agents by session.members.agentRequests.collectAsState()
    LaunchedEffect(session) { try { session.members.refreshRequests() } catch (e: Exception) { } }
    val now = session.now()
    val town = world.groups.count { it.type == sh.belfry.core.model.ChannelType.CHANNEL && it.id != Charter.ID && Unread.channel(world, read, it.id, now) > 0 }
    return PlaceCounts(
        requests = if (world.amGuest) 0 else people.size + agents.size + world.groupRequestCount(),
        toAgree = if (world.amGuest) 0 else world.charterToAgree().size,
        mentions = world.mentionsOfMe(99).count { Unread.mentionIsNew(read, it, now) },
        dms = dms.unreadTotal,
        todosOnMe = world.myTodos().first.size,
        town = town,
    )
}
