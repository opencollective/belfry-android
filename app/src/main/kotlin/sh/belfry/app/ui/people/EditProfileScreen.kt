package sh.belfry.app.ui.people

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.belfry.app.ui.chat.Photos
import sh.belfry.app.ui.chat.face
import sh.belfry.app.ui.kit.Avatar
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.rememberBusy
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.nav.LocalWorld
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.Place
import sh.belfry.core.model.Share
import java.io.ByteArrayOutputStream

/** A profile picture from the system photo picker: at most 512 px, JPEG, read off the main thread. */
internal object AvatarPhoto {
    const val MAX_SIDE = 512

    suspend fun load(context: Context, uri: Uri): ByteArray? {
        val picked = Photos.load(context, uri, 0) ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val full = BitmapFactory.decodeByteArray(picked.attachment.bytes, 0, picked.attachment.bytes.size) ?: return@withContext null
                val scale = MAX_SIDE.toFloat() / maxOf(full.width, full.height)
                val small = if (scale < 1f) {
                    Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
                } else {
                    full
                }
                val out = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }
}

/**
 * Edit your profile: picture, name, username (your address here), about, tags (suggested from
 * every member's tags) and where you are (a listed city, one you add, the country only, or
 * nothing). Never the device's location.
 */
@Composable
fun EditProfileScreen(onClose: () -> Unit) {
    val c = Belfry.colors
    val world = LocalWorld.current
    val session = LocalSession.current
    val context = LocalContext.current
    val me = world.me
    val domain = session.community.domain
    val p0 = remember { world.profiles[me] }
    val x0 = remember { world.extras[me] }
    val nip05 = p0?.nip05 ?: ""
    val own = nip05.lowercase().endsWith("@" + domain.lowercase())
    val user0 = if (own) nip05.substringBeforeLast('@').lowercase() else ""

    var name by remember { mutableStateOf(p0?.name ?: "") }
    var user by remember { mutableStateOf(user0) }
    var about by remember { mutableStateOf(world.aboutOf(me)) }
    val tags = remember { mutableStateListOf<String>().apply { addAll(x0?.tags ?: emptyList()) } }
    var share by remember { mutableStateOf(x0?.share ?: Share.CITY) }
    var place by remember { mutableStateOf<Place?>(x0?.place) }
    var error by remember { mutableStateOf<String?>(null) }
    var picError by remember { mutableStateOf<String?>(null) }
    val picBusy = rememberBusy()

    BackHandler { onClose() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            picError = null
            picBusy.run(onError = { picError = it.shown("The picture was not saved. Try again.") }) {
                val bytes = AvatarPhoto.load(context, uri) ?: throw ActionException("photo", "This picture could not be read.")
                session.setAvatar(bytes)
            }
        }
    }

    Page("Edit your profile", onBack = onClose) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(world.face(me), size = 56.dp)
            Spacer(Modifier.width(12.dp))
            BusyButton(
                if (picBusy.busy) "Uploading…" else "Change picture",
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                kind = ButtonKind.Ghost, small = true, busy = picBusy,
            )
        }
        ErrorLine(picError)
        Spacer(Modifier.height(14.dp))
        BelfryTextField(
            name, { name = it.take(100) }, label = "Name",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.testTag("edit-name"),
        )
        if (own) {
            Spacer(Modifier.height(14.dp))
            BelfryTextField(
                user, { v -> user = v.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }.take(30) },
                label = "Username · your address here",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                trailing = { Text("@$domain", fontSize = 15.sp, color = c.muted, maxLines = 1) },
            )
        }
        Spacer(Modifier.height(14.dp))
        BelfryTextField(
            about, { about = it.take(2000) }, label = "About you · Markdown works", singleLine = false, minLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Spacer(Modifier.height(14.dp))
        TagsField(tags)
        Spacer(Modifier.height(4.dp))
        PlaceField(share, { share = it }, place, { place = it })
        ErrorLine(error)
        Spacer(Modifier.height(18.dp))
        BusyButton(
            "Save",
            onClick = {
                error = null
                try {
                    val wanted = user.trim().lowercase()
                    val address = if (own && wanted.isNotEmpty() && wanted != user0) session.setUsername(wanted) else null
                    if (address != null || name.trim() != (p0?.name ?: "") || about.trim() != world.aboutOf(me)) {
                        session.saveProfile(name.trim(), about.trim(), address)
                    }
                    session.saveExtras(tags.toList(), share, place)
                    onClose()
                } catch (e: ActionException) {
                    error = e.shown()
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("edit-save"),
        )
    }
}
