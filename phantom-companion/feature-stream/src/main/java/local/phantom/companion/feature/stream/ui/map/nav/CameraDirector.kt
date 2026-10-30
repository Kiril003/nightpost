package local.phantom.companion.feature.stream.ui.map.nav

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState

/**
 * HORIZON I.1 — the kinematic camera director. The car map is a windshield,
 * not a picture held at arm's length: the camera must lean into speed, zoom
 * out to the horizon on the open road and into the junction in the city, and
 * — the part the giants only half-do — look *ahead* of the operator, along
 * the corridor, not at the dot under his own hand.
 *
 * Pure math, no MapLibre types: it consumes a fix + speed + heading (+ the
 * kinematic verdict and, optionally, the curvature of the road ahead) and
 * emits a [Directive] — a target camera pose. A thin canvas wrapper applies
 * it per frame. Every output is critically smoothed here, on the same
 * discipline as [local.phantom.companion.core.sensor.nav.FixFlux]: the
 * director sets targets, its own springs make them fluid, so the wrapper can
 * write the pose straight to the camera without a second smoothing pass
 * fighting this one.
 *
 * The *envelope* (the speed→zoom/tilt/lead curves) is pure and unit-tested.
 * The *feel* — the exact constants — is a [DEVICE] tuning surface: they live
 * in the companion object, named, for the seat.
 */
class CameraDirector {

    data class Directive(
        /** Where the camera looks — LED AHEAD of the dot along its bearing. */
        val targetLat: Double,
        val targetLon: Double,
        val zoom: Double,
        val tiltDeg: Double,
        val bearingDeg: Double,
    )

    private var camLat = Double.NaN
    private var camLon = Double.NaN
    private var zoom = Double.NaN
    private var tilt = Double.NaN
    private var lead = Double.NaN
    private var bearing = Double.NaN
    private var zoomBias = 0.0
    private var lastMs = 0L

    /**
     * Latched so a walker waiting out a crossing (STILL after ~8 s of dwell)
     * does not pump the zoom a level out and back on every red light.
     */
    private var onFoot = false

    fun reset() {
        camLat = Double.NaN
        camLon = Double.NaN
        zoom = Double.NaN
        tilt = Double.NaN
        lead = Double.NaN
        bearing = Double.NaN
        zoomBias = 0.0
        onFoot = false
        lastMs = 0L
    }

    /**
     * Adopt the CURRENT camera pose as the glide's starting point. QA FIELD
     * FAILURE — the director used to cold-start by SNAPPING to its own
     * targets (zoom 17.5, tilt 0, whatever bearing it was handed), so the
     * locate tap teleported and spun the map in one frame. Every activation
     * (locate tap, gesture end) now seeds from where the camera actually is
     * and eases out; the operator's own zoom survives as a bias on the
     * envelope, because a pinch is a preference, not an error.
     */
    fun seed(
        lat: Double,
        lon: Double,
        zoom: Double,
        tiltDeg: Double,
        bearingDeg: Double,
        speedMps: Float,
        motion: MotionState? = null,
        /**
         * Keep the seeded zoom as a preference on the envelope. TRUE only when
         * resuming from the operator's own pinch — a locate tap seeded from a
         * route overview must glide IN to the envelope, not inherit the
         * overview's zoom as though the operator had chosen it.
         */
        adoptZoomBias: Boolean = false,
    ) {
        camLat = lat
        camLon = lon
        this.zoom = zoom
        this.tilt = tiltDeg
        this.bearing = bearingDeg
        if (lead.isNaN()) lead = 0.0
        latchGait(motion)
        zoomBias = if (adoptZoomBias) {
            (zoom - targetZoom(speedMps, motion)).coerceIn(ZOOM_BIAS_OUT, ZOOM_BIAS_IN)
        } else {
            0.0
        }
        lastMs = 0L
    }

