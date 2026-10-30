package local.phantom.companion.feature.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home grid, which a driver glances at once and at speed. What he can tell
 * before reading anything, and what the tile is allowed to claim, are pinned
 * here — the templates themselves are drawn by the host and cannot be tested.
 */
class CarHomeGridTest {

    @Test
    fun `every category is a different shape, not the same pin four times`() {
        val glyphs = CarCategory.entries.map { it.glyph }
        assertEquals(
            "identical tiles force the driver back onto reading all four labels",
            CarCategory.entries.size,
            glyphs.toSet().size,
        )
    }

    @Test
    fun `not knowing where he is never looks like there being nothing there`() {
        // The failure this exists to prevent: an underground car park, no fix,
        // four blank tiles, and a driver who reads «поруч заправок немає» and
        // passes the last station before 40 km of nothing.
        val noFix = CarCopy.gridSubtitle(Nearest.NoFix)
        val notFound = CarCopy.gridSubtitle(Nearest.NotFound)
        val asking = CarCopy.gridSubtitle(Nearest.Asking)

        assertNull("only the transient state may be silent", asking)
        assertEquals("no fix must say so out loud", "чекаю сигнал", noFix)
        assertEquals(3, setOf(noFix, notFound, asking).size)
    }

    @Test
    fun `nothing found testifies about the gazetteer, not about the world`() {
        // PHANTOM can say it did not find one. It cannot say there is none —
        // it does not know whether this area was ever mapped, and the tile has
        // no room to explain the difference.
        val text = CarCopy.gridSubtitle(Nearest.NotFound)!!
        assertTrue("must not assert an absence in the world", !text.contains("немає"))
        assertEquals("не знайшов", text)
    }

    @Test
    fun `only a found category is allowed to be a number`() {
        assertEquals("3,4 км", CarCopy.gridSubtitle(Nearest.Found(3_412.0)))
        assertEquals("350 м", CarCopy.gridSubtitle(Nearest.Found(347.0)))
        assertEquals("38 км", CarCopy.gridSubtitle(Nearest.Found(37_800.0)))
    }

    @Test
    fun `a number computed once would go stale, so the grid keeps asking`() {
        assertTrue("must re-ask while the car is moving", CarCopy.GRID_REFRESH_MS > 0)
        assertTrue("but not often enough to be a battery drain", CarCopy.GRID_REFRESH_MS >= 30_000)
    }

    @Test
    fun `rows are ordered by distance, whatever order the search returned`() {
        val far = place(id = 1, metres = 12_000.0)
        val near = place(id = 2, metres = 400.0)
        val mid = place(id = 3, metres = 3_000.0)
        assertEquals(
            listOf(2L, 3L, 1L),
            nearbyFirst(listOf(far, near, mid)).map { it.id },
        )
    }

    @Test
    fun `a place with no usable distance goes last and says so on its own row`() {
        // Not a sentinel quietly sorted to one end: the driver has to be able
        // to see why that row is at the bottom.
        val unknown = place(id = 9, metres = Double.NaN)
        val ordered = nearbyFirst(listOf(unknown, place(id = 1, metres = 5_000.0)))
        assertEquals(listOf(1L, 9L), ordered.map { it.id })
        assertTrue(CarCopy.subtitle(unknown).contains("невідома"))
        assertEquals("відстань невідома", CarCopy.distanceOrUnknown(-1.0))
    }

    @Test
    fun `an empty result survives ordering`() {
        assertTrue(nearbyFirst(emptyList()).isEmpty())
    }

    private fun place(id: Long, metres: Double) =
        CarPlace(id = id, name = "x", lat = 50.0, lon = 30.0, distanceMeters = metres)
}
