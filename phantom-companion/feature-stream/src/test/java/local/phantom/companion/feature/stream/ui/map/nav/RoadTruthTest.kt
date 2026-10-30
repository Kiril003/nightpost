package local.phantom.companion.feature.stream.ui.map.nav

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-2b — the road-snap's new spine: OSRM `/match` is the PRIMARY snapper
 * and it must work with NO Overpass tiles at all (that is the owner's field
 * failure — Overpass unreachable exactly while driving). The tile matcher is
 * only the fallback, and an unsure or absent OSRM must leave the dot on the
 * raw fix, never on a guess.
 *
 * The collector rides [runTest]'s [backgroundScope] so the endless StateFlow
 * subscription is cancelled with the test instead of hanging it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoadTruthTest {

    private fun nav(lat: Double, lon: Double, dead: Boolean = false) = LocationFix(
        lat = lat,
        lon = lon,
        accuracyM = 8f,
        speedMps = 12f,
        grade = FixGrade.NAVIGATION,
        dead = dead,
    )

    @Test
    fun `OSRM match snaps the dot with no Overpass tiles at all`() = runTest(UnconfinedTestDispatcher()) {
        val operator = MutableStateFlow<LocationFix?>(null)
        var calls = 0
        var lastTraceSize = 0
        val truth = RoadTruth(
            scope = backgroundScope,
            operatorFix = operator,
            fetchRoads = null, // Overpass unreachable — the field failure itself
            matchTrace = { trace ->
                calls++
                lastTraceSize = trace.size
                RoadSnapFix(lat = 50.4501, lon = 30.5201, wayName = "вулиця Хрещатик", confidence = 0.9f)
            },
            nowMs = { 0L },
        )
        truth.setActive(true)

        // One point cannot be map-matched — the primary must not even fire.
        operator.value = nav(50.4500, 30.5200)
        advanceUntilIdle()
        assertEquals(0, calls)
        assertNull(truth.snapped.value)

        // Second point: OSRM is consulted and its snap becomes the dot, with
        // zero road tiles loaded.
        operator.value = nav(50.4505, 30.5205)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(2, lastTraceSize)
        val snap = truth.snapped.value
        assertNotNull(snap)
        assertEquals(50.4501, snap!!.lat, 1e-9)
        assertEquals(30.5201, snap.lon, 1e-9)
        assertEquals("вулиця Хрещатик", truth.wayName.value)
    }

    @Test
    fun `a low-confidence OSRM match is refused and leaves the dot on the raw fix`() = runTest(UnconfinedTestDispatcher()) {
        val operator = MutableStateFlow<LocationFix?>(null)
        val truth = RoadTruth(
            scope = backgroundScope,
            operatorFix = operator,
            fetchRoads = null,
            matchTrace = { RoadSnapFix(lat = 50.9, lon = 30.9, wayName = "кудись", confidence = 0.40f) },
            nowMs = { 0L },
        )
        truth.setActive(true)

        operator.value = nav(50.4500, 30.5200); advanceUntilIdle()
        operator.value = nav(50.4505, 30.5205); advanceUntilIdle()

        // Below MIN_CONFIDENCE and no tile fallback → the dot stays on the raw
        // fused fix (snapped == null), never on the 50.9/30.9 guess.
        assertNull(truth.snapped.value)
        assertNull(truth.wayName.value)
    }

    @Test
    fun `a dead fix clears the snap and resets the match trace`() = runTest(UnconfinedTestDispatcher()) {
        val operator = MutableStateFlow<LocationFix?>(null)
        var calls = 0
        val truth = RoadTruth(
            scope = backgroundScope,
            operatorFix = operator,
            fetchRoads = null,
            matchTrace = { calls++; RoadSnapFix(lat = 50.0, lon = 30.0, wayName = null, confidence = 0.9f) },
            nowMs = { 0L },
        )
        truth.setActive(true)

        operator.value = nav(50.4500, 30.5200); advanceUntilIdle()
        operator.value = nav(50.4505, 30.5205); advanceUntilIdle()
        assertNotNull(truth.snapped.value)
        val callsAtSnap = calls

        // Dead-reckoned fix: inertia has no business claiming asphalt.
        operator.value = nav(50.4510, 30.5210, dead = true); advanceUntilIdle()
        assertNull(truth.snapped.value)

        // The trace was reset, so one fresh point can't match — the matcher
        // stays silent until a second real fix rebuilds the window.
        operator.value = nav(50.4515, 30.5215); advanceUntilIdle()
        assertEquals(callsAtSnap, calls)
        assertNull(truth.snapped.value)
    }

    @Test
    fun `the match trace slides and caps at the window`() = runTest(UnconfinedTestDispatcher()) {
        val operator = MutableStateFlow<LocationFix?>(null)
        var lastTraceSize = 0
        val truth = RoadTruth(
            scope = backgroundScope,
            operatorFix = operator,
            fetchRoads = null,
            matchTrace = { trace ->
                lastTraceSize = trace.size
                RoadSnapFix(lat = 50.0, lon = 30.0, wayName = null, confidence = 0.9f)
            },
            nowMs = { 0L },
        )
        truth.setActive(true)

        repeat(10) { i ->
            operator.value = nav(50.4500 + i * 0.0005, 30.5200 + i * 0.0005)
            advanceUntilIdle()
        }
        // MATCH_WINDOW = 6: the oldest crumbs are shed, the trace never grows
        // without bound.
        assertTrue("trace should never exceed the window", lastTraceSize <= 6)
        assertEquals(6, lastTraceSize)
    }
}
