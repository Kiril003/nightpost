package local.phantom.companion.core.data.poi

import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.PhantomDatabase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HIGHWAY — the number this whole wave exists to produce: how long the REAL
 * FTS4 index takes on REAL device SQLite at the REAL bundled scale (265 447
 * streets), not a JVM guess.
 *
 * Why not [GazetteerSearchTest]/[StreetGazetteerSearchTest]: those seed a
 * handful of rows to prove correctness. This seeds the ENTIRE bundled asset —
 * the thing nobody had measured past 21 000 Kyiv POIs (device-verified
 * 11.08). File-backed, not in-memory: an in-memory table is fully resident in
 * RAM before the first query and would understate what a driver's phone does
 * with the real on-disk (SQLCipher-encrypted in production) database — this
 * is still unencrypted, which is the one gap left to the true number.
 *
 * Anchored on Kyiv's real Khreshchatyk coordinates rather than the device's
 * live GPS fix ON PURPOSE: this phone's actual GNSS position while this wave
 * was measured was Ostrava, Czech Republic, ~1200 km from any bundled street
 * — a live-location UI search from here would hit the lat/lon box filter and
 * correctly return nothing in under a millisecond, which proves nothing about
 * FTS4 at scale. A test supplies the coordinates a driver in Kyiv would
 * actually have; that is the scenario the feature is built for.
 */
@RunWith(AndroidJUnit4::class)
class StreetSearchDeviceBenchmarkTest {

    private lateinit var db: PhantomDatabase

    // Kyiv, Khreshchatyk — real coordinates baked into the asset.
    private val lat = 50.4445
    private val lon = 30.5211

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun seed_and_search_the_full_bundled_corpus_on_device() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = appContext.getDatabasePath("street_bench.db")
        dbFile.delete()
        db = Room.databaseBuilder(appContext, PhantomDatabase::class.java, dbFile.absolutePath)
            .build()
        val dao = db.poiGazetteerDao()

        // Read the asset the SAME way AppContainer does: instrumentation
        // context first (androidTest APK carries core-data's own assets),
        // target context as a fallback.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val stream = runCatching { instrumentation.context.assets.open("country/ua/streets.tsv") }
            .getOrElse { instrumentation.targetContext.assets.open("country/ua/streets.tsv") }

        var written = 0
        val seedMs = System.currentTimeMillis()
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLine() // header — generated=<epoch> count=<n>
            for (chunk in OfflineStreetPack.chunkLines(reader)) {
                dao.upsert(chunk)
                written += chunk.size
            }
        }
        val seedElapsed = System.currentTimeMillis() - seedMs
        assertTrue("expected on the order of 265k rows, got $written", written > 200_000)

        val box = OfflinePoiSearch.boundingBox(lat, lon, 25_000)
        val lonScaleSq = Math.cos(Math.toRadians(lat)).let { it * it }

        suspend fun timedSearch(query: String): Pair<Long, List<PoiGazetteerEntity>> {
            val match = OfflinePoiSearch.toMatchQuery(query)!!
            val t0 = System.nanoTime()
            val rows = dao.searchInBox(
                query = match,
                minLat = box[0], maxLat = box[1], minLon = box[2], maxLon = box[3],
                atLat = lat, atLon = lon, lonScaleSq = lonScaleSq,
                limit = 100,
            )
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            return elapsedMs to rows
        }

        // Cold: process just seeded, page cache and Room/SQLite statement
        // cache both empty. This is the number a driver's FIRST search after
        // install/boot actually pays.
        val (coldMs, coldRows) = timedSearch("хрещатик")
        // Warm: same query again — what every search after the first costs.
        val (warmMs, warmRows) = timedSearch("хрещатик")
        // A typo one edit away — must MISS strict FTS (proves FTS is not
        // silently fuzzy) and fall through to PoiRepository's rescue scan.
        val repo = PoiRepository(poiDao = db.poiDao(), gazetteer = dao)
        val rescueT0 = System.nanoTime()
        val rescueHits = repo.searchOffline("крещатик", lat, lon, radiusMeters = 25_000)
        val rescueMs = (System.nanoTime() - rescueT0) / 1_000_000

        val corpusT0 = System.nanoTime()
        val corpus = dao.size()
        val corpusMs = (System.nanoTime() - corpusT0) / 1_000_000

        val report = """
            |=== STREET SEARCH DEVICE BENCHMARK (SM-A525F, real file-backed SQLite) ===
            |rows written:        $written
            |seed time:           ${seedElapsed} ms (${written * 1000L / maxOf(seedElapsed, 1)} rows/s)
            |corpus size (COUNT): $corpus (${corpusMs} ms)
            |'хрещатик' COLD:     ${coldMs} ms -> ${coldRows.size} hit(s) ${coldRows.firstOrNull()?.name}
            |'хрещатик' WARM:     ${warmMs} ms -> ${warmRows.size} hit(s)
            |'крещатик' (typo, rescue path): ${rescueMs} ms -> ${rescueHits.size} hit(s) ${rescueHits.firstOrNull()?.name}
            |============================================================================
        """.trimMargin()
        Log.i("StreetSearchBenchmark", report)
        println(report)

        assertTrue("'хрещатик' must find the real Khreshchatyk", coldRows.isNotEmpty())
        assertTrue("the typo must reach it via rescue, not strict FTS", rescueHits.isNotEmpty())
    }
}
