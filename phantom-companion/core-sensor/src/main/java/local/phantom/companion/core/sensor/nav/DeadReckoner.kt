package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.ImuSample
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * PHANTOM-EW, layer 3 — inertial navigation, and an honest account of what it
 * can and cannot do.
 *
 * **What it cannot do.** Double-integrating a phone's MEMS accelerometer to
 * get position is a well-known way to produce nonsense: bias drift is
 * integrated twice, and the error grows with t². Anyone who tells you their
 * app does "INS on a phone" and holds a metre for a minute is selling
 * something. Free inertial position is dead in about ten seconds.
 *
 * **What it can do,** and what this class actually does: hold *velocity* and
 * *heading* over the seconds-to-minutes window that a jamming episode
 * occupies, by refusing to integrate twice. Speed comes from the last trusted
 * GPS speed, corrected by the along-track acceleration; heading comes from the
 * rotation vector (gyro + magnetometer fused by the platform), which does not
 * drift the way a bare gyro does. That gives a velocity vector, which
 * [Kalman2D] integrates ONCE — and whose error therefore grows with t, not t².
 *
 * The result still drifts. The engine's contract is not «this is where you
 * are» but «this is my best estimate and here is how wrong it probably is»
 * — see [FusedFix.uncertaintyM].
 *
 * ## Two corrections, 2026-07-14 (see `docs/EW_DRIFT_MATRIX.md`)
 *
 * **The speed decay is gone, and with it the worst defect in the engine.**
 * The old code integrated the along-track acceleration only when it rose above
 * the sensor's noise floor, and otherwise bled the speed away at 33 %/s — the
 * idea being that an operator who has actually stopped must not keep gliding
 * across the map. But a car holding a steady 30 m/s produces *almost no linear
 * acceleration at all*, so «cruising» and «stopped» look identical to that
 * rule, and the reckoner chose «stopped». Measured: a true 30 m/s decayed to
 * 1.1 m/s within ten seconds. Dead reckoning did not drift — **it parked**, and
 * the operator drove away from his own dot.
 *
 * There is no fixing this by tuning the threshold, because the ambiguity is
 * *physical*: an accelerometer cannot distinguish constant velocity from rest.
 * Galileo settled that. So the reckoner no longer tries. It integrates the
 * along-track acceleration honestly and holds the speed it was seeded with.
 *
 * A real stop is then caught by the thing that actually announces one — the
 * **braking deceleration**, which is large, sustained and unmistakable. (The
 * old rule was never what held a stopped operator still: the engine seeds speed
 * from the last GPS fix, and a stationary operator is seeded at zero.)
 *
 * The price is honest and bounded: with no measurement, an accelerometer bias
 * b integrates into a speed error of b·t — about 3 m/s after a minute at a
 * typical 0.05 m/s² bias. Linear in time, not quadratic, and the covariance in
 * [Kalman2D] is what tells the operator about it.
 *
 * **Heading is now course over ground, not the phone's yaw.** The platform's
 * rotation vector reports where the *device* is pointing. A phone in a
 * cupholder, a pocket or a hand is not aligned with the road, so its yaw is not
 * the direction of travel — and the old code overwrote its GPS-seeded bearing
 * with that yaw on the very next IMU sample, which made the seed pointless.
 *
 * What is trustworthy is the *change*: if the phone does not rotate relative to
 * the vehicle, Δ(device yaw) = Δ(course). So the reckoner seeds the course from
 * the last GPS bearing and then **propagates it by the device's yaw delta**,
 * never by its absolute yaw. A phone that is re-gripped mid-blackout will
 * corrupt this, and nothing on the device can tell us that it happened; the
 * covariance is the only honest answer.
 */
class DeadReckoner {
    /** Along-track speed, m/s. Seeded from the last trusted fix. */
    private var speedMps: Double = 0.0

    /** Course over ground in radians, north-up — the direction of TRAVEL. */
    private var courseRad: Double = 0.0
    private var haveCourse = false

    /** The device's own yaw when the course was last seeded. */
    private var yawAtSeed: Double? = null
    private var courseAtSeed: Double = 0.0

