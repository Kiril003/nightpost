package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-04 — the offline router used to know where a road goes and nothing about
 * what it is: every metre was priced at one flat speed, so a trunk road with a
 * 90 sign on it and a courtyard came out at the same minutes per kilometre.
 *
 * These fix both halves of the answer: the estimate now follows the road, and
 * it carries the register it was derived from — a minute read off a sign and a
 * minute invented are different claims and must never be shown as one.
 */
class LocalRouterSpeedTest {

    private val lat = 50.4500
    private val west = 30.5000
    private val mid = 30.5270
    private val east = 30.5300

    private fun drive(ways: List<RoadWay>, fromLon: Double, toLon: Double) =
        LocalRouter().route(
            graph = RoadGraph(ways),
            fromLat = lat, fromLon = fromLon,
            toLat = lat, toLon = toLon,
            footProfile = false,
        )

    @Test
    fun `a posted limit times the journey, not the flat guess`() {
        val trunk = RoadWay(
            id = 1,
            points = listOf(lat to west, lat to east),
            tags = mapOf("highway" to "trunk", "maxspeed" to "90"),
        )
        val r = drive(listOf(trunk), west, east)
        assertNotNull(r)
        checkNotNull(r)
        assertEquals(RoadProfile.SpeedBasis.POSTED, r.speedBasis)
        assertEquals(1.0, r.postedShare, 1e-9)
        assertEquals(
            r.distanceMeters / RoadProfile.speedMps(trunk.tags, foot = false),
            r.durationSeconds,
            1e-6,
        )
        // And it is a real difference, not a rounding one: the flat guess was
        // 7.5 m/s for every road in Ukraine.
        assertTrue(
            "duration ${r.durationSeconds}s is still the flat guess",
            r.durationSeconds < r.distanceMeters / LocalRouter.CAR_SPEED_MPS * 0.6,
        )
    }

    @Test
    fun `road class times the journey when no sign is tagged`() {
        val residential = RoadWay(
            id = 1,
            points = listOf(lat to west, lat to east),
            tags = mapOf("highway" to "residential"),
        )
        val r = drive(listOf(residential), west, east)
        checkNotNull(r)
        assertEquals(RoadProfile.SpeedBasis.CLASS, r.speedBasis)
        assertEquals(0.0, r.postedShare, 1e-12)
        // 7,0 -> 8,3: темп житлової вулиці переобраховано 05.09 на 15 720
        // відкладених кілометрах свіжого пакета України. Стара величина
        // завищувала час у дорозі на +3,71 хв на 10 км; нова дає +0,14.
        assertEquals(r.distanceMeters / 8.3, r.durationSeconds, 1e-6)
    }

    @Test
    fun `an untagged way routes and times exactly as it always did`() {
        // Every row a device wrote before the tags column, and every way of a
        // pack baked without one. This is the promise the migration made.
        val plain = RoadWay(id = 1, points = listOf(lat to west, lat to east))
        val r = drive(listOf(plain), west, east)
        checkNotNull(r)
        assertEquals(RoadProfile.SpeedBasis.ASSUMED, r.speedBasis)
        assertEquals(0.0, r.postedShare, 1e-12)
        assertEquals(r.distanceMeters / LocalRouter.CAR_SPEED_MPS, r.durationSeconds, 1e-9)
    }

    @Test
    fun `one untagged stretch demotes the whole estimate to an assumption`() {
        // The mixed world the national bake forces on us: an imported pack
        // carries no tags, Overpass tiles do. Nine tenths measured is still
        // not «за знаками».
        val posted = RoadWay(
            id = 1,
            points = listOf(lat to west, lat to mid),
            tags = mapOf("highway" to "trunk", "maxspeed" to "90"),
        )
        val untagged = RoadWay(id = 2, points = listOf(lat to mid, lat to east))
        val r = drive(listOf(posted, untagged), west, east)
        checkNotNull(r)
        assertEquals(RoadProfile.SpeedBasis.ASSUMED, r.speedBasis)
        assertTrue(
            "postedShare ${r.postedShare} should measure the signed stretch",
            r.postedShare > 0.85 && r.postedShare < 0.95,
        )
        // The two stretches are timed apart, so the total is neither end's
        // speed applied to the whole length.
        assertTrue(
            r.durationSeconds > r.distanceMeters / RoadProfile.speedMps(posted.tags, foot = false),
        )
        assertTrue(r.durationSeconds < r.distanceMeters / LocalRouter.CAR_SPEED_MPS)
    }

    @Test
    fun `the trace attributes every segment to the road under it`() {
        val posted = RoadWay(
            id = 1,
            points = listOf(lat to west, lat to mid),
            tags = mapOf("highway" to "trunk", "maxspeed" to "90"),
        )
        val untagged = RoadWay(id = 2, points = listOf(lat to mid, lat to east))
        val graph = RoadGraph(listOf(posted, untagged))
        val from = graph.near(lat, west, 120.0, limit = 1).first()
        val to = graph.near(lat, east, 120.0, limit = 1).first()
        val traced = graph.routeTraced(from, to)
        checkNotNull(traced)
        assertEquals(traced.points.size - 1, traced.segmentWays.size)
        assertTrue(traced.segmentWays.any { it.id == 1L })
        assertTrue(traced.segmentWays.any { it.id == 2L })
    }

    @Test
    fun `walking is honest about being a guess even on a signed road`() {
        val trunk = RoadWay(
            id = 1,
            points = listOf(lat to west, lat to east),
            tags = mapOf("highway" to "residential", "maxspeed" to "50"),
        )
        val r = LocalRouter().route(
            graph = RoadGraph(listOf(trunk)),
            fromLat = lat, fromLon = west,
            toLat = lat, toLon = east,
            footProfile = true,
        )
        checkNotNull(r)
        assertEquals(RoadProfile.SpeedBasis.ASSUMED, r.speedBasis)
        assertEquals(r.distanceMeters / LocalRouter.FOOT_SPEED_MPS, r.durationSeconds, 1e-9)
    }
}
