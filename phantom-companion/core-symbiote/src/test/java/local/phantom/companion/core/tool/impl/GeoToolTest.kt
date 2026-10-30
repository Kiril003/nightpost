package local.phantom.companion.core.tool.impl

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.net.map.GeoEndpoints
import local.phantom.companion.core.net.map.GeoEndpointsConfig
import local.phantom.companion.core.net.map.RouteBasis
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R1.4 — unit tests for the geo stack (Nominatim / Overpass / OSRM via
 * MockEngine) + the GeoHttp Nominatim throttle.
 *
 * GeoSearchTool/GeoRouteTool resolve their base URLs from
 * [GeoEndpoints.config] at call time (GEO-LAW, licensing sweep) — tests
 * install the dev-fallback community-demo config so the URL-shape
 * assertions below still hold, and restore DISABLED after so no other
 * test class inherits a live config.
 */
class GeoToolTest {

    @Before
    fun installDevFallbackGeoConfig() {
        GeoEndpoints.config = GeoEndpointsConfig.devFallback()
    }

    @After
    fun resetGeoConfig() {
        GeoEndpoints.config = GeoEndpointsConfig.DISABLED
    }

    private val stubContext = Application()

    private fun ctx() = ToolContext(
        appContext = stubContext,
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = setOf(ToolPermission.NETWORK, ToolPermission.LOCATION),
    )

    private val kyivFix = LocationContextProvider { LocationFix(50.4501, 30.5234) }

    private fun httpReturning(body: String, capture: ((url: String, body: String?) -> Unit)? = null) =
        GeoHttp(HttpClient(MockEngine { req ->
            capture?.invoke(
                req.url.toString(),
                (req.body as? io.ktor.http.content.TextContent)?.text,
            )
            respond(content = body, status = HttpStatusCode.OK)
        }))

    private fun httpFailing() =
        GeoHttp(HttpClient(MockEngine { _ -> respondError(HttpStatusCode.BadGateway) }))

    // ── geo.search ──────────────────────────────────────────────────────

    private val nominatimBody = """
        [
          {"display_name":"Аптека АНЦ, Хрещатик, Київ","lat":"50.4470","lon":"30.5220","type":"pharmacy"},
          {"display_name":"Аптека Доброго Дня, Київ","lat":"50.4490","lon":"30.5260","type":"pharmacy"}
        ]
    """.trimIndent()

    @Test
    fun `search - parses places and builds a widget-ready map object`() = runTest {
        var url = ""
        val tool = GeoSearchTool(httpReturning(nominatimBody) { u, _ -> url = u })
        val result = tool.execute(buildJsonObject { put("query", "аптека київ") }, ctx()) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val s = (result as ToolResult.Ok).structured!!
        assertEquals(2, s["results"]!!.jsonArray.size)
        val map = s["map"]!!.jsonObject
        assertEquals("50.447", map["latitude"]!!.jsonPrimitive.content)
        assertEquals(2, map["markers"]!!.jsonArray.size)
        assertTrue(url.contains("format=jsonv2"))
        assertTrue(url.contains("limit=5"))
    }

    @Test
    fun `search - empty result is honest, not an error`() = runTest {
        val tool = GeoSearchTool(httpReturning("[]"))
        val result = tool.execute(buildJsonObject { put("query", "щосьнеіснуюче") }, ctx()) {}
        assertTrue(result is ToolResult.Ok)
        assertEquals(0, (result as ToolResult.Ok).structured!!["results"]!!.jsonArray.size)
        assertTrue(result.output.contains("Нічого не знайдено"))
    }

    @Test
    fun `search - service down yields geo_unavailable`() = runTest {
        val tool = GeoSearchTool(httpFailing())
        val result = tool.execute(buildJsonObject { put("query", "київ") }, ctx()) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("geo_unavailable", (result as ToolResult.Err).code)
    }

    // ── geo.nearby ──────────────────────────────────────────────────────

    private val overpassBody = """
        {"elements":[
          {"type":"node","lat":50.4489,"lon":30.5215,"tags":{"name":"Аптека 1","amenity":"pharmacy"}},
          {"type":"node","lat":50.4512,"lon":30.5251,"tags":{"amenity":"pharmacy"}}
        ]}
    """.trimIndent()

