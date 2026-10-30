package local.phantom.companion.core.sensor.nav

import kotlin.math.sin
import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-3 — each test is a way an operator actually moves, rendered as
 * synthetic sensor truth: a 2 Hz gait, a rail-smooth cruise, a red light
 * that must NOT demote a car to a pedestrian.
 */
class KinematicsTest {

    private fun imu(hp: Float, yaw: Float = 0f) = ImuSample(
        accelX = 0f, accelY = 0f, accelZ = 9.81f + hp,
        gyroX = 0f, gyroY = 0f, gyroZ = yaw,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    /**
     * Simulate [seconds] of movement: 50 Hz IMU with a sinusoidal body
     * acceleration, 1 Hz fixes. Returns the clock after the run.
     */
    private fun KinematicsEngine.ride(
        fromMs: Long,
        seconds: Int,
        speedMps: Float,
        vibAmp: Float,
        vibHz: Float,
        yaw: Float = 0f,
        altitudeM: Float = 150f,
    ): Long {
        var t = fromMs
        repeat(seconds) {
            repeat(50) { i ->
                val tS = (t + i * 20) / 1000.0
                val hp = (vibAmp * sin(2.0 * Math.PI * vibHz * tS)).toFloat()
                onImu(imu(hp, yaw), t + i * 20L)
            }
            t += 1_000
            onFix(speedMps, altitudeM, t)
        }
        return t
    }

    @Test
    fun `a 2 Hz gait at walking speed is WALK`() {
        val k = KinematicsEngine()
        k.ride(0, 15, speedMps = 1.4f, vibAmp = 2.0f, vibHz = 2.0f)
        assertEquals(KinematicsEngine.MotionState.WALK, k.profile.value.state)
        assertTrue(k.profile.value.confidence > 0.6f)
    }

    @Test
    fun `a fast gait at running speed is RUN`() {
        val k = KinematicsEngine()
        k.ride(0, 15, speedMps = 3.2f, vibAmp = 4.0f, vibHz = 2.8f)
        assertEquals(KinematicsEngine.MotionState.RUN, k.profile.value.state)
    }

    @Test
    fun `rough vibration without a gait at cycling speed is CYCLE`() {
        val k = KinematicsEngine()
        // 8 Hz road buzz — far above any human cadence.
        k.ride(0, 15, speedMps = 4.0f, vibAmp = 0.9f, vibHz = 8.0f)
        assertEquals(KinematicsEngine.MotionState.CYCLE, k.profile.value.state)
    }

    @Test
    fun `city driving is DRIVE`() {
        val k = KinematicsEngine()
        // Engine + road harmonics: jerky enough to never read as rails.
        k.ride(0, 15, speedMps = 14f, vibAmp = 0.6f, vibHz = 11f, yaw = 0.06f)
        assertEquals(KinematicsEngine.MotionState.DRIVE, k.profile.value.state)
    }

    @Test
    fun `rail-smooth fast cruise is TRAIN with capped honesty`() {
        val k = KinematicsEngine()
        k.ride(0, 15, speedMps = 25f, vibAmp = 0.05f, vibHz = 5f, yaw = 0.004f)
        assertEquals(KinematicsEngine.MotionState.TRAIN, k.profile.value.state)
        // TRAIN-vs-DRIVE is ambiguous this early — confidence must say so.
        assertTrue(
            "early TRAIN overclaimed: ${k.profile.value.confidence}",
            k.profile.value.confidence <= KinematicsEngine.AMBIGUOUS_CONFIDENCE + 0.05f,
        )
    }

    @Test
    fun `airliner speed is FLIGHT`() {
        val k = KinematicsEngine()
        k.ride(0, 15, speedMps = 210f, vibAmp = 0.1f, vibHz = 6f, altitudeM = 9_000f)
        assertEquals(KinematicsEngine.MotionState.FLIGHT, k.profile.value.state)
    }

    @Test
    fun `a red light does not demote a car to a pedestrian`() {
        val k = KinematicsEngine()
        var t = k.ride(0, 20, speedMps = 14f, vibAmp = 0.6f, vibHz = 11f, yaw = 0.06f)
        assertEquals(KinematicsEngine.MotionState.DRIVE, k.profile.value.state)
        // 12 seconds parked at the light: still a car.
        t = k.ride(t, 12, speedMps = 0f, vibAmp = 0.1f, vibHz = 11f)
        assertEquals(
            "a 12 s stop flipped DRIVE",
            KinematicsEngine.MotionState.DRIVE,
            k.profile.value.state,
        )
        // 45 seconds parked: engine off, genuinely still.
        k.ride(t, 45, speedMps = 0f, vibAmp = 0.05f, vibHz = 11f)
        assertEquals(KinematicsEngine.MotionState.STILL, k.profile.value.state)
    }

    @Test
    fun `flapping evidence never accumulates toward a switch`() {
        val k = KinematicsEngine()
        var t = k.ride(0, 20, speedMps = 1.4f, vibAmp = 2.0f, vibHz = 2.0f)
        assertEquals(KinematicsEngine.MotionState.WALK, k.profile.value.state)
        // Alternate 3 s of walk-speed and 3 s of cycle-speed evidence — the
        // dwell clock restarts on every flap, so WALK must hold.
        repeat(4) {
            t = k.ride(t, 3, speedMps = 4.0f, vibAmp = 0.9f, vibHz = 8.0f)
            t = k.ride(t, 3, speedMps = 1.4f, vibAmp = 2.0f, vibHz = 2.0f)
        }
        assertEquals(KinematicsEngine.MotionState.WALK, k.profile.value.state)
    }

    @Test
    fun `speed-only degradation still classifies with humbler confidence`() {
        val k = KinematicsEngine()
        var t = 0L
        repeat(15) {
            t += 1_000
            k.onFix(14f, 150f, t) // no IMU at all
        }
        assertEquals(KinematicsEngine.MotionState.DRIVE, k.profile.value.state)
        assertTrue(k.profile.value.confidence < 0.6f)
    }

    @Test
    fun `a walk with no step detector is still confident enough to re-pace a route`() {
        val e = KinematicsEngine()
        var t = 1_000L
        repeat(90) {
            t += 1_000
            e.onFix(1.3f, 150f, t)
        }
        val p = e.profile.value
        assertEquals(KinematicsEngine.MotionState.WALK, p.state)
        assertTrue(
            "speed-only WALK came back at ${p.confidence}; guidance gates at " +
                "${KinematicsEngine.WALK_SPEED_ONLY_CONFIDENCE} and would see UNKNOWN",
            p.confidence >= KinematicsEngine.WALK_SPEED_ONLY_CONFIDENCE,
        )
    }
}
