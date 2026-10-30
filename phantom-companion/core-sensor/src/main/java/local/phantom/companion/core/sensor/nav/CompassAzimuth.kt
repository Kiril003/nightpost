package local.phantom.companion.core.sensor.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * FIELD FIX (Kyiv, 2026-07-24) — the perpendicular arrow.
 *
 * `SensorManager.getOrientation(R, out)[0]` was read straight as the
 * operator's heading. That call answers a narrower question than it looks:
 * it returns yaw **in the device's own frame**, which is only the direction
 * a human is facing while the phone lies FLAT on its back. A pedestrian
 * holds the phone upright to read it — and upright puts the device's +Y
 * axis at the sky, where yaw about it stops describing anything on the
 * ground and degenerates entirely as the axis approaches vertical. The
 * operator saw a chevron pointing north while walking east.
 *
 * So do not ask for yaw. Ask which way the operator is FACING, and take it
 * from whichever device axis is actually lying in the horizontal plane:
 *
 *  - phone flat (reading it face-up): the edge that points up the SCREEN,
 *    which is the top edge only at rotation 0 — in landscape it is a side;
 *  - phone upright (reading it, or mounted): the rear camera's axis, which
 *    is horizontal exactly when the screen is vertical.
 *
 * Whichever of the two is closer to horizontal is the one that still carries
 * a bearing; the other is near-vertical and is noise. Between the two
 * postures the choice crosses over smoothly at 45°, and when BOTH are
 * vertical — the phone on its edge, screen to the sky — there is honestly no
 * heading to report and this returns null rather than a number.
 *
 * All of it is pure maths over the rotation matrix, so it is tested rather
 * than eyeballed on a device.
 */
object CompassAzimuth {

    /** `Surface.ROTATION_*`, kept local so the module stays framework-free. */
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    /**
     * Below this the candidate axis is too close to vertical for its
     * horizontal shadow to mean anything — a hair of tilt would swing the
     * answer through tens of degrees.
     */
    const val MIN_HORIZONTAL = 0.10f

    /**
     * The direction the operator is facing, degrees clockwise from true
     * north, or null when the posture carries no heading.
     *
     * [r] is the row-major 3×3 from `SensorManager.getRotationMatrixFromVector`,
     * which maps device coordinates into the world frame (East, North, Up).
     * [displayRotation] is the window manager's current rotation.
     */
    fun fromRotationMatrix(r: FloatArray, displayRotation: Int): Float? {
        if (r.size < 9) return null

        // Device axes expressed in the world frame are the matrix's columns.
        val xE = r[0]; val xN = r[3]; val xU = r[6]
        val yE = r[1]; val yN = r[4]; val yU = r[7]
        val zE = r[2]; val zN = r[5]; val zU = r[8]

        // «Up the screen» in device coordinates depends on how the UI is
        // rotated: at 90° the phone is turned but the operator still reads
        // along the screen's own vertical.
        val (upE, upN, upU) = when (displayRotation) {
            ROTATION_90 -> Triple(-xE, -xN, -xU)
            ROTATION_180 -> Triple(-yE, -yN, -yU)
            ROTATION_270 -> Triple(xE, xN, xU)
            else -> Triple(yE, yN, yU)
        }

        // Where the rear camera looks: the screen normal, reversed.
        val backE = -zE; val backN = -zN; val backU = -zU

        val useBack = abs(backU) < abs(upU)
        val e = if (useBack) backE else upE
        val n = if (useBack) backN else upN

        if (hypot(e, n) < MIN_HORIZONTAL) return null
        return normalizeDeg(Math.toDegrees(atan2(e.toDouble(), n.toDouble())).toFloat())
    }

    fun normalizeDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    /** Shortest signed turn from [from] to [to], in (-180, 180]. */
    fun deltaDeg(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f

    /**
     * A magnetometer-backed heading twitches by degrees between samples;
     * a chevron that twitches reads as a fault. Ease toward the reading
     * along the short way round, and let a genuine turn through by easing
     * proportionally rather than clamping.
     */
    fun smooth(previous: Float?, observed: Float, alpha: Float = SMOOTH_ALPHA): Float {
        if (previous == null) return normalizeDeg(observed)
        val a = alpha.coerceIn(0f, 1f)
        return normalizeDeg(previous + deltaDeg(previous, observed) * a)
    }

    /** One sample's share of the correction. Empirical: settles in ~10 samples. */
    const val SMOOTH_ALPHA = 0.25f
}
