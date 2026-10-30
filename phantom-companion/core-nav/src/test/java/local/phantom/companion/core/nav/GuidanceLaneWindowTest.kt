package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.LaneInfo
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRESENCE — when the lane band is on, and when it is honestly absent.
 *
 * The same due-north synthetic route the rest of the nav tests drive, at a
 * car's speed: the band arms inside the speed-scaled window of a maneuver that
 * HAS lane data, disarms behind it, and never appears over a road that carries
 * none.
 */
class GuidanceLaneWindowTest {

    private val points = (0 until 40).map { (50.0 + it * 0.001) to 30.0 }

    private val lanes = listOf(
        LaneInfo(listOf("left"), validForManeuver = false),
        LaneInfo(listOf("straight"), validForManeuver = false),
        LaneInfo(listOf("right"), validForManeuver = true),
    )

    private fun variant(withLanes: Boolean) = RouteVariant(
        points = points,
        distanceMeters = 4300.0,
        durationSeconds = 300.0,
        steps = listOf(
            RouteStep("depart", null, "", 0.0, 0.0, points[0].first, points[0].second),
            RouteStep(
                "turn", "right", "Васильківська", 0.0, 0.0,
                points[20].first, points[20].second,
                lanes = if (withLanes) lanes else emptyList(),
            ),
            RouteStep("arrive", null, "", 0.0, 0.0, points[39].first, points[39].second),
        ),
        speedsMps = emptyList(),
        profile = RouteProfile.CAR,
    )

    private val baseMs = 1_000_000_000L

    /** 50 km/h — under the lead, so the floor decides the window. */
    private val townMps = 13.9f

    /** 90 km/h — over the cap, so the ceiling decides it. */
    private val roadMps = 25f

    @Test
    fun `the window is a floor and a speed lead, capped`() {
        assertEquals(150.0, GuidanceSession.laneWindowM(0.0), 0.001)
        assertEquals(150.0, GuidanceSession.laneWindowM(townMps.toDouble()), 0.001)
        assertEquals(250.0, GuidanceSession.laneWindowM(roadMps.toDouble()), 0.001)
    }

    @Test
    fun `band arms inside the window and holds the lanes it was given`() {
        val session = GuidanceSession.of(variant(withLanes = true)) { null }!!
        // ~670 m out — data exists, the moment does not.
        val far = session.onFix(50.014, 30.0, townMps, baseMs)
        assertFalse(far.progress.laneApproach)
        assertEquals(3, far.progress.lanes.size)

        // ~222 m out: outside the town window (150 m), inside the road one.
        val town = session.onFix(50.018, 30.0, townMps, baseMs + 1_000)
        assertFalse(town.progress.laneApproach)
        val road = session.onFix(50.018, 30.0, roadMps, baseMs + 2_000)
        assertTrue(road.progress.laneApproach)

        // ~111 m out — inside both.
        val near = session.onFix(50.019, 30.0, townMps, baseMs + 3_000)
        assertTrue(near.progress.laneApproach)
        assertEquals(listOf(false, false, true), near.progress.lanes.map { it.validForManeuver })
    }

    @Test
    fun `band disarms once the maneuver is behind`() {
        val session = GuidanceSession.of(variant(withLanes = true)) { null }!!
        assertTrue(session.onFix(50.019, 30.0, townMps, baseMs).progress.laneApproach)
        // Past the turn: the next step is «arrive», which carries no lanes.
        val after = session.onFix(50.025, 30.0, townMps, baseMs + 5_000)
        assertFalse(after.progress.laneApproach)
        assertTrue(after.progress.lanes.isEmpty())
    }

    @Test
    fun `no lane data means no band, however close the turn is`() {
        val session = GuidanceSession.of(variant(withLanes = false)) { null }!!
        val u = session.onFix(50.0199, 30.0, townMps, baseMs)
        assertTrue(u.progress.distanceToManeuverM < 20.0)
        assertFalse(u.progress.laneApproach)
        assertTrue(u.progress.lanes.isEmpty())
    }

    @Test
    fun `leaving the corridor takes the band with it`() {
        val session = GuidanceSession.of(variant(withLanes = true)) { null }!!
        // 111 m to the turn but ~140 m sideways of the route: the lane claim is
        // about a corridor the operator is no longer on.
        val u = session.onFix(50.019, 30.002, roadMps, baseMs)
        assertTrue(u.progress.offRoute)
        assertFalse(u.progress.laneApproach)
    }
}
