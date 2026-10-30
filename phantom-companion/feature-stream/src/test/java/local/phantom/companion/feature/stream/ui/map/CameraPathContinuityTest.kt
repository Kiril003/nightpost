package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.nav.haversineM
import local.phantom.companion.core.sensor.nav.FixFlux
import local.phantom.companion.feature.stream.ui.map.nav.CameraDirector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIELD REPORT (owner, 2026-07-21) — «карта по осі пиздує»: in follow mode the
 * camera lurched sideways once per location update.
 *
 * The camera is not something this suite can look at, so it is measured
 * instead. These tests run the REAL chain the device runs — the
 * [RoadSnapCarry] decision, [FixFlux]'s spring, and [CameraDirector]'s
 * envelope — at 30 fps over a scripted drive, and assert the property the eye
 * was reporting on: **the camera path is continuous.** No production class is
 * mocked; only the fix stream is synthetic.
 *
 * The defect was never in the camera. It was that `displayLocation` fell back
 * `snap ?: raw`, so an intermittent road match handed the camera two DIFFERENT
 * positions on alternate ticks and it faithfully chased the alternation.
 */
class CameraPathContinuityTest {

    private val fixHz = 1
    private val frameMs = 33L // the canvas loop's ~30 fps

    private fun fix(
        lat: Double,
        lon: Double,
        speed: Float,
        bearing: Float? = 0f,
    ) = LocationFix(
        lat = lat, lon = lon, bearing = bearing, accuracyM = 6f,
        speedMps = speed, grade = FixGrade.NAVIGATION,
    )

    /**
     * Run [fixes] through carry → flux → director at frame rate, returning the
     * camera pose每 frame. [snaps] is the matcher's verdict per fix (null = a
     * miss), aligned by index with [fixes].
     */
    private fun drive(
        fixes: List<LocationFix>,
        snaps: List<LocationFix?>,
        carry: RoadSnapCarry = RoadSnapCarry(),
    ): List<CameraDirector.Directive> {
        val flux = FixFlux()
        val director = CameraDirector()
        val out = mutableListOf<CameraDirector.Directive>()
        var now = 1_000L
        var seeded = false

        fixes.forEachIndexed { i, raw ->
            val moving = (raw.speedMps ?: 0f) >= ROAD_SNAP_MIN_SPEED_MPS
            val display = carry.step(raw = raw, snap = snaps[i], moving = moving)
            flux.onTarget(
                lat = display.lat, lon = display.lon,
                speedMps = display.speedMps ?: 0f,
                bearingDeg = display.bearing,
                uncertaintyM = display.accuracyM ?: 0f,
                atMs = now, trusted = true,
            )
            // One fix interval of frames, exactly as the canvas loop runs.
            repeat(1000 / fixHz / frameMs.toInt()) {
                now += frameMs
                val eased = flux.step(now) ?: return@repeat
                // The canvas freezes course below walking speed; mirror that.
                val course = if (moving) eased.bearingDeg else null
                if (!seeded) {
                    director.seed(
                        lat = eased.lat, lon = eased.lon, zoom = 16.0,
                        tiltDeg = 0.0, bearingDeg = 0.0,
                        speedMps = display.speedMps ?: 0f,
                    )
                    seeded = true
                }
                out += director.step(
                    nowMs = now, lat = eased.lat, lon = eased.lon,
                    speedMps = display.speedMps ?: 0f,
                    courseBearingDeg = course,
                )
            }
        }
        return out
    }

    private fun maxStepM(path: List<CameraDirector.Directive>): Double =
        path.zipWithNext { a, b ->
            haversineM(a.targetLat to a.targetLon, b.targetLat to b.targetLon)
        }.maxOrNull() ?: 0.0

