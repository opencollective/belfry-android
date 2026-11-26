package sh.belfry.app.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import sh.belfry.app.ui.theme.Belfry

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

/**
 * A small copy icon (SPEC 19.93; Event form canvas evview-mid, evview-qr; web 1d5d814): copies [text], and for a
 * moment says "Copied" in words where the icon was (e-ink: the word says it, not a colour). 48 dp to touch.
 */
@Composable
fun CopyIconButton(text: String, title: String, modifier: Modifier = Modifier, label: String = "Copy the link") {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_500); copied = false } }
    Box(
        modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = label) {
                copyText(context, text, title)
                try { haptics.performHapticFeedback(HapticFeedbackType.LongPress) } catch (e: Exception) { }
                copied = true
            }
            .semantics { contentDescription = if (copied) "Copied" else label },
        contentAlignment = Alignment.Center,
    ) {
        if (copied) Text("Copied", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text, modifier = Modifier.padding(horizontal = 8.dp))
        else Icon(BelfryIcons.Copy, contentDescription = null, tint = Belfry.colors.text, modifier = Modifier.size(22.dp))
    }
}
