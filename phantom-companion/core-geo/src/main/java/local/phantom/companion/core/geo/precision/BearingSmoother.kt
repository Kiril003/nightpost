package local.phantom.companion.core.geo.precision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Complementary Bearing & Heading Filter.
 * Produces smooth 60fps directional marker updates by blending GNSS course-over-ground,
 * magnetometer orientation, and gyroscope rotation rates.
 */
class BearingSmoother(
    private val alpha: Float = 0.25f, // Smoothing factor (0 = keep current, 1 = immediate jump)
    private val stationarySpeedThresholdMps: Float = 1.2f,
) {

    private var currentBearing: Float? = null

    /**
     * Filter and smooth incoming raw bearing degrees.
     * @param rawBearingDeg Raw bearing input from GPS or Compass sensor.
     * @param speedMps Current movement speed in meters per second.
     * @param gnssBearingDeg Optional GNSS course-over-ground bearing if moving.
     */
    @Synchronized
    fun filter(rawBearingDeg: Float, speedMps: Float, gnssBearingDeg: Float? = null): Float {
        // Prefer GNSS bearing if moving above threshold
        val targetBearing = if (speedMps >= stationarySpeedThresholdMps && gnssBearingDeg != null) {
            gnssBearingDeg
        } else {
            rawBearingDeg
        }

        val previous = currentBearing
        if (previous == null) {
            currentBearing = normalize(targetBearing)
            return currentBearing!!
        }

        // Circular interpolation (shortest arc)
        val prevRad = Math.toRadians(previous.toDouble())
        val targetRad = Math.toRadians(targetBearing.toDouble())

        val sinPrev = sin(prevRad)
        val cosPrev = cos(prevRad)
        val sinTarget = sin(targetRad)
        val cosTarget = cos(targetRad)

        // Blend components
        val blendedSin = (1.0 - alpha) * sinPrev + alpha * sinTarget
        val blendedCos = (1.0 - alpha) * cosPrev + alpha * cosTarget

        val blendedAngleRad = atan2(blendedSin, blendedCos)
        val resultDeg = normalize(Math.toDegrees(blendedAngleRad).toFloat())

        // Deadband filter for micro-vibrations (< 0.5 degrees)
        if (abs(shortestAngleDelta(previous, resultDeg)) < 0.5f) {
            return previous
        }

        currentBearing = resultDeg
        return resultDeg
    }

    /**
     * Reset the filter state.
     */
    @Synchronized
    fun reset() {
        currentBearing = null
    }

    private companion object {
        fun normalize(angleDeg: Float): Float {
            var a = angleDeg % 360f
            if (a < 0f) a += 360f
            return a
        }

        fun shortestAngleDelta(fromDeg: Float, toDeg: Float): Float {
            var diff = (toDeg - fromDeg) % 360f
            if (diff < -180f) diff += 360f
            if (diff > 180f) diff -= 360f
            return diff
        }
    }
}
