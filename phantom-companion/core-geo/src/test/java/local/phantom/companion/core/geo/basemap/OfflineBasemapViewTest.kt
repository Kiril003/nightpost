package local.phantom.companion.core.geo.basemap

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Основа з `tiles` як VIEW мусить прийматись.
 *
 * planetiler у компактному режимі (і tippecanoe так само) не тримає однакові
 * тайли двічі: фізичні таблиці — `tiles_shallow` і `tiles_data`, а `tiles` над
 * ними — view. Усі 25 основ із печі саме такі, і приймальна перевірка питала
 * `sqlite_master WHERE type='table'`, тобто відкидала кожну з них — і з
 * каталогу, і з ручного вибору файла — повідомленням «бракує таблиць
 * metadata/tiles». Читає їх MapLibre звичайним `SELECT … FROM tiles`, якому
 * view не заважає; єдиним, хто заперечував, були наші власні ворота.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineBasemapViewTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bake(file: File, tilesAsView: Boolean) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
        listOf(
            "name" to "Україна",
            "format" to "pbf",
            "bounds" to "22.1,44.3,40.2,52.4",
            "minzoom" to "0",
            "maxzoom" to "14",
        ).forEach { (k, v) -> db.execSQL("INSERT INTO metadata VALUES (?, ?)", arrayOf(k, v)) }

        if (tilesAsView) {
            // Розклад planetiler 0.10.2: дві фізичні таблиці і view над ними.
            db.execSQL("CREATE TABLE tiles_shallow (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data_id INTEGER)")
            db.execSQL("CREATE TABLE tiles_data (tile_data_id INTEGER, tile_data BLOB)")
            db.execSQL(
                "CREATE VIEW tiles AS SELECT s.zoom_level, s.tile_column, s.tile_row, d.tile_data " +
                    "FROM tiles_shallow s JOIN tiles_data d ON s.tile_data_id = d.tile_data_id",
            )
            db.execSQL("INSERT INTO tiles_data VALUES (1, X'1f8b')")
            db.execSQL("INSERT INTO tiles_shallow VALUES (0, 0, 0, 1)")
        } else {
            db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            db.execSQL("INSERT INTO tiles VALUES (0, 0, 0, X'1f8b')")
        }
        db.close()
    }

    private fun importOf(file: File): OfflineBasemapResult = runBlocking {
        OfflineBasemapImporter.importFromFile(ApplicationProvider.getApplicationContext(), file)
    }

    @Test
    fun `основа з tiles як view приймається`() {
        val f = tmp.newFile("compact.mbtiles")
        bake(f, tilesAsView = true)
        val result = importOf(f)
        assertTrue(
            "основу planetiler-compact відкинуто: ${(result as? OfflineBasemapResult.Failure)?.message}",
            result is OfflineBasemapResult.Success,
        )
        val info = (result as OfflineBasemapResult.Success).info
        assertEquals("Україна", info.name)
        assertEquals(0, info.minZoom)
        assertEquals(14, info.maxZoom)
        assertEquals(22.1, info.west, 1e-9)
        assertEquals(52.4, info.north, 1e-9)
    }

    @Test
    fun `звичайна основа з таблицею tiles приймається далі`() {
        val f = tmp.newFile("plain.mbtiles")
        bake(f, tilesAsView = false)
        assertTrue(importOf(f) is OfflineBasemapResult.Success)
    }

    /**
     * Розширення на view не має перетворити перевірку на «пускаємо будь-що»:
     * файл без `tiles` узагалі мусить і далі відмовлятись, назвавши причину.
     */
    @Test
    fun `файл без tiles відмовляється й далі`() {
        val f = tmp.newFile("notiles.mbtiles")
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
        db.execSQL("INSERT INTO metadata VALUES ('format', 'pbf')")
        db.close()
        val result = importOf(f)
        assertTrue(result is OfflineBasemapResult.Failure)
        assertTrue(
            "відмова мусить називати причину: ${(result as OfflineBasemapResult.Failure).message}",
            result.message.contains("metadata/tiles"),
        )
    }
}
