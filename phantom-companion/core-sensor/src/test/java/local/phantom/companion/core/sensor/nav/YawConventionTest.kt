package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * TYPE_ROTATION_VECTOR, as Android defines it: device → world ENU, and
 * getOrientation's azimuth is the rotation about −Z. A phone whose top faces
 * east therefore carries θ = −90° about +Z. The old tests built the quaternion
 * with θ = +azimuth, so every right turn was reckoned as a left one and nothing
 * could turn red.
 */
class YawConventionTest {

    private fun facing(azimuthDeg: Double, accelY: Float = 0f): ImuSample {
        val half = -Math.toRadians(azimuthDeg) / 2.0
        return ImuSample(
            accelX = 0f, accelY = accelY, accelZ = 9.81f,
            gyroX = 0f, gyroY = 0f, gyroZ = 0f,
            rotW = cos(half).toFloat(), rotX = 0f, rotY = 0f, rotZ = sin(half).toFloat(),
        )
    }

    private fun gap(a: Float, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    @Test
    fun `the quaternion is Android's - forward thrust of a phone facing east pushes east`() {
        val dr = DeadReckoner()
        dr.seed(speedMps = 10f, bearingDeg = 90f, nowMs = 0L)
        var t = 0L
        var v: Pair<Double, Double>? = null
        repeat(50) {
            t += 20
            v = dr.onImu(facing(90.0, accelY = 1f), t)
        }
        val (vE, vN) = v!!
        assert(vE > 10.5 && abs(vN) < 0.5) { "east=$vE north=$vN" }
    }

    @Test
    fun `a right turn of the phone is a right turn of the course`() {
        val fuser = BearingFuser()
        val dr = DeadReckoner()
        fuser.onImu(facing(0.0), 20L)
        dr.onImu(facing(0.0), 20L)
        fuser.onCourse(0f, 10f, 40L)
        dr.seed(10f, 0f, 40L)
        var t = 40L
        for (a in 1..90) {
            t += 20
            fuser.onImu(facing(a.toDouble()), t)
            dr.onImu(facing(a.toDouble()), t)
        }
        assertEquals(0.0, gap(fuser.courseDeg!!, 90.0), 1.0)
        assertEquals(0.0, gap(dr.heading!!, 90.0), 1.0)
    }
}
