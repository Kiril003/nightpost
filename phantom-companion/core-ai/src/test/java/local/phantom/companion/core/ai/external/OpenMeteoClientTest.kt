package local.phantom.companion.core.ai.external

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W1-5 — pin the Open-Meteo HTTP contract + cache TTL semantics.
 *
 * Real network call is impossible in unit-test land; MockEngine
 * stands in. Each case feeds a curated JSON body and asserts the
 * client maps it onto the [WeatherSnapshot] shape the WeatherWidget
 * renders.
 */
class OpenMeteoClientTest {

    private val canonical12hBody = """
        {
          "timezone": "UTC",
          "hourly": {
            "time": [
              "2126-05-10T00:00", "2126-05-10T01:00", "2126-05-10T02:00",
              "2126-05-10T03:00", "2126-05-10T04:00", "2126-05-10T05:00",
              "2126-05-10T06:00", "2126-05-10T07:00", "2126-05-10T08:00",
              "2126-05-10T09:00", "2126-05-10T10:00", "2126-05-10T11:00"
            ],
            "temperature_2m": [10.0, 10.5, 11.0, 11.5, 12.0, 12.5, 13.0, 13.5, 14.0, 14.5, 15.0, 15.5],
            "wind_speed_10m": [2.0, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 3.0, 3.1],
            "precipitation":  [0.0, 0.0, 0.1, 0.2, 0.0, 0.0, 0.5, 1.0, 0.0, 0.0, 0.0, 0.0]
          }
        }
    """.trimIndent()

    private val canonical3dDailyBody = """
        {
          "timezone": "Europe/Kyiv",
          "daily": {
            "time": ["2126-05-10", "2126-05-11", "2126-05-12"],
            "temperature_2m_min": [8.0, 9.5, 7.0],
            "temperature_2m_max": [18.0, 21.0, 16.5],
            "precipitation_sum": [0.0, 3.2, 0.4],
            "precipitation_probability_max": [5, 80, 30],
            "wind_speed_10m_max": [4.0, 6.5, 5.0]
          }
        }
    """.trimIndent()

