package local.phantom.companion.feature.stream.ui.map.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arbiter, on a fake clock. Every failure these lock is one the operator
 * has already reported on the device: dead zoom buttons in a drive, the map
 * «freezing» under a pinch, two authorities easing the same tilt.
 */
class CameraServiceTest {

    private class FakePort : MapPort {
        val writes = mutableListOf<String>()
        var current: CameraPose? = null

        override fun pose(): CameraPose? = current
        override fun move(pose: CameraPose) { writes += "move(${pose.zoom})"; current = pose }
        override fun flyTo(target: GeoPoint, zoom: Double?, durationMs: Int) {
            writes += "flyTo(${target.lat},${target.lon},$zoom)"
        }
        override fun fit(bounds: GeoBounds, padding: EdgeInsets, durationMs: Int) { writes += "fit" }
        override fun zoomBy(levels: Double, durationMs: Int) { writes += "zoomBy($levels)" }
        override fun bearingTo(degrees: Double, durationMs: Int) { writes += "bearingTo($degrees)" }
        override fun tiltTo(degrees: Double, durationMs: Int) { writes += "tiltTo($degrees)" }
        override fun flyover(path: List<GeoPoint>, durationMs: Int) { writes += "flyover(${path.size})" }
        override suspend fun animateAwait(pose: CameraPose, durationMs: Int): Boolean {
            writes += "animateAwait(${pose.lat},${pose.lon})"
            return true
        }
    }

    private var clock = 100_000L
    private val port = FakePort()
    private val service = CameraService(port) { clock }

    private fun tick(ms: Long) { clock += ms }

    private fun pose(zoom: Double = 16.0, lat: Double = 50.45, lon: Double = 30.52) =
        CameraPose(lat, lon, zoom, tiltDeg = 0.0, bearingDeg = 0.0)

    private fun arm() {
        service.submit(CameraIntent.FollowArm(issuedAtMs = clock))
        service.frame(pose())
    }

    // ── gesture preemption ────────────────────────────────────────────────