    /**
     * A zoom BUTTON says exactly what a pinch says. The pinch was already
     * heard — [seed] adopts it as a bias on the envelope — while the button
     * fired an animateCamera the 30 ms follow write trampled on the next
     * frame, so the control was dead in the one mode a driver uses it. It
     * bends the same envelope, within the same limits, and the step's easing
     * carries it: a nudge glides, it does not cut.
     */
    fun nudgeZoomBias(deltaLevels: Double) {
        zoomBias = (zoomBias + deltaLevels).coerceIn(ZOOM_BIAS_OUT, ZOOM_BIAS_IN)
    }

    /**
     * One frame. [speedMps] comes from the fused fix; [courseBearingDeg] is
     * the direction of TRAVEL when there is believable travel to speak of —
     * and null when there is not (standstill, untrusted sky), in which case
     * the camera HOLDS its bearing. The compass never steers this camera: a
     * standing operator whose map spins to wherever his phone happens to
     * point is the «крутиться і зависає» field failure. [motion] from the
     * kinematic classifier (nullable); [curvatureDegPerM] is the |heading
     * change| per metre of the corridor just ahead, if known (0 = straight).
     */
    fun step(
        nowMs: Long,
        lat: Double,
        lon: Double,
        speedMps: Float,
        courseBearingDeg: Float? = null,
        motion: MotionState? = null,
        curvatureDegPerM: Double = 0.0,
    ): Directive {
        latchGait(motion)
        val zTarget = (targetZoom(speedMps, motion) + zoomBias)
            .coerceIn(ZOOM_FAST - 1.5 + ZOOM_BIAS_OUT, ZOOM_WALK + ZOOM_BIAS_IN)
        val tTarget = targetTilt(speedMps, motion, curvatureDegPerM)
        val lTarget = targetLead(speedMps, motion, curvatureDegPerM)

        var dtS = if (lastMs == 0L) 0.0 else (nowMs - lastMs) / 1000.0
        lastMs = nowMs
        dtS = dtS.coerceIn(0.0, MAX_FRAME_GAP_S)

        if (zoom.isNaN()) {
            // Never seeded (tests, headless): adopt targets without a glide.
            zoom = zTarget; tilt = tTarget; lead = lTarget
            bearing = courseBearingDeg?.toDouble() ?: 0.0
        } else {
            zoom = ease(zoom, zTarget, dtS, ZOOM_TAU_S)
            tilt = ease(tilt, tTarget, dtS, TILT_TAU_S)
            lead = ease(lead, lTarget, dtS, LEAD_TAU_S)
            if (courseBearingDeg != null) {
                bearing = easeAngle(bearing, courseBearingDeg.toDouble(), dtS, BEARING_TAU_S)
            }
        }

        // The camera looks at a point led ahead of the dot along the SMOOTHED
        // bearing — so a bearing swing sweeps the look-ahead point smoothly
        // rather than snapping the horizon sideways. The pose itself EASES to
        // that aim: a locate tap from three streets away is a glide, not a cut.
        val (aimLat, aimLon) = forward(lat, lon, bearing, lead)
        if (camLat.isNaN()) {
            camLat = aimLat; camLon = aimLon
        } else {
            camLat = ease(camLat, aimLat, dtS, POS_TAU_S)
            camLon = ease(camLon, aimLon, dtS, POS_TAU_S)
        }
        return Directive(camLat, camLon, zoom, tilt, bearing)
    }

    // ── The envelope (speed → pose), pure and testable ────────────────────

    private fun latchGait(motion: MotionState?) {
        when (motion) {
            MotionState.WALK, MotionState.RUN -> onFoot = true
            MotionState.CYCLE, MotionState.DRIVE,
            MotionState.TRAIN, MotionState.FLIGHT,
            -> onFoot = false
            else -> Unit
        }
    }

