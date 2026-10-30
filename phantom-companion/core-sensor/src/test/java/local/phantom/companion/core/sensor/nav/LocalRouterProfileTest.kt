package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-to-end shape of the owner's complaint: given a short cut through a
 * courtyard and a longer way round on a street, which does the router pick?
 *
 * Before [RoadProfile] it picked the courtyard every time, because the two were
 * the same road to it — only their lengths differed.
 */
class LocalRouterProfileTest {

    private val startLat = 50.4500
    private val startLon = 30.5000
    private val endLat = 50.4500
    private val endLon = 30.5030

    /** Straight through the block: shortest, and somebody's yard. */
    private val courtyard = RoadWay(
        id = 1,
        points = listOf(startLat to startLon, endLat to endLon),
        tags = mapOf("highway" to "service", "service" to "driveway"),
    )

    /** Round two sides of the block on a real street: twice as long. */
    private val street = RoadWay(
        id = 2,
        points = listOf(
            startLat to startLon,
            (startLat + 0.0010) to startLon,
            (startLat + 0.0010) to endLon,
            endLat to endLon,
        ),
        tags = mapOf("highway" to "residential"),
    )

    private fun route(ways: List<RoadWay>) = LocalRouter().route(
        graph = RoadGraph(ways),
        fromLat = startLat, fromLon = startLon,
        toLat = endLat, toLon = endLon,
        footProfile = true,
    )

    /** Did the chosen path leave the straight line between the endpoints? */
    private fun wentAround(points: List<Pair<Double, Double>>) =
        points.any { it.first > startLat + 0.0005 }

    @Test
    fun `the longer street beats the shorter courtyard`() {
        val r = route(listOf(courtyard, street))
        assertNotNull("a route must exist through either way", r)
        assertTrue(
            "router took the courtyard short cut: ${r!!.points}",
            wentAround(r.points),
        )
    }

    @Test
    fun `untagged ways still route by pure length — the old behaviour, unchanged`() {
        val plainYard = courtyard.copy(tags = emptyMap())
        val plainStreet = street.copy(tags = emptyMap())
        val r = route(listOf(plainYard, plainStreet))
        assertNotNull(r)
        assertTrue("with no tags the shortest path must win", !wentAround(r!!.points))
    }

    @Test
    fun `a courtyard is still used when it is the only way home`() {
        val r = route(listOf(courtyard))
        assertNotNull("penalised is not forbidden — a man may live down there", r)
    }

    @Test
    fun `private land loses to a far longer public street`() {
        val private = courtyard.copy(tags = mapOf("highway" to "service", "access" to "private"))
        val r = route(listOf(private, street))
        assertNotNull(r)
        assertTrue("router crossed private land: ${r!!.points}", wentAround(r.points))
    }

    // ТРАСА — the corridor case. Same two endpoints ~7.1 km apart; one road
    // runs straight between them, the other detours 334 m north and back.
    private val corridorEndLon = 30.6000
    private val northLat = startLat + 0.003

    private fun straight(highway: String) = RoadWay(
        id = 10,
        points = listOf(startLat to startLon, startLat to corridorEndLon),
        tags = mapOf("highway" to highway),
    )

    private fun detour(highway: String) = RoadWay(
        id = 11,
        points = listOf(
            startLat to startLon,
            northLat to startLon,
            northLat to corridorEndLon,
            startLat to corridorEndLon,
        ),
        tags = mapOf("highway" to highway),
    )

    private fun corridor(ways: List<RoadWay>, foot: Boolean) = LocalRouter().route(
        graph = RoadGraph(ways),
        fromLat = startLat, fromLon = startLon,
        toLat = startLat, toLon = corridorEndLon,
        footProfile = foot,
    )

    private fun tookTheDetour(points: List<Pair<Double, Double>>) =
        points.any { it.first > startLat + 0.001 }

    @Test
    fun `the national road wins even when the local road is shorter`() {
        // The whole of B-05: Ukraine tags its М-roads `trunk`, so a profile
        // that does not rank `trunk` at the top answers a corridor with the
        // shortest patchwork of lesser roads it can find.
        val r = corridor(listOf(straight("secondary"), detour("trunk")), foot = false)
        assertNotNull("a route must exist along either corridor", r)
        checkNotNull(r)
        assertTrue("router took the shorter secondary: ${r.points}", tookTheDetour(r.points))
        assertTrue(
            "the chosen road must be the LONGER one — ${r.distanceMeters} m",
            r.distanceMeters > 7500.0,
        )
        // And it is still only a class, not a sign: the trunk carries no
        // maxspeed here, so the minutes are an inference and say so.
        assertEquals(RoadProfile.SpeedBasis.CLASS, r.speedBasis)
        assertEquals(0.0, r.postedShare, 1e-12)
    }

