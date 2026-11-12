package sh.belfry.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.theme.Belfry

/**
 * The bar on top of a screen: an optional navigation slot ([BackButton], or the ☰ on the feed),
 * title + optional subtitle (tappable when [onTitleClick] is given), actions on the right.
 * It pads itself below the status bar. A back arrow calls onBack (pop): it never pushes.
 */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Belfry.colors
    Column(modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (navigation != null) navigation() else Spacer(Modifier.width(12.dp))
            val click = if (onTitleClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onTitleClick) else Modifier
            Column(Modifier.weight(1f).then(click).padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, fontSize = 12.sp, lineHeight = 15.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            actions()
        }
        Hairline()
    }
}

@Composable
fun BackButton(onBack: () -> Unit) = IconAction(BelfryIcons.Back, "Back", onBack)

/**
 * An instant icon button for bars (navigation, open search…), with an optional count badge.
 *
 * The badge is a sibling of the button, not its content: Material's IconButton clips what is in
 * it to its 40 dp circle, which cut the badge's corner (and more of it at a large font size).
 * It sits inside the button's own 48 dp square, so nothing around can cut it either.
 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    tint: Color = Belfry.colors.text,
    enabled: Boolean = true,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
        }
        if (badge > 0) {
            CountBadge(badge, Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 1.dp).testTag("icon-badge"), small = true, max = "99+")
        }
    }
}

/**
 * A count in a circle (unread…). It grows with the text: a circle for one digit at any font
 * size (never narrower than it is tall), a pill for more. Never give it a fixed size.
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    max: String = "99",
    color: Color = Belfry.colors.badge,
    onColor: Color = Belfry.colors.onBadge,
) {
    val min = if (small) 16.dp else 22.dp
    Box(
        modifier
            .clip(CircleShape)
            .background(color)
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                val w = maxOf(p.width, p.height)
                layout(w, p.height) { p.place((w - p.width) / 2, 0) }
            }
            .defaultMinSize(minWidth = min, minHeight = min)
            .padding(horizontal = if (small) 4.dp else 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) max else count.toString(),
            fontSize = if (small) 10.sp else 12.sp, lineHeight = if (small) 12.sp else 14.sp,
            fontWeight = FontWeight.SemiBold, color = onColor, maxLines = 1, softWrap = false,
        )
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Belfry.colors.line))
}

/** Underline tabs (channel info: About · Members · Files…). Equal widths: never scrolls sideways. */
@Composable
fun Tabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, t ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).clickable(role = Role.Tab) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        t, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 11.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) c.text else Color.Transparent))
                }
            }
        }
        Hairline()
    }
}

/** The web's `.seg`: a small set of exclusive options ("one person / up to 50"). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Belfry.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) c.surface2 else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(i) }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    o, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A pill: a topic, a tag, a reaction count. `selected` inverts it. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Belfry.colors
    val fg = if (selected) c.onAccent else c.text
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier.clip(CircleShape).background(if (selected) c.accent else c.surface2).then(click).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small muted heading above a group or list, with an optional action on the right. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Belfry.colors.muted, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Belfry.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.faint, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
        if (!text.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = c.muted, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

enum class BannerKind { Info, Warning, Error }

/** A strip under the top bar: "Waiting for connection…", "Not sent", "You're a guest"… */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    kind: BannerKind = BannerKind.Info,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Belfry.colors
    val fg = when (kind) {
        BannerKind.Info -> c.muted
        BannerKind.Warning -> c.orange
        BannerKind.Error -> c.danger
    }
    Row(
        modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 13.sp, lineHeight = 17.sp, color = fg, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** line · date · line */
@Composable
fun DaySeparator(label: String, modifier: Modifier = Modifier) {
    val c = Belfry.colors
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.muted, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
    }
}
