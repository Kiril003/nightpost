package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-1 — pins RouteIntel's pure corridor math. */
class RouteIntelMathTest {

    // ~1 km straight west→east line at lat 50 (0.014° lon ≈ 1000 m).
    private val kmLine = listOf(50.0 to 30.0, 50.0 to 30.014)

    @Test
    fun `samplePolyline keeps endpoints and honours maxSamples`() {
        val samples = samplePolyline(
            listOf(50.0 to 30.0, 50.0 to 30.007, 50.0 to 30.014),
            maxSamples = 11,
            minStepMeters = 25.0,
        )
        assertTrue(samples.size in 3..11)
        assertEquals(50.0 to 30.0, samples.first())
        assertEquals(50.0 to 30.014, samples.last())
        // Interior samples are strictly increasing in lon (equidistant walk).
        val lons = samples.map { it.second }
        assertEquals(lons, lons.sorted())
    }

    @Test
    fun `samplePolyline never denser than minStepMeters`() {
        // 100 m line, asking for 48 samples → step clamps to 25 m → ≤ 6 points.
        val short = listOf(50.0 to 30.0, 50.0 to 30.0014)
        val samples = samplePolyline(short, maxSamples = 48, minStepMeters = 25.0)
        assertTrue("got ${samples.size}", samples.size <= 6)
    }

    @Test
    fun `distanceToSegmentMeters measures perpendicular offset`() {
        // Point 0.001° lat (~110 m) north of the segment's midpoint.
        val d = distanceToSegmentMeters(50.001, 30.007, 50.0, 30.0, 50.0, 30.014)
        assertEquals(110.5, d, 2.0)
        // Point past the end clamps to the endpoint.
        val dEnd = distanceToSegmentMeters(50.0, 30.02, 50.0, 30.0, 50.0, 30.014)
        assertTrue(dEnd > 400.0)
    }

    @Test
    fun `nearestWay attributes a sample to the closer way within the cap`() {
        val near = OverpassElement(
            id = 1, type = "way", tags = mapOf("highway" to "residential"),
            geometry = listOf(OverpassCenter(50.0001, 30.0), OverpassCenter(50.0001, 30.014)),
        )
        val far = OverpassElement(
            id = 2, type = "way", tags = mapOf("highway" to "primary"),
            geometry = listOf(OverpassCenter(50.01, 30.0), OverpassCenter(50.01, 30.014)),
        )
        val hit = nearestWay(50.0, 30.007, listOf(far, near), maxMeters = 35.0)
        assertEquals(1L, hit?.id)
        // Nothing within the cap → honest null, sample stays unmatched.
        assertNull(nearestWay(50.005, 30.007, listOf(far, near), maxMeters = 35.0))
    }

    @Test
    fun `tallyShares sums to one and sorts descending`() {
        val shares = tallyShares(listOf("а", "б", "а", "а"))
        assertEquals("а" to 0.75, shares[0])
        assertEquals("б" to 0.25, shares[1])
        assertEquals(1.0, shares.sumOf { it.second }, 1e-9)
        assertTrue(tallyShares(emptyList()).isEmpty())
    }

    @Test
    fun `elevationGain ignores jitter under the noise floor`() {
        val (ascent, descent) = elevationGain(listOf(100.0, 101.0, 105.0, 103.0, 110.0), noiseMeters = 2.0)
        assertEquals(12.0, ascent, 1e-9)  // 100→105 (+5), 103→110 (+7)
        assertEquals(2.0, descent, 1e-9)  // 105→103 (−2)
        assertEquals(0.0 to 0.0, elevationGain(emptyList()))
    }
}