    /** Last yaw the platform reported, so a seed arriving between IMU samples
     *  still has something to latch onto. */
    private var lastYaw: Double? = null

    private var lastMs: Long = 0L

    /** Learned along-track accelerometer bias, m/s². See [observeBias]. */
    private var biasAlong: Double = 0.0

    /** Start of the current bias-estimation baseline. */
    private var biasWindowMs: Long = 0L
    private var biasWindowSpeed: Double = 0.0
    /** Along-track velocity the accelerometer has integrated since the window
     *  opened — accumulated ACROSS seeds, unlike [speedMps]. */
    private var integSinceWindow: Double = 0.0

    /** The bias the engine is currently correcting for — diagnostics only. */
    val accelBiasMps2: Float get() = biasAlong.toFloat()

    // ZUPT (zero-velocity update) — the one stop detector that does not
    // confuse cruising with rest. The braking-deceleration story above covers
    // a clean stop; the Kyiv field drive showed the case it misses: a long
    // jam begins while the operator is parked or creeping, the reckoner is
    // seeded with a speed, and with no measurement it glides that speed for
    // kilometres. Linear ACCELERATION cannot separate cruise from rest — but
    // VIBRATION can: a vehicle in motion shakes (road texture, engine load)
    // and a phone at true rest is orders of magnitude quieter. Track the
    // variance of the horizontal world-frame acceleration; when it stays
    // below [ZUPT_VARIANCE] for [ZUPT_HOLD_MS], the platform is physically
    // still and the speed is zeroed. Conservative on purpose ([DEVICE]-tuned
    // constants): a false negative costs nothing we do not already pay.
    private var vibMean = 0.0
    private var vibVar = 0.0
    private var stillSinceMs = 0L

    /** The ZUPT must ARM before it may fire: it needs to have SEEN vibration
     *  — SUSTAINED, for [ZUPT_ARM_HOLD_MS], not a braking transient — and
     *  watched it cease. Quiet alone proves nothing — a platform that never
     *  vibrated (a synthetic feed, the EW simulator's noise-only model) is
     *  not evidence of a stop, and an unarmed ZUPT zeroing a clean cruise
     *  would be F2 all over again. */
    private var zuptArmed = false
    private var armCandidateSinceMs = 0L

    /**
     * Adopt a bias already learned elsewhere.
     *
     * The accelerometer's bias is a property of the *sensor*, not of whoever is
     * integrating it — so a second reckoner (the one [DriftAudit] runs as an
     * independent witness) should not have to rediscover it from scratch. It
     * cannot, in fact: the audit re-seeds only once a minute, and the estimator
     * needs a ten-second baseline between seeds, so its own bias would stay at
     * zero forever. It then integrated a raw 0.05 m/s² for a full window and
     * cried «spoof» on an honest pedestrian.
     *
     * The bias itself is learned against GPS speed, so an adversary forging
     * speed can poison it. That is a real limit, recorded in the matrix.
     */
    fun primeBias(bias: Float) {
        biasAlong = bias.toDouble().coerceIn(-MAX_BIAS_MPS2, MAX_BIAS_MPS2)
    }

    /**
     * Seed from the last fix PHANTOM believed.
     *
     * This is also where the accelerometer's **bias is learned, for free**.
     * Removing the old speed decay left one honest cost: an accelerometer bias
     * b integrates straight into a speed error of b·t, and a cheap MEMS part
     * biased by 0.05 m/s² is 3 m/s wrong after a minute of blindness. In the
     * matrix that showed up as a *pedestrian* being flung 1.5 km off during a
     * five-minute blackout — the drift dwarfed the walk.
     *
     * But while the sky is trusted, the bias is *observable*: GPS states the
     * true speed, and the difference between that and what the reckoner
     * integrated to, over the interval, is the bias times the interval. So the
     * engine's every accepted fix quietly calibrates the sensor that will have
     * to carry the operator when that sky goes out. The estimate is what makes
     * honest integration affordable.
     */
    fun seed(speedMps: Float, bearingDeg: Float?, nowMs: Long) {
        observeBias(speedMps.toDouble(), nowMs)

        this.speedMps = speedMps.toDouble()
        if (bearingDeg != null) {
            courseRad = Math.toRadians(bearingDeg.toDouble())
            courseAtSeed = courseRad
            // Latch the device's current attitude as the reference for the
            // course. Until an IMU sample arrives we have no yaw to latch, and
            // the first one will do it.
            yawAtSeed = lastYaw
            haveCourse = true
        }
        lastMs = nowMs
    }

