package sh.belfry.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Chip
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.Keep
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.kit.rememberFormDraft
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.crypto.Hex
import sh.belfry.core.model.Actions
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.FormDrafts
import sh.belfry.core.model.MeetingForm
import sh.belfry.core.model.Meetings
import sh.belfry.core.model.MeetingStatus
import sh.belfry.core.model.meetingStatus
import sh.belfry.core.model.Named
import sh.belfry.core.model.attendance
import sh.belfry.core.model.findPeople
import sh.belfry.core.model.heldByMe
import sh.belfry.core.model.lastPlace
import sh.belfry.core.model.meetingPeople
import sh.belfry.core.model.meetingPlaces
import sh.belfry.core.model.meetingRole
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** Named people in a draft: one per line, name then a tab then the e-mail (when there is one). */
private fun namedToText(list: List<Named>): String = list.joinToString("\n") { it.name.replace('\t', ' ').replace('\n', ' ') + "\t" + it.email.orEmpty() }
private fun namedFromText(text: String): List<Named> = text.split('\n').mapNotNull { line ->
    val name = line.substringBefore('\t').trim()
    val email = line.substringAfter('\t', "").trim()
    if (name.isEmpty()) null else Named(name.take(100), email.takeIf { Meetings.isEmail(it) })
}

/** A face of the people picker: tap to pick or unpick; the picked ones are ringed and ticked. */
@Composable
private fun PickFace(label: String, on: Boolean, description: String, tag: String, onClick: () -> Unit, face: @Composable () -> Unit) {
    val c = Belfry.colors
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { contentDescription = description; selected = on }
            .padding(vertical = 4.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(50.dp).clip(CircleShape).border(2.dp, if (on) c.text else androidx.compose.ui.graphics.Color.Transparent, CircleShape), contentAlignment = Alignment.Center) { face() }
            if (on) {
                Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(BelfryIcons.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(12.dp))
                }
            }
        }
        Text(label, fontSize = 11.sp, lineHeight = 14.sp, color = if (on) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * Who comes (web Meeting.tsx `PeoplePicker`): one grid of faces (the channel's most active members
 * first, then its parent's; the ones picked are ticked), then +. The + asks for a name or an
 * e-mail, shows the people here who match it, then asks for whichever of the two is missing
 * (optional); someone not here gets an account.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeoplePicker(channel: String, people: List<String>, named: List<Named>, onChange: (List<String>, List<Named>) -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val now = LocalSession.current.now()
    var step by remember { mutableIntStateOf(0) } // 0: the grid, 1: who (a name or an e-mail), 2: the other one
    var who by remember { mutableStateOf("") }
    var more by remember { mutableStateOf("") }
    val faces = remember(world, channel, people) { (people + world.findPeople(channel, "", now, 12)).distinct() }
    val isEmail = Meetings.isEmail(who)
    val matches = if (step == 1 && who.isNotBlank() && !isEmail) world.findPeople(channel, who, now, 6).filter { it !in people } else emptyList()
    val toggle = { k: String -> onChange(if (k in people) people - k else people + k, named) }
    val reset = { step = 0; who = ""; more = "" }

    Column(Modifier.testTag("meet-people")) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in faces) {
                val name = world.displayName(k)
                PickFace(name, k in people, name, "meet-face-" + k.take(8), onClick = { toggle(k) }) { Avatar(world.face(k), size = 44.dp) }
            }
            named.forEachIndexed { i, n ->
                PickFace(n.name, true, "${n.name}: remove", "meet-named-$i", onClick = { onChange(people, named.filterIndexed { j, _ -> j != i }) }) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                        Text(n.name.trim().take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    }
                }
            }
            if (step == 0) {
                PickFace("Add", false, "Add someone", "meet-add", onClick = { step = 1 }) {
                    Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(BelfryIcons.Plus, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (step == 1) {
            Gap(8)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BelfryTextField(
                    who, { who = it.take(254) }, Modifier.weight(1f).testTag("meet-who"), placeholder = "Name or e-mail",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = KeyboardType.Email),
                )
                BusyButton("OK", onClick = { step = 2 }, small = true, enabled = who.isNotBlank(), modifier = Modifier.testTag("meet-who-ok"))
                BusyButton("Cancel", onClick = { reset() }, small = true, kind = ButtonKind.Ghost)
            }
            if (matches.isNotEmpty()) {
                Gap(8)
                Muted("Already here:")
                Gap(4)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in matches) Chip(world.displayName(k), icon = BelfryIcons.User, onClick = { toggle(k); reset() }, modifier = Modifier.testTag("meet-match"))
                }
            }
        }
        if (step == 2) {
            Gap(8)
            Muted(if (isEmail) "Adding ${who.trim()}. Their name?" else "Adding ${who.trim()}, who isn't here yet: an account is made for them. Their e-mail?")
            Gap(6)
            val bad = !isEmail && more.isNotBlank() && !Meetings.isEmail(more)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BelfryTextField(
                    more, { more = it.take(if (isEmail) 100 else 254) }, Modifier.weight(1f).testTag("meet-more"),
                    placeholder = if (isEmail) "Name (optional)" else "E-mail (optional)",
                    keyboardOptions = if (isEmail) KeyboardOptions(capitalization = KeyboardCapitalization.Words) else KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
                )
                BusyButton("Add", onClick = { onChange(people, named + Meetings.named(who, more)); reset() }, small = true, enabled = !bad, modifier = Modifier.testTag("meet-more-add"))
                BusyButton("Cancel", onClick = { reset() }, small = true, kind = ButtonKind.Ghost)
            }
        }
    }
}

