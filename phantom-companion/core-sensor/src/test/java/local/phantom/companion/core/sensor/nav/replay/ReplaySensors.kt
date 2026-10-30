package local.phantom.companion.core.sensor.nav.replay

import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal fun Random.gauss(): Double {
    var u: Double
    do { u = nextDouble() } while (u <= 1e-12)
    return sqrt(-2.0 * ln(u)) * cos(2.0 * PI * nextDouble())
}

internal class Ou(private val sigma: Double, private val tauS: Double, private val rng: Random) {
    var x: Double = rng.gauss() * sigma
        private set

    fun step(dtS: Double): Double {
        val a = exp(-dtS / tauS)
        x = a * x + sqrt(1 - a * a) * sigma * rng.gauss()
        return x
    }
}

internal enum class Body { CAR, WALK, STILL }

/**
 * Android conventions, on purpose and checked against the platform docs:
 * TYPE_ROTATION_VECTOR rotates device → world ENU; a phone whose top points at
 * compass azimuth A has rotation θ = −A about +Z. Gyro is right-handed, so a
 * clockwise (compass-positive) turn reads NEGATIVE gyro Z.
 */
internal class ImuSynth(
    private val body: Body,
    private val mountYawRad: Double,
    private val rng: Random,
    private val accelBias: Double = 0.04,
    private val gyroBias: Double = 0.003,
    private val magErrSigmaRad: Double = Math.toRadians(6.0),
) {
    private val magErr = Ou(magErrSigmaRad, 60.0, rng)
    private val biasX = accelBias * rng.gauss()
    private val biasY = accelBias * rng.gauss()
    private var stepPhase = 0.0

    fun sample(t: Truth, dtS: Double): ImuSample {
        magErr.step(dtS)
        val azimuth = t.headingRad + mountYawRad
        val theta = -(azimuth + magErr.x)
        val thetaTrue = -azimuth
        val latAcc = t.speedMps * t.yawRateCw
        val aE = t.alongAccel * sin(t.headingRad) + latAcc * cos(t.headingRad)
        val aN = t.alongAccel * cos(t.headingRad) - latAcc * sin(t.headingRad)
        var vx = cos(thetaTrue) * aE + sin(thetaTrue) * aN
        var vy = -sin(thetaTrue) * aE + cos(thetaTrue) * aN
        var vz = 9.81
        val moving = t.speedMps > 0.3
        val vib = when (body) {
            Body.CAR -> if (moving) 0.35 else 0.07
            Body.WALK -> if (moving) 0.25 else 0.02
            Body.STILL -> 0.012
        }
        if (body == Body.WALK && moving) {
            val cadence = 1.8
            stepPhase += 2 * PI * cadence * dtS
            vz += 2.4 * sin(stepPhase)
            vy += 0.8 * sin(stepPhase + 0.7)
        }
        vx += biasX + vib * rng.gauss()
        vy += biasY + vib * rng.gauss()
        vz += vib * rng.gauss()
        val gz = -t.yawRateCw + gyroBias + 0.006 * rng.gauss() + (if (moving && body != Body.STILL) 0.01 * rng.gauss() else 0.0)
        val half = theta / 2
        return ImuSample(
            accelX = vx.toFloat(),
            accelY = vy.toFloat(),
            accelZ = vz.toFloat(),
            gyroX = (0.004 * rng.gauss()).toFloat(),
            gyroY = (0.004 * rng.gauss()).toFloat(),
            gyroZ = gz.toFloat(),
            rotW = cos(half).toFloat(),
            rotX = 0f,
            rotY = 0f,
            rotZ = sin(half).toFloat(),
            elapsedMs = t.tMs,
        )
    }
}

internal sealed interface Sky {
    data class Clear(val accM: Double = 6.0) : Sky
    data class Canyon(val errM: Double = 35.0, val claimM: Double = 25.0) : Sky
    data object Silent : Sky
    data class CellOnly(val accM: Double = 1_200.0) : Sky
    data class Drift(val mps: Double, val dirRad: Double = PI / 2) : Sky
    data class Jump(val offsetM: Double, val dirRad: Double = PI / 3) : Sky
    data object Indoor : Sky
}

internal data class SkyWindow(val fromS: Double, val toS: Double, val sky: Sky)

internal class FlpSynth(private val rng: Random) {
    private val errE = Ou(1.0, 12.0, rng)
    private val errN = Ou(1.0, 12.0, rng)
    private val canyonE = Ou(1.0, 25.0, rng)
    private val canyonN = Ou(1.0, 25.0, rng)
    private val indoorLogAcc = Ou(1.0, 240.0, rng)
    private val altErr = Ou(1.0, 30.0, rng)
    private var cellOffset = En(0.0, 0.0)
    private var cellUntilS = -1.0
    private var driftStartS = -1.0
    private var driftAnchor = En(0.0, 0.0)
    private var indoorGapUntilS = -1.0
    private var nextIndoorS = 0.0

