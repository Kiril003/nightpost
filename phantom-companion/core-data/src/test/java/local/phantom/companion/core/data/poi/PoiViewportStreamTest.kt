package local.phantom.companion.core.data.poi

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map used to sit bare while a 7-second Overpass call ran. The viewport
 * answer must be local first and live second — never live only.
 */
class PoiViewportStreamTest {

    private val south = 50.40
    private val west = 30.50
    private val north = 50.50
    private val east = 30.60

    private fun poi(id: Long, name: String, category: String = "amenity", sub: String? = "cafe") =
        PoiEntity(id = id, lat = 50.45, lon = 30.55, name = name, category = category, subcategory = sub, tags = emptyMap())

    private fun place(id: Long, name: String, category: String = "shop", sub: String? = "supermarket") =
        PoiGazetteerEntity(id = id, lat = 50.45, lon = 30.55, name = name, category = category, subcategory = sub)

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

    private class FakeGazetteerDao : PoiGazetteerDao {
        val rows = mutableListOf<PoiGazetteerEntity>()
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
        ) = emptyList<PoiGazetteerEntity>()

        override suspend fun searchAnywhere(
            query: String,
            atLat: Double,
            atLon: Double,
            lonScaleSq: Double,
            limit: Int,
        ) = emptyList<PoiGazetteerEntity>()

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
        ) = emptyList<PoiGazetteerEntity>()

        /**
         * Дзеркало `ORDER BY` зі справжнього запиту, а не заглушка.
         *
         * Стояв `emptyList()` — і саме тому підміна мовчала про те, чим
         * відрізняються два порядки: доки диск кликав [inBox], різниці не
         * було видно, бо порожнеча однакова на будь-якому сортуванні.
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
        ) = rows.filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon }
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
        ) = rows.filter { it.lat in minLat..maxLat && it.lon in minLon..maxLon }
            .sortedByDescending { it.seenAt }
            .take(limit)

        override suspend fun byIds(ids: List<Long>) = rows.filter { it.id in ids }

        override suspend fun size() = rows.size
    }

    @Test
    fun `the disk answer comes first, the live one upgrades it`() = runTest {
        val dao = FakePoiDao()
        val gazetteer = FakeGazetteerDao().apply { rows += place(1, "Сільпо") }
        val repo = PoiRepository(
            poiDao = dao,
            gazetteer = gazetteer,
            liveFetch = { _, _, _, _ ->
                delay(3_000)
                listOf(poi(2, "WOG", sub = "fuel"))
            },
        )

        val seen = repo.getPois(south, west, north, east, zoom = 16.0).toList()

        assertEquals(2, seen.size)
        assertEquals(listOf(1L), seen[0].map { it.id })
        assertEquals(setOf(1L, 2L), seen[1].map { it.id }.toSet())
        // Everything the map drew, PHANTOM kept.
        assertEquals(listOf(2L), dao.rows.map { it.id })
        assertTrue(gazetteer.rows.any { it.id == 2L })
    }

    @Test
    fun `a hanging Overpass cannot hold the pump`() = runTest {
        val gazetteer = FakeGazetteerDao().apply { rows += place(1, "Сільпо") }
        val repo = PoiRepository(
            poiDao = FakePoiDao(),
            gazetteer = gazetteer,
            liveTimeoutMs = 5_000,
            liveFetch = { _, _, _, _ ->
                delay(10 * 60_000)
                listOf(poi(2, "WOG", sub = "fuel"))
            },
        )

        val seen = repo.getPois(south, west, north, east, zoom = 16.0).toList()

        assertEquals(1, seen.size)
        assertEquals(listOf(1L), seen[0].map { it.id })
        assertTrue("the live stage must give up at its budget", currentTime < 10 * 60_000)
    }

    @Test
    fun `no disk answer and no live answer is one honest empty emission`() = runTest {
        val repo = PoiRepository(poiDao = FakePoiDao(), liveFetch = { _, _, _, _ -> emptyList() })
        assertEquals(listOf(emptyList<PoiEntity>()), repo.getPois(south, west, north, east).toList())
    }

    @Test
    fun `a live failure leaves the disk answer standing`() = runTest {
        val gazetteer = FakeGazetteerDao().apply { rows += place(1, "Сільпо") }
        val repo = PoiRepository(
            poiDao = FakePoiDao(),
            gazetteer = gazetteer,
            liveFetch = { _, _, _, _ -> error("немає мережі") },
        )

        val seen = repo.getPois(south, west, north, east).toList()

        assertEquals(1, seen.size)
        assertEquals(listOf(1L), seen[0].map { it.id })
    }

    @Test
    fun `a cache dense enough to be a real fetch answers alone`() = runTest {
        val dao = FakePoiDao().apply { rows += (1..6).map { poi(it.toLong(), "місце $it") } }
        var calls = 0
        val repo = PoiRepository(
            poiDao = dao,
            liveFetch = { _, _, _, _ -> calls++; emptyList() },
        )

        val seen = repo.getPois(south, west, north, east).toList()

        assertEquals(1, seen.size)
        assertEquals(6, seen[0].size)
        assertEquals(0, calls)
    }

    /**
     * КЕШ ДОПОВНЮЄ ДИСК, А НЕ ЗАМІНЮЄ ЙОГО.
     *
     * Заслін «щільний кеш відповідає сам» правильно береже від ЗАЙВОГО походу
     * в Overpass — і, поки його не розділили, мовчки викидав разом із походом
     * увесь національний довідник. Пʼяти рядків у 15-хвилинному кеші досить,
     * щоб вимкнути 176 558 місць країни: `MIN_CACHED_DENSITY = 5`.
     *
     * Наслідок був адресний. Запит Overpass (`OverpassQueries`) знає
     * `drinking_water`, але НЕ знає `natural=spring` і `man_made=water_well` —
     * тобто 9 830 джерел і криниць країни. Один успішний похід у мережу
     * прибирав із мапи саме воду, і рівно на ті чверть години, протягом яких
     * людина вже бачила, що мережа є, і повірила б мапі.
     *
     * Похід у мережу при цьому не повертається — його й не мало бути.
     */
    @Test
    fun `a dense cache adds to the disk floor and never replaces it`() = runTest {
        val dao = FakePoiDao().apply { rows += (1..6).map { poi(it.toLong(), "місце $it") } }
        val gazetteer = FakeGazetteerDao().apply {
            rows += place(700, "Джерело", category = "natural", sub = "spring")
            rows += place(701, "Криниця", category = "man_made", sub = "water_well")
        }
        var calls = 0
        val repo = PoiRepository(
            poiDao = dao,
            gazetteer = gazetteer,
            liveFetch = { _, _, _, _ -> calls++; emptyList() },
        )

        val seen = repo.getPois(south, west, north, east, zoom = 16.0).toList()

        assertEquals(1, seen.size)
        val ids = seen[0].map { it.id }.toSet()
        assertTrue("джерело зникло під щільним кешем: $ids", 700L in ids)
        assertTrue("криниця зникла під щільним кешем: $ids", 701L in ids)
        assertTrue("кешовані рядки мусять лишитись: $ids", ids.containsAll((1L..6L).toList()))
        assertEquals("щільний кеш не сміє слати нового запиту в мережу", 0, calls)
    }

    @Test
    fun `the viewport cap holds even when the live answer floods it`() = runTest {
        val gazetteer = FakeGazetteerDao().apply {
            rows += (1..50).map { place(it.toLong(), "крамниця $it", "shop", "clothes") }
        }
        val repo = PoiRepository(
            poiDao = FakePoiDao(),
            gazetteer = gazetteer,
            liveFetch = { _, _, _, _ -> (100..200).map { poi(it.toLong(), "кава $it") } },
        )

        // z13.5 — a district: 25 pins, not 151.
        val seen = repo.getPois(south, west, north, east, zoom = 13.5).toList()

        assertEquals(2, seen.size)
        assertTrue(seen.all { it.size <= PoiDensity.capForZoom(13.5) })
        assertEquals(PoiDensity.capForZoom(13.5), seen.last().size)
    }

    /**
     * ВІКНО ДИСКА БЕРЕТЬСЯ ВІД ЦЕНТРА КАДРУ, А НЕ ВІД ПІВНОЧІ.
     *
     * Довідник віддавав `inBox` — `ORDER BY seenAt DESC`. Для рядків, які
     * телефон справді бачив живими, це «найсвіжіше першим» і має сенс. Для
     * національного корпусу — ні: усі 176 558 рядків засіяні одним потоком, і
     * `seenAt` у них це порядок РЯДКА У ФАЙЛІ, а файл упорядкований за
     * широтою. Тобто `DESC` означало «найпівнічніші», і `LIMIT cap * 4`
     * вирізав із кадру смугу вздовж його ВЕРХНЬОГО краю — нижні дві третини
     * мапи не мали шансу показати нічого з довідника. Жодне ранжування цього
     * не лікує: ті рядки до нього не доходили.
     *
     * Корпус тут відтворює саме цю властивість: широта росте разом із
     * `seenAt`. Двісті рядків одного роду при стелі 25 (`cap * 4` = 100) — під
     * старим порядком у кадр не потрапляє ЖОДНА точка з південної половини.
     */
    @Test
    fun `the disk window is taken from the centre of the frame, not its north edge`() = runTest {
        val centreLat = (south + north) / 2
        val gazetteer = FakeGazetteerDao().apply {
            rows += (0..199).map {
                PoiGazetteerEntity(
                    id = it.toLong(),
                    lat = south + 0.0002 + it * 0.0005,
                    lon = 30.55,
                    name = "крамниця $it",
                    category = "shop",
                    subcategory = "clothes",
                    seenAt = 1_000L + it,
                )
            }
        }
        val repo = PoiRepository(
            poiDao = FakePoiDao(),
            gazetteer = gazetteer,
            liveFetch = { _, _, _, _ -> emptyList() },
        )

        val frame = repo.getPois(south, west, north, east, zoom = 13.5).toList().first()

        assertEquals(PoiDensity.capForZoom(13.5), frame.size)
        val southern = frame.count { it.lat < centreLat }
        assertTrue(
            "кадр вирізаний по верхньому краю: південніше центра $southern точок із ${frame.size}",
            southern >= frame.size / 3,
        )
        assertTrue("північна половина теж мусить бути в кадрі", frame.any { it.lat > centreLat })
        assertFalse(
            "найпівнічніша точка коробки не може бути найдоречнішою для центра",
            frame.any { it.id == 199L },
        )
    }
}
