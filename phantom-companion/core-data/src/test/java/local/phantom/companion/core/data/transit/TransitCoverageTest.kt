package local.phantom.companion.core.data.transit

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.transit.TransitlandClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-3 — драбина покриття транспорту.
 *
 * До неї відповідь була бінарна: київська модель або мовчанка, однакова і
 * там, де транспорту нема, і там, де ми просто нічого про нього не знаємо.
 * Тут перевіряється і порядок ярусів, і головне: новий глобальний ярус
 * несе РОЗКЛАД і не отримує права на рухомі мітки (польовий вердикт 13).
 */
class TransitCoverageTest {

    /** Приблизні межі змодельованої київської мережі. */
    private val kyivModel = FeedBounds(south = 50.30, west = 30.20, north = 50.60, east = 30.85)

    private val kyiv = 50.4501 to 30.5234
    private val lviv = 49.8397 to 24.0297
    private val barcelona = 41.3874 to 2.1686

    private fun resolve(
        point: Pair<Double, Double>,
        bounds: FeedBounds? = kyivModel,
        hasLiveClient: Boolean = false,
        keyed: Boolean = false,
    ) = TransitCoverageResolver.resolve(
        lat = point.first,
        lon = point.second,
        simulatedBounds = bounds,
        hasLiveClient = hasLiveClient,
        transitlandAvailable = keyed,
    )

    // ————————————————————————————————————————————————————————————————
    // Порядок ярусів
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `реальний фід забирає точку навіть коли ключ Transitland є`() {
        val coverage = resolve(lviv, hasLiveClient = true, keyed = true)

        assertEquals(TransitPlane.LIVE_FEED, coverage.plane)
        assertEquals("Львів", coverage.liveFeed?.city)
    }

    @Test
    fun `реєстр знає фід, але без клієнта live не оголошується`() {
        // Бейдж «live» без даних — рівно та брехня, яку ця драбина закриває.
        val coverage = resolve(lviv, keyed = true)

        assertEquals(TransitPlane.STATIC_SCHEDULE, coverage.plane)
        assertNull(coverage.liveFeed)
    }

    @Test
    fun `київська модель лишається моделлю і не переозброюється Transitland-ом`() {
        val coverage = resolve(kyiv, keyed = true)

        assertEquals(TransitPlane.SIMULATED_MODEL, coverage.plane)
        assertTrue(coverage.isSimulated)
        assertFalse("модель не має права на рухомі мітки", coverage.feedsMovingVehicles)
    }

    @Test
    fun `решта світу отримує статичний розклад, коли ключ налаштовано`() {
        val coverage = resolve(barcelona, keyed = true)

        assertEquals(TransitPlane.STATIC_SCHEDULE, coverage.plane)
        assertTrue(coverage.feedsSchedules)
    }

    @Test
    fun `без ключа порожнеча має причину, а не вигляд «тут транспорту нема»`() {
        val coverage = resolve(barcelona)

        assertEquals(TransitPlane.NONE, coverage.plane)
        assertEquals(SourceUnavailable.MissingApiKey, coverage.absence)
        assertFalse(coverage.feedsSchedules)
    }

    @Test
    fun `без модельованої мережі й без ключа теж лишається чесна причина`() {
        val coverage = resolve(kyiv, bounds = null)

        assertEquals(TransitPlane.NONE, coverage.plane)
        assertEquals(SourceUnavailable.MissingApiKey, coverage.absence)
    }

    @Test
    fun `непридатна точка відхиляється окремою причиною`() {
        val coverage = resolve(Double.NaN to 30.5, keyed = true)

        assertEquals(TransitPlane.NONE, coverage.plane)
        assertTrue(coverage.absence is SourceUnavailable.RequestRejected)
    }

    @Test
    fun `рухомі мітки дозволені рівно одному ярусу`() {
        val planes = TransitPlane.entries.map { TransitCoverage(it) }

        assertEquals(
            listOf(TransitPlane.LIVE_FEED),
            planes.filter { it.feedsMovingVehicles }.map { it.plane },
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Джерело: ключ лише з конфігурації
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `порожній ключ — джерело чесно відсутнє і клієнта не існує`() = runBlocking {
        var built = 0
        val source = TransitlandSource(apiKey = "  ", clientFactory = { built++; error("не має будуватись") })

        assertFalse(source.available)
        listOf(
            source.stopsAround(barcelona.first, barcelona.second),
            source.linesAtStop("s-x"),
            source.departuresAtStop("s-x"),
            source.operatorsAround(barcelona.first, barcelona.second),
        ).forEach {
            assertEquals(SourceUnavailable.MissingApiKey, (it as GeoResult.Unavailable).reason)
        }
        assertEquals("без ключа HTTP-клієнта не має бути взагалі", 0, built)
    }

    @Test
    fun `null-ключ рівносильний порожньому`() {
        val source = TransitlandSource(apiKey = null, clientFactory = { error("не має будуватись") })

        assertFalse(source.available)
    }

    @Test
    fun `налаштований ключ не будує клієнта до першого запиту`() {
        var built = 0
        val source = TransitlandSource(
            apiKey = "tl-key",
            clientFactory = { built++; TransitlandClient.withKey(it) },
        )

        assertTrue(source.available)
        assertEquals("клієнт має бути лінивим — мапа відкривається без нього", 0, built)
    }
}
