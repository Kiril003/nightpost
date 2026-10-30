package local.phantom.companion.self

import org.junit.Assert.assertEquals
import org.junit.Test

class VisitTextTest {

    @Test
    fun `reminder visit is only the person's own words`() {
        val v = reminderVisit("7", "Подзвонити мамі")
        assertEquals("", v.why)
        assertEquals("Подзвонити мамі", visitText(v, "\n\n"))
    }

    @Test
    fun `lesson keeps its reason line`() {
        val v = Initiative.Visit("l", Initiative.Kind.LESSON, "l", "Повторимо цикли?", "Учора цикли хитались.", 0L)
        assertEquals("Повторимо цикли?\n\nУчора цикли хитались.", visitText(v, "\n\n"))
    }
}
