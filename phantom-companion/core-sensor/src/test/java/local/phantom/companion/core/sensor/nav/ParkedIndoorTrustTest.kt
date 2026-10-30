package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import kotlin.math.cos
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIELD CAPTURE (coordinator, 2026-07-21 15:47, build a5e5782) — a phone lying
 * STILL on a desk, indoors, plugged in, showed `EW JAMMED · DR` and drew a
 * wandering orange trail across the map: the engine concluded it was being
 * jammed and dead-reckoned a body that never moved.
 *
 * The root cause was a hole exactly where real life lives. The engine forked on
 * accuracy — trust the sky at ≤ 25 m, keep the old jam/spoof machine worse than
 * that — but indoors, in courtyards, in canyons, accuracy is routinely worse
 * than 25 m with zero jamming. And [CustomLocationEngine.onTick] flipped to
 * JAMMED on any silence past [CustomLocationEngine.JAM_TIMEOUT_MS], which
 * indoors (one fix per 10–30 s) is the NORMAL fix cadence, not an attack.
 *
 * Two properties now hold, and this test is their proof — not a lucky
 * screenshot but the whole time series:
 *
 *  1. **Stationary is an absolute gate.** A body that is demonstrably still
 *     (fused speed at a standstill AND no vehicle-grade vibration) has nothing
 *     to reckon, so dead reckoning never arms and the puck holds position.
 *  2. **JAMMED needs positive evidence.** Silence over a parked phone is a weak
 *     sky, not electronic warfare; the honest floor is DEGRADED, never JAMMED.
 *
 * The second test is the guard rail: a body that is genuinely MOVING when the
 * sky goes dark MUST still jam and dead-reckon — the fix may not buy the parked
 * case by regressing the tunnel case NAV P0.1–P0.4 exists to serve.
 */
class ParkedIndoorTrustTest {

    private fun gps(
        lat: Double,
        lon: Double,
        accM: Float,
        speed: Float? = 0f,
        bearing: Float? = null,
        atMs: Long,
    ) = GpsSample(lat, lon, accM, speed, bearing, altitudeM = null, elapsedMs = atMs)

    /** Identity attitude → device axes are world axes; [ax]/[ay] are the
     *  horizontal accelerations the reckoner integrates and the ZUPT watches. */
    private fun imu(ax: Float, ay: Float) = ImuSample(
        accelX = ax, accelY = ay, accelZ = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    private fun distM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = (bLat - aLat) * 111_320.0
        val dLon = (bLon - aLon) * 111_320.0 * cos(Math.toRadians(aLat))
        return hypot(dLat, dLon)
    }

