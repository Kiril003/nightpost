package local.phantom.companion.feature.stream.ui.map.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import local.phantom.companion.feature.stream.ui.map.MapPinFace
import local.phantom.companion.feature.stream.ui.map.MapSymbolPalette

/**
 * Одне лице для КОЖНОГО бітмапа мапи: тепла світла плашка, чорнильний обід,
 * кільце-ідентичність, гліф родини по центру.
 *
 * Плашка й обід свідомо не залежать від теми ([MapSymbolPalette] і
 * MapSymbolContrastTest): пара «світле лице + чорнильний край» бере 3:1 на
 * КОЖНІЙ землі обох полярностей, зокрема на чужому тайл-провайдері, чию землю
 * застосунок знати не може. Тема змінює тільки [ring] і [ink].
 *
 * Раніше цю функцію тричі скопіювали (піни, збережені місця, значки загроз) і
 * тричі домалювали в неї системне емодзі. Тепер копія одна, і гліф у неї
 * приходить параметром.
 */
internal object PhantomPin {

    fun bitmap(
        glyph: PhantomGlyph,
        ring: Int,
        ink: Int,
        density: Float,
        contentDp: Float = MapPinFace.CONTENT_DP,
        padDp: Float = MapPinFace.PAD_DP,
        ringDp: Float = MapPinFace.RING_DP,
        glyphDp: Float = MapPinFace.GLYPH_DP,
    ): Bitmap {
        val size = MapPinFace.bitmapPx(density, contentDp, padDp)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f
        val r = contentDp * density / 2f
        val rimW = MapPinFace.RIM_DP * density
        val ringW = ringDp * density

        canvas.drawCircle(
            c, c, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = MapSymbolPalette.DARK.plate
            },
        )
        canvas.drawCircle(
            c, c, r - rimW / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = rimW
                color = MapSymbolPalette.DARK.plateRim
            },
        )
        canvas.drawCircle(
            c, c, r - rimW - ringW / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = ringW
                color = ring
            },
        )
        glyph.drawOn(CanvasGlyphPen(canvas, c, c, glyphDp * density, ink))
        return bmp
    }
}
