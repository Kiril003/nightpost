package local.phantom.companion.core.sensor.nav.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample

/**
 * PHANTOM-EW — a synthetic operator, so the navigator can be tested against a
 * truth it cannot see.
 *
 * The whole point of `CustomLocationEngine` is that it keeps working when the
 * sky is lying. That claim is untestable on a real drive: out there, GPS *is*
 * the truth you would grade against, so an engine that quietly follows a
 * spoofer scores perfectly. Only a simulation has a ground truth independent
 * of the sensors, and only then can the question be asked properly — *how far
 * from the operator's real body is PHANTOM's dot, and does PHANTOM know it?*
 *
 * Everything here is deterministic (seeded [Random]) so a finding can be
 * reproduced exactly, and allocation-free per step so a hundred thousand
 * simulated hours do not need a hundred thousand hours of heap.
 *
 * Frames, stated explicitly because the engine has an opinion about them:
 *  - world is ENU metres from a session origin (x = east, y = north, z = up);
 *  - the vehicle travels along its heading;
 *  - the *phone* may be rotated by [mountYawDeg] about the vertical relative to
 *    the vehicle — a phone in a cupholder is not aligned with the road, and
 *    that turns out to matter enormously. See [EwSimulator].
 */

/** The operator's real state. The engine never sees this. */
data class Truth(
    val tMs: Long,
    /** Metres east/north of the session origin. */
    val east: Double,
    val north: Double,
    val speedMps: Double,
    /** Radians, north-up, clockwise (the compass convention the engine uses). */
    val headingRad: Double,
    val altitudeM: Double,
)

enum class DriveProfile(
    val cruiseMps: Double,
    /** How sharply the route turns, radians/second at cruise. */
    val turnRateRadS: Double,
    /** Chance per second of a stop (traffic light, crossing). */
    val stopChancePerS: Double,
) {
    WALK(cruiseMps = 1.4, turnRateRadS = 0.25, stopChancePerS = 0.01),
    URBAN(cruiseMps = 11.0, turnRateRadS = 0.12, stopChancePerS = 0.02),
    HIGHWAY(cruiseMps = 30.0, turnRateRadS = 0.02, stopChancePerS = 0.0005),
}

/**
 * Integrates a plausible journey. Not a road network — a body with inertia
 * that speeds up, brakes, stops and turns. That is enough to exercise every
 * assumption the engine makes about motion.
 */
class TruthTrack(
    private val profile: DriveProfile,
    private val rng: Random,
    val originLat: Double = 50.4501, // Kyiv
    val originLon: Double = 30.5234,
) {
    private var east = 0.0
    private var north = 0.0
    private var speed = 0.0
    private var heading = rng.nextDouble(0.0, 2 * PI)
    private var altitude = 0.0

    private var targetSpeed = profile.cruiseMps
    private var turn = 0.0
    private var stoppedForS = 0.0

    /** Along-track acceleration of the last step (m/s²) — the IMU needs it. */
    var lastAlongAccel: Double = 0.0
        private set

    fun step(dtS: Double, tMs: Long): Truth {
        // Traffic: sometimes the operator simply stops.
        if (stoppedForS > 0.0) {
            stoppedForS -= dtS
            targetSpeed = 0.0
        } else if (rng.nextDouble() < profile.stopChancePerS * dtS) {
            stoppedForS = rng.nextDouble(3.0, 25.0)
            targetSpeed = 0.0
        } else {
            targetSpeed = profile.cruiseMps * rng.nextDouble(0.75, 1.15)
        }

        // Inertia: approach the target rather than teleporting the speed.
        val accel = ((targetSpeed - speed) * 0.5).coerceIn(-4.0, 2.5)
        lastAlongAccel = accel
        speed = (speed + accel * dtS).coerceAtLeast(0.0)

        // Turns wander rather than switching instantly.
        turn += rng.nextDouble(-1.0, 1.0) * profile.turnRateRadS * dtS
        turn *= 0.98
        heading = (heading + turn * dtS + 2 * PI) % (2 * PI)

        east += speed * sin(heading) * dtS
        north += speed * cos(heading) * dtS

        // Gentle terrain, so the barometer has something honest to say.
        altitude += sin(tMs / 90_000.0) * 0.02

        return Truth(tMs, east, north, speed, heading, altitude)
    }

    fun latOf(t: Truth): Double = originLat + Math.toDegrees(t.north / EARTH_R)
    fun lonOf(t: Truth): Double =
        originLon + Math.toDegrees(t.east / (EARTH_R * cos(Math.toRadians(originLat))))
}

