package local.phantom.companion.feature.stream.ui.map.around

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builder B1 (R4): опублікована сторінка з точкою — пін на мапі застосунку, дотик — її картка. */
class VenuePinsTest {

    private val cafe = AroundPlace("Cafe X", 49.8356, 18.2925, address = "Nádražní 12", venueId = "07abd3049779a2a5")
    private val osm = AroundPlace("Kiosk", 49.836, 18.293, kind = "cafe")

    @Test
    fun `only Nightpost places become pins, map places and unknown answers do not`() {
        assertEquals(listOf(cafe), venuePinsOf(AroundPlaces.Shown(listOf(cafe, osm), venues = true)))
        assertEquals(emptyList<AroundPlace>(), venuePinsOf(AroundPlaces.Shown(listOf(osm), venues = false)))
        assertEquals(emptyList<AroundPlace>(), venuePinsOf(AroundPlaces.Unknown))
        assertEquals(emptyList<AroundPlace>(), venuePinsOf(null))
    }

    @Test
    fun `a pin carries the page id, its name and lon-lat in GeoJSON order`() {
        val json = venuePinsGeoJson(listOf(cafe))
        assertTrue(json, json.contains("\"venueId\":\"07abd3049779a2a5\""))
        assertTrue(json, json.contains("\"name\":\"Cafe X\""))
        assertTrue(json, json.contains("[18.2925,49.8356]"))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", venuePinsGeoJson(emptyList()))
    }

    @Test
    fun `pins are reachable - the canvas asks for places, draws them and a tap opens the place card`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val map = File(dir!!, "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")
        val canvas = File(map, "MapCanvas.kt").readText()
        val ground = File(map, "MapGroundContributions.kt").readText()
        val wiring = File(map, "MapCanvasWiring.kt").readText()
        assertTrue("канва питає місця поруч", canvas.contains("viewModel.loadAround("))
        assertTrue("канва малює піни", canvas.contains("venuePinsGeoJson = "))
        assertTrue("шар пінів зареєстровано", ground.contains("id = \"venuePins\"") && ground.contains("VenuePinsOverlay.update("))
        assertTrue("дотик веде на картку місця з venueId", Regex("\"venuePins\" to \\{.{0,600}?venueId = ", RegexOption.DOT_MATCHES_ALL).containsMatchIn(wiring))
    }
}
