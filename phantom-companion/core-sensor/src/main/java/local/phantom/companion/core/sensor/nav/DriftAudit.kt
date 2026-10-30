package local.phantom.companion.core.sensor.nav

import kotlin.math.hypot
import local.phantom.companion.core.sensor.ImuSample

/**
 * PHANTOM-EW, layer 5 — the second authority.
 *
 * [SpoofGate] rejects the *impossible*. It says so itself, and it is right to:
 * physics is a hard authority, and hard authorities do not get to guess. But
 * the consequence was measured, and it is severe — a patient adversary who
 * never violates physics is never challenged at all. A drag of 1 m/s walked a
 * stationary operator 600 m in ten minutes with **zero** rejections, while the
 * engine reported ±5 m of confidence (`docs/EW_DRIFT_MATRIX.md`, F3).
 *
 * The engine already held the evidence to catch that, and never looked at it.
 * It runs an inertial track in parallel with the satellite one. A spoofer can
 * forge the sky; he cannot reach into the operator's pocket and turn the
 * accelerometer. So:
 *
 *   **Over a window, how far the satellites say you moved must agree with how
 *   far your own body says you moved — to within what dead reckoning could
 *   plausibly have got wrong.**
 *
 * A drag breaks that agreement, steadily and in one direction, which is exactly
 * what no honest GPS error does.
 *
 * ## The detection floor is real, and it is not a bug
 *
 * Dead reckoning has its own error, roughly proportional to distance travelled.
 * A drag slower than that error grows is, by construction, invisible here:
 *
 *   detectable drag  ≳  [drErrorFraction] × operator's speed
 *
 * At [drErrorFraction] = 0.18 and an 11 m/s urban drive, that is about 2 m/s.
 * Slower than that, and inertial corroboration cannot tell an adversary from
 * an honest sensor. **Catching the truly patient spoofer needs a third
 * authority — map-matching (is the operator on a road that exists?) or a known
 * start point.** That is not built, and this class does not pretend to it.
 *
 * ## What it cannot see
 *
 * The window is seeded with the *reported* GPS speed. An adversary who drags
 * the position AND forges a matching speed field, and who was already dragging
 * before the window opened, is consistent with himself and invisible to this
 * check. What he cannot survive is the *onset*: the moment a drag begins
 * mid-window, position and inertia part company.
 */
