package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbTrackerTest {

    @Test
    fun recordsSequentialFixesAndCalculatesDistance() {
        val tracker = BreadcrumbTracker(minStepM = 5.0)

        val t0 = 1000000L
        assertTrue(tracker.record(50.4501, 30.5234, t0, 3f))
        // Too close (< 5m), should be rejected
        val tooClose = tracker.record(50.450101, 30.523401, t0 + 1000, 3f)
        assertEquals(false, tooClose)
        assertEquals(1, tracker.pointCount())

        // Move 50m north
        assertTrue(tracker.record(50.45055, 30.5234, t0 + 5000, 3f))
        assertEquals(2, tracker.pointCount())
        assertTrue(tracker.totalDistanceM() > 40.0)
    }

    @Test
    fun rejectsInaccurateFixes() {
        val tracker = BreadcrumbTracker()
        val accepted = tracker.record(50.4501, 30.5234, 1000L, 50f)
        assertEquals(false, accepted)
        assertEquals(0, tracker.pointCount())
    }

    @Test
    fun prunesOldPointsBeyondRetentionWindow() {
        val maxAgeMs = 10_000L
        val tracker = BreadcrumbTracker(maxAgeMs = maxAgeMs, minStepM = 1.0)

        tracker.record(50.4501, 30.5234, 1000L, 3f)
        tracker.record(50.4502, 30.5234, 2000L, 3f)
        assertEquals(2, tracker.pointCount())

        // New point at 15000L (> 10s after first point)
        tracker.record(50.4503, 30.5234, 15000L, 3f)
        val track = tracker.getTrack()
        assertTrue(track.none { it.timeMs == 1000L })
        assertTrue(track.any { it.timeMs == 15000L })
    }

    @Test
    fun buildRetraceRouteReversesAndSimplifies() {
        val tracker = BreadcrumbTracker(minStepM = 1.0)
        val startLat = 50.4500
        val startLon = 30.5200

        // Walk east in 5 steps
        for (i in 0..5) {
            tracker.record(startLat, startLon + i * 0.0005, 1000L + i * 1000, 2f)
        }

        val retrace = tracker.buildRetraceRoute()
        assertNotNull(retrace)
        assertTrue(retrace!!.size >= 2)

        // Retrace must start at the latest point and end at the origin
        val firstPt = retrace.first()
        val lastPt = retrace.last()

        assertEquals(startLat, firstPt.first, 1e-4)
        assertEquals(startLon + 5 * 0.0005, firstPt.second, 1e-4)

        assertEquals(startLat, lastPt.first, 1e-4)
        assertEquals(startLon, lastPt.second, 1e-4)
    }

    @Test
    fun simplifyRdpPreservesCorners() {
        // Create an L-shaped path: (0,0) -> (0, 0.01) -> (0.01, 0.01)
        val pts = listOf(
            50.0 to 30.0,
            50.0 to 30.002,
            50.0 to 30.005,
            50.0 to 30.010, // Corner
            50.003 to 30.010,
            50.007 to 30.010,
            50.010 to 30.010, // End
        )

        val simplified = BreadcrumbTracker.simplifyRdp(pts, epsilonM = 5.0)
        // Collinear intermediate points should be stripped, corner must remain
        assertTrue(simplified.size in 3..4)
        assertEquals(50.0, simplified.first().first, 1e-4)
        assertEquals(30.0, simplified.first().second, 1e-4)
        assertEquals(50.010, simplified.last().first, 1e-4)
        assertEquals(30.010, simplified.last().second, 1e-4)
    }
}
