package local.phantom.companion.feature.stream.ui.map.icons

/**
 * Родина знаків PHANTOM — одна рука на всю мапу.
 *
 * До цього кожен пін ніс СИСТЕМНЕ кольорове емодзі: кава на одному телефоні —
 * плоска чашка Google, на іншому — обʼємна Samsung, на третьому взагалі
 * тофу-квадрат. Гліф не можна було ні перефарбувати, ні звузити, ні
 * вирівняти по сітці, і разом вони читались як набір наліпок, а не як мапа.
 *
 * Тут гліф — це послідовність примітивів на сітці 24×24, і більше нічого:
 * ні шрифту, ні SVG, ні ресурсу. Одна вага штриха (2u), круглі кінці, живе
 * поле 2..22. Колір задає той, хто малює, тож один і той самий гліф іде і в
 * бітмап MapLibre, і в Compose-оверлей.
 */
internal object GlyphGrid {
    const val SIZE = 24f
    const val STROKE_U = 2f

    /** За це поле не виходить жоден гліф — інакше кільце піна його зріже. */
    const val MARGIN_U = 2f
    const val MIN_U = MARGIN_U
    const val MAX_U = SIZE - MARGIN_U
}

/**
 * Перо родини: координати в одиницях сітки (0..24), y росте ВНИЗ — так само,
 * як у Canvas, щоб числа гліфа читались однаково в коді й на екрані.
 *
 * Примітивів свідомо шість. Сьомий — це запрошення намалювати щось, що не
 * належить до родини.
 */
internal interface GlyphPen {
    fun line(x1: Float, y1: Float, x2: Float, y2: Float)

    /** Ламана з пар x,y. */
    fun poly(vararg pts: Float)

    /** Ламана, замкнена на першу точку. */
    fun polyClosed(vararg pts: Float)

    /** Залитий багатокутник — акцент, не основа. */
    fun solid(vararg pts: Float)

    fun circle(cx: Float, cy: Float, r: Float)

    fun dot(cx: Float, cy: Float, r: Float)

    /** Кути в градусах, 0° — праворуч, додатній напрямок — за годинниковою. */
    fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float)

    fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f)

    fun solidBox(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f)
}

/** Промені зірки/спалаху — щоб десять магічних чисел не жили в гліфі. */
internal fun starPoints(
    cx: Float,
    cy: Float,
    outer: Float,
    inner: Float,
    points: Int,
    startDeg: Float = -90f,
): FloatArray {
    val out = FloatArray(points * 4)
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) outer else inner
        val a = Math.toRadians((startDeg + i * 180f / points).toDouble())
        out[i * 2] = cx + (r * kotlin.math.cos(a)).toFloat()
        out[i * 2 + 1] = cy + (r * kotlin.math.sin(a)).toFloat()
    }
    return out
}

/** Кільцевий сектор — трилисник радіації й подібне. */
internal fun sectorPoints(
    cx: Float,
    cy: Float,
    rIn: Float,
    rOut: Float,
    startDeg: Float,
    sweepDeg: Float,
    steps: Int = 6,
): FloatArray {
    val out = FloatArray((steps + 1) * 4)
    var i = 0
    for (s in 0..steps) {
        val a = Math.toRadians((startDeg + sweepDeg * s / steps).toDouble())
        out[i++] = cx + (rOut * kotlin.math.cos(a)).toFloat()
        out[i++] = cy + (rOut * kotlin.math.sin(a)).toFloat()
    }
    for (s in steps downTo 0) {
        val a = Math.toRadians((startDeg + sweepDeg * s / steps).toDouble())
        out[i++] = cx + (rIn * kotlin.math.cos(a)).toFloat()
        out[i++] = cy + (rIn * kotlin.math.sin(a)).toFloat()
    }
    return out
}
