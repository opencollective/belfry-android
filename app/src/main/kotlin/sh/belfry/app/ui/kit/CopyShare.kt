package sh.belfry.app.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The pair's height (canvas v113 events/event-full-address: Xavier found 48 short). */
val COPY_SHARE_HEIGHT = 50.dp

/**
 * Copy link · Share… side by side, equal widths, 50 dp (canvas v113 events/event-full-address and
 * -copied; the invite sheets too). Copy link copies, buzzes, and reads "Copied" with a check for
 * 2 s; Share… opens the system share sheet with [title].
 */
@Composable
fun CopyShareButtons(
    url: String, title: String, modifier: Modifier = Modifier, enabled: Boolean = true, copyTag: String = "link-copy", shareTag: String = "link-share",
    copyLabel: String = "Copy link", shareLabel: String = "Share…",
    /** More buttons on the same row (an event's QR): give each `weight(1f)` and [COPY_SHARE_HEIGHT]. */
    more: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2_000); copied = false } }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BusyButton(
            if (copied) "Copied" else copyLabel,
            onClick = {
                copyText(context, url, title)
                try { haptics.performHapticFeedback(HapticFeedbackType.LongPress) } catch (e: Exception) { }
                copied = true
            },
            modifier = Modifier.weight(1f).height(COPY_SHARE_HEIGHT).testTag(copyTag), kind = ButtonKind.Ghost, enabled = enabled,
            icon = if (copied) BelfryIcons.Check else BelfryIcons.Copy,
        )
        BusyButton(
            shareLabel, onClick = { shareText(context, url, title) },
            modifier = Modifier.weight(1f).height(COPY_SHARE_HEIGHT).testTag(shareTag), kind = ButtonKind.Ghost, enabled = enabled, icon = BelfryIcons.Share,
        )
        more?.invoke(this)
    }
}
