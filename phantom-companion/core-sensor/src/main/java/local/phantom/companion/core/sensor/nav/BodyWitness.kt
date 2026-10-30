package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.ImuSample
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * ПОЛЬОВА ПОМИЛКА, 23.08 21:34 — «точка кудись в жопу зміщується».
 *
 * The phone lay on a desk. The chip delivered fixes claiming 1.3 → 3.9 → 6.5 →
 * 9.1 m/s and the display walked with them, because every witness the
 * [ConsistencyGate] had was a witness the transmitter also owns: the fix's own
 * position, the fix's own accuracy, the fix's own speed. The network anchor was
 * not enough — a fix that CLAIMS to be driving widens its own divergence margin
 * tenfold (see the gate), so a walking-pace spoof certifies itself.
 *
 * This is the witness that cannot be bribed from a distance: the operator's own
 * body. Two facts, both physical, neither of them a verdict:
 *
 *  1. **Barometric altitude** — air pressure in the room. A forged GPS altitude
 *     rate that the air contradicts is a lie, and the lie is measurable in m/s.
 *  2. **Vibration** — a body that is moving shakes (footfalls, road texture,
 *     engine load); a phone at rest on a desk is orders of magnitude quieter.
 *     Accelerometer AND gyroscope must both be quiet, which is a stricter bar
 *     than the shipped ZUPT ([DeadReckoner.isQuiet], accelerometer only).
 *
 * Deliberately NOT wired to [CustomLocationEngine]. That engine produces a
 * VERDICT ([LocationTrust]) which flaps TRUSTED↔DEGRADED once every six seconds
 * on a parked phone, and coupling the display path to a flapping verdict is the
 * breathing-zoom disaster this codebase already paid for once. Facts do not
 * flap; verdicts do. Only facts cross this line.
 *
 * Pure: no Android types, no clock of its own. Feed it samples and a `nowMs`.
 */
class BodyWitness(
    private val altimeter: Altimeter = Altimeter(),
) {
    private var vibMean = 0.0
    private var vibVar = 0.0
    private var gyroMean = 0.0
    private var gyroVar = 0.0

    private var lastImuMs = 0L
    private var lastPressureMs = 0L
    private var stillSinceMs = 0L
    private var movingSinceMs = 0L
    private var haveImu = false
    private var havePressure = false

    fun reset() {
        altimeter.reset()
        vibMean = 0.0
        vibVar = 0.0
        gyroMean = 0.0
        gyroVar = 0.0
        lastImuMs = 0L
        lastPressureMs = 0L
        stillSinceMs = 0L
        movingSinceMs = 0L
        haveImu = false
        havePressure = false
    }

    fun onPressure(hpa: Float, nowMs: Long) {
        altimeter.onPressure(hpa, nowMs)
        lastPressureMs = nowMs
        havePressure = true
    }

    /**
     * One IMU frame. Decimated to [MIN_IMU_INTERVAL_MS]: the navigation lease
     * runs the sensors at 50 Hz and a variance statistic does not need it, so
     * four of every five frames are dropped before any arithmetic happens.
     */
    fun onImu(sample: ImuSample, nowMs: Long) {
        val t = sample.elapsedMs.takeIf { it > 0L } ?: nowMs
        if (lastImuMs == 0L) {
            lastImuMs = t
            return
        }
        val dtS = (t - lastImuMs) / 1000.0
        // Backwards stamp: re-datum rather than wedge on a `lastImuMs` the feed
        // will never reach again.
        if (dtS < 0.0) {
            lastImuMs = t
            stillSinceMs = 0L
            movingSinceMs = 0L
            return
        }
        if (dtS * 1000.0 < MIN_IMU_INTERVAL_MS) return
        lastImuMs = t
        if (dtS > MAX_IMU_GAP_S) {
            // A gap means the statistic spans a blackout. Windows restart rather
            // than carry a stillness claim across time nobody observed.
            stillSinceMs = 0L
            movingSinceMs = 0L
            return
        }

        val accel = sqrt(
            (sample.accelX * sample.accelX +
                sample.accelY * sample.accelY +
                sample.accelZ * sample.accelZ).toDouble(),
        )
        val gyro = sqrt(
            (sample.gyroX * sample.gyroX +
                sample.gyroY * sample.gyroY +
                sample.gyroZ * sample.gyroZ).toDouble(),
        )
        val k = 1.0 - exp(-dtS / TAU_S)
        vibMean += (accel - vibMean) * k
        vibVar += ((accel - vibMean) * (accel - vibMean) - vibVar) * k
        gyroMean += (gyro - gyroMean) * k
        gyroVar += ((gyro - gyroMean) * (gyro - gyroMean) - gyroVar) * k
        haveImu = true

        val quiet = vibVar < STILL_ACCEL_VAR && gyroVar < STILL_GYRO_VAR
        if (quiet) {
            if (stillSinceMs == 0L) stillSinceMs = t
        } else {
            stillSinceMs = 0L
        }
        if (vibVar >= MOVING_ACCEL_VAR) {
            if (movingSinceMs == 0L) movingSinceMs = t
        } else {
            movingSinceMs = 0L
        }
    }

    fun evidence(nowMs: Long): BodyEvidence {
        val imuFresh = haveImu && lastImuMs != 0L && (nowMs - lastImuMs) <= EVIDENCE_STALE_MS
        val baroFresh = havePressure && (nowMs - lastPressureMs) <= EVIDENCE_STALE_MS
        return BodyEvidence(
            baroAltitudeM = if (baroFresh) altimeter.altitudeM else null,
            baroClimbMps = if (baroFresh) altimeter.climbMps else null,
            accelVar = if (imuFresh) vibVar.toFloat() else null,
            stillForMs = if (imuFresh && stillSinceMs != 0L) nowMs - stillSinceMs else 0L,
            movingForMs = if (imuFresh && movingSinceMs != 0L) nowMs - movingSinceMs else 0L,
        )
    }

    companion object {
        /** 25 Hz is plenty for a variance; the lease may deliver 50. */
        const val MIN_IMU_INTERVAL_MS = 40.0

        /** Past this the samples do not bracket a continuous observation. */
        const val MAX_IMU_GAP_S = 1.0

        /** EMA horizon, same as the shipped ZUPT's ([DeadReckoner]). */
        const val TAU_S = 0.5

        /**
         * Accelerometer-magnitude variance below this = physically at rest.
         * Same number the shipped ZUPT zeroes a vehicle's speed on
         * ([DeadReckoner.ZUPT_VARIANCE]); a phone on a desk sits an order of
         * magnitude under it, a phone in a rolling car well above.
         */
        const val STILL_ACCEL_VAR = 0.015

        /**
         * …and the gyroscope must agree. This is the bar the shipped detector
         * does NOT have, and it is what makes the stillness claim safe to
         * refuse a fix on: a phone mounted in a car that is smooth enough to
         * fool the accelerometer still yaws with every lane correction.
         */
        const val STILL_GYRO_VAR = 0.0004

        /** Variance that proves the platform CAN vibrate ([DeadReckoner] parity). */
        const val MOVING_ACCEL_VAR = 0.06

        /** Evidence older than this says nothing about now. */
        const val EVIDENCE_STALE_MS = 3_000L

        /** Stillness must be this old to veto anything ([PhysicalRefusal] parity). */
        const val STILL_VERDICT_HOLD_MS = 5_000L

        /** Motion is believed far sooner — a missed departure is the costly error. */
        const val MOVING_VERDICT_HOLD_MS = 1_000L
    }
}

