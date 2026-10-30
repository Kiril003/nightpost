package local.phantom.companion.core.data.evac

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.poi.PoiDao
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сканування укриттів запускає повітряна тривога. Скільки разів воно йде в
 * мережу — це не мікрооптимізація, а секунди між «тривога» і «куди бігти»,
 * тож кількість запитів тут закріплена тестом.
 */
class ShelterRepositoryTest {

    private class FakeDao(private val cached: List<PoiEntity> = emptyList()) : PoiDao {
        val inserted = mutableListOf<PoiEntity>()

        override fun getPoisInBoundingBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
        ): Flow<List<PoiEntity>> = flowOf(cached)

        override suspend fun insertPois(pois: List<PoiEntity>) {
            inserted += pois
        }

        override suspend fun clearExpired(expiryTime: Long) = Unit
    }

    private class FakeOverpass(
        private val answer: (Int) -> GeoResult<List<OverpassElement>>,
    ) : OverpassClient() {
        val queries = mutableListOf<Triple<Int, List<String>, Int>>()

        override suspend fun fetchAroundAnyResult(
            lat: Double,
            lon: Double,
            radiusMeters: Int,
            selectors: List<String>,
            limit: Int,
        ): GeoResult<List<OverpassElement>> {
            queries += Triple(radiusMeters, selectors, limit)
            return answer(radiusMeters)
        }
    }

    private fun node(id: Long, lat: Double, lon: Double, key: String, value: String, name: String? = null) =
        OverpassElement(
            id = id,
            type = "node",
            lat = lat,
            lon = lon,
            tags = buildMap {
                put(key, value)
                name?.let { put("name", it) }
            },
        )

    private val empty: (Int) -> GeoResult<List<OverpassElement>> = { GeoResult.Ok(emptyList()) }

    @Test
    fun `a dead source is SourceDown, never NoAssetsFound`() = runTest {
        val down = SourceUnavailable.Transport("SocketTimeoutException")
        val overpass = FakeOverpass { GeoResult.Unavailable(down) }

        val result = ShelterRepository(FakeDao(), overpass).findNearest(50.45, 30.52)

        // Під тривогою «джерело недоступне» і «укриттів нема» ведуть оператора
        // в протилежні боки — вони не мають права виглядати однаково.
        assertEquals(ShelterScanResult.SourceDown(down), result)
    }

    @Test
    fun `a dead source with a cache hit still answers Found`() = runTest {
        val cached = PoiEntity(
            id = 7,
            lat = 50.451,
            lon = 30.521,
            name = "Укриття",
            category = "amenity",
            subcategory = "shelter",
            tags = mapOf("amenity" to "shelter", "shelter_type" to "bomb_shelter"),
        )
        val overpass = FakeOverpass { GeoResult.Unavailable(SourceUnavailable.EmptyPayload) }

        val result = ShelterRepository(FakeDao(listOf(cached)), overpass).findNearest(50.45, 30.52)

        assertTrue(result is ShelterScanResult.Found)
        assertTrue((result as ShelterScanResult.Found).offline)
    }

    @Test
    fun `a full miss costs one query per radius, not one per tag`() = runTest {
        val overpass = FakeOverpass(empty)
        val result = ShelterRepository(FakeDao(), overpass).findNearest(50.45, 30.52)

        assertEquals(ShelterScanResult.NoAssetsFound, result)
        // Три радіуси × кожен щабель окремо давали десяток послідовних запитів.
        assertEquals(3, overpass.queries.size)
        assertEquals(listOf(3000, 6000, 12000), overpass.queries.map { it.first })
    }

    @Test
    fun `one query carries every tag spec at once`() = runTest {
        val overpass = FakeOverpass(empty)
        ShelterRepository(FakeDao(), overpass).findNearest(50.45, 30.52)

        val (_, selectors, limit) = overpass.queries.first()
        assertEquals(ShelterTruth.TIERS.flatMap { it.selectors }, selectors)
        // `amenity=shelter` в OSM — це навіс; питати про нього під тривогою не можна.
        assertTrue("селектор навісу повернувся в запит", selectors.none { it == "amenity=shelter" })
        // Входи метро цілями маршруту не є — це рішення власника, не наслідок
        // прибирання альтанок.
        assertTrue("метро потрапило у щаблі", selectors.none { it.contains("subway") })
        // Стеля результатів має покривати всі щаблі, інакше обʼєднання запитів
        // урізало б відповідь там, де окремі запити її віддавали.
        assertTrue("стеля $limit замала на ${selectors.size} типів", limit >= 80 * selectors.size)
    }

    @Test
    fun `an official shelter outranks improvised cover found by the same query`() = runTest {
        val overpass = FakeOverpass {
            GeoResult.Ok(
                listOf(
                    node(1, 50.4600, 30.52, "amenity", "school", "Школа поруч"),
                    node(2, 50.4700, 30.52, "shelter_type", "bomb_shelter", "Укриття далі"),
                )
            )
        }
        val result = ShelterRepository(FakeDao(), overpass).findNearest(50.45, 30.52)

        val found = result as ShelterScanResult.Found
        assertEquals(ShelterAssetType.BOMB_SHELTER, found.tokens.first().assetType)
        assertEquals("Укриття далі", found.tokens.first().name)
        assertEquals(1, overpass.queries.size)
        assertTrue(!found.offline)
    }

    @Test
    fun `same-type results come back nearest first`() = runTest {
        val overpass = FakeOverpass {
            GeoResult.Ok(
                listOf(
                    node(1, 50.4800, 30.52, "shelter_type", "bomb_shelter", "Далеке"),
                    node(2, 50.4510, 30.52, "shelter_type", "bomb_shelter", "Близьке"),
                )
            )
        }
        val found = ShelterRepository(FakeDao(), overpass).findNearest(50.45, 30.52) as ShelterScanResult.Found

        assertEquals(listOf("Близьке", "Далеке"), found.tokens.map { it.name })
        assertTrue(found.tokens[0].distanceMeters < found.tokens[1].distanceMeters)
    }

    @Test
    fun `a cached shelter still outranks a freshly found school`() = runTest {
        val cachedShelter = PoiEntity(
            id = 99, lat = 50.4900, lon = 30.52, name = "Кешоване укриття",
            category = "amenity", subcategory = "shelter",
            tags = mapOf("amenity" to "shelter", "shelter_type" to "bomb_shelter"),
        )
        val overpass = FakeOverpass {
            GeoResult.Ok(listOf(node(1, 50.4501, 30.52, "amenity", "school", "Школа поруч")))
        }
        val found = ShelterRepository(FakeDao(listOf(cachedShelter)), overpass)
            .findNearest(50.45, 30.52) as ShelterScanResult.Found

        assertEquals(ShelterAssetType.BOMB_SHELTER, found.tokens.first().assetType)
        assertTrue("кеш мусить бути позначений як офлайн", found.offline)
    }

    @Test
    fun `an unavailable source falls back to cache instead of claiming emptiness`() = runTest {
        val cachedShelter = PoiEntity(
            id = 7, lat = 50.4520, lon = 30.52, name = "Кешоване укриття",
            category = "amenity", subcategory = "shelter",
            tags = mapOf("amenity" to "shelter", "shelter_type" to "bomb_shelter"),
        )
        val overpass = FakeOverpass { GeoResult.Unavailable(SourceUnavailable.RateLimited(2_000L)) }
        val found = ShelterRepository(FakeDao(listOf(cachedShelter)), overpass)
            .findNearest(50.45, 30.52) as ShelterScanResult.Found

        assertTrue(found.offline)
        assertEquals("Кешоване укриття", found.tokens.first().name)
        // Недоступність не робить зайвих походів у мережу понад один на радіус.
        assertEquals(1, overpass.queries.size)
    }

    @Test
    fun `every asset type from the paid-for query lands in the cache`() = runTest {
        val dao = FakeDao()
        val overpass = FakeOverpass {
            GeoResult.Ok(
                listOf(
                    node(1, 50.4510, 30.52, "shelter_type", "bomb_shelter", "Укриття"),
                    node(2, 50.4520, 30.52, "amenity", "school", "Школа"),
                    node(3, 50.4530, 30.52, "building", "administrative", "Адмінбудівля"),
                )
            )
        }
        ShelterRepository(dao, overpass).findNearest(50.45, 30.52)

        assertEquals(
            setOf("shelter", "school", "administrative"),
            dao.inserted.mapNotNull { it.subcategory }.toSet(),
        )
    }

    @Test
    fun `a bare amenity=shelter canopy is never offered as a shelter`() = runTest {
        // «Альтанки для пікніка» — дослівна назва з випеченого пакета. Під
        // тривогою вона не має права виграти в школи за сусідньою стіною.
        val overpass = FakeOverpass {
            GeoResult.Ok(
                listOf(
                    node(1, 50.4501, 30.52, "amenity", "shelter", "Альтанки для пікніка"),
                    node(2, 50.4800, 30.52, "amenity", "school", "Школа далі"),
                )
            )
        }
        val found = ShelterRepository(FakeDao(), overpass).findNearest(50.45, 30.52) as ShelterScanResult.Found

        assertEquals(ShelterAssetType.SCHOOL, found.tokens.first().assetType)
        assertTrue("навіс потрапив у відповідь", found.tokens.none { it.name.contains("Альтанк") })
    }

    @Test
    fun `a cached canopy does not outrank a fresh school`() = runTest {
        val canopy = PoiEntity(
            id = 5, lat = 50.4505, lon = 30.52, name = "місце для куріння",
            category = "amenity", subcategory = "shelter", tags = mapOf("amenity" to "shelter"),
        )
        val overpass = FakeOverpass {
            GeoResult.Ok(listOf(node(1, 50.4900, 30.52, "amenity", "school", "Школа")))
        }
        val found = ShelterRepository(FakeDao(listOf(canopy)), overpass)
            .findNearest(50.45, 30.52) as ShelterScanResult.Found

        assertEquals(ShelterAssetType.SCHOOL, found.tokens.first().assetType)
    }
}
