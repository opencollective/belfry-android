package sh.belfry.app.ui.people

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.BusyState
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.LocalNetworkImages
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.app.ui.theme.brandColour
import sh.belfry.app.ui.theme.onColour
import sh.belfry.core.engine.ActionException
import sh.belfry.core.net.Branding

/**
 * Me → Branding (web Branding.tsx, 2026-11-11): the belfry's admins set its logo, cover image and
 * two colours. Save publishes them for everyone (`POST <api>/branding`; the belfry keeps them in
 * its own key's profile and lists them in belfry.json).
 */
@Composable
fun BrandingScreen() {
    val session = LocalSession.current
    val context = LocalContext.current
    var b by remember { mutableStateOf(session.branding) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val logoBusy = rememberBusy()
    val coverBusy = rememberBusy()

    fun pick(busy: BusyState, name: String, side: Int, set: (String) -> Unit): (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            error = null
            busy.run(onError = { error = it.shown("The picture was not uploaded. Try again.") }) {
                val bytes = AvatarPhoto.load(context, uri, side) ?: throw ActionException("photo", "This picture could not be read.")
                set(session.uploadPicture(bytes, name))
                saved = false
            }
        }
    }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), pick(logoBusy, "logo.jpg", 512) { b = b.copy(logo = it) })
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), pick(coverBusy, "cover.jpg", 1600) { b = b.copy(cover = it) })
    val images = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Page("Branding", subtitle = session.community.name) {
        BrandingForm(
            b, { b = it; saved = false; error = null }, error, saved, logoBusy, coverBusy,
            onLogo = { logoPicker.launch(images) }, onCover = { coverPicker.launch(images) },
            onSave = {
                val bad = listOf("primary" to b.primary, "secondary" to b.secondary).firstOrNull { it.second.isNotEmpty() && !Branding.isColour(it.second) }
                if (bad != null) {
                    error = "The ${bad.first} colour: #rrggbb, like #2563eb."
                } else {
                    error = null
                    try {
                        b = session.saveBranding(b.copy(primary = b.primary.lowercase(), secondary = b.secondary.lowercase()))
                        saved = true
                    } catch (e: ActionException) {
                        error = e.shown()
                    }
                }
            },
        )
    }
}

/** The form itself (rendered alone in screenshot tests). */
@Composable
fun BrandingForm(
    b: Branding,
    onChange: (Branding) -> Unit,
    error: String?,
    saved: Boolean,
    logoBusy: BusyState,
    coverBusy: BusyState,
    onLogo: suspend () -> Unit,
    onCover: suspend () -> Unit,
    onSave: suspend () -> Unit,
) {
    val c = Belfry.colors
    Hint("Shown in the chat, in the mails and in any Nostr app: they are kept in the profile of the belfry’s own Nostr account.")
    SectionTitle("Logo")
    PictureRow(b.logo, 56.dp, 56.dp, "The logo", logoBusy, onLogo) { onChange(b.copy(logo = "")) }
    SectionTitle("Cover image")
    PictureRow(b.cover, 120.dp, 56.dp, "The cover", coverBusy, onCover) { onChange(b.copy(cover = "")) }
    Spacer(Modifier.height(16.dp))
    ColourField("Primary colour", "Buttons, links in mails, the bar next to mentions", b.primary, "brand-primary") { onChange(b.copy(primary = it)) }
    Spacer(Modifier.height(14.dp))
    ColourField("Secondary colour", "Badges and highlights", b.secondary, "brand-secondary") { onChange(b.copy(secondary = it)) }
    val p = brandColour(b.primary)
    val s = brandColour(b.secondary)
    if (p != null || s != null) {
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = "Preview" }) {
            if (p != null) PreviewChip("Primary", p)
            if (s != null) PreviewChip("Secondary", s)
        }
    }
    ErrorLine(error)
    if (saved) Hint("Saved. Everyone sees it the next time their app checks in.", Modifier.padding(top = 10.dp))
    Spacer(Modifier.height(16.dp))
    BusyButton("Save", onClick = onSave, modifier = Modifier.fillMaxWidth().testTag("brand-save"), enabled = !logoBusy.busy && !coverBusy.busy)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun PictureRow(url: String, w: Dp, h: Dp, what: String, busy: BusyState, onPick: suspend () -> Unit, onRemove: () -> Unit) {
    val c = Belfry.colors
    val shape = RoundedCornerShape(8.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(w, h).clip(shape).background(c.surface2).border(1.dp, c.line, shape)) {
            if (url.startsWith("https://") && LocalNetworkImages.current) {
                AsyncImage(model = url, contentDescription = what, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        BusyButton(if (busy.busy) "Uploading…" else if (url.isEmpty()) "Upload" else "Change", onClick = onPick, kind = ButtonKind.Ghost, small = true, busy = busy)
        if (url.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            BusyButton("Remove", onClick = { onRemove() }, kind = ButtonKind.Ghost, small = true, enabled = !busy.busy)
        }
    }
}

private val PRESETS = listOf("#2563eb", "#7c3aed", "#db2777", "#dc2626", "#ea580c", "#16a34a", "#0d9488", "#09090b")

/** A colour as `#rrggbb`, typed or picked from a few swatches (the web uses the browser's colour picker). */
@Composable
private fun ColourField(label: String, hint: String, value: String, tag: String, onValue: (String) -> Unit) {
    val c = Belfry.colors
    val colour = brandColour(value)
    BelfryTextField(
        value, { onValue(it.trim().take(7)) }, label = label, placeholder = "#2563eb", hint = hint,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.testTag(tag),
        trailing = { Box(Modifier.size(22.dp).clip(CircleShape).background(colour ?: c.surface2).border(1.dp, c.line, CircleShape)) },
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (hex in PRESETS) {
            val on = value.equals(hex, ignoreCase = true)
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(brandColour(hex) ?: Color.Gray)
                    .border(if (on) 2.dp else 1.dp, if (on) c.text else c.line, CircleShape)
                    .clickable(role = Role.RadioButton, onClickLabel = "$label $hex") { onValue(hex) },
            )
        }
        if (value.isNotEmpty()) {
            Text(
                "Clear", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.muted,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { onValue("") }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PreviewChip(text: String, colour: Color) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(colour).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = onColour(colour))
    }
}
