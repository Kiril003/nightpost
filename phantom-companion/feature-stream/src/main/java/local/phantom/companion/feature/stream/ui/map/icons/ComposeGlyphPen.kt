package local.phantom.companion.feature.stream.ui.map.icons

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Та сама сітка на Compose-полотні — щоб пігулка транспорту й мітка загрози
 * носили ТОЙ САМИЙ знак, що й пін на землі, а не свій власний символ.
 *
 * Один [Path] на весь гліф і скидання між примітивами: ці оверлеї
 * перемальовуються на кожен рух камери, тож шлях на кожну лінію — це сміття
 * у купі там, де палець тягне мапу.
 */
internal class ComposeGlyphPen(
    private val scope: DrawScope,
    boxPx: Float,
    private val color: Color,
) : GlyphPen {

    private val u = boxPx / GlyphGrid.SIZE
    private val ox = (scope.size.width - boxPx) / 2f
    private val oy = (scope.size.height - boxPx) / 2f
    private val path = Path()

    private val stroke = Stroke(
        width = GlyphGrid.STROKE_U * u,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    private fun x(v: Float) = ox + v * u
    private fun y(v: Float) = oy + v * u

    private fun trace(pts: FloatArray, close: Boolean): Path {
        path.reset()
        if (pts.size < 4) return path
        path.moveTo(x(pts[0]), y(pts[1]))
        var i = 2
        while (i + 1 < pts.size) {
            path.lineTo(x(pts[i]), y(pts[i + 1]))
            i += 2
        }
        if (close) path.close()
        return path
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float) = scope.drawLine(
        color = color,
        start = Offset(x(x1), y(y1)),
        end = Offset(x(x2), y(y2)),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
    )

    override fun poly(vararg pts: Float) = scope.drawPath(trace(pts, false), color, style = stroke)

    override fun polyClosed(vararg pts: Float) = scope.drawPath(trace(pts, true), color, style = stroke)

    override fun solid(vararg pts: Float) = scope.drawPath(trace(pts, true), color, style = Fill)

    override fun circle(cx: Float, cy: Float, r: Float) =
        scope.drawCircle(color, r * u, Offset(x(cx), y(cy)), style = stroke)

    override fun dot(cx: Float, cy: Float, r: Float) =
        scope.drawCircle(color, r * u, Offset(x(cx), y(cy)), style = Fill)

    override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float) = scope.drawArc(
        color = color,
        startAngle = startDeg,
        sweepAngle = sweepDeg,
        useCenter = false,
        topLeft = Offset(x(cx - r), y(cy - r)),
        size = Size(2 * r * u, 2 * r * u),
        style = stroke,
    )

    override fun box(x: Float, y: Float, w: Float, h: Float, radius: Float) = scope.drawRoundRect(
        color = color,
        topLeft = corner(x, y),
        size = Size(w * u, h * u),
        cornerRadius = CornerRadius(radius * u),
        style = stroke,
    )

    override fun solidBox(x: Float, y: Float, w: Float, h: Float, radius: Float) = scope.drawRoundRect(
        color = color,
        topLeft = corner(x, y),
        size = Size(w * u, h * u),
        cornerRadius = CornerRadius(radius * u),
        style = Fill,
    )

    private fun corner(left: Float, top: Float) = Offset(x(left), y(top))
}

/** Знак родини як звичайна іконка Compose. */
@Composable
internal fun PhantomGlyphIcon(glyph: PhantomGlyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        glyph.drawOn(ComposeGlyphPen(this, size.minDimension, tint))
    }
}