    @Test
    fun `nearby - builds an around-query and maps POIs with markers`() = runTest {
        var postBody: String? = null
        val tool = GeoNearbyTool(
            http = httpReturning(overpassBody) { _, b -> postBody = b },
            locationProvider = kyivFix,
        )
        val result = tool.execute(buildJsonObject { put("category", "pharmacy") }, ctx()) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val s = (result as ToolResult.Ok).structured!!
        val rows = s["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, rows.size)
        // Unnamed POI falls back to the category as its label. Asserted by lookup,
        // not by index: rows are ordered by distance, which is the point of «поруч».
        assertTrue(
            "unnamed POI must fall back to the category: $rows",
            rows.any { it["name"]!!.jsonPrimitive.content == "pharmacy" },
        )
        val distances = rows.map { it["distance_m"]!!.jsonPrimitive.content.toDouble() }
        assertEquals("nearest must come first: $distances", distances.sorted(), distances)
        assertEquals(2, s["map"]!!.jsonObject["markers"]!!.jsonArray.size)
        val decoded = java.net.URLDecoder.decode(postBody!!, "UTF-8")
        assertTrue("query must be an around-search: $decoded", decoded.contains("around:800,50.450100,30.523400"))
        assertTrue(decoded.contains("\"amenity\"=\"pharmacy\""))
    }

    @Test
    fun `nearby - non-latin category is rejected before any network call`() = runTest {
        val tool = GeoNearbyTool(httpFailing(), kyivFix)
        val result = tool.execute(buildJsonObject { put("category", "аптека) {дірка}") }, ctx()) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_args", (result as ToolResult.Err).code)
    }

    @Test
    fun `nearby - no coords yields no_location`() = runTest {
        val tool = GeoNearbyTool(httpReturning(overpassBody), LocationContextProvider { null })
        val result = tool.execute(buildJsonObject { put("category", "cafe") }, ctx()) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("no_location", (result as ToolResult.Err).code)
    }

    // ── geo.route ───────────────────────────────────────────────────────

    private val osrmBody = """
        {"code":"Ok","routes":[{"distance":1234.5,"duration":900.0,
          "geometry":{"coordinates":[[30.5234,50.4501],[30.5240,50.4495],[30.5250,50.4480]]}}]}
    """.trimIndent()

    @Test
    fun `route - parses OSRM geojson into distance duration and polyline map`() = runTest {
        var url = ""
        val tool = GeoRouteTool(
            http = httpReturning(osrmBody) { u, _ -> url = u },
            locationProvider = kyivFix,
        )
        val result = tool.execute(
            buildJsonObject {
                put("to_lat", 50.4480)
                put("to_lon", 30.5250)
                put("mode", "walking")
            },
            ctx(),
        ) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val s = (result as ToolResult.Ok).structured!!
        assertEquals("1234.5", s["distance_m"]!!.jsonPrimitive.content)
        assertEquals("900.0", s["duration_s"]!!.jsonPrimitive.content)
        assertEquals(3, s["points"]!!.jsonArray.size)
        val map = s["map"]!!.jsonObject
        assertEquals(2, map["markers"]!!.jsonArray.size)
        assertEquals(1, map["polylines"]!!.jsonArray.size)
        // from defaults to the ambient fix; OSRM takes lon,lat order.
        assertTrue(url.contains("/route/v1/walking/30.523400,50.450100;30.525000,50.448000"))
    }

    @Test
    fun `route - OSRM non-Ok code yields routing_unavailable`() = runTest {
        val tool = GeoRouteTool(
            http = httpReturning("""{"code":"NoRoute","routes":[]}"""),
            locationProvider = kyivFix,
        )
        val result = tool.execute(
            buildJsonObject { put("to_lat", 50.0); put("to_lon", 30.0) },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("routing_unavailable", (result as ToolResult.Err).code)
    }

    /**
     * The offline router knows the speed basis, the posted share and the metres
     * of highway shoulder; the tool used to squeeze the answer through a
     * Triple(distance, duration, points) and lose all three.
     */
    @Test
    fun `route - offline answer carries the speed basis and the shoulder warning`() = runTest {
        val tool = GeoRouteTool(
            http = httpFailing(),
            locationProvider = kyivFix,
            offlineRouteCalculator = { _, _, _, _, _ ->
                RouteVariant(
                    points = listOf(50.4501 to 30.5234, 50.4400 to 30.5300),
                    distanceMeters = 4000.0,
                    durationSeconds = 3000.0,
                    steps = emptyList(),
                    speedsMps = emptyList(),
                    profile = RouteProfile.FOOT,
                    speedBasis = "ASSUMED",
                    postedShare = 0.0,
                    trunkShoulderMeters = 900.0,
                )
            },
        )
        val result = tool.execute(
            buildJsonObject {
                put("to_lat", 50.4400); put("to_lon", 30.5300); put("mode", "walking")
            },
            ctx(),
        ) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertTrue(ok.output, ok.output.contains(RouteBasis.TRUNK_SHOULDER))
        assertTrue(ok.output, ok.output.contains("час — припущення"))
        val s = ok.structured!!
        assertEquals("ASSUMED", s["speed_basis"]!!.jsonPrimitive.content)
        assertEquals("900.0", s["trunk_shoulder_m"]!!.jsonPrimitive.content)
        assertEquals(2, s["basis"]!!.jsonArray.size)
    }

    @Test
    fun `route - service down yields routing_unavailable`() = runTest {
        val tool = GeoRouteTool(httpFailing(), kyivFix)
        val result = tool.execute(
            buildJsonObject { put("to_lat", 50.0); put("to_lon", 30.0) },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("routing_unavailable", (result as ToolResult.Err).code)
    }

    // ── GeoHttp Nominatim throttle ──────────────────────────────────────

    @Test
    fun `nominatim throttle sleeps between consecutive calls`() = runTest {
        val sleeps = mutableListOf<Long>()
        var now = 10_000L
        val http = GeoHttp(
            httpClient = HttpClient(MockEngine { _ ->
                respond(content = "[]", status = HttpStatusCode.OK)
            }),
            clockMs = { now },
            sleep = { sleeps += it },
        )
        // First call ever must NOT sleep.
        http.getNominatim("https://nominatim.openstreetmap.org/search?q=a")
        now += 200 // only 200ms passed — next call must wait the remainder
        http.getNominatim("https://nominatim.openstreetmap.org/search?q=b")
        assertEquals(1, sleeps.size)
        assertEquals(GeoHttp.NOMINATIM_MIN_INTERVAL_MS - 200, sleeps[0])
    }
}
