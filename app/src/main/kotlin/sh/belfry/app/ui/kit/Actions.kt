package sh.belfry.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.theme.Belfry

/**
 * A Discord-style grouped list for menus and sheets: one rounded block, hairlines between rows.
 * Put [ActionRow], [BusyRow] and [SwitchRow] inside. Never stack bordered buttons instead.
 */
@Composable
fun ActionGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Belfry.colors.line),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        content = content,
    )
}

@Composable
private fun RowFrame(
    label: String,
    icon: ImageVector?,
    sub: String?,
    danger: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Belfry.colors
    val textColor = if (danger) Color(0xFFDC2626) else c.text
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface)
            .defaultMinSize(minHeight = 50.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = (if (danger) textColor else c.muted).copy(alpha = alpha), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = textColor.copy(alpha = alpha), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) {
                Text(sub, fontSize = 13.sp, lineHeight = 17.sp, color = c.muted.copy(alpha = alpha), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/**
 * One row of an [ActionGroup]. For a row that only navigates or opens something (instant).
 * A row that does work uses [BusyRow].
 */
@Composable
fun ActionRow(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    sub: String? = null,
    value: String? = null,
    danger: Boolean = false,
    chevron: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Belfry.colors
    val click = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    val tail: (@Composable () -> Unit)? = if (trailing != null || value != null || chevron) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(value, fontSize = 14.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                }
                trailing?.invoke()
                if (chevron) {
                    Spacer(Modifier.width(4.dp))
                    Icon(BelfryIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
                }
            }
        }
    } else {
        null
    }
    RowFrame(label, icon, sub, danger, enabled, modifier.then(click), trailing = tail)
}

/** A row that does work: locks on tap, its icon becomes a spinner until the work ends. */
@Composable
fun BusyRow(
    label: String,
    onClick: suspend () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    sub: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    busy: BusyState = rememberBusy(),
    onError: (Throwable) -> Unit = {},
) {
    val on = enabled && !busy.busy
    val click = Modifier.clickable(enabled = on, role = Role.Button) { busy.run(onError, onClick) }
    if (busy.busy) {
        RowFrame(label, null, sub, danger, false, modifier.then(click), leading = { Spinner(size = 18.dp) })
    } else {
        RowFrame(label, icon, sub, danger, enabled, modifier.then(click))
    }
}

/** A setting with a switch on the right. Pass `busy = true` while the change is being saved. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    sub: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val c = Belfry.colors
    val on = enabled && !busy
    RowFrame(
        label, icon, sub, false, enabled,
        modifier.clickable(enabled = on, role = Role.Switch) { onCheckedChange(!checked) },
        trailing = {
            if (busy) {
                Spinner(size = 18.dp)
            } else {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = c.onAccent,
                        checkedTrackColor = c.accent,
                        uncheckedThumbColor = c.muted,
                        uncheckedTrackColor = c.surface2,
                        uncheckedBorderColor = c.surface2,
                    ),
                )
            }
        },
    )
}
