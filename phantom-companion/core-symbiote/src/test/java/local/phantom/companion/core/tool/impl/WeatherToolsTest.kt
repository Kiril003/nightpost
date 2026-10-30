package local.phantom.companion.core.tool.impl

import android.app.Application
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.ai.external.OpenMeteoClient
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1.2 — unit tests for the agentic [WeatherNowTool] / [WeatherForecastTool].
 * OpenMeteoClient is driven by Ktor MockEngine; location by a fake provider.
 */
class WeatherToolsTest {

    private val stubContext = Application()

    private fun ctx() = ToolContext(
        appContext = stubContext,
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = setOf(ToolPermission.NETWORK, ToolPermission.LOCATION),
    )

    private val hourlyBody = """
        {
          "timezone": "Europe/Kyiv",
          "hourly": {
            "time": ["2126-05-10T00:00", "2126-05-10T01:00", "2126-05-10T02:00"],
            "temperature_2m": [10.0, 10.5, 11.0],
            "wind_speed_10m": [2.0, 2.1, 2.2],
            "precipitation":  [0.0, 0.3, 0.0]
          }
        }
    """.trimIndent()

    private val dailyBody = """
        {
          "timezone": "Europe/Kyiv",
          "daily": {
            "time": ["2126-05-10", "2126-05-11"],
            "temperature_2m_min": [8.0, 9.5],
            "temperature_2m_max": [18.0, 21.0],
            "precipitation_sum": [0.0, 3.2],
            "precipitation_probability_max": [5, 80],
            "wind_speed_10m_max": [4.0, 6.5]
          }
        }
    """.trimIndent()

    private fun okClient(body: String) = OpenMeteoClient(
        engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
        clockMs = { 0L },
    )

    private val kyivFix = LocationContextProvider { LocationFix(50.4501, 30.5234) }

    @Test
    fun `now - real hourly numbers reach the structured output`() = runTest {
        val tool = WeatherNowTool(okClient(hourlyBody), kyivFix)
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val s = (result as ToolResult.Ok).structured!!
        val hours = s["hours"]!!.jsonArray
        assertEquals(3, hours.size)
        assertEquals("10.0", hours[0].jsonObject["temp_c"]!!.jsonPrimitive.content)
        assertEquals("0.3", hours[1].jsonObject["rain_mm"]!!.jsonPrimitive.content)
        assertEquals("Europe/Kyiv", s["timezone"]!!.jsonPrimitive.content)
    }

    @Test
    fun `now - explicit lat lon override ambient location`() = runTest {
        var requestedUrl = ""
        val client = OpenMeteoClient(
            engine = MockEngine { req ->
                requestedUrl = req.url.toString()
                respond(
                    content = hourlyBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
            clockMs = { 0L },
        )
        val tool = WeatherNowTool(client, kyivFix)
        val result = tool.execute(
            buildJsonObject {
                put("lat", 49.8397)
                put("lon", 24.0297)
            },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Ok)
        assertTrue("url should carry explicit lat: $requestedUrl", requestedUrl.contains("latitude=49.8397"))
    }

    @Test
    fun `now - no coords anywhere yields honest no_location`() = runTest {
        val tool = WeatherNowTool(okClient(hourlyBody), LocationContextProvider { null })
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("no_location", (result as ToolResult.Err).code)
    }

    @Test
    fun `now - OpenMeteo failure yields weather_unavailable, never numbers`() = runTest {
        val client = OpenMeteoClient(
            engine = MockEngine { _ -> respondError(HttpStatusCode.ServiceUnavailable) },
            clockMs = { 0L },
        )
        val tool = WeatherNowTool(client, kyivFix)
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("weather_unavailable", (result as ToolResult.Err).code)
    }

    @Test
    fun `forecast - real daily numbers reach the structured output`() = runTest {
        val tool = WeatherForecastTool(okClient(dailyBody), kyivFix)
        val result = tool.execute(buildJsonObject { put("days", 2) }, ctx()) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val days = (result as ToolResult.Ok).structured!!["days"]!!.jsonArray
        assertEquals(2, days.size)
        assertEquals("21.0", days[1].jsonObject["temp_max_c"]!!.jsonPrimitive.content)
        assertEquals("80", days[1].jsonObject["rain_prob_pct"]!!.jsonPrimitive.content)
    }

    @Test
    fun `forecast - failure yields weather_unavailable`() = runTest {
        val client = OpenMeteoClient(
            engine = MockEngine { _ -> respondError(HttpStatusCode.BadGateway) },
            clockMs = { 0L },
        )
        val tool = WeatherForecastTool(client, kyivFix)
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("weather_unavailable", (result as ToolResult.Err).code)
    }

    @Test
    fun `ids and permissions match the R1_2 contract`() {
        val now = WeatherNowTool(okClient(hourlyBody), kyivFix)
        val fc = WeatherForecastTool(okClient(dailyBody), kyivFix)
        assertEquals("weather.now", now.id)
        assertEquals("weather.forecast", fc.id)
        assertEquals(setOf(ToolPermission.NETWORK, ToolPermission.LOCATION), now.permissions)
        assertEquals(setOf(ToolPermission.NETWORK, ToolPermission.LOCATION), fc.permissions)
    }
}
