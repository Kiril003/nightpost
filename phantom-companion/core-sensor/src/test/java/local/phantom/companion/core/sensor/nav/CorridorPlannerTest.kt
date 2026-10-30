package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorridorPlannerTest {

    private val kyivCenter = 50.4501 to 30.5234
    private val kyivNorth = 50.5200 to 30.4900

    @Test
    fun `sample keeps endpoints and respects step`() {
        val pts = CorridorPlanner.sample(listOf(kyivCenter, kyivNorth), stepM = 500.0)
        assertEquals(kyivCenter, pts.first())
        assertEquals(kyivNorth, pts.last())
        for (i in 1 until pts.size) {
            val d = haversineM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
            assertTrue("step $d m too long", d <= 600.0)
        }
    }

    @Test
    fun `corridorCells covers the whole route without duplicates`() {
        val plan = CorridorPlanner.corridorCells(listOf(kyivCenter, kyivNorth))
        assertFalse(plan.truncated)
        assertTrue(plan.cells.isNotEmpty())
        assertEquals(plan.cells.size, plan.cells.toSet().size)
        // every sampled route point must fall inside some planned cell
        for ((lat, lon) in CorridorPlanner.sample(listOf(kyivCenter, kyivNorth))) {
            val hit = plan.cells.any { cell ->
                val b = CorridorPlanner.cellBounds(cell)
                lat >= b.minLat && lat <= b.maxLat && lon >= b.minLon && lon <= b.maxLon
            }
            assertTrue("point $lat,$lon uncovered", hit)
        }
    }

    @Test
    fun `corridorCells truncates a continental route and says so`() {
        val plan = CorridorPlanner.corridorCells(listOf(50.45 to 30.52, 49.84 to 24.03), maxCells = 40)
        assertTrue(plan.truncated)
        assertEquals(40, plan.cells.size)
    }

    @Test
    fun `cellBounds inverts cellsCovering packing, negative hemispheres included`() {
        for ((lat, lon) in listOf(50.4501 to 30.5234, -33.9 to 151.2, 50.45 to -0.2, -0.01 to -0.01)) {
            val cells = RoadMeshStore.cellsCovering(lat, lon, lat, lon)
            assertEquals(1, cells.size)
            val b = CorridorPlanner.cellBounds(cells[0])
            assertTrue(lat >= b.minLat && lat <= b.maxLat)
            assertTrue(lon >= b.minLon && lon <= b.maxLon)
        }
    }

    @Test
    fun `tileBoxes chain covers route and respects cap`() {
        val boxes = CorridorPlanner.tileBoxes(listOf(kyivCenter, 49.84 to 24.03))
        assertTrue(boxes.isNotEmpty())
        assertTrue(boxes.size <= CorridorPlanner.MAX_TILE_BOXES)
        for ((lat, lon) in CorridorPlanner.sample(listOf(kyivCenter, 49.84 to 24.03), 2000.0)) {
            assertTrue(
                "route point escaped every tile box",
                boxes.any { lat >= it.minLat && lat <= it.maxLat && lon >= it.minLon && lon <= it.maxLon },
            )
        }
    }

    @Test
    fun `estimateTiles grows with zoom and is sane for one city box`() {
        val box = listOf(CorridorPlanner.Box(50.40, 30.45, 50.50, 30.60))
        val low = CorridorPlanner.estimateTiles(box, 9, 11)
        val full = CorridorPlanner.estimateTiles(box, 9, 14)
        assertTrue(low in 1 until full)
        assertTrue("estimate $full absurd for one city", full < 5_000)
    }
}
