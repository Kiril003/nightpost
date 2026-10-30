package local.phantom.companion.core.sensor.nav

import kotlin.math.abs
import kotlin.math.pow

/**
 * PHANTOM-EW, layer 4 — the barometer, and the question no map can answer.
 *
 * A GPS fix on the road and a GPS fix in the stairwell of the building beside
 * it are the same two numbers, and GPS altitude is far too noisy (±10-20 m,
 * worse in a canyon) to tell them apart. Air pressure is not: it resolves a
 * single floor, it costs nothing, and — the reason it is here — **it cannot be
 * spoofed from a distance.** A jammer can own the sky. It cannot own the air
 * in the room.
 *
 * Two jobs:
 *  1. tell PHANTOM whether the operator is on the road, climbing, or settled
 *     on some floor above it ([VerticalContext]);
 *  2. hand [SpoofGate] an independent witness against a forged GPS altitude.
 *
 * All relative to a session reference pressure — absolute altitude needs the
 * local QNH, which changes with the weather, and PHANTOM does not have it. The
 * honest quantity is the DIFFERENTIAL, and the differential is what the
 * questions above actually need.
 */
class Altimeter(
    /** Sea-level-ish reference; only differences matter, so the value is arbitrary. */
    private val smoothing: Double = PRESSURE_SMOOTHING,
) {
    private var referenceHpa: Double? = null
    private var smoothedHpa: Double = 0.0
    private var lastMs: Long = 0L

    /** Metres above the session reference. */
    var altitudeM: Float = 0f
        private set

    /** Positive = climbing, m/s. The witness [SpoofGate] cross-examines GPS with. */
    var climbMps: Float = 0f
        private set

    var context: VerticalContext = VerticalContext.UNKNOWN
        private set

    /** Sustained climb, in seconds — a lift or a stairwell, not a hill. */
    private var climbingForS: Double = 0.0

    fun reset() {
        referenceHpa = null
        lastMs = 0L
        altitudeM = 0f
        climbMps = 0f
        climbingForS = 0.0
        context = VerticalContext.UNKNOWN
    }

    /** Re-datum: the operator is known to be at street level here (e.g. a trusted road-matched fix). */
    fun rebase() {
        referenceHpa = smoothedHpa.takeIf { it > 0.0 }
        altitudeM = 0f
    }

    fun onPressure(hpa: Float, nowMs: Long) {
        if (hpa <= 0f || !hpa.isFinite()) return
        val p = hpa.toDouble()

        if (referenceHpa == null) {
            referenceHpa = p
            smoothedHpa = p
            lastMs = nowMs
            context = VerticalContext.GROUND
            return
        }
        val prevAlt = altitudeM
        smoothedHpa = smoothing * smoothedHpa + (1 - smoothing) * p

        // The barometric formula, in its standard-atmosphere form. Over the
        // tens of metres this class cares about it is effectively linear
        // (≈8.3 m per hPa), but the exact form costs nothing.
        val ratio = smoothedHpa / referenceHpa!!
        altitudeM = (44_330.0 * (1.0 - ratio.pow(1.0 / 5.255))).toFloat()

        val dtS = if (lastMs == 0L) 0.0 else (nowMs - lastMs) / 1000.0
        lastMs = nowMs
        if (dtS <= 0.0) return

        climbMps = ((altitudeM - prevAlt) / dtS).toFloat()

        // Классify. A lift does ~1-2 m/s; a stairwell ~0.3-0.5 m/s; a road
        // gradient, at speed, can also climb — which is why VERTICAL_TRANSIT
        // needs the climb SUSTAINED, and why the engine only trusts this
        // classification when the operator is slow or stationary.
        climbingForS = if (abs(climbMps) > CLIMB_THRESHOLD_MPS) climbingForS + dtS else 0.0
        context = when {
            climbingForS >= SUSTAINED_CLIMB_S -> VerticalContext.VERTICAL_TRANSIT
            abs(altitudeM) >= ELEVATED_THRESHOLD_M -> VerticalContext.ELEVATED
            else -> VerticalContext.GROUND
        }
    }

    companion object {
        const val PRESSURE_SMOOTHING = 0.85

        /** Slower than this is weather, not a body moving. */
        const val CLIMB_THRESHOLD_MPS = 0.25f

        /** A climb held this long is a building, not a bump in the road. */
        const val SUSTAINED_CLIMB_S = 3.0

        /** Roughly one storey and a half above the datum: the operator is inside, up. */
        const val ELEVATED_THRESHOLD_M = 4.5f
    }
}