    @Test
    fun `the same fork is answered one way by car and another on foot`() {
        val ways = listOf(straight("residential"), detour("secondary"))

        val byCar = corridor(ways, foot = false)
        checkNotNull(byCar)
        assertTrue("car stayed on the residential street: ${byCar.points}", tookTheDetour(byCar.points))

        // A pedestrian gains nothing from the bigger road and walks the short
        // way. The ladder must not follow him there.
        val onFoot = corridor(ways, foot = true)
        checkNotNull(onFoot)
        assertFalse("walker was pushed onto the secondary: ${onFoot.points}", tookTheDetour(onFoot.points))
    }

    // УЗБІЧЧЯ — the pedestrian half of the corridor. `straight` is ~7.09 km of
    // highway; `detour` is ~7.76 km round two extra sides.

    @Test
    fun `on foot an ordinary street still beats the highway beside it`() {
        val r = corridor(listOf(straight("trunk"), detour("residential")), foot = true)
        checkNotNull(r)
        assertTrue("walker was sent up the highway: ${r.points}", tookTheDetour(r.points))
        assertEquals(0.0, r.trunkShoulderMeters, 1e-9)
        assertFalse(r.walksTrunkShoulder)
    }

    @Test
    fun `the 250x detour over unmapped verges is over`() {
        // A 33.8 km dogleg on rough track — 4.8× the highway. While `trunk` was
        // blocked at 500× the router took anything up to ~333× longer rather
        // than touch asphalt; in a de-occupied oblast that verge is where the
        // mines are, and OSM carries none of them.
        val longTrack = RoadWay(
            id = 12,
            points = listOf(
                startLat to startLon,
                (startLat + 0.12) to startLon,
                (startLat + 0.12) to corridorEndLon,
                startLat to corridorEndLon,
            ),
            tags = mapOf("highway" to "track", "surface" to "ground"),
        )
        val r = corridor(listOf(straight("trunk"), longTrack), foot = true)
        checkNotNull(r)
        assertFalse(
            "router walked ${r.distanceMeters.toInt()} m of track to dodge 7 km of asphalt",
            tookTheDetour(r.points),
        )
        // And the route says which part of it was highway, in metres it
        // actually measured — not that a shoulder was found there.
        assertTrue("only ${r.trunkShoulderMeters} m counted as highway", r.trunkShoulderMeters > 7000.0)
        assertTrue(r.walksTrunkShoulder)
    }

    @Test
    fun `a trunk with a pavement raises no shoulder notice`() {
        val paved = straight("trunk").copy(tags = mapOf("highway" to "trunk", "sidewalk" to "both"))
        val r = corridor(listOf(paved), foot = true)
        checkNotNull(r)
        assertEquals(0.0, r.trunkShoulderMeters, 1e-9)
        assertFalse("OSM recorded a pavement here — there is nothing to warn about", r.walksTrunkShoulder)
    }

    @Test
    fun `preferring the trunk does not put highway speed on an urban one`() {
        // A trunk posted at 50 — Південний міст is exactly this — must be timed
        // at 50, and the estimate must call itself a reading, not a class.
        val posted = detour("trunk").copy(tags = mapOf("highway" to "trunk", "maxspeed" to "50"))
        val r = corridor(listOf(straight("secondary"), posted), foot = false)
        checkNotNull(r)
        assertTrue("router left the posted trunk: ${r.points}", tookTheDetour(r.points))
        assertEquals(RoadProfile.SpeedBasis.POSTED, r.speedBasis)
        assertEquals(
            r.distanceMeters / (50 * 1000.0 / 3600.0 * 0.7),
            r.durationSeconds,
            1e-6,
        )
        // The class table would have claimed 25 m/s and shaved the journey to
        // under half. It does not get to.
        assertTrue(
            "duration ${r.durationSeconds}s is the class guess, not the sign",
            r.durationSeconds > r.distanceMeters / 20.0,
        )
    }
}
