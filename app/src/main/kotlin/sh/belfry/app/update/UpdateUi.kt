package sh.belfry.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.BuildConfig
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.IconAction
import sh.belfry.app.ui.kit.SwitchRow
import sh.belfry.app.ui.theme.Belfry

/** The fixed words (nothing from a release's text is shown, only its version number). */
object UpdateText {
    fun available(v: String) = "Belfry $v is available."
    fun downloading(v: String) = "Downloading Belfry $v…"
    const val INSTALLING = "Opening the installer…"
}

/** A slim line at the top of Home when a newer version is out: Update, or close it for this version. */
@Composable
fun UpdateBar() {
    val context = LocalContext.current
    val r by Updater.available.collectAsState()
    val state by Updater.state.collectAsState()
    var hidden by remember { mutableStateOf(Updater.hiddenFor(context)) }
    val release = r ?: return
    if (hidden == release.version && state == Updater.State.Idle) return
    val c = Belfry.colors
    Column(Modifier.fillMaxWidth().background(c.surface).testTag("update-bar")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val line = when (val s = state) {
                Updater.State.Downloading -> UpdateText.downloading(release.version)
                Updater.State.Installing -> UpdateText.INSTALLING
                is Updater.State.Failed -> s.message
                Updater.State.Idle -> UpdateText.available(release.version)
            }
            Text(line, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            BusyButton("Update", onClick = { Updater.install(context, release) }, small = true, enabled = state !is Updater.State.Downloading && state !is Updater.State.Installing)
            IconAction(BelfryIcons.Close, "Not now", { Updater.hide(context, release.version); hidden = release.version })
        }
        Hairline()
    }
}

/** Me → App: the version, whether a newer one is out, and the automatic check. */
@Composable
fun UpdateRows() {
    val context = LocalContext.current
    val r by Updater.available.collectAsState()
    val state by Updater.state.collectAsState()
    var auto by remember { mutableStateOf(Updater.auto(context)) }
    var checked by remember { mutableStateOf(false) }
    if (!Updater.enabled) return
    val release = r
    ActionRow(
        "Updates", onClick = null, icon = BelfryIcons.Retry,
        sub = when {
            state is Updater.State.Failed -> (state as Updater.State.Failed).message
            release != null -> UpdateText.available(release.version)
            checked -> "You have the latest version (${BuildConfig.VERSION_NAME})."
            else -> "From github.com/opencollective/belfry-android"
        },
        modifier = Modifier.testTag("me-updates"),
        trailing = {
            if (release != null) BusyButton("Update", onClick = { Updater.install(context, release) }, small = true)
            else BusyButton("Check", onClick = { Updater.check(context, force = true); checked = true }, small = true, kind = ButtonKind.Ghost)
        },
    )
    SwitchRow(
        "Check for updates", auto, { on -> auto = on; Updater.setAuto(context, on) },
        sub = "When the app opens, at most once an hour",
    )
}
