package sh.belfry.app.ui.people

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.belfry.app.rec.RecException
import sh.belfry.app.rec.Recorder
import sh.belfry.app.scan.openAppSettings
import sh.belfry.app.ui.chat.ChatFormat
import sh.belfry.app.ui.chat.ComposerState
import sh.belfry.app.ui.chat.RecView
import sh.belfry.app.ui.chat.RecorderBody
import sh.belfry.app.ui.chat.rememberSendLine
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.MoreIcons
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.audio.RecNotice
import sh.belfry.core.audio.RecPhase
import sh.belfry.core.dm.Dm
import sh.belfry.core.dm.DmFiles
import sh.belfry.core.dm.DmNewFile
import sh.belfry.core.dm.DmSnapshot
import sh.belfry.core.model.World

/** Files of one message, or several pictures sent together, with their caption (canvas v109). */
class DmGroup(val key: String, val files: List<Dm>, val caption: Dm?) {
    val first: Dm get() = files.first()
    val at: Long get() = minOf(first.at, caption?.at ?: Long.MAX_VALUE)
    val mine: Boolean get() = first.mine
}

object DmGroups {
    /**
     * The thread with files folded: each file, or each group (same author, same group tag) with
     * at least one file, becomes one [DmGroup] where its first part was; files in order
     * (created_at, then id: vectors/dm-files.json), the caption under them.
     */
    fun collapse(list: List<Dm>): List<Any> {
        val groups = list.filter { it.group != null }.groupBy { it.from + "/" + it.group }
            .filterValues { g -> g.any { it.isFile } }
        val out = ArrayList<Any>()
        val done = HashSet<String>()
        for (d in list) {
            val k = d.group?.let { d.from + "/" + it }
            val g = k?.let { groups[it] }
            when {
                g != null -> if (done.add(k)) out.add(DmGroup(k, g.filter { it.isFile }.sortedWith(compareBy({ it.at }, { it.rid })), g.lastOrNull { it.kind == 14 }))
                d.isFile -> out.add(DmGroup(d.rid, listOf(d), null))
                else -> out.add(d)
            }
        }
        return out
    }
}

/** A file message: the pictures / file / recording without a bubble, then time and the send line; the caption as an ordinary message under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FilesBubble(g: DmGroup, dms: DmSnapshot, world: World, group: Boolean, onMenu: (rid: String, emojiOnly: Boolean) -> Unit, caption: @Composable (Dm) -> Unit) {
    val c = Belfry.colors
    val session = LocalSession.current
    val retry = rememberBusy()
    val head = g.first
    val pending = g.files.any { it.pending }
    val failed = g.files.firstOrNull { it.failed != null }
    val chips = dms.reactionsOf(head.rid)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).testTag("dm-files"),
        horizontalAlignment = if (head.mine) Alignment.End else Alignment.Start,
    ) {
        if (!head.mine && group) Text(world.displayName(head.from), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Box(Modifier.combinedClickable(onClickLabel = null, onLongClickLabel = "Message actions", onLongClick = { onMenu(head.rid, false) }, onClick = {})) {
            DmFileParts(g.files, pending)
        }
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ChatFormat.time(world, head.at), fontSize = 11.sp, color = c.muted)
            // one line for the whole block (canvas v109: one Retry for pictures sent together)
            val pendingOne = g.files.firstOrNull { it.pending && it.failed == null }
            val line = if (head.mine && pendingOne != null) rememberSendLine(pendingOne.rid, 0) else null
            val text = failed?.let { "Not sent: ${it.failed}" } ?: line?.text
            if (text != null) {
                Spacer(Modifier.width(8.dp))
                Text(text, fontSize = 11.sp, color = if (failed != null) c.danger else c.muted, modifier = Modifier.weight(1f, fill = false).testTag("dm-files-line"))
            }
            if (failed != null || line?.retry == true) {
                Spacer(Modifier.width(6.dp))
                if (retry.busy) Spinner(size = 12.dp) else Text(
                    "Retry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (failed != null) c.danger else c.text,
                    modifier = Modifier.clickable(role = Role.Button) {
                        retry.run { if (failed != null) g.files.filter { it.failed != null }.forEach { session.retryDm(it.rid) } else session.sendNow() }
                    }.padding(horizontal = 4.dp, vertical = 2.dp).testTag("dm-files-retry"),
                )
            }
        }
        if (chips.isNotEmpty()) DmReactionChips(head, chips, onAdd = { onMenu(head.rid, true) })
    }
    g.caption?.let { caption(it) }
}

/** The ＋ in a conversation (canvas v109 messages/attach-plus): Photos, File, Audio recording. */
@Composable
internal fun DmPlusSheet(conv: String, composer: ComposerState, onClose: () -> Unit, onRecord: () -> Unit) {
    val context = LocalContext.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val guest = session.guest.collectAsState().value != null
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            composer.error = null
            try {
                val f = readPicked(context, uri, if (guest) DmFiles.MAX_GUEST else DmFiles.MAX)
                if (f == null) composer.error = DmFiles.tooLarge(guest)
                else session.sendDmFiles(conv, listOf(f))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: sh.belfry.core.engine.ActionException) {
                composer.error = e.message
            } catch (e: Exception) {
                composer.error = "This file could not be read."
            }
        }
    }
    BelfrySheet(onDismiss = onClose) {
        ActionGroup {
            ActionRow("Photos", onClick = { onClose(); composer.pickPhotos?.invoke() }, icon = BelfryIcons.Image, sub = "Pictures from this device", modifier = Modifier.testTag("dm-plus-photos"))
            // a video goes as a file (canvas v109)
            ActionRow("File", onClick = { onClose(); pick.launch(arrayOf("*/*")) }, icon = MoreIcons.File, sub = "A document or a video", modifier = Modifier.testTag("dm-plus-file"))
            ActionRow("Audio recording", onClick = { onClose(); onRecord() }, icon = MoreIcons.Mic, sub = "Record here; kept on this device until sent", modifier = Modifier.testTag("dm-plus-record"))
        }
    }
}

