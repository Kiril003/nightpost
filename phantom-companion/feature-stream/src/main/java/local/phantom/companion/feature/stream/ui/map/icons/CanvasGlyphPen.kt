package local.phantom.companion.feature.stream.ui.map.icons

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Сітка гліфа, покладена на android.graphics.Canvas.
 *
 * Один екземпляр — один гліф: обидва Paint народжуються тут, тож жоден
 * малювальник не успадковує чужий колір чи товщину. Штрих виводиться з
 * розміру коробки, а не з щільності екрана: гліф на 14dp і гліф на 30dp —
 * це та сама фігура, а не та сама товщина лінії.
 */
internal class CanvasGlyphPen(
    private val canvas: Canvas,
    centreX: Float,
    centreY: Float,
    boxPx: Float,
    color: Int,
) : GlyphPen {

    private val u = boxPx / GlyphGrid.SIZE
    private val ox = centreX - boxPx / 2f
    private val oy = centreY - boxPx / 2f

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GlyphGrid.STROKE_U * u
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private val scratch = RectF()

    private fun x(v: Float) = ox + v * u
    private fun y(v: Float) = oy + v * u

    private fun pathOf(pts: FloatArray, close: Boolean) = Path().apply {
        if (pts.size < 4) return@apply
        moveTo(x(pts[0]), y(pts[1]))
        var i = 2
        while (i + 1 < pts.size) {
            lineTo(x(pts[i]), y(pts[i + 1]))
            i += 2
        }
        if (close) close()
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        canvas.drawLine(x(x1), y(y1), x(x2), y(y2), stroke)

    override fun poly(vararg pts: Float) = canvas.drawPath(pathOf(pts, false), stroke)

    override fun polyClosed(vararg pts: Float) = canvas.drawPath(pathOf(pts, true), stroke)

    override fun solid(vararg pts: Float) = canvas.drawPath(pathOf(pts, true), fill)

    override fun circle(cx: Float, cy: Float, r: Float) =
        canvas.drawCircle(x(cx), y(cy), r * u, stroke)

    override fun dot(cx: Float, cy: Float, r: Float) =
        canvas.drawCircle(x(cx), y(cy), r * u, fill)

    override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float) {
        scratch.set(x(cx - r), y(cy - r), x(cx + r), y(cy + r))
        canvas.drawArc(scratch, startDeg, sweepDeg, false, stroke)
    }

    override fun box(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        rect(x, y, w, h)
        canvas.drawRoundRect(scratch, radius * u, radius * u, stroke)
    }

    override fun solidBox(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        rect(x, y, w, h)
        canvas.drawRoundRect(scratch, radius * u, radius * u, fill)
    }

    private fun rect(left: Float, top: Float, w: Float, h: Float) =
        scratch.set(x(left), y(top), x(left + w), y(top + h))
}