    /**
     * The per-frame step along EAST only — the axis the snap perturbs on a
     * due-north drive, and the «по осі» the owner reported. Total step is the
     * wrong instrument here: at 14 m/s the forward advance (~0.46 m/frame)
     * plus the director's initial zoom/lead glide dominate it and hide the
     * very oscillation under test.
     */
    private fun maxLateralStepM(path: List<CameraDirector.Directive>): Double =
        path.zipWithNext { a, b ->
            haversineM(a.targetLat to a.targetLon, a.targetLat to b.targetLon)
        }.maxOrNull() ?: 0.0

    /** Drop the director's seed transient — it glides from the seeded pose
     *  into its speed envelope once, and that is not what we are measuring. */
    private fun settled(path: List<CameraDirector.Directive>) = path.drop(60)

    private fun maxBearingStepDeg(path: List<CameraDirector.Directive>): Double =
        path.zipWithNext { a, b ->
            kotlin.math.abs(((b.bearingDeg - a.bearingDeg + 540.0) % 360.0) - 180.0)
        }.maxOrNull() ?: 0.0

    /**
     * THE REGRESSION. A straight 14 m/s drive north where the matcher answers
     * on alternate ticks with a snap ~8 m to the east — the shape of an OSRM
     * `/match` that sometimes lands under the confidence bar.
     *
     * Without the carry the display fix hops east↔west 8 m at 1 Hz and the
     * camera chases it. With it, the correction is held and decayed, so the
     * path only ever advances along the drive.
     */
    @Test
    fun `an intermittent road snap does not make the camera lurch sideways`() {
        val fixes = (0 until 20).map { i ->
            fix(lat = 50.4500 + i * 0.000126, lon = 30.5000, speed = 14f)
        }
        // ~8 m east at this latitude.
        val snaps = fixes.mapIndexed { i, f ->
            if (i % 2 == 0) f.copy(lon = f.lon + 0.000113) else null
        }

        val withCarry = maxLateralStepM(settled(drive(fixes, snaps)))

        // The honest control: the OLD behaviour, `snap ?: raw`, same inputs.
        val alternating = object {
            fun step(raw: LocationFix, snap: LocationFix?, moving: Boolean) =
                if (moving) snap ?: raw else raw
        }
        val flux = FixFlux(); val director = CameraDirector()
        val old = mutableListOf<CameraDirector.Directive>()
        var now = 1_000L; var seeded = false
        fixes.forEachIndexed { i, raw ->
            val d = alternating.step(raw, snaps[i], true)
            flux.onTarget(d.lat, d.lon, d.speedMps ?: 0f, d.bearing, d.accuracyM ?: 0f, now, true)
            repeat(30) {
                now += frameMs
                val e = flux.step(now) ?: return@repeat
                if (!seeded) {
                    director.seed(e.lat, e.lon, 16.0, 0.0, 0.0, d.speedMps ?: 0f)
                    seeded = true
                }
                old += director.step(now, e.lat, e.lon, d.speedMps ?: 0f, e.bearingDeg)
            }
        }
        val withoutCarry = maxLateralStepM(settled(old))

        // Peak-to-peak is the number that matches what the eye reported: how
        // far the camera swings sideways across the whole segment.
        fun spreadM(p: List<CameraDirector.Directive>): Double {
            val lons = p.map { it.targetLon }
            val lat = p.first().targetLat
            return haversineM(lat to (lons.minOrNull() ?: 0.0), lat to (lons.maxOrNull() ?: 0.0))
        }
        val oldSpread = spreadM(settled(old))
        val newSpread = spreadM(settled(drive(fixes, snaps)))

        // A held correction contributes NO lateral motion at all on a straight
        // drive: the offset is constant, so it cancels out of every difference.
        assertTrue(
            "carried path lurched $withCarry m sideways per frame — the " +
                "correction is not being held",
            withCarry < 0.05,
        )
        // The control must actually reproduce the defect, or this test proves
        // nothing. Same chain, fed by the old `snap ?: raw`. 0.15 m per frame
        // at 30 fps is the camera sliding sideways at ~4.5 m/s.
        assertTrue(
            "the control did not reproduce the lurch, so the comparison is " +
                "meaningless: old=$withoutCarry m/frame vs new=$withCarry m/frame",
            withoutCarry > 0.15,
        )
        assertTrue(
            "the old path should swing metres sideways; it swung $oldSpread m",
            oldSpread > 3.0,
        )
        assertTrue("the carried path swung $newSpread m sideways", newSpread < 0.2)
        println(
            "CAMERA LATERAL — per-frame step: old=$withoutCarry m  new=$withCarry m | " +
                "peak-to-peak swing: old=$oldSpread m  new=$newSpread m",
        )
    }

