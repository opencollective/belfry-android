package sh.belfry.app.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.theme.Belfry

/**
 * The one-shape header (docs/one-shape.md 2; canvas one-shape/anatomy), the same for a channel,
 * a group, a DM, a thread, and later the event, to-do and e-mail screens: two lines at most, the
 * [title] and the [items] row ([IconRow]: never clips). [navigation] is ☰ on a top-level screen,
 * ‹ on a pushed one; [actions] stays empty except a channel's bell. [quick] holds the kind's quick
 * actions under the row (Going / Maybe, Reply by e-mail, …), one row at most.
 *
 * [onPull] (a tap on the title or a drag down) opens the level under it, drawn by [OnePulled] over
 * the screen's content; [onPush] (a drag up) closes it. [pulled] says which, for screen readers.
 */
@Composable
fun OneHeader(
    title: String,
    items: List<RowItem>,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onPull: (() -> Unit)? = null,
    onPush: (() -> Unit)? = null,
    pulled: Boolean = false,
    /** What a tap on the title says it does ("Open the channel summary"); null = [pulled]'s words. */
    pullLabel: String? = null,
    titleLoading: Boolean = false,
    titleMark: (@Composable () -> Unit)? = null,
    quick: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Belfry.colors
    Column(modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).testTag("one-header")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (navigation != null) navigation() else Spacer(Modifier.width(12.dp))
            val drag = if (onPull != null && onPush != null) Modifier.pointerInput(onPull, onPush) {
                var moved = 0f
                detectVerticalDragGestures(onDragStart = { moved = 0f }, onDragEnd = {
                    if (moved > 24f) onPull() else if (moved < -24f) onPush?.invoke()
                }) { _, dy -> moved += dy }
            } else Modifier
            val click = if (onPull != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(
                role = Role.Button,
                onClickLabel = pullLabel ?: if (pulled) "Fold the header" else "Pull the header down",
                onClick = { if (pulled && onPush != null) onPush() else onPull() },
            ) else Modifier
            Column(Modifier.weight(1f).then(drag).then(click).padding(horizontal = 4.dp, vertical = 6.dp).testTag("one-header-title")) {
                if (titleLoading) {
                    NameSkeleton(Modifier.padding(vertical = 4.dp), fraction = 0.45f, height = 16.dp)
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                    )
                    titleMark?.invoke()
                }
                if (items.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    IconRow(items, Modifier.fillMaxWidth())
                }
            }
            actions()
        }
        if (quick != null) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { quick() }
        Hairline()
    }
}

/**
 * The header pulled down, drawn over the screen's content (put it last in a [Box] that holds the
 * content): a dimmed backdrop that closes it, [content] (scrolls itself), and a handle that closes
 * on a tap or a drag up. Test tags `pull-open` and `pull-handle`.
 */
@Composable
fun BoxScope.OnePulled(open: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Belfry.colors
    AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Fold the header") { onClose() },
        )
    }
    AnimatedVisibility(visible = open, enter = expandVertically(), exit = shrinkVertically()) {
        Column(Modifier.fillMaxWidth().background(c.bg).testTag("pull-open")) {
            Column(Modifier.weight(1f, fill = false)) { content() }
            Box(
                Modifier.fillMaxWidth().height(24.dp)
                    .pointerInput(Unit) {
                        var moved = 0f
                        detectVerticalDragGestures(onDragStart = { moved = 0f }, onDragEnd = { if (moved < -16f) onClose() }) { _, dy -> moved += dy }
                    }
                    .clickable(role = Role.Button, onClickLabel = "Fold the header") { onClose() }
                    .testTag("pull-handle"),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) }
            Hairline()
        }
    }
}
