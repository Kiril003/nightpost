package local.phantom.companion.feature.stream.ui.map.icons

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import local.phantom.companion.feature.stream.ui.map.MapSymbolPalette
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkBox
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkPalette
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkPen
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkPlate
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkTone

/**
 * Решта землі мапи тією самою рукою, що й жетони місць: одне світло згори-зліва,
 * бічна грань, тінь контакту, чорнильний край.
 */
internal object GroundTokens {

    /** Скупчення — стос жетонів: що більше місць, то вищий стос і ширший камінь. */
    enum class Pile(val layers: Int, val sizeDp: Float) { FEW(2, 26f), SOME(3, 31f), MANY(4, 36f) }

    fun pileImageId(pile: Pile, isDark: Boolean) = "poi-pile-${pile.name.lowercase()}-${if (isDark) "night" else "day"}"

    /**
     * Де лице верхнього каменя відносно центру бітмапа, у dp: сюди лягає лічба.
     * Камені зсунуті вправо-вниз від верхнього, тож центр бітмапа не на ньому.
     */
    fun pileFaceOffsetDp(pile: Pile): Pair<Float, Float> {
        val n = pile.layers - 1
        return -n * FAN_X_DP / 2f to -(n * FAN_Y_DP + SIDE_DP) / 2f
    }