    /** «кожний мікрорух оновлює» — parked, with GPS scatter. The camera must
     *  not move and must not rotate at all. */
    @Test
    fun `a stationary noisy segment moves and rotates the camera by nothing`() {
        val rnd = java.util.Random(7)
        val fixes = (0 until 20).map {
            fix(
                lat = 50.4500 + (rnd.nextDouble() - 0.5) * 0.00008,
                lon = 30.5000 + (rnd.nextDouble() - 0.5) * 0.00008,
                speed = 0.2f,
                // Standing still, GNSS bearing is pure noise.
                bearing = rnd.nextFloat() * 360f,
            )
        }
        val path = drive(fixes, List(fixes.size) { null })

        assertEquals(
            "a parked camera may not rotate on bearing noise",
            0.0,
            maxBearingStepDeg(path),
            1e-9,
        )
        assertTrue(
            "parked camera drifted ${maxStepM(path)} m per frame on GPS scatter",
            maxStepM(path) < 0.5,
        )
    }

    /** The snap giving up must be a glide, not a step back to raw. */
    @Test
    fun `snap-on to snap-off is a glide, not a step`() {
        val fixes = (0 until 24).map { i ->
            fix(lat = 50.4500 + i * 0.000126, lon = 30.5000, speed = 14f)
        }
        // Matched for the first half, then the matcher goes dark for good.
        val snaps = fixes.mapIndexed { i, f ->
            if (i < 12) f.copy(lon = f.lon + 0.000113) else null
        }
        val path = drive(fixes, snaps)
        assertTrue(
            "release from the snap stepped ${maxStepM(path)} m in one frame",
            maxStepM(path) < 1.5,
        )
    }

    /** A snap that disagrees with the fused fix by more than a lane is a
     *  different road, and must be refused rather than carried. */
    @Test
    fun `an implausible snap is refused, not carried`() {
        val carry = RoadSnapCarry()
        val raw = fix(50.4500, 30.5000, 14f)
        val far = raw.copy(lat = raw.lat + 0.0009) // ~100 m
        assertEquals(raw.lat, carry.step(raw, far, moving = true).lat, 1e-12)
        assertTrue("nothing may be carried from a refused snap", !carry.carrying)
    }

    /** The carry must not outlive its welcome: after the grace window the pin
     *  belongs to the raw fix again. */
    @Test
    fun `the carry expires and hands the pin back to raw`() {
        val carry = RoadSnapCarry()
        val raw = fix(50.4500, 30.5000, 14f)
        carry.step(raw, raw.copy(lon = raw.lon + 0.000113), moving = true)

        // Through the grace window the correction is HELD in full — a miss is
        // missing data, not evidence the body moved back.
        repeat(ROAD_SNAP_GRACE_MISSES) {
            val held = carry.step(raw, null, moving = true)
            assertEquals(
                "the correction must be held, not decayed, inside the grace window",
                raw.lon + 0.000113, held.lon, 1e-12,
            )
        }
        // Then it lets go over the release ramp rather than stepping.
        val ramp = (1..ROAD_SNAP_RELEASE_STEPS).map { carry.step(raw, null, moving = true).lon }
        assertTrue(
            "the release must be monotonic toward raw, was $ramp",
            ramp.zipWithNext().all { (a, b) -> b < a } && ramp.last() > raw.lon,
        )
        val after = carry.step(raw, null, moving = true)
        assertEquals("the carry must expire back onto the raw fix", raw.lon, after.lon, 1e-12)
        assertTrue(!carry.carrying)
    }
}
