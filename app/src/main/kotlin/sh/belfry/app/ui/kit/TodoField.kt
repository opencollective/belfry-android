package sh.belfry.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.TodoLimit

/**
 * A to-do's text field (canvas todos/new-todo-limit): the text stays as typed, never cut; from
 * 250 "n of 300" under it; past 300 the extra is tinted red and the line under it says by how
 * much. The caller turns Add off with [TodoLimit.fits].
 */
@Composable
fun TodoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    maxLines: Int = 8,
) {
    val c = Belfry.colors
    val tint = remember(c.danger) { OverLimit(c.danger, c.danger.copy(alpha = 0.14f)) }
    BelfryTextField(
        value, onValueChange, modifier,
        label = label, placeholder = placeholder,
        error = TodoLimit.error(value),
        counter = TodoLimit.counter(value),
        singleLine = false, maxLines = maxLines,
        visualTransformation = tint,
    )
}

/** Tints what is past the limit; the text itself is untouched (identity offsets). */
private class OverLimit(private val fg: Color, private val bg: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val from = TodoLimit.overFrom(text.text) ?: return TransformedText(text, OffsetMapping.Identity)
        val out = buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(color = fg, background = bg), from.coerceAtMost(text.length), text.length)
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
    override fun equals(other: Any?) = other is OverLimit && other.fg == fg && other.bg == bg
    override fun hashCode() = fg.hashCode() * 31 + bg.hashCode()
}
