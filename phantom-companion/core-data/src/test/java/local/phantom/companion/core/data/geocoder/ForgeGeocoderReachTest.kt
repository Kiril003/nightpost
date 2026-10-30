package local.phantom.companion.core.data.geocoder

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.poi.PoiDao
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.data.poi.PoiVocabulary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * СТОРОЖ ДОСЯЖНОСТІ. Не «читач уміє розібрати пакет», а «те, що питає екран
 * мапи, доходить до пакета й повертається рядком видачі».
 *
 * [PoiRepository.searchOfflineVerdict] — та сама функція, яку кличе
 * `MapPoiSearch.offlineSearch`; газетира тут немає навмисно, тож єдиний
 * можливий автор відповіді — пакет кузні.
 */
class ForgeGeocoderReachTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var geocoder: ForgeGeocoder

    @Before
    fun open() {
        val file = ForgePackFixture.unpack(temp.newFolder("forge"))
        geocoder = ForgeGeocoder.over(JdbcPackDb(file.absolutePath)) ?: error("пакет кузні не відкрився")
    }

    @After
    fun close() {
        geocoder.close()
    }

    @Test
    fun `a pharmacy typed on the map comes back from the forge pack`() = runTest {
        val verdict = repo().searchOfflineVerdict("аптека", 47.0105, 28.8638, limit = 5)
        assertTrue(verdict is PoiRepository.OfflineSearch.Hits)
        val first = verdict.hits.first()
        assertEquals("Apoteka", first.name)
        assertEquals("pharmacy", first.subcategory)
        assertEquals(384, first.distanceMeters.toInt())
        assertEquals("аптека", PoiVocabulary.label(first.subcategory, first.tags))
    }

    @Test
    fun `an address typed on the map comes back with its street and number`() = runTest {
        val verdict = repo().searchOfflineVerdict("Strada Haltei 6/1", 47.0105, 28.8638, limit = 5)
        val first = verdict.hits.first()
        assertEquals("Strada Haltei 6/1", first.name)
        assertEquals(65, first.distanceMeters.toInt())
    }

    @Test
    fun `a shelter keeps the tags the row is judged by`() = runTest {
        val verdict = repo().searchOfflineVerdict("укриття", 47.0105, 28.8638, limit = 5)
        val first = verdict.hits.first()
        assertEquals("Bomb shelter", first.name)
        assertEquals("bomb_shelter", first.tags["shelter_type"])
        assertEquals("бомбосховище", PoiVocabulary.label(first.subcategory, first.tags))
    }

    @Test
    fun `without the pack the surface still says where it looked`() = runTest {
        val bare = PoiRepository(poiDao = NoPoiDao())
        assertEquals(
            PoiRepository.OfflineSearch.NowhereToLook,
            bare.searchOfflineVerdict("аптека", 47.0105, 28.8638),
        )
    }

    private fun repo() = PoiRepository(poiDao = NoPoiDao(), forgeGeocoder = { geocoder })

    private class NoPoiDao : PoiDao {
        override fun getPoisInBoundingBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
        ): Flow<List<PoiEntity>> = flowOf(emptyList())

        override suspend fun insertPois(pois: List<PoiEntity>) = Unit
        override suspend fun clearExpired(expiryTime: Long) = Unit
    }
}
