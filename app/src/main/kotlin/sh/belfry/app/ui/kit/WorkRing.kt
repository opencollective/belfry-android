package sh.belfry.app.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.theme.Belfry

/**
 * Busy on it now (web common.tsx WorkRing): a ring that fills clockwise over the hour (one
 * minute = 6°), "1h", "2h"… in it once past an hour. [since] and [now] in Unix seconds.
 */
@Composable
fun WorkRing(since: Long, now: Long, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val c = Belfry.colors
    val min = maxOf(0.0, (now - since) / 60.0)
    val frac = if (min >= 60) 1f else maxOf(0.04f, (min / 60).toFloat())
    val label = if (min >= 60) "${(min / 60).toInt()}h" else ""
    val said = "Working on it for " + if (min < 60) "${Math.round(min)} min" else label
    Box(modifier.size(size).semantics { contentDescription = said }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 2.dp.toPx()
            val box = Size(this.size.width - w, this.size.height - w)
            val at = Offset(w / 2, w / 2)
            drawArc(c.line, 0f, 360f, false, at, box, style = Stroke(w))
            drawArc(c.orange, -90f, 360f * frac, false, at, box, style = Stroke(w, cap = StrokeCap.Round))
        }
        if (label.isNotEmpty()) Text(label, fontSize = (size.value * 0.38f).sp, lineHeight = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold, color = c.orange, maxLines = 1)
    }
}

/** The session's time now (Unix seconds), read again every [everyMs]: for states that expire, like a to-do's heartbeat. */
@Composable
fun rememberNow(everyMs: Long = 30_000): State<Long> {
    val session = LocalSession.current
    return produceState(session.now(), session) {
        while (true) { delay(everyMs); value = session.now() }
    }
}
