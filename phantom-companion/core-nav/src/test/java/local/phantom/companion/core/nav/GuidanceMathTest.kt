package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-2 — the guidance math on a synthetic due-north route: 20 points a
 * millidegree of latitude apart (~111 m each), a turn maneuver at point
 * 10 and arrival at the end.
 */
class GuidanceMathTest {

    private val points = (0 until 20).map { (50.0 + it * 0.001) to 30.0 }
    private val stepMeters = haversineM(points[0], points[1]) // ~111.2

    private fun variant(
        speeds: List<Double> = emptyList(),
        durationSeconds: Double = 1000.0,
    ) = RouteVariant(
        points = points,
        distanceMeters = stepMeters * 19,
        durationSeconds = durationSeconds,
        steps = listOf(
            RouteStep("depart", null, "", 0.0, 0.0, points[0].first, points[0].second),
            RouteStep("turn", "right", "Хрещатик", 0.0, 0.0, points[10].first, points[10].second),
            RouteStep("arrive", null, "", 0.0, 0.0, points[19].first, points[19].second),
        ),
        speedsMps = speeds,
        profile = RouteProfile.FOOT,
    )

    @Test
    fun `cumulative distances are monotonic and total is the polyline length`() {
        val path = RoutePath.of(variant())
        assertNotNull(path)
        path!!
        for (i in 1 until path.cumDistM.size) {
            assertTrue(path.cumDistM[i] > path.cumDistM[i - 1])
        }
        assertEquals(stepMeters * 19, path.totalDistM, 1.0)
    }

    @Test
    fun `snap lands on the route with the off-route distance and a north bearing`() {
        val path = RoutePath.of(variant())!!
        // ~30 m east of the midpoint (1 deg lon ≈ 71.5 km at lat 50).
        val snap = path.snap(50.0095, 30.00042)
        assertEquals(30.0, snap.offMeters, 4.0)
        assertEquals(stepMeters * 9.5, snap.alongM, 3.0)
        assertTrue("bearing ≈ north, was ${snap.bearingDeg}", snap.bearingDeg < 2.0 || snap.bearingDeg > 358.0)
    }

    @Test
    fun `step positions along the route are monotonic and correct`() {
        val path = RoutePath.of(variant())!!
        assertEquals(0.0, path.stepAlongM[0], 1.0)
        assertEquals(stepMeters * 10, path.stepAlongM[1], 2.0)
        assertEquals(stepMeters * 19, path.stepAlongM[2], 2.0)
    }

    @Test
    fun `nextStepIndex approaches the turn then the arrival`() {
        val path = RoutePath.of(variant())!!
        assertEquals(1, path.nextStepIndex(stepMeters * 5))
        assertEquals(2, path.nextStepIndex(stepMeters * 15))
        // Past everything → still the arrive step, never out of bounds.
        assertEquals(2, path.nextStepIndex(stepMeters * 30))
    }

    @Test
    fun `durations follow the speed annotations and normalise to the OSRM total`() {
        // First half at 1 m/s, second half at 4 m/s → most time in the first half.
        val speeds = List(19) { if (it < 10) 1.0 else 4.0 }
        val path = RoutePath.of(variant(speeds = speeds, durationSeconds = 800.0))!!
        assertEquals(800.0, path.totalDurS, 0.5)
        val halfTime = path.durationAtM(path.totalDistM / 2)
        assertTrue("slow half should own most of the time", halfTime > 800.0 * 0.6)
    }

    @Test
    fun `durations without annotations are distance-proportional`() {
        val path = RoutePath.of(variant(durationSeconds = 1900.0))!!
        assertEquals(950.0, path.durationAtM(path.totalDistM / 2), 5.0)
    }

    @Test
    fun `curvature ahead is zero on a straight and positive into a corner`() {
        val straight = RoutePath.of(variant())!!
        assertEquals(0.0, straight.curvatureAheadDegPerM(0.0, 120.0), 1e-6)

        // Due north 10 points, then a 90° corner due east.
        val corner = (0 until 10).map { (50.0 + it * 0.001) to 30.0 } +
            (1 until 10).map { 50.009 to (30.0 + it * 0.001) }
        val v = variant().copy(points = corner)
        val path = RoutePath.of(v)!!
        // Standing ~60 m before the corner: the 90° turn sits inside the window.
        val before = path.curvatureAheadDegPerM(stepMeters * 9 - 60.0, 120.0)
        assertTrue("corner ahead must register (got $before)", before > 0.3)
        // Long past the corner the road is straight again.
        assertEquals(0.0, path.curvatureAheadDegPerM(stepMeters * 9 + 300.0, 120.0), 1e-6)
    }

