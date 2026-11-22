package sh.belfry.app.ui.cards

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.InlinePictures
import sh.belfry.app.ui.kit.PICTURE_FAILED
import sh.belfry.app.ui.kit.Spinner
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.people.sizeText
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.mail.MailPart
import sh.belfry.core.mail.ParsedMail
import sh.belfry.core.model.MailOriginal
import sh.belfry.core.model.Pictures

/** Whole e-mails already opened, by their file's URL: a card scrolled back into view isn't fetched again. */
private val opened = object : LinkedHashMap<String, ParsedMail>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedMail>?): Boolean = size > 16
}

private suspend fun openCached(o: MailOriginal): ParsedMail? {
    synchronized(opened) { opened[o.url] }?.let { return it }
    return openOriginal(o)?.also { synchronized(opened) { opened[o.url] = it } }
}

/**
 * The attachments of an e-mail in a channel (canvas v179 one-shape/email-pictures): the post carries
 * only `mail-original` (the whole e-mail, encrypted on the file server), so it is opened on the device
 * when the card is shown. Nothing shows while it opens or when it has no attachments (the card doesn't
 * know there are any); a failure shows nothing either, "Full e-mail" stays.
 */
@Composable
internal fun MailAttachments(original: MailOriginal?, modifier: Modifier = Modifier) {
    if (original == null) return
    val session = LocalSession.current
    var mail by remember(original.url) { mutableStateOf<ParsedMail?>(null) }
    LaunchedEffect(original.url) {
        if (!session.localOnly) mail = try { openCached(original) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
    }
    mail?.let { MailParts(it.attachments, original.url, modifier) }
}

/**
 * Attachments after the text (canvas v179): pictures (JPEG, PNG, GIF, WebP, HEIC) in the message, one
 * large or the grid, in the mail's order; then the other files as rows that save the file. A picture
 * that can't be shown falls back to its row, "Picture · couldn't be opened".
 */
@Composable
internal fun MailParts(parts: List<MailPart>, key: Any, modifier: Modifier = Modifier) {
    if (parts.isEmpty()) return
    val pictures = parts.indices.filter { Pictures.isPicture(parts[it].type, parts[it].name) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember(key) { mutableStateOf<MailPart?>(null) }
    var note by remember(key) { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val part = saving
        if (uri == null || part == null) { saving = null; return@rememberLauncherForActivityResult }
        scope.launch {
            note = try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(part.data) } }
                "Saved."
            } catch (e: Exception) { "Not saved. Try again." }
            saving = null
        }
    }
    val row: @Composable (MailPart, Boolean) -> Unit = { part, failed ->
        val c = Belfry.colors
        Row(
            Modifier.widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface)
                .clickable(enabled = saving == null, role = Role.Button, onClickLabel = "Save the file") {
                    saving = part; note = null
                    try { saver.launch(part.name.ifEmpty { "attachment" }) } catch (e: Exception) { saving = null; note = "No app on this device can save files." }
                }
                .padding(horizontal = 10.dp, vertical = 8.dp).testTag("mail-file"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (saving === part) Spinner(size = 16.dp) else Icon(if (failed) BelfryIcons.Image else BelfryIcons.Upload, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(part.name.ifEmpty { "attachment" }, fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(if (failed) PICTURE_FAILED else sizeText(part.data.size.toLong()), fontSize = 12.sp, color = c.muted, maxLines = 1)
        }
    }
    Column(modifier.padding(top = 6.dp).testTag("mail-parts"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (pictures.isNotEmpty()) {
            InlinePictures(
                key = key, count = pictures.size, names = pictures.map { parts[it].name.ifEmpty { null } },
                load = { pictures.map { parts[it].data } },
                fallback = { j -> row(parts[pictures[j]], true) },
            )
        }
        for (i in parts.indices) if (i !in pictures) row(parts[i], false)
        note?.let { Text(it, fontSize = 12.sp, color = Belfry.colors.muted) }
    }
}
