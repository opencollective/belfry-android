package sh.belfry.app.ui.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.OneShape

/**
 * One item of a header's icon row (docs/one-shape.md 2): a one-colour icon and a short text
 * ("3", "From Lou", "Thu 4 Dec · 7 PM"). [key] is its kind for the drop order
 * ([OneShape.DROP_ORDER]: owner, files, todos, photos, comments, pinned; "lead" and "people" stay);
 * [spoken] is the full label for screen readers ("3 open to-dos"). Leave an item out at zero.
 */
data class RowItem(
    val key: String,
    val icon: ImageVector?,
    val text: String,
    val spoken: String,
    val lead: Boolean = false,
)

/** The lead item's colour: blue on both themes, also set in semi-bold (e-ink: weight carries it, not hue). */
@Composable
fun leadColor(): Color = if (Belfry.colors.isDark) Color(0xFF7CB4FF) else Color(0xFF1D5FD1)

/**
 * The header's second line: items in one row that never clips. When they don't fit, items leave by
 * [OneShape.fitRow]'s order; the rest keep their places.
 */
@Composable
fun IconRow(items: List<RowItem>, modifier: Modifier = Modifier, color: Color = Belfry.colors.muted) {
    if (items.isEmpty()) return
    val gapPx = 10
    val lead = leadColor()
    Layout(
        modifier = modifier.testTag("icon-row"),
        content = {
            for (it in items) {
                val tint = if (it.lead) lead else color
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("row-item-${it.key}").clearAndSetSemantics { contentDescription = it.spoken },
                ) {
                    if (it.icon != null) {
                        Icon(it.icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
                        if (it.text.isNotEmpty()) Spacer(Modifier.width(3.dp))
                    }
                    if (it.text.isNotEmpty()) Text(
                        it.text, color = tint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Clip,
                        fontWeight = if (it.lead) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val gap = (gapPx * density).toInt()
        val free = constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity)
        val placeables = measurables.map { it.measure(free) }
        val indexed = placeables.indices.toList()
        val budget = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val kept = OneShape.fitRow(indexed, { items[it].key }, { placeables[it].width }, gap, budget).toMutableList()
        // beyond the shared order (a kind it doesn't name, like a channel's 📅): the last such item
        // leaves next, so nothing is ever drawn past the row's end; lead and people still stay
        fun total() = kept.sumOf { placeables[it].width } + gap * (kept.size - 1).coerceAtLeast(0)
        while (total() > budget) {
            val i = kept.indexOfLast { items[it].key != "lead" && items[it].key != "people" }
            if (i < 0) break
            kept.removeAt(i)
        }
        val height = kept.maxOfOrNull { placeables[it].height } ?: 0
        val width = (kept.sumOf { placeables[it].width } + gap * (kept.size - 1).coerceAtLeast(0)).coerceAtMost(budget)
        layout(width, height) {
            var x = 0
            for (i in kept) {
                val p = placeables[i]
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}
