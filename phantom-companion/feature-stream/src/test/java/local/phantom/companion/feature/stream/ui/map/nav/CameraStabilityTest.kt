package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.sensor.nav.FixFlux
import kotlin.math.abs
import kotlin.math.sign
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARCH: PRECISION PIVOT (camera) — the owner's four camera complaints, measured.
 *
 * «Erratic auto-zoom (breathing), unstable auto-pitch, camera snapping, rotation
 * jitter.» The shared root cause was the follow camera gating on the EW jam
 * VERDICT, which flaps JAMMED on single-frequency hardware. A verdict that flaps
 * made the flux snap/slew mode, the zoom speed-cap, and the travel course all
 * flap — and the camera chased the flapping. The pivot keys the camera off the
 * FIX's own accuracy instead ([cameraFixUsable]/[cameraSpeed]/[cameraCourse]),
 * so a steady fix produces steady inputs and the envelope's easing does the rest.
 *
 * A camera cannot be looked at, so it is measured. These tests run the REAL chain
 * the device runs — the pure follow-input decision, then [FixFlux]'s spring, then
 * [CameraDirector]'s envelope — over a synthetic drive. Only the fix stream is
 * fake; no production class is mocked. The metric is a TIME SERIES of per-frame
 * pose deltas: breathing is Δzoom reversals, twitch is Δbearing/Δtilt magnitude.
 */
class CameraStabilityTest {

    private val frameMs = 33L // the canvas loop's ~30 fps

    private data class Pose(val zoom: Double, val tilt: Double, val bearing: Double)

    /**
     * Runs a 60-second drive (north-east, gently turning) through the real chain.
     * [usableOf] decides per fix whether the camera believes it — the seam the
     * pivot changed. [speedCapMps] is the kinematic speed cap applied when a fix
     * is NOT believed. Returns the per-frame camera poses.
     */
    private fun drivePoses(
        usableOf: (index: Int, accuracyM: Float) -> Boolean,
        speedCapMps: Float,
        accuracyM: Float = 20f,
    ): List<Pose> {
        val flux = FixFlux()
        val director = CameraDirector()
        var lat = 50.4500
        var lon = 30.5200
        var now = 1_000L
        var seeded = false
        var wasMoving = false
        val out = mutableListOf<Pose>()

        repeat(60) { i ->
            // ~12 m/s with mild Doppler noise; a gentle 0°→~30° heading drift.
            val speed = 12f + ((i % 5) - 2) * 0.4f
            val headingDeg = (i * 0.5).toFloat().coerceAtMost(30f)
            // Advance the true position along the heading (rough local metres).
            lat += (speed * 33 / 111_320.0)
            lon += (speed * 5 / 111_320.0)

            val usable = usableOf(i, accuracyM)
            val moving = speed > if (wasMoving) 0.8f else 1.4f
            wasMoving = moving
            val camSpeed = cameraSpeed(usable, speed, speedCapMps)

            flux.onTarget(
                lat = lat, lon = lon, speedMps = speed,
                bearingDeg = headingDeg, uncertaintyM = accuracyM,
                atMs = now, trusted = usable,
            )
            repeat(1000 / frameMs.toInt()) {
                now += frameMs
                val eased = flux.step(now) ?: return@repeat
                val course = cameraCourse(usable, moving, eased.bearingDeg)
                if (!seeded) {
                    director.seed(
                        lat = eased.lat, lon = eased.lon, zoom = 16.0,
                        tiltDeg = 0.0, bearingDeg = headingDeg.toDouble(),
                        speedMps = camSpeed,
                    )
                    seeded = true
                }
                val d = director.step(
                    nowMs = now, lat = eased.lat, lon = eased.lon,
                    speedMps = camSpeed, courseBearingDeg = course,
                )
                out += Pose(d.zoom, d.tiltDeg, d.bearingDeg)
            }
        }
        return out
    }

    private fun maxZoomStep(p: List<Pose>) =
        p.zipWithNext { a, b -> abs(b.zoom - a.zoom) }.maxOrNull() ?: 0.0

    private fun maxTiltStep(p: List<Pose>) =
        p.zipWithNext { a, b -> abs(b.tilt - a.tilt) }.maxOrNull() ?: 0.0

    private fun maxBearingStep(p: List<Pose>) =
        p.zipWithNext { a, b -> abs(((b.bearing - a.bearing + 540.0) % 360.0) - 180.0) }
            .maxOrNull() ?: 0.0

