package local.phantom.companion.core.net.live

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.geosrc.GeoClock
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.geosrc.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-4 — транспорт живого шару.
 *
 * Перевіряється не «працює», а межа: що виїжджає з пристрою, що лишається,
 * і як площина поводиться, коли ключів немає. Мережі тут нема.
 */
class LiveMarksClientTest {

    private class FakeClock(var now: Long = 1_700_000_000_000L) : GeoClock {
        override fun nowMs(): Long = now
    }

    private val config = LiveMarksConfig(baseUrl = "https://проєкт.supabase.co", anonKey = "anon-ключ-123")

    private class Recorder(private val body: String, private val status: HttpStatusCode = HttpStatusCode.OK) {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    // ── геохеш ───────────────────────────────────────────────────────────

    @Test
    fun `геохеш збігається з еталоном`() {
        assertEquals("u4pruydqqvj", LiveGeohash.encode(57.64911, 10.40744, 11))
        assertEquals("u8vxn8", LiveGeohash.encode(50.4501, 30.5234, 6))
        assertEquals("u8vxn", LiveGeohash.encode(50.4501, 30.5234, LiveGeohash.QUERY_PRECISION))
    }

    @Test
    fun `сусідні точки лежать в одній клітинці, далекі — в різних`() {
        assertEquals(LiveGeohash.encode(50.4501, 30.5234), LiveGeohash.encode(50.4502, 30.5235))
        assertFalse(LiveGeohash.encode(50.4501, 30.5234) == LiveGeohash.encode(50.5001, 30.6234))
    }

    @Test
    fun `непридатна точка не дає клітинки`() {
        assertEquals("", LiveGeohash.encode(Double.NaN, 30.0))
        assertEquals("", LiveGeohash.encode(91.0, 30.0))
        assertTrue(LiveGeohash.regionCells(50.45, 30.52, -1.0).isEmpty())
    }

    @Test
    fun `регіон вкривається без дір і не роздувається`() {
        val cells = LiveGeohash.regionCells(50.4501, 30.5234, 3_000.0)
        assertTrue("центр мусить бути в наборі", LiveGeohash.encode(50.4501, 30.5234, 5) in cells)
        assertTrue("кут вʼюпорта мусить бути вкритий", LiveGeohash.encode(50.4721, 30.5580, 5) in cells)
        assertTrue("забагато клітинок: ${cells.size}", cells.size <= LiveMarksClient.MAX_CELLS)
    }

    // ── чесна відсутність ────────────────────────────────────────────────

    @Test
    fun `без ключів площини немає і мережі не буде`() = runTest {
        val rec = Recorder("[]")
        val client = LiveMarksClient(LiveMarksConfig(), engine = rec.engine)

        assertFalse(client.isConfigured)
        val push = client.push(LiveMarkDraft("ДТП", 50.45, 30.52, 1L))
        val pull = client.pull(listOf("u8vxn"))

        assertEquals(SourceUnavailable.MissingApiKey, (push as GeoResult.Unavailable).reason)
        assertEquals(SourceUnavailable.MissingApiKey, (pull as GeoResult.Unavailable).reason)
        assertTrue("жодного запиту бути не мало", rec.requests.isEmpty())
    }

    @Test
    fun `нешифрований хост не вважається налаштованим`() {
        assertFalse(LiveMarksConfig(baseUrl = "http://хост", anonKey = "k").isConfigured)
    }

    // ── що виїжджає з пристрою ───────────────────────────────────────────

    @Test
    fun `ключ їде в заголовках, ніколи в URL`() = runTest {
        val rec = Recorder("")
        val clock = FakeClock()
        val client = LiveMarksClient(config, deviceTag = "tag-1", engine = rec.engine, clock = clock)

        client.push(LiveMarkDraft("ДТП", 50.4501, 30.5234, clock.now + 60 * 60_000L))

        val request = rec.requests.single()
        assertEquals("anon-ключ-123", request.headers["apikey"])
        assertEquals("Bearer anon-ключ-123", request.headers["Authorization"])
        assertFalse("ключ у URL", request.url.toString().contains("anon-ключ"))
    }

    @Test
    fun `мітка їде з клітинкою і без жодної координати оператора`() = runTest {
        val rec = Recorder("")
        val clock = FakeClock()
        val client = LiveMarksClient(config, deviceTag = "tag-1", engine = rec.engine, clock = clock)

        client.push(LiveMarkDraft("ДТП", 50.4501, 30.5234, clock.now + 60 * 60_000L))

        val body = (rec.requests.single().body as io.ktor.http.content.TextContent).text
        assertTrue(body.contains("\"geohash6\":\"u8vxn8\""))
        assertTrue(body.contains("\"kind\":\"ДТП\""))
        assertTrue(body.contains("\"device_tag\":\"tag-1\""))
    }