    /**
     * Estimate the accelerometer's along-track bias against GPS speed.
     *
     * **The baseline has to be long, and that is the whole trick.** The bias is
     * inferred from `(what the accelerometer integrated) − (what GPS says)`,
     * divided by the interval. Over a ONE-SECOND interval — which is how often
     * fixes arrive — GPS speed noise of ±0.4 m/s becomes ±0.4 m/s² of apparent
     * bias: eight times larger than the ~0.05 m/s² we are trying to measure.
     * The first cut of this did exactly that, and spent the blackout subtracting
     * a bias it had hallucinated from noise; a pedestrian's 300 s drift got
     * *worse* (364 m → 822 m).
     *
     * Over a ten-second baseline the same noise divides down to ±0.06 m/s², and
     * the estimate becomes worth having. So the integration is accumulated
     * across seeds and only cashed in when the window is long enough.
     */
    private fun observeBias(observedSpeed: Double, nowMs: Long) {
        if (!haveCourse) return

        if (biasWindowMs == 0L) {
            biasWindowMs = nowMs
            biasWindowSpeed = observedSpeed
            integSinceWindow = 0.0
            return
        }

        val dt = (nowMs - biasWindowMs) / 1000.0
        if (dt < BIAS_WINDOW_S) return
        if (dt > BIAS_WINDOW_MAX_S) {
            // The window spanned a blackout; the GPS endpoints do not bracket a
            // continuous integration any more. Start again rather than learn a
            // lie.
            biasWindowMs = nowMs
            biasWindowSpeed = observedSpeed
            integSinceWindow = 0.0
            return
        }

        // What the sky says the speed did, versus what the accelerometer thought.
        val observedDelta = observedSpeed - biasWindowSpeed
        val predictedDelta = integSinceWindow
        val residual = (predictedDelta - observedDelta) / dt

        biasAlong = (biasAlong + BIAS_GAIN * residual)
            .coerceIn(-MAX_BIAS_MPS2, MAX_BIAS_MPS2)

        biasWindowMs = nowMs
        biasWindowSpeed = observedSpeed
        integSinceWindow = 0.0
    }

    fun reset() {
        speedMps = 0.0
        courseRad = 0.0
        courseAtSeed = 0.0
        haveCourse = false
        yawAtSeed = null
        lastYaw = null
        lastMs = 0L
        biasAlong = 0.0
        biasWindowMs = 0L
        biasWindowSpeed = 0.0
        integSinceWindow = 0.0
        vibMean = 0.0
        vibVar = 0.0
        stillSinceMs = 0L
        zuptArmed = false
        armCandidateSinceMs = 0L
    }

