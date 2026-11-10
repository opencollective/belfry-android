package sh.belfry.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.theme.Belfry

/**
 * The web's `.field` + `.input`: a small muted label above, a bordered box (the border takes the
 * text colour when focused, the danger colour with an error), an error line below.
 */
@Composable
fun BelfryTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    hint: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 8,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Belfry.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Belfry.buttonRadius)
    val border = when {
        error != null -> c.danger
        focused -> c.text
        else -> c.line
    }
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, fontSize = 13.sp, color = c.muted)
            Spacer(Modifier.height(6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = if (enabled) c.text else c.muted),
            cursorBrush = SolidColor(c.text),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(c.bg)
                        .border(if (focused || error != null) 1.5.dp else 1.dp, border, shape)
                        .defaultMinSize(minHeight = 46.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, fontSize = 16.sp, lineHeight = 22.sp, color = c.faint, maxLines = if (singleLine) 1 else 3)
                        }
                        inner()
                    }
                    if (trailing != null) {
                        Spacer(Modifier.width(8.dp))
                        trailing()
                    }
                }
            },
        )
        val below = error ?: hint
        if (below != null) {
            Spacer(Modifier.height(6.dp))
            Text(below, fontSize = 13.sp, lineHeight = 17.sp, color = if (error != null) c.danger else c.muted)
        }
    }
}
