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
fun CreateProposalScreen(parent: String) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
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

/** Test hook: which picker the form shows when it opens ("start-date" for the screenshot). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateEventScreen(channel: String, openPicker: String? = null, showErrors: Boolean = false) {
    val session = LocalSession.current
    val world = LocalWorld.current
    val nav = LocalNav.current
    val zone = world.ctx.zone
    val first = remember { Instant.ofEpochSecond(session.now()).atZone(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0) }
    var title by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf(first.toLocalDate()) }
    var startTime by remember { mutableStateOf(first.toLocalTime()) }
    var endDate by remember { mutableStateOf(first.plusHours(1).toLocalDate()) }
    var endTime by remember { mutableStateOf(first.plusHours(1).toLocalTime()) }
    var location by remember { mutableStateOf("") }
    var online by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(showErrors) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(if (openPicker == "start-date") Picking.StartDate else if (openPicker == "start-time") Picking.StartTime else Picking.None) }

    val start = ZonedDateTime.of(startDate, startTime, zone)
    val end = ZonedDateTime.of(endDate, endTime, zone)
    val link = online.trim()
    val linkBad = link.isNotEmpty() && !link.startsWith("https://")
    val endsBefore = if (allDay) endDate.isBefore(startDate) else end.isBefore(start)
    val places = remember(channel, world) { world.eventPlaces(channel).take(8) }
    val where = world.group(channel)

    CardScreen("Post an event") {
        PageColumn {
            BelfryTextField(title, { title = it }, Modifier.fillMaxWidth().testTag("event-title"), label = "Title", placeholder = "e.g. Nix workshop", error = if (tried && title.isBlank()) "Give the event a title" else null, keyboardOptions = words)
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
                    placeholder = "Paste a Google Meet, Zoom or other link",
                    error = if (linkBad) "The link should start with https://" else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Gap(8)
                Chip("New Jitsi link", icon = BelfryIcons.Link, onClick = { online = Actions.jitsiLink(title.ifBlank { "meeting" }) })
            }
            BelfryTextField(description, { description = it }, Modifier.fillMaxWidth(), label = "Description", placeholder = "Markdown: **bold**, *italic*, - lists, [links](https://…)", singleLine = false, minLines = 3, maxLines = 8, keyboardOptions = words)
            error?.let { ErrorLine(it) }
            BusyButton(
                "Post event",
                onClick = {
                    tried = true
                    error = null
                    if (title.isNotBlank() && !linkBad && !endsBefore) {
                        val n = NewEvent(
                            title = title.trim(),
                            description = description.trim(),
                            location = location.trim(),
                            allDay = allDay,
                            start = start.toEpochSecond(),
                            end = end.toEpochSecond(),
                            startDate = startDate.toString(),
                            endDate = endDate.toString(),
                            online = link.ifEmpty { null },
                            tzid = zone.id,
                            whenLabel = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(start),
                        )
                        val coord = session.createEvent(n, channel)
                        nav.back()
                        nav.go(Routes.event(coord))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("create-submit"),
                onError = { error = shown(it) },
            )
            Muted("Posted in #${where?.name ?: channel}. The event gets its own chat.")
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