    /**
     * One IMU sample. Returns the current inertial velocity as (east, north)
     * m/s, or null while the reckoner has no course to project onto.
     */
    fun onImu(sample: ImuSample, nowMs: Long): Pair<Double, Double>? {
        val dtS = if (lastMs == 0L) 0.0 else (nowMs - lastMs) / 1000.0
        lastMs = nowMs

        val yaw = yawOf(sample)
        if (yaw != null) {
            if (haveCourse) {
                // Propagate the course by how far the DEVICE turned, never by
                // where the device is pointing. A cupholder is not a compass.
                val anchor = yawAtSeed ?: yaw.also { yawAtSeed = it }
                courseRad = wrap(courseAtSeed + angleDelta(anchor, yaw))
            } else {
                // No GPS bearing was ever seen (cold start under a jammer). The
                // device's yaw is a poor proxy for the course, but it is the
                // only proxy there is, and saying nothing is worse.
                courseRad = yaw
                haveCourse = true
                courseAtSeed = yaw
                yawAtSeed = yaw
            }
            lastYaw = yaw
        }

        if (dtS <= 0.0 || dtS > MAX_IMU_GAP_S) return velocity()

        // The accelerometer speaks in the device's frame. Rotate it into the
        // world with the platform's own attitude quaternion — the old code
        // projected raw device axes onto a heading, which is only correct if
        // the phone happens to be aligned with the road.
        // Gravity is a known constant in the WORLD frame, and it lives entirely
        // on the vertical axis there — so the horizontal components need no
        // gravity correction at all. The old low-pass had to *guess* at gravity,
        // and a low-pass cannot tell gravity from a long, gentle acceleration.
        // The vertical channel is the altimeter's business, not ours.
        val (wx, wy, _) = rotateToWorld(sample)

        val alongTrack = wx * sin(courseRad) + wy * cos(courseRad) - biasAlong
        speedMps = (speedMps + alongTrack * dtS).coerceIn(0.0, MAX_SPEED_MPS)

        // ZUPT — see the field note at the state above.
        val mag = Math.hypot(wx, wy)
        val kv = 1.0 - Math.exp(-dtS / ZUPT_TAU_S)
        vibMean += (mag - vibMean) * kv
        val dev = (mag - vibMean) * (mag - vibMean)
        vibVar += (dev - vibVar) * kv
        if (vibVar > ZUPT_ARM_VARIANCE) {
            if (armCandidateSinceMs == 0L) armCandidateSinceMs = nowMs
            if (nowMs - armCandidateSinceMs >= ZUPT_ARM_HOLD_MS) zuptArmed = true
        } else {
            armCandidateSinceMs = 0L
        }
        if (zuptArmed && vibVar < ZUPT_VARIANCE) {
            if (stillSinceMs == 0L) stillSinceMs = nowMs
            if (nowMs - stillSinceMs >= ZUPT_HOLD_MS) speedMps = 0.0
        } else {
            stillSinceMs = 0L
        }

        // The bias baseline integrates across seeds — it must see what the
        // accelerometer alone thinks the speed did, over ten seconds, not what
        // it thinks between two GPS fixes one second apart.
        integSinceWindow += alongTrack * dtS

        return velocity()
    }

    private fun velocity(): Pair<Double, Double>? {
        if (!haveCourse) return null
        return speedMps * sin(courseRad) to speedMps * cos(courseRad)
    }

    val heading: Float? get() = if (haveCourse) {
        ((Math.toDegrees(courseRad) + 360.0) % 360.0).toFloat()
    } else {
        null
    }

    val speed: Float get() = speedMps.toFloat()

    /**
     * The vibration floor is quiet RIGHT NOW. Deliberately weaker than the
     * ZUPT (no arming): an unarmed claim of quiet may not zero a speed — a
     * smooth cruise mimics it — but combined with the filter's own ~zero
     * velocity it is enough to say «this body is not running anywhere», and
     * that is the witness the engine's doubt growth consults at a standstill.
     */
    val isQuiet: Boolean get() = vibVar < ZUPT_VARIANCE

    /**
     * P0 JAMMED-WHILE-PARKED — the platform is vibrating like a MOVING VEHICLE
     * right now, at the same [ZUPT_ARM_VARIANCE] the ZUPT uses to prove the
     * platform can vibrate at all. A parked phone with ordinary MEMS noise sits
     * an order of magnitude below this; a car, a bus, a train do not. The
     * engine's stationary gate reads this as «there is real motion to reckon»,
     * so that a phone on a desk is never dead-reckoned across the map on sensor
     * noise. Distinct from [isQuiet] on purpose: the gap between the two
     * thresholds is «maybe» — neither confidently still nor confidently moving —
     * and the second witness (the fused speed) breaks the tie.
     */
    val isMoving: Boolean get() = vibVar >= ZUPT_ARM_VARIANCE