/**
 * A new meeting in a few taps (web 2026-11-12): a channel's ＋ → Meeting. Topic, people, when, how
 * long, where (the last place by default). Members only. Its URL is sent in the channel it was
 * made from (Xavier, 2026-11-22: sent, not left in the message box); made from the calendar, the
 * meeting opens instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateMeetingScreen(from: String) {
    // from the calendar's ＋ (no channel): people are suggested from the Townsquare, nothing is posted
    val posts = from != Routes.NO_CHANNEL
    val c = Belfry.colors
    val channel = if (posts) from else "general"
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    val zone = world.ctx.zone
    val first = remember { Instant.ofEpochSecond(session.now()).atZone(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0) }
    var topic by remember { mutableStateOf("") }
    var people by remember { mutableStateOf<List<String>>(emptyList()) }
    var named by remember { mutableStateOf<List<Named>>(emptyList()) }
    var date by remember { mutableStateOf(first.toLocalDate()) }
    var time by remember { mutableStateOf(first.toLocalTime()) }
    var minutes by remember { mutableIntStateOf(60) }
    var place by remember { mutableStateOf(world.lastPlace()) }
    var online by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableIntStateOf(0) } // 1: the date, 2: the time
    // made once: the button stays locked (a second tap never makes a second meeting)
    var done by remember { mutableStateOf(false) }
    val places = remember(world) { world.meetingPlaces() }
    val where = world.group(channel)

    // kept on this device until the meeting is made (web `useFormDraft('meeting:' + channel)`)
    val draft = rememberFormDraft(FormDrafts.meeting(channel)) { kept ->
        topic = kept["topic"] ?: topic
        people = kept["people"]?.split(',')?.filter { Hex.isKey(it) } ?: people
        named = kept["named"]?.let(::namedFromText) ?: named
        try { kept["date"]?.let { date = LocalDate.parse(it) } } catch (e: Exception) { }
        try { kept["time"]?.let { time = LocalTime.parse(it) } } catch (e: Exception) { }
        minutes = kept["minutes"]?.toIntOrNull()?.takeIf { it in Meetings.LENGTHS } ?: minutes
        place = kept["place"] ?: place
        online = kept["online"]?.takeIf { it.isEmpty() || it.startsWith("https://") } ?: online
    }
    draft.Keep(
        "topic" to topic, "people" to people.joinToString(","), "named" to namedToText(named), "date" to date.toString(), "time" to time.toString(),
        "minutes" to minutes.toString(), "place" to place, "online" to online,
    )

    CardScreen("New meeting", subtitle = if (posts) where?.let { "From # " + it.name } else null) {
        PageColumn {
            BelfryTextField(topic, { topic = it.take(200) }, Modifier.fillMaxWidth().testTag("meet-topic"), label = "Topic", placeholder = "e.g. Plan the garden day", keyboardOptions = words)
            Column {
                Muted("People")
                Gap(6)
                PeoplePicker(channel, people, named) { p, n -> people = p; named = n }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerField("When", DATE.format(date), BelfryIcons.Calendar, "meet-date", Modifier.weight(1.5f)) { picking = 1 }
                PickerField("At", TIME.format(time), BelfryIcons.Clock, "meet-time", Modifier.weight(1f)) { picking = 2 }
            }
            Column {
                Muted("How long")
                Gap(6)
                FlowRow(Modifier.testTag("meet-lengths"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in Meetings.LENGTHS) Chip(Meetings.lengthLabel(n), selected = minutes == n, onClick = { minutes = n })
                }
            }
            Column {
                BelfryTextField(place, { place = it }, Modifier.fillMaxWidth().testTag("meet-place"), label = "Where", placeholder = "A room, an address", keyboardOptions = words)
                Gap(8)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (pl in places.filter { it != place }) Chip(pl, icon = BelfryIcons.MapPin, onClick = { place = pl })
                    if (online.isEmpty()) Chip(
                        "Add a video link", icon = BelfryIcons.Link,
                        onClick = { online = Actions.jitsiLink(topic.ifBlank { "meeting" }) },
                        modifier = Modifier.testTag("meet-video"),
                    )
                }
                // the link itself, so the person sees what is added (and can take it off)
                if (online.isNotEmpty()) {
                    Gap(8)
                    Row(Modifier.fillMaxWidth().testTag("meet-video-link"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(BelfryIcons.Link, contentDescription = "Video link", tint = c.text, modifier = Modifier.size(18.dp))
                        GapW(8)
                        Text(online, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp, color = c.text)
                        IconAction(BelfryIcons.Close, "Remove the video link", { online = "" }, Modifier.testTag("meet-video-remove"))
                    }
                }
            }
            error?.let { ErrorLine(it) }
            BusyButton(
                "Create the meeting",
                onClick = {
                    error = null
                    if (!done && topic.isNotBlank()) {
                        done = true
                        val start = ZonedDateTime.of(date, time, zone).toEpochSecond()
                        // its link is sent in the channel as soon as the meeting is queued (before the people by name are added)
                        val d = session.events.createMeeting(channel, MeetingForm(topic.trim(), people, named, start, minutes, place.trim(), online.trim(), zone.id), announce = posts)
                        draft.clear()
                        nav.back()
                        if (!posts) nav.go(Routes.event(d))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("create-submit"),
                enabled = topic.isNotBlank() && !done,
                onError = { x ->
                    error = shown(x)
                    // made, but someone could not be added: nothing to make again
                    if ((x as? sh.belfry.core.engine.ActionException)?.code == "named") draft.clear() else done = false
                },
            )
            Muted(
                "Members only. " +
                    (if (people.isNotEmpty()) (if (people.size == 1) world.displayName(people[0]) + " sees" else "${people.size} people see") + " it in their upcoming events and get its chat."
                    else "The people you invite see it in their upcoming events and get its chat.") +
                    (if (named.isNotEmpty()) " " + (if (named.size == 1) named[0].name + " gets" else "${named.size} people get") + " an account; without an e-mail, it stays yours to hand over." else ""),
            )
        }
    }
    when (picking) {
        1 -> PickDate(date, { picking = 0 }) { date = it }
        2 -> PickTime(time, { picking = 0 }) { time = it }
    }
}

/**
 * A meeting's people on its page (web Events.tsx `MeetingPeople`): who made it and everyone
 * invited, what they answered, whether they were there. A host marks it for each (for people who
 * don't use their phone), adds someone by name, and hands over an account they made.
 */