/** The picked file's bytes, name and type; null when it is larger than MAX (it is not read whole then). */
private suspend fun readPicked(context: Context, uri: Uri, max: Int): DmNewFile? = withContext(Dispatchers.IO) {
    val r = context.contentResolver
    var name = "file"
    var size = -1L
    r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) {
            cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> cur.getString(i)?.let { name = it } }
            cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cur.isNull(it) }?.let { i -> size = cur.getLong(i) }
        }
    }
    if (size > max) return@withContext null
    val bytes = r.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return@withContext null
        }
        out.toByteArray()
    } ?: throw java.io.IOException("unreadable")
    DmNewFile(name, r.getType(uri) ?: "application/octet-stream", bytes)
}

/** The channel's recorder for a direct message: Stop sends it (no "where" step). */
@Composable
internal fun DmRecorderSheet(conv: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val session = LocalSession.current
    val rec by Recorder.state.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var ms by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    val target = "dm:$conv"
    val live = rec.meta?.takeIf { rec.live && it.groupId == target }
    LaunchedEffect(live?.id) { while (live != null) { ms = Recorder.elapsed(); level = Recorder.level(); delay(250) } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { denied = false; Recorder.start(context, session.accountId, target) } else denied = true
    }
    val view = when {
        live?.phase == RecPhase.PAUSED -> RecView.PAUSED
        live != null -> RecView.RECORDING
        else -> RecView.READY
    }
    BelfrySheet(onDismiss = { if (!sending) onClose() }, title = "Audio recording") {
        RecorderBody(
            view = view, ms = if (live != null) ms else 0, level = level,
            notice = error ?: if (denied) RecNotice.NO_PERMISSION.text else rec.notice?.text,
            events = emptyList(), channelName = "this conversation", eventLine = { "" },
            onStart = {
                error = null
                Recorder.dismissNotice()
                if (Recorder.permitted(context)) Recorder.start(context, session.accountId, target) else launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onPause = { Recorder.pause() },
            onResume = { Recorder.resume() },
            onStop = {
                Recorder.stop()
                val m = Recorder.state.value.meta
                if (m != null && m.phase == RecPhase.STOPPED && m.groupId == target) {
                    sending = true
                    try {
                        Recorder.postToDm(context, session, m, conv)
                        onClose()
                    } catch (e: RecException) {
                        error = e.notice.text
                    } catch (e: sh.belfry.core.engine.ActionException) {
                        error = e.message
                    } catch (e: Exception) {
                        error = "The recording could not be handed to the outbox. It is still on this device."
                    } finally { sending = false }
                }
            },
            onPost = { },
            onDiscard = { onClose() },
            onSettings = if (denied) ({ openAppSettings(context) }) else null,
        )
    }
}
