package local.phantom.companion.feature.stream.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import local.phantom.companion.feature.stream.ui.map.icons.PoiGlyphTable
import local.phantom.companion.feature.stream.ui.map.icons.marks.WorldFigures
import local.phantom.companion.feature.stream.ui.map.world.WorldKind
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Уся родина знаків мапи на землі Обсидіану, у справжньому розмірі A52
 * (xhdpi, 2 px на dp). Кадр лягає в build/map-look/symbol-atlas-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w411dp-h891dp-xhdpi")
class MapSymbolAtlasShotsTest {

    private val d = 2f

    @Test fun atlasDay() = atlas("symbol-atlas-day", isDark = false, SymbolAtlas)
    @Test fun atlasNight() = atlas("symbol-atlas-night", isDark = true, SymbolAtlas)
    @Test fun atlasDayBefore() = atlas("symbol-atlas-day-before", isDark = false, SymbolAtlasBefore)
    @Test fun atlasNightBefore() = atlas("symbol-atlas-night-before", isDark = true, SymbolAtlasBefore)

    private fun atlas(name: String, isDark: Boolean, pen: AtlasPen) {
        val w = (411 * d).toInt()
        val h = (620 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        ground(c, w, h, isDark)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10 * d
            color = MapSymbolPalette.resolve(isDark).textInk
        }
        var y = 14 * d
        fun title(s: String) {
            c.drawText(s, 8 * d, y, text)
            y += 8 * d
        }

        title("місця")
        val places = listOf(
            "cafe", "supermarket", "school", "museum", "police", "drinking_water", "dentist",
            "station", "subway_entrance", "parking", "park", "viewpoint", "hotel", "entrance",
        )
        places.chunked(7).forEach { row ->
            rowOf(row.size, y) { i, cx, cy -> drawBitmapCentered(c, pen.place(row[i], isDark, d), cx, cy) }
            y += 40 * d
        }

        title("тіла світу")
        WorldKind.entries.chunked(8).forEach { row ->
            rowOf(row.size, y) { i, cx, cy ->
                val fig = WorldFigures.figure(row[i], isDark, false, d)
                c.drawBitmap(fig, cx - fig.width / 2f, cy - fig.height * 0.9f, null)
            }
            y += 52 * d
        }

        title("скупчення")
        val counts = listOf(4, 23, 140)
        rowOf(counts.size, y + 6 * d) { i, cx, cy -> pen.cluster(c, counts[i], isDark, d, cx, cy) }
        y += 60 * d

        title("транспорт")
        val modes = listOf("BUS", "TROLLEYBUS", "TRAM", "METRO", "RAIL")
        listOf(14f, 16f).forEach { zoom ->
            rowOf(modes.size, y) { i, cx, cy -> pen.stop(c, modes[i], zoom, isDark, d, cx, cy) }
            y += 34 * d
        }

        title("я, маршрут, пошук")
        pen.route(c, isDark, d, 20 * d, y + 30 * d, w - 20 * d, y + 30 * d)
        y += 80 * d

        File("build/map-look").mkdirs()
        File("build/map-look/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(PoiGlyphTable.bySubcategory.isNotEmpty())
    }

    private fun rowOf(n: Int, y: Float, draw: (Int, Float, Float) -> Unit) {
        val span = 411 * d / (n.coerceAtLeast(1) + 0f)
        for (i in 0 until n) draw(i, span * (i + 0.5f), y + 22 * d)
    }

    private fun drawBitmapCentered(c: Canvas, b: Bitmap, cx: Float, cy: Float) =
        c.drawBitmap(b, cx - b.width / 2f, cy - b.height / 2f, null)

    /** Шматок Обсидіану: земля, квартали, вулиця — щоб знак стояв на справжньому тлі. */
    private fun ground(c: Canvas, w: Int, h: Int, isDark: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (isDark) 0xFF0A0C10.toInt() else 0xFFF8F4EE.toInt()
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.color = if (isDark) 0xFF20262E.toInt() else 0xFFFAF9F5.toInt()
        c.drawRect(0f, 0f, w * 0.55f, h.toFloat(), p)
        p.color = if (isDark) 0xFF626669.toInt() else 0xFFC5C0B6.toInt()
        var y = 30f
        while (y < h) {
            c.drawRoundRect(RectF(20f, y, w * 0.5f, y + 50f), 4f, 4f, p)
            y += 110f
        }
        p.color = if (isDark) 0xFF1E4532.toInt() else 0xFFB6C8B1.toInt()
        c.drawRect(w * 0.72f, 0f, w.toFloat(), h * 0.5f, p)
        p.color = if (isDark) 0xFFBDBBB4.toInt() else 0xFFEFEDEA.toInt()
        p.strokeWidth = 14f
        c.drawLine(w * 0.6f, 0f, w * 0.64f, h.toFloat(), p)
    }
}
