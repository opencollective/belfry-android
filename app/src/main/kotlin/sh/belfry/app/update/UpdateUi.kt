package sh.belfry.app.update

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.BuildConfig
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfrySheet
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
    fun button(s: Updater.State) = if ((s as? Updater.State.Failed)?.web == true) "belfry.sh" else "Update"

    // The sheet before Android's "Install unknown apps" setting (OSV, 2026-11-21)
    const val ASK_TITLE = "Let Belfry install its update"
    const val ASK_LINE = "Android asks this once. On the next screen, turn on \u201cAllow from this source\u201d, then come back here: the update goes on by itself."
    const val AGAIN_TITLE = "Belfry can't install it yet"
    const val AGAIN_LINE = "The setting is still off. If your phone showed another screen, download the update from belfry.sh instead."
    const val OPEN_SETTING = "Open the setting"
    const val OPEN_SETTING_AGAIN = "Open the setting again"
    const val WEB = "Download from belfry.sh"
    const val WEB_SUB = "belfry.sh/android, in your browser"
}

/** Once, in AppRoot: the sheet [Updater.tap] asks for before sending the person to the setting. */
@Composable
fun UpdateSheetHost() {
    val context = LocalContext.current
    val ask by Updater.asking.collectAsState()
    val a = ask ?: return
    BelfrySheet(onDismiss = { Updater.dismissAsk() }) {
        UpdateAskContent(a.again, onSetting = { Updater.openSetting(context, a.release) }, onWeb = { Updater.openWeb(context) })
    }
}

/** The sheet's body: one line on why, then the setting and belfry.sh (belfry.sh first once the setting failed). */
@Composable
fun UpdateAskContent(again: Boolean, onSetting: () -> Unit, onWeb: () -> Unit) {
    val c = Belfry.colors
    Column(Modifier.fillMaxWidth().testTag("update-ask")) {
        Text(if (again) UpdateText.AGAIN_TITLE else UpdateText.ASK_TITLE, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text(if (again) UpdateText.AGAIN_LINE else UpdateText.ASK_LINE, fontSize = 15.sp, lineHeight = 21.sp, color = c.muted)
        Spacer(Modifier.height(16.dp))
        val setting: @Composable () -> Unit = {
            ActionRow(if (again) UpdateText.OPEN_SETTING_AGAIN else UpdateText.OPEN_SETTING, onClick = onSetting, icon = BelfryIcons.Settings, modifier = Modifier.testTag("update-ask-setting"))
        }
        val web: @Composable () -> Unit = {
            ActionRow(UpdateText.WEB, onClick = onWeb, icon = BelfryIcons.Globe, sub = UpdateText.WEB_SUB, modifier = Modifier.testTag("update-ask-web"))
        }
        ActionGroup { if (again) { web(); setting() } else { setting(); web() } }
    }
}

/** A slim line at the top of Home when a newer version is out: Update, or close it for this version. */
@Composable
fun UpdateBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val r by Updater.available.collectAsState()
    val state by Updater.state.collectAsState()
    val hidden = UpdateBarState.hidden(context)
    val release = r ?: return
    if (hidden == release.version && state == Updater.State.Idle) return
    val c = Belfry.colors
    // edge to edge: the bar is the top of the window, so it starts below the status bar and the
    // camera cutout (Xavier on a Pixel, 2026-11-24); the screen under it is told (consumeWindowInsets)
    Column(modifier.fillMaxWidth().background(c.surface).windowInsetsPadding(UpdateInsets.top).testTag("update-bar")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val line = when (val s = state) {
                Updater.State.Downloading -> UpdateText.downloading(release.version)
                Updater.State.Installing -> UpdateText.INSTALLING
                is Updater.State.Failed -> s.message
                Updater.State.Idle -> UpdateText.available(release.version)
            }
            Text(line, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            BusyButton(UpdateText.button(state), onClick = { Updater.tap(context, release) }, small = true, enabled = state !is Updater.State.Downloading && state !is Updater.State.Installing, modifier = Modifier.testTag("update-button"))
            IconAction(BelfryIcons.Close, "Not now", { Updater.hide(context, release.version); UpdateBarState.hiddenNow.value = release.version })
        }
        Hairline()
    }
}

/** The top of the window a bar drawn there must keep clear of: the status bar and a display cutout. */
object UpdateInsets {
    val top: WindowInsets
        @Composable get() = WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
}

/**
 * [UpdateBar] at the top of the window and CONTENT under it. The bar keeps clear of the status bar and
 * the cutout; when it shows, the content is told that space is taken, so its own top bar doesn't pad again.
 */
@Composable
fun UnderUpdateBar(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        val bar = updateBarShown()
        UpdateBar()
        val top = UpdateInsets.top
        Box(Modifier.weight(1f).then(if (bar) Modifier.consumeWindowInsets(top) else Modifier)) { content() }
    }
}

/** Whether [UpdateBar] shows now (so the screen under it knows the top is taken). */
@Composable
fun updateBarShown(): Boolean {
    val context = LocalContext.current
    val r by Updater.available.collectAsState()
    val state by Updater.state.collectAsState()
    val release = r ?: return false
    return !(UpdateBarState.hidden(context) == release.version && state == Updater.State.Idle)
}

/** The version whose bar was closed (✕), shared by the bar and the screen under it, so both change at once. */
object UpdateBarState {
    internal val hiddenNow = mutableStateOf<String?>(null)
    private var read = false
    fun hidden(context: android.content.Context): String? {
        if (!read) { read = true; if (hiddenNow.value == null) hiddenNow.value = Updater.hiddenFor(context) }
        return hiddenNow.value
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
            if (release != null) BusyButton(UpdateText.button(state), onClick = { Updater.tap(context, release) }, small = true)
            else BusyButton("Check", onClick = { Updater.check(context, force = true); checked = true }, small = true, kind = ButtonKind.Ghost)
        },
    )
    SwitchRow(
        "Check for updates", auto, { on -> auto = on; Updater.setAuto(context, on) },
        sub = "When the app opens, at most once an hour",
    )
}
