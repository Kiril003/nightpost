package local.phantom.companion.feature.stream.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import local.phantom.companion.feature.stream.ui.map.icons.GroundTokens
import local.phantom.companion.feature.stream.ui.map.icons.PlaceToken
import local.phantom.companion.feature.stream.ui.map.icons.PoiGlyphTable

internal interface AtlasPen {
    fun place(key: String, isDark: Boolean, d: Float): Bitmap
    fun cluster(c: Canvas, count: Int, isDark: Boolean, d: Float, cx: Float, cy: Float)
    fun stop(c: Canvas, mode: String, zoom: Float, isDark: Boolean, d: Float, cx: Float, cy: Float)
    fun route(c: Canvas, isDark: Boolean, d: Float, x0: Float, y0: Float, x1: Float, y1: Float)
}

/** Кожен знак тими самими бітмапами й параметрами, що йдуть у шари. */
internal object SymbolAtlas : AtlasPen {

    override fun place(key: String, isDark: Boolean, d: Float): Bitmap {
        val family = PoiGlyphTable.resolve(null, key)
        return PlaceToken.bitmap(family.glyph, family.tier, isDark, d)
    }

    override fun cluster(c: Canvas, count: Int, isDark: Boolean, d: Float, cx: Float, cy: Float) {
        val pile = when {
            count >= 50 -> GroundTokens.Pile.MANY
            count >= 10 -> GroundTokens.Pile.SOME
            else -> GroundTokens.Pile.FEW
        }
        val b = GroundTokens.pile(pile, isDark, d)
        c.drawBitmap(b, cx - b.width / 2f, cy - b.height / 2f, null)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12 * d; color = MapSymbolPalette.resolve(isDark).plateInk; textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val (ox, oy) = GroundTokens.pileFaceOffsetDp(pile)
        c.drawText(count.toString(), cx + ox * d, cy + oy * d + 4 * d, t)
    }

    override fun stop(c: Canvas, mode: String, zoom: Float, isDark: Boolean, d: Float, cx: Float, cy: Float) {
        val b = GroundTokens.stop(GroundTokens.stopKindOf(mode), isDark, d)
        val k = when {
            zoom <= 12f -> 0.7f
            zoom <= 15f -> 0.7f + (zoom - 12f) / 3f * 0.3f
            else -> (1f + (zoom - 15f) / 2.5f * 0.3f).coerceAtMost(1.3f)
        }
        val w = b.width * k
        val h = b.height * k
        c.drawBitmap(b, null, android.graphics.RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), Paint(Paint.FILTER_BITMAP_FLAG))
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
        val start = GroundTokens.routeStart(isDark, d)
        c.drawBitmap(start, sx + 60 * d - start.width / 2f, y0 - start.height / 2f, null)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = MapSymbolPalette.PUCK_RIM
        c.drawCircle(sx, y0, PUCK_HALO_R_DP * d, dot)
        dot.apply { style = Paint.Style.STROKE; strokeWidth = PUCK_EDGE_W_DP * d; color = MapSymbolPalette.PUCK_HALO }
        c.drawCircle(sx, y0, PUCK_HALO_R_DP * d, dot)
        dot.apply { strokeWidth = PUCK_RING_W_DP * d; color = MapSymbolPalette.PUCK_CORE }
        c.drawCircle(sx, y0, PUCK_RING_R_DP * d, dot)
        dot.apply { style = Paint.Style.FILL }
        c.drawCircle(sx, y0, PUCK_CORE_R_DP * d, dot)
        val end = GroundTokens.routeEnd(isDark, d)
        val contact = end.height * (local.phantom.companion.feature.stream.ui.map.icons.marks.MarkBox.CONTACT_Y / local.phantom.companion.feature.stream.ui.map.icons.marks.MarkBox.H)
        c.drawBitmap(end, ex - end.width / 2f, y1 - contact, null)
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val turn = createManeuverTurnBitmap(ctx)
        c.drawBitmap(turn, (sx + ex) / 2 - turn.width / 2f, y0 - turn.height / 2f, null)
    }
}
