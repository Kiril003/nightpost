package local.phantom.companion.feature.stream.ui.map.icons

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import local.phantom.companion.core.data.poi.PlaceTier
import local.phantom.companion.feature.stream.ui.map.MapPinFace
import local.phantom.companion.feature.stream.ui.map.MapSymbolPalette

/**
 * Обрис жетона місця — відповідь на «що це за місце» ще до того, як око
 * прочитає знак усередині. Форма взята з того, як ці місця позначають у
 * житті: табличка транспорту квадратна, установа — з фронтоном, служба
 * порятунку — щит, вода — крапля, памʼятка — ромб картографів, їжа — тарілка.
 */
internal enum class TokenShape { PLATE, SIGN, PEDIMENT, SHIELD, DROP, LOZENGE, SHOPFRONT, SLAB }

internal fun tokenShapeOf(glyph: PhantomGlyph): TokenShape = when (glyph) {
    PhantomGlyph.FOOD, PhantomGlyph.CAFE, PhantomGlyph.BAR, PhantomGlyph.BREAD, PhantomGlyph.WIFI -> TokenShape.PLATE
    PhantomGlyph.BUS, PhantomGlyph.TRAM, PhantomGlyph.METRO, PhantomGlyph.TRAIN, PhantomGlyph.STOP,
    PhantomGlyph.BOAT, PhantomGlyph.TAXI, PhantomGlyph.PARKING, PhantomGlyph.EV, PhantomGlyph.FUEL,
    PhantomGlyph.CARD, PhantomGlyph.POST, PhantomGlyph.PARCEL, PhantomGlyph.POWER -> TokenShape.SIGN
    PhantomGlyph.CIVIC, PhantomGlyph.SCHOOL, PhantomGlyph.BOOK, PhantomGlyph.CHURCH, PhantomGlyph.BED -> TokenShape.PEDIMENT
    PhantomGlyph.POLICE, PhantomGlyph.FIRE, PhantomGlyph.HOSPITAL, PhantomGlyph.PHARMACY, PhantomGlyph.PULSE,
    PhantomGlyph.HEART, PhantomGlyph.TOOTH, PhantomGlyph.PAW, PhantomGlyph.SHELTER -> TokenShape.SHIELD
    PhantomGlyph.WATER -> TokenShape.DROP
    PhantomGlyph.TREE, PhantomGlyph.MOUNTAIN, PhantomGlyph.VIEW, PhantomGlyph.BRIDGE -> TokenShape.LOZENGE
    PhantomGlyph.SHOP, PhantomGlyph.CART, PhantomGlyph.REPAIR, PhantomGlyph.TYRE, PhantomGlyph.CAR,
    PhantomGlyph.FACTORY -> TokenShape.SHOPFRONT
    PhantomGlyph.DOOR, PhantomGlyph.EXIT, PhantomGlyph.STAIRS, PhantomGlyph.ELEVATOR, PhantomGlyph.BARRIER,
    PhantomGlyph.CHECKPOINT, PhantomGlyph.CAMERA, PhantomGlyph.PERSON -> TokenShape.SLAB
    else -> TokenShape.PLATE
}

/**
 * Жетон місця, що ЛЕЖИТЬ на землі — на відміну від тіл світу, які на ній
 * СТОЯТЬ. Світло одне на всю мапу, згори-зліва: лице світле вгорі й тьмяніше
 * донизу, під лицем — бічна грань тону ярусу, під гранню — тінь контакту.
 * Важливість несе товщина грані й розмір, а не яскравість: критичне місце —
 * вищий, більший камінь, крамниця — тонка плитка.
 */
internal object PlaceToken {

    internal data class Cut(val sizeDp: Float, val sideDp: Float, val glyphDp: Float)

    internal const val BITMAP_DP = 40f

    internal fun cutOf(tier: PlaceTier, shape: TokenShape): Cut = when {
        shape == TokenShape.SLAB -> Cut(20f, 1.4f, 13f)
        tier == PlaceTier.CRITICAL -> Cut(30f, 3.2f, 17f)
        tier == PlaceTier.SUSTAIN -> Cut(28f, 2.6f, 16f)
        else -> Cut(26f, 1.8f, 15f)
    }

