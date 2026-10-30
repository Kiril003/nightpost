package local.phantom.companion.core.sensor.nav

/**
 * Damps display jitter without ever inventing motion: every output is a convex
 * combination of fixes the chip actually reported, so it can decline to follow
 * but never lead. A parked phone stays parked.
 *
 * The part of a step the reported speed explains is followed whole; only the
 * excess is damped, against observed jitter rather than the chip's claimed
 * accuracy (under EW that claim is the first casualty). Idempotent per fix —
 * the display path pulls it more than once per fix.
 */
class FixSmoother(
    private val stillSpeedMps: Double = STILL_SPEED_MPS,
    private val maxLagM: Double = MAX_LAG_M,
    private val minNoiseM: Double = MIN_NOISE_M,
) {
    data class Smoothed(val lat: Double, val lon: Double)

    private var outLat = 0.0
    private var outLon = 0.0
    private var have = false
    private var lastElapsedMs = Long.MIN_VALUE
    private var noiseM = 0.0

    fun smooth(
        lat: Double,
        lon: Double,
        speedMps: Float?,
        elapsedMs: Long,
    ): Smoothed {
        if (have && elapsedMs == lastElapsedMs) return Smoothed(outLat, outLon)

        val dtS = if (have && elapsedMs > lastElapsedMs) (elapsedMs - lastElapsedMs) / 1000.0 else 0.0
        lastElapsedMs = elapsedMs

        if (!have) {
            outLat = lat
            outLon = lon
            have = true
            return Smoothed(outLat, outLon)
        }

        val step = haversineM(outLat, outLon, lat, lon)
        val explainedM = (speedMps?.toDouble() ?: 0.0).coerceAtLeast(0.0) * dtS
        val excessM = (step - explainedM).coerceAtLeast(0.0)
        val still = speedMps != null && speedMps < stillSpeedMps
        val band = maxOf(minNoiseM, noiseM) * (if (still) STILL_BAND else 1.0)

        val alpha = if (step <= 0.0) {
            0.0
        } else {
            val follow = (explainedM / step).coerceIn(0.0, 1.0)
            val damped = (excessM * excessM) / (excessM * excessM + band * band)
            (follow + (1.0 - follow) * damped).coerceIn(0.0, 1.0)
        }

        outLat += alpha * (lat - outLat)
        outLon += alpha * (lon - outLon)

        // Leash grows with jitter: a fixed one would snap onto every scatter
        // reading and hand the sawtooth back untouched.
        if (haversineM(outLat, outLon, lat, lon) > maxOf(maxLagM, band)) {
            outLat = lat
            outLon = lon
        }

        noiseM = if (noiseM == 0.0) excessM else NOISE_EMA * noiseM + (1.0 - NOISE_EMA) * excessM
        return Smoothed(outLat, outLon)
    }

    fun reset() {
        have = false
        lastElapsedMs = Long.MIN_VALUE
        noiseM = 0.0
    }

    companion object {
        /** ~2,5 km/h — under a walk. */
        const val STILL_SPEED_MPS = 0.7

        /** Dynamic lag ceiling: 15m allows responsive driving while holding stationary bounds. */
        const val MAX_LAG_M = 15.0

        /** Urban 1σ baseline noise. */
        const val MIN_NOISE_M = 15.0

        const val STILL_BAND = 3.0
        const val NOISE_EMA = 0.8
    }
}
