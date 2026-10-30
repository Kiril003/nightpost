package local.phantom.companion.feature.stream.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefingFactsTest {

    @Test
    fun `a closing time is stated, not implied by raw hours`() {
        assertEquals(
            "відчинено, зачиняється о 18:00",
            BriefingFacts.openLine(open = true, boundaryMinuteOfDay = 18 * 60),
        )
    }

    @Test
    fun `open with no known boundary claims no boundary`() {
        assertEquals("відчинено", BriefingFacts.openLine(open = true, boundaryMinuteOfDay = null))
    }

    @Test
    fun `closed says when it opens when that is known`() {
        assertEquals(
            "зачинено, відчиняється о 09:30",
            BriefingFacts.openLine(open = false, boundaryMinuteOfDay = 9 * 60 + 30),
        )
    }

    @Test
    fun `midnight and wrap-around both read as a clock`() {
        assertEquals("00:00", BriefingFacts.minuteClock(0))
        assertEquals("00:05", BriefingFacts.minuteClock(1445))
        assertEquals("23:59", BriefingFacts.minuteClock(1439))
    }

    @Test
    fun `nothing similar nearby says nothing at all`() {
        assertNull(BriefingFacts.similarLine(0, "Аврора", 120.0))
    }

    @Test
    fun `the nearest alternative arrives with its distance`() {
        assertEquals(
            "3 поруч, найближче — Аврора за 120 м",
            BriefingFacts.similarLine(3, "Аврора", 123.0),
        )
    }

    @Test
    fun `a count without a nearest name still counts`() {
        assertEquals("2 поруч", BriefingFacts.similarLine(2, null, null))
    }

    @Test
    fun `far alternatives switch to kilometres`() {
        val line = BriefingFacts.similarLine(1, "Сільпо", 2_400.0)
        assertTrue(line!!.endsWith("2,4 км") || line.endsWith("2.4 км"))
    }

    @Test
    fun `distance rounds to something worth saying out loud`() {
        assertEquals("120 м", BriefingFacts.distance(50.0, 30.0, 50.0 + 0.00112, 30.0))
    }
}