    @Test
    fun `TTL зрізається до стелі RLS ще на пристрої`() = runTest {
        val rec = Recorder("")
        val clock = FakeClock()
        val client = LiveMarksClient(config, engine = rec.engine, clock = clock)

        client.push(LiveMarkDraft("ДТП", 50.45, 30.52, clock.now + 10L * 60 * 60_000))

        val body = (rec.requests.single().body as io.ktor.http.content.TextContent).text
        val expires = java.time.Instant.parse(Regex("\"expires_at\":\"([^\"]+)\"").find(body)!!.groupValues[1])
        assertTrue(expires.toEpochMilli() <= clock.now + LiveMarksClient.MAX_TTL_MS)
    }

    @Test
    fun `вид поза схемою не витрачає запит`() = runTest {
        val rec = Recorder("")
        val client = LiveMarksClient(config, engine = rec.engine)

        val r = client.push(LiveMarkDraft("камера", 50.45, 30.52, System.currentTimeMillis() + 60_000))

        assertTrue(r is GeoResult.Unavailable && r.reason is SourceUnavailable.RequestRejected)
        assertTrue(rec.requests.isEmpty())
    }

    @Test
    fun `мертва мітка не надсилається`() = runTest {
        val rec = Recorder("")
        val clock = FakeClock()
        val client = LiveMarksClient(config, engine = rec.engine, clock = clock)

        val r = client.push(LiveMarkDraft("ДТП", 50.45, 30.52, clock.now - 1L))

        assertTrue(r is GeoResult.Unavailable && r.reason is SourceUnavailable.RequestRejected)
        assertTrue(rec.requests.isEmpty())
    }

    // ── читання регіону ──────────────────────────────────────────────────

    @Test
    fun `регіон питається префіксом і не тягне чужих токенів`() = runTest {
        val rec = Recorder("[]")
        val clock = FakeClock()
        val client = LiveMarksClient(config, deviceTag = "tag-1", engine = rec.engine, clock = clock)

        client.pull(listOf("u8vxn", "u8vxp"))

        // Зірочку Ktor може закодувати як %2A — сервер розкодує назад, тож
        // перевіряється сам префікс, а не форма подання.
        val url = rec.requests.single().url.toString()
        assertTrue("префіксний фільтр: $url", url.contains("geohash6.like.u8vxn"))
        assertFalse("токен пристрою в запиті: $url", url.contains("tag-1"))
        assertFalse("токен пристрою вичитується назад: $url", url.contains("device_tag"))
    }

    @Test
    fun `рядки розбираються, мертві відсіюються`() = runTest {
        val clock = FakeClock()
        val alive = java.time.Instant.ofEpochMilli(clock.now + 30 * 60_000L)
        val dead = java.time.Instant.ofEpochMilli(clock.now - 60_000L)
        val body = """
            [{"id":"a","kind":"патруль","lat":50.45,"lon":30.52,"geohash6":"u8vxn8",
              "created_at":"${java.time.Instant.ofEpochMilli(clock.now - 600_000L)}","expires_at":"$alive"},
             {"id":"b","kind":"ДТП","lat":50.46,"lon":30.53,"geohash6":"u8vxn9",
              "created_at":"${java.time.Instant.ofEpochMilli(clock.now - 600_000L)}","expires_at":"$dead"}]
        """.trimIndent()
        val client = LiveMarksClient(config, engine = Recorder(body).engine, clock = clock)

        val rows = client.pull(listOf("u8vxn")).valueOrNull()

        assertEquals(1, rows?.size)
        assertEquals("патруль", rows?.single()?.kind)
    }

    @Test
    fun `порожній регіон не витрачає запит`() = runTest {
        val rec = Recorder("[]")
        val client = LiveMarksClient(config, engine = rec.engine)

        val r = client.pull(emptyList())

        assertTrue(r is GeoResult.Unavailable && r.reason is SourceUnavailable.RequestRejected)
        assertTrue(rec.requests.isEmpty())
    }

    @Test
    fun `друге читання в межах десяти секунд відсікається обмежувачем`() = runTest {
        val rec = Recorder("[]")
        val clock = FakeClock()
        val client = LiveMarksClient(config, engine = rec.engine, clock = clock)

        assertTrue(client.pull(listOf("u8vxn")) is GeoResult.Ok)
        val second = client.pull(listOf("u8vxn"))
        assertTrue(second is GeoResult.Unavailable && second.reason is SourceUnavailable.RateLimited)
        assertEquals(1, rec.requests.size)

        clock.now += LiveMarksClient.DEFAULT_MIN_PULL_INTERVAL_MS
        assertTrue(client.pull(listOf("u8vxn")) is GeoResult.Ok)
        assertEquals(2, rec.requests.size)
    }

    @Test
    fun `відхилений ключ читається як відхилений ключ, не як порожній район`() = runTest {
        val rec = Recorder("{\"message\":\"нема\"}", HttpStatusCode.Unauthorized)
        val client = LiveMarksClient(config, engine = rec.engine)

        val r = client.pull(listOf("u8vxn"))

        assertTrue(r is GeoResult.Unavailable && r.reason is SourceUnavailable.InvalidApiKey)
    }
}
