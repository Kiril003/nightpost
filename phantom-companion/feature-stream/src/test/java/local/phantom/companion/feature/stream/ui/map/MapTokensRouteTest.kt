package local.phantom.companion.feature.stream.ui.map

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.feature.stream.ui.widget.MapPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTokensRouteTest {

    private fun line(color: String?) = MapPayload.PolylineData(
        points = listOf(MapPayload.PointData(50.0, 14.0), MapPayload.PointData(50.1, 14.1)),
        color = color,
    )

    private fun colorOf(raw: String): String =
        Json.parseToJsonElement(raw).jsonObject["features"]!!.jsonArray[0].jsonObject["properties"]!!
            .jsonObject["color"]!!.jsonPrimitive.content

    @Test
    fun `route is text ink of the theme, the alternative is muted ink`() {
        assertEquals("#1E1A15", routeColor(RouteRole.ROUTE, isDark = false))
        assertEquals("#ECEAE5", routeColor(RouteRole.ROUTE, isDark = true))
        assertEquals(routeColor(RouteRole.ROUTE, false), routeColor(RouteRole.ACCESS, false))
        assertEquals(routeColor(RouteRole.ROUTE, true), routeColor(RouteRole.INDOOR, true))
        assertEquals("#776D60", routeColor(RouteRole.ALT, isDark = false))
    }

    @Test
    fun `a role re-inks with the theme, a transit line keeps its own colour`() {
        val route = listOf(line(RouteRole.ROUTE.id))
        assertEquals(MapTokens.ROUTE_INK_DAY, colorOf(buildPolylinesGeoJson(route, isDark = false)))
        assertEquals(MapTokens.ROUTE_INK_NIGHT, colorOf(buildPolylinesGeoJson(route, isDark = true)))
        assertEquals("#2E6DB4", colorOf(buildPolylinesGeoJson(listOf(line("#2E6DB4")), isDark = true)))
        assertEquals(MapTokens.ROUTE_INK_NIGHT, colorOf(buildPolylinesGeoJson(listOf(line(null)), isDark = true)))
    }

    @Test
    fun `route producers hold no colour literal`() {
        val dir = File("src/main/java/local/phantom/companion/feature/stream/ui/map")
        listOf("MapRouteCoordinator.kt", "EvacuationCoordinator.kt").forEach { name ->
            val hex = Regex("\"#[0-9A-Fa-f]{6}\"").findAll(File(dir, name).readText()).map { it.value }.toList()
            assertTrue("$name: $hex", hex.isEmpty())
        }
    }

    @Test
    fun `route chevrons are drawn in the halo tone on the ink line, not white`() {
        val src = File("src/main/java/local/phantom/companion/feature/stream/ui/map/MapStyleLayers.kt").readText()
        val arrow = src.substringAfter("internal fun createRouteArrowBitmap(").substringBefore("\n}\n")
        assertTrue(arrow.contains("MapTokens.routeHalo(isDark)"))
        assertTrue(!arrow.contains("Color.WHITE"))
    }
}
