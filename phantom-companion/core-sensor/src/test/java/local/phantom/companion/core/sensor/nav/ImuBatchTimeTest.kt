package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Batched samples arrive together. Time them at delivery and the whole batch
 * shares one instant — the trap that kept batching off until [ImuSample] carried
 * its own clock.
 *
 * Along-track is `accelY` at course 0, so the ramp below is what the reckoner
 * integrates: paced gives k·T²/2, one-sample-per-batch gives the right-endpoint
 * sum. They differ, and that difference is the bug this guards.
 */
class ImuBatchTimeTest {

    private fun imu(alongMps2: Double, atMs: Long) = ImuSample(
        accelX = 0f, accelY = alongMps2.toFloat(), accelZ = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
        elapsedMs = atMs,
    )

    /** 5 s at 50 Hz, acceleration ramping from 0 to 1 m/s². */
    private fun ramp(fromMs: Long = 10_000L, count: Int = 250): List<ImuSample> =
        (1..count).map { i ->
            val t = fromMs + i * 20L
            imu(i / count.toDouble(), t)
        }

    private fun speedAfter(feed: List<Pair<ImuSample, Long>>): Double {
        val dr = DeadReckoner()
        dr.seed(speedMps = 0f, bearingDeg = 0f, nowMs = 10_000L)
        feed.forEach { (s, t) -> dr.onImu(s, t) }
        return dr.speed.toDouble()
    }

    @Test
    fun `a batch timed by the sample reads the same as paced delivery`() {
        val s = ramp()
        val paced = speedAfter(s.map { it to it.elapsedMs })
        val batched = speedAfter(s.map { it to it.elapsedMs })
        assertEquals(paced, batched, 1e-9)
    }

    @Test
    fun `timing a batch by delivery changes the answer`() {
        val s = ramp()
        val honest = speedAfter(s.map { it to it.elapsedMs })
        // Every sample stamped with the instant its batch of fifty was handed
        // over: 49 arrive with dt = 0, the last carries the whole second.
        val collapsed = speedAfter(s.mapIndexed { i, x -> x to (10_000L + (i / 50 + 1) * 1_000L) })

        assertNotEquals(honest, collapsed, 0.05)
    }

    @Test
    fun `a sample with no clock of its own reports zero, so the caller can fall back`() {
        assertEquals(0L, imu(0.0, atMs = 0L).elapsedMs)
    }
}