    @Test
    fun `R1_2 parses 3-day daily forecast into DailySnapshot`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = canonical3dDailyBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        val snap = client.fetchDaily(50.45, 30.52, days = 3)
        assertNotNull(snap)
        assertEquals(3, snap!!.days.size)
        assertEquals("2126-05-11", snap.days[1].dateIso)
        assertEquals(9.5f, snap.days[1].tempMinC, 1e-3f)
        assertEquals(21.0f, snap.days[1].tempMaxC, 1e-3f)
        assertEquals(3.2f, snap.days[1].rainMm, 1e-3f)
        assertEquals(80, snap.days[1].rainProbPct)
        assertEquals(6.5f, snap.days[1].windMaxMs, 1e-3f)
        client.close()
    }

    @Test
    fun `R1_2 daily cache hit within TTL`() = runTest {
        var requests = 0
        val engine = MockEngine { _ ->
            requests++
            respond(
                content = canonical3dDailyBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        val first = client.fetchDaily(50.45, 30.52)
        val second = client.fetchDaily(50.45, 30.52)
        assertEquals(1, requests)
        assertEquals(first, second)
        client.close()
    }

    @Test
    fun `R1_2 daily non-2xx returns null (honest degradation)`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.ServiceUnavailable) }
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        assertNull(client.fetchDaily(50.45, 30.52))
        client.close()
    }

    @Test
    fun `parses 12-hour hourly forecast into snapshot`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = canonical12hBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        // Anchor clock to before the canonical body's timestamps so
        // none of the 12 hours are filtered out as "past".
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        val snap = client.fetch(50.45, 30.52)
        assertNotNull(snap)
        assertEquals(12, snap!!.hours.size)
        assertEquals(10.0f, snap.hours[0].tempC, 1e-3f)
        assertEquals(2.0f, snap.hours[0].windMs, 1e-3f)
        assertEquals(0.5f, snap.hours[6].rainMm, 1e-3f)
        client.close()
    }

    @Test
    fun `cache hit on second call to same coords (within TTL)`() = runTest {
        var requests = 0
        val engine = MockEngine { _ ->
            requests++
            respond(
                content = canonical12hBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        val first = client.fetch(50.45, 30.52)
        val second = client.fetch(50.45, 30.52)
        assertEquals(1, requests)
        assertEquals(first, second)
        client.close()
    }

    @Test
    fun `coords rounded to 0_1 share cache hits`() = runTest {
        var requests = 0
        val engine = MockEngine { _ ->
            requests++
            respond(
                content = canonical12hBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        client.fetch(50.45, 30.52)
        client.fetch(50.46, 30.51) // ~10m away — same rounded key (50.4, 30.5)
        assertEquals(1, requests)
        client.close()
    }

    @Test
    fun `cache expires after TTL — second call refetches`() = runTest {
        var requests = 0
        val engine = MockEngine { _ ->
            requests++
            respond(
                content = canonical12hBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val now = arrayOf(0L)
        val ttlMs = 60L * 60L * 1000L
        val client = OpenMeteoClient(engine = engine, cacheTtlMs = ttlMs, clockMs = { now[0] })
        client.fetch(50.45, 30.52)
        now[0] = ttlMs + 1L
        client.fetch(50.45, 30.52)
        assertEquals(2, requests)
        client.close()
    }

    @Test
    fun `5xx returns null without throwing`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.InternalServerError) }
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        assertNull(client.fetch(50.45, 30.52))
        client.close()
    }

    @Test
    fun `malformed body returns null`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "not json at all",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        assertNull(client.fetch(50.45, 30.52))
        client.close()
    }

    @Test
    fun `missing hourly arrays return null`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"timezone":"UTC","hourly":{}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        assertNull(client.fetch(50.45, 30.52))
        client.close()
    }

    @Test
    fun `request URL carries lat lon and required params`() = runTest {
        val captured = mutableListOf<String>()
        val engine = MockEngine { req ->
            captured += req.url.toString()
            respond(
                content = canonical12hBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        client.fetch(50.4501, 30.5234)
        client.close()
        val url = captured.single()
        assertTrue("must carry latitude", url.contains("latitude=50.4501"))
        assertTrue("must carry longitude", url.contains("longitude=30.5234"))
        assertTrue("must request the three hourly fields",
            url.contains("hourly=temperature_2m") &&
                url.contains("wind_speed_10m") &&
                url.contains("precipitation"))
        // Не питали — і споживач ставив на місце відповіді сталу 270°, тобто
        // «Західний» у кожному брифі радара. Питання в URL і є те, що робить
        // румб виміром, а не оздобою.
        assertTrue("must request the measured wind direction",
            url.contains("wind_direction_10m"))
        assertTrue("must request m/s wind units",
            url.contains("wind_speed_unit=ms"))
    }

    /** Напрямок їде як виміряли, а його відсутність — як null, не як 270°. */
    @Test
    fun `wind direction rides through, and its absence stays absent`() = runTest {
        val withDir = canonical12hBody.replace(
            "\"precipitation\":",
            "\"wind_direction_10m\": [10, 40, 95, 130, 185, 220, 265, 300, 350, 5, 45, 90],\n" +
                "            \"precipitation\":",
        )
        val engine = MockEngine { _ ->
            respond(
                content = withDir,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = OpenMeteoClient(engine = engine, clockMs = { 0L })
        val snap = client.fetch(50.45, 30.52)
        assertNotNull(snap)
        assertEquals(10, snap!!.hours.first().windDirDeg)
        client.close()

        val bare = MockEngine { _ ->
            respond(
                content = canonical12hBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client2 = OpenMeteoClient(engine = bare, clockMs = { 0L })
        val silent = client2.fetch(50.45, 30.52)
        assertNotNull("відсутній напрямок не сміє відкидати всю погоду", silent)
        assertNull("напрямок, якого не прислали, приїхав числом", silent!!.hours.first().windDirDeg)
        client2.close()
    }
}
