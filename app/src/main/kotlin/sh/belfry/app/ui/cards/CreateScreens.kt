package sh.belfry.app.ui.cards

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.Chip
import sh.belfry.app.ui.kit.Segmented
import sh.belfry.app.ui.kit.SwitchRow
import sh.belfry.app.ui.nav.LocalNav
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.nav.Routes
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.Actions
import sh.belfry.core.model.EventForm
import sh.belfry.core.model.EventLinks
import sh.belfry.core.model.SlugCheck
import sh.belfry.core.model.Visibility
import sh.belfry.core.model.eventByKey
import sh.belfry.app.ui.chat.Photos
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.rememberBusy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import sh.belfry.core.model.NewEvent
import sh.belfry.core.model.NewProposal
import sh.belfry.core.model.PodAccess
import sh.belfry.core.model.ProposalStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

private fun shown(e: Throwable): String = (e as? ActionException)?.message ?: "That did not work. Try again."

private val words = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

@Composable
fun CreateChannelScreen() {
    val session = LocalSession.current
    val nav = LocalNav.current
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    CardScreen("New channel") {
        PageColumn {
            BelfryTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("create-name"), label = "Name", placeholder = "e.g. Garden", error = if (tried && name.isBlank()) "Give the channel a name" else null, keyboardOptions = words)
            BelfryTextField(about, { about = it }, Modifier.fillMaxWidth(), label = "What is it for?", singleLine = false, minLines = 3, maxLines = 6, keyboardOptions = words)
            error?.let { ErrorLine(it) }
            BusyButton(
                "Create channel",
                onClick = {
                    tried = true
                    error = null
                    if (name.isNotBlank()) {
                        val id = session.createChannel(name.trim(), about.trim())
                        nav.openChannel(id)
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("create-submit"),
                onError = { error = shown(it) },
            )
            Muted("Every member can read and write in a channel.")
        }
    }
}

/** "Who can join": open or closed, and for a closed pod the question applicants answer. */
@Composable
internal fun AccessFields(closed: Boolean, prompt: String, onClosed: (Boolean) -> Unit, onPrompt: (String) -> Unit) {
    Column {
        Muted("Who can join")
        Gap(6)
        Segmented(listOf("Open", "Closed"), if (closed) 1 else 0, { onClosed(it == 1) }, Modifier.fillMaxWidth())
        Gap(6)
        Muted(if (closed) "Apply, admins admit" else "Any member joins")
        if (closed) {
            Gap(12)
            BelfryTextField(prompt, onPrompt, Modifier.fillMaxWidth(), label = "Question applicants answer", placeholder = Actions.DEFAULT_PROMPT, singleLine = false, minLines = 2, maxLines = 4, keyboardOptions = words)
        }
    }
}

@Composable
fun CreatePodScreen(parent: String) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var closed by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val where = world.group(parent)
    CardScreen("New pod") {
        PageColumn {
            BelfryTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("create-name"), label = "Name", placeholder = "e.g. Garden crew", error = if (tried && name.isBlank()) "Give the pod a name" else null, keyboardOptions = words)
            BelfryTextField(about, { about = it }, Modifier.fillMaxWidth(), label = "What is it working on?", singleLine = false, minLines = 3, maxLines = 6, keyboardOptions = words)
            AccessFields(closed, prompt, { closed = it }, { prompt = it })
            error?.let { ErrorLine(it) }
            BusyButton(
                "Create pod",
                onClick = {
                    tried = true
                    error = null
                    if (name.isNotBlank()) {
                        val id = session.createPod(name.trim(), about.trim(), parent, PodAccess(closed, prompt))
                        nav.openChannel(id)
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("create-submit"),
                onError = { error = shown(it) },
            )
            Muted("A working group inside ${where?.name ?: parent}, with its own members, chat, events, photos and pods.")
        }
    }
}

@Composable
fun CreateProposalScreen(parent: String, from: String? = null, fromChannel: String? = null) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    // a request turned into a proposal (web 2026-11-11): its words to start from, a link back
    val request = if (from != null && fromChannel != null) world.message(fromChannel, from) else null
    var title by remember { mutableStateOf(request?.let { r -> (r.ev.tag("subject") ?: r.text.lineSequence().firstOrNull().orEmpty().replace("**", "")).take(140) } ?: "") }
    var summary by remember { mutableStateOf("") }
    var text by remember {
        mutableStateOf(request?.let { r ->
            val page = world.webPage(world.group(fromChannel!!))?.slug ?: fromChannel
            r.text.replace(Regex("^\\*\\*[^\\n]*\\*\\*\\n+"), "") + "\n\n_From a request by ${world.displayName(r.ev.pubkey)}: https://${world.ctx.domain}/$page/${r.ev.id}_"
        } ?: "")
    }
    var start by remember { mutableIntStateOf(1) }
    var tried by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val where = world.group(parent)
    val statuses = listOf(ProposalStatus.DRAFT, ProposalStatus.FEEDBACK, ProposalStatus.VOTING)
    CardScreen("New proposal") {
        PageColumn {
            ProposalFields(title, { title = it }, summary, { summary = it }, text, { text = it }, titleError = if (tried && title.isBlank()) "Give the proposal a title" else null)
            Column {
                Muted("Start as")
                Gap(6)
                Segmented(listOf("Draft", "Feedback", "Votes"), start, { start = it }, Modifier.fillMaxWidth())
                Gap(6)
                Muted(listOf("Still writing it", "Ask for comments", "Open the vote on version 1 right away")[start])
            }
            error?.let { ErrorLine(it) }
            BusyButton(
                "Post proposal",
                onClick = {
                    tried = true
                    error = null
                    if (title.isNotBlank()) {
                        val id = session.createProposal(NewProposal(title.trim(), summary.trim(), text.trim(), statuses[start]), parent)
                        if (request != null) {
                            session.replyInThread(fromChannel!!, request.ev, "Became a proposal: “${title.trim()}”. Members discuss it and vote on it in #proposals.")
                        }
                        nav.back()
                        nav.go(Routes.proposal(id))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("create-submit"),
                onError = { error = shown(it) },
            )
            Muted("Posted in ${where?.name ?: parent}. It gets its own chat for feedback; you can publish new versions.")
        }
    }
}

// ---- events ------------------------------------------------------------------------------

private val DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** A field that opens a picker: looks like the text fields, is a button. */
@Composable
private fun PickerField(label: String, value: String, icon: ImageVector, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(8.dp)
    Column(modifier) {
        Muted(label)
        Gap(6)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape).border(1.dp, c.line, shape).clickable(role = Role.Button, onClick = onClick).testTag(tag).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, Modifier.weight(1f), fontSize = 15.sp, color = c.text, maxLines = 1)
            Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDate(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickTime(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(LocalTime.of(state.hour, state.minute))
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state) },
    )
}

