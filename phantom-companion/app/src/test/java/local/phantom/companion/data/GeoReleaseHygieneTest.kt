package local.phantom.companion.data

import kotlinx.coroutines.launch
import local.phantom.companion.core.net.geosrc.GeoClock
import local.phantom.companion.core.net.geosrc.MinIntervalLimiter
import local.phantom.companion.core.net.map.GeoEndpointsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Храповик ATLAS-0.5: реліз не має права підставити чужий сервер за
 * замовчуванням, а публічний геокодер — отримувати запити частіше, ніж
 * дозволяє його політика.
 */
class GeoReleaseHygieneTest {

    @Test
    fun `release default is disabled, not the public demo servers`() {
        val fallback = geoFallbackFor(debug = false, devHost = "10.0.2.2")
        assertNull(fallback.routingTemplate)
        assertNull(fallback.geocodeBaseUrl)
        assertNull(fallback.rasterTileTemplate)
        assertEquals(GeoEndpointsConfig.DISABLED, fallback)
    }

    @Test
    fun `release default shares nothing with the forbidden demo stack`() {
        val forbidden = GeoEndpointsConfig.devFallback()
        val fallback = geoFallbackFor(debug = false, devHost = "10.0.2.2")
        assertTrue(fallback.routingTemplate != forbidden.routingTemplate)
        assertTrue(fallback.geocodeBaseUrl != forbidden.geocodeBaseUrl)
        assertTrue(fallback.rasterTileTemplate != forbidden.rasterTileTemplate)
    }

    @Test
    fun `debug still boots the self-hosted local stack`() {
        val fallback = geoFallbackFor(debug = true, devHost = "192.168.1.7")
        assertEquals("http://192.168.1.7:8080/{profile}", fallback.routingTemplate)
        assertEquals("http://192.168.1.7:8088", fallback.geocodeBaseUrl)
    }

    @Test
    fun `public geocode tiers share one budget of at least one per second`() {
        assertTrue(PUBLIC_GEOCODE_MIN_GAP_MS >= 1_100L)

        var now = 0L
        val limiter = MinIntervalLimiter(PUBLIC_GEOCODE_MIN_GAP_MS, clock = GeoClock { now })
        assertTrue(limiter.tryAcquire())
        // Другий тір іде одразу за першим — саме так виглядав спам на публічний хост.
        assertTrue(!limiter.tryAcquire())
        assertTrue(limiter.retryAfterMs() >= 1_000L)

        now += PUBLIC_GEOCODE_MIN_GAP_MS
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `awaiting a slot returns once the budget refills`() {
        var now = 0L
        val limiter = MinIntervalLimiter(PUBLIC_GEOCODE_MIN_GAP_MS, clock = GeoClock { now })
        assertTrue(limiter.tryAcquire())
        kotlinx.coroutines.test.runTest {
            // Віртуальний час: delay() усередині awaitPublicGeocodeSlot не займає
            // реальних секунд, але годинник джерела мусить рухатись разом із ним.
            val job = launch { awaitPublicGeocodeSlot(limiter) }
            var ticks = 0
            while (job.isActive && ticks++ < 100) {
                now += 100L
                testScheduler.advanceTimeBy(100L)
                testScheduler.runCurrent()
            }
            assertTrue(job.isCompleted)
            assertTrue(now >= PUBLIC_GEOCODE_MIN_GAP_MS)
        }
    }
}
