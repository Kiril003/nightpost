package local.phantom.companion.core.sensor.nav

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IMU folds one-second buckets on the SENSOR callback thread; the
 * classifier sums them on the fix pump's coroutine. They are two threads
 * touching one `ArrayDeque`.
 *
 * On the owner's device (07-19) that raced and threw:
 *
 * ```
 * FATAL EXCEPTION: DefaultDispatcher-worker-6
 * java.lang.NullPointerException: Attempt to invoke virtual method
 *   'java.lang.Object kotlin.Pair.component2()' on a null object reference
 *   at KinematicsEngine.classify(Kinematics.kt:180)
 *   at KinematicsEngine.onFix(Kinematics.kt:162)
 *   at PhantomLocator$start$4.invokeSuspend(PhantomLocator.kt:127)
 * ```
 *
 * `ArrayDeque.removeFirst()` nulls the slot it vacates. The classifier's
 * `for ((_, b) in buckets)` walked into that hole and destructured null — and
 * because the throw landed in the pump's coroutine, it did not merely lose one
 * classification: **it killed the fix pump for the rest of the drive.** A
 * silent nav death, in the subsystem the owner was already complaining about.
 *
 * This test drives both threads at once, hard enough that an unguarded deque
 * fails within a second or two. It is a race, so it cannot prove absence — but
 * it reliably reproduced the NPE against the pre-fix code, which is what makes
 * it worth keeping.
 */
class KinematicsConcurrencyTest {

    private fun imu(hp: Float) = ImuSample(
        accelX = 0f, accelY = 0f, accelZ = 9.81f + hp,
        gyroX = 0f, gyroY = 0f, gyroZ = 0.2f,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    @Test
    fun `the sensor thread may fold buckets while the pump classifies`() {
        val engine = KinematicsEngine()
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val stop = CountDownLatch(2)

        // Bucket boundaries are one second of SIMULATED time, so both threads
        // advance a shared synthetic clock in big strides: the deque then
        // rotates and trims constantly instead of once a real second, which is
        // what concentrates the race into a short test.
        val clock = java.util.concurrent.atomic.AtomicLong(1_000L)

        val sensor = Thread({
            runCatching {
                start.await()
                repeat(60_000) { i ->
                    engine.onImu(imu(if (i % 2 == 0) 1.4f else -1.4f), clock.addAndGet(40L))
                }
            }.onFailure { failure.compareAndSet(null, it) }
            stop.countDown()
        }, "imu")

        val pump = Thread({
            runCatching {
                start.await()
                repeat(60_000) {
                    // Speed in the driving band, so classify() runs its full
                    // body rather than bailing early.
                    engine.onFix(speedMps = 14f, altitudeM = 120f, nowMs = clock.addAndGet(40L))
                }
            }.onFailure { failure.compareAndSet(null, it) }
            stop.countDown()
        }, "pump")

        sensor.start()
        pump.start()
        start.countDown()

        assertTrue("threads did not finish in 30 s", stop.await(30, TimeUnit.SECONDS))
        assertNull(
            "the kinematics engine threw under concurrent IMU + fix traffic — " +
                "this is the NPE that killed the fix pump mid-drive: ${failure.get()}",
            failure.get(),
        )
    }
}
