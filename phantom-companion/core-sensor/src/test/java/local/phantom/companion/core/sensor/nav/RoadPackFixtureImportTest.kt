package local.phantom.companion.core.sensor.nav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Наскрізна проба того, що `scripts/install-road-pack.sh` обіцяє власнику
 * рядком «Пакет прийнято»: файл, спечений mesh_bake.py — `ways(cell,id,name,
 * oneway,pts,tags)` + `PRAGMA user_version` — читається так само, як це
 * робить `RoadPackImporter.importFromPath` (:feature-precision): відкрити
 * READONLY, прочитати `ways` без огляду на версію, влити в [RoadMeshStore],
 * прочитати назад і довести, що [RoadProfile] бере швидкість зі знаку.
 *
 * Версія фікстури — 3, не 1 і не 2: mesh_bake.py дійшов до DB_VERSION=3
 * (транспортний шар, 05.09.2026), а `ways` не змінювалась відколи Kotlin
 * навчився тегів (v2). Пакет на апараті власника (`kyiv-v3.db`) несе саме
 * цю версію — округлювати її в тесті до старішого числа означало б
 * перевіряти вигаданий пакет, а не той, що лежить у Завантаженнях.
 */
@RunWith(RobolectricTestRunner::class)
class RoadPackFixtureImportTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<Context>()

    /** Пече рівно те, що лежить на диску: дві клітинки однієї дороги, як
     *  `bake_ways()` дублює рядок на кожну клітинку своєї bbox. */
    private fun bakeFixture(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE ways(cell INTEGER NOT NULL, id INTEGER NOT NULL, name TEXT, " +
                "oneway INTEGER NOT NULL, pts TEXT NOT NULL, tags TEXT, PRIMARY KEY(cell, id))",
        )
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
        val pts = "50.450000,30.520000;50.451000,30.529000"
        val tags = "highway=primary|maxspeed=50"
        db.execSQL(
            "INSERT INTO ways(cell,id,name,oneway,pts,tags) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(2522L, 1001L, "вул. Тестова", 0L, pts, tags),
        )
        db.execSQL(
            "INSERT INTO ways(cell,id,name,oneway,pts,tags) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(2523L, 1001L, "вул. Тестова", 0L, pts, tags),
        )
        db.execSQL("INSERT INTO meta(k,v) VALUES('format','3')")
        db.execSQL("PRAGMA user_version = 3")
        db.close()
    }

    /** Дзеркало `RoadPackImporter.importFromPath`'s читального циклу — без
     *  залежності на :feature-precision, щоб цей гейт лишався в core-sensor. */
    private fun readPack(path: String): List<RoadWay> {
        val pack = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        val seen = HashSet<Long>()
        val out = ArrayList<RoadWay>()
        try {
            pack.rawQuery("SELECT id, name, oneway, pts, tags FROM ways", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (!seen.add(id)) continue // одна дорога лежить у кількох клітинках
                    val decoded = RoadWayCodec.decodePoints(c.getString(3)) ?: continue
                    out.add(
                        RoadWay(
                            id = id,
                            points = decoded,
                            name = if (c.isNull(1)) null else c.getString(1),
                            oneway = c.getInt(2) != 0,
                            tags = if (c.isNull(4)) emptyMap() else RoadWayCodec.decodeTags(c.getString(4)),
                        ),
                    )
                }
            }
        } finally {
            pack.close()
        }
        return out
    }

    @Test
    fun `справжній v3-пакет читається, геометрія розбирається, швидкість береться зі знаку`() {
        val fixture = File.createTempFile("phantom_road_mesh_fixture", ".db")
        assertTrue(fixture.delete())
        bakeFixture(fixture)

        val ways = readPack(fixture.path)
        fixture.delete()

        assertEquals("дублікат по клітинках мусить згорнутись в одну дорогу", 1, ways.size)
        val decoded = ways.single()
        assertEquals(2, decoded.points.size)
        assertEquals(50.450000, decoded.points[0].first, 1e-6)
        assertEquals(30.520000, decoded.points[0].second, 1e-6)
        assertEquals("primary", decoded.tags["highway"])
        assertEquals("50", decoded.tags["maxspeed"])

        val store = RoadMeshStore(ctx(), "fixture_import_probe.db")
        try {
            store.bulkImport(ways)
            val back = store.waysIn(50.449, 30.519, 50.452, 30.530)
            assertEquals(1, back.size)
            val way = back.single()
            assertEquals(2, way.points.size)
            assertEquals("primary", way.tags["highway"])

            assertEquals(RoadProfile.SpeedBasis.POSTED, RoadProfile.speedBasis(way.tags, foot = false))
            assertEquals(
                50 * 1000.0 / 3600.0 * 0.7,
                RoadProfile.speedMps(way.tags, foot = false),
                1e-9,
            )
        } finally {
            store.close()
        }
    }
}
