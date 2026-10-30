package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.poi.PoiDao
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiFtsEntity
import local.phantom.companion.core.data.poi.PoiGazetteerDao
import local.phantom.companion.core.data.poi.PoiGazetteerEntity
import local.phantom.companion.core.data.poi.PoiRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «На мапі жодних позначень, обʼєктів тощо немає» — скарга власника на апараті.
 *
 * Механізм навколишніх точок зібрано давно: зупинка камери → [MapPoiSearch
 * .updateCameraBounds] → дебаунс → `PoiRepository.getPois` → `_pois`. Але
 * ЖОДЕН тест не проходив цим шляхом цілком, тож «мапа сама показує, що поруч»
 * було обіцянкою, а не перевіркою — і будь-який розрив у ланцюзі мовчав би.
 *
 * Тут доводиться саме ПОВЕДІНКА, а не наявність коду: після однієї зупинки
 * камери й БЕЗ жодного натискання категорії список точок не порожній.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapAmbientPlacesTest {

    /**
     * Дати віртуальному часу добігти ДО КІНЦЯ, включно з фоновим збирачем.
     *
     * `advanceUntilIdle()` тут не годиться, і це виміряно (зонд 16.09):
     * дебаунс, що живе в `backgroundScope`, вона не рушить з місця — черга
     * вважається порожньою, щойно в ній лишились самі фонові задачі, і тест
     * червонів на справному коді. Рахунок: дебаунс 700 мс + пауза
     * `fetchPois` 350 мс, тож двох секунд стає з запасом.
     */
    private fun kotlinx.coroutines.test.TestScope.settle() {
        advanceTimeBy(2_000L)
        runCurrent()
    }

    // Київ — той самий bbox, що несе вбудований `country/ua/poi.json`.
    private val south = 50.44
    private val west = 30.51
    private val north = 50.46
    private val east = 30.53

    private fun bounds() = OfflineBounds(
        northLat = north,
        eastLon = east,
        southLat = south,
        westLon = west,
    )

    private fun place(id: Long, name: String, sub: String, lat: Double = 50.45, lon: Double = 30.52) =
        PoiGazetteerEntity(
            id = id,
            lat = lat,
            lon = lon,
            name = name,
            category = "amenity",
            subcategory = sub,
            seenAt = id,
        )

    private class FakePoiDao : PoiDao {
        val rows = mutableListOf<PoiEntity>()
        override fun getPoisInBoundingBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
        ): Flow<List<PoiEntity>> =
            flowOf(rows.filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon })

        override suspend fun insertPois(pois: List<PoiEntity>) {
            rows.removeAll { row -> pois.any { it.id == row.id } }
            rows += pois
        }

        override suspend fun clearExpired(expiryTime: Long) = Unit
    }

    /** Корпус, засіяний країнним пакетом: єдине, що телефон має офлайн. */
    private class FakeGazetteer(val rows: MutableList<PoiGazetteerEntity>) : PoiGazetteerDao {
        override suspend fun insertPlaces(places: List<PoiGazetteerEntity>) {
            rows.removeAll { row -> places.any { it.id == row.id } }
            rows += places
        }

        override suspend fun insertTerms(terms: List<PoiFtsEntity>) = Unit

        override suspend fun searchInBox(
            query: String,
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> = emptyList()

        override suspend fun searchAnywhere(
            query: String,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> = emptyList()

        override suspend fun byCategoryInBox(
            subcategories: List<String>,
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> = emptyList()

        /**
         * Дзеркало справжнього `ORDER BY`, а не заглушка: саме цим запитом диск
         * бере вікно кадру, відколи воно впорядковане від ЦЕНТРА, а не від
         * півночі. Порожній список тут мовчки лишав би мапу голою — і рівно це
         * тут і сталось, доки правку не було прогнано.
         */
        override suspend fun nearestPlacesInBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> =
            nearestInBox(minLat, maxLat, minLon, maxLon, atLat, atLon, lonScaleSq, Int.MAX_VALUE)
                .filter { it.category != "street" && it.category != "place" }
                .take(limit)

        override suspend fun nearestInBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> = rows
            .filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon }
            .sortedBy {
                val dLat = it.lat - atLat
                val dLon = it.lon - atLon
                dLat * dLat + dLon * dLon * lonScaleSq
            }
            .take(limit)

        override suspend fun inBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            limit: Int,
        ): List<PoiGazetteerEntity> = rows
            .filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon }
            .sortedByDescending { it.seenAt }
            .take(limit)

        override suspend fun byIds(ids: List<Long>): List<PoiGazetteerEntity> =
            rows.filter { it.id in ids }

        override suspend fun size(): Int = rows.size
    }

    /**
     * Репозиторій у режимі, у якому живе власник: жодної мережі
     * (`overpassClient = null`, `liveFetch` мовчить), сам лише диск.
     */
    private fun offlineRepository(corpus: List<PoiGazetteerEntity>) = PoiRepository(
        poiDao = FakePoiDao(),
        overpassClient = null,
        gazetteer = FakeGazetteer(corpus.toMutableList()),
        liveFetch = { _, _, _, _ -> emptyList() },
    )

    private fun corpus(): List<PoiGazetteerEntity> = listOf(
        place(1, "Аптека Подорожник", "pharmacy"),
        place(2, "АТБ", "supermarket", lat = 50.452, lon = 30.522),
        place(3, "WOG", "fuel", lat = 50.448, lon = 30.518),
        place(4, "Укрпошта", "post_office", lat = 50.451, lon = 30.525),
        place(5, "Сільпо", "supermarket", lat = 50.449, lon = 30.515),
    )

    /**
     * ГОЛОВНЕ. Одна зупинка камери, жодного натискання — і місця на мапі.
     *
     * Це той самий шлях, яким іде апарат: `updateCameraBounds` з реального
     * `OnCameraIdleListener`, дебаунс, запит до диска, `_pois`.
     */
    @Test
    fun `камера зупинилась — місця зʼявились без жодного натискання`() = runTest {
        val notices = mutableListOf<String>()
        val search = MapPoiSearch(
            scope = backgroundScope,
            poiRepository = offlineRepository(corpus()),
            vaultStore = null,
            savedPlaceStore = null,
            postNotice = { notices += it },
            activeCorridor = { null },
        )

        // Рівно те, що робить палець: відпустив мапу на вуличному зумі.
        search.updateCameraBounds(bounds(), zoom = 16.0)
        settle()

        assertTrue(
            "після зупинки камери мапа лишилась порожньою — точки не запитані; сповіщення=$notices",
            search.pois.value.isNotEmpty(),
        )
        assertEquals(
            "жодна категорія не натиснута, тож «поруч» не має бути закріплене",
            null,
            search.nearbyActive.value,
        )
    }

    /** Заслін масштабу лишається заслоном: на далекому зумі точок немає. */
    @Test
    fun `далекий зум мовчить про точки`() = runTest {
        val search = MapPoiSearch(
            scope = backgroundScope,
            poiRepository = offlineRepository(corpus()),
            vaultStore = null,
            savedPlaceStore = null,
            postNotice = {},
            activeCorridor = { null },
        )

        search.updateCameraBounds(bounds(), zoom = 11.0)
        settle()

        assertTrue(
            "нижче MIN_POI_ZOOM коробка завелика — точок бути не може",
            search.pois.value.isEmpty(),
        )
    }

    /** Підказка про заслін — РІВНО один раз на перехід, а не на кожен зсув. */
    @Test
    fun `підказку про масштаб кажуть один раз`() = runTest {
        val notices = mutableListOf<String>()
        val search = MapPoiSearch(
            scope = backgroundScope,
            poiRepository = offlineRepository(corpus()),
            vaultStore = null,
            savedPlaceStore = null,
            postNotice = { notices += it },
            activeCorridor = { null },
        )
        search.setZoomHint("Наблизь, щоб побачити місця")

        repeat(3) {
            search.updateCameraBounds(bounds(), zoom = 11.0)
            settle()
        }

        assertEquals(
            "заслін масштабу говорить один раз на перехід: $notices",
            1,
            notices.count { it == "Наблизь, щоб побачити місця" },
        )
    }

    /**
     * Порожній корпус — це порожня мапа, і жодного вигаданого піна.
     * Поза покриттям пакета підлоги немає СВІДОМО (AppContainer:1584).
     */
    @Test
    fun `поза покриттям пакета мапа чесно порожня`() = runTest {
        val search = MapPoiSearch(
            scope = backgroundScope,
            poiRepository = offlineRepository(emptyList()),
            vaultStore = null,
            savedPlaceStore = null,
            postNotice = {},
            activeCorridor = { null },
        )

        search.updateCameraBounds(bounds(), zoom = 16.0)
        settle()

        assertTrue(search.pois.value.isEmpty())
    }

    /**
     * Дебаунс: поки палець веде мапу, запиту немає. Інакше кожен кадр камери
     * коштував би походу на диск — та сама термальна яма, що й у MapCanvas.
     */
    @Test
    fun `рух камери не породжує запиту, доки вона не спинилась`() = runTest {
        val search = MapPoiSearch(
            scope = backgroundScope,
            poiRepository = offlineRepository(corpus()),
            vaultStore = null,
            savedPlaceStore = null,
            postNotice = {},
            activeCorridor = { null },
        )

        // Палець веде: кадри частіше за вікно дебаунсу.
        repeat(5) {
            search.updateCameraBounds(bounds(), zoom = 16.0)
            advanceTimeBy(100L)
        }
        assertTrue(
            "під пальцем точки не запитують",
            search.pois.value.isEmpty(),
        )

        settle()
        assertTrue(
            "а щойно камера спинилась — запитують",
            search.pois.value.isNotEmpty(),
        )
    }
}