@Composable
internal fun MeetingPeople(e: CalEvent, host: Boolean) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val nav = LocalNav.current
    val now = session.now()
    val there = world.attendance(e)
    val keys = world.meetingPeople(e)
    val held = world.heldByMe(e)
    // before it starts a host can only take someone off; checking in comes with the meeting
    val started = Meetings.checkInOpen(e, now)
    var removing by remember(e.coord) { mutableStateOf<String?>(null) }
    var giving by remember(e.coord) { mutableStateOf<String?>(null) }
    var email by remember(e.coord) { mutableStateOf("") }
    var name by remember(e.coord) { mutableStateOf("") }
    var mail by remember(e.coord) { mutableStateOf("") }
    var error by remember(e.coord) { mutableStateOf<String?>(null) }
    val nThere = there.values.count { it }

    Column(Modifier.testTag("meeting-people")) {
        SectionTitle("People · ${keys.size}" + if (nThere > 0) " · $nThere checked in" else "")
        for (k in keys) {
            val isThere = there[k] == true
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = world.displayName(k) + ": profile") { nav.go(Routes.profile(k)) }) {
                    StatusFace(k, world.meetingStatus(e, k, there), 36)
                }
                GapW(10)
                Column(Modifier.weight(1f)) {
                    Text(world.displayName(k), fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(world.meetingRole(e, k, there) + if (k in held) " · account held by you" else "", fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (host && k in held) {
                    GapW(6)
                    BusyButton("Hand over", onClick = { giving = k; email = ""; error = null }, small = true, kind = ButtonKind.Ghost, modifier = Modifier.testTag("meet-give-" + k.take(8)))
                }
                if (host && (started || isThere)) {
                    GapW(6)
                    BusyButton(
                        if (isThere) "Checked in" else "Check in",
                        onClick = { session.events.checkIn(e, k, !isThere) },
                        small = true, kind = if (isThere) ButtonKind.Primary else ButtonKind.Ghost, icon = if (isThere) BelfryIcons.Check else null,
                        modifier = Modifier.testTag("meet-check-" + k.take(8)).semantics { selected = isThere },
                        onError = { error = shownError(it) },
                    )
                } else if (host && k != e.creator) {
                    IconAction(BelfryIcons.Close, "Remove " + world.displayName(k), { removing = k; error = null }, Modifier.testTag("meet-remove-" + k.take(8)))
                }
            }
        }
        val g = giving
        if (g != null) {
            Gap(8)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BelfryTextField(
                    email, { email = it.take(254) }, Modifier.weight(1f).testTag("meet-give-email"), placeholder = world.displayName(g) + "'s e-mail",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
                )
                BusyButton(
                    "Give it", onClick = { error = null; session.events.handOver(e.groupId, g, email.trim()); giving = null },
                    small = true, enabled = Meetings.isEmail(email), modifier = Modifier.testTag("meet-give-ok"), onError = { error = shownError(it) },
                )
            }
        }
        if (host) {
            Gap(10)
            // one lock for the row: the account is made once
            val busy = rememberBusy()
            BelfryTextField(name, { name = it.take(100) }, Modifier.fillMaxWidth().testTag("meet-add-name"), placeholder = "Add someone by name", enabled = !busy.busy, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            Gap(8)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BelfryTextField(
                    mail, { mail = it.take(254) }, Modifier.weight(1f).testTag("meet-add-email"), placeholder = "E-mail (optional)", enabled = !busy.busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
                )
                BusyButton(
                    "Add",
                    onClick = {
                        error = null
                        session.events.addByName(e, Named(name.trim(), mail.trim().takeIf { Meetings.isEmail(it) }))
                        name = ""; mail = ""
                    },
                    small = true, kind = ButtonKind.Ghost, enabled = name.isNotBlank() && (mail.isBlank() || Meetings.isEmail(mail)), busy = busy,
                    modifier = Modifier.testTag("meet-add-ok"), onError = { error = shownError(it) },
                )
            }
        }
        error?.let { Gap(6); ErrorLine(it) }
    }
    removing?.let { k ->
        ConfirmSheet(
            title = "Remove ${world.displayName(k)} from this meeting?",
            confirmLabel = "Remove",
            onConfirm = { session.events.uninvite(e, k); removing = null },
            onDismiss = { removing = null },
            message = "They leave its chat and it no longer shows in their calendar.",
            confirmTag = "meet-remove-ok",
        )
    }
}

