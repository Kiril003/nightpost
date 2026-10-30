package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HORIZON I.1 — the camera director's promises: it leans into speed, looks
 * ahead along the bearing, flattens for a standstill, and never jumps.
 */
class CameraDirectorTest {

    private val lat = 50.4501
    private val lon = 30.5234

    /** Settle the director at a constant condition and return the last pose. */
    private fun CameraDirector.settle(
        speed: Float, bearing: Float, motion: MotionState, curvature: Double = 0.0,
    ): CameraDirector.Directive {
        var d: CameraDirector.Directive? = null
        var t = 0L
        repeat(240) { // ~4 s at 60 fps
            d = step(t, lat, lon, speed, bearing, motion, curvature)
            t += 16
        }
        return d!!
    }

    @Test
    fun `standing still is top-down and looks at the dot`() {
        val d = CameraDirector().settle(0f, 0f, MotionState.STILL)
        assertEquals(0.0, d.tiltDeg, 0.5)
        // No lead: the target is the dot itself.
        assertEquals(lat, d.targetLat, 1e-5)
        assertEquals(lon, d.targetLon, 1e-5)
        assertEquals(CameraDirector.ZOOM_SLOW, d.zoom, 0.2)
    }

    @Test
    fun `open-road speed leans in, pulls out, and looks well ahead`() {
        val d = CameraDirector().settle(25f, 0f, MotionState.DRIVE) // ~90 km/h north
        assertTrue("should lean toward the windshield", d.tiltDeg > 55.0)
        assertTrue("should zoom out toward the horizon", d.zoom < 15.5)
        // Looking north: the target is meaningfully north of the dot —
        // ~LEAD_SECONDS × speed = ~100 m at 25 m/s.
        val northM = Math.toRadians(d.targetLat - lat) * 6_371_000.0
        assertTrue("camera should look well ahead at speed, was $northM", northM > 80.0)
    }

    @Test
    fun `city speed is between the two extremes`() {
        val slow = CameraDirector().settle(0f, 0f, MotionState.STILL).zoom
        val city = CameraDirector().settle(7f, 0f, MotionState.DRIVE).zoom
        val fast = CameraDirector().settle(25f, 0f, MotionState.DRIVE).zoom
        assertTrue("city zoom should sit between still and fast", city in fast..slow)
        assertTrue(city < slow)
        assertTrue(city > fast)
    }

    @Test
    fun `a train pulls the camera out to the region`() {
        val drive = CameraDirector().settle(25f, 0f, MotionState.DRIVE).zoom
        val train = CameraDirector().settle(25f, 0f, MotionState.TRAIN).zoom
        assertTrue("a train should be wider than a car at the same speed", train < drive)
    }

    @Test
    fun `the lead point follows the bearing`() {
        // Heading east: the look-ahead point is east of the dot.
        val d = CameraDirector().settle(20f, 90f, MotionState.DRIVE)
        assertTrue("target should be east of the dot", d.targetLon > lon)
        assertEquals("and on the dot's latitude", lat, d.targetLat, 1e-3)
    }

    @Test
    fun `pose eases without a jump on a speed step`() {
        val dir = CameraDirector()
        // Cruise settled at speed…
        var t = 0L
        var last = dir.step(t, lat, lon, 25f, 0f, MotionState.DRIVE)
        repeat(120) { t += 16; last = dir.step(t, lat, lon, 25f, 0f, MotionState.DRIVE) }
        // …then a hard stop. The very next frame must not teleport the tilt.
        t += 16
        val next = dir.step(t, lat, lon, 0f, 0f, MotionState.STILL)
        assertTrue("tilt jumped ${'$'}{last.tiltDeg} → ${'$'}{next.tiltDeg}", Math.abs(next.tiltDeg - last.tiltDeg) < 5.0)
    }

    // ── On foot: the owner's «екран дубовий не показував що йду» ─────────

    /** Ground metres per screen pixel at this zoom and latitude. */
    private fun metersPerPixel(zoom: Double): Double =
        156_543.03 * Math.cos(Math.toRadians(lat)) / Math.pow(2.0, zoom)

    @Test
    fun `walking pace visibly moves the map`() {
        val d = CameraDirector().settle(1.3f, 0f, MotionState.WALK)
        val pxPerSecond = 1.3 / metersPerPixel(d.zoom)
        // The car envelope put a walk at ~2 px/s — a frozen screen.
        assertTrue("walking moved the map $pxPerSecond px/s", pxPerSecond > 4.0)
    }

    @Test
    fun `a walker gets a flat map, not a windshield`() {
        val walk = CameraDirector().settle(1.3f, 0f, MotionState.WALK)
        val city = CameraDirector().settle(7f, 0f, MotionState.DRIVE)
        assertTrue("a footpath should not be pitched like a road", walk.tiltDeg < 25.0)
        assertTrue(walk.tiltDeg < city.tiltDeg)
        assertTrue("a walker reads his own block", walk.zoom > city.zoom)
    }

    @Test
    fun `waiting out a crossing does not pump the zoom`() {
        val dir = CameraDirector()
        var t = 0L
        var last = dir.step(t, lat, lon, 1.3f, 0f, MotionState.WALK)
        repeat(120) { t += 16; last = dir.step(t, lat, lon, 1.3f, 0f, MotionState.WALK) }
        val walking = last.zoom
        // The classifier calls STILL after ~8 s at the lights.
        repeat(600) { t += 16; last = dir.step(t, lat, lon, 0f, 0f, MotionState.STILL) }
        assertEquals("the zoom pulled out at a red light", walking, last.zoom, 0.1)
    }

    @Test
    fun `getting into a car leaves the pedestrian envelope`() {
        val dir = CameraDirector()
        var t = 0L
        repeat(120) { t += 16; dir.step(t, lat, lon, 1.3f, 0f, MotionState.WALK) }
        var last = dir.step(t, lat, lon, 20f, 0f, MotionState.DRIVE)
        repeat(600) { t += 16; last = dir.step(t, lat, lon, 20f, 0f, MotionState.DRIVE) }
        assertTrue("still framed for a walker at 72 km/h", last.zoom < CameraDirector.ZOOM_SLOW)
        assertTrue(last.tiltDeg > 45.0)
    }

    @Test
    fun `a sharp bend shortens the look-ahead`() {
        val straight = CameraDirector().settle(20f, 0f, MotionState.DRIVE)
        val bend = CameraDirector().settle(20f, 0f, MotionState.DRIVE, curvature = 0.2)
        val straightLead = Math.toRadians(straight.targetLat - lat) * 6_371_000.0
        val bendLead = Math.toRadians(bend.targetLat - lat) * 6_371_000.0
        assertTrue("a bend should pull the look-ahead in", bendLead < straightLead)
    }
}