    private fun targetZoom(speedMps: Float, motion: MotionState?): Double {
        // ~0.76 m/px at the car's slow zoom: 1.3 m/s of walking moves the map
        // under 2 px/s, which is the «екран дубовий» the owner watched. The
        // pedestrian scale puts the same pace at ~5 px/s — visibly walking.
        if (onFoot) return ZOOM_WALK
        val base = lerpBySpeed(
            speedMps,
            slow = ZOOM_SLOW, mid = ZOOM_MID, fast = ZOOM_FAST,
        )
        return when (motion) {
            // Trains/flight cover ground too fast for a street zoom — pull all
            // the way out to the region.
            MotionState.TRAIN, MotionState.FLIGHT -> base - 1.5
            MotionState.STILL -> ZOOM_SLOW
            else -> base
        }.coerceIn(ZOOM_FAST - 1.5, ZOOM_SLOW)
    }

    private fun targetTilt(speedMps: Float, motion: MotionState?, curvature: Double): Double {
        // Standing still → top-down (orientation). Moving → lean into it.
        if (motion == MotionState.STILL || speedMps < STILL_SPEED_MPS) return 0.0
        // A walker decides which pavement and which crossing, not which lane:
        // a windshield lean spends his screen on a horizon he will reach in
        // four minutes.
        if (onFoot) return TILT_WALK
        val base = lerpBySpeed(speedMps, slow = TILT_SLOW, mid = TILT_MID, fast = TILT_FAST)
        // A bend ahead flattens the tilt a touch so the turn stays legible.
        val curveRelief = (curvature * CURVE_TILT_RELIEF).coerceIn(0.0, 20.0)
        return (base - curveRelief).coerceIn(0.0, TILT_FAST)
    }

    private fun targetLead(speedMps: Float, motion: MotionState?, curvature: Double): Double {
        if (motion == MotionState.STILL || speedMps < STILL_SPEED_MPS) return 0.0
        val seconds = if (onFoot) LEAD_SECONDS_WALK else LEAD_SECONDS
        // Look ~LEAD_SECONDS of travel ahead, capped so a highway doesn't
        // throw the camera a kilometre forward off the operator entirely.
        val raw = (speedMps * seconds).toDouble().coerceAtMost(LEAD_MAX_M)
        // Into a bend, shorten the lead — you look at the apex, not past it.
        val curveShorten = 1.0 - (curvature * CURVE_LEAD_SHORTEN).coerceIn(0.0, 0.6)
        return raw * curveShorten
    }