/** A face with its status in the corner (never by colour alone: each status has its own mark). */
@Composable
internal fun StatusFace(pubkey: String, status: MeetingStatus, size: Int) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val filled = status == MeetingStatus.CHECKED_IN
    Box(Modifier.size((size + 4).dp)) {
        Avatar(world.face(pubkey), Modifier.align(Alignment.Center), size = size.dp)
        val mark = when (status) {
            MeetingStatus.INVITED -> BelfryIcons.Clock
            MeetingStatus.CONFIRMED, MeetingStatus.CHECKED_IN -> BelfryIcons.Check
            MeetingStatus.MAYBE -> null
            MeetingStatus.DECLINED -> BelfryIcons.Close
        }
        val badge = (size * 0.45f).coerceAtLeast(14f).dp
        Box(
            Modifier.align(Alignment.BottomEnd).size(badge).clip(CircleShape).background(if (filled) c.text else c.bg).border(1.dp, c.text, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (mark != null) Icon(mark, contentDescription = null, tint = if (filled) c.bg else c.text, modifier = Modifier.size(badge * 0.7f))
            else Text("?", fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = c.text)
        }
    }
}

/**
 * A meeting's people at a glance (mid): one row of faces, each with its status mark (a clock:
 * invited, a tick: confirmed, a filled tick: checked in), then the counts in words. A tap opens
 * the whole page, where the list is.
 */
@Composable
internal fun MeetingFaces(e: CalEvent, onOpen: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val there = world.attendance(e)
    val people = world.meetingPeople(e).map { it to world.meetingStatus(e, it, there) }
    // those checked in first, then confirmed, then the rest
    val order = listOf(MeetingStatus.CHECKED_IN, MeetingStatus.CONFIRMED, MeetingStatus.MAYBE, MeetingStatus.INVITED, MeetingStatus.DECLINED)
    val sorted = people.sortedBy { order.indexOf(it.second) }
    val words = order.mapNotNull { st -> people.count { it.second == st }.takeIf { it > 0 }?.let { "$it ${st.label.lowercase()}" } }.joinToString(" · ")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "See the people", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "People: $words" }.padding(vertical = 4.dp).testTag("meeting-faces"),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            // as many faces as fit in one row (never a sideways scroll), then +N
            val fit = ((maxWidth.value + 6f) / 50f).toInt().coerceAtLeast(1)
            val shown = if (sorted.size > fit) sorted.take(fit - 1) else sorted
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((k, st) in shown) StatusFace(k, st, 40)
                if (sorted.size > shown.size) Box(Modifier.size(40.dp).clip(CircleShape).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                    Text("+${sorted.size - shown.size}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                }
            }
        }
        Gap(6)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(words, Modifier.weight(1f), fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(BelfryIcons.Chevron, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
    }
}
