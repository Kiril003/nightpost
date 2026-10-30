package local.phantom.companion.feature.stream.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import local.phantom.companion.core.data.poi.PlaceTier
import local.phantom.companion.feature.stream.ui.map.icons.PhantomPin
import local.phantom.companion.feature.stream.ui.map.icons.PoiGlyphTable

/** Як кожен знак стоїть на склі сьогодні — тими самими параметрами, що й шари. */
internal object SymbolAtlasBefore : AtlasPen {

    override fun place(key: String, isDark: Boolean, d: Float): Bitmap {
        val family = PoiGlyphTable.resolve(null, key)
        val p = MapSymbolPalette.resolve(isDark)
        val tint = when (family.tier) {
            PlaceTier.CRITICAL -> p.pinCritical
            PlaceTier.SUSTAIN -> p.pinSustain
            PlaceTier.ORDINARY -> p.pinOrdinary
        }
        return PhantomPin.bitmap(family.glyph, tint, tint, d)
    }

    override fun cluster(c: Canvas, count: Int, isDark: Boolean, d: Float, cx: Float, cy: Float) {
        val p = MapSymbolPalette.resolve(isDark)
        val fill = when {
            count >= 50 -> if (isDark) 0xFF8B5CF6.toInt() else 0xFF6D28D9.toInt()
            count >= 10 -> if (isDark) 0xFF6366F1.toInt() else 0xFF4338CA.toInt()
            else -> if (isDark) 0xFF3B82F6.toInt() else 0xFF1D4ED8.toInt()
        }
        val r = when {
            count >= 50 -> 26f
            count >= 10 -> 22f
            else -> 18f
        } * d
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; alpha = (0.85f * 255).toInt() }
        c.drawCircle(cx, cy, r, paint)
        paint.apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = p.separator; alpha = 255 }
        c.drawCircle(cx, cy, r, paint)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12 * d; color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        }
        c.drawText(count.toString(), cx, cy + 4 * d, t)
    }

    override fun stop(c: Canvas, mode: String, zoom: Float, isDark: Boolean, d: Float, cx: Float, cy: Float) {
        val colour = android.graphics.Color.parseColor(
            transitLegHex(-1, local.phantom.companion.core.data.transit.raptor.TransitMode.valueOf(mode)),
        )
        val r = when (mode) {
            "METRO" -> 5.5f
            "RAIL" -> 5.0f
            "TRAM" -> 4.2f
            else -> 3.2f
        } * d
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour; alpha = 242 }
        c.drawCircle(cx, cy, r, paint)
        paint.apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d; color = if (isDark) 0xFF0B1220.toInt() else 0xFFFFFFFF.toInt() }
        c.drawCircle(cx, cy, r, paint)
    }

    override fun route(c: Canvas, isDark: Boolean, d: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
        val p = MapSymbolPalette.resolve(isDark)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            color = p.routeCasing; strokeWidth = 12f * d
        }
        val sx = x0 + 40 * d
        val ex = x1 - 70 * d
        c.drawLine(sx, y0, ex, y1, line)
        line.color = routeCoreColor(isDark); line.strokeWidth = 8f * d
        c.drawLine(sx, y0, ex, y1, line)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = MapSymbolPalette.PUCK_HALO
        c.drawCircle(sx, y0, 15f * d, dot)
        dot.color = 0xFFEAB308.toInt()
        c.drawCircle(sx, y0, 9f * d, dot)
        dot.apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = MapSymbolPalette.PUCK_RIM }
        c.drawCircle(sx, y0, 9f * d, dot)
        dot.apply { strokeWidth = 4.5f * d; color = p.searchCore }
        c.drawCircle(x1 - 30 * d, y1 - 20 * d, 7f * d, dot)
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val turn = createManeuverTurnBitmap(ctx)
        c.drawBitmap(turn, (sx + ex) / 2 - turn.width / 2f, y0 - turn.height / 2f, null)
    }
}
