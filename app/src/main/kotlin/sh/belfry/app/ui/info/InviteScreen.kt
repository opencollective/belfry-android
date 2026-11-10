package sh.belfry.app.ui.info

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.belfry.app.ui.kit.*
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.net.GuestLink

private val PEOPLE = listOf(1 to "One person", 50 to "Up to 50")
private val LINK_DAYS = listOf(1 to "1 day", 7 to "1 week", 30 to "1 month")

/**
 * Invite people to a channel: its link (members open it directly) with QR code, copy and the
 * system share sheet. Any member can make it a guest link instead (one person and a day unless
 * asked otherwise): whoever uses it joins this channel only, for 30 days, without a membership.
 *
 * [makeLink] is the belfry call (tests pass a slow one).
 */
@Composable
fun InviteScreen(
    id: String,
    startAsGuest: Boolean = false,
    makeLink: (suspend (people: Int, linkDays: Int) -> GuestLink)? = null,
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val g = world.group(id)
    val label = channelLabel(g, id)
    val memberUrl = remember(id) { session.community.channelLink(id) }

    var guest by remember { mutableStateOf(false) }
    var people by remember { mutableIntStateOf(1) }
    var linkDays by remember { mutableIntStateOf(1) }
    var link by remember { mutableStateOf<GuestLink?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val asked = remember { intArrayOf(0) }

    fun fetch(n: Int, days: Int) {
        val mine = ++asked[0] // a newer choice wins
        busy = true
        error = null
        link = null
        scope.launch {
            try {
                val made = if (makeLink != null) makeLink(n, days) else session.createGuestLink(id, n, days)
                if (mine == asked[0]) link = made
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActionException) {
                if (mine == asked[0]) error = e.message ?: "The guest link could not be made."
            } catch (e: Exception) {
                if (mine == asked[0]) error = "The guest link could not be made."
            } finally {
                if (mine == asked[0]) busy = false
            }
        }
    }

    fun pickGuest(on: Boolean) {
        guest = on
        if (on && link == null && !busy) fetch(people, linkDays)
        if (!on) error = null
    }

    LaunchedEffect(Unit) { if (startAsGuest) pickGuest(true) }

    InfoPage("Invite to $label") {
        ActionGroup {
            SwitchRow(
                "Guest access for 30 days", guest, { pickGuest(it) }, icon = BelfryIcons.User, busy = busy,
                sub = "For a person or an AI agent who isn't a member: this channel only, nothing else.",
                modifier = Modifier.testTag("invite-guest"),
            )
        }
        if (guest) {
            Spacer(Modifier.height(12.dp))
            Text("How many", fontSize = 13.sp, color = c.muted)
            Spacer(Modifier.height(6.dp))
            Segmented(
                PEOPLE.map { it.second }, PEOPLE.indexOfFirst { it.first == people },
                { i -> val n = PEOPLE[i].first; if (n != people) { people = n; fetch(n, linkDays) } },
                enabled = !busy,
            )
            Spacer(Modifier.height(10.dp))
            Text("Link works for", fontSize = 13.sp, color = c.muted)
            Spacer(Modifier.height(6.dp))
            Segmented(
                LINK_DAYS.map { it.second }, LINK_DAYS.indexOfFirst { it.first == linkDays },
                { i -> val d = LINK_DAYS[i].first; if (d != linkDays) { linkDays = d; fetch(people, d) } },
                enabled = !busy,
            )
        }
        Spacer(Modifier.height(18.dp))
        val pending = guest && link == null
        LinkBox(
            url = if (guest) link?.url ?: memberUrl else memberUrl,
            shareTitle = label,
            waiting = pending, busy = busy, error = error,
            waitingText = "Making the guest link…",
            onRetry = { fetch(people, linkDays) },
        )
        Spacer(Modifier.height(14.dp))
        Hint(
            if (guest) {
                (if (people == 1) "For one person" else "For up to 50 people") + ", within " +
                    (when (linkDays) { 1 -> "a day"; 7 -> "a week"; else -> "a month" }) +
                    ". They give a name and are in; someone who has an account joins with it. Everyone here sees who invited them."
            } else {
                "Members open $label with this link. For someone who isn't a member yet, turn on guest access above."
            },
        )
        Spacer(Modifier.height(24.dp))
    }
}
