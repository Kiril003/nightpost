package local.phantom.companion.feature.stream.ui.map.icons.marks

/**
 * Перо, яке нічого не малює, а записує — як `RecordingGlyphPen` у родини.
 * Фігурки — чиста геометрія, тож у модулі без Robolectric їх усе одно можна
 * перевірити по-справжньому: чи щось намальовано, у які межі це вклалось і чи
 * дійшло тіло до землі.
 *
 * Обертання застосовується НАСПРАВДІ: інакше межі ДТП (кузов під -7°) були б
 * виміряні по неповернутій фігурі, тобто не по тій, що потрапить у бітмап.
 * Вкладене перо віддає вже перетворену точку своєму [sink], і той накладає
 * власне перетворення — так само, як стек матриць полотна.
 */
internal class RecordingMarkPen(
    private val angle: Float = 0f,
    private val pivotX: Float = 0f,
    private val pivotY: Float = 0f,
    private val sink: RecordingMarkPen? = null,
) : MarkPen {

    var ops = 0
        private set
    var minX = Float.MAX_VALUE
        private set
    var minY = Float.MAX_VALUE
        private set
    var maxX = -Float.MAX_VALUE
        private set
    var maxY = -Float.MAX_VALUE
        private set

    val tones = mutableSetOf<MarkTone>()

    private fun mark(x: Float, y: Float) {
        var rx = x
        var ry = y
        if (angle != 0f) {
            val a = Math.toRadians(angle.toDouble())
            val dx = (x - pivotX).toDouble()
            val dy = (y - pivotY).toDouble()
            rx = (pivotX + dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)).toFloat()
            ry = (pivotY + dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a)).toFloat()
        }
        sink?.mark(rx, ry)
        if (rx < minX) minX = rx
        if (rx > maxX) maxX = rx
        if (ry < minY) minY = ry
        if (ry > maxY) maxY = ry
    }

    private fun count(tone: MarkTone?) {
        ops++
        tone?.let { tones += it }
    }

    private fun trace(tone: MarkTone?, pts: FloatArray) {
        count(tone)
        var i = 0
        while (i + 1 < pts.size) {
            mark(pts[i], pts[i + 1])
            i += 2
        }
    }

    private fun absorb(inner: RecordingMarkPen) {
        ops += inner.ops
        tones += inner.tones
    }

    override fun poly(tone: MarkTone, vararg pts: Float) = trace(tone, pts)

    override fun plane(tone: MarkTone, vararg pts: Float) = trace(tone, pts)

    override fun edge(vararg pts: Float) = trace(null, pts)

    override fun thin(tone: MarkTone, width: Float, vararg pts: Float) = trace(tone, pts)

    override fun box(tone: MarkTone, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        count(tone)
        mark(x, y)
        mark(x + w, y + h)
    }

    override fun plate(tone: MarkTone, x: Float, y: Float, w: Float, h: Float, radius: Float) =
        box(tone, x, y, w, h, radius)

    override fun boxEdge(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        count(null)
        mark(x, y)
        mark(x + w, y + h)
    }

    override fun disc(tone: MarkTone, cx: Float, cy: Float, r: Float) = oval(tone, cx, cy, r, r)

    override fun dot(tone: MarkTone, cx: Float, cy: Float, r: Float) = oval(tone, cx, cy, r, r)

    override fun oval(tone: MarkTone, cx: Float, cy: Float, rx: Float, ry: Float) {
        count(tone)
        mark(cx - rx, cy - ry)
        mark(cx + rx, cy + ry)
    }

    override fun line(tone: MarkTone, width: Float, vararg pts: Float) = trace(tone, pts)

    /**
     * Межі бере сам багатокутник-кліп, а не те, що всередині: смуги навмисно
     * починаються за краєм фігури й гинуть під відсіченням — виміряти їх
     * означало б звинуватити знак у виході за бітмап, якого на склі немає.
     */
    override fun clipped(clip: FloatArray, block: MarkPen.() -> Unit) {
        trace(null, clip)
        val inner = RecordingMarkPen()
        inner.block()
        absorb(inner)
    }

    override fun rotated(degrees: Float, pivotX: Float, pivotY: Float, block: MarkPen.() -> Unit) {
        val inner = RecordingMarkPen(degrees, pivotX, pivotY, sink = this)
        inner.block()
        absorb(inner)
    }
}