class DriftAudit(
    /** Long enough for a slow drag to accumulate; short enough to still be news. */
    private val windowMs: Long = WINDOW_MS,
    /** How wrong dead reckoning may honestly be, as a fraction of distance flown. */
    private val drErrorFraction: Double = DR_ERROR_FRACTION,
    /** Floor, for the standing-still case where the fraction gives nothing. */
    private val baseM: Double = BASE_TOLERANCE_M,
) {
    /**
     * A free-running inertial track. Seeded ONCE per window and never
     * re-anchored inside it — the instant it is re-seeded from GPS it stops
     * being independent evidence and becomes an echo of the thing it is meant
     * to be auditing.
     */
    private val inertial = DeadReckoner()

    private var open = false
    private var startMs = 0L
    private var startE = 0.0
    private var startN = 0.0

    /** Inertial displacement accumulated since the window opened. */
    private var inertE = 0.0
    private var inertN = 0.0

    /** Distance travelled since the window opened — the error budget scales on it. */
    private var travelled = 0.0

    /** The accuracy of the fix that opened the window. */
    private var startAccuracyM = 0f

    private var lastImuMs = 0L

    /** Last computed disagreement and the budget it was judged against. */
    var lastResidualM: Double = 0.0
        private set
    var lastBudgetM: Double = 0.0
        private set

    /** Hand the witness the sensor calibration the engine already paid for. */
    fun primeBias(bias: Float) {
        inertial.primeBias(bias)
    }

    fun reset() {
        inertial.reset()
        open = false
        startMs = 0L
        inertE = 0.0
        inertN = 0.0
        travelled = 0.0
        startAccuracyM = 0f
        lastImuMs = 0L
        lastResidualM = 0.0
        lastBudgetM = 0.0
    }

    fun onImu(sample: ImuSample, nowMs: Long) {
        val v = inertial.onImu(sample, nowMs)
        val dtS = if (lastImuMs == 0L) 0.0 else (nowMs - lastImuMs) / 1000.0
        lastImuMs = nowMs
        if (!open || v == null || dtS <= 0.0 || dtS > MAX_GAP_S) return

        inertE += v.first * dtS
        inertN += v.second * dtS
        travelled += hypot(v.first, v.second) * dtS
    }

    /** The audit's finding for a closed window. */
    enum class Finding {
        /** The window is still open; there is nothing to say yet. */
        PENDING,

        /** The sky and the body agree, to within what dead reckoning can miss. */
        AGREE,

        /** They do not. The sky is lying *plausibly* — the only way worth lying. */
        DIVERGE,
    }

    /**
     * Fold in a fix the gate was willing to accept.
     *
     * Returns [Finding.PENDING] until a full window has elapsed — a verdict on
     * five seconds of data would be noise wearing a uniform.
     */
    fun onAcceptedFix(
        e: Double,
        n: Double,
        speedMps: Float,
        bearingDeg: Float?,
        /** The chip's stated accuracy. It lands in the residual twice — once at
         *  each end of the window — so it has to be paid for in the budget. */
        accuracyM: Float,
        nowMs: Long,
    ): Finding {
        if (!open) {
            openWindow(e, n, speedMps, bearingDeg, accuracyM, nowMs)
            return Finding.PENDING
        }
        if (nowMs - startMs < windowMs) return Finding.PENDING

        val skyE = e - startE
        val skyN = n - startN
        val residual = hypot(skyE - inertE, skyN - inertN)

        // Three things can separate the sky from the body without anyone lying:
        //   · dead reckoning's own drift, proportional to distance carried;
        //   · the GPS noise sitting in the two endpoints of the window;
        //   · a floor, for the standing-still case where neither term bites.
        // Omitting the GPS term was a false-alarm generator: a 45 m urban canyon
        // puts ~42 m into the residual before anyone has done anything wrong, and
        // the audit called SPOOFED on an honest pedestrian in a canyon.
        val budget = drErrorFraction * travelled +
            GPS_NOISE_K * maxOf(accuracyM, startAccuracyM) +
            baseM

        lastResidualM = residual
        lastBudgetM = budget
        val diverged = residual > budget

        // Rebase either way: after a verdict, the next window must start from
        // the present, not from a point the operator left ten minutes ago.
        openWindow(e, n, speedMps, bearingDeg, accuracyM, nowMs)
        return if (diverged) Finding.DIVERGE else Finding.AGREE
    }

    private fun openWindow(
        e: Double,
        n: Double,
        speedMps: Float,
        bearingDeg: Float?,
        accuracyM: Float,
        nowMs: Long,
    ) {
        open = true
        startMs = nowMs
        startE = e
        startN = n
        startAccuracyM = accuracyM
        inertE = 0.0
        inertN = 0.0
        travelled = 0.0
        inertial.seed(speedMps, bearingDeg, nowMs)
    }

    companion object {
        const val WINDOW_MS = 60_000L

        /**
         * Dead reckoning's honest error, as a fraction of the distance it has
         * carried. Set from the measured drift of the repaired [DeadReckoner]
         * (`docs/EW_DRIFT_MATRIX.md`); it is the single dial that trades false
         * alarms against the slowest drag that can be seen at all.
         *
         * Too low and an honest urban canyon trips the alarm. Too high and the
         * adversary simply drives slower.
         */
        const val DR_ERROR_FRACTION = 0.20

        /** The GPS noise in the window's two endpoints, paid for honestly.
         *  Measured against clean canyon drives — see the matrix. */
        const val GPS_NOISE_K = 2.5

        const val BASE_TOLERANCE_M = 45.0

        private const val MAX_GAP_S = 2.0
    }
}
