package local.phantom.companion.core.net.transit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.geosrc.GeoBBox
import local.phantom.companion.core.net.geosrc.GeoClock
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.geosrc.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тести глобального джерела розкладів (ATLAS-3).
 *
 * Transitland — платний за квотою агрегатор чужих GTFS-фідів, і саме тому
 * перевіряються насамперед межі: ключ лише заголовком (URL осідає в
 * журналах і в ключі кеша), відсутній ключ — чесна відсутність замість
 * порожнього табло, і жодного мережевого дотику, поки ключа немає.
 *
 * Мережі тут немає — усі відповіді підставляє мок-двигун.
 */
class TransitlandClientTest {

    private class FakeClock(var now: Long = 0L) : GeoClock {
        override fun nowMs(): Long = now
    }

    private val kyiv = GeoBBox(minLat = 50.34, minLon = 30.24, maxLat = 50.59, maxLon = 30.82)

    private fun json(body: String) = MockEngine { _ ->
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    /** Табло однієї зупинки: розклад, один рейс із прогнозом, один нічний. */
    private val departuresBody = """
        {"stops":[{"onestop_id":"s-u8vzm-lisova","stop_name":"Лісова","departures":[
          {"departure_time":"14:32:00","stop_headsign":"Академмістечко",
           "trip":{"trip_id":"t-1","trip_headsign":"Академмістечко",
             "route":{"onestop_id":"r-u8vz-m1","route_short_name":"М1",
               "route_long_name":"Святошинсько-Броварська","route_type":1,
               "agency":{"agency_name":"Київський метрополітен"}}}},
          {"departure":{"scheduled":"14:20:00","estimated":"14:23:30"},
           "trip":{"trip_id":"t-2","trip_headsign":"Вокзальна",
             "route":{"onestop_id":"r-u8vz-t14","route_short_name":"14",
               "route_type":0,"agency":{"agency_name":"Київпастранс"}}}},
          {"departure_time":"25:10:00",
           "trip":{"trip_id":"t-3","route":{"onestop_id":"r-u8vz-n1","route_type":3}}},
          {"trip":{"trip_id":"t-broken","route":{"onestop_id":"r-x"}}}
        ]}]}
    """.trimIndent()

    // ————————————————————————————————————————————————————————————————
    // Ключ
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `без ключа джерело чесно відсутнє і мережі не торкається`() = runTest {
        val engine = json("""{"stops":[]}""")
        val client = TransitlandClient(apiKey = null, engine = engine)

        val stops = client.stopsInBbox(kyiv)
        val departures = client.departuresAtStop("s-u8vzm-lisova")

        assertEquals(
            "порожній ключ — це MissingApiKey, а не порожній список",
            SourceUnavailable.MissingApiKey,
            (stops as GeoResult.Unavailable).reason,
        )
        assertEquals(SourceUnavailable.MissingApiKey, (departures as GeoResult.Unavailable).reason)
        assertTrue("запиту не мало бути взагалі", engine.requestHistory.isEmpty())
        assertFalse(client.hasApiKey)
    }

    @Test
    fun `порожній рядок ключа рівносильний відсутньому`() = runTest {
        val engine = json("""{"stops":[]}""")
        val client = TransitlandClient(apiKey = "   ", engine = engine)

        assertFalse(client.hasApiKey)
        assertTrue(client.stopsInBbox(kyiv) is GeoResult.Unavailable)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `ключ іде заголовком і ніколи не потрапляє в URL`() = runTest {
        // URL осідає в журналі мережі, в ключі кеша і в звітах про збої —
        // ключ звідти витікає без жодної атаки.
        val secret = "tl-secret-key-42"
        val engine = json(departuresBody)
        val client = TransitlandClient(apiKey = secret, engine = engine)

        client.departuresAtStop("s-u8vzm-lisova")
        client.stopsInBbox(kyiv)

        assertEquals(2, engine.requestHistory.size)
        engine.requestHistory.forEach { request ->
            assertEquals(secret, request.headers["apikey"])
            assertFalse(
                "ключ у URL: ${request.url}",
                request.url.toString().contains(secret),
            )
        }
    }

    // ————————————————————————————————————————————————————————————————
    // Табло відправлень
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `табло розбирається з обох форм часу і сортується за фактичним часом`() = runTest {
        val client = TransitlandClient(apiKey = "k", engine = json(departuresBody))

        val list = client.departuresAtStop("s-u8vzm-lisova").valueOrNull()

        assertEquals("рейс без жодного часу не має потрапляти в табло", 3, list?.size)
        assertEquals(listOf("t-2", "t-1", "t-3"), list?.map { it.tripId })
    }

    @Test
    fun `прогноз реального часу позначається лише там, де він справді прийшов`() = runTest {
        val client = TransitlandClient(apiKey = "k", engine = json(departuresBody))

        val byTrip = client.departuresAtStop("s-u8vzm-lisova").valueOrNull()!!.associateBy { it.tripId }

        val predicted = byTrip.getValue("t-2")
        assertTrue(predicted.isRealtime)
        assertEquals(14 * 3600 + 20 * 60, predicted.scheduledSecondOfDay)
        assertEquals(14 * 3600 + 23 * 60 + 30, predicted.estimatedSecondOfDay)
        assertEquals("показуємо прогноз, а не розклад", 14 * 3600 + 23 * 60 + 30, predicted.effectiveSecondOfDay)

        val scheduled = byTrip.getValue("t-1")
        assertFalse("розклад не має вдавати прогноз", scheduled.isRealtime)
        assertNull(scheduled.estimatedSecondOfDay)
    }

    @Test
    fun `нічний рейс лишається наступною добою, а не переїжджає на ранок`() = runTest {
        val client = TransitlandClient(apiKey = "k", engine = json(departuresBody))

        val night = client.departuresAtStop("s-u8vzm-lisova").valueOrNull()!!.first { it.tripId == "t-3" }

        assertEquals(25 * 3600 + 10 * 60, night.scheduledSecondOfDay)
    }

    @Test
    fun `вид транспорту береться з route_type, а назва лише уточнює`() = runTest {
        val client = TransitlandClient(apiKey = "k", engine = json(departuresBody))

        val byTrip = client.departuresAtStop("s-u8vzm-lisova").valueOrNull()!!.associateBy { it.tripId }

        assertEquals(TransitMode.METRO, byTrip.getValue("t-1").mode)
        assertEquals(TransitMode.TRAM, byTrip.getValue("t-2").mode)
        assertEquals("Академмістечко", byTrip.getValue("t-1").headsign)
    }

    @Test
    fun `айді зупинки береться з відповіді, а не з запиту`() = runTest {
        // Сервіс розвʼязує псевдоніми: збережений запитаний айді потім не
        // зійшовся б із айді в решті даних.
        val client = TransitlandClient(apiKey = "k", engine = json(departuresBody))

        val first = client.departuresAtStop("s-alias").valueOrNull()!!.first()

        assertEquals("s-u8vzm-lisova", first.stopOnestopId)
    }

    @Test
    fun `порожній Onestop ID відхиляється до мережі`() = runTest {
        val engine = json(departuresBody)
        val client = TransitlandClient(apiKey = "k", engine = engine)

        val result = client.departuresAtStop("  ")

        assertTrue((result as GeoResult.Unavailable).reason is SourceUnavailable.RequestRejected)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `битий JSON — це відмова джерела, а не порожнє табло`() = runTest {
        val client = TransitlandClient(apiKey = "k", engine = json("{ це не json"))

        val result = client.departuresAtStop("s-u8vzm-lisova")

        assertTrue((result as GeoResult.Unavailable).reason is SourceUnavailable.Malformed)
    }

    @Test
    fun `відповідь без відправлень — порожній список, а не відмова`() = runTest {
        val client = TransitlandClient(apiKey = "k", engine = json("""{"stops":[]}"""))

        val result = client.departuresAtStop("s-u8vzm-lisova")

        assertEquals(emptyList<TransitDeparture>(), result.valueOrNull())
    }

    // ————————————————————————————————————————————————————————————————
    // Квота
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `повторне табло тієї самої зупинки живе з кеша`() = runTest {
        val clock = FakeClock()
        val engine = json(departuresBody)
        val client = TransitlandClient(apiKey = "k", engine = engine, clock = clock)

        client.departuresAtStop("s-u8vzm-lisova")
        clock.now = TransitlandClient.DEFAULT_CACHE_TTL_MS - 1
        val cached = client.departuresAtStop("s-u8vzm-lisova")

        assertEquals("другого мережевого запиту бути не мало", 1, engine.requestHistory.size)
        assertEquals(3, cached.valueOrNull()?.size)

        clock.now = TransitlandClient.DEFAULT_CACHE_TTL_MS + 1
        client.departuresAtStop("s-u8vzm-lisova")
        assertEquals("після TTL — новий запит", 2, engine.requestHistory.size)
    }

    @Test
    fun `різний горизонт табло — різні записи кеша`() = runTest {
        val engine = json(departuresBody)
        val client = TransitlandClient(apiKey = "k", engine = engine)

        client.departuresAtStop("s-u8vzm-lisova", withinSeconds = 600)
        client.departuresAtStop("s-u8vzm-lisova", withinSeconds = 7_200)

        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun `горизонт табло не виходить за добу`() = runTest {
        val engine = json(departuresBody)
        val client = TransitlandClient(apiKey = "k", engine = engine)

        client.departuresAtStop("s-u8vzm-lisova", withinSeconds = 999_999)

        val next = engine.requestHistory.first().url.parameters["next"]
        assertEquals(TransitlandClient.MAX_DEPARTURE_HORIZON_SEC.toString(), next)
    }
}