/**
 * What the body reports, at one instant. Every field nullable-or-zero on
 * purpose: a device with no barometer, or an IMU lease nobody holds, must
 * produce «I have nothing to say» — never a fabricated stillness that would
 * refuse an honest fix.
 */
data class BodyEvidence(
    /** Metres above the session reference pressure, or null with no barometer. */
    val baroAltitudeM: Float? = null,
    /** Barometric vertical rate, m/s. Positive = climbing. */
    val baroClimbMps: Float? = null,
    /** Accelerometer-magnitude variance, or null with no fresh IMU. */
    val accelVar: Float? = null,
    val stillForMs: Long = 0L,
    val movingForMs: Long = 0L,
) {
    val hasImu: Boolean get() = accelVar != null
    val hasBaro: Boolean get() = baroAltitudeM != null

    /**
     * The body has been demonstrably at rest for [holdMs]. The caller picks the
     * hold: a refusal wants a long one, a lock release a shorter one.
     */
    fun stillFor(holdMs: Long): Boolean = hasImu && stillForMs >= holdMs

    /** The platform is vibrating like something that is actually going somewhere. */
    fun movingFor(holdMs: Long): Boolean = hasImu && movingForMs >= holdMs

    /**
     * true = confidently moving, false = confidently still, null = no opinion.
     * The gap between the two holds is deliberate: «maybe» must stay expressible,
     * because a caller that treats maybe-still as still freezes an honest phone.
     */
    fun movingVerdict(): Boolean? = when {
        movingFor(BodyWitness.MOVING_VERDICT_HOLD_MS) -> true
        stillFor(BodyWitness.STILL_VERDICT_HOLD_MS) -> false
        else -> null
    }
}

/**
 * The vertical-rate FACT, shared by the two things that ask it: [SpoofGate]
 * (which turns it into the operator-facing «висота GPS суперечить барометру»
 * verdict) and [ConsistencyGate] (which turns it into a refusal). One
 * computation, two thresholds — the verdict may keep its wide one, because a
 * false accusation costs the banner its meaning; the gate needs a tighter one,
 * because a false acceptance walks the operator across the district.
 */
object VerticalFact {
    /** Vertical rate implied by two altitudes, m/s. Null when unusable. */
    fun rateMps(fromM: Float?, toM: Float?, dtS: Double): Float? {
        if (fromM == null || toM == null || dtS <= 0.0) return null
        val r = (toM - fromM) / dtS
        return if (r.isFinite()) r.toFloat() else null
    }

    /** |GPS rate − barometric rate|, m/s. Null when either witness is silent. */
    fun disagreementMps(gpsRateMps: Float?, baroRateMps: Float?): Float? {
        if (gpsRateMps == null || baroRateMps == null) return null
        return abs(gpsRateMps - baroRateMps)
    }
}
