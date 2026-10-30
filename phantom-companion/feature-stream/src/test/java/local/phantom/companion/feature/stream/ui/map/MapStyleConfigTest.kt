package local.phantom.companion.feature.stream.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.feature.stream.ui.widget.MapPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAP-5 + MAP-8 — regression guard.
 *
 * Every map surface inflates whatever URL [MapStyleConfig.resolveStyleUrl]
 * returns (the in-chat map card that first did so left on 04.10). The
 * widget used to be pinned to `demotiles.maplibre.org` (empty grayscale
 * coastlines), and the visible regression was "AI sent a map, the
 * widget rendered nothing useful". These tests pin the contract that
 *
 *   1. the default config (used when no [LocalMapStyleConfig] provider
 *      is installed, e.g. in previews/tests) resolves to a real OSM
 *      style, never to demotiles, and
 *   2. theme switches produce a different URL where the provider ships
 *      one, so dark mode actually looks different.
 *
 * MAP-8 — added polylines (routes) support.
 *
 * If either fact regresses, this test fails before a build is shipped.
 */
class MapStyleConfigTest {

    @Test
    fun `default config is our own style and never demotiles`() {
        // ATLAS-1: замовчування переїхало з чужого Liberty на власний
        // Обсидіан. Вимога та сама, що й була: перший запуск без жодного ключа
        // мусить дати СПРАВЖНЮ мапу, а не порожню кулю demotiles.
        val cfg = MapStyleConfig.Default
        val light = cfg.resolveStyleUrl(isDark = false)
        val dark = cfg.resolveStyleUrl(isDark = true)
        assertEquals("замовчування більше не наш стиль", OBSIDIAN_DAY_STYLE, light)
        assertEquals("замовчування більше не наш стиль", OBSIDIAN_NIGHT_STYLE, dark)
        assertFalse(
            "default style URL must never be demotiles (the empty-globe regression)",
            light.contains("demotiles"),
        )
        assertFalse(
            "default style URL must never be demotiles (the empty-globe regression)",
            dark.contains("demotiles"),
        )
    }

    @Test
    fun `day and night are two files but one offline pack`() {
        // Історія цієї перевірки — про те, ЧОМУ день і ніч мусили колись бути
        // одним URL. Дві виміряні причини: у чужому `styles/dark` було 47
        // шарів проти 111 у `liberty` (ніч отримувала не темну мапу, а
        // неповну), і пакет офлайну привʼязувався до URL стилю, тож знятий
        // уночі вдень не показувався.
        //
        // Обидві причини зникли, і саме це тут закріплено. Перша: обидва
        // варіанти Обсидіана компілюються з ОДНОГО джерела, тож розійтися
        // складом шарів не можуть (це стереже :tools:obsidian). Друга: ключ
        // офлайн-пакета більше не полярність, а джерело тайлів.
        val cfg = MapStyleConfig.Default
        assertNotEquals(
            "полярність знову коштує повного перезавантаження чужого стилю",
            cfg.resolveStyleUrl(isDark = false),
            cfg.resolveStyleUrl(isDark = true),
        )
        assertTrue(
            "пакет офлайну знову привʼязаний до полярності — те саме місце довелось би качати двічі",
            cfg.resolveOfflineStyleUrl().contains("openfreemap.org"),
        )
    }

    @Test
    fun `MapTiler with a key shows up verbatim in the resolved url`() {
        val cfg = MapStyleConfig(
            providerId = TileProviderId.MAPTILER,
            apiKey = "secret_key_123",
            customUrl = null,
        )
        val light = cfg.resolveStyleUrl(isDark = false)
        val dark = cfg.resolveStyleUrl(isDark = true)
        assertTrue("light must hit maptiler", light.contains("api.maptiler.com"))
        assertTrue("light must carry the key", light.contains("key=secret_key_123"))
        assertTrue("dark variant", dark.contains("dark"))
    }

    // ─── MAP-6 — buildMarkersGeoJson contract ────────────────────────

