package sh.belfry.app.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.belfry.app.ui.theme.Belfry

/**
 * The owner's rule: a button disables itself the moment it is tapped and shows progress until
 * its work is done. [run] flips [busy] synchronously (so a second tap in the same frame is
 * swallowed), runs the work, and frees the button when it ends, also when it throws.
 */
@Stable
class BusyState internal constructor(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set

    /** Returns false (and does nothing) when work is already running. */
    fun run(onError: (Throwable) -> Unit = {}, block: suspend () -> Unit): Boolean {
        if (busy) return false
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                busy = false
            }
        }
        return true
    }
}

/** For custom controls. Share one state between controls that must lock together. */
@Composable
fun rememberBusy(): BusyState {
    val scope = rememberCoroutineScope()
    return remember(scope) { BusyState(scope) }
}

enum class ButtonKind { Primary, Ghost, Danger }

/**
 * The app's button. `onClick` is the whole work (suspend): the button is disabled and shows a
 * spinner until it returns. Full width by default like the web's `.btn`; `small = true` wraps
 * its text.
 */
@Composable
fun BusyButton(
    text: String,
    onClick: suspend () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    small: Boolean = false,
    busy: BusyState = rememberBusy(),
    onError: (Throwable) -> Unit = {},
) {
    val c = Belfry.colors
    val (container, content) = when (kind) {
        ButtonKind.Primary -> c.accent to c.onAccent
        ButtonKind.Ghost -> c.bg to c.text
        ButtonKind.Danger -> Color(0xFFDC2626) to Color.White
    }
    Button(
        onClick = { busy.run(onError, onClick) },
        enabled = enabled && !busy.busy,
        modifier = modifier.defaultMinSize(minHeight = if (small) 36.dp else 46.dp),
        shape = RoundedCornerShape(Belfry.buttonRadius),
        border = if (kind == ButtonKind.Ghost) BorderStroke(1.dp, c.line) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = if (kind == ButtonKind.Ghost) container else container.copy(alpha = 0.45f),
            disabledContentColor = if (kind == ButtonKind.Ghost) content.copy(alpha = 0.45f) else content,
        ),
        contentPadding = if (small) PaddingValues(horizontal = 16.dp, vertical = 8.dp) else PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        elevation = null,
    ) {
        if (busy.busy) {
            Spinner(size = 16.dp, color = content)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = if (small) 14.sp else 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** An icon button that does work: the icon becomes a spinner while it runs. */
@Composable
fun BusyIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: suspend () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Belfry.colors.text,
    busy: BusyState = rememberBusy(),
    onError: (Throwable) -> Unit = {},
) {
    IconButton(onClick = { busy.run(onError, onClick) }, enabled = enabled && !busy.busy, modifier = modifier) {
        if (busy.busy) {
            Spinner(size = 18.dp, color = tint)
        } else {
            Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = Belfry.colors.muted) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(size), color = color, strokeWidth = 2.dp)
    }
}