    fun pile(pile: Pile, isDark: Boolean, density: Float): Bitmap {
        val p = MapSymbolPalette.resolve(isDark)
        val s = pile.sizeDp * density
        val n = pile.layers - 1
        val fx = FAN_X_DP * density
        val fy = FAN_Y_DP * density
        val side = SIDE_DP * density
        val pad = 3f * density
        val bmp = Bitmap.createBitmap((s + n * fx + 2 * pad).toInt(), (s + side + n * fy + 2 * pad).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wall = mix(p.plate, INK, 0.38f)
        for (i in n downTo 0) {
            val r = RectF(pad + i * fx, pad + i * fy, pad + i * fx + s, pad + i * fy + s)
            if (i == n) contactShadow(c, r, side, isDark, density)
            puck(c, r, side, p, density, sideTone = wall)
        }
        return bmp
    }

    /**
     * Зупинка з мережі пакета — плита платформи, що лежить на землі. Вид несе
     * обрис, не колір: червоний трамвай на мапі, де корал означає небезпеку,
     * брехав би. Автобус — табличка, трамвай — табличка з рейкою, метро й
     * поїзд — ширша плита станції з колією, бо станція і є орієнтиром міста.
     */
    enum class StopKind(val wDp: Float, val hDp: Float) { BUS(9f, 9f), TRAM(10.5f, 10.5f), STATION(15f, 10f) }

    fun stopKindOf(mode: String): StopKind = when (mode) {
        "TRAM" -> StopKind.TRAM
        "METRO", "RAIL", "FUNICULAR" -> StopKind.STATION
        else -> StopKind.BUS
    }

    fun stopImageId(kind: StopKind, isDark: Boolean) = "pack-stop-${kind.name.lowercase()}-${if (isDark) "night" else "day"}"

    fun stop(kind: StopKind, isDark: Boolean, density: Float): Bitmap {
        val p = MapSymbolPalette.resolve(isDark)
        val pad = 2.5f * density
        val side = 1.6f * density
        val w = kind.wDp * density
        val hh = kind.hDp * density
        val bmp = Bitmap.createBitmap((w + 2 * pad).toInt(), (hh + side + 2 * pad).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(pad, pad, pad + w, pad + hh)
        contactShadow(c, r, side, isDark, density)
        puck(c, r, side, p, density, sideTone = SLATE, radius = hh * 0.24f)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1.3f * density
            color = p.plateInk
        }
        val cx = r.centerX()
        val cy = r.centerY()
        when (kind) {
            StopKind.BUS -> c.drawCircle(cx, cy, 1.5f * density, ink.apply { style = Paint.Style.FILL })
            StopKind.TRAM -> {
                c.drawLine(r.left + 2.2f * density, cy - 1.2f * density, r.right - 2.2f * density, cy - 1.2f * density, ink)
                c.drawLine(r.left + 2.2f * density, cy + 1.2f * density, r.right - 2.2f * density, cy + 1.2f * density, ink)
            }
            StopKind.STATION -> {
                c.drawLine(r.left + 2.4f * density, cy - 1.3f * density, r.right - 2.4f * density, cy - 1.3f * density, ink)
                c.drawLine(r.left + 2.4f * density, cy + 1.3f * density, r.right - 2.4f * density, cy + 1.3f * density, ink)
                ink.strokeWidth = 0.9f * density
                var x = r.left + 3.4f * density
                while (x < r.right - 2.6f * density) {
                    c.drawLine(x, cy - 2.4f * density, x, cy + 2.4f * density, ink)
                    x += 2.4f * density
                }
            }
        }
        return bmp
    }

    const val ROUTE_END_IMAGE = "route-end"
    const val ROUTE_START_IMAGE = "route-start"

    /**
     * Кінець маршруту — віха, що СТОЇТЬ на землі, як тіла світу: мета — це
     * місце, куди людина йде, тож бурштин (єдиний акцент мапи). Стоїть на тій
     * самій коробці й тіні, що й аптека чи укриття, щоб поруч вони не висіли на
     * різній висоті.
     */
    fun routeEnd(isDark: Boolean, density: Float): Bitmap =
        MarkPlate.bake(MarkPalette.accent(isDark), density) { milestone() }

    /** Початок, коли він не там, де людина: плоский камінь без жодного акценту. */
    fun routeStart(isDark: Boolean, density: Float): Bitmap {
        val p = MapSymbolPalette.resolve(isDark)
        val pad = 3f * density
        val s = 13f * density
        val side = 2f * density
        val bmp = Bitmap.createBitmap((s + 2 * pad).toInt(), (s + side + 2 * pad).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(pad, pad, pad + s, pad + s)
        contactShadow(c, r, side, isDark, density)
        puck(c, r, side, p, density, sideTone = SLATE, radius = s / 2f)
        c.drawCircle(r.centerX(), r.centerY(), 2.2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.plateInk })
        return bmp
    }

    private const val FAN_X_DP = 2.6f
    private const val FAN_Y_DP = 3f
    private const val SIDE_DP = 2.4f
    private const val SLATE = 0xFF6E675E.toInt()
    private const val INK = 0xFF14161B.toInt()

    private fun contactShadow(c: Canvas, face: RectF, side: Float, isDark: Boolean, density: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            alpha = if (isDark) 150 else 70
            maskFilter = BlurMaskFilter(1.6f * density, BlurMaskFilter.Blur.NORMAL)
        }
        val r = RectF(face).apply { offset(0.8f * density, side + 1.2f * density) }
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint)
    }

    /** Один камінь: грань тону, світле лице з градієнтом згори-зліва, край, відблиск. */
    private fun puck(c: Canvas, r: RectF, side: Float, p: MapSymbolPalette, density: Float, sideTone: Int, radius: Float = r.width() / 2f) {
        val face = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        val under = Path(face).apply { offset(0f, side) }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.shader = LinearGradient(r.left, 0f, r.right, 0f, sideTone, mix(sideTone, INK, 0.5f), Shader.TileMode.CLAMP)
        c.drawPath(under, fill)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            color = p.plateRim
        }
        c.drawPath(under, rim)
        fill.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            0xFFFFFDF6.toInt(), mix(p.plate, INK, 0.08f), Shader.TileMode.CLAMP,
        )
        c.drawPath(face, fill)
        c.drawPath(face, rim)
        val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * density
            color = 0xFFFFFFFF.toInt()
            alpha = 170
        }
        c.save()
        c.clipPath(face)
        c.translate(0.8f * density, 0.8f * density)
        c.drawPath(face, glint)
        c.restore()
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val from = (a shr shift) and 0xFF
            val to = (b shr shift) and 0xFF
            return (from + (to - from) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Віха мети: камінь-основа, стовп, прапор, що тріпоче від світла. */
private fun MarkPen.milestone() {
    oval(MarkTone.SHADE, 54f, 114f, 30f, 7f)
    oval(MarkTone.BONE, 54f, 110f, 30f, 7f)
    poly(MarkTone.DEEP, 55f, 20f, 61f, 16f, 61f, 108f, 55f, 112f)
    poly(MarkTone.BONE, 47f, 20f, 55f, 20f, 55f, 112f, 47f, 112f)
    poly(MarkTone.PLATE, 55f, 22f, 94f, 30f, 84f, 44f, 94f, 58f, 55f, 58f)
    plane(MarkTone.LIT, 58f, 26f, 86f, 31f, 58f, 34f)
    dot(MarkTone.INK, 51f, 16f, 5f)
}
