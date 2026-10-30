package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * The display smoother against the two failures it sits between.
 *
 * Behind it: the sawtooth of 2026-08-04, a walk up one street rendered as a
 * zigzag through the buildings either side. Ahead of it: the sin the precision
 * pivot removed — a parked phone drawing motion it never made. A filter that
 * fixes the first by committing the second is not a fix, so the parked cases
 * here are as load-bearing as the jitter ones.
 */
class FixSmootherTest {

    private val lat0 = 50.5040
    private val lon0 = 30.4094

    /** Degrees of latitude per metre, near enough at this latitude. */
    private val degPerM = 1.0 / 111_320.0

    // — the pivot's law: never invent motion ————————————————————————————————

    @Test
    fun `a phone that does not move does not move`() {
        val s = FixSmoother()
        s.smooth(lat0, lon0, speedMps = 0f, elapsedMs = 1_000L)
        repeat(120) { i ->
            val out = s.smooth(lat0, lon0, speedMps = 0f, elapsedMs = 2_000L + i * 1_000L)
            assertEquals("output drifted off a motionless input", lat0, out.lat, 0.0)
            assertEquals("output drifted off a motionless input", lon0, out.lon, 0.0)
        }
    }

    @Test
    fun `the same fix pulled twice cannot walk the puck`() {
        val s = FixSmoother()
        s.smooth(lat0, lon0, speedMps = 0f, elapsedMs = 1_000L)
        val moved = s.smooth(lat0 + 20 * degPerM, lon0, speedMps = 0f, elapsedMs = 2_000L)
        // The display path pulls this lambda repeatedly between fixes.
        repeat(10) {
            val again = s.smooth(lat0 + 20 * degPerM, lon0, speedMps = 0f, elapsedMs = 2_000L)
            assertEquals(moved.lat, again.lat, 0.0)
            assertEquals(moved.lon, again.lon, 0.0)
        }
    }

    // — the 04.08 sawtooth ————————————————————————————————————————————————

    @Test
    fun `a standing phone in a 30 m scatter is not dragged around by it`() {
        val s = FixSmoother()
        s.smooth(lat0, lon0, speedMps = 0f, elapsedMs = 1_000L)

        var worst = 0.0
        repeat(60) { i ->
            val jitter = if (i % 2 == 0) 30.0 else -30.0
            val out = s.smooth(
                lat0 + jitter * degPerM, lon0,
                speedMps = 0f, elapsedMs = 2_000L + i * 1_000L,
            )
            worst = max(worst, haversineM(lat0, lon0, out.lat, out.lon))
        }
        // Raw input swung ±30 m every second. Anything near that is the sawtooth.
        assertTrue("puck wandered $worst m on a phone standing still", worst < 12.0)
    }

    @Test
    fun `a jittering walk is damped without being stopped`() {
        val raw = FixSmoother()
        val s = FixSmoother()
        s.smooth(lat0, lon0, speedMps = 1.4f, elapsedMs = 1_000L)
        raw.smooth(lat0, lon0, speedMps = 1.4f, elapsedMs = 1_000L)

        var rawPathM = 0.0
        var outPathM = 0.0
        var prevOutLat = lat0
        var prevRawLat = lat0
        var trueLat = lat0

        repeat(60) { i ->
            trueLat += 1.4 * degPerM // an honest 1,4 m/s north
            val jitter = if (i % 2 == 0) 25.0 else -25.0
            val obsLat = trueLat + jitter * degPerM
            val out = s.smooth(obsLat, lon0, speedMps = 1.4f, elapsedMs = 2_000L + i * 1_000L)

            outPathM += haversineM(prevOutLat, lon0, out.lat, lon0)
            rawPathM += haversineM(prevRawLat, lon0, obsLat, lon0)
            prevOutLat = out.lat
            prevRawLat = obsLat
        }

        // Path length is the sawtooth's signature: the operator walked ~84 m, the
        // raw trace draws several times that by folding back on itself each tick.
        assertTrue("smoothed path $outPathM m is not shorter than raw $rawPathM m", outPathM < rawPathM / 2)
        // ...and he must still have got there.
        val advancedM = haversineM(lat0, lon0, prevOutLat, lon0)
        assertTrue("the walk was damped into a standstill: $advancedM m", advancedM > 50.0)
    }

    // — it may lag, but only so far ————————————————————————————————————————

    @Test
    fun `an honest walk is followed with no meaningful lag`() {
        val s = FixSmoother()
        var lat = lat0
        s.smooth(lat, lon0, speedMps = 1.4f, elapsedMs = 1_000L)
        repeat(30) { i ->
            lat += 1.4 * degPerM
            val out = s.smooth(lat, lon0, speedMps = 1.4f, elapsedMs = 2_000L + i * 1_000L)
            val lagM = haversineM(out.lat, lon0, lat, lon0)
            assertTrue("clean 1,4 m/s walk lagged $lagM m at step $i", lagM < 0.5)
        }
    }

    @Test
    fun `a drive is never trailed by more than the leash`() {
        val s = FixSmoother()
        var lat = lat0
        s.smooth(lat, lon0, speedMps = 15f, elapsedMs = 1_000L)
        repeat(120) { i ->
            lat += 15.0 * degPerM
            val out = s.smooth(lat, lon0, speedMps = 15f, elapsedMs = 2_000L + i * 1_000L)
            val lagM = haversineM(out.lat, lon0, lat, lon0)
            assertTrue("puck fell $lagM m behind a 54 km/h drive", lagM <= FixSmoother.MAX_LAG_M + 1.0)
        }
    }

    @Test
    fun `the output never leads the chip`() {
        // Convex combination only: the puck may sit between the last two answers,
        // never beyond the newest one. This is what makes "never invents motion"
        // a property of the arithmetic rather than a promise.
        val s = FixSmoother()
        var lat = lat0
        var prev = lat0
        s.smooth(lat, lon0, speedMps = 8f, elapsedMs = 1_000L)
        repeat(50) { i ->
            prev = lat
            lat += 8.0 * degPerM
            val out = s.smooth(lat, lon0, speedMps = 8f, elapsedMs = 2_000L + i * 1_000L)
            assertTrue("output ran ahead of the fix", out.lat <= lat + 1e-12)
            assertTrue("output fell behind the previous fix", out.lat >= prev - 1e-12)
        }
    }
}
