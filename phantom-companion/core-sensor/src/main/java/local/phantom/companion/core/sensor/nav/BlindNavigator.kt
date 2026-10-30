package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.indoor.pdr.StepDetector
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the person is when the sky goes quiet: the last believed fix carried
 * forward by the body's own motion — forward acceleration and stops for a
 * vehicle, steps for a walker, turns from the gyro — and held to the road when
 * a road graph is at hand. Its doubt grows with every metre it has not seen.
 */
class BlindNavigator(
    private val graphNear: ((lat: Double, lon: Double) -> RoadGraph?)? = null,
) {
    data class Estimate(
        val lat: Double,
        val lon: Double,
        val bearingDeg: Float?,
        val speedMps: Float,
        val uncertaintyM: Float,
        val blind: Boolean,
        val onRoad: Boolean,
        val sinceSkyMs: Long,
        val movedM: Float = 0f,
    )

    private enum class Mode { NONE, SKY, BLIND }

    private val body = BodyWitness()
    private val course = BearingFuser()
    private val steps = StepDetector()

    private var mode = Mode.NONE
    private var foot = false
    private var oLat = 0.0
    private var oLon = 0.0
    private var e = 0.0
    private var n = 0.0
    private var offE = 0.0
    private var offN = 0.0
    private var skyAccM = 0.0
    private var weakSigmaM = 0.0
    private var speed = 0.0
    private var anchorSpeed = 0.0
    private var integrated = 0.0
    private var integratedS = 0.0
    private var maxSpeed = 0.0
    private var lastGoodMs = 0L
    private var lastGoodElapsed = Long.MIN_VALUE
    private var lastFixElapsed = Long.MIN_VALUE
    private var intervalMs = 1_000.0
    private var lastStepMs = 0L
    private var lastImuMs = 0L
    private var lastAdvanceMs = 0L
    private var pendingM = 0.0
    private var travelledM = 0.0
    private var coast: GraphCoasting? = null
    private var onRoad = false
    private var accSinceFix = 0.0
    private var accWindowS = 0.0
    private var bias = 0.0
    private var candE = 0.0
    private var candN = 0.0
    private var candMs = 0L
    private var candCount = 0

    @Synchronized
    fun reset() {
        body.reset(); course.reset(); steps.reset()
        mode = Mode.NONE
        coast = null
        pendingM = 0.0; travelledM = 0.0; offE = 0.0; offN = 0.0; candCount = 0
        lastGoodElapsed = Long.MIN_VALUE; lastFixElapsed = Long.MIN_VALUE
    }

    @Synchronized
    fun onImu(s: ImuSample, nowMs: Long) {
        body.onImu(s, nowMs)
        course.onImu(s, nowMs)
        val t = s.elapsedMs.takeIf { it > 0L } ?: nowMs
        val dt = if (lastImuMs == 0L) 0.0 else (t - lastImuMs) / 1000.0
        lastImuMs = t
        val mag = sqrt((s.accelX * s.accelX + s.accelY * s.accelY + s.accelZ * s.accelZ).toDouble())
        val step = steps.onSample(t, mag)
        if (step != null) {
            lastStepMs = t
            if (mode == Mode.BLIND && foot) move(step.lengthM)
        }
        if (dt <= 0.0 || dt > MAX_IMU_GAP_S) return
        val fwd = forwardAccel(s)
        if (mode == Mode.SKY) {
            accSinceFix += fwd * dt
            accWindowS += dt
        }
        if (foot) return
        val ev = body.evidence(nowMs)
        if (ev.stillFor(STILL_ZUPT_MS)) {
            anchorSpeed = 0.0
            integrated = 0.0
            integratedS = 0.0
            speed = 0.0
        } else if (mode == Mode.BLIND) {
            integrated += (fwd - bias) * dt
            integratedS += dt
            // Speed changes are rare and sustained; vibration integrates into a
            // random walk. Believe the integral only as far as it stands out of
            // that walk, measured on this very ride.
            val walk2 = (ev.accelVar?.toDouble() ?: VIB_VAR_DEFAULT) * SAMPLE_S * integratedS
            val kept = integrated * integrated / (integrated * integrated + walk2 + 1e-9)
            speed = (anchorSpeed + integrated * kept).coerceIn(0.0, maxOf(MIN_SPEED_CAP, maxSpeed * 1.3))
        }
        move(speed * dt)
    }

    @Synchronized
    fun onPressure(hpa: Float, nowMs: Long) = body.onPressure(hpa, nowMs)

    @Synchronized
    fun onFix(s: GpsSample, nowMs: Long, distrusted: Boolean = false) {
        if (s.elapsedMs != 0L && s.elapsedMs == lastFixElapsed) return
        if (lastFixElapsed != Long.MIN_VALUE && s.elapsedMs > lastFixElapsed) {
            intervalMs = 0.8 * intervalMs + 0.2 * (s.elapsedMs - lastFixElapsed).coerceIn(200L, 30_000L)
        }
        lastFixElapsed = s.elapsedMs
        if (distrusted || !s.lat.isFinite() || !s.lon.isFinite()) return
        if (mode == Mode.NONE) {
            oLat = s.lat
            oLon = s.lon
        }
        val fe = lonToMeters(s.lon - oLon, oLat)
        val fn = latToMeters(s.lat - oLat)
        val acc = s.accuracyM.toDouble().takeIf { it > 0.0 } ?: GOOD_M
        val good = acc <= GOOD_M
        when {
            mode == Mode.NONE -> sky(s, fe, fn, acc, nowMs)
            mode == Mode.SKY && good -> sky(s, fe, fn, acc, nowMs)
            mode == Mode.SKY && nowMs - lastGoodMs > WEAK_LOSS_MS && !body.evidence(nowMs).stillFor(STILL_ZUPT_MS) -> {
                // A cell fix in place of a satellite one is the sky gone, not a
                // noisier sky: a tunnel with coverage looks exactly like this.
                enterBlind(nowMs)
                weak(fe, fn, acc)
            }
            mode == Mode.SKY -> weak(fe, fn, acc)
            good -> reacquire(s, fe, fn, acc, nowMs)
            else -> weak(fe, fn, acc)
        }
    }

    @Synchronized
    fun onTick(nowMs: Long) {
        if (mode == Mode.SKY && nowMs - lastGoodMs > lossAfterMs()) enterBlind(nowMs)
        decayOffset(nowMs)
    }

    @Synchronized
    fun estimate(nowMs: Long): Estimate? {
        onTick(nowMs)
        if (mode == Mode.NONE) return null
        advance(nowMs)
        val lat = oLat + metersToLat(n + offN)
        val lon = oLon + metersToLon(e + offE, oLat)
        return Estimate(
            lat = lat,
            lon = lon,
            bearingDeg = course.courseDeg,
            speedMps = speed.toFloat(),
            uncertaintyM = sigmaM(nowMs).toFloat(),
            blind = mode == Mode.BLIND,
            onRoad = mode == Mode.BLIND && onRoad,
            sinceSkyMs = if (mode == Mode.BLIND) nowMs - lastGoodMs else 0L,
            movedM = travelledM.toFloat(),
        )
    }

    private fun sky(s: GpsSample, fe: Double, fn: Double, acc: Double, nowMs: Long) {
        val v = s.speedMps?.toDouble()
        if (mode == Mode.SKY && v != null && accWindowS in BIAS_MIN_S..BIAS_MAX_S && speed >= BIAS_MIN_SPEED) {
            val residual = (accSinceFix - (v - speed)) / accWindowS
            bias = (bias + BIAS_GAIN * (residual - bias)).coerceIn(-MAX_BIAS, MAX_BIAS)
        }
        accSinceFix = 0.0
        accWindowS = 0.0
        e = fe
        n = fn
        skyAccM = acc
        weakSigmaM = acc
        if (v != null) {
            speed = v
            anchorSpeed = v
            integrated = 0.0
            integratedS = 0.0
            maxSpeed = maxOf(maxSpeed * 0.999, v)
            steps.calibrate(v, nowMs)
        }
        foot = (v ?: speed) < FOOT_MAX_MPS && nowMs - lastStepMs < STEP_RECENT_MS
        course.onCourse(s.bearingDeg, (v ?: speed).toFloat(), nowMs)
        pendingM = 0.0
        travelledM = 0.0
        lastGoodMs = nowMs
        lastAdvanceMs = nowMs
        candCount = 0
        coast = null
        onRoad = false
        mode = Mode.SKY
    }

    private fun weak(fe: Double, fn: Double, acc: Double) {
        val sigma = if (mode == Mode.BLIND) blindSigma(lastAdvanceMs) else weakSigmaM
        val gap = hypot(fe - (e + pendingDe()), fn - (n + pendingDn()))
        if (gap > 3.0 * hypot(sigma, acc)) return
        val k = sigma * sigma / (sigma * sigma + acc * acc)
        advance(lastAdvanceMs)
        e += k * (fe - e)
        n += k * (fn - n)
        val shrunk = sqrt(sigma * sigma * acc * acc / (sigma * sigma + acc * acc))
        if (mode == Mode.BLIND) travelledM *= shrunk / sigma.coerceAtLeast(1.0) else weakSigmaM = shrunk
    }

    private fun reacquire(s: GpsSample, fe: Double, fn: Double, acc: Double, nowMs: Long) {
        advance(nowMs)
        val gap = hypot(fe - e, fn - n)
        val believable = gap <= 3.0 * hypot(sigmaM(nowMs), acc) + REJOIN_SLACK_M
        if (!believable) {
            val consistent = candCount > 0 &&
                hypot(fe - candE, fn - candN) <= 3.0 * acc + CAND_SLACK_M + speed * (nowMs - candMs) / 1000.0
            candCount = if (consistent) candCount + 1 else 1
            candE = fe
            candN = fn
            candMs = nowMs
            if (candCount < CONCEDE_AFTER) return
        }
        offE += e - fe
        offN += n - fn
        sky(s, fe, fn, acc, nowMs)
    }

    private fun enterBlind(nowMs: Long) {
        mode = Mode.BLIND
        travelledM = 0.0
        onRoad = false
        coast = null
        val c = course.courseDeg
        val near = graphNear
        if (!foot && c != null && near != null) {
            val lat = oLat + metersToLat(n)
            val lon = oLon + metersToLon(e, oLat)
            val g = runCatching { near(lat, lon) }.getOrNull()
            if (g != null) {
                val gc = GraphCoasting(g)
                if (gc.seed(lat, lon, c.toDouble(), skyAccM, nowMs) is GraphCoasting.Coast.Held) {
                    coast = gc
                    onRoad = true
                }
            }
        }
        advance(nowMs)
    }

    private fun move(m: Double) {
        if (m > 0.0 && mode != Mode.NONE) pendingM += m
    }

    private fun pendingDe(): Double = course.courseDeg?.let { pendingM * sin(Math.toRadians(it.toDouble())) } ?: 0.0
    private fun pendingDn(): Double = course.courseDeg?.let { pendingM * cos(Math.toRadians(it.toDouble())) } ?: 0.0

    private fun advance(nowMs: Long) {
        lastAdvanceMs = maxOf(lastAdvanceMs, nowMs)
        val d = pendingM
        if (d <= 0.0) return
        pendingM = 0.0
        if (mode != Mode.BLIND) return
        travelledM += d
        val c = course.courseDeg?.toDouble() ?: return
        val gc = coast
        if (gc != null) {
            when (val r = runCatching { gc.step(d, c, nowMs) }.getOrNull()) {
                is GraphCoasting.Coast.Held -> {
                    e = lonToMeters(r.lon - oLon, oLat)
                    n = latToMeters(r.lat - oLat)
                    return
                }
                else -> {
                    coast = null
                    onRoad = false
                }
            }
        }
        e += d * sin(Math.toRadians(c))
        n += d * cos(Math.toRadians(c))
    }

    private fun decayOffset(nowMs: Long) {
        val dt = ((nowMs - offsetAtMs).coerceAtLeast(0L)) / 1000.0
        offsetAtMs = nowMs
        val k = exp(-dt / MERGE_TAU_S)
        offE *= k
        offN *= k
        if (hypot(offE, offN) < 0.5) {
            offE = 0.0
            offN = 0.0
        }
    }

    private var offsetAtMs = 0L

    private fun sigmaM(nowMs: Long): Double =
        if (mode == Mode.BLIND) blindSigma(nowMs) else weakSigmaM

    private fun blindSigma(nowMs: Long): Double {
        val tS = (nowMs - lastGoodMs).coerceAtLeast(0L) / 1000.0
        val drift = if (foot) FOOT_DRIFT else VEHICLE_DRIFT
        val lateral = if (onRoad) 0.0 else OFF_ROAD_DRIFT * travelledM
        val accel = if (foot) 0.0 else 0.5 * BIAS_RESIDUAL * tS * tS
        return skyAccM + drift * travelledM + accel + lateral
    }

    private fun lossAfterMs(): Long = maxOf(LOSS_MIN_MS, (intervalMs * LOSS_INTERVALS).toLong())

    private fun forwardAccel(s: ImuSample): Double {
        val c = course.courseDeg ?: return 0.0
        val w = s.rotW.toDouble()
        val x = s.rotX.toDouble()
        val y = s.rotY.toDouble()
        val z = s.rotZ.toDouble()
        if (w * w + x * x + y * y + z * z < 0.5) return 0.0
        val vx = s.accelX.toDouble()
        val vy = s.accelY.toDouble()
        val vz = s.accelZ.toDouble()
        val tx = 2.0 * (y * vz - z * vy)
        val ty = 2.0 * (z * vx - x * vz)
        val tz = 2.0 * (x * vy - y * vx)
        val wx = vx + w * tx + (y * tz - z * ty)
        val wy = vy + w * ty + (z * tx - x * tz)
        val r = Math.toRadians(c.toDouble())
        return wx * sin(r) + wy * cos(r)
    }

    companion object {
        /** The blind estimate replaces the dot only while it is worth more than the frozen fix. */
        fun shows(e: Estimate?): Boolean =
            e != null && e.blind && e.movedM >= SHOW_MIN_MOVED_M && e.uncertaintyM <= SHOW_MAX_M

        const val SHOW_MAX_M = 2_000f
        const val SHOW_MIN_MOVED_M = 5f
        const val GOOD_M = 40.0
        const val LOSS_MIN_MS = 2_000L
        const val WEAK_LOSS_MS = 1_200L
        const val SAMPLE_S = 0.02
        const val VIB_VAR_DEFAULT = 0.1
        const val LOSS_INTERVALS = 2.5
        const val MAX_IMU_GAP_S = 1.0
        const val STILL_ZUPT_MS = 1_500L
        const val MIN_SPEED_CAP = 35.0
        const val FOOT_MAX_MPS = 2.8
        const val STEP_RECENT_MS = 3_000L
        const val BIAS_MIN_S = 0.5
        const val BIAS_MAX_S = 3.0
        const val BIAS_MIN_SPEED = 3.0
        const val BIAS_GAIN = 0.05
        const val MAX_BIAS = 0.5
        const val BIAS_RESIDUAL = 0.02
        const val VEHICLE_DRIFT = 0.04
        const val FOOT_DRIFT = 0.06
        const val OFF_ROAD_DRIFT = 0.05
        const val REJOIN_SLACK_M = 25.0
        const val CAND_SLACK_M = 30.0
        const val CONCEDE_AFTER = 3
        const val MERGE_TAU_S = 1.5
    }
}