    @Test
    fun `a live gesture suppresses the follow write`() {
        arm()
        port.writes.clear()

        service.onGesture()
        val outcome = service.frame(pose(zoom = 17.0))

        assertEquals(CameraOutcome.Suppressed(SuppressReason.GESTURE_FRESH), outcome)
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun `the follow write resumes once the gesture window lapses`() {
        arm()
        service.onGesture()
        service.frame(pose(zoom = 17.0))

        tick(CameraService.GESTURE_FRESH_MS)
        val outcome = service.frame(pose(zoom = 17.0))

        assertTrue(outcome is CameraOutcome.FollowWrote)
        assertEquals(listOf("move(16.0)", "move(17.0)"), port.writes)
    }

    @Test
    fun `a gesture suppresses lower-priority intents but not the operator's own`() {
        service.onGesture()

        service.submit(CameraIntent.IdlePitch(tiltDeg = 45.0, issuedAtMs = clock))
        assertEquals(CameraOutcome.Suppressed(SuppressReason.FOLLOW_DISARMED), service.frame())

        service.submit(CameraIntent.BearingReset(issuedAtMs = clock))
        assertTrue(service.frame() is CameraOutcome.Applied)
        assertEquals(listOf("bearingTo(0.0)"), port.writes)
    }

    @Test
    fun `an assistant focus yields to a fresh gesture and lands after it`() {
        service.onGesture()
        service.submit(
            CameraIntent.Focus(
                GeoPoint(49.0, 32.0),
                zoom = 14.0,
                priority = CameraPriority.ASSISTANT,
                issuedAtMs = clock,
            ),
        )

        service.frame()
        assertTrue(port.writes.isEmpty())

        tick(CameraService.GESTURE_FRESH_MS)
        assertTrue(service.frame() is CameraOutcome.Applied)
        assertEquals(listOf("flyTo(49.0,32.0,14.0)"), port.writes)
    }

    // ── nudge during follow ───────────────────────────────────────────────

    @Test
    fun `a nudge during follow becomes an envelope bias, never a write`() {
        arm()
        port.writes.clear()

        service.submit(CameraIntent.ZoomNudge(1.0, issuedAtMs = clock))
        val outcome = service.frame(pose(zoom = 16.5))

        assertEquals(CameraOutcome.FollowBias(1.0), outcome)
        assertTrue("the button must not start an animation the follow loop tramples", port.writes.isEmpty())
        assertTrue(service.followArmed)
    }

    @Test
    fun `nudges accumulate — a double tap is one glide of two levels`() {
        arm()

        service.submit(CameraIntent.ZoomNudge(1.0, issuedAtMs = clock))
        service.submit(CameraIntent.ZoomNudge(1.0, issuedAtMs = clock))

        assertEquals(CameraOutcome.FollowBias(2.0), service.frame(pose()))
    }

    @Test
    fun `a nudge with follow disarmed animates the map directly`() {
        service.submit(CameraIntent.ZoomNudge(-1.0, issuedAtMs = clock))

        val outcome = service.frame()

        assertTrue(outcome is CameraOutcome.Applied)
        assertEquals(listOf("zoomBy(-1.0)"), port.writes)
    }

    @Test
    fun `a nudge during a gesture animates rather than biasing`() {
        arm()
        port.writes.clear()
        service.onGesture()

        service.submit(CameraIntent.ZoomNudge(1.0, issuedAtMs = clock))
        val outcome = service.frame(pose())

        assertTrue(outcome is CameraOutcome.Applied)
        assertEquals(listOf("zoomBy(1.0)"), port.writes)
    }

    @Test
    fun `a nudge is consumed exactly once`() {
        arm()
        service.submit(CameraIntent.ZoomNudge(1.0, issuedAtMs = clock))
        service.frame(pose())

        tick(40)
        assertTrue(service.frame(pose(zoom = 16.4)) is CameraOutcome.FollowWrote)
    }

    // ── one-shot vs continuous ────────────────────────────────────────────

    @Test
    fun `exactly one consumer writes the map per frame`() {
        arm()
        port.writes.clear()

        service.submit(CameraIntent.Focus(GeoPoint(50.0, 30.0), issuedAtMs = clock))
        service.frame(pose(zoom = 18.0))

        assertEquals(1, port.writes.size)
        assertEquals("flyTo(50.0,30.0,null)", port.writes.single())
    }

    @Test
    fun `a browse intent disarms the follow`() {
        arm()
        service.submit(CameraIntent.FitBounds(GeoBounds(49.0, 30.0, 51.0, 32.0), EdgeInsets(32, 112, 32, 96), issuedAtMs = clock))

        service.frame(pose())

        assertFalse(service.followArmed)
        assertEquals(CameraOutcome.Suppressed(SuppressReason.FOLLOW_DISARMED), service.frame(pose()))
    }

    @Test
    fun `a one-shot in flight is not interrupted by lower priority`() {
        service.submit(CameraIntent.Flyover(listOf(GeoPoint(50.0, 30.0)), durationMs = 3_000, issuedAtMs = clock))
        service.frame()
        port.writes.clear()

        tick(500)
        service.submit(
            CameraIntent.Focus(GeoPoint(48.0, 35.0), priority = CameraPriority.ASSISTANT, issuedAtMs = clock),
        )
        service.frame()

        assertTrue("the model must not cut a flyover short", port.writes.isEmpty())
    }

    @Test
    fun `the operator interrupts a one-shot in flight`() {
        service.submit(CameraIntent.Flyover(listOf(GeoPoint(50.0, 30.0)), durationMs = 3_000, issuedAtMs = clock))
        service.frame()
        port.writes.clear()

        tick(500)
        service.submit(CameraIntent.BearingReset(issuedAtMs = clock))
        service.frame()

        assertEquals(listOf("bearingTo(0.0)"), port.writes)
    }

    @Test
    fun `follow yields while a one-shot animation is still running`() {
        arm()
        service.submit(
            CameraIntent.Focus(
                GeoPoint(50.0, 30.0),
                priority = CameraPriority.ASSISTANT,
                disarmsFollow = false,
                issuedAtMs = clock,
            ),
        )
        service.frame(pose())
        port.writes.clear()

        tick(100)
        assertEquals(CameraOutcome.Suppressed(SuppressReason.ONESHOT_IN_FLIGHT), service.frame(pose(zoom = 17.0)))

        tick(CameraService.FOCUS_MS.toLong())
        assertTrue(service.frame(pose(zoom = 17.0)) is CameraOutcome.FollowWrote)
    }

    @Test
    fun `the idle pitch rule never argues with an armed follow`() {
        arm()
        port.writes.clear()

        service.submit(CameraIntent.IdlePitch(tiltDeg = 52.0, issuedAtMs = clock))
        val outcome = service.frame(pose(zoom = 16.0))

        assertTrue(port.writes.none { it.startsWith("tiltTo") })
        assertEquals(CameraOutcome.Suppressed(SuppressReason.DEADBAND), outcome)
    }

    @Test
    fun `the idle pitch rule applies on a browsing map`() {
        service.submit(CameraIntent.IdlePitch(tiltDeg = 52.0, issuedAtMs = clock))

        assertTrue(service.frame() is CameraOutcome.Applied)
        assertEquals(listOf("tiltTo(52.0)"), port.writes)
    }

    @Test
    fun `an unchanged pose is not rewritten`() {
        arm()
        port.writes.clear()

        tick(30)
        assertEquals(CameraOutcome.Suppressed(SuppressReason.DEADBAND), service.frame(pose()))
        assertTrue(port.writes.isEmpty())
    }

    // ── expiry ────────────────────────────────────────────────────────────

    @Test
    fun `a stale intent is dropped, never applied`() {
        val focus = CameraIntent.Focus(GeoPoint(50.0, 30.0), issuedAtMs = clock, staleAfterMs = 500)
        service.submit(focus)

        tick(501)
        service.frame()

        assertTrue("a replayed request from an hour ago is not an instruction", port.writes.isEmpty())
        assertEquals(focus, service.lastExpired)
    }

    @Test
    fun `an intent inside its freshness window still applies`() {
        service.submit(CameraIntent.Focus(GeoPoint(50.0, 30.0), issuedAtMs = clock, staleAfterMs = 500))

        tick(499)
        service.frame()

        assertEquals(listOf("flyTo(50.0,30.0,null)"), port.writes)
    }

    @Test
    fun `expiry does not cost the follow loop its frame`() {
        arm()
        port.writes.clear()
        service.submit(
            CameraIntent.Focus(GeoPoint(50.0, 30.0), priority = CameraPriority.AMBIENT, issuedAtMs = clock, staleAfterMs = 100),
        )

        tick(101)
        val outcome = service.frame(pose(zoom = 17.0))

        assertTrue(outcome is CameraOutcome.FollowWrote)
        assertEquals(listOf("move(17.0)"), port.writes)
    }

    @Test
    fun `a newer intent of equal priority replaces the pending one`() {
        service.submit(CameraIntent.Focus(GeoPoint(50.0, 30.0), issuedAtMs = clock))
        service.submit(CameraIntent.Focus(GeoPoint(48.0, 35.0), issuedAtMs = clock))

        service.frame()

        assertEquals(listOf("flyTo(48.0,35.0,null)"), port.writes)
    }

    @Test
    fun `a lower-priority intent does not displace a pending operator intent`() {
        service.submit(CameraIntent.BearingReset(issuedAtMs = clock))
        service.submit(
            CameraIntent.Focus(GeoPoint(48.0, 35.0), priority = CameraPriority.ASSISTANT, issuedAtMs = clock),
        )

        service.frame()

        assertEquals(listOf("bearingTo(0.0)"), port.writes)
    }
}