    @Test
    fun `markers geojson is well-formed and carries label + coordinates`() {
        val markers = listOf(
            MapPayload.MarkerData(lat = 50.45, lon = 30.52, label = "Київ"),
            MapPayload.MarkerData(lat = 49.84, lon = 24.03, label = null),
        )
        val raw = buildMarkersGeoJson(markers)
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals("FeatureCollection", root["type"]!!.jsonPrimitive.content)
        val features = root["features"]!!.jsonArray
        assertEquals(2, features.size)

        val first = features[0].jsonObject
        assertEquals("Feature", first["type"]!!.jsonPrimitive.content)
        val coords = first["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        // GeoJSON spec: [lon, lat] not [lat, lon] — pinning the order
        // because mixing them up draws the marker on the wrong continent.
        assertEquals(30.52, coords[0].jsonPrimitive.content.toDouble(), 1e-6)
        assertEquals(50.45, coords[1].jsonPrimitive.content.toDouble(), 1e-6)
        assertEquals("Київ", first["properties"]!!.jsonObject["label"]!!.jsonPrimitive.content)

        val second = features[1].jsonObject
        // Null label collapses to "" so the SymbolLayer's
        // `Expression.get("label")` never blows up on a missing key.
        assertEquals("", second["properties"]!!.jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun `markers geojson escapes quotes and backslashes in labels`() {
        val markers = listOf(
            MapPayload.MarkerData(
                lat = 0.0,
                lon = 0.0,
                label = "He said \"hi\" and \\backslash\\",
            ),
        )
        val raw = buildMarkersGeoJson(markers)
        // The whole string must parse — if escape was wrong, this
        // throws SerializationException and the test fails red.
        val root = Json.parseToJsonElement(raw).jsonObject
        val label = root["features"]!!.jsonArray[0].jsonObject["properties"]!!
            .jsonObject["label"]!!.jsonPrimitive.content
        assertEquals("He said \"hi\" and \\backslash\\", label)
    }

    @Test
    fun `markers geojson handles newlines and tabs without breaking parse`() {
        val markers = listOf(
            MapPayload.MarkerData(
                lat = 0.0,
                lon = 0.0,
                label = "line1\nline2\tcol2",
            ),
        )
        val raw = buildMarkersGeoJson(markers)
        val root = Json.parseToJsonElement(raw).jsonObject
        val label = root["features"]!!.jsonArray[0].jsonObject["properties"]!!
            .jsonObject["label"]!!.jsonPrimitive.content
        assertEquals("line1\nline2\tcol2", label)
    }

    @Test
    fun `markers geojson handles empty marker list`() {
        val raw = buildMarkersGeoJson(emptyList())
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals(0, root["features"]!!.jsonArray.size)
    }

    // ─── MAP-8 — buildPolylinesGeoJson contract ──────────────────────

    @Test
    fun `polylines geojson is well-formed and carries coordinates`() {
        val polylines = listOf(
            MapPayload.PolylineData(
                points = listOf(
                    MapPayload.PointData(lat = 50.45, lon = 30.52),
                    MapPayload.PointData(lat = 49.84, lon = 24.03)
                )
            )
        )
        val raw = buildPolylinesGeoJson(polylines, isDark = false)
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals("FeatureCollection", root["type"]!!.jsonPrimitive.content)
        val features = root["features"]!!.jsonArray
        assertEquals(1, features.size)

        val first = features[0].jsonObject
        assertEquals("Feature", first["type"]!!.jsonPrimitive.content)
        assertEquals("LineString", first["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val coords = first["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(2, coords.size)

        val pt1 = coords[0].jsonArray
        assertEquals(30.52, pt1[0].jsonPrimitive.content.toDouble(), 1e-6)
        assertEquals(50.45, pt1[1].jsonPrimitive.content.toDouble(), 1e-6)
    }

    @Test
    fun `polylines geojson handles empty list`() {
        val raw = buildPolylinesGeoJson(emptyList(), isDark = false)
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals(0, root["features"]!!.jsonArray.size)
    }

    // ─── QA-fix 2026-07-17 — the glyph-font firewall ─────────────────
    //
    // A SymbolLayer with a textField but NO textFont inherits MapLibre's
    // default stack («Open Sans Regular,Arial Unicode MS Regular»),
    // which OpenFreeMap's glyph server does not host: every range 404s
    // and the label text silently never renders — no crash, no error
    // surface, just invisible names (Cyrillic included). Found live on
    // the 2026-07-17 QA drive. Every textField site must therefore ship
    // a textFont ([MAP_TEXT_FONT] = «Noto Sans Regular», hosted by all
    // our providers).

    @Test
    fun `every textField site ships a textFont — the default stack 404s on OpenFreeMap`() {
        val uiRoot = java.io.File("src/main/java/local/phantom/companion/feature/stream/ui")
        assertTrue("ui source root must exist for the scan", uiRoot.isDirectory)
        val offenders = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val text = file.readText()
                val fields = Regex("""PropertyFactory\.textField\(""").findAll(text).count()
                val fonts = Regex("""PropertyFactory\.textFont\(""").findAll(text).count()
                if (fields > fonts) {
                    "${file.name}: $fields textField vs $fonts textFont"
                } else {
                    null
                }
            }
            .toList()
        assertEquals(
            "SymbolLayer(s) with textField but no textFont — labels will " +
                "silently not render on OpenFreeMap (glyph 404). Add " +
                "PropertyFactory.textFont(MAP_TEXT_FONT): $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    // ─── back to MapStyleConfig ───────────────────────────────────────

    @Test
    fun `Custom URL is used for both modes`() {
        val cfg = MapStyleConfig(
            providerId = TileProviderId.CUSTOM,
            apiKey = null,
            customUrl = "https://example.com/style.json",
        )
        assertEquals("https://example.com/style.json", cfg.resolveStyleUrl(isDark = false))
        assertEquals("https://example.com/style.json", cfg.resolveStyleUrl(isDark = true))
    }
}
