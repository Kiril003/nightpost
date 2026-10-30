package local.phantom.companion.feature.stream.ui.map.peers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.feature.stream.ui.map.icons.CanvasGlyphPen
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import org.json.JSONObject

/**
 * Обличчя людей на мапі — той самий аватар, що в розмові: плоска пляма тону з
 * білою літерою або фото колом, без світла, сяйва й градієнта. Кільце 2dp —
 * колір ЗЕМЛІ ([ground]), а не світлий обід: воно вирізає аватар із доріг і
 * сусідів, як у системних мапах, а від самої землі його відділяє насичений тон.
 * Значок унизу праворуч — та сама мова: піднята плашка, гліф кольором тексту;
 * бурштин лише в «телефон сідає» і в точці зустрічі — там, де треба діяти.
 */
internal object PeerMarks {

    private const val DISC_DP = 40f
    private const val RING_DP = 2f
    private const val BADGE_DP = 17f
    private val AMBER = NightColors.accent.toArgb()

    private fun ground(night: Boolean) = (if (night) NightColors else DayColors).canvas.toArgb()
    private fun raised(night: Boolean) = (if (night) NightColors else DayColors).raised.toArgb()
    private fun text(night: Boolean) = (if (night) NightColors else DayColors).text.toArgb()

    fun bake(props: JSONObject, night: Boolean, density: Float, face: Typeface? = null): Bitmap? {
        val count = props.optInt("count", 1)
        if (props.optString("img") == "pwm") return meet(night, density)
        return if (count > 1) {
            val parts = props.optString("img").split('-')
            group((parts.getOrNull(1)?.toIntOrNull() ?: 0) to (parts.getOrNull(2)?.toIntOrNull() ?: 0), count, night, density, face)
        } else {
            person(
                tone = props.optInt("tone", 0),
                initial = String(Character.toChars(props.optInt("glyph", '?'.code))),
                face = props.optString("face").takeIf { it.isNotEmpty() },
                badge = props.optString("badge"),
                night = night,
                density = density,
                typeface = face,
            )
        }
    }

    fun person(
        tone: Int,
        initial: String,
        face: String?,
        badge: String,
        night: Boolean,
        density: Float,
        typeface: Typeface? = null,
    ): Bitmap {
        val disc = DISC_DP * density
        // Значок сидить на краю кулі й виходить за неї: без запасу його обрізало.
        val pad = 7.5f * density
        val size = (disc + pad * 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val r = disc / 2f
        val t = PersonTone.of(tone, night)
        c.drawCircle(cx, cx, r + RING_DP * density, paint(ground(night)))
        c.drawCircle(cx, cx, r, paint(t.fill))
        val photo = face?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (photo != null) {
            val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = r * 2 / minOf(photo.width, photo.height)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(cx - photo.width * scale / 2, cx - photo.height * scale / 2)
            })
            c.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        } else {
            val text = letter(t.ink, 17f, density, typeface)
            c.drawText(initial, cx, baseline(cx, text), text)
        }
        badge(c, badge, cx + r * 0.72f, cx + r * 0.72f, night, density)
        return bmp
    }

    private fun letter(colour: Int, sp: Float, density: Float, typeface: Typeface?) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        textSize = sp * density
        this.typeface = typeface ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private fun baseline(cy: Float, p: Paint) = cy - (p.descent() + p.ascent()) / 2

    private fun badge(c: Canvas, kind: String, x: Float, y: Float, night: Boolean, density: Float) {
        if (kind.isEmpty()) return
        val br = BADGE_DP * density / 2f
        c.drawCircle(x, y, br + 1.5f * density, paint(ground(night)))
        val low = kind == "low"
        c.drawCircle(x, y, br, paint(if (low) AMBER else raised(night)))
        val ink = if (low) NightColors.onAccent.toArgb() else text(night)
        when (kind) {
            "car" -> PhantomGlyph.CAR.drawOn(CanvasGlyphPen(c, x, y, br * 1.5f, ink))
            "foot" -> PhantomGlyph.FOOT.drawOn(CanvasGlyphPen(c, x, y, br * 1.5f, ink))
            "low" -> {
                val w = br * 1.1f
                val h = br * 0.62f
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * density; color = ink }
                c.drawRoundRect(RectF(x - w / 2, y - h / 2, x + w / 2 - 1.5f * density, y + h / 2), 1.5f * density, 1.5f * density, stroke)
                c.drawRect(RectF(x + w / 2 - 1.2f * density, y - h / 5, x + w / 2 + 0.3f * density, y + h / 5), paint(ink))
                c.drawRect(RectF(x - w / 2 + 1.4f * density, y - h / 2 + 1.4f * density, x - w / 2 + 3.2f * density, y + h / 2 - 1.4f * density), paint(ink))
            }
        }
    }

    /** Двоє в одному місці — дві кулі їхніх тонів, одна за одною, і лічильник. */
    fun group(tones: Pair<Int, Int>, count: Int, night: Boolean, density: Float, typeface: Typeface? = null): Bitmap {
        val disc = 34f * density
        val shift = 12f * density
        val pad = 4f * density
        val w = (disc + shift + pad * 2).toInt()
        val h = (disc + pad * 2).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = disc / 2f
        val cy = h / 2f
        listOf(pad + shift + r to tones.second, pad + r to tones.first).forEach { (cx, tone) ->
            c.drawCircle(cx, cy, r + RING_DP * density, paint(ground(night)))
            c.drawCircle(cx, cy, r, paint(PersonTone.of(tone, night).fill))
        }
        val text = letter(PersonTone.of(tones.first, night).ink, 16f, density, typeface)
        c.drawText(count.toString(), pad + r, baseline(cy, text), text)
        return bmp
    }

    /** Точка зустрічі — бурштином: єдиний акцент, і це моє рішення на мапі. */
    fun meet(night: Boolean, density: Float): Bitmap {
        val disc = 34f * density
        val size = (disc + 8 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        c.drawCircle(cx, cx, disc / 2 + RING_DP * density, paint(ground(night)))
        c.drawCircle(cx, cx, disc / 2, paint(AMBER))
        PhantomGlyph.VIA.drawOn(CanvasGlyphPen(c, cx, cx, disc * 0.62f, NightColors.onAccent.toArgb()))
        return bmp
    }

    private fun paint(colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
}
