package local.phantom.companion.core.sensor.nav

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APPLE-LOCK — the Doppler velocity channel.
 *
 * The failure being tested: a position-only filter must INVENT velocity
 * from scattered positions, and one second of 15 m urban multipath reads
 * as 15 m/s of phantom velocity — the lateral wobble the operator watched
 * next to Apple Maps. Fusing the chip's Doppler course/speed (measured
 * from carrier frequency, honest in exactly the multipath that scatters
 * the position) pins the velocity state instead.
 */
class Kalman2DTest {

    @Test
    fun `doppler velocity is adopted almost fully at cold seed`() {
        val f = Kalman2D()
        f.seed(0.0, 0.0, 25.0)
        // INITIAL_VELOCITY_VAR = 100 against r = 0.25 → the measurement wins.
        f.updateVelocity(10.0, 0.0, 0.5)
        assertEquals(10.0, f.vE, 1.0)
        assertEquals(0.0, f.vN, 0.5)
    }

    @Test
    fun `standstill scatter invents velocity without doppler and not with it`() {
        val without = Kalman2D()
        val with = Kalman2D()
        without.seed(0.0, 0.0, 25.0)
        with.seed(0.0, 0.0, 25.0)

        // A parked car in an urban canyon: the truth never moves, the
        // measurements alternate ±12 m of multipath, once a second.
        var maxWithout = 0.0
        var maxWith = 0.0
        for (i in 1..20) {
            val scatterN = if (i % 2 == 0) 12.0 else -12.0
            without.predict(1.0)
            without.update(0.0, scatterN, 15.0)
            maxWithout = maxOf(maxWithout, hypot(without.vE, without.vN))

            with.predict(1.0)
            with.update(0.0, scatterN, 15.0)
            // The chip reports ~zero speed; the engine fuses it as (0,0).
            with.updateVelocity(0.0, 0.0, CustomLocationEngine.DOPPLER_STILL_SIGMA_MPS)
            maxWith = maxOf(maxWith, hypot(with.vE, with.vN))
        }

        assertTrue(
            "position-only should invent speed from scatter (got $maxWithout m/s)",
            maxWithout > 1.0,
        )
        assertTrue("doppler-pinned should stay near zero (got $maxWith m/s)", maxWith < 0.8)
        assertTrue("doppler must beat position-only decisively", maxWith * 2 < maxWithout)
    }

    @Test
    fun `moving track adopts true speed from doppler within three fixes`() {
        val f = Kalman2D()
        f.seed(0.0, 0.0, 25.0)
        // True motion: 15 m/s due east; positions carry alternating ±10 m
        // of north multipath, Doppler reports the honest velocity.
        for (i in 1..3) {
            val e = 15.0 * i
            val scatterN = if (i % 2 == 0) 10.0 else -10.0
            f.predict(1.0)
            f.update(e, scatterN, 15.0)
            f.updateVelocity(15.0, 0.0, 1.0)
        }
        assertEquals(15.0, f.vE, 1.0)
        assertTrue("north velocity must not chase multipath (got ${f.vN})", abs(f.vN) < 1.0)
    }

    @Test
    fun `velocity update keeps covariance finite and uncertainty positive`() {
        val f = Kalman2D()
        f.seed(0.0, 0.0, 25.0)
        repeat(100) {
            f.predict(1.0)
            f.update(0.0, 0.0, 10.0)
            f.updateVelocity(0.0, 0.0, 0.3)
        }
        assertTrue(f.uncertaintyM.isFinite())
        assertTrue(f.uncertaintyM > 0.0)
    }
}
