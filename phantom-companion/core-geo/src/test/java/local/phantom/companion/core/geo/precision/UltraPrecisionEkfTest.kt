package local.phantom.companion.core.geo.precision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraPrecisionEkfTest {

    @Test
    fun `ekf produces smooth location fixes and reduces uncertainty`() {
        val ekf = UltraPrecisionEkfEngine()
        val fix1 = ekf.updateGnssFix(50.4501, 30.5234, 150.0, 10.0f, 5.0f, 90f, 1000L)
        assertNotNull(fix1)
        assertEquals(50.4501, fix1.latitude, 0.0001)

        val fix2 = ekf.updateGnssFix(50.4502, 30.5236, 150.0, 5.0f, 5.0f, 90f, 2000L)
        assertTrue(fix2.accuracyMeters <= fix1.accuracyMeters)
    }

    @Test
    fun `hmm road matcher snaps raw location to nearest segment`() {
        val road = RoadSegment(
            id = "seg_1",
            name = "Вулиця Хрещатик",
            startLat = 50.4475,
            startLon = 30.5220,
            endLat = 50.4510,
            endLon = 30.5240,
        )
        val matcher = HmmRoadMatcher(listOf(road))
        val result = matcher.match(50.4490, 30.5230, 45f)

        assertTrue(result.isMatched)
        assertEquals("Вулиця Хрещатик", result.roadName)
        assertTrue(result.distanceToSegmentMeters < 25.0)
    }

    @Test
    fun `bearing smoother eliminates micro jitter`() {
        val smoother = BearingSmoother(alpha = 0.3f)
        val b1 = smoother.filter(90f, 2f)
        assertEquals(90f, b1, 0.1f)

        val b2 = smoother.filter(90.2f, 2f)
        assertEquals(90f, b2, 0.1f) // Deadband suppresses micro jitter < 0.5 degrees
    }
}
