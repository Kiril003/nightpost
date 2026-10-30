package local.phantom.companion.core.data.poi

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.PhantomDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HIGHWAY — offline POI search against the REAL SQLite on the REAL device.
 *
 * This is not a formality. The whole feature rests on facts that only the
 * device can confirm:
 *
 *  - **FTS5 does not exist here.** `USING fts5` fails with `no such module` on
 *    the target (SM-A566B / Android 16), which is why the index is FTS4. If a
 *    future Android ever ships FTS5, that is an upgrade to consider — it is not
 *    something to assume.
 *  - **unicode61 must fold Cyrillic.** The default tokenizer case-folds ASCII
 *    only, so «Львівська» would never match «львівськ» and the search would be
 *    quietly useless in the only language it is for.
 *  - **The external-content index must stay in sync.** Room generates no
 *    triggers for `content=`; if `upsert` ever wrote one table and not the
 *    other, MATCH would return rowids for rows that do not exist, and the join
 *    would silently drop them. A driver would see "no fuel nearby" on a road
 *    lined with it.
 */
@RunWith(AndroidJUnit4::class)
class GazetteerSearchTest {

    private lateinit var db: PhantomDatabase
    private lateinit var dao: PoiGazetteerDao

    // Kyiv, Maidan.
    private val lat = 50.4501
    private val lon = 30.5234

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PhantomDatabase::class.java,
        ).build()
        dao = db.poiGazetteerDao()
    }

    @After
    fun close() = db.close()

    private fun poi(id: Long, name: String, lat: Double, lon: Double, sub: String) =
        PoiGazetteerEntity(id, lat, lon, name, "amenity", sub)

    private fun seed() = runBlocking {
        dao.upsert(
            listOf(
                poi(1, "Кав'ярня Львівська", 50.4510, 30.5240, "cafe"),
                poi(2, "АЗС ОККО", 50.4530, 30.5250, "fuel"),
                poi(3, "WOG АЗС", 50.4600, 30.5300, "fuel"),
                // Far away: Lviv. Must never surface in a Kyiv search.
                poi(4, "ОККО Львів", 49.8397, 24.0297, "fuel"),
            ),
        )
    }

    @Test
    fun unicode61_folds_cyrillic_and_prefixes_match() = runBlocking {
        seed()
        // He types three letters at 90 km/h and means the whole word.
        val hits = search("львівськ")
        assertEquals(1, hits.size)
        assertEquals("Кав'ярня Львівська", hits.first().name)
    }

    @Test
    fun an_apostrophe_does_not_break_the_query() = runBlocking {
        seed()
        // The literal reason toMatchQuery exists: this must not throw.
        val hits = search("кав'ярня")
        assertEquals(1, hits.size)
        assertEquals(1L, hits.first().id)
    }

    @Test
    fun the_far_away_namesake_is_not_an_answer() = runBlocking {
        seed()
        // «ОККО» matches both Kyiv and Lviv by name. Only one of them is a
        // place this driver can reach.
        val hits = search("окко", radius = 25_000)
        assertEquals(1, hits.size)
        assertEquals("АЗС ОККО", hits.first().name)
    }

    @Test
    fun nearest_first() = runBlocking {
        seed()
        val hits = search("азс", radius = 25_000)
        assertEquals(listOf(2L, 3L), hits.map { it.id })
        assertTrue(hits[0].distanceMeters < hits[1].distanceMeters)
    }

    @Test
    fun category_browse_needs_no_text_at_all() = runBlocking {
        seed()
        // The car head's «АЗС» chip: a tap, not typing.
        val rows = dao.byCategoryInBox(
            subcategories = listOf("fuel"),
            minLat = lat - 0.5, maxLat = lat + 0.5,
            minLon = lon - 0.5, maxLon = lon + 0.5,
            atLat = lat, atLon = lon, lonScaleSq = lonScaleSq,
            limit = 50,
        )
        val hits = OfflinePoiSearch.rank(rows, lat, lon, radiusMeters = 25_000)
        assertEquals("both Kyiv stations, nearest first", listOf(2L, 3L), hits.map { it.id })
    }

    /**
     * ОБРІЗАННЯ МУСИТЬ ВІДРІЗАТИ НАЙДАЛЬШИХ, А НЕ ВИПАДКОВИХ.
     *
     * Донедавна `searchInBox` мала `LIMIT` і не мала `ORDER BY`: із трьохсот
     * збігів SQLite віддавала ті, до яких дійшла першою (порядок запису), і
     * ранжування за відстанню в Kotlin бачило вже обрізаний список. Найближче
     * місце могло не дожити до порівняння відстаней.
     *
     * Тут сорок далеких АЗС записані ПЕРШИМИ, найближча — ОСТАННЬОЮ, а ліміт
     * менший за кількість збігів. Без `ORDER BY` у видачі її не було б.
     *
     * ЦЕ МОЖЕ ДОВЕСТИ ЛИШЕ ПРИСТРІЙ: JVM-набір не має SQLite, і жоден
     * підроблений DAO не свідчить про те, як обрізає справжня база.
     */
    @Test
    fun the_limit_cuts_the_farthest_not_the_arbitrary() = runBlocking {
        val far = (10L until 50L).map { i ->
            // ~2..6 км на північ — усі далі за найближчу.
            poi(i, "АЗС №$i", lat + 0.02 + i * 0.0005, lon, "fuel")
        }
        dao.upsert(far)
        dao.upsert(listOf(poi(999, "АЗС Найближча", lat + 0.0005, lon, "fuel")))

        val box = OfflinePoiSearch.boundingBox(lat, lon, 25_000)
        val rows = dao.searchInBox(
            query = OfflinePoiSearch.toMatchQuery("азс")!!,
            minLat = box[0], maxLat = box[1], minLon = box[2], maxLon = box[3],
            atLat = lat, atLon = lon, lonScaleSq = lonScaleSq,
            limit = 5,
        )

        assertEquals(5, rows.size)
        assertTrue(
            "найближча АЗС не дожила до обрізання — ORDER BY зник",
            rows.any { it.id == 999L },
        )
        assertEquals("і вона мусить бути першою", 999L, rows.first().id)
    }

    /** cos²φ для порядку за квадратом пласкої відстані — як у репозиторії. */
    private val lonScaleSq: Double
        get() = Math.cos(Math.toRadians(lat)).let { it * it }

    @Test
    fun reseeing_a_place_updates_it_without_duplicating_the_index() = runBlocking {
        seed()
        // OSM ids are stable, so driving past the same station again must
        // UPDATE it — not add a second copy, and not leave a stale term behind
        // in the FTS index pointing at the old name.
        dao.upsert(listOf(poi(2, "ОККО Хрещатик", 50.4530, 30.5250, "fuel")))

        assertEquals("no duplicate row", 4, dao.size())
        assertEquals("the new name is findable", 1, search("хрещатик").size)
        assertTrue("the old name is gone from the index", search("азс окко").isEmpty())
    }

    private suspend fun search(q: String, radius: Int = 5_000): List<PoiHit> {
        val box = OfflinePoiSearch.boundingBox(lat, lon, radius)
        val match = OfflinePoiSearch.toMatchQuery(q) ?: return emptyList()
        val rows = dao.searchInBox(
            query = match,
            minLat = box[0], maxLat = box[1], minLon = box[2], maxLon = box[3],
            atLat = lat, atLon = lon, lonScaleSq = lonScaleSq,
            limit = 100,
        )
        return OfflinePoiSearch.rank(rows, lat, lon, radius, q, limit = 20)
    }
}
