package local.phantom.companion.feature.stream.ui.map.icons.marks

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.feature.stream.ui.map.MapPinFace

/**
 * Тони одного класу, зведені з токенів Обсидіану (docs/atlas/OBSIDIAN_TOKENS_V0.md).
 *
 * `plate` — ТЕМА-ІНВАРІАНТНИЙ і дорівнює денному токену. Це виміряно, не
 * вподобання: нічні токени на кістяній плашці дають 1.74–3.41:1 (блокпост
 * #F2A93B — 1.74, укриття #46C285 — 1.96), тобто кільце й шви були б майже
 * невидимі. Денні дають 2.96–5.70. Полярність рухає лише [MarkTone.SIGNAL] —
 * ті плями, що торкаються ЗЕМЛІ.
 */
internal class MarkPalette private constructor(
    private val signal: Int,
    private val plate: Int,
    private val isDark: Boolean,
) {
    private val shade = mix(plate, INK, 0.34f)
    private val deep = mix(plate, INK, 0.60f)
    private val lit = mix(plate, BONE, 0.55f)
    private val glow = mix(INK, signal, 0.35f)

    val shadowAlpha: Int get() = if (isDark) 153 else 66

    fun of(tone: MarkTone): Int = when (tone) {
        MarkTone.BONE -> BONE
        MarkTone.INK -> INK
        MarkTone.PLATE -> plate
        MarkTone.SHADE -> shade
        MarkTone.DEEP -> deep
        MarkTone.LIT -> lit
        MarkTone.SIGNAL -> signal
        MarkTone.GLOW -> glow
    }

    companion object {
        const val BONE = 0xFFF2EFE7.toInt()
        const val INK = 0xFF14161B.toInt()
        // Бурштин D: ніч — акцент, день — лінія акценту (3,29:1 на кістці).
        val AMBER_NIGHT = NightColors.accent.toArgb()
        val AMBER_DAY = DayColors.accentLine.toArgb()

        // signal(ніч) → signal(день) → plate. Третє число — не третій відтінок
        // ролі, а її ДЕННИЙ токен, поглиблений до 3:1 на кістяній плашці
        // (амбер підтягнуто з #C77B21 до #C37821 — рівно 3.03:1).
        // 27.09, мова форми: бурштин — єдиний акцент, корал — лише небезпека.
        // Решта класів нейтральна: сіра плашка 4,9:1 на кістці; клас несе силует.
        private val NEUTRAL = Triple(0xFFB9B0A3.toInt(), 0xFF6E675E.toInt(), 0xFF6E675E.toInt())
        private val ROLES: Map<MarkKind, Triple<Int, Int, Int>> = mapOf(
            MarkKind.GATE to NEUTRAL,
            MarkKind.CHECKPOINT to NEUTRAL,
            MarkKind.CRASH to Triple(0xFFFF5D45.toInt(), 0xFFD93B26.toInt(), 0xFFD93B26.toInt()),
            MarkKind.CAMERA to Triple(0xFFA8A49C.toInt(), 0xFF5A5F66.toInt(), 0xFF5A5F66.toInt()),
            // Світло на порозі укриття — бурштин: це місце, куди людина йде.
            MarkKind.SHELTER to Triple(AMBER_NIGHT, AMBER_DAY, 0xFF6E675E.toInt()),
            MarkKind.DANGER to Triple(0xFFE5484D.toInt(), 0xFFC7373D.toInt(), 0xFFC7373D.toInt()),
        )

        /** Нейтральне тіло світу (аптека, АЗС, пошта). */
        fun neutral(isDark: Boolean): MarkPalette =
            MarkPalette(if (isDark) NEUTRAL.first else NEUTRAL.second, NEUTRAL.third, isDark)

        /** Бурштин: мета маршруту або укриття під тривогою. */
        fun accent(isDark: Boolean): MarkPalette =
            MarkPalette(if (isDark) AMBER_NIGHT else AMBER_DAY, AMBER_DAY, isDark)

        fun of(kind: MarkKind, isDark: Boolean): MarkPalette {
            val (night, day, plate) = ROLES.getValue(kind)
            return MarkPalette(if (isDark) night else day, plate, isDark)
        }

        /**
         * Роль поза сімома класами подій. Люди — не восьмий клас загрози, тож
         * власного рядка в [ROLES] не мають; домішки площин лишаються ті самі,
         * бо саме вони дають обʼєм, який упізнається силуетом.
         */
        fun role(signalNight: Int, signalDay: Int, plate: Int, isDark: Boolean): MarkPalette =
            MarkPalette(if (isDark) signalNight else signalDay, plate, isDark)

        private fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val from = (a shr shift) and 0xFF
                val to = (b shr shift) and 0xFF
                return (from + (to - from) * t).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** Перо фігурки поверх android.graphics. Полотно вже масштабоване в авторські
 *  одиниці, тож числа програми йдуть у Canvas як є, разом із товщинами. */
internal class CanvasMarkPen(
    private val canvas: Canvas,
    private val palette: MarkPalette,
) : MarkPen {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val scratch = RectF()

    private fun pathOf(pts: FloatArray, close: Boolean): Path = Path().apply {
        if (pts.size < 4) return@apply
        moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            lineTo(pts[i], pts[i + 1])
            i += 2
        }
        if (close) close()
    }

    private fun fillWith(tone: MarkTone, path: Path) {
        fill.color = palette.of(tone)
        canvas.drawPath(path, fill)
    }

    private fun strokeWith(tone: MarkTone, width: Float, path: Path) {
        stroke.color = palette.of(tone)
        stroke.strokeWidth = width
        canvas.drawPath(path, stroke)
    }

    override fun poly(tone: MarkTone, vararg pts: Float) = thin(tone, MarkBox.INK_W, *pts)

    override fun plane(tone: MarkTone, vararg pts: Float) = fillWith(tone, pathOf(pts, true))

    override fun edge(vararg pts: Float) =
        strokeWith(MarkTone.INK, MarkBox.INK_W, pathOf(pts, true))

    override fun thin(tone: MarkTone, width: Float, vararg pts: Float) {
        val path = pathOf(pts, true)
        fillWith(tone, path)
        strokeWith(MarkTone.INK, width, path)
    }

    private fun rectPath(x: Float, y: Float, w: Float, h: Float, radius: Float): Path {
        scratch.set(x, y, x + w, y + h)
        return Path().apply { addRoundRect(scratch, radius, radius, Path.Direction.CW) }
    }

    override fun box(tone: MarkTone, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        val path = rectPath(x, y, w, h, radius)
        fillWith(tone, path)
        strokeWith(MarkTone.INK, MarkBox.INK_W, path)
    }

    override fun plate(tone: MarkTone, x: Float, y: Float, w: Float, h: Float, radius: Float) =
        fillWith(tone, rectPath(x, y, w, h, radius))

    override fun boxEdge(x: Float, y: Float, w: Float, h: Float, radius: Float) =
        strokeWith(MarkTone.INK, MarkBox.INK_W, rectPath(x, y, w, h, radius))

    override fun disc(tone: MarkTone, cx: Float, cy: Float, r: Float) {
        fill.color = palette.of(tone)
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = palette.of(MarkTone.INK)
        stroke.strokeWidth = MarkBox.INK_W
        canvas.drawCircle(cx, cy, r, stroke)
    }

    override fun dot(tone: MarkTone, cx: Float, cy: Float, r: Float) {
        fill.color = palette.of(tone)
        canvas.drawCircle(cx, cy, r, fill)
    }

    override fun oval(tone: MarkTone, cx: Float, cy: Float, rx: Float, ry: Float) {
        scratch.set(cx - rx, cy - ry, cx + rx, cy + ry)
        fill.color = palette.of(tone)
        canvas.drawOval(scratch, fill)
        stroke.color = palette.of(MarkTone.INK)
        stroke.strokeWidth = MarkBox.INK_W
        canvas.drawOval(scratch, stroke)
    }

    override fun line(tone: MarkTone, width: Float, vararg pts: Float) =
        strokeWith(tone, width, pathOf(pts, false))

    override fun clipped(clip: FloatArray, block: MarkPen.() -> Unit) {
        canvas.save()
        canvas.clipPath(pathOf(clip, true))
        block()
        canvas.restore()
    }

    override fun rotated(degrees: Float, pivotX: Float, pivotY: Float, block: MarkPen.() -> Unit) {
        canvas.save()
        canvas.rotate(degrees, pivotX, pivotY)
        block()
        canvas.restore()
    }
}

/**
 * Випікання знака в бітмап MapLibre. Раз на стиль і полярність, ніколи на кадр.
 *
 * Тінь контакту — не декор: вона єдина каже, ДЕ саме стоїть фігурка, і саме її
 * центр ([MarkBox.CONTACT_Y]) мусить збігтися з координатою. Низ бітмапа на
 * [ANCHOR_SLACK_DP] нижче, тож шар доводить знак `iconOffset`-ом — без цього
 * кожен знак висить на два dp північніше за те, що заявляє.
 */
internal object MarkPlate {

    val ANCHOR_SLACK_DP: Float =
        (MarkBox.H - MarkBox.CONTACT_Y) / (MarkBox.H / MarkBox.H_DP)

    fun imageId(kind: MarkKind, isDark: Boolean): String =
        "mark-${kind.id}-${if (isDark) "night" else "day"}"

    fun barImageId(isDark: Boolean): String = "mark-gate-bar-${if (isDark) "night" else "day"}"

    fun figure(kind: MarkKind, isDark: Boolean, density: Float): Bitmap =
        bake(MarkPalette.of(kind, isDark), density, kind.draw)

    /**
     * Спільна коробка родини: тінь контакту, зсув підошви на землю, авторський
     * масштаб. Усе, що стоїть на цій землі, мусить ставати на неї однаково —
     * інакше сусідні знаки висять на різній висоті над однією координатою.
     */
    fun bake(palette: MarkPalette, density: Float, draw: MarkPen.() -> Unit): Bitmap {
        val w = MapPinFace.bitmapPx(density, MarkBox.W_DP, padDp = 0f)
        val h = MapPinFace.bitmapPx(density, MarkBox.H_DP, padDp = 0f)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val scale = w / MarkBox.W
        canvas.scale(scale, scale)

        contactShadow(canvas, palette)
        canvas.translate(0f, MarkBox.DROP)
        CanvasMarkPen(canvas, palette).draw()
        return bmp
    }

    fun bar(isDark: Boolean, density: Float): Bitmap {
        val w = MapPinFace.bitmapPx(density, MarkBar.W_DP, padDp = 0f)
        val h = MapPinFace.bitmapPx(density, MarkBar.H_DP, padDp = 0f)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val scale = w / MarkBar.W
        canvas.scale(scale, scale)
        MarkBar.draw(CanvasMarkPen(canvas, MarkPalette.of(MarkKind.GATE, isDark)))
        return bmp
    }

    /** Нічна земля вже майже чорна, тож тінь там читається як вага; денна
     *  темніша дала б знаку чорний серп під підошвою. */
    private fun contactShadow(canvas: Canvas, palette: MarkPalette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF000000.toInt()
            alpha = palette.shadowAlpha
            maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(
            MarkBox.W / 2f - MarkBox.CONTACT_RX,
            MarkBox.CONTACT_Y - MarkBox.CONTACT_RY,
            MarkBox.W / 2f + MarkBox.CONTACT_RX,
            MarkBox.CONTACT_Y + MarkBox.CONTACT_RY,
            paint,
        )
    }
}
