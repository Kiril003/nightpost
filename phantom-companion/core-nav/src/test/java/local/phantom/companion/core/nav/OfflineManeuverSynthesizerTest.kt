package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineManeuverSynthesizerTest {

    @Test
    fun `empty and single point inputs return empty steps`() {
        assertTrue(OfflineManeuverSynthesizer.synthesize(emptyList()).isEmpty())
        assertTrue(OfflineManeuverSynthesizer.synthesize(listOf(50.45 to 30.52)).isEmpty())
    }

    @Test
    fun `straight line path creates depart and arrive steps`() {
        val points = listOf(
            50.4500 to 30.5200,
            50.4550 to 30.5200,
            50.4600 to 30.5200,
        )
        val steps = OfflineManeuverSynthesizer.synthesize(points)
        assertEquals(2, steps.size)
        assertEquals("depart", steps[0].type)
        assertEquals("arrive", steps[1].type)
        assertTrue(steps[1].distanceMeters > 500.0)
    }

    @Test
    fun `right turn path creates turn step with right modifier`() {
        val points = listOf(
            50.4500 to 30.5200, // heading north
            50.4550 to 30.5200, // turn right (east)
            50.4550 to 30.5300,
        )
        val steps = OfflineManeuverSynthesizer.synthesize(points)
        assertEquals(3, steps.size)
        assertEquals("depart", steps[0].type)
        assertEquals("turn", steps[1].type)
        assertEquals("right", steps[1].modifier)
        assertEquals("arrive", steps[2].type)
        assertNotNull(steps[1].bearingAfterDeg)
    }

    @Test
    fun `left turn path creates turn step with left modifier`() {
        val points = listOf(
            50.4500 to 30.5200, // heading north
            50.4550 to 30.5200, // turn left (west)
            50.4550 to 30.5100,
        )
        val steps = OfflineManeuverSynthesizer.synthesize(points)
        assertEquals(3, steps.size)
        assertEquals("depart", steps[0].type)
        assertEquals("turn", steps[1].type)
        assertEquals("left", steps[1].modifier)
        assertEquals("arrive", steps[2].type)
    }
}
