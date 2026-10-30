package local.phantom.companion.feature.stream.ui.map.icons.marks

/**
 * ЗНАКИ ПОДІЙ — обʼємні фігурки, а не жетони.
 *
 * Родинне перо ([local.phantom.companion.feature.stream.ui.map.icons.GlyphPen])
 * малює ЗНАК: одна вага штриха на сітці 24×24, один колір. Тут потрібне інше —
 * тіло з трьома площинами (світло / тон / тінь), яке впізнається силуетом
 * раніше, ніж око прочитає колір. Тому власне перо, і воно свідомо ширше:
 * заливка окремо від обводу, бо саме з цього складається обʼєм.
 *
 * Простір — 108×132 при щільності 3, тобто 36×44dp. Фігурка авторська на
 * базовій лінії [BASELINE] і зсувається на [DROP] так, щоб підошва стала на
 * землю; земля — це еліпс контакту в [CONTACT_Y], і саме він, а не низ
 * бітмапа, мусить збігтися з координатою (див. MarkPlate.ANCHOR_SLACK_DP).
 */
internal object MarkBox {
    const val W = 108f
    const val H = 132f

    /** Авторська база фігурки, до зсуву. */
    const val BASELINE = 118f

    /** Зсув фігурки вниз, щоб підошва торкнулась землі. */
    const val DROP = 6f

    /** Центр еліпса контакту — точка, у якій знак СТОЇТЬ. */
    const val CONTACT_Y = 126f
    const val CONTACT_RX = 26f
    const val CONTACT_RY = 7.5f

    /** Силует. Свідомо тонший за родинні 2u/24 (це дало б 8px на цій коробці):
     *  на тілі з внутрішніми площинами така вага зʼїдає саму фігуру. */
    const val INK_W = 4f

    /** Внутрішній шов — вікно, скло, сходинка. */
    const val SEAM_W = 2.6f

    const val W_DP = 36f
    const val H_DP = 44f
}

/**
 * Тони фігурки. [SIGNAL] — єдиний, що змінюється з полярністю, і він завжди
 * лежить там, де знак торкається ЗЕМЛІ (промені камери, розрив ДТП, світло на
 * порозі укриття). Решта — тема-інваріантні, як плашка й обід усієї родини:
 * нічний токен на кістяній плашці дає 1.7–3.4:1, тобто гліф був би невидимий.
 */
internal enum class MarkTone {
    BONE,
    INK,
    PLATE,
    SHADE,
    DEEP,
    LIT,
    SIGNAL,

    /** [SIGNAL] у 35% над чорнильним отвором, змішаний наперед. */
    GLOW,
}

/**
 * Примітиви фігурки. Заливка й обвід розділені навмисно: внутрішня площина з
 * обводом читається як окремий предмет, а не як грань того самого тіла.
 */
internal interface MarkPen {
    /** Заливка + чорнильний силует. */
    fun poly(tone: MarkTone, vararg pts: Float)

    /** Заливка без обводу — внутрішня грань. */
    fun plane(tone: MarkTone, vararg pts: Float)

    /** Тільки силует: перекриває смуги, що вилізли за край. */
    fun edge(vararg pts: Float)

    /** Заливка + силует тоншим пером (шви, сходинки, скло). */
    fun thin(tone: MarkTone, width: Float, vararg pts: Float)

    fun box(tone: MarkTone, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f)

    fun plate(tone: MarkTone, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f)

    fun boxEdge(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f)

    fun disc(tone: MarkTone, cx: Float, cy: Float, r: Float)

    fun dot(tone: MarkTone, cx: Float, cy: Float, r: Float)

    fun oval(tone: MarkTone, cx: Float, cy: Float, rx: Float, ry: Float)

    /** Ламана з круглими кінцями: промінь, шов, знак оклику. */
    fun line(tone: MarkTone, width: Float, vararg pts: Float)

    /** Усе, що намалює [block], лишається всередині багатокутника [clip]. */
    fun clipped(clip: FloatArray, block: MarkPen.() -> Unit)

    fun rotated(degrees: Float, pivotX: Float, pivotY: Float, block: MarkPen.() -> Unit)
}

/**
 * Діагональна небезпечна штриховка всередині [clip]. Не примітив пера, а
 * геометрія: перу вистачає знати про відсікання.
 */
internal fun MarkPen.stripes(
    tone: MarkTone,
    clip: FloatArray,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    count: Int,
    slant: Float,
) {
    val span = (x1 - x0) + kotlin.math.abs(slant)
    val step = span / count
    val width = step * 0.5f
    clipped(clip) {
        var x = x0 - kotlin.math.abs(slant)
        while (x < x1 + step) {
            plane(
                tone,
                x, y1,
                x + width, y1,
                x + width + slant, y0,
                x + slant, y0,
            )
            x += step
        }
    }
}

/** Промені зірки-спалаху ДТП. */
internal fun markStar(
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
