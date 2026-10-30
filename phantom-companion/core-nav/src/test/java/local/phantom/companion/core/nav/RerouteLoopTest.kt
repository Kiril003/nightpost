package local.phantom.companion.core.nav

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA FIELD FAILURE — «маршрут крутиться у циклі». An operator off the corridor
 * (a canyon, a garage, indoor scatter with a speed the chip still reports)
 * replanned every 15 s forever. The route cache answers each of those from
 * memory, so the loop was silent, free and unbreakable: every turn rebound the
 * session, blanked the banner to «чекаю GPS» and re-posted the notification.
 *
 * The previous pass at this throttled the ANNOUNCEMENT to 45 s and left the
 * replan running — the loop stopped being audible and stayed visible. These
 * tests pin the cause instead: a replan that returns the road already under
 * the wheels publishes nothing, and repeated reroutes cost more each time.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class RerouteLoopTest {

    private val points = (0 until 20).map { (50.0 + it * 0.001) to 30.0 }
    private val dest = points.last()
    private val start = points.first()

    /** ~143 m east of the corridor at mid-route — past FOOT's 55 m. */
    private val offLat = 50.005
    private val offLon = 30.0020

    private fun routeAlong(lons: Double, profile: RouteProfile = RouteProfile.FOOT) = RouteVariant(
        points = points.map { it.first to lons },
        distanceMeters = haversineM(points[0], points[19]),
        durationSeconds = 1600.0,
        steps = listOf(
            RouteStep("depart", null, "", 0.0, 0.0, points[0].first, lons),
            RouteStep("arrive", null, "", 0.0, 0.0, points[19].first, lons),
        ),
        speedsMps = emptyList(),
        profile = profile,
    )

    private val onCorridor = routeAlong(30.0)

    private val baseMs = 1_000_000_000L

    /**
     * The defect, stated as a property: a stationary operator judged off-route
     * must cost ONE replan attempt per backoff window and ZERO republished
     * plans, because every answer is the road he is already standing on.
     *
     * Against the pre-fix controller this ran 8 republications in the same
     * two minutes — one per 15 s cooldown — each one a full rebuild.
     */
    @Test
    fun `standing off route republishes nothing when the road comes back the same`() = runTest {
        var now = baseMs
        var revisions = 0
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, _ -> listOf(onCorridor) },
            nowMs = { now },
        )
        assertNotNull(planner.plan(listOf(start, dest), RouteProfile.FOOT))

        // Mirrors NavigationEngine.bumpPlan(): a published plan rebinds the
        // live session. Without that the session never moves and the harness
        // measures a drive nobody is steering.
        var controller: GuidanceController? = null
        controller = GuidanceController(
            scope = this,
            planner = planner,
            onPlanReplaced = { revisions++; controller?.onPlanPublished() },
            postNotice = {},
            clockMs = { now },
        )
        controller.start()

        // Two minutes of 1 Hz fixes, all at the same off-corridor point, each
        // reporting the walking speed a scattering chip still claims.
        repeat(120) {
            now += 1_000
            controller.onFix(offLat, offLon, 1.4f, trusted = true)
            advanceUntilIdle()
        }

        assertEquals(0, revisions)
        // …and prove the reroute machinery actually RAN rather than being
        // switched off: the planner was re-asked from the operator's position.
        assertEquals(offLat to offLon, planner.waypoints.first())
    }

    /**
     * The other half, and the one that stops this being «disable rerouting»:
     * when the planner genuinely returns different tarmac, it is published.
     */
    @Test
    fun `a genuinely different road is published once`() = runTest {
        var now = baseMs
        var revisions = 0
        val detour = routeAlong(30.0020)
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, wp ->
                if (wp.first() == start) listOf(onCorridor) else listOf(detour)
            },
            nowMs = { now },
        )
        assertNotNull(planner.plan(listOf(start, dest), RouteProfile.FOOT))

        var controller: GuidanceController? = null
        controller = GuidanceController(
            scope = this,
            planner = planner,
            onPlanReplaced = { revisions++; controller?.onPlanPublished() },
            postNotice = {},
            clockMs = { now },
        )
        controller.start()

        repeat(60) {
            now += 1_000
            controller.onFix(offLat, offLon, 1.4f, trusted = true)
            advanceUntilIdle()
        }

        // Published exactly once: the first replan moved him onto the detour,
        // and from there the corridor answer stops changing.
        assertEquals(1, revisions)
    }

    /**
     * QA FIELD FAILURE (this pass) — the schedule below was documented, tested
     * here, and never once consulted by `maybeReroute`, which carried its own
     * inline `when` that flattened to a 10 s floor forever past the fourth
     * strike. A green run of exactly this test proved nothing about the
     * running app. Fixed by making `maybeReroute` call this function.
     */
    @Test
    fun `the cooldown opens fast and escalates to a one-minute ceiling`() {
        assertEquals(2_000L, GuidanceController.rerouteCooldownMs(0))
        assertEquals(4_000L, GuidanceController.rerouteCooldownMs(1))
        assertEquals(6_000L, GuidanceController.rerouteCooldownMs(2))
        assertEquals(10_000L, GuidanceController.rerouteCooldownMs(3))
        assertEquals(20_000L, GuidanceController.rerouteCooldownMs(4))
        assertEquals(40_000L, GuidanceController.rerouteCooldownMs(5))
        assertEquals(60_000L, GuidanceController.rerouteCooldownMs(6))
        assertEquals(60_000L, GuidanceController.rerouteCooldownMs(20))
    }

    @Test
    fun `the same geometry on the same profile is the same road`() {
        assertTrue(GuidanceController.sameRoute(onCorridor, routeAlong(30.0)))
        // Different tarmac.
        assertTrue(!GuidanceController.sameRoute(onCorridor, routeAlong(30.0020)))
        // Same line, different body: a car does not inherit a walker's plan.
        assertTrue(!GuidanceController.sameRoute(onCorridor, routeAlong(30.0, RouteProfile.CAR)))
        // Nothing bound yet is never «the same».
        assertTrue(!GuidanceController.sameRoute(null, onCorridor))
    }
}
