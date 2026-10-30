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
 * HIGHWAY — proves a street is findable through the EXACT search path
 * [GazetteerSearchTest] already proves for a café, on the REAL device SQLite.
 *
 * Nothing here is a new search: [OfflineStreetPack] only produces
 * [PoiGazetteerEntity] rows with `category = "street"`, written through the
 * SAME [PoiGazetteerDao.upsert] and read back through the SAME
 * [PoiRepository.searchOfflineVerdict]. If this test needed its own matching
 * logic, that would mean the reuse claim was false.
 */
@RunWith(AndroidJUnit4::class)
class StreetGazetteerSearchTest {

    private lateinit var db: PhantomDatabase
    private lateinit var repo: PoiRepository

    // Kyiv, Khreshchatyk.
    private val lat = 50.4445
    private val lon = 30.5211

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PhantomDatabase::class.java,
        ).build()
        repo = PoiRepository(poiDao = db.poiDao(), gazetteer = db.poiGazetteerDao())
    }

    @After
    fun close() = db.close()

    private fun seedBundled(vararg lines: String) = runBlocking {
        // No header line here: chunkLines starts reading at the FIRST street,
        // exactly as AppContainer calls it once it has consumed the header.
        val body = lines.joinToString("") { "$it\n" }
        for (chunk in OfflineStreetPack.chunkLines(java.io.StringReader(body).buffered())) {
            db.poiGazetteerDao().upsert(chunk)
        }
    }

    @Test
    fun a_bundled_street_is_findable_by_name() = runBlocking {
        seedBundled("$lat\t$lon\tprimary\tвулиця Хрещатик")
        val result = repo.searchOfflineVerdict("хрещатик", lat, lon, radiusMeters = 5_000)
        assertEquals(1, result.hits.size)
        assertEquals("вулиця Хрещатик", result.hits.first().name)
        assertEquals("street", result.hits.first().category)
    }

    @Test
    fun a_typo_reaches_the_street_through_the_same_rescue_pass_as_a_cafe() = runBlocking {
        seedBundled("$lat\t$lon\tprimary\tвулиця Хрещатик")
        // «крещатик» — к instead of х, the common misspelling — must not MATCH
        // FTS (no morphology, no fuzziness there); it must survive on rescue.
        val strict = OfflinePoiSearch.toMatchQuery("крещатик")
        val strictRows = strict?.let {
            db.poiGazetteerDao().searchInBox(
                query = it,
                minLat = lat - 1, maxLat = lat + 1, minLon = lon - 1, maxLon = lon + 1,
                atLat = lat, atLon = lon, lonScaleSq = 1.0, limit = 20,
            )
        }.orEmpty()
        assertTrue("FTS itself must not fuzzy-match — that is Kotlin's job", strictRows.isEmpty())

        val result = repo.searchOfflineVerdict("крещатик", lat, lon, radiusMeters = 5_000)
        assertEquals(1, result.hits.size)
        assertEquals("вулиця Хрещатик", result.hits.first().name)
    }

    @Test
    fun a_far_away_namesake_street_is_not_an_answer() = runBlocking {
        seedBundled(
            "$lat\t$lon\tprimary\tвулиця Шевченка",
            // Lviv — same street name, a different city, ~470 km away.
            "49.8397\t24.0297\tprimary\tвулиця Шевченка",
        )
        val result = repo.searchOfflineVerdict("шевченка", lat, lon, radiusMeters = 25_000)
        assertEquals(1, result.hits.size)
        assertTrue(result.hits.first().distanceMeters < 25_000)
    }

    @Test
    fun a_street_that_does_not_exist_here_is_NothingHere_not_an_empty_list() = runBlocking {
        seedBundled("$lat\t$lon\tprimary\tвулиця Хрещатик")
        val result = repo.searchOfflineVerdict("абракадаброво", lat, lon, radiusMeters = 5_000)
        assertTrue(result is PoiRepository.OfflineSearch.NothingHere)
        assertEquals(1, (result as PoiRepository.OfflineSearch.NothingHere).corpusSize)
    }
}