private enum class Picking { None, StartDate, StartTime, EndDate, EndTime }

/**
 * Make an event, or edit one ([edit] = its coordinate): title, cover, when (all day or times),
 * where, online link, Markdown description, who sees it, who can come (anyone, or by
 * application with a question; editing: the Tickets sheet), its custom URL checked as it is
 * typed. From a channel's ＋ ([channel]), the new event's URL is put in that channel's composer
 * to send (no automatic post). Test hooks: [openPicker] ("start-date"), [showErrors].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateEventScreen(channel: String, openPicker: String? = null, showErrors: Boolean = false, edit: String? = null) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val zone = world.ctx.zone
    val old = remember(edit) { edit?.let { world.eventByKey(it) } }
    if (edit != null && old == null) {
        CardScreen("Edit the event") { sh.belfry.app.ui.kit.EmptyState("This event is not here (yet)", icon = BelfryIcons.Calendar) }
        return
    }
    val first = remember { old?.let { Instant.ofEpochSecond(it.start).atZone(zone) } ?: Instant.ofEpochSecond(session.now()).atZone(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0) }
    val last = remember { old?.let { o -> o.end?.let { Instant.ofEpochSecond(it).atZone(zone) } } ?: first.plusHours(1) }
    var title by remember { mutableStateOf(old?.title ?: "") }
    var allDay by remember { mutableStateOf(old?.allDay ?: false) }
    var startDate by remember { mutableStateOf(first.toLocalDate()) }
    var startTime by remember { mutableStateOf(first.toLocalTime()) }
    var endDate by remember { mutableStateOf(last.toLocalDate()) }
    var endTime by remember { mutableStateOf(last.toLocalTime()) }
    var location by remember { mutableStateOf(old?.location ?: "") }
    var online by remember { mutableStateOf(old?.links?.firstOrNull() ?: "") }
    var description by remember { mutableStateOf(old?.description ?: "") }
    var cover by remember { mutableStateOf(old?.cover) }
    var visibility by remember { mutableStateOf(old?.visibility ?: Visibility.MEMBERS) }
    var byApplication by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf(old?.slug ?: "") }
    var slugEdited by remember { mutableStateOf(old?.slug != null) }
    var slugState by remember { mutableStateOf<SlugCheck?>(null) }
    var slugChecking by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(showErrors) }
    var error by remember { mutableStateOf<String?>(null) }
    var coverError by remember { mutableStateOf<String?>(null) }
    // made or saved once: the button stays locked (a second tap never makes a second event)
    var done by remember { mutableStateOf(false) }
    val coverBusy = rememberBusy()
    var picking by remember { mutableStateOf(if (openPicker == "start-date") Picking.StartDate else if (openPicker == "start-time") Picking.StartTime else Picking.None) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) coverBusy.run(onError = { coverError = shown(it) }) {
            coverError = null
            val photo = Photos.load(context, uri, 0) ?: throw ActionException("photo", "This picture could not be read.")
            cover = session.uploadPicture(photo.attachment.bytes, "cover.jpg", photo.attachment.mime)
        }
    }

    // the custom URL: from the title until edited; checked as it is typed
    LaunchedEffect(title) { if (!slugEdited && old == null) slug = EventLinks.slugOf(title) }
    LaunchedEffect(slug) {
        slugState = null
        if (slug.isEmpty() || slug == old?.slug) return@LaunchedEffect
        val problem = EventLinks.slugProblem(slug)
        if (problem != null) { slugState = SlugCheck(false, slug, problem); return@LaunchedEffect }
        slugChecking = true
        delay(350)
        slugState = try { session.events.slugFree(slug, old?.d ?: "") } catch (e: Exception) { null }
        slugChecking = false
    }

    val start = ZonedDateTime.of(startDate, startTime, zone)
    val end = ZonedDateTime.of(endDate, endTime, zone)
    val link = online.trim()
    val linkBad = link.isNotEmpty() && !link.startsWith("https://")
    val endsBefore = if (allDay) endDate.isBefore(startDate) else end.isBefore(start)
    val places = remember(channel, world) { world.eventPlaces(channel).take(8) }
    val where = if (channel == Routes.NO_CHANNEL) null else world.group(channel)
    val slugTaken = slugState?.free == false

    CardScreen(if (old != null) "Edit the event" else "New event") {
        PageColumn {
            BelfryTextField(title, { title = it.take(200) }, Modifier.fillMaxWidth().testTag("event-title"), label = "Title", placeholder = "e.g. Garden day", error = if (tried && title.isBlank()) "Give the event a title" else null, keyboardOptions = words)
            Column {
                val cv = cover
                if (cv != null) {
                    EventCover(cv, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
                    Gap(8)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BusyButton("Change the cover", onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, kind = ButtonKind.Ghost, small = true, busy = coverBusy)
                        BusyButton("Remove", onClick = { cover = null }, kind = ButtonKind.Ghost, small = true)
                    }
                } else {
                    BusyButton(
                        if (coverBusy.busy) "Uploading…" else "Add a cover picture",
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.fillMaxWidth().testTag("event-cover"), kind = ButtonKind.Ghost, icon = BelfryIcons.Image, busy = coverBusy,
                    )
                }
                coverError?.let { Gap(6); ErrorLine(it) }
            }
            ActionGroup { SwitchRow("All day", allDay, { allDay = it }, icon = BelfryIcons.Calendar) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerField("Starts", DATE.format(startDate), BelfryIcons.Calendar, "start-date", Modifier.weight(1.5f)) { picking = Picking.StartDate }
                if (!allDay) PickerField("At", TIME.format(startTime), BelfryIcons.Clock, "start-time", Modifier.weight(1f)) { picking = Picking.StartTime }
            }
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PickerField("Ends", DATE.format(endDate), BelfryIcons.Calendar, "end-date", Modifier.weight(1.5f)) { picking = Picking.EndDate }
                    if (!allDay) PickerField("At", TIME.format(endTime), BelfryIcons.Clock, "end-time", Modifier.weight(1f)) { picking = Picking.EndTime }
                }
                if (tried && endsBefore) {
                    Gap(6)
                    ErrorLine("It ends before it starts")
                }
                if (!allDay) { Gap(6); Muted("Time zone: " + zone.id.replace('_', ' ')) }
            }
            Column {
                BelfryTextField(location, { location = it }, Modifier.fillMaxWidth(), label = "Where", placeholder = "A place, or leave empty if online only", keyboardOptions = words)
                if (places.isNotEmpty()) {
                    Gap(8)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (pl in places) Chip(pl, selected = location == pl, icon = BelfryIcons.MapPin, onClick = { location = if (location == pl) "" else pl })
                    }
                }
            }
            Column {
                BelfryTextField(
                    online, { online = it }, Modifier.fillMaxWidth().testTag("event-online"), label = "Online",
                    placeholder = "A Jitsi, Google Meet or Zoom link",
                    error = if (linkBad) "The link should start with https://" else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Gap(8)
                Chip("New Jitsi link", icon = BelfryIcons.Link, onClick = { online = Actions.jitsiLink(title.ifBlank { "meeting" }) })
            }
            BelfryTextField(description, { description = it }, Modifier.fillMaxWidth(), label = "Description", placeholder = "Markdown: **bold**, *italic*, - lists, [links](https://…)", singleLine = false, minLines = 3, maxLines = 8, keyboardOptions = words)
            Column {
                Text("Who sees it", fontSize = 13.sp, color = Belfry.colors.muted)
                Gap(6)
                // squares with a line each, like the web (Xavier, 2026-11-11); who can come is set
                // with the event's tickets, not here
                ChoiceSquares(Visibility.entries.map { it.label to it.hint }, visibility.ordinal, { visibility = Visibility.entries[it] }, Modifier.testTag("event-visibility"))
            }
            Column {
                BelfryTextField(
                    slug, { slugEdited = true; slug = EventLinks.cleanSlug(it) }, Modifier.fillMaxWidth().testTag("event-slug"), label = "URL",
                    placeholder = old?.d ?: "garden-day",
                    error = if (slugTaken) slugState?.problem ?: "Taken. Try ${slugState?.suggestion}" else null,
                    hint = when {
                        slug.isEmpty() -> null
                        slugChecking -> "Checking…"
                        slugState?.free == true -> "Free"
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                Gap(4)
                Muted(EventLinks.url(world.ctx.domain, slug.ifEmpty { old?.d ?: "…" }))
            }
            error?.let { ErrorLine(it) }
            BusyButton(
                if (old != null) "Save" else "Create the event",
                onClick = {
                    tried = true
                    error = null
                    if (!done && title.isNotBlank() && !linkBad && !endsBefore && !slugTaken) {
                        done = true
                        val n = EventForm(
                            title = title.trim(), description = description.trim(), allDay = allDay,
                            start = start.toEpochSecond(), end = end.toEpochSecond(),
                            startDate = startDate.toString(), endDate = endDate.toString(), tz = zone.id,
                            location = location.trim(), online = link, visibility = visibility, slug = slug.trim('-'), cover = cover,
                            applicationPrompt = if (byApplication && old == null) question.trim().ifEmpty { "Why would you like to come?" } else null,
                        )
                        if (old != null) {
                            session.events.edit(old, n)
                            nav.back()
                        } else {
                            val made = session.events.create(n)
                            nav.back()
                            if (where != null) EventHandoff.hand(channel, EventLinks.url(world.ctx.domain, made))
                            else nav.go(Routes.event(made))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("create-submit"),
                enabled = !coverBusy.busy && !done,
                onError = { done = false; error = shown(it) },
            )
            Muted(
                if (old != null) "Everyone who RSVPed sees the change."
                else "It gets its own chat, photos and to-dos." + if (where != null) " Its URL goes in #${where.name}’s message box, ready to send." else " Send its URL in a channel to show it there.",
            )
        }
    }

    when (picking) {
        Picking.None -> Unit
        Picking.StartDate -> PickDate(startDate, { picking = Picking.None }) {
            startDate = it
            if (endDate.isBefore(it)) endDate = it
        }
        Picking.EndDate -> PickDate(endDate, { picking = Picking.None }) { endDate = it }
        Picking.StartTime -> PickTime(startTime, { picking = Picking.None }) {
            startTime = it
            if (endDate == startDate && endTime.isBefore(it)) endTime = it.plusHours(1).let { e -> if (e.isBefore(it)) LocalTime.of(23, 59) else e }
        }
        Picking.EndTime -> PickTime(endTime, { picking = Picking.None }) { endTime = it }
    }
}


/** Options as equal squares, each with its name and a short line; the picked one is outlined. */
@Composable
private fun ChoiceSquares(options: List<Pair<String, String>>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (label, hint) ->
            val on = i == selected
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.weight(1f).heightIn(min = 76.dp).clip(shape)
                    .background(if (on) c.surface else Color.Transparent)
                    .border(if (on) 2.dp else 1.dp, if (on) c.text else c.line, shape)
                    .selectable(selected = on, role = Role.RadioButton) { onPick(i) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text(hint, fontSize = 12.sp, color = c.muted, lineHeight = 16.sp)
            }
        }
    }
}
