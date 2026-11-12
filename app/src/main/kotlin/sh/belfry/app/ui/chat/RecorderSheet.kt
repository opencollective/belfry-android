package sh.belfry.app.ui.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.belfry.app.rec.RecException
import sh.belfry.app.rec.Recorder
import sh.belfry.app.scan.openAppSettings
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.audio.RecMeta
import sh.belfry.core.audio.RecNotice
import sh.belfry.core.audio.RecPhase
import sh.belfry.core.audio.Recordings
import sh.belfry.core.model.CalEvent

/** What the recorder's sheet shows. */
enum class RecView { READY, RECORDING, PAUSED, WHERE }

/**
 * ＋ → Audio recording (the web's Recorder.tsx, 2026-11-12): record with pause and resume and the
 * length so far; at the end, which event or meeting it was (not asked in an event's own chat).
 * The audio is written to the device as it records and is only forgotten once the outbox holds
 * it. Closing the sheet while it records keeps it recording (a bar in the chat brings it back).
 * [resumeOf]: an unfinished recording to post.
 */
@Composable
fun RecorderSheet(groupId: String, resumeOf: RecMeta? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val session = LocalSession.current
    val world = LocalWorld.current
    val rec by Recorder.state.collectAsState()
    var done by remember { mutableStateOf(resumeOf) }
    var error by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }
    var ms by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    val live = rec.meta?.takeIf { rec.live && done == null }
    LaunchedEffect(live?.id) {
        while (live != null) { ms = Recorder.elapsed(); level = Recorder.level(); delay(250) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { denied = false; Recorder.start(context, session.accountId, groupId) } else denied = true
    }
    val post: suspend (RecMeta, String) -> Unit = { m, target ->
        error = null
        try {
            Recorder.post(context, session, m, target)
            onClose()
        } catch (e: RecException) {
            error = e.notice.text
        } catch (e: Exception) {
            error = "The recording could not be handed to the outbox. It is still on this device."
        }
    }
    val stopped = done
    val view = when {
        stopped != null -> RecView.WHERE
        live?.phase == RecPhase.PAUSED -> RecView.PAUSED
        live != null -> RecView.RECORDING
        else -> RecView.READY
    }
    val at = stopped?.startedAt ?: System.currentTimeMillis()
    BelfrySheet(onDismiss = onClose, title = "Audio recording") {
        RecorderBody(
            view = view,
            ms = stopped?.ms ?: if (live != null) ms else 0,
            level = level,
            notice = error ?: when {
                denied -> RecNotice.NO_PERMISSION.text
                else -> rec.notice?.text
            },
            events = if (view == RecView.WHERE) Recordings.candidates(world.calendar, at) else emptyList(),
            channelName = world.group(stopped?.groupId ?: groupId)?.name ?: (stopped?.groupId ?: groupId),
            eventLine = { e -> ChatFormat.day(world, e.start, session.now()) + " · " + ChatFormat.time(world, e.start) + (e.location?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") },
            onStart = {
                error = null
                Recorder.dismissNotice()
                if (Recorder.permitted(context)) Recorder.start(context, session.accountId, groupId) else launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onPause = { Recorder.pause() },
            onResume = { Recorder.resume() },
            onStop = {
                Recorder.stop()
                val m = Recorder.state.value.meta
                if (m != null && m.phase == RecPhase.STOPPED) {
                    // in an event's own chat it is that event's: posted at once
                    if (m.groupId.startsWith("ev-")) { done = m; post(m, m.groupId) } else done = m
                }
            },
            onPost = { target -> stopped?.let { post(it, target ?: it.groupId) } },
            onDiscard = {
                stopped?.let { withContext(Dispatchers.IO) { Recorder.discard(context, it.id) } }
                onClose()
            },
            onSettings = if (denied) ({ openAppSettings(context) }) else null,
        )
    }
}

/** The sheet's body, without the microphone (screenshot tests draw it). */
@Composable
fun RecorderBody(
    view: RecView,
    ms: Long,
    level: Float,
    notice: String?,
    events: List<CalEvent>,
    channelName: String,
    eventLine: (CalEvent) -> String,
    onStart: suspend () -> Unit,
    onPause: suspend () -> Unit,
    onResume: suspend () -> Unit,
    onStop: suspend () -> Unit,
    onPost: suspend (target: String?) -> Unit,
    onDiscard: suspend () -> Unit,
    onSettings: (() -> Unit)? = null,
) {
    val c = Belfry.colors
    var discarding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("recorder-$view")) {
        if (view != RecView.WHERE) {
            Text(
                Recordings.clock(ms), fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                color = if (view == RecView.RECORDING) c.text else c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("recorder-clock"),
            )
            Spacer(Modifier.height(10.dp))
            // the input level: 24 bars
            Row(Modifier.fillMaxWidth().height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                repeat(24) { i ->
                    val lit = view == RecView.RECORDING && i / 24f < level
                    Box(Modifier.width(6.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(if (lit) c.text else c.line))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when (view) {
                    RecView.READY -> "Kept on this device while it records, until it is posted. It goes on with the screen off."
                    RecView.PAUSED -> "Paused"
                    else -> "Recording · saved on this device as it goes"
                },
                fontSize = 14.sp, lineHeight = 19.sp, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            if (notice != null) {
                Spacer(Modifier.height(8.dp))
                Text(notice, fontSize = 15.sp, lineHeight = 20.sp, color = c.danger, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("recorder-notice"))
            }
            Spacer(Modifier.height(16.dp))
            when (view) {
                RecView.READY -> {
                    BusyButton("Start recording", onClick = onStart, modifier = Modifier.fillMaxWidth().testTag("recorder-start"), icon = MoreIcons.Mic)
                    if (onSettings != null) {
                        Spacer(Modifier.height(8.dp))
                        BusyButton("Open the app’s settings", onClick = { onSettings() }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
                    }
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // one lock for both: a tap on Stop while Pause works is not lost, it waits
                    val busy = rememberBusy()
                    if (view == RecView.PAUSED) {
                        BusyButton("Resume", onClick = onResume, modifier = Modifier.weight(1f).testTag("recorder-resume"), kind = ButtonKind.Ghost, busy = busy)
                    } else {
                        BusyButton("Pause", onClick = onPause, modifier = Modifier.weight(1f).testTag("recorder-pause"), kind = ButtonKind.Ghost, busy = busy)
                    }
                    BusyButton("Stop", onClick = onStop, modifier = Modifier.weight(1f).testTag("recorder-stop"), busy = busy)
                }
            }
        } else {
            Text("${Recordings.clock(ms)} recorded · kept on this device", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            if (notice != null) {
                Spacer(Modifier.height(6.dp))
                Text(notice, fontSize = 15.sp, lineHeight = 20.sp, color = c.danger, modifier = Modifier.testTag("recorder-notice"))
            }
            Spacer(Modifier.height(14.dp))
            val busy = rememberBusy()
            if (events.isNotEmpty()) {
                Text("Which event or meeting was it?", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.muted)
                Spacer(Modifier.height(8.dp))
                ActionGroup {
                    for (e in events) {
                        BusyRow(
                            (if (e.meeting) "Meeting · " else "") + e.title, onClick = { onPost(e.groupId) }, icon = BelfryIcons.Calendar,
                            sub = eventLine(e), busy = busy, modifier = Modifier.testTag("recorder-event"),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            ActionGroup {
                BusyRow(
                    if (events.isEmpty()) "Post it in # $channelName" else "None: post it in # $channelName", onClick = { onPost(null) },
                    icon = BelfryIcons.Send, sub = "It uploads from this device, and tries again until it is out.", busy = busy,
                    modifier = Modifier.testTag("recorder-post"),
                )
            }
            Spacer(Modifier.height(12.dp))
            ActionGroup {
                ActionRow("Discard", onClick = { discarding = true }, icon = BelfryIcons.Trash, danger = true, enabled = !busy.busy, modifier = Modifier.testTag("recorder-discard"))
            }
        }
    }
    if (discarding) {
        ConfirmSheet(
            title = "Discard this recording?", confirmLabel = "Discard", message = "It cannot be brought back.",
            onConfirm = { onDiscard(); discarding = false }, onDismiss = { discarding = false },
        )
    }
}

/**
 * In a chat, above the composer: the live recording (tap: back to its sheet), or a recording that
 * was not posted (stopped, or cut short when the app was closed) with "Post it" and "Discard".
 */
@Composable
fun RecordingBar(groupId: String) {
    val context = LocalContext.current
    val session = LocalSession.current
    val world = LocalWorld.current
    val rec by Recorder.state.collectAsState()
    var left by remember { mutableStateOf<List<RecMeta>>(emptyList()) }
    var open by remember { mutableStateOf<RecMeta?>(null) }
    var openLive by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf<RecMeta?>(null) }
    var ms by remember { mutableLongStateOf(0L) }
    val live = rec.meta?.takeIf { rec.live }
    LaunchedEffect(rec.meta?.id, rec.live, open, discarding) {
        left = withContext(Dispatchers.IO) { try { Recorder.unfinished(context, session.accountId) } catch (e: Exception) { emptyList() } }
    }
    LaunchedEffect(live?.id) { while (live != null) { ms = Recorder.elapsed(); delay(500) } }
    val first = left.firstOrNull()
    RecordingBarContent(
        live = live?.let { (it.phase == RecPhase.PAUSED) to ms },
        notice = rec.notice?.text.takeIf { live != null },
        unfinished = if (live == null && first != null) Recordings.clock(first.ms) + " · " + ChatFormat.day(world, first.startedAt / 1000, session.now()) else null,
        onOpen = { openLive = true },
        onPost = { open = first },
        onDiscard = { discarding = first },
    )
    if (openLive) RecorderSheet(live?.groupId ?: groupId, onClose = { openLive = false })
    open?.let { m -> RecorderSheet(m.groupId, resumeOf = m, onClose = { open = null }) }
    discarding?.let { m ->
        ConfirmSheet(
            title = "Discard this recording?", confirmLabel = "Discard",
            message = "${Recordings.clock(m.ms)} recorded. It cannot be brought back.",
            onConfirm = { withContext(Dispatchers.IO) { Recorder.discard(context, m.id) }; discarding = null },
            onDismiss = { discarding = null },
        )
    }
}

/** [live]: paused? and the recorded time. [unfinished]: its length and day. */
@Composable
fun RecordingBarContent(live: Pair<Boolean, Long>?, notice: String?, unfinished: String?, onOpen: () -> Unit, onPost: suspend () -> Unit, onDiscard: suspend () -> Unit) {
    val c = Belfry.colors
    if (live == null && unfinished == null) return
    Hairline()
    if (live != null) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).clickable(role = Role.Button, onClickLabel = "Open the recording", onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("recording-live"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (live.first) c.muted else c.danger))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text((if (live.first) "Paused" else "Recording") + " · " + Recordings.clock(live.second), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                if (notice != null) Text(notice, fontSize = 13.sp, lineHeight = 17.sp, color = c.danger)
            }
            Text("Open", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.text)
        }
    } else if (unfinished != null) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 8.dp).testTag("recording-unfinished"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("An unfinished recording", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(unfinished, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            BusyButton("Discard", onClick = onDiscard, kind = ButtonKind.Ghost, small = true, modifier = Modifier.testTag("recording-discard"))
            Spacer(Modifier.width(8.dp))
            BusyButton("Post it", onClick = onPost, small = true, modifier = Modifier.testTag("recording-post"))
        }
    }
}
