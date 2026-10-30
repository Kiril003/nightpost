package local.phantom.companion.feature.stream.geo

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.net.map.OverpassCenter
import local.phantom.companion.core.net.map.OverpassElement
import local.phantom.companion.feature.stream.ui.map.MapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pure mapping half of the sphere-sweep build-now layers (towers,
 * drainage/culvert crossings, fords) — no network, no Compose. The fetch
 * half (MapWorldLayers.summonGroundLayer) is the same Overpass client every
 * other map feed already uses; what's specific here, and worth locking down,
 * is the tag reading: `waterway=culvert` is the near-empty tag (15
 * nationally per the sphere sweep) and `tunnel=culvert` is the populated one
 * (62,932) — getting that backwards would silently mislabel every real
 * culvert as an open ditch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapGroundLayersTest {

    @Before
    fun bindText() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    private fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String> = emptyMap()) =
        OverpassElement(id = id, type = "node", lat = lat, lon = lon, tags = tags)

    private fun wayCenter(id: Long, lat: Double, lon: Double, tags: Map<String, String>) =
        OverpassElement(id = id, type = "way", center = OverpassCenter(lat, lon), tags = tags)

    @Test
    fun czProfile_noShelterChip() {
        val ids = MapGroundLayers.quickAccessFor(showsUkraineWartime = false)
        assertTrue(MapGroundLayers.SHELTERS !in ids)
        assertEquals(MapGroundLayers.quickAccessIds.size - 1, ids.size)
    }

    @Test
    fun uaProfile_keepsShelterChip() {
        val ids = MapGroundLayers.quickAccessFor(showsUkraineWartime = true)
        assertTrue(MapGroundLayers.SHELTERS in ids)
        assertEquals(MapGroundLayers.quickAccessIds, ids)
    }

    @Test
    fun noCyrillicInGroundLayers() {
        val candidates = listOf(
            java.io.File("src/main/java/local/phantom/companion/feature/stream/geo/MapGroundLayers.kt"),
            java.io.File("phantom-companion/feature-stream/src/main/java/local/phantom/companion/feature/stream/geo/MapGroundLayers.kt"),
        )
        val file = candidates.first { it.exists() }
        val stripped = file.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//.*"), "")
        assertTrue(!Regex("[\\u0400-\\u04FF]").containsMatchIn(stripped))
    }

    @Test
    fun `power tower node becomes an unattributed landmark marker`() {
        val markers = MapGroundLayers.markersFrom(
            MapGroundLayers.POWER_TOWERS,
            listOf(node(1, 50.45, 30.52, mapOf("power" to "tower", "operator" to "Ukrenergo"))),
        )
        assertEquals(1, markers.size)
        assertEquals("tower", markers.single().label)
        assertTrue(markers.single().detail?.contains("Ukrenergo") != true)
    }

    @Test
    fun `tunnel equals culvert is read as the passable pipe, not waterway equals culvert`() {
        val markers = MapGroundLayers.markersFrom(
            MapGroundLayers.DRAINAGE_CROSSINGS,
            listOf(
                wayCenter(1, 50.1, 30.1, mapOf("waterway" to "stream", "tunnel" to "culvert")),
                wayCenter(2, 50.2, 30.2, mapOf("waterway" to "ditch")),
                wayCenter(3, 50.3, 30.3, mapOf("waterway" to "drain")),
            ),
        )
        assertEquals(3, markers.size)
        val (culvert, ditch, drain) = markers
        assertEquals("culvert", culvert.label)
        assertEquals("ditch", ditch.label)
        assertEquals("ditch", drain.label)
        assertTrue(culvert.detail!!.contains("unverified"))
        assertTrue(ditch.detail!!.contains("obstacle"))
    }

    @Test
    fun `ford node carries an explicit not-currently-known caveat`() {
        val markers = MapGroundLayers.markersFrom(
            MapGroundLayers.FORDS,
            listOf(node(1, 49.0, 25.0, mapOf("ford" to "yes"))),
        )
        assertEquals(1, markers.size)
        assertEquals("ford", markers.single().label)
        assertTrue(markers.single().detail!!.contains("unknown"))
    }

    @Test
    fun `an element with no coordinate at all is dropped, not crashed on`() {
        val markers = MapGroundLayers.markersFrom(
            MapGroundLayers.POWER_TOWERS,
            listOf(OverpassElement(id = 1, type = "relation", tags = mapOf("power" to "tower"))),
        )
        assertTrue(markers.isEmpty())
    }

    @Test
    fun `unknown id yields no markers instead of guessing a mapping`() {
        assertTrue(MapGroundLayers.markersFrom("not-a-real-layer", listOf(node(1, 0.0, 0.0))).isEmpty())
    }

    @Test
    fun `fuel station node becomes a branded fuel marker with fuel types`() {
        val markers = MapGroundLayers.markersFrom(
            MapGroundLayers.FUEL_STATIONS,
            listOf(node(1, 50.45, 30.52, mapOf("amenity" to "fuel", "brand" to "WOG", "fuel:diesel" to "yes", "fuel:octane_95" to "yes"))),
        )
        assertEquals(1, markers.size)
        assertEquals("WOG", markers.single().label)
        assertTrue(markers.single().detail!!.contains("Fuel: WOG"))
        assertTrue(markers.single().detail!!.contains("95/D"))
    }

    @Test
    fun `ev charging station becomes an EV marker with power`() {
        val markers = MapGroundLayers.markersFrom(
            MapGroundLayers.EV_CHARGERS,
            listOf(node(1, 50.45, 30.52, mapOf("amenity" to "charging_station", "brand" to "AutoEnterprise", "charging_station:output" to "50 kW"))),
        )
        assertEquals(1, markers.size)
        assertEquals("AutoEnterprise", markers.single().label)
        assertTrue(markers.single().detail!!.contains("50 kW"))
    }

    @Test
    fun `shelter node becomes a protective shelter marker`() {
        val markers = MapGroundLayers.markersFrom(
            MapGroundLayers.SHELTERS,
            listOf(
                node(
                    1, 50.45, 30.52,
                    mapOf("amenity" to "shelter", "shelter_type" to "bomb_shelter", "name" to "Shelter 12"),
                ),
            ),
        )
        assertEquals(1, markers.size)
        assertEquals("Shelter 12", markers.single().label)
        assertTrue(markers.single().detail!!.contains("Shelter: Shelter 12"))
    }

    @Test
    fun `a bare amenity=shelter canopy yields no shelter marker`() {
        val markers = MapGroundLayers.markersFrom(
            MapGroundLayers.SHELTERS,
            listOf(node(1, 50.45, 30.52, mapOf("amenity" to "shelter", "name" to "Picnic gazebo"))),
        )
        assertEquals(0, markers.size)
    }

    @Test
    fun `water source node becomes a drinking water marker`() {
        val markers = MapGroundLayers.markersFrom(
            MapGroundLayers.WATER_SOURCES,
            listOf(node(1, 50.45, 30.52, mapOf("amenity" to "drinking_water", "name" to "Pump room"))),
        )
        assertEquals(1, markers.size)
        assertEquals("Pump room", markers.single().label)
        assertTrue(markers.single().detail!!.contains("Drinking water: Pump room"))
    }

    @Test
    fun `every quick-access id has its own honest empty notice`() {
        val notices = MapGroundLayers.quickAccessIds.map { MapGroundLayers.emptyNotice(it) }
        val unavail = MapGroundLayers.quickAccessIds.map { MapGroundLayers.unavailableNotice(it) }
        assertEquals(notices.size, notices.distinct().size)
        assertTrue(notices.all { it != 0 })
        assertEquals(unavail.size, unavail.distinct().size)
        assertTrue(unavail.all { it != 0 })
    }
}
