package local.phantom.companion.core.geo.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacePackPlannerTest {

    private val zhytomyr = NamedPlace(
        id = "t-obl", nameUa = "Житомирська область", nameEn = "Zhytomyr Oblast",
        parentUa = null, kind = PlaceKind.Oblast,
        northLat = 51.9, eastLon = 29.6, southLat = 49.5, westLon = 27.2,
        landAreaKm2 = 29_832.0, osmRef = null,
    )

    private val small = NamedPlace(
        id = "t-city", nameUa = "Мале місто", nameEn = "Small",
        parentUa = "Житомирська область", kind = PlaceKind.City,
        northLat = 50.30, eastLon = 28.75, southLat = 50.20, westLon = 28.55,
        landAreaKm2 = null, osmRef = null,
    )

    @Test
    fun `no chunk exceeds the budget`() {
        val plan = PlacePackPlanner.plan(zhytomyr)
        assertNotNull(plan)
        plan!!.chunks.forEach {
            assertTrue(
                "chunk ${it.index} carries ${it.tiles} tiles, over the budget",
                it.tiles <= PlacePackPlanner.TILES_PER_CHUNK,
            )
        }
    }

    @Test
    fun `the chunks put the whole place back together`() {
        val plan = PlacePackPlanner.plan(zhytomyr)!!
        assertEquals(zhytomyr.northLat, plan.chunks.maxOf { it.northLat }, 1e-9)
        assertEquals(zhytomyr.southLat, plan.chunks.minOf { it.southLat }, 1e-9)
        assertEquals(zhytomyr.eastLon, plan.chunks.maxOf { it.eastLon }, 1e-9)
        assertEquals(zhytomyr.westLon, plan.chunks.minOf { it.westLon }, 1e-9)
    }

    @Test
    fun `a small city needs exactly one chunk`() {
        val plan = PlacePackPlanner.plan(small)!!
        assertEquals(1, plan.chunkCount)
    }

    @Test
    fun `each deeper level costs about four times more`() {
        val z13 = PlacePackPlanner.plan(zhytomyr, maxZoom = 13)!!
        val z14 = PlacePackPlanner.plan(zhytomyr, maxZoom = 14)!!
        val ratio = z14.tiles.toDouble() / z13.tiles.toDouble()
        assertTrue("z14/z13 tile ratio was $ratio", ratio in 3.0..4.5)
    }

    @Test
    fun `asking past the source ceiling buys nothing and says so`() {
        // The vector source tops out at z14; a plan that answered "z16" would
        // quote sixteen times the bytes for tiles that never arrive.
        val deep = PlacePackPlanner.plan(zhytomyr, maxZoom = 16)!!
        assertEquals(PlacePackPlanner.MAX_ZOOM, deep.maxZoom)
        assertEquals(PlacePackPlanner.plan(zhytomyr, maxZoom = 14)!!.tiles, deep.tiles)
        assertTrue(PlacePackPlanner.DEPTHS.all { it <= PlacePackPlanner.MAX_ZOOM })
    }

    @Test
    fun `a place that will not fit is refused with a number, not queued`() {
        val ukraine = NamedPlace(
            id = "t-ua", nameUa = "Україна", nameEn = "Ukraine", parentUa = null,
            kind = PlaceKind.Country,
            northLat = 52.4, eastLon = 40.3, southLat = 44.3, westLon = 22.1,
            landAreaKm2 = 600_000.0, osmRef = null,
        )
        assertNull(PlacePackPlanner.plan(ukraine, maxChunks = 2))
        val needed = PlacePackPlanner.chunksNeeded(ukraine, PlacePackPlanner.MIN_ZOOM, 14)
        assertTrue("a refusal that names no number", needed > 2)
    }

    @Test
    fun `the whole country at full depth still fits in one operation`() {
        val ukraine = PlaceGazetteer.country
        assertNotNull("the gazetteer lost Ukraine", ukraine)
        val plan = PlacePackPlanner.plan(ukraine!!)
        assertNotNull("Ukraine cannot be planned at all", plan)
        assertTrue(plan!!.chunkCount in 1..PlacePackPlanner.MAX_CHUNKS)
    }

    @Test
    fun `a thin sample is never shown as a device average`() {
        assertNull(PlacePackPlanner.deviceAverage(10L, 40_000L))
        assertNull(PlacePackPlanner.deviceAverage(0L, 0L))
    }

    @Test
    fun `a real sample yields a device average`() {
        val avg = PlacePackPlanner.deviceAverage(10_000L, 20_000_000L)
        assertNotNull(avg)
        assertEquals(2_000L, avg!!.bytesPerResource)
    }

    @Test
    fun `the estimate weighs each zoom on its own measured size`() {
        // A flat bytes-per-tile constant undercounted Ukraine 2.7x: a z10 tile
        // is ~80 kB and a rural z14 one ~1.3 kB, and a pyramid mixes them.
        assertTrue(
            PlacePackPlanner.bytesAtZoom(10, PlaceKind.Oblast) >
                PlacePackPlanner.bytesAtZoom(14, PlaceKind.Oblast) * 10,
        )
        // A city box is solid built-up area; the country mean undercounts it.
        assertTrue(
            PlacePackPlanner.bytesAtZoom(14, PlaceKind.City) >
                PlacePackPlanner.bytesAtZoom(14, PlaceKind.Oblast) * 5,
        )
        val plan = PlacePackPlanner.plan(zhytomyr)!!
        val flat = plan.tiles * PlacePackPlanner.bytesAtZoom(14, PlaceKind.Oblast)
        assertTrue("the estimate collapsed back to a flat rate", plan.bytes > flat)
        assertEquals(plan.chunks.sumOf { it.bytes }, plan.bytes)
    }

    @Test
    fun `the country estimate lands where the source measurement puts it`() {
        // Sampling the live source over Ukraine's box put z10-14 at ~2.3 GB.
        val plan = PlacePackPlanner.plan(PlaceGazetteer.country!!)!!
        val gb = plan.bytes / (1024.0 * 1024.0 * 1024.0)
        assertTrue("Ukraine estimated at $gb GB", gb in 1.5..3.5)
    }

    @Test
    fun `an inverted box plans nothing`() {
        val broken = small.copy(northLat = 49.0)
        assertNull(PlacePackPlanner.plan(broken))
    }

    /**
     * Ланки коридора перекриваються (кожна бере наступну точку маршруту). Сума
     * по ланках рахувала б спільні тайли двічі — рівно та помилка, через яку
     * підсумок офлайн-панелі показував 2590 МБ там, де на диску 1828.
     */
    @Test
    fun `overlapping corridor links do not pay for the same tile twice`() {
        val a = PlacePackPlanner.CorridorBox(50.50, 30.60, 50.45, 30.50)
        val b = PlacePackPlanner.CorridorBox(50.50, 30.60, 50.45, 30.50)
        val one = PlacePackPlanner.corridorBytes(listOf(a), 10, 14)
        val two = PlacePackPlanner.corridorBytes(listOf(a, b), 10, 14)
        assertTrue("a corridor must cost something", one > 0)
        assertEquals("the same box twice must cost the same", one, two)
    }

    @Test
    fun `a longer corridor costs more than a shorter one`() {
        val near = PlacePackPlanner.CorridorBox(50.50, 30.60, 50.45, 30.50)
        val far = PlacePackPlanner.CorridorBox(50.90, 31.60, 50.85, 31.50)
        val short = PlacePackPlanner.corridorBytes(listOf(near), 10, 14)
        val long = PlacePackPlanner.corridorBytes(listOf(near, far), 10, 14)
        assertTrue("$long must exceed $short", long > short)
    }

    @Test
    fun `a corridor is never priced past the provider ceiling`() {
        val box = PlacePackPlanner.CorridorBox(50.50, 30.60, 50.45, 30.50)
        assertEquals(
            PlacePackPlanner.corridorBytes(listOf(box), 10, PlacePackPlanner.MAX_ZOOM),
            PlacePackPlanner.corridorBytes(listOf(box), 10, 18),
        )
        assertTrue(PlacePackPlanner.corridorTiles(listOf(box), 10, 18).keys.max() <= PlacePackPlanner.MAX_ZOOM)
    }

    @Test
    fun `no links means no number`() {
        assertEquals(0L, PlacePackPlanner.corridorBytes(emptyList(), 10, 14))
    }
}