    /**
     * The device's linear+gravity acceleration, rotated from the device frame
     * into world ENU by the attitude quaternion: v' = q v q*.
     */
    private fun rotateToWorld(s: ImuSample): Triple<Double, Double, Double> {
        val w = s.rotW.toDouble()
        val x = s.rotX.toDouble()
        val y = s.rotY.toDouble()
        val z = s.rotZ.toDouble()
        val vx = s.accelX.toDouble()
        val vy = s.accelY.toDouble()
        val vz = s.accelZ.toDouble()

        val norm = w * w + x * x + y * y + z * z
        if (norm < 0.5) {
            // No usable attitude. Treat the device as world-aligned — wrong, but
            // it is what the old code always did, and it is better than zero.
            return Triple(vx, vy, vz)
        }

        // t = 2 (q_vec × v);  v' = v + w·t + (q_vec × t)
        val tx = 2.0 * (y * vz - z * vy)
        val ty = 2.0 * (z * vx - x * vz)
        val tz = 2.0 * (x * vy - y * vx)
        return Triple(
            vx + w * tx + (y * tz - z * ty),
            vy + w * ty + (z * tx - x * tz),
            vz + w * tz + (x * ty - y * tx),
        )
    }

    /** Yaw (rotation about the vertical) from the sample's quaternion, or null if absent. */
    private fun yawOf(s: ImuSample): Double? {
        val w = s.rotW.toDouble()
        val x = s.rotX.toDouble()
        val y = s.rotY.toDouble()
        val z = s.rotZ.toDouble()
        val norm = w * w + x * x + y * y + z * z
        if (norm < 0.5) return null // no rotation vector on this device/sample
        val siny = 2.0 * (w * z + x * y)
        val cosy = 1.0 - 2.0 * (y * y + z * z)
        // Android's quaternion turns counter-clockwise about +Z; a compass course
        // turns clockwise. Without the minus every right turn was reckoned left.
        return -atan2(siny, cosy)
    }

    /** Shortest signed angle from [a] to [b], in (-π, π]. */
    private fun angleDelta(a: Double, b: Double): Double = wrapSigned(b - a)

    private fun wrap(r: Double): Double = (r % TAU + TAU) % TAU

    private fun wrapSigned(r: Double): Double {
        var d = (r + PI) % TAU
        if (d < 0) d += TAU
        return d - PI
    }

    companion object {
        private const val TAU = 2.0 * PI

        const val GRAVITY_MPS2 = 9.81

        /** A sample gap this long means the IMU stream broke — do not integrate across it. */
        const val MAX_IMU_GAP_S = 2.0

        const val MAX_SPEED_MPS = 90.0

        /** How fast the bias estimate chases its target. Slow on purpose: it
         *  must average sensor noise away, not chase it. */
        const val BIAS_GAIN = 0.25

        /** No real MEMS bias is larger than this; past it, the sensor is faulty. */
        const val MAX_BIAS_MPS2 = 0.6

        /** The baseline that makes the estimate worth having — see [observeBias]. */
        const val BIAS_WINDOW_S = 10.0

        /** Longer than this and the window has spanned a blackout; discard it. */
        const val BIAS_WINDOW_MAX_S = 20.0

        // ── ZUPT ([DEVICE]-tuned; err toward never firing in motion) ──────
        /** Horizontal-accel variance below this = physically at rest. A
         *  vehicle in motion vibrates well above it; a phone at rest sits
         *  an order of magnitude under. */
        const val ZUPT_VARIANCE = 0.015

        /** The quiet must hold this long before the speed is zeroed. */
        const val ZUPT_HOLD_MS = 3_000L

        /** EMA horizon for the vibration statistics. */
        const val ZUPT_TAU_S = 0.5

        /** Variance that proves the platform CAN vibrate — arming threshold. */
        const val ZUPT_ARM_VARIANCE = 0.06

        /** The vibration must be SUSTAINED to arm — a braking transient
         *  spikes the variance for well under a second and proves nothing;
         *  a rolling vehicle vibrates continuously. */
        const val ZUPT_ARM_HOLD_MS = 3_000L
    }
}
