package local.phantom.companion.core.data.evac

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.poi.PlaceTier
import local.phantom.companion.core.data.poi.PlaceTiers
import local.phantom.companion.core.data.poi.PoiDao
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.valueOrNull
import local.phantom.companion.core.net.map.OverpassCenter
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Укриття поруч» у Києві (збірка 16): на мапі метро й звичайні значки, щитів
 * немає. Живий Overpass 05.10 знає в межах Києва пʼять сертифікованих сховищ,
 * і саме їх мапа губила:
 *
 *  - чотири з пʼяти — `military=bunker` + `bunker_type=bomb_shelter`. Жодного
 *    з ключів, за якими `toEntity` вибирає рід, на них немає, тож вони йшли в
 *    «other» і малювались ромбом «просто місце»;
 *  - сховище в OSM майже ніколи не має імені, а безіменне `toEntity` викидало
 *    разом з урнами й лавками — і з мапи, і з інструмента `shelter.nearest`.
 *
 * Форми обʼєктів нижче — дослівно з тієї відповіді Overpass (теги, без імені).
 */
class LiveShelterPinTest {

    private class SilentDao : PoiDao {
        override fun getPoisInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<PoiEntity>> =
            flowOf(emptyList())
        override suspend fun insertPois(pois: List<PoiEntity>) = Unit
        override suspend fun clearExpired(expiryTime: Long) = Unit
    }

    private class Answering(private val elements: List<OverpassElement>) : OverpassClient() {
        override suspend fun fetchAroundResult(lat: Double, lon: Double, radiusMeters: Int, selector: String) =
            GeoResult.Ok(elements)
    }

    private val bunker = OverpassElement(
        id = 101, type = "way", center = OverpassCenter(50.4512, 30.5221),
        tags = mapOf("military" to "bunker", "bunker_type" to "bomb_shelter", "building" to "bunker"),
    )
    private val civil = OverpassElement(
        id = 102, type = "node", lat = 50.4498, lon = 30.5240,
        tags = mapOf("building" to "yes", "civil_shelter" to "yes"),
    )
    private val gazebo = OverpassElement(
        id = 103, type = "node", lat = 50.4505, lon = 30.5230,
        tags = mapOf("amenity" to "shelter", "shelter_type" to "gazebo", "name" to "Альтанка"),
    )

    private suspend fun shelters(vararg el: OverpassElement): List<PoiEntity> =
        PoiRepository(SilentDao(), Answering(el.toList()))
            .getNearbyResult(50.45, 30.52, 3000, ShelterTruth.BOMB_SHELTER_SELECTOR)
            .valueOrNull().orEmpty()

    @Test
    fun `a nameless certified shelter reaches the map`() = runTest {
        val found = shelters(bunker, civil)
        assertEquals("сертифіковані сховища без імені зникли: $found", 2, found.size)
        found.forEach { assertEquals("Укриття", it.name) }
    }

    @Test
    fun `a military bunker shelter is drawn as a shelter, not as a plain place`() = runTest {
        val pin = shelters(bunker).single()
        assertEquals("amenity", pin.category)
        assertEquals("shelter", pin.subcategory)
        // Той самий ключ обирає щит на склі (PoiGlyphTable) і ярус виживання.
        assertEquals("shelter", PlaceTiers.pinKey(pin))
        assertEquals(PlaceTier.CRITICAL, PlaceTiers.of(pin))
    }

    @Test
    fun `a canopy still never becomes a shelter`() = runTest {
        val found = shelters(bunker, gazebo)
        assertTrue("альтанку визнано сховищем", found.none { it.name == "Альтанка" && PlaceTiers.of(it) == PlaceTier.CRITICAL })
        assertTrue(found.any { it.id == 101L && it.subcategory == "shelter" })
    }
}
