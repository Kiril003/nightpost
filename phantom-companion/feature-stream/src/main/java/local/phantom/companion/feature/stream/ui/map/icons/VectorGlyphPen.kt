package local.phantom.companion.feature.stream.ui.map.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import java.util.concurrent.ConcurrentHashMap

/**
 * Та сама сітка, віддана як [ImageVector].
 *
 * Потрібна там, де компонент приймає іконку, а не полотно: `PhantomChip` бере
 * `ImageVector`, і його власна доктрина забороняє писати тридцять шостий чіп
 * заради знака родини. Замість нового компонента родина навчилась виглядати
 * як звичайна іконка — і тепер їде в будь-який API, що чекав на Material.
 *
 * Колір навмисно чорний: [androidx.compose.material3.Icon] накриває вектор
 * `ColorFilter.tint`, тож фарбує викликач, як і в решті родини.
 */
private class VectorGlyphPen(private val builder: ImageVector.Builder) : GlyphPen {

    private fun stroke(block: PathBuilder.() -> Unit) {
        builder.addPath(
            pathData = nodes(block),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = GlyphGrid.STROKE_U,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    private fun fill(block: PathBuilder.() -> Unit) {
        builder.addPath(
            pathData = nodes(block),
            fill = SolidColor(Color.Black),
            stroke = null,
        )
    }

    private fun nodes(block: PathBuilder.() -> Unit) = PathBuilder().apply(block).nodes

    private fun PathBuilder.trace(pts: FloatArray, closed: Boolean) {
        if (pts.size < 4) return
        moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            lineTo(pts[i], pts[i + 1])
            i += 2
        }
        if (closed) close()
    }

    /** Дуга в термінах SVG: прапорець «за годинниковою» — це знак розгортки. */
    private fun PathBuilder.sweep(cx: Float, cy: Float, r: Float, from: Float, span: Float) {
        val a0 = Math.toRadians(from.toDouble())
        val a1 = Math.toRadians((from + span).toDouble())
        moveTo(cx + r * cos(a0).toFloat(), cy + r * sin(a0).toFloat())
        arcTo(r, r, 0f, abs(span) > 180f, span > 0f, cx + r * cos(a1).toFloat(), cy + r * sin(a1).toFloat())
    }

    private fun PathBuilder.ring(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        close()
    }

    private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        val r = radius.coerceAtMost(minOf(w, h) / 2f)
        if (r <= 0f) {
            trace(floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h), closed = true)
            return
        }
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcTo(r, r, 0f, false, true, x + w, y + r)
        lineTo(x + w, y + h - r)
        arcTo(r, r, 0f, false, true, x + w - r, y + h)
        lineTo(x + r, y + h)
        arcTo(r, r, 0f, false, true, x, y + h - r)
        lineTo(x, y + r)
        arcTo(r, r, 0f, false, true, x + r, y)
        close()
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float) = stroke {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }

    override fun poly(vararg pts: Float) = stroke { trace(pts, closed = false) }

    override fun polyClosed(vararg pts: Float) = stroke { trace(pts, closed = true) }

    override fun solid(vararg pts: Float) = fill { trace(pts, closed = true) }

    override fun circle(cx: Float, cy: Float, r: Float) = stroke { ring(cx, cy, r) }

    override fun dot(cx: Float, cy: Float, r: Float) = fill { ring(cx, cy, r) }

    override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float) =
        stroke { sweep(cx, cy, r, startDeg, sweepDeg) }

    override fun box(x: Float, y: Float, w: Float, h: Float, radius: Float) =
        stroke { rect(x, y, w, h, radius) }

    override fun solidBox(x: Float, y: Float, w: Float, h: Float, radius: Float) =
        fill { rect(x, y, w, h, radius) }
}

private val CACHE = ConcurrentHashMap<PhantomGlyph, ImageVector>()

/** Знак родини як [ImageVector]. Будується раз на гліф — не на кожен кадр. */
internal fun PhantomGlyph.asImageVector(): ImageVector = CACHE.getOrPut(this) {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = GlyphGrid.SIZE.dp,
        defaultHeight = GlyphGrid.SIZE.dp,
        viewportWidth = GlyphGrid.SIZE,
        viewportHeight = GlyphGrid.SIZE,
    )
    drawOn(VectorGlyphPen(builder))
    builder.build()
}
