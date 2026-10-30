package local.phantom.companion.core.nav

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ATLAS-4 — the cache's promises: a prefetched plan costs zero extra
 * round-trips, identical intent within the TTL is answered locally, a
 * failure is never cached, and staleness is honoured.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class RoutePlannerCacheTest {

    private val kyiv = 50.4501 to 30.5234
    private val podil = 50.4622 to 30.5168

    // Single-point geometry on purpose: RouteIntel.analyze() refuses < 2
    // points, so the enrichment job the planner launches after a plan stays
    // off the network and these tests stay hermetic.
    private fun variant() = RouteVariant(
        points = listOf(kyiv),
        distanceMeters = 1500.0,
        durationSeconds = 1100.0,
        steps = emptyList(),
        speedsMps = emptyList(),
        profile = RouteProfile.FOOT,
    )

    @Test
    fun `same plan twice inside the TTL is one round-trip`() = runTest {
        var calls = 0
        var now = 0L
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, _ -> calls++; listOf(variant()) },
            nowMs = { now },
        )
        assertNotNull(planner.plan(listOf(kyiv, podil), RouteProfile.FOOT))
        now += 30_000
        assertNotNull(planner.plan(listOf(kyiv, podil), RouteProfile.FOOT))
        assertEquals(1, calls)
    }

    @Test
    fun `prefetch makes the real plan a zero-cost hit`() = runTest {
        var calls = 0
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, _ -> calls++; listOf(variant()) },
            nowMs = { 0L },
        )
        planner.prefetch(listOf(kyiv, podil), RouteProfile.FOOT)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertNotNull(planner.plan(listOf(kyiv, podil), RouteProfile.FOOT))
        assertEquals(1, calls)
    }

    @Test
    fun `near-identical coordinates quantize to the same intent`() = runTest {
        var calls = 0
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, _ -> calls++; listOf(variant()) },
            nowMs = { 0L },
        )
        planner.plan(listOf(kyiv, podil), RouteProfile.FOOT)
        // 3 m of long-press wobble: same pins as far as intent goes.
        planner.plan(
            listOf(kyiv.first + 0.00002 to kyiv.second, podil.first to podil.second + 0.00002),
            RouteProfile.FOOT,
        )
        assertEquals(1, calls)
    }

    @Test
    fun `profiles never share a cache line`() = runTest {
        var calls = 0
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, _ -> calls++; listOf(variant()) },
            nowMs = { 0L },
        )
        planner.plan(listOf(kyiv, podil), RouteProfile.FOOT)
        planner.plan(listOf(kyiv, podil), RouteProfile.CAR)
        assertEquals(2, calls)
    }

    @Test
    fun `a stale entry is refetched`() = runTest {
        var calls = 0
        var now = 0L
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, _ -> calls++; listOf(variant()) },
            nowMs = { now },
        )
        planner.plan(listOf(kyiv, podil), RouteProfile.FOOT)
        now += 4 * 60_000L // beyond the 3-minute TTL
        planner.plan(listOf(kyiv, podil), RouteProfile.FOOT)
        assertEquals(2, calls)
    }

    @Test
    fun `a failure is honest and never cached`() = runTest {
        var calls = 0
        var fail = true
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, _ -> calls++; if (fail) null else listOf(variant()) },
            nowMs = { 0L },
        )
        assertNull(planner.plan(listOf(kyiv, podil), RouteProfile.FOOT))
        fail = false
        assertNotNull(planner.plan(listOf(kyiv, podil), RouteProfile.FOOT))
        assertEquals(2, calls)
    }

    @Test
    fun `transit prefetch is a no-op`() = runTest {
        var calls = 0
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, _ -> calls++; listOf(variant()) },
            nowMs = { 0L },
        )
        planner.prefetch(listOf(kyiv, podil), RouteProfile.TRANSIT)
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test
    fun `transit plan falls back to road route when transit unavailable`() = runTest {
        var requestedProfile: RouteProfile? = null
        val planner = RoutePlanner(
            scope = this,
            routeSource = { prof, _ ->
                requestedProfile = prof
                listOf(variant().copy(profile = prof))
            },
            nowMs = { 0L },
        )
        val plan = planner.plan(listOf(kyiv, podil), RouteProfile.TRANSIT)
        assertNotNull("Should fallback to road route instead of failing with null", plan)
        assertEquals(RouteProfile.FOOT, requestedProfile)
        assertEquals(RouteProfile.FOOT, plan?.profile)
    }
}
