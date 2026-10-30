package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGridTest {

    // ~110 m square building at Kyiv center
    private val building = CoverGrid.Companion.Footprint(
        ring = listOf(
            50.4500 to 30.5230,
            50.4510 to 30.5230,
            50.4510 to 30.5245,
            50.4500 to 30.5245,
        ),
        heightM = 24f,
    )

    @Test
    fun `wall cells score full cover, far ground none`() {
        val grid = CoverGrid.build(listOf(building))
        assertTrue(grid.cellCount > 0)
        assertEquals(1.0, grid.coverAt(50.4500, 30.5230), 1e-9)
        assertEquals(0.0, grid.coverAt(50.4600, 30.5400), 1e-9)
    }

    @Test
    fun `adjacent ring gets partial credit`() {
        val grid = CoverGrid.build(listOf(building))
        // one cell south of the wall
        val adj = grid.coverAt(50.4500 - CoverGrid.CELL_DEG, 30.5230)
        assertEquals(CoverGrid.ADJACENT_FACTOR.toDouble(), adj, 1e-6)
    }

    @Test
    fun `low shed shields less than a slab`() {
        val shed = building.copy(heightM = 3f)
        val grid = CoverGrid.build(listOf(shed))
        val w = grid.coverAt(50.4500, 30.5230)
        assertTrue(w in 0.3..0.4)
    }

    @Test
    fun `openFractionAlong separates a wall-hug from open steppe`() {
        val grid = CoverGrid.build(listOf(building))
        val hug = grid.openFractionAlong(listOf(50.4500 to 30.5230, 50.4510 to 30.5230))
        val steppe = grid.openFractionAlong(listOf(50.4600 to 30.5400, 50.4650 to 30.5500))
        assertTrue(hug < 0.2)
        assertEquals(1.0, steppe, 1e-9)
    }

    @Test
    fun `costFactor punishes open ground only`() {
        val grid = CoverGrid.build(listOf(building))
        assertEquals(1.0, grid.costFactorAt(50.4500, 30.5230, 0.8), 1e-6)
        assertEquals(1.8, grid.costFactorAt(50.4600, 30.5400, 0.8), 1e-6)
    }

    @Test
    fun `degenerate rings are ignored`() {
        val grid = CoverGrid.build(
            listOf(CoverGrid.Companion.Footprint(listOf(50.45 to 30.52, 50.46 to 30.53), 10f)),
        )
        assertEquals(0, grid.cellCount)
    }
}
