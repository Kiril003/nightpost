package local.phantom.companion.feature.stream.ui.map.icons

/**
 * Перо, яке нічого не малює, а записує.
 *
 * Гліфи — чиста геометрія, тож у модулі без Robolectric їх усе одно можна
 * перевірити по-справжньому: скільки примітивів, у які межі вони вклались і
 * чи взагалі щось лишили на сітці. Порожній гліф — це пін без знака, і поки
 * його не намалюють на пристрої, ніщо інше про це не скаже.
 */
internal class RecordingGlyphPen : GlyphPen {

    var strokes = 0
        private set
    var fills = 0
        private set

    var minX = Float.MAX_VALUE
        private set
    var minY = Float.MAX_VALUE
        private set
    var maxX = -Float.MAX_VALUE
        private set
    var maxY = -Float.MAX_VALUE
        private set

    val marks get() = strokes + fills

    private fun mark(x: Float, y: Float) {
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }

    private fun trace(pts: FloatArray) {
        var i = 0
        while (i + 1 < pts.size) {
            mark(pts[i], pts[i + 1])
            i += 2
        }
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        strokes++
        mark(x1, y1)
        mark(x2, y2)
    }

    override fun poly(vararg pts: Float) {
        strokes++
        trace(pts)
    }

    override fun polyClosed(vararg pts: Float) {
        strokes++
        trace(pts)
    }

    override fun solid(vararg pts: Float) {
        fills++
        trace(pts)
    }

    override fun circle(cx: Float, cy: Float, r: Float) {
        strokes++
        mark(cx - r, cy - r)
        mark(cx + r, cy + r)
    }

    override fun dot(cx: Float, cy: Float, r: Float) {
        fills++
        mark(cx - r, cy - r)
        mark(cx + r, cy + r)
    }

    /** Дуга обмірюється по самій дузі: коробка повного кола збрехала б на
     *  третину сітки — саме там, де гліф свідомо виходить широким. */
    override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float) {
        strokes++
        val steps = 64
        for (s in 0..steps) {
            val a = Math.toRadians((startDeg + sweepDeg * s / steps).toDouble())
            mark(cx + (r * kotlin.math.cos(a)).toFloat(), cy + (r * kotlin.math.sin(a)).toFloat())
        }
    }

    override fun box(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        strokes++
        mark(x, y)
        mark(x + w, y + h)
    }

    override fun solidBox(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        fills++
        mark(x, y)
        mark(x + w, y + h)
    }
}
