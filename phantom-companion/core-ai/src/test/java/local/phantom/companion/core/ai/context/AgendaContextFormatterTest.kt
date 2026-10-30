package local.phantom.companion.core.ai.context

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A refused calendar and a free day are different facts. Collapsed into one,
 * the model plans over a meeting it was never shown and sounds certain doing
 * it — which is the failure this whole screen was built to avoid.
 */
class AgendaContextFormatterTest {

    @Test
    fun `no permission is not an empty day`() {
        assertEquals("[AGENDA:unavailable]", AgendaContextFormatter.format(null))
        assertEquals("[AGENDA:empty]", AgendaContextFormatter.format(emptyList()))
    }

    @Test
    fun `an entry carries its hour, title and place`() {
        val out = AgendaContextFormatter.format(
            listOf(AgendaEntry("Зустріч з командою", "14:05", "Кафедральна 5")),
        )
        assertEquals("[AGENDA:14:05 Зустріч з командою @Кафедральна 5]", out)
    }

    @Test
    fun `an all-day entry says so instead of inventing an hour`() {
        val out = AgendaContextFormatter.format(listOf(AgendaEntry("Відпустка", null, null)))
        assertEquals("[AGENDA:весь день Відпустка]", out)
    }

    @Test
    fun `several entries stay in the order the calendar gave them`() {
        val out = AgendaContextFormatter.format(
            listOf(
                AgendaEntry("Зустріч", "09:00", null),
                AgendaEntry("Посилка", "18:30", null),
            ),
        )
        assertEquals("[AGENDA:09:00 Зустріч; 18:30 Посилка]", out)
    }
}
