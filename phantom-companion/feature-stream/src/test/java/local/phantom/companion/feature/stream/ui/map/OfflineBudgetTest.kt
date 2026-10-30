package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile arithmetic behind the pack budget. Get it wrong in one direction and
 * a city pack is throttled to a useless zoom; wrong in the other and the device
 * carries gigabytes again.
 */
class OfflineBudgetTest {

    // Kyiv, roughly Obolon — the kind of box an operator saves.
    private val nLat = 50.53
    private val eLon = 30.52
    private val sLat = 50.47
    private val wLon = 30.40

    @Test
    fun `the whole world is one tile at zoom zero`() {
        assertEquals(1L, OfflineBudget.tileCount(85.0, 179.9, -85.0, -179.9, 0))
    }

    @Test
    fun `each level multiplies the count`() {
        // Exact ratios only hold for boxes aligned to the grid; what must never
        // happen is a deeper level costing the same or less.
        var prev = OfflineBudget.tileCount(nLat, eLon, sLat, wLon, 10)
        for (z in 11..16) {
            val now = OfflineBudget.tileCount(nLat, eLon, sLat, wLon, z)
            assertTrue("z$z=$now must exceed z${z - 1}=$prev", now > prev)
            prev = now
        }
    }

    @Test
    fun `a city district goes as deep as the provider actually has`() {
        val z = OfflineBudget.cappedMaxZoom(nLat, eLon, sLat, wLon, minZoom = 10, requestedMax = 16)
        // Not z15. Measured 17.08.2026 on the owner's phone: 1,9 GB of tile
        // store, 488 128 tiles, deepest z14 — and 17 of the packs had been
        // ordered to z16. Everything past z14 was an empty request that still
        // counted in requiredResourceCount, so the percentage never arrived.
        assertEquals(14, z)
    }

    @Test
    fun `nothing gets ordered past the provider ceiling`() {
        // The budget is not the only cap, and it is the one that used to hide
        // this: a tiny box fits any zoom, so only the ceiling stops it.
        for (requested in 15..22) {
            val z = OfflineBudget.cappedMaxZoom(
                northLat = 50.4501, eastLon = 30.5240, southLat = 50.4500, westLon = 30.5239,
                minZoom = 10, requestedMax = requested,
            )
            assertEquals("z$requested was allowed through as z$z", 14, z)
        }
    }

    @Test
    fun `the default a pack is created with is the ceiling itself`() {
        assertEquals(14.0, OfflineRegionsManager.MAX_DEFAULT_ZOOM, 0.0)
    }

    @Test
    fun `a pack that does not fit the disk is refused, with the reserve counted`() {
        val gb = 1024L * 1024L * 1024L
        assertTrue(OfflineBudget.fitsDisk(estimateBytes = gb, usableBytes = 3 * gb))
        // 1 GB pack, 1,2 GB free: fits the arithmetic, leaves the phone with
        // 200 MB. A phone with nothing free is a dead phone.
        assertFalse(OfflineBudget.fitsDisk(estimateBytes = gb, usableBytes = gb + 200L * 1024 * 1024))
        assertEquals(300L * 1024 * 1024, OfflineBudget.diskShortfallBytes(gb, gb + 200L * 1024 * 1024))
        assertEquals(0L, OfflineBudget.diskShortfallBytes(gb, 3 * gb))
    }

    @Test
    fun `an oblast-sized box is capped well below the request`() {
        // ~300x300 km. At z16 this is millions of tiles.
        val z = OfflineBudget.cappedMaxZoom(
            northLat = 51.5, eastLon = 32.0, southLat = 49.0, westLon = 29.0,
            minZoom = 10, requestedMax = 16,
        )
        assertTrue("an oblast at z$z would be gigabytes", z <= 14)
    }

    @Test
    fun `the cap never falls below the floor`() {
        // A whole country, asked for deep zoom: still not shallower than min.
        val z = OfflineBudget.cappedMaxZoom(
            northLat = 52.4, eastLon = 40.2, southLat = 44.4, westLon = 22.1,
            minZoom = 8, requestedMax = 16,
        )
        assertTrue("a pack shallower than its floor is broken, got z$z", z >= 8)
    }

    @Test
    fun `the pyramid counts every level, not just the deepest`() {
        val deepest = OfflineBudget.tileCount(nLat, eLon, sLat, wLon, 14)
        val pyramid = OfflineBudget.pyramidTiles(nLat, eLon, sLat, wLon, 10, 14)
        assertTrue("pyramid=$pyramid must exceed its base=$deepest", pyramid > deepest)
    }

    @Test
    fun `a capped pack actually fits the budget`() {
        val z = OfflineBudget.cappedMaxZoom(
            northLat = 51.5, eastLon = 32.0, southLat = 49.0, westLon = 29.0,
            minZoom = 10, requestedMax = 16,
        )
        val tiles = OfflineBudget.pyramidTiles(51.5, 32.0, 49.0, 29.0, 10, z)
        assertTrue(
            "capped to z$z but still $tiles tiles",
            tiles <= OfflineBudget.MAX_TILES_PER_PACK || z == 10,
        )
    }
}