    private fun lerpBySpeed(speedMps: Float, slow: Double, mid: Double, fast: Double): Double {
        val s = speedMps.toDouble()
        return when {
            s <= SPEED_MID_MPS -> lerp(slow, mid, (s / SPEED_MID_MPS).coerceIn(0.0, 1.0))
            else -> lerp(mid, fast, ((s - SPEED_MID_MPS) / (SPEED_FAST_MPS - SPEED_MID_MPS)).coerceIn(0.0, 1.0))
        }
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun ease(cur: Double, target: Double, dtS: Double, tauS: Double): Double {
        val k = 1.0 - exp(-dtS / tauS)
        return cur + (target - cur) * k
    }

    private fun easeAngle(cur: Double, target: Double, dtS: Double, tauS: Double): Double {
        val delta = ((target - cur + 540.0) % 360.0) - 180.0
        val k = 1.0 - exp(-dtS / tauS)
        return (cur + delta * k + 360.0) % 360.0
    }

    /** Geodesic forward: [distM] metres from (lat, lon) along [bearingDeg]. */
    private fun forward(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        if (distM <= 0.0) return lat to lon
        val r = 6_371_000.0
        val d = distM / r
        val br = Math.toRadians(bearingDeg)
        val la = Math.toRadians(lat)
        val lo = Math.toRadians(lon)
        val la2 = asin(sin(la) * cos(d) + cos(la) * sin(d) * cos(br))
        val lo2 = lo + atan2(sin(br) * sin(d) * cos(la), cos(d) - sin(la) * sin(la2))
        return Math.toDegrees(la2) to Math.toDegrees(lo2)
    }

    companion object {
        // ── [DEVICE] tuning surface — the seat owns these numbers ─────────
        // Speed anchors (m/s): ~0, ~7 (25 km/h city), ~25 (90 km/h open road).
        const val SPEED_MID_MPS = 7.0
        const val SPEED_FAST_MPS = 25.0
        const val STILL_SPEED_MPS = 0.6

        // Zoom: closer when slow, wider when fast.
        const val ZOOM_SLOW = 17.5
        const val ZOOM_MID = 16.3
        const val ZOOM_FAST = 14.8
        const val ZOOM_WALK = 18.6

        // Tilt (degrees): top-down at rest, windshield-lean at speed.
        const val TILT_SLOW = 35.0
        const val TILT_MID = 52.0
        const val TILT_FAST = 62.0
        const val TILT_WALK = 15.0

        // Forward look-ahead.
        const val LEAD_SECONDS = 4.0
        const val LEAD_SECONDS_WALK = 8.0
        const val LEAD_MAX_M = 350.0

        // Curvature response.
        const val CURVE_TILT_RELIEF = 6.0
        const val CURVE_LEAD_SHORTEN = 2.5

        // Smoothing time constants (seconds).
        const val ZOOM_TAU_S = 0.7
        const val TILT_TAU_S = 0.9
        const val LEAD_TAU_S = 0.8
        const val BEARING_TAU_S = 0.45
        const val POS_TAU_S = 0.35

        // How far the operator's own pinch may bend the speed→zoom envelope.
        const val ZOOM_BIAS_OUT = -2.5
        const val ZOOM_BIAS_IN = 1.5

        private const val MAX_FRAME_GAP_S = 0.25
    }
}

/**
 * ARCH: PRECISION PIVOT (camera) — the follow camera's input decision, made pure
 * so it can be proven as a time series (see CameraStabilityTest).
 *
 * The owner reported the camera "breathing" (zoom pumping), pitching up and down,
 * teleporting, and twitching in rotation. The shared root cause was NOT accuracy
 * driving zoom (nothing couples them) — it was the follow loop gating three
 * things on the EW jam VERDICT, which flaps JAMMED on single-frequency hardware:
 *   • whether [FixFlux] snaps or slew-limits a jump,
 *   • the zoom/tilt envelope's speed (a motion-class cap toggled on/off),
 *   • whether the camera takes the travel course or holds its bearing.
 * A verdict that flaps therefore made all three flap, and the camera chased the
 * flapping. The pivot's doctrine is that EW is a passive badge that never touches
 * the display — so the camera now believes the FIX's own honesty (its accuracy),
 * never the verdict. A parked or cleanly-moving fix has steady accuracy, so these
 * inputs stop flapping and the envelope's easing does the rest.
 */
internal const val CAMERA_USABLE_ACC_M = 150f

/** A fix good enough to LEAD the camera by — judged on its own admitted radius,
 *  not on any jam verdict. Above this the camera slew-limits and holds bearing.
 *  When accuracy is unquantified (null), treat as usable so physical GPS chips
 *  rotate the camera into Heading-Up driving perspective rather than locking North. */
internal fun cameraFixUsable(accuracyM: Float?): Boolean =
    (accuracyM ?: 0f) <= CAMERA_USABLE_ACC_M

/** The speed the zoom/tilt envelope is driven by. A usable fix is believed; an
 *  unusable one is capped to what the kinematic class says the body can do, so a
 *  fictional 300 m/s never throws the envelope wide. Never a function of the jam
 *  verdict, so it does not flap. */
internal fun cameraSpeed(usable: Boolean, speedMps: Float, speedCapMps: Float): Float =
    if (usable) speedMps else minOf(speedMps, speedCapMps)

/** The bearing handed to the director: the travel course only while genuinely
 *  moving on a usable fix, else null (hold). The compass never steers the map. */
internal fun cameraCourse(usable: Boolean, moving: Boolean, easedBearingDeg: Float?): Float? =
    if (usable && moving) easedBearingDeg else null
