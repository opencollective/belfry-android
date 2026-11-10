package sh.belfry.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.theme.Belfry

/**
 * The app's bottom sheet (message menu, invite, confirmations…): a Material 3 ModalBottomSheet
 * with the Belfry colours, the web's grab handle and 18 dp top corners. Content scrolls
 * vertically when it is taller than the screen; back and a tap outside dismiss it.
 * The body is [SheetContent], which tests can render on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BelfrySheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Belfry.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        containerColor = c.bg,
        contentColor = c.text,
        tonalElevation = 0.dp,
        scrimColor = Color(0x6609090B),
        dragHandle = { SheetGrab() },
    ) {
        SheetContent(title = title, content = content)
    }
}

@Composable
fun SheetGrab() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(Belfry.colors.surface2))
    }
}

/** What goes inside a [BelfrySheet]: optional title, then the content with the sheet's padding. */
@Composable
fun SheetContent(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        if (title != null) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Belfry.colors.text, modifier = Modifier.padding(bottom = 12.dp))
        }
        content()
    }
}

/**
 * Asks before something that cannot be undone, in the app (never a system dialog), and can show
 * what it is about (e.g. the message about to be deleted) in [preview].
 */
@Composable
fun ConfirmSheet(
    title: String,
    confirmLabel: String,
    onConfirm: suspend () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    danger: Boolean = true,
    cancelLabel: String = "Cancel",
    preview: (@Composable () -> Unit)? = null,
) {
    BelfrySheet(onDismiss = onDismiss) {
        ConfirmContent(title, confirmLabel, onConfirm, onDismiss, message, danger, cancelLabel, preview)
    }
}

/** The body of a [ConfirmSheet]. After [onConfirm] returns, the caller closes the sheet. */
@Composable
fun ConfirmContent(
    title: String,
    confirmLabel: String,
    onConfirm: suspend () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    danger: Boolean = true,
    cancelLabel: String = "Cancel",
    preview: (@Composable () -> Unit)? = null,
) {
    val c = Belfry.colors
    val busy = rememberBusy()
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        if (!message.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(message, fontSize = 15.sp, lineHeight = 21.sp, color = c.muted)
        }
        if (preview != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, c.line, RoundedCornerShape(12.dp))
                    .background(c.surface)
                    .padding(12.dp),
            ) { preview() }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BusyButton(cancelLabel, onClick = { onDismiss() }, modifier = Modifier.weight(1f), kind = ButtonKind.Ghost, enabled = !busy.busy)
            BusyButton(
                confirmLabel, onClick = onConfirm, modifier = Modifier.weight(1f),
                kind = if (danger) ButtonKind.Danger else ButtonKind.Primary, busy = busy,
            )
        }
    }
}
