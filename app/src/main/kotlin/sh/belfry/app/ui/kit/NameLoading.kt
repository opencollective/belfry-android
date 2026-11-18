package sh.belfry.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.belfry.app.ui.theme.Belfry

/**
 * A name that hasn't arrived (SPEC 19.39, the three platforms' shared rule): in a row or a header,
 * no text but a plain grey bar where the name will be (no shimmer: e-ink), "Loading" for screen
 * readers. Never any part of an id.
 */
@Composable
fun NameSkeleton(modifier: Modifier = Modifier, fraction: Float = 0.4f, height: Dp = 14.dp) {
    Box(
        modifier.fillMaxWidth(fraction).height(height).clip(RoundedCornerShape(4.dp)).background(Belfry.colors.surface2)
            .semantics { contentDescription = "Loading" }.testTag("name-loading"),
    )
}

/** True while a name hasn't arrived, for about 5 s; after that the kind's word shows instead ("Channel"). */
@Composable
fun rememberNameLoading(named: Boolean): Boolean {
    var waited by remember { mutableStateOf(false) }
    if (!named) LaunchedEffect(Unit) { delay(5_000); waited = true }
    return !named && !waited
}
