package local.phantom.companion.feature.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Where the line goes on a car screen. A projection that is subtly wrong looks
 * plausible and points a driver at the wrong road, so every claim the surface
 * relies on is pinned here rather than checked by eye on a head unit.
 */
class CarMapProjectionTest {

    private val kyivLat = 50.4501
    private val kyivLon = 30.5234

    private fun cam(bearing: Double = 0.0, span: Double = 600.0) = CarMapProjection.Camera(
        lat = kyivLat, lon = kyivLon, bearingDeg = bearing,
        widthPx = 800, heightPx = 480, spanM = span,
    )

    @Test
    fun `the driver sits low on the surface, not in the middle`() {
        val c = cam()
        val here = CarMapProjection.project(c, kyivLat, kyivLon)
        assertEquals(400f, here.x, 0.5f)
        assertEquals(480f * CarMapProjection.PUCK_Y_FRACTION, here.y, 0.5f)
        assertTrue("most of the surface must be road ahead", here.y > 480f / 2)
    }

    @Test
    fun `heading up — a point straight ahead is straight up the screen`() {
        // 100 m north, driving north.
        val ahead = kyivLat + 100.0 / 111_320.0
        val p = CarMapProjection.project(cam(bearing = 0.0), ahead, kyivLon)
        assertEquals("no sideways drift", 400f, p.x, 1f)
        assertTrue("ahead must be above the puck", p.y < 480f * CarMapProjection.PUCK_Y_FRACTION)
    }

    @Test
    fun `driving east puts the eastern point ahead, not to the right`() {
        val east = kyivLon + 100.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(kyivLat)))
        val p = CarMapProjection.project(cam(bearing = 90.0), kyivLat, east)
        assertEquals(400f, p.x, 2f)
        assertTrue(p.y < 480f * CarMapProjection.PUCK_Y_FRACTION)
    }

    @Test
    fun `a point behind falls below the puck`() {
        val behind = kyivLat - 100.0 / 111_320.0
        val p = CarMapProjection.project(cam(bearing = 0.0), behind, kyivLon)
        assertTrue("behind must render behind", p.y > 480f * CarMapProjection.PUCK_Y_FRACTION)
    }

    @Test
    fun `the span is what fits ahead of the driver`() {
        val c = cam(span = 600.0)
        val edge = kyivLat + 600.0 / 111_320.0
        val p = CarMapProjection.project(c, edge, kyivLon)
        assertEquals("600 m ahead lands at the top edge", 0f, p.y, 2f)
    }

    @Test
    fun `zoom opens up with speed`() {
        val slow = CarMapProjection.spanForSpeed(10)
        val town = CarMapProjection.spanForSpeed(50)
        val motorway = CarMapProjection.spanForSpeed(110)
        assertTrue(slow < town && town < motorway)
        assertEquals(CarMapProjection.DEFAULT_SPAN_M, CarMapProjection.spanForSpeed(null), 0.01)
    }

    @Test
    fun `the nearest point on the line is where the route splits`() {
        val line = (0..10).map { kyivLat + it * 0.001 to kyivLon }
        val at = CarMapProjection.nearestIndex(line, kyivLat + 0.0049, kyivLon)
        assertEquals(5, at)
    }

    @Test
    fun `an empty line has no nearest point and does not throw`() {
        assertEquals(0, CarMapProjection.nearestIndex(emptyList(), kyivLat, kyivLon))
    }

    @Test
    fun `a route that never comes near the camera draws nothing`() {
        val faraway = listOf(0.0 to 0.0, 0.1 to 0.1)
        assertTrue(CarMapProjection.visibleSlice(cam(), faraway).isEmpty())
    }

    @Test
    fun `the slice keeps one point past the edge so the line enters the screen`() {
        val line = (0..40).map { kyivLat + it * 0.002 to kyivLon }
        val slice = CarMapProjection.visibleSlice(cam(), line)
        assertTrue("must not clip to exactly what is inside", slice.size >= 2)
        val last = slice.last()
        val distM = abs(last.first - kyivLat) * 111_320.0
        assertTrue("the leaving point must be beyond the span", distM > 600.0)
    }

    @Test
    fun `a two-point line survives slicing untouched`() {
        val line = listOf(kyivLat to kyivLon, kyivLat + 0.001 to kyivLon)
        assertEquals(line, CarMapProjection.visibleSlice(cam(), line))
    }
}
