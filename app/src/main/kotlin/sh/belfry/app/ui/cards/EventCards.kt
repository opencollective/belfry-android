package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.ApiEvent
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.EventRef
import sh.belfry.core.model.EventStatus
import sh.belfry.core.model.Message
import sh.belfry.core.model.MyState
import sh.belfry.core.model.Rsvp
import sh.belfry.core.model.eventByCoord
import sh.belfry.core.model.eventByLink
import sh.belfry.core.model.eventOfGroup
import sh.belfry.core.model.eventPeople
import sh.belfry.core.model.isPast
import sh.belfry.core.model.myState
import sh.belfry.core.model.stateLabel
import sh.belfry.core.model.tickets

/**
 * A new event's URL, handed from the event form to the composer of the channel it was made from
 * (the web puts it in the composer; nothing is posted until the member sends it).
 */
object EventHandoff {
    var pending by mutableStateOf<Pair<String, String>?>(null)
        private set

    fun hand(channel: String, url: String) { pending = channel to url }

    /** The URL waiting for this channel's composer, once. */
    fun take(channel: String): String? {
        val p = pending ?: return null
        if (p.first != channel) return null
        pending = null
        return p.second
    }
}

/** The event cards under a message's text (its `a` tags and its event URLs, at most three). */
@Composable
fun EventCards(m: Message, modifier: Modifier = Modifier) {
    if (m.eventRefs.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in m.eventRefs.take(3)) EventRefCard(r)
    }
}

/** One card: the event this device knows, else what the belfry says (a guest); nothing while unknown. */
@Composable
internal fun EventRefCard(r: EventRef, modifier: Modifier = Modifier) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val local = r.coord?.let { world.eventByCoord(it) } ?: r.link?.let { world.eventByLink(it) }
    if (local != null) return EventCardView(local, null, modifier)
    val link = r.link ?: r.coord?.split(":")?.getOrNull(2) ?: return
    val remote by session.events.remote.collectAsState()
    LaunchedEffect(link) { session.events.fetch(link) }
    val api = remote[link]?.api ?: return
    EventCardView(api.toCalEvent(world.ctx.zone, world.me), api, modifier)
}

/** An event's card in a channel: cover, date, title, when and where, my answer; Going / Maybe in one tap for the plain RSVP. */
@Composable
internal fun EventCardView(e: CalEvent, api: ApiEvent?, modifier: Modifier = Modifier) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val c = Belfry.colors
    val now = session.now()
    val tks = if (e.isNew) world.tickets(e, api) else emptyList()
    val plain = !e.isNew || (tks.size == 1 && tks[0].implicit && !e.remote && !world.amGuest)
    val mine = if (e.isNew) world.myState(e, world.eventPeople(e), api) else null
    val answered = if (e.isNew) mine != MyState.NONE && mine != MyState.HOST else e.rsvps[world.me] != null
    val label = if (e.isNew) mine?.label else e.rsvps[world.me]?.label
    val open = e.status != EventStatus.CANCELLED && !world.isPast(e, now)
    val shape = RoundedCornerShape(12.dp)
    val key = if (e.remote) e.link else e.coord
    Column(
        modifier.padding(top = 4.dp).widthIn(max = 360.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape)
            .clickable { nav.go(Routes.event(key)) }.testTag("event-card"),
    ) {
        if (e.cover != null) EventCover(e.cover, Modifier.fillMaxWidth().aspectRatio(2f))
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DateChip(world, e.start)
            GapW(12)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.status == EventStatus.CANCELLED) { Badge("Cancelled", Tone.Bad); GapW(6) }
                    Text(e.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Muted(
                    CardFormat.dayAndWhen(world, e, now) + (if (!e.location.isNullOrEmpty()) " · " + e.location else "") + (if (e.links.isNotEmpty()) " · online" else ""),
                    maxLines = 2,
                )
                Muted((if (answered && label != null) "$label · " else "") + "${e.going} going", maxLines = 1)
            }
        }
        if (open && !answered) {
            if (plain) {
                // Going and Maybe share one lock: one tap, one answer
                val busy = rememberBusy()
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BusyButton("Going", onClick = { session.events.rsvp(e, Rsvp.ACCEPTED) }, modifier = Modifier.weight(1f).testTag("card-going"), small = true, busy = busy)
                    BusyButton("Maybe", onClick = { session.events.rsvp(e, Rsvp.TENTATIVE) }, modifier = Modifier.weight(1f).testTag("card-maybe"), small = true, kind = ButtonKind.Ghost, busy = busy)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                    Badge(if (tks.size == 1 && tks[0].closed) "Apply" else "RSVP", Tone.Warm)
                }
            }
        }
    }
}

/** Under an event chat's top bar: what it is the chat of, one tap to the event. */
@Composable
internal fun EventChatBar(groupId: String) {
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val e = world.eventOfGroup(groupId) ?: return
    val c = Belfry.colors
    BarRow(Modifier.testTag("event-bar"), onClick = { nav.go(Routes.event(e.coord)) }) {
        Icon(BelfryIcons.Calendar, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
        GapW(8)
        val s = world.stateLabel(e)
        Text(
            (if (e.status == EventStatus.CANCELLED) "Cancelled · " else "") + ChatFormat.day(world, e.start, session.now()) + " · " + CardFormat.whenText(world, e) + (s?.let { " · $it" } ?: ""),
            Modifier.weight(1f), fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        GapW(8)
        Text("Details", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.muted)
        Icon(BelfryIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(16.dp))
    }
}
