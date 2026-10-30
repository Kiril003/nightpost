package local.phantom.companion.feature.stream.report

import local.phantom.companion.feature.stream.ui.report.ReportBlock
import local.phantom.companion.feature.stream.ui.report.ReportDoc
import local.phantom.companion.feature.stream.ui.report.ReportLayout
import local.phantom.companion.feature.stream.ui.report.ReportSection
import local.phantom.companion.feature.stream.ui.report.ReportStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Моделювання документа: порядок рядків, зміст, поточний розділ, прогрес.
 *
 * Це арифметика, від якої залежить, що написано в липкій шапці. Всередині
 * `@Composable` вона була б неперевірною — тут вона просто функції.
 *
 * Фікстури нижче — **синтетичні**: жодне число не видає себе за історію
 * оператора, вони існують лише щоб перевірити форму документа.
 */
class ReportLayoutTest {

    private fun prose(key: String) = ReportBlock.Prose(key = key, text = "текст")
    private fun heavy(key: String) = ReportBlock.RouteTrace(
        key = key,
        points = listOf(
            local.phantom.companion.feature.stream.ui.report.GeoPoint(50.45, 30.52),
            local.phantom.companion.feature.stream.ui.report.GeoPoint(50.46, 30.53),
        ),
    )

    private val doc = ReportDoc(
        id = "d1",
        title = "Документ",
        headline = listOf(ReportStat(label = "Відстань", value = "38,4", unit = "км")),
        sections = listOf(
            ReportSection(id = "a", title = "Перший", blocks = listOf(prose("p1"), heavy("h1"))),
            ReportSection(id = "b", title = "Другий", blocks = listOf(prose("p2"))),
        ),
        sources = listOf("GNSS"),
    )

    @Test
    fun `порядок рядків — шапка, числа, розділи, колофон`() {
        val rows = ReportLayout.flatten(doc)
        assertEquals(ReportLayout.Row.Masthead, rows[0])
        assertEquals(ReportLayout.Row.Headline, rows[1])
        assertTrue(rows[2] is ReportLayout.Row.SectionHead)
        assertTrue(rows[3] is ReportLayout.Row.BlockRow)
        assertTrue(rows[4] is ReportLayout.Row.BlockRow)
        assertTrue(rows[5] is ReportLayout.Row.SectionHead)
        assertEquals(ReportLayout.Row.Colophon, rows.last())
    }

    @Test
    fun `кожен блок — окремий рядок, інакше розділ склався б одним кадром`() {
        val rows = ReportLayout.flatten(doc)
        assertEquals(3, rows.count { it is ReportLayout.Row.BlockRow })
    }

    @Test
    fun `ключі рядків унікальні — LazyColumn на це спирається`() {
        val keys = ReportLayout.flatten(doc).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `порожній документ отримує окремий рядок порожнього стану`() {
        val rows = ReportLayout.flatten(ReportDoc(title = "Тиждень"))
        assertTrue(rows.contains(ReportLayout.Row.Empty))
        assertEquals(ReportLayout.Row.Masthead, rows.first())
    }

    @Test
    fun `документ із даними не показує порожнього стану`() {
        assertTrue(!ReportLayout.flatten(doc).contains(ReportLayout.Row.Empty))
    }

    @Test
    fun `колофон зʼявляється лише коли є що в ньому написати`() {
        val bare = ReportDoc(
            title = "Т",
            sections = listOf(ReportSection(id = "a", title = "Р", blocks = listOf(prose("p")))),
        )
        assertTrue(!ReportLayout.flatten(bare).contains(ReportLayout.Row.Colophon))
    }

    @Test
    fun `зміст указує на рядок заголовка розділу`() {
        val rows = ReportLayout.flatten(doc)
        val outline = ReportLayout.outline(doc)
        assertEquals(2, outline.size)
        assertEquals("Перший", outline[0].title)
        assertEquals(2, outline[0].blockCount)
        outline.forEach { entry ->
            val row = rows[entry.rowIndex]
            assertTrue(row is ReportLayout.Row.SectionHead && row.index == entry.index)
        }
    }

    @Test
    fun `поточний розділ — останній, що вже пройшов повз`() {
        val rows = ReportLayout.flatten(doc)
        // Ще в шапці документа.
        assertEquals(-1, ReportLayout.currentSection(rows, 0))
        assertEquals(-1, ReportLayout.currentSection(rows, 1))
        // Заголовок першого розділу.
        assertEquals(0, ReportLayout.currentSection(rows, 2))
        // Його блоки — усе ще перший розділ, а не «наступний визирнув».
        assertEquals(0, ReportLayout.currentSection(rows, 4))
        assertEquals(1, ReportLayout.currentSection(rows, 5))
    }

    @Test
    fun `поточний розділ не виходить за межі списку`() {
        val rows = ReportLayout.flatten(doc)
        assertEquals(1, ReportLayout.currentSection(rows, 999))
    }

    @Test
    fun `прогрес враховує видимі рядки, а не лише перший`() {
        // 10 рядків, видно з 0-го чотири — прочитано 40 %.
        assertEquals(0.4f, ReportLayout.progress(10, 0, 4), 1e-6f)
        // Останній рядок на екрані — документ прочитано, навіть якщо
        // скрол формально не в самому низу.
        assertEquals(1f, ReportLayout.progress(10, 6, 4), 1e-6f)
        assertEquals(1f, ReportLayout.progress(10, 9, 8), 1e-6f)
    }

    @Test
    fun `документ з одного рядка вважається прочитаним`() {
        assertEquals(1f, ReportLayout.progress(1, 0, 1), 1e-6f)
        assertEquals(1f, ReportLayout.progress(0, 0, 0), 1e-6f)
    }

    @Test
    fun `важкі блоки рахуються окремо — це план гідратації`() {
        assertEquals(1, ReportLayout.heavyBlockCount(doc))
    }
}
