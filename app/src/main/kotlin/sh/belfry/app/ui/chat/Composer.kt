package sh.belfry.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyIconButton
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.engine.NewAttachment
import sh.belfry.core.model.Mentions

/** What the person is writing: the text with its caret, picked mentions, attached photos. */
@Stable
class ComposerState {
    var value by mutableStateOf(TextFieldValue(""))
    /** (name, pubkey) of the people picked with @; sent as `nostr:npub…`. */
    val picked = mutableStateListOf<Pair<String, String>>()
    val photos = mutableStateListOf<PickedPhoto>()
    var announce by mutableStateOf(false)
    var loadingPhotos by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
    /** Opens the system photo picker (set by the composer on screen). */
    var pickPhotos: (() -> Unit)? = null
    /** Asks the field for focus (set by the composer on screen). */
    var focus: (() -> Unit)? = null

    fun clear() {
        value = TextFieldValue("")
        picked.clear()
        photos.clear()
        announce = false
        error = null
    }

    fun set(text: String, picks: List<Pair<String, String>> = emptyList()) {
        value = TextFieldValue(text, TextRange(text.length))
        picked.clear()
        picked.addAll(picks)
        error = null
    }
}

@Composable
fun rememberComposerState(key: Any? = null): ComposerState = remember(key) { ComposerState() }

/**
 * The message box: a field that grows to a few lines (Enter adds a line), the @ picker above it,
 * picked photos as removable thumbnails, and a send button that locks while [onSend] runs, so a
 * double tap never sends twice. [onPlus] shows the ＋ button (the "add" sheet); without it and
 * with [photos], a photo button opens the system picker directly.
 */
@Composable
fun Composer(
    state: ComposerState,
    placeholder: String,
    onSend: suspend (text: String, files: List<NewAttachment>, picked: List<Pair<String, String>>, announce: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    photos: Boolean = true,
    autoFocus: Boolean = false,
    onPlus: (() -> Unit)? = null,
) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val busy = rememberBusy()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            state.loadingPhotos += uris.size
            uris.forEachIndexed { i, uri ->
                val p = Photos.load(context, uri, i)
                state.loadingPhotos -= 1
                if (p != null) state.photos.add(p) else state.error = "A photo could not be read."
            }
        }
    }
    SideEffect {
        state.pickPhotos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        state.focus = { try { focusRequester.requestFocus() } catch (e: IllegalStateException) { } }
    }
    if (autoFocus) LaunchedEffect(Unit) { try { focusRequester.requestFocus() } catch (e: IllegalStateException) { } }

    val v = state.value
    val caret = v.selection.end.coerceIn(0, v.text.length)
    val query = if (v.selection.collapsed) Mentions.query(v.text.substring(0, caret)) else null
    val candidates = if (query != null) Mentions.candidates(query, world.people, world.me) else emptyList()
    val canSend = (v.text.isNotBlank() || state.photos.isNotEmpty()) && state.loadingPhotos == 0

    Column(modifier.fillMaxWidth()) {
        if (query != null && candidates.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)) {
                candidates.forEachIndexed { i, (pubkey, name) ->
                    if (i > 0) Hairline()
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            val (text, pos) = Mentions.pick(v.text, caret, query, name)
                            state.value = TextFieldValue(text, TextRange(pos))
                            if (state.picked.none { it.second == pubkey }) state.picked.add(name to pubkey)
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(world.face(pubkey), size = 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(name, fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (state.announce) {
            Row(Modifier.fillMaxWidth().background(c.surface).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(BelfryIcons.Megaphone, contentDescription = null, tint = c.orange, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Announcement: sent to the channel and e-mailed to its followers", fontSize = 13.sp, color = c.text, modifier = Modifier.weight(1f))
                IconAction(BelfryIcons.Close, "Not an announcement", { state.announce = false })
            }
        }
        if (state.photos.isNotEmpty() || state.loadingPhotos > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // At most 6 photos of 52 dp: fits a phone's width without scrolling.
                state.photos.forEachIndexed { i, p ->
                    Box(Modifier.size(52.dp)) {
                        Image(p.thumb, contentDescription = "Photo ${i + 1}", contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(8.dp)))
                        Box(
                            Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(c.accent).clickable(role = Role.Button, onClickLabel = "Remove photo ${i + 1}") { state.photos.remove(p) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(BelfryIcons.Close, contentDescription = "Remove photo ${i + 1}", tint = c.onAccent, modifier = Modifier.size(12.dp)) }
                    }
                }
                if (state.loadingPhotos > 0) Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Spinner() }
            }
        }
        state.error?.let { Text(it, fontSize = 13.sp, color = c.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
        Row(Modifier.fillMaxWidth().padding(start = if (compact) 0.dp else 4.dp, end = if (compact) 0.dp else 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            if (onPlus != null) {
                IconAction(BelfryIcons.Plus, "Add to the conversation", onPlus)
            } else if (photos) {
                IconAction(BelfryIcons.Image, "Add photos", { state.pickPhotos?.invoke() })
            } else {
                Spacer(Modifier.width(8.dp))
            }
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(shape).border(1.dp, c.line, shape).background(c.bg).padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (v.text.isEmpty()) Text(placeholder, fontSize = 16.sp, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = v,
                    onValueChange = { state.value = it; state.error = null },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("composer-field"),
                    textStyle = TextStyle(color = c.text, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(c.text),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(40.dp).clip(shape).background(if (canSend || busy.busy) c.accent else c.surface2), contentAlignment = Alignment.Center) {
                BusyIconButton(
                    BelfryIcons.Send, "Send",
                    onClick = {
                        val text = state.value.text.trim()
                        val files = state.photos.map { it.attachment }
                        if (text.isNotEmpty() || files.isNotEmpty()) {
                            onSend(text, files, state.picked.toList(), state.announce)
                            state.clear()
                        }
                    },
                    modifier = Modifier.testTag("composer-send"),
                    enabled = canSend,
                    tint = if (canSend || busy.busy) c.onAccent else c.faint,
                    busy = busy,
                    onError = { state.error = (it as? ActionException)?.message ?: "Not sent. Try again." },
                )
            }
        }
    }
}

/** "Editing your message · Cancel", above the composer while an edit is open. */
@Composable
fun EditingBar(onCancel: () -> Unit) {
    val c = Belfry.colors
    Row(Modifier.fillMaxWidth().background(c.surface).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(BelfryIcons.Edit, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Editing your message", fontSize = 13.sp, color = c.text, modifier = Modifier.weight(1f))
        Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.clickable(role = Role.Button, onClick = onCancel).padding(4.dp))
    }
}
