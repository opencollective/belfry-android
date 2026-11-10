package sh.belfry.app.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.model.PodShapes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A pod's mark: a regular polygon with one side per member (a triangle at least), a circle from
 * 13 members. The same drawing as the web's `PodShape` (24-unit box, radius 9.5, point up).
 */
@Composable
fun PodShape(members: Int, modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = Belfry.colors.muted) {
    val label = "$members ${if (members == 1) "member" else "members"}"
    Canvas(modifier.size(size).semantics { contentDescription = label }) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 2f * u, join = StrokeJoin.Round, cap = StrokeCap.Round)
        if (PodShapes.isCircle(members)) {
            drawCircle(color, radius = 9f * u, center = Offset(12f * u, 12f * u), style = stroke)
        } else {
            val n = PodShapes.sides(members)
            val cy = if (n == 3) 13.5f else 12f
            val path = Path()
            for (k in 0 until n) {
                val a = -PI / 2 + 2 * PI * k / n
                val x = (12f + 9.5f * cos(a).toFloat()) * u
                val y = (cy + 9.5f * sin(a).toFloat()) * u
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color, style = stroke)
        }
    }
}

/** The pod's shape in the same framed box as [ChannelIcon] (drawer rows, lists). */
@Composable
fun PodIcon(members: Int, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) { PodShape(members, size = size * 0.72f) }
}

/** Line icons the first set lacked. */
object MoreIcons {
    private fun icon(name: String, vararg d: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (p in d) {
            b.addPath(
                pathData = addPathNodes(p), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    /** A to-do: a ticked box. */
    val Todo by lazy { icon("todo", "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z", "M8.5 12.5l2.5 2.5 4.5-5.5") }
    val ChevronUp by lazy { icon("chevron-up", "M6 15l6-6 6 6") }
}
