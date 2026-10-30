package local.phantom.companion.core.sensor.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IRONCLAD-1 — the fused course channel's promises, pinned:
 *
 *  - the arrow NEVER spins at a standstill (the Kyiv failure, named);
 *  - no single fix — however forged — swings it past the step clamp;
 *  - a real turn is tracked by the gyro backbone with no sky at all;
 *  - and the engine publishes THIS channel, not the Kalman velocity azimuth.
 */
class BearingFuserTest {

    /** Android attitude for a phone facing compass azimuth [deg]: θ = −azimuth about +Z. */
    private fun imuYaw(deg: Double, gyroZ: Float = 0f): ImuSample {
        val half = -Math.toRadians(deg) / 2.0
        return ImuSample(
            accelX = 0f, accelY = 0f, accelZ = 9.81f,
            gyroX = 0f, gyroY = 0f, gyroZ = gyroZ,
            rotW = cos(half).toFloat(), rotX = 0f, rotY = 0f, rotZ = sin(half).toFloat(),
        )
    }

    /** No usable attitude — forces the raw-gyro fallback. */
    private fun imuNoAttitude(gyroZ: Float) = ImuSample(
        accelX = 0f, accelY = 0f, accelZ = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = gyroZ,
        rotW = 0f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    private fun angularDistance(a: Float, b: Float): Float =
        abs(((a - b + 540f) % 360f) - 180f)

    @Test
    fun `cold start has no course until a moving fix reports one`() {
        val fuser = BearingFuser()
        fuser.onImu(imuYaw(45.0), 0L)
        assertNull(fuser.courseDeg)
        fuser.onCourse(courseDeg = 10f, speedMps = 11f, nowMs = 1_000L)
        assertEquals(10f, fuser.courseDeg!!, 0.01f)
    }

    @Test
    fun `a standstill course observation is refused — the arrow holds`() {
        val fuser = BearingFuser()
        fuser.onCourse(0f, 11f, 0L)
        // A parked chip reports garbage courses; none of them may move us.
        var t = 1_000L
        for (garbage in listOf(90f, 210f, 137f, 45f)) {
            fuser.onCourse(garbage, 0.4f, t)
            t += 1_000L
        }
        assertEquals(0f, fuser.courseDeg!!, 0.01f)
    }

    @Test
    fun `no single fix may swing the course past the step clamp`() {
        val fuser = BearingFuser()
        fuser.onCourse(0f, 11f, 0L)
        // A forged fix claims the operator instantly drives SOUTH.
        fuser.onCourse(180f, 11f, 1_000L)
        assertTrue(
            "one fix swung the course ${angularDistance(fuser.courseDeg!!, 0f)}°",
            angularDistance(fuser.courseDeg!!, 0f) <= BearingFuser.MAX_STEP_DEG.toFloat() + 0.1f,
        )
    }

    @Test
    fun `a persistent honest disagreement is absorbed over a few fixes`() {
        val fuser = BearingFuser()
        fuser.onCourse(0f, 11f, 0L)
        var t = 1_000L
        repeat(12) {
            fuser.onCourse(30f, 11f, t)
            t += 1_000L
        }
        assertTrue(
            "truth at 30°, fused at ${fuser.courseDeg}°",
            angularDistance(fuser.courseDeg!!, 30f) < 3f,
        )
    }

    @Test
    fun `the gyro backbone tracks a turn with no sky at all`() {
        val fuser = BearingFuser()
        fuser.onCourse(0f, 11f, 0L)
        // The device turns 90° over two seconds — attitude only, no GNSS.
        var t = 0L
        for (i in 1..100) {
            t += 20
            fuser.onImu(imuYaw(90.0 * i / 100.0), t)
        }
        assertEquals(90f, fuser.courseDeg!!, 2f)
    }

    @Test
    fun `without attitude, the raw Z gyro carries the turn`() {
        val fuser = BearingFuser()
        fuser.onCourse(0f, 11f, 0L)
        // +0.5 rad/s counter-clockwise for 2 s = −1 rad of course ≈ 302.7°.
        var t = 0L
        repeat(100) {
            t += 20
            fuser.onImu(imuNoAttitude(gyroZ = 0.5f), t)
        }
        assertEquals(302.7f, fuser.courseDeg!!, 2f)
    }

    // ── Engine level: the published bearing is the fused course ───────────

    private val lat0 = 50.4501
    private val lon0 = 30.5234
    private fun northOf(m: Double) = lat0 + Math.toDegrees(m / 6_371_000.0)

    private fun fix(northM: Double, speed: Float, bearing: Float?) = GpsSample(
        lat = northOf(northM),
        lon = lon0,
        accuracyM = 5f,
        speedMps = speed,
        bearingDeg = bearing,
    )

    @Test
    fun `the published arrow does not spin when the operator stops`() {
        val engine = CustomLocationEngine()
        var t = 0L
        // Cruise north: the course settles at 0°.
        for (i in 0..10) {
            engine.onGps(fix(i * 11.0, speed = 11f, bearing = 0f), t)
            t += 1_000
        }
        val cruising = engine.fix.value!!.bearingDeg!!
        assertEquals(0f, cruising, 1f)

        // Red light: zero speed, and the chip's course turns to garbage.
        for (garbage in listOf(137f, 45f, 290f, 12f, 199f)) {
            engine.onGps(fix(110.0, speed = 0f, bearing = garbage), t)
            t += 1_000
        }
        assertTrue(
            "the arrow moved to ${engine.fix.value!!.bearingDeg}° at a standstill",
            angularDistance(engine.fix.value!!.bearingDeg!!, 0f) < 1f,
        )
    }

    @Test
    fun `one forged course mid-cruise moves the published arrow by at most the clamp`() {
        val engine = CustomLocationEngine()
        var t = 0L
        for (i in 0..10) {
            engine.onGps(fix(i * 11.0, speed = 11f, bearing = 0f), t)
            t += 1_000
        }
        // Position still plausible, speed still 11 — but the course lies.
        engine.onGps(fix(121.0, speed = 11f, bearing = 180f), t)
        assertTrue(
            "a single fix swung the published arrow to ${engine.fix.value!!.bearingDeg}°",
            angularDistance(engine.fix.value!!.bearingDeg!!, 0f) <=
                BearingFuser.MAX_STEP_DEG.toFloat() + 1f,
        )
    }
}