    fun bitmap(glyph: PhantomGlyph, tier: PlaceTier, isDark: Boolean, density: Float): Bitmap {
        val palette = MapSymbolPalette.resolve(isDark)
        val tone = when (tier) {
            PlaceTier.CRITICAL -> palette.pinCritical
            PlaceTier.SUSTAIN -> palette.pinSustain
            PlaceTier.ORDINARY -> palette.pinOrdinary
        }
        val shape = tokenShapeOf(glyph)
        val cut = cutOf(tier, shape)
        val px = MapPinFace.bitmapPx(density, BITMAP_DP - 2 * MapPinFace.PAD_DP)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = cut.sizeDp * density
        val side = cut.sideDp * density
        val cx = px / 2f
        val top = px / 2f - (s + side) / 2f
        val face = outline(shape, cx - s / 2f, top, s)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            alpha = if (isDark) 150 else 70
            maskFilter = BlurMaskFilter(1.6f * density, BlurMaskFilter.Blur.NORMAL)
        }
        c.save(); c.translate(0.8f * density, side + 1.2f * density); c.drawPath(face, shadow); c.restore()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = MapPinFace.RIM_DP * density * 0.8f
            color = palette.plateRim
        }
        val sidePath = Path(face).apply { offset(0f, side) }
        fill.color = mix(tone, INK, 0.25f)
        c.drawPath(sidePath, fill)
        val walls = Path().apply { op(sidePath, face, Path.Op.DIFFERENCE) }
        fill.shader = LinearGradient(cx - s / 2f, 0f, cx + s / 2f, 0f, tone, mix(tone, INK, 0.45f), Shader.TileMode.CLAMP)
        c.drawPath(walls, fill)
        fill.shader = null
        c.drawPath(sidePath, rim)

        fill.shader = LinearGradient(
            cx - s / 2f, top, cx + s / 3f, top + s,
            mix(palette.plate, BONE_LIT, 0.8f), mix(palette.plate, INK, 0.07f), Shader.TileMode.CLAMP,
        )
        c.drawPath(face, fill)
        fill.shader = null
        c.drawPath(face, rim)

        val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 0.9f * density
            color = 0xFFFFFFFF.toInt()
            alpha = 170
        }
        c.save()
        c.clipPath(face)
        c.translate(0.9f * density, 0.9f * density)
        c.drawPath(face, glint)
        c.restore()

        val glyphCy = top + s * glyphCentre(shape)
        glyph.drawOn(CanvasGlyphPen(c, cx, glyphCy, cut.glyphDp * density, tone))
        return bmp
    }

    /** Де оптичний центр лиця: у фронтона й щита маса не посередині коробки. */
    private fun glyphCentre(shape: TokenShape): Float = when (shape) {
        TokenShape.PEDIMENT -> 0.60f
        TokenShape.SHIELD -> 0.44f
        TokenShape.DROP -> 0.60f
        TokenShape.SHOPFRONT -> 0.56f
        else -> 0.5f
    }

    internal fun outline(shape: TokenShape, x: Float, y: Float, s: Float): Path = Path().apply {
        val r = RectF(x, y, x + s, y + s)
        when (shape) {
            TokenShape.PLATE -> addOval(r, Path.Direction.CW)
            TokenShape.SIGN -> addRoundRect(r, s * 0.22f, s * 0.22f, Path.Direction.CW)
            TokenShape.SLAB -> addRoundRect(r, s * 0.14f, s * 0.14f, Path.Direction.CW)
            TokenShape.PEDIMENT -> {
                moveTo(x + s / 2f, y)
                lineTo(x + s, y + s * 0.34f)
                lineTo(x + s, y + s - s * 0.1f)
                quadTo(x + s, y + s, x + s - s * 0.1f, y + s)
                lineTo(x + s * 0.1f, y + s)
                quadTo(x, y + s, x, y + s - s * 0.1f)
                lineTo(x, y + s * 0.34f)
                close()
            }
            TokenShape.SHIELD -> {
                moveTo(x + s * 0.12f, y)
                lineTo(x + s * 0.88f, y)
                quadTo(x + s, y, x + s, y + s * 0.12f)
                lineTo(x + s, y + s * 0.46f)
                cubicTo(x + s, y + s * 0.76f, x + s * 0.72f, y + s * 0.92f, x + s / 2f, y + s)
                cubicTo(x + s * 0.28f, y + s * 0.92f, x, y + s * 0.76f, x, y + s * 0.46f)
                lineTo(x, y + s * 0.12f)
                quadTo(x, y, x + s * 0.12f, y)
                close()
            }
            TokenShape.DROP -> {
                moveTo(x + s / 2f, y)
                cubicTo(x + s * 0.62f, y + s * 0.22f, x + s * 0.94f, y + s * 0.44f, x + s * 0.94f, y + s * 0.64f)
                cubicTo(x + s * 0.94f, y + s * 0.86f, x + s * 0.74f, y + s, x + s / 2f, y + s)
                cubicTo(x + s * 0.26f, y + s, x + s * 0.06f, y + s * 0.86f, x + s * 0.06f, y + s * 0.64f)
                cubicTo(x + s * 0.06f, y + s * 0.44f, x + s * 0.38f, y + s * 0.22f, x + s / 2f, y)
                close()
            }
            TokenShape.LOZENGE -> {
                val k = s * 0.08f
                moveTo(x + s / 2f - k, y + k)
                quadTo(x + s / 2f, y, x + s / 2f + k, y + k)
                lineTo(x + s - k, y + s / 2f - k)
                quadTo(x + s, y + s / 2f, x + s - k, y + s / 2f + k)
                lineTo(x + s / 2f + k, y + s - k)
                quadTo(x + s / 2f, y + s, x + s / 2f - k, y + s - k)
                lineTo(x + k, y + s / 2f + k)
                quadTo(x, y + s / 2f, x + k, y + s / 2f - k)
                close()
            }
            TokenShape.SHOPFRONT -> {
                val a = s * 0.16f
                moveTo(x + a * 0.4f, y + a)
                lineTo(x + s - a * 0.4f, y + a)
                lineTo(x + s, y + a * 2.1f)
                lineTo(x + s, y + s - s * 0.1f)
                quadTo(x + s, y + s, x + s - s * 0.1f, y + s)
                lineTo(x + s * 0.1f, y + s)
                quadTo(x, y + s, x, y + s - s * 0.1f)
                lineTo(x, y + a * 2.1f)
                close()
            }
        }
    }

    private const val INK = 0xFF14161B.toInt()
    private const val BONE_LIT = 0xFFFFFDF6.toInt()

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val from = (a shr shift) and 0xFF
            val to = (b shr shift) and 0xFF
            return (from + (to - from) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