    fun fix(t: Truth, sky: Sky, tS: Double, dtS: Double): GpsSample? {
        errE.step(dtS); errN.step(dtS); altErr.step(dtS); canyonE.step(dtS); canyonN.step(dtS); indoorLogAcc.step(dtS)
        if (sky !is Sky.Drift) driftStartS = -1.0
        return when (sky) {
            is Sky.Clear -> sample(t.p + En(errE.x, errN.x) * (sky.accM / 1.6), sky.accM * (0.9 + 0.2 * rng.nextDouble()), t, 0.25, 3.0)
            is Sky.Canyon -> sample(
                t.p + En(canyonE.x, canyonN.x) * sky.errM + En(errE.x, errN.x) * 5.0,
                sky.claimM * (0.7 + 0.6 * rng.nextDouble()), t, 1.0, 12.0,
            )
            Sky.Silent -> null
            is Sky.CellOnly -> {
                if (tS >= cellUntilS) {
                    cellOffset = En(rng.gauss(), rng.gauss()) * (sky.accM * 0.5)
                    cellUntilS = tS + 40 + 40 * rng.nextDouble()
                }
                if (rng.nextDouble() > 0.35) return null
                GpsSample(
                    lat = (t.p + cellOffset).lat, lon = (t.p + cellOffset).lon,
                    accuracyM = (sky.accM * (0.8 + 0.4 * rng.nextDouble())).toFloat(),
                    speedMps = null, bearingDeg = null, altitudeM = null, elapsedMs = t.tMs,
                )
            }
            is Sky.Drift -> {
                if (driftStartS < 0) driftStartS = tS
                val off = unit(sky.dirRad) * (sky.mps * (tS - driftStartS))
                val fake = t.p + off + En(errE.x, errN.x) * 2.0
                val vFake = unit(t.headingRad) * t.speedMps + unit(sky.dirRad) * sky.mps
                GpsSample(
                    lat = fake.lat, lon = fake.lon, accuracyM = 4f,
                    speedMps = vFake.norm().toFloat(),
                    bearingDeg = if (vFake.norm() > 0.5) Math.toDegrees(compassOf(vFake)).toFloat() else null,
                    altitudeM = t.altitudeM.toFloat(), elapsedMs = t.tMs,
                )
            }
            is Sky.Jump -> sample(t.p + unit(sky.dirRad) * sky.offsetM + En(errE.x, errN.x) * 2.0, 4.0, t, 0.2, 2.0)
            Sky.Indoor -> indoor(t, tS)
        }
    }

    private fun indoor(t: Truth, tS: Double): GpsSample? {
        if (tS < indoorGapUntilS || tS < nextIndoorS) return null
        nextIndoorS = tS + 1.0 + 2.0 * rng.nextDouble()
        if (rng.nextDouble() < 0.0015) {
            indoorGapUntilS = tS + 30 + 170 * rng.nextDouble()
            return null
        }
        val acc = exp(ln(60.0) + 1.1 * indoorLogAcc.x).coerceIn(12.0, 900.0)
        val p = t.p + En(canyonE.x, canyonN.x) * (acc * 0.55)
        return GpsSample(
            lat = p.lat, lon = p.lon, accuracyM = acc.toFloat(),
            speedMps = (0.25 * rng.nextDouble()).toFloat(), bearingDeg = null,
            altitudeM = (t.altitudeM + acc * 0.8 * altErr.x).toFloat(), elapsedMs = t.tMs,
        )
    }

    private fun sample(p: En, acc: Double, t: Truth, speedSigma: Double, bearingSigmaDeg: Double): GpsSample {
        val speed = (t.speedMps + speedSigma * rng.gauss()).coerceAtLeast(0.0)
        return GpsSample(
            lat = p.lat, lon = p.lon, accuracyM = acc.toFloat(),
            speedMps = speed.toFloat(),
            bearingDeg = if (t.speedMps > 1.0) {
                ((Math.toDegrees(t.headingRad) + bearingSigmaDeg * rng.gauss() + 360) % 360).toFloat()
            } else {
                null
            },
            altitudeM = (t.altitudeM + 3 * rng.gauss()).toFloat(),
            elapsedMs = t.tMs,
        )
    }
}

internal class NetworkSynth(private val rng: Random, private val periodS: Double = 20.0) {
    private var nextS = 0.0
    private var cell = En(0.0, 0.0)
    private var cellUntilS = -1.0

    fun fix(t: Truth, accM: Double?, tS: Double): GpsSample? {
        if (accM == null || tS < nextS) return null
        nextS = tS + periodS
        if (tS >= cellUntilS) {
            cell = En(rng.gauss(), rng.gauss()) * (accM * 0.45)
            cellUntilS = tS + 60 + 120 * rng.nextDouble()
        }
        val p = t.p + cell
        return GpsSample(
            lat = p.lat, lon = p.lon, accuracyM = (accM * (0.8 + 0.4 * rng.nextDouble())).toFloat(),
            speedMps = null, bearingDeg = null, elapsedMs = t.tMs,
        )
    }
}

internal fun pressureOf(altitudeM: Double): Float =
    (1013.25 * Math.pow(1.0 - 2.25577e-5 * altitudeM, 5.25588)).toFloat()
