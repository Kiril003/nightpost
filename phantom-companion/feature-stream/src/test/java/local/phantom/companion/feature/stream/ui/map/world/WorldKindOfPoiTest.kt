package local.phantom.companion.feature.stream.ui.map.world

import local.phantom.companion.core.data.poi.OfflinePoiPack
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.feature.stream.ui.map.eventMarksGeoJson
import local.phantom.companion.feature.stream.ui.map.eventMarksScene
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkKind
import local.phantom.companion.feature.stream.ui.map.pinnedPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorldKindOfPoiTest {

    private var nextId = 1L

    // Порядок категорій — той, яким PoiRepository розбирає відповідь Overpass;
    // `healthcare` там не категорія, тож такий вузол приходить як `other`.
    private fun overpass(vararg tags: Pair<String, String>): PoiEntity {
        val t = tags.toMap()
        val cat = when {
            "amenity" in t -> "amenity"
            "shop" in t -> "shop"
            "railway" in t -> "railway"
            t["highway"] == "bus_stop" -> "highway"
            "public_transport" in t -> "public_transport"
            "barrier" in t -> "barrier"
            else -> "other"
        }
        return PoiEntity(nextId++, 49.8309, 18.1633, "x", cat, t[cat], t)
    }

    private fun packRow(cat: String, sub: String) = PoiEntity(nextId++, 49.8309, 18.1633, "x", cat, sub, emptyMap())

    @Test
    fun `живі теги Overpass стають тілами світу`() {
        assertEquals(WorldKind.POST, WorldKind.ofPoi(overpass("amenity" to "parcel_locker", "brand" to "Z-BOX")))
        assertEquals(WorldKind.POST, WorldKind.ofPoi(overpass("amenity" to "vending_machine", "vending" to "parcel_pickup;parcel_mail_in")))
        assertEquals(WorldKind.POST, WorldKind.ofPoi(overpass("amenity" to "post_office")))
        assertEquals(WorldKind.STOP, WorldKind.ofPoi(overpass("highway" to "bus_stop", "public_transport" to "platform")))
        assertEquals(WorldKind.STOP, WorldKind.ofPoi(overpass("public_transport" to "platform")))
        assertEquals(WorldKind.STOP, WorldKind.ofPoi(overpass("railway" to "tram_stop", "public_transport" to "stop_position")))
        assertEquals(WorldKind.ATM, WorldKind.ofPoi(overpass("amenity" to "bank", "atm" to "yes", "name" to "Fio banka")))
        assertEquals(WorldKind.ATM, WorldKind.ofPoi(overpass("amenity" to "atm")))
        assertEquals(WorldKind.HOSPITAL, WorldKind.ofPoi(overpass("amenity" to "hospital")))
        assertEquals(WorldKind.HOSPITAL, WorldKind.ofPoi(overpass("amenity" to "clinic")))
        assertEquals(WorldKind.HOSPITAL, WorldKind.ofPoi(overpass("healthcare" to "clinic")))
        assertEquals(WorldKind.PHARMACY, WorldKind.ofPoi(overpass("amenity" to "pharmacy")))
        assertEquals(WorldKind.PHARMACY, WorldKind.ofPoi(overpass("healthcare" to "pharmacy")))
        assertEquals(WorldKind.FUEL, WorldKind.ofPoi(overpass("amenity" to "fuel")))
        assertEquals(WorldKind.SHELTER, WorldKind.ofPoi(overpass("amenity" to "shelter", "civil_shelter" to "yes")))
        assertEquals(WorldKind.LIFT_GATE, WorldKind.ofPoi(overpass("barrier" to "lift_gate")))
    }

    @Test
    fun `чого немає серед шістнадцяти — лишається тихим піном`() {
        assertEquals(null, WorldKind.ofPoi(overpass("amenity" to "bank")))
        assertEquals(null, WorldKind.ofPoi(overpass("amenity" to "bank", "atm" to "no")))
        assertEquals("навіс не укриття", null, WorldKind.ofPoi(overpass("amenity" to "shelter")))
        assertEquals(null, WorldKind.ofPoi(overpass("amenity" to "cafe")))
        assertEquals(null, WorldKind.ofPoi(overpass("amenity" to "doctors")))
        assertEquals(null, WorldKind.ofPoi(overpass("amenity" to "vending_machine", "vending" to "drinks")))
        assertEquals(null, WorldKind.ofPoi(overpass("public_transport" to "station")))
    }

    @Test
    fun `рядок пакета без тегів впізнається за парою категорія-підкатегорія`() {
        assertEquals(WorldKind.ATM, WorldKind.ofPoi(packRow("amenity", "atm")))
        assertEquals(WorldKind.POST, WorldKind.ofPoi(packRow("amenity", "parcel_locker")))
        assertEquals(WorldKind.STOP, WorldKind.ofPoi(packRow("public_transport", "platform")))
        assertEquals(WorldKind.HOSPITAL, WorldKind.ofPoi(packRow("healthcare", "hospital")))
    }

    private val names = MarkKind.entries.associateWith { it.id }

    private fun sceneAround(p: PoiEntity, zoom: Double) = eventMarksScene(
        layers = emptyList(),
        events = emptyList(),
        zoom = zoom,
        corridor = emptyList(),
        focus = p.lat to p.lon,
        names = names,
        places = listOf(p),
    )

    @Test
    fun `справжні місця з пакета Києва йдуть у шар тіл і не йдуть у шар пінів`() {
        val pack = OfflinePoiPack.parse(File("../core-data/src/main/assets/country/ua/poi.json").readText())
        val byId = pack.chunked().flatten().associateBy { it.id }
        val expected = mapOf(
            11891918281L to WorldKind.POST,
            4550935764L to WorldKind.POST,
            4321076594L to WorldKind.STOP,
            26360786L to WorldKind.STOP,
            3394767246L to WorldKind.ATM,
            6846981166L to WorldKind.ATM,
            7921460428L to WorldKind.HOSPITAL,
            7812902283L to WorldKind.PHARMACY,
            2295894889L to WorldKind.FUEL,
        )
        val real = expected.keys.map { byId.getValue(it) }
        val cafe = byId.getValue(4086602642L)

        assertEquals(listOf(cafe), pinnedPlaces(real + cafe))
        for (p in real) {
            val kind = expected.getValue(p.id)
            assertEquals("${p.name}", kind, WorldKind.ofPoi(p))
            val marks = sceneAround(p, zoom = 17.0)
            assertEquals("${p.name} не став тілом", listOf("poi_${p.id}"), marks.map { it.id })
            assertTrue(eventMarksGeoJson(marks).contains("\"fig\":\"${kind.id}\""))
        }
        assertTrue(sceneAround(cafe, 17.0).isEmpty())
    }

    @Test
    fun `одне місце — один значок на будь-якому масштабі`() {
        val locker = overpass("amenity" to "parcel_locker")
        for (zoom in listOf(13.0, 14.5, 15.0, 15.9, 16.0, 17.0)) {
            val pinned = locker in pinnedPlaces(listOf(locker))
            val bodied = sceneAround(locker, zoom).isNotEmpty()
            assertFalse("z$zoom: пін і тіло разом", pinned && bodied)
            assertFalse("z$zoom: круглий пін замість тіла", pinned)
        }
        assertTrue(sceneAround(locker, 16.0).isNotEmpty())
    }
}