    @Test
    fun `parked indoors with poor accuracy and noisy imu never jams, never dead-reckons, never wanders`() {
        val engine = CustomLocationEngine()
        val rnd = java.util.Random(42)
        val lat0 = 50.4501
        val lon0 = 30.5234
        var t = 1_000L

        // Establish the origin with a first, poor (indoor) fix reporting a
        // standstill — exactly what a chip at a desk delivers.
        engine.onGps(gps(lat0, lon0, 40f, 0f, null, t), t)
        val fix0 = engine.fix.value!!

        val trustCounts = mutableMapOf<LocationTrust, Int>()
        var everDead = false
        var maxDeadReckonedM = 0.0
        var maxWanderM = 0.0
        var lastGpsMs = t
        val endMs = t + 300_000L // five minutes, the brief's bar

        while (t <= endMs) {
            // IMU at 50 Hz: parked, with ordinary MEMS noise. σ≈0.08 m/s² is
            // real sensor chatter, well below the vehicle-vibration threshold
            // ([DeadReckoner.ZUPT_ARM_VARIANCE]) that would read as motion.
            engine.onImu(
                imu(
                    (rnd.nextGaussian() * 0.08).toFloat(),
                    (rnd.nextGaussian() * 0.08).toFloat(),
                ),
                t,
            )

            // A poor indoor fix every 15 s, with a few metres of multipath
            // scatter — the sky answering as fast as it can, not a jam.
            if (t - lastGpsMs >= 15_000L) {
                lastGpsMs = t
                engine.onGps(
                    gps(
                        lat0 + rnd.nextGaussian() * 0.00004, // ~4 m
                        lon0 + rnd.nextGaussian() * 0.00006,
                        42f, 0f, null, t,
                    ),
                    t,
                )
            }

            // 1 Hz heartbeat — the beat that lets silence be noticed.
            if (t % 1_000L == 0L) {
                engine.onTick(t)
                val trust = engine.trust.value
                trustCounts[trust] = (trustCounts[trust] ?: 0) + 1
                val f = engine.fix.value!!
                if (f.dead) everDead = true
                maxDeadReckonedM = maxOf(maxDeadReckonedM, engine.snapshot().deadReckonedM)
                maxWanderM = maxOf(maxWanderM, distM(fix0.lat, fix0.lon, f.lat, f.lon))
            }
            t += 20L
        }

        val jammed = trustCounts[LocationTrust.JAMMED] ?: 0
        val spoofed = trustCounts[LocationTrust.SPOOFED] ?: 0
        println(
            "PARKED-INDOORS over ${trustCounts.values.sum()} ticks (5 min): " +
                "$trustCounts | maxWander=${"%.1f".format(maxWanderM)}m " +
                "maxDeadReckoned=${"%.1f".format(maxDeadReckonedM)}m everDead=$everDead",
        )

        assertEquals("a parked phone indoors must spend ZERO time JAMMED", 0, jammed)
        assertEquals("a parked phone must never be judged SPOOFED", 0, spoofed)
        assertFalse("a parked phone must never publish a dead-reckoned fix", everDead)
        assertTrue(
            "dead reckoning armed on a parked phone: carried ${maxDeadReckonedM}m",
            maxDeadReckonedM < 1.0,
        )
        assertTrue(
            "the puck wandered ${maxWanderM}m on a phone that never moved",
            maxWanderM < 25.0,
        )
    }

    @Test
    fun `a moving vehicle that loses the sky still jams and dead-reckons`() {
        val engine = CustomLocationEngine()
        val rnd = java.util.Random(7)
        val lat0 = 50.4500
        val lon0 = 30.5200
        var t = 1_000L
        var i = 0

        // Drive north at ~15 m/s under a clean sky for 20 s. Vehicle-grade
        // vibration (σ≈0.6 m/s²) so the body reads as genuinely moving.
        while (t < 21_000L) {
            engine.onImu(
                imu((rnd.nextGaussian() * 0.6).toFloat(), (rnd.nextGaussian() * 0.6).toFloat()),
                t,
            )
            if (t % 1_000L == 0L) {
                engine.onGps(gps(lat0 + i * 0.000135, lon0, 6f, 15f, 0f, t), t)
                engine.onTick(t)
                i++
            }
            t += 20L
        }
        assertTrue(
            "a clean sky at 15 m/s must not be judged jammed or spoofed",
            engine.trust.value != LocationTrust.JAMMED &&
                engine.trust.value != LocationTrust.SPOOFED,
        )

        // Tunnel: the fixes stop, the vehicle keeps moving and vibrating.
        val drStart = engine.snapshot().deadReckonedM
        while (t < 45_000L) {
            engine.onImu(
                imu((rnd.nextGaussian() * 0.6).toFloat(), (rnd.nextGaussian() * 0.6).toFloat()),
                t,
            )
            if (t % 1_000L == 0L) engine.onTick(t)
            t += 20L
        }

        assertEquals(
            "a moving vehicle whose sky went dark IS jammed — this is the case " +
                "the stationary gate must NOT break",
            LocationTrust.JAMMED,
            engine.trust.value,
        )
        assertTrue(
            "dead reckoning must carry a moving operator through the tunnel " +
                "(carried only ${engine.snapshot().deadReckonedM - drStart}m more)",
            engine.snapshot().deadReckonedM > drStart + 50.0,
        )
        assertTrue("the fix must be marked dead under a real jam", engine.fix.value!!.dead)
    }
}
