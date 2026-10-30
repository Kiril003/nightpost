package local.phantom.companion.feature.stream.ui.map.icons

import local.phantom.companion.feature.stream.ui.map.MapPinFace
import local.phantom.companion.feature.stream.ui.map.MapSymbolPalette
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Храповик власної родини: емодзі не має права повернутись у шлях піна.
 *
 * Емодзі поверталось не злим наміром, а зручністю — один рядок замість
 * двадцяти чисел. Ціна цього рядка: вигляд мапи вирішує системний шрифт
 * телефона, гліф не перефарбовується, не вирівнюється й не масштабується, а
 * на апараті без потрібного шрифту стає порожнім квадратом.
 */
class PinPathPurityTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private val base = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map"

    private fun pinPathSources(): List<File> {
        val named = listOf(
            "POIOverlay.kt",
            "SavedPlacesOverlay.kt",
            "MapAlertLayers.kt",
            "EventMarksLayer.kt",
            "transit/TransitVehicleMarkerOverlay.kt",
        ).map { File(repoRoot(), "$base/$it") }
        val family = File(repoRoot(), "$base/icons").walkTopDown().filter { it.extension == "kt" }
        val all = named + family
        all.forEach { assertTrue("${it.name} не знайдено — храповик уже нічого не боронить", it.isFile) }
        return all
    }

    /**
     * Діапазони, у яких живуть кольорові емодзі. Кирилиця, лапки-ялинки, тире
     * й стрілки коментарів лежать нижче й свідомо не чіпаються.
     */
    private fun emojiAt(text: String): List<String> {
        val hits = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val flagged = cp in 0x1F000..0x1FAFF ||
                cp in 0x2600..0x27BF ||
                cp in 0x2B00..0x2BFF ||
                cp == 0x24C2 ||
                cp == 0xFE0F
            if (flagged) hits += "U+%04X".format(cp)
            i += Character.charCount(cp)
        }
        return hits
    }

    @Test
    fun `not one emoji codepoint survives in the pin path`() {
        pinPathSources().forEach { file ->
            val hits = emojiAt(file.readText())
            assertTrue("${file.name} несе емодзі: ${hits.distinct()}", hits.isEmpty())
        }
    }

    /**
     * Кодування — половина захисту: емодзі потрапляє на пін через МАЛЮВАННЯ
     * ТЕКСТУ, тож заборонено саме воно. Шлях, у якому нема `drawText`, не має
     * куди підставити системний шрифт.
     */
    @Test
    fun `no map symbol is drawn as text any more`() {
        pinPathSources().forEach { file ->
            val text = file.readText()
            assertTrue("${file.name} малює символ текстом", !text.contains("drawText"))
            assertTrue("${file.name} міряє гліф як шрифт", !text.contains("getTextBounds"))
        }
    }

    /**
     * Доктрина честі (D8, закон 1), польова редакція 23.08: модельної одиниці
     * на склі немає ЗОВСІМ.
     *
     * Тильда й приглушення були компромісом — позначкою «цієї машини, може, і
     * немає». Власник проїхав місто й не зустрів жодної з них: мітку, до якої
     * не можна підійти, людина читає як обіцянку, хоч як її познач. Тому фільтр
     * стоїть перед розкладкою, а не в оздобленні пігулки.
     */
    @Test
    fun `the glass takes observed fixes only`() {
        val text = File(repoRoot(), "$base/transit/TransitVehicleMarkerOverlay.kt").readText()
        assertTrue(
            "зник фільтр живих позицій — на скло знову може заїхати розклад",
            text.contains("glassFleet("),
        )
        assertTrue(
            "повернулась тильда модельної одиниці замість її відсутності",
            !text.contains("map_transit_simulated_mark"),
        )
    }

    /* ── Тон ярусу читається на плашці, а не тільки на землі ─────────────── */

    /**
     * Кільце і гліф лежать УСЕРЕДИНІ плашки, тож міряти їх треба проти неї.
     * Старі значення цього не проходили: бурштин АЗС давав 1.76:1 — кільце
     * «пальне · транспорт · пошта» було майже невидиме на своєму ж піні.
     *
     * Земля береться найтемніша можлива: плашка 95%-непрозора, тож саме над
     * чорнотою вона композитується найтемніше, і саме там тонові найтісніше.
     */
    @Test
    fun `every pin tier reads on the plate it sits on, in both polarities`() {
        val plate = composite(MapSymbolPalette.DARK.plate, 0x000000)
        listOf(true, false).forEach { isDark ->
            val p = MapSymbolPalette.resolve(isDark)
            mapOf(
                "критичний" to p.pinCritical,
                "тримає рух" to p.pinSustain,
                "звичайний" to p.pinOrdinary,
            ).forEach { (name, tint) ->
                assertTrue(
                    "$name тон (isDark=$isDark) на плашці — ${ratio(tint, plate)}:1",
                    contrast(tint, plate) >= 3.0,
                )
            }
        }
    }

    /** Гліф значка загрози іде чорнилом саме тому, що тони тривоги плашку не беруть. */
    @Test
    fun `the ink glyph of a badge always clears its plate`() {
        val plate = composite(MapSymbolPalette.DARK.plate, 0x000000)
        assertTrue(
            "чорнило на плашці — ${ratio(MapSymbolPalette.DARK.plateInk, plate)}:1",
            contrast(MapSymbolPalette.DARK.plateInk, plate) >= 4.5,
        )
    }

    /** Коробка гліфа мусить вміститись у поле, яке лишили обід і кільце. */
    @Test
    fun `the glyph box fits inside the ring, corners included`() {
        val field = MapPinFace.CONTENT_DP - 2 * (MapPinFace.RIM_DP + MapPinFace.RING_DP)
        val live = MapPinFace.GLYPH_DP * (GlyphGrid.MAX_U - GlyphGrid.MIN_U) / GlyphGrid.SIZE
        val diagonal = live * kotlin.math.sqrt(2f)
        assertTrue("діагональ живого поля ${diagonal}dp проти ${field}dp кільця", diagonal <= field)
    }

    private fun composite(argb: Int, ground: Int): Int {
        val alpha = ((argb ushr 24) and 0xFF) / 255f
        fun mix(shift: Int): Int {
            val f = (argb shr shift) and 0xFF
            val b = (ground shr shift) and 0xFF
            return (f * alpha + b * (1 - alpha)).toInt()
        }
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun ratio(a: Int, b: Int) = "%.2f".format(contrast(a, b))
}
