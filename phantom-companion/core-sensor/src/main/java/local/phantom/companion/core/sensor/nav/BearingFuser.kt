package local.phantom.companion.core.sensor.nav

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import local.phantom.companion.core.sensor.ImuSample

/**
 * IRONCLAD-1 — the fused course channel, and the end of the spinning arrow.
 *
 * ## The failure, named precisely
 *
 * [CustomLocationEngine.publish] used to derive the live bearing from the
 * Kalman filter's velocity vector. At cruise that vector points down the
 * road; at a red light it is centimetres-per-second of NOISE, and the
 * arrow's azimuth was `atan2(noise, noise)` — the operator watched his own
 * arrow spin at every standstill, and under EW a degraded fix could kick
 * the velocity (and the arrow) anywhere it liked for a beat.
 *
 * ## Why not "just run Madgwick"
 *
 * Madgwick/Mahony are ATTITUDE filters. The platform's rotation vector —
 * which [ImuSample] already carries — IS a vendor-calibrated filter of
 * that class, running against sensors we cannot even see raw (and our IMU
 * feed carries no magnetometer, so a hand-rolled AHRS could not produce
 * absolute yaw at all — only a worse copy of the relative yaw we already
 * have). The industrial upgrade is not to re-derive the attitude; it is to
 * apply the *complementary-filter principle* to the channel that actually
 * failed: COURSE.
 *
 *  - **Backbone (high frequency):** the device's yaw *delta* from the
 *    rotation vector, at sensor rate. Never its absolute yaw — a cupholder
 *    is not a compass (see [DeadReckoner]'s field note) — and when the
 *    attitude quaternion is absent, the raw Z gyro integrates as a
 *    last-resort flat-phone fallback, same "better than zero" doctrine as
 *    the reckoner's own frame fallback.
 *  - **Correction (low frequency):** GNSS course, admitted only from an
 *    ACCEPTED fix moving faster than [MIN_COURSE_SPEED_MPS] (below that,
 *    chip course is mush), pulled in with a [TAU_S] first-order gain and
 *    HARD-CLAMPED at [MAX_STEP_DEG] per fix.
 *
 * The clamp is the teleport immunity, by construction: a spoofed fix that
 * claims the operator suddenly drives south moves the course at most
 * [MAX_STEP_DEG]; a refused fix moves it not at all (refusals never reach
 * this class); a standstill moves it never (speed gate). Meanwhile a real
 * turn is tracked by the gyro backbone within the same sensor beat it
 * happens — instant AND smooth are not in tension, because they come from
 * different frequency bands. That is the whole idea of complementary
 * filtering, applied where this system actually bled.
 *
 * Pure Kotlin, clock-injected, one instance per engine. Display easing on
 * top of this (FixFlux, CameraDirector) is unchanged — they ease a truth
 * that no longer lies.
 */
class BearingFuser {

    /** Fused course over ground, radians, same convention as the yaw source. */
    private var courseRad = 0.0
    private var haveCourse = false

    private var lastYaw: Double? = null
    private var lastImuMs = 0L
    private var lastCourseMs = 0L

    /** Fused course over ground in degrees [0, 360), or null before the
     *  first course observation ever (cold start under a jam). */
    val courseDeg: Float?
        get() = if (haveCourse) {
            ((Math.toDegrees(courseRad) + 360.0) % 360.0).toFloat()
        } else {
            null
        }

    fun reset() {
        courseRad = 0.0
        haveCourse = false
        lastYaw = null
        lastImuMs = 0L
        lastCourseMs = 0L
    }

    /**
     * One IMU sample, at sensor rate, ALWAYS — trusted sky or dark. The
     * backbone must already be tracking when the correction source dies.
     */
    fun onImu(sample: ImuSample, nowMs: Long) {
        val dtS = if (lastImuMs == 0L) 0.0 else (nowMs - lastImuMs) / 1000.0
        lastImuMs = nowMs

        val yaw = yawOf(sample)
        if (yaw != null) {
            val prev = lastYaw
            lastYaw = yaw
            if (haveCourse && prev != null) {
                courseRad = wrap(courseRad + wrapSigned(yaw - prev))
            }
            return
        }
        // No attitude on this device/sample: integrate the raw Z gyro under
        // a flat-phone assumption. Android's convention is right-handed with
        // Z out of the screen, so positive gyroZ is a COUNTER-clockwise turn
        // seen from above — a negative course delta.
        if (haveCourse && dtS > 0.0 && dtS <= MAX_IMU_GAP_S) {
            courseRad = wrap(courseRad - sample.gyroZ * dtS)
        }
    }

    /**
     * The course an ACCEPTED fix reported, or null. Callers feed every
     * accepted fix; the gates live here so they are tested here.
     */
    fun onCourse(courseDeg: Float?, speedMps: Float, nowMs: Long) {
        if (courseDeg == null || speedMps < MIN_COURSE_SPEED_MPS) return
        val obs = Math.toRadians(courseDeg.toDouble())
        if (!haveCourse) {
            courseRad = wrap(obs)
            haveCourse = true
            lastCourseMs = nowMs
            return
        }
        val dtS = ((nowMs - lastCourseMs).coerceAtLeast(0L) / 1000.0)
            .coerceAtMost(MAX_CORRECTION_GAP_S)
        lastCourseMs = nowMs
        val k = 1.0 - exp(-dtS / TAU_S)
        val step = (wrapSigned(obs - courseRad) * k)
            .coerceIn(-MAX_STEP_RAD, MAX_STEP_RAD)
        courseRad = wrap(courseRad + step)
    }

    /** Yaw from the platform's attitude quaternion, or null if absent. */
    private fun yawOf(s: ImuSample): Double? {
        val w = s.rotW.toDouble()
        val x = s.rotX.toDouble()
        val y = s.rotY.toDouble()
        val z = s.rotZ.toDouble()
        if (w * w + x * x + y * y + z * z < 0.5) return null
        // Compass (clockwise) azimuth: Android's rotation is counter-clockwise about +Z.
        return -atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
    }

    private fun wrap(r: Double): Double = (r % TAU + TAU) % TAU

    private fun wrapSigned(r: Double): Double {
        var d = (r + PI) % TAU
        if (d < 0) d += TAU
        return d - PI
    }

    companion object {
        private const val TAU = 2.0 * PI

        /** Above 1.0 m/s (~3.6 km/h) the course is active travel. */
        const val MIN_COURSE_SPEED_MPS = 1.0f

        /** Fast absorption of turn headings (0.8s vs 2.5s). */
        const val TAU_S = 0.8

        /** Responsive turn step limit: 60° per fix. */
        const val MAX_STEP_DEG = 60.0
        val MAX_STEP_RAD = Math.toRadians(MAX_STEP_DEG)

        /** A correction after a long gap still steps at most this many
         *  seconds' worth of gain — a returning sky is evidence, not a snap. */
        const val MAX_CORRECTION_GAP_S = 3.0

        /** Mirror of [DeadReckoner.MAX_IMU_GAP_S]: a broken IMU stream is
         *  not integrated across. */
        const val MAX_IMU_GAP_S = 2.0
    }
}
