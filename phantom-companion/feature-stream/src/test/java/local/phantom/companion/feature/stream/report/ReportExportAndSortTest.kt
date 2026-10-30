package local.phantom.companion.feature.stream.report

import local.phantom.companion.feature.stream.ui.report.Column
import local.phantom.companion.feature.stream.ui.report.GeoPoint
import local.phantom.companion.feature.stream.ui.report.ReportBlock
import local.phantom.companion.feature.stream.ui.report.ReportDoc
import local.phantom.companion.feature.stream.ui.report.ReportExport
import local.phantom.companion.feature.stream.ui.report.ReportSection
import local.phantom.companion.feature.stream.ui.report.ReportStat
import local.phantom.companion.feature.stream.ui.report.ReportTableSort
import local.phantom.companion.feature.stream.ui.report.Slice
import local.phantom.companion.feature.stream.ui.report.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Експорт і сортування — дві дії, які оператор виконує над документом, а
 * не над картинкою. Обидві чисті, обидві пришпилені.
 */
class ReportExportAndSortTest {

    /* ── Експорт ─────────────────────────────────────────────────── */

    private val doc = ReportDoc(
        id = "d",
        kicker = "інцидент",
        title = "Підміна GPS",
        period = "21 липня 2026",
        lede = "Супутникове рішення стрибнуло за океан.",
        headline = listOf(ReportStat(label = "Розбіжність", value = "12 278", unit = "км")),
        sections = listOf(
            ReportSection(
                id = "s1",
                title = "Хронологія",
                blocks = listOf(
                    ReportBlock.Timeline(
                        key = "t",
                        events = listOf(
                            TimelineEvent(time = "14:02:11", title = "Стрибок рішення"),
                        ),
                    ),
                ),
            ),
        ),
        sources = listOf("GNSS raw"),
        methodology = listOf("Розбіжність — гаверсинус між рішеннями."),
        generatedAt = "21.07.2026 14:40",
    )

    @Test
    fun `експорт містить шапку, розділи, джерела і методологію`() {
        val text = ReportExport.asText(doc)
        assertTrue(text.contains("ІНЦИДЕНТ"))
        assertTrue(text.contains("Підміна GPS"))
        assertTrue(text.contains("21 липня 2026"))
        assertTrue(text.contains("1. Хронологія"))
        assertTrue(text.contains("14:02:11"))
        assertTrue(text.contains("ДЖЕРЕЛА"))
        assertTrue(text.contains("МЕТОДОЛОГІЯ"))
        assertTrue(text.contains("Згенеровано: 21.07.2026 14:40"))
    }

    @Test
    fun `геометрія експортується описом, а не псевдографікою`() {
        // ASCII-діаграма виглядає як дані, але зіставити її з оригіналом
        // неможливо — тому слід стає описом із кількістю точок.
        val trace = ReportBlock.RouteTrace(
            key = "r",
            title = "Слід",
            points = List(3) { GeoPoint(50.45 + it * 0.001, 30.52) },
        )
        val out = ReportExport.blockText(trace)
        assertEquals("[Слід — точки сліду: 3]", out)
    }

    @Test
    fun `кільце експортується частками, бо частки — це і є його зміст`() {
        val donut = ReportBlock.Donut(
            key = "d",
            slices = listOf(Slice("пальне", 75f), Slice("паркінг", 25f)),
        )
        val out = ReportExport.blockText(donut)
        assertTrue(out.contains("пальне"))
        assertTrue(out.contains("75"))
        assertTrue(out.contains("25"))
    }

    @Test
    fun `порожній документ експортується без вигаданих даних`() {
        val text = ReportExport.asText(ReportDoc(title = "Тиждень"))
        assertTrue(text.contains("Тиждень"))
        assertTrue(!text.contains("0 км"))
        assertTrue(!text.contains("ДЖЕРЕЛА"))
    }

    /* ── Сортування таблиці ──────────────────────────────────────── */

    private val columns = listOf(
        Column(label = "АЗС"),
        Column(label = "Ціна", align = "end", numeric = true),
    )
    private val rows = listOf(
        listOf("WOG", "54,9"),
        listOf("OKKO", "9,1"),
        listOf("Укрнафта", "12,4"),
    )

    @Test
    fun `числова колонка сортується як число, а не як рядок`() {
        // Рядкове порівняння поставило б «9,1» після «54,9» — таблиця
        // збрехала б саме там, де їй найбільше довіряють.
        val desc = ReportTableSort.sort(rows, column = 1, ascending = false, columns = columns)
        assertEquals(listOf("WOG", "Укрнафта", "OKKO"), desc.map { it[0] })

        val asc = ReportTableSort.sort(rows, column = 1, ascending = true, columns = columns)
        assertEquals(listOf("OKKO", "Укрнафта", "WOG"), asc.map { it[0] })
    }

    @Test
    fun `текстова колонка сортується за абеткою без урахування регістру`() {
        val asc = ReportTableSort.sort(rows, column = 0, ascending = true, columns = columns)
        assertEquals("OKKO", asc.first()[0])
    }

    @Test
    fun `без вибраної колонки порядок не змінюється`() {
        assertEquals(rows, ReportTableSort.sort(rows, -1, ascending = true, columns = columns))
        assertEquals(rows, ReportTableSort.sort(rows, 9, ascending = true, columns = columns))
    }

    @Test
    fun `число витягується з відформатованої комірки`() {
        assertEquals(12270.0, ReportTableSort.numberOf("12 270 км"), 1e-6)
        assertEquals(-4.2, ReportTableSort.numberOf("−4,2 %"), 1e-6)
        assertEquals(54.9, ReportTableSort.numberOf("54,9"), 1e-6)
    }

    @Test
    fun `комірка без числа не ламає порівняння`() {
        assertEquals(Double.NEGATIVE_INFINITY, ReportTableSort.numberOf("—"), 0.0)
    }
}
