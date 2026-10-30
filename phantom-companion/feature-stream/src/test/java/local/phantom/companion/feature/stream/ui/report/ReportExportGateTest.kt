package local.phantom.companion.feature.stream.ui.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Платимо за винесення документа, не за його читання. Тому замок стоїть рівно
 * на дорозі в буфер обміну — і ніде більше.
 */
class ReportExportGateTest {

    private val doc = ReportDoc(
        id = "trip-2026-08",
        kicker = "ЗВІТ ПРО ПОЇЗДКУ",
        title = "Київ — Львів",
        period = "12 серпня",
        lede = "Шість годин, одна зупинка.",
        sections = listOf(
            ReportSection(
                title = "Дорога",
                lede = "Траса М06.",
                blocks = listOf(ReportBlock.Prose(text = "Виїхали о шостій.")),
            ),
        ),
        sources = listOf("бортовий журнал"),
    )

    @Test
    fun `without a key not one line of the document reaches the clipboard`() {
        assertNull(ReportExport.forClipboard(doc, entitled = false))
    }

    @Test
    fun `with a key the whole document goes out`() {
        val text = ReportExport.forClipboard(doc, entitled = true)

        assertNotNull(text)
        assertEquals(ReportExport.asText(doc), text)
        assertTrue(text!!.contains("Київ — Львів"))
        assertTrue(text.contains("Виїхали о шостій."))
        assertTrue(text.contains("бортовий журнал"))
    }

    @Test
    fun `the report itself is untouched by the lock — it stays readable`() {
        // Деградація, не ампутація: документ рендериться з тих самих рядків,
        // і замок не має права зробити його порожнім чи коротшим.
        val locked = ReportLayout.flatten(doc)
        val body = ReportExport.asText(doc)

        assertTrue("документ під замком втратив рядки", locked.isNotEmpty())
        assertTrue(body.contains("Виїхали о шостій."))
        assertEquals(ReportLayout.flatten(doc).size, locked.size)
    }

    @Test
    fun `an empty document does not become a way around the gate`() {
        assertNull(ReportExport.forClipboard(ReportDoc(), entitled = false))
    }
}