private const val EARTH_R = 6_371_000.0

/** Great-circle metres — the same maths the engine grades itself against. */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_R * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
}

/**
 * The chip's view of the truth: the right place, blurred by its own accuracy.
 * A real receiver's error is not white — but a Gaussian at the stated accuracy
 * is the honest *best case*, and the engine failing even against that is the
 * finding, not the noise model.
 */
fun gpsOf(
    track: TruthTrack,
    t: Truth,
    accuracyM: Float,
    rng: Random,
    altitudeM: Float? = null,
): GpsSample {
    val sigma = accuracyM / 1.5
    val dE = rng.nextGaussian() * sigma
    val dN = rng.nextGaussian() * sigma
    val lat = track.originLat + Math.toDegrees((t.north + dN) / EARTH_R)
    val lon = track.originLon +
        Math.toDegrees((t.east + dE) / (EARTH_R * cos(Math.toRadians(track.originLat))))
    return GpsSample(
        lat = lat,
        lon = lon,
        accuracyM = accuracyM,
        speedMps = (t.speedMps + rng.nextGaussian() * 0.4).toFloat().coerceAtLeast(0f),
        bearingDeg = ((Math.toDegrees(t.headingRad) + 360.0) % 360.0).toFloat(),
        altitudeM = altitudeM,
    )
}

/**
 * The IMU, in the **phone's** frame — which is the crux.
 *
 * Android's rotation vector reports the *device's* yaw, not the vehicle's
 * course. If the phone sits in a cupholder rotated [mountYawDeg] from the
 * direction of travel, then every heading the platform hands the engine is
 * wrong by exactly that much, and the accelerometer axes are rotated with it.
 *
 * We synthesise both effects together, honestly, because on a real phone they
 * always arrive together.
 */
fun imuOf(
    t: Truth,
    alongAccel: Double,
    mountYawDeg: Double,
    rng: Random,
    /**
     * Accelerometer bias, m/s². The one error that actually matters once the
     * reckoner integrates honestly: a constant bias b becomes a speed error of
     * b·t. A cheap MEMS part holds roughly 0.02–0.08 m/s² after the platform's
     * own calibration. Default is the pessimistic end — a fix that only works
     * on a perfect sensor is not a fix.
     */
    biasMps2: Double = 0.05,
): ImuSample {
    val phi = Math.toRadians(mountYawDeg)

    // Linear acceleration in world ENU, then expressed in the device's frame.
    val aE = alongAccel * sin(t.headingRad)
    val aN = alongAccel * cos(t.headingRad)
    val dx = aE * cos(phi) + aN * sin(phi) + biasMps2
    val dy = -aE * sin(phi) + aN * cos(phi) + biasMps2

    val noise = 0.06 // MEMS noise floor, m/s² — a quiet, well-behaved sensor.
    // Phone flat: gravity rides the device's z axis. The engine rotates the
    // whole vector into the world before touching it, so this is honest.
    val ax = (dx + rng.nextGaussian() * noise).toFloat()
    val ay = (dy + rng.nextGaussian() * noise).toFloat()
    val az = (9.81 + rng.nextGaussian() * noise).toFloat()

    // The platform's fused rotation vector: the DEVICE's yaw = course + mount.
    val yaw = t.headingRad + phi
    val half = yaw / 2.0
    return ImuSample(
        accelX = ax,
        accelY = ay,
        accelZ = az,
        gyroX = 0f,
        gyroY = 0f,
        gyroZ = 0f,
        rotW = cos(half).toFloat(),
        rotX = 0f,
        rotY = 0f,
        rotZ = sin(half).toFloat(),
    )
}

/** Barometric pressure for an altitude — the one witness a jammer cannot reach. */
fun pressureOf(altitudeM: Double): Float =
    (1013.25 * Math.pow(1.0 - 2.25577e-5 * altitudeM, 5.25588)).toFloat()

private fun Random.nextGaussian(): Double {
    // Box–Muller. `Random` has no Gaussian and pulling in java.util.Random
    // would cost the reproducibility that makes a finding worth reporting.
    var u: Double
    do { u = nextDouble() } while (u <= 1e-12)
    return Math.sqrt(-2.0 * Math.log(u)) * cos(2.0 * PI * nextDouble())
}
