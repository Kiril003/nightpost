package local.phantom.companion.core.ai.tool

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.ai.external.OpenMeteoClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W1-5 — pin the WeatherTool placeholder-detect + hydrate contract.
 */
class WeatherToolTest {

    private val canonicalBody = """
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

    private fun mockClient(body: String = canonicalBody, status: HttpStatusCode = HttpStatusCode.OK): OpenMeteoClient {
        val engine = MockEngine { _ ->
            if (status.value in 200..299) respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            ) else respondError(status)
        }
        return OpenMeteoClient(engine = engine, clockMs = { 0L })
    }

    /* ---- isPlaceholderRequest ---- */

    @Test
    fun `placeholder detected when payload has request marker`() {
        val tool = WeatherTool(mockClient(), LocationContextProvider { null })
        assertTrue(tool.isPlaceholderRequest("""{"type":"weather","payload":{"request":"current"}}"""))
    }

    @Test
    fun `placeholder detected when top-level has request marker`() {
        val tool = WeatherTool(mockClient(), LocationContextProvider { null })
        assertTrue(tool.isPlaceholderRequest("""{"type":"weather","request":"current"}"""))
    }

    @Test
    fun `placeholder detected when hours array is empty`() {
        val tool = WeatherTool(mockClient(), LocationContextProvider { null })
        assertTrue(tool.isPlaceholderRequest("""{"location":"Київ","hours":[]}"""))
    }

    @Test
    fun `populated payload is not a placeholder`() {
        val tool = WeatherTool(mockClient(), LocationContextProvider { null })
        val populated = """
            {"location":"Київ","hours":[{"ts":1,"temp_c":10,"wind_ms":2,"rain_mm":0}]}
        """.trimIndent()
        assertFalse(tool.isPlaceholderRequest(populated))
    }

    @Test
    fun `malformed JSON is treated as not-a-placeholder`() {
        // We don't want a malformed payload to trigger an unsolicited
        // hydrate call; let the renderer's parser handle the fallback.
        val tool = WeatherTool(mockClient(), LocationContextProvider { null })
        assertFalse(tool.isPlaceholderRequest("not json"))
    }

    /* ---- fetchCurrent ---- */

    @Test
    fun `fetchCurrent returns NoLocation when provider has no fix`() = runTest {
        val tool = WeatherTool(mockClient(), LocationContextProvider { null })
        val result = tool.fetchCurrent()
        assertEquals(WeatherTool.WeatherFetchResult.NoLocation, result)
    }

    @Test
    fun `fetchCurrent returns NetworkFailure on Open-Meteo failure`() = runTest {
        val tool = WeatherTool(
            openMeteoClient = mockClient(status = HttpStatusCode.InternalServerError),
            locationProvider = LocationContextProvider { LocationFix(50.45, 30.52) },
        )
        val result = tool.fetchCurrent()
        assertEquals(WeatherTool.WeatherFetchResult.NetworkFailure, result)
    }

    @Test
    fun `fetchCurrent returns Hydrated payload populated with hours`() = runTest {
        val tool = WeatherTool(
            openMeteoClient = mockClient(),
            locationProvider = LocationContextProvider { LocationFix(50.45, 30.52) },
        )
        val result = tool.fetchCurrent(locationLabel = "Київ")
        assertTrue(result is WeatherTool.WeatherFetchResult.Hydrated)
        val hydrated = result as WeatherTool.WeatherFetchResult.Hydrated
        assertEquals("Київ", hydrated.location)

        val obj = Json.parseToJsonElement(hydrated.payloadJson).jsonObject
        assertEquals("Київ", obj["location"]?.jsonPrimitive?.content)
        val hours = obj["hours"]?.jsonArray
        assertNotNull(hours)
        assertEquals(12, hours!!.size)
        // Spot-check the WeatherWidget-shape field names — `temp_c`,
        // `wind_ms`, `rain_mm` (snake_case via @SerialName), with the
        // first hour matching the canonical body.
        val first = hours[0].jsonObject
        assertEquals(10.0f, first["temp_c"]?.jsonPrimitive?.content?.toFloat() ?: 0f, 1e-3f)
        assertEquals(2.0f, first["wind_ms"]?.jsonPrimitive?.content?.toFloat() ?: 0f, 1e-3f)
        assertEquals(0.0f, first["rain_mm"]?.jsonPrimitive?.content?.toFloat() ?: 0f, 1e-3f)
    }
}