    @Test
    fun `degenerate variants are refused honestly`() {
        assertNull(RoutePath.of(variant().copy(points = emptyList())))
        assertNull(RoutePath.of(variant().copy(points = listOf(50.0 to 30.0))))
    }

    // ── Pacing: the measured body outranks the planned profile ──────────

    @Test
    fun `a walked car route is paced for the walker, not the car`() {
        assertEquals(PACE_CAR, paceFor(RouteProfile.CAR, NavGait.UNKNOWN))
        assertEquals(PACE_FOOT, paceFor(RouteProfile.CAR, NavGait.ON_FOOT))
        // …and the reverse: a foot route driven gets vehicle distances.
        assertEquals(PACE_CAR, paceFor(RouteProfile.FOOT, NavGait.DRIVING))
        assertEquals(PACE_BIKE, paceFor(RouteProfile.FOOT, NavGait.CYCLING))
    }

    @Test
    fun `without a kinematic verdict the plan still decides`() {
        assertEquals(PACE_FOOT, paceFor(RouteProfile.FOOT, NavGait.UNKNOWN))
        assertEquals(PACE_FOOT, paceFor(RouteProfile.TRANSIT, NavGait.UNKNOWN))
        assertEquals(PACE_BIKE, paceFor(RouteProfile.BIKE, NavGait.UNKNOWN))
    }

    @Test
    fun `every pedestrian cue lands inside a minute of walking`() {
        // 1.3 m/s is the pedestrian fix the device actually reports.
        val v = 1.3
        PACE_FOOT.tierFloorsM.forEachIndexed { i, floor ->
            val reach = maxOf(floor, v * (PACE_FOOT.tierLeadsS[i] + GuidanceSession.TTS_PIPELINE_LEAD_S))
            assertTrue("tier $i fires ${reach / v} s out", reach / v <= 60.0)
        }
        // The car's widest tier is 7 minutes of walking — the reason the plan
        // must not be allowed to pace a walk.
        assertTrue(PACE_CAR.tierFloorsM.first() / v > 400.0)
    }

    @Test
    fun `the pedestrian corridor is wider and slower to fire than the car's`() {
        // A walker crosses roads and swaps pavements; a car that leaves its
        // corridor has left it.
        assertTrue(PACE_FOOT.offRouteM > PACE_CAR.offRouteM)
        assertTrue(PACE_FOOT.offRouteSustainMs > PACE_CAR.offRouteSustainMs)
        // …and the sustain still has to be short enough to be useful: under
        // 15 m of committed walking at 1.3 m/s.
        assertTrue(PACE_FOOT.offRouteSustainMs / 1000.0 * 1.3 < 15.0)
    }

    @Test
    fun `an eta may move later for a slow body but never sooner`() {
        // A 3 km stretch the planner timed as a 6-minute drive, walked.
        val walked = remainingDurationS(plannedS = 360.0, remainingM = 3_000.0, pace = PACE_FOOT)
        assertTrue("promised a drive to a walker: ${walked / 60} min", walked > 2_000.0)
        // A planner estimate that is already slower than the nominal pace —
        // hills, steps, a crowd — is the better witness and survives.
        assertEquals(
            4_000.0,
            remainingDurationS(plannedS = 4_000.0, remainingM = 3_000.0, pace = PACE_FOOT),
            0.1,
        )
        // Traffic is the planner's job: a driving ETA is never second-guessed.
        assertEquals(
            360.0,
            remainingDurationS(plannedS = 360.0, remainingM = 3_000.0, pace = PACE_CAR),
            0.1,
        )
    }

    @Test
    fun `arrival is called closer on foot than behind a wheel`() {
        assertTrue(PACE_FOOT.arriveRadiusM < PACE_CAR.arriveRadiusM)
        // 25 m is 19 s of walking left unguided; 15 m is four paces.
        assertTrue(PACE_FOOT.arriveRadiusM / 1.3 < 13.0)
    }
}