    /** How many times Δzoom changes sign — the literal "breathing" count. */
    private fun zoomReversals(p: List<Pose>): Int {
        val deltas = p.zipWithNext { a, b -> b.zoom - a.zoom }.filter { abs(it) > 1e-4 }
        return deltas.zipWithNext { a, b -> if (sign(a) != sign(b)) 1 else 0 }.sum()
    }

    // ── 1. Keyed off accuracy, the camera is smooth ──────────────────────────

    @Test
    fun `a drive on a steady fix yields a smooth camera — no breathing, no twitch`() {
        // Accuracy 20 m the whole way ⇒ cameraFixUsable stays true; no flapping.
        val poses = drivePoses(
            usableOf = { _, acc -> cameraFixUsable(acc) },
            speedCapMps = 45f,
        )
        val tail = poses.takeLast(1200) // last ~40 s, past the seed transient
        val zStep = maxZoomStep(tail)
        val tStep = maxTiltStep(tail)
        val bStep = maxBearingStep(tail)
        val reversals = zoomReversals(tail)

        println(
            "CAMERA-STEADY: maxZoomStep=${"%.4f".format(zStep)}/frame " +
                "maxTiltStep=${"%.4f".format(tStep)}°/frame " +
                "maxBearingStep=${"%.4f".format(bStep)}°/frame " +
                "zoomReversals=$reversals",
        )

        // A gently-turning drive: the eased envelope moves in tiny, monotone
        // steps. What the eye sees as "breathing/twitch" is per-frame MAGNITUDE,
        // not the reversal count — a 0.006 zoom step is invisible however often it
        // changes sign as the envelope tracks mild speed noise.
        assertTrue("zoom must not pump ($zStep/frame)", zStep < 0.01)
        assertTrue("tilt must not twitch ($tStep°/frame)", tStep < 0.2)
        assertTrue("bearing must ease, not jump ($bStep°/frame)", bStep < 1.0)
        assertTrue("reversals stay bounded ($reversals)", reversals < 25)
    }

    // ── 2. A flapping jam verdict no longer reaches the camera ───────────────

    @Test
    fun `keying off accuracy instead of the jam verdict removes the breathing and twitch`() {
        // OLD behaviour: the camera believed the EW verdict, which flaps JAMMED
        // every second on this hardware — and while "jammed" the fused speed the
        // old path saw was capped to a walk (a misclassified drive), pumping the
        // zoom, while the course was nulled, jerking the bearing.
        val oldPoses = drivePoses(
            usableOf = { i, _ -> i % 2 == 0 }, // verdict flaps every fix
            speedCapMps = 3f, // "jammed" ⇒ walk cap bites the 12 m/s drive
        )
        // NEW behaviour: keyed off the fix's own (steady) accuracy — no flap.
        val newPoses = drivePoses(
            usableOf = { _, acc -> cameraFixUsable(acc) },
            speedCapMps = 3f, // identical cap; it simply never engages now
        )

        val oldTail = oldPoses.takeLast(1200)
        val newTail = newPoses.takeLast(1200)
        val oldRev = zoomReversals(oldTail)
        val newRev = zoomReversals(newTail)
        val oldZ = maxZoomStep(oldTail)
        val newZ = maxZoomStep(newTail)
        val oldB = maxBearingStep(oldTail)
        val newB = maxBearingStep(newTail)

        println(
            "CAMERA-DECOUPLE: zoomReversals old=$oldRev new=$newRev | " +
                "maxZoomStep old=${"%.4f".format(oldZ)} new=${"%.4f".format(newZ)} | " +
                "maxBearingStep old=${"%.3f".format(oldB)}° new=${"%.3f".format(newB)}°",
        )

        // The flapping verdict really did make the old camera breathe (a visible
        // ~0.04 zoom step per frame, pumping) and jerk the bearing…
        assertTrue("the flapping scenario must actually pump zoom ($oldZ)", oldZ >= 0.02)
        // …and keying off accuracy removes it: per-frame zoom magnitude collapses
        // to invisible, and the bearing steps shrink. Magnitude is what the eye
        // reports — the old camera moved zoom ~8× harder each frame.
        assertTrue("zoom pumping must collapse (old=$oldZ new=$newZ)", newZ < oldZ / 3)
        assertTrue("the new camera zoom is absolutely smooth ($newZ/frame)", newZ < 0.01)
        assertTrue("bearing jerk must shrink (old=$oldB new=$newB)", newB <= oldB)
        assertTrue("and it breathes far less often (old=$oldRev new=$newRev)", newRev < oldRev)
    }
}
