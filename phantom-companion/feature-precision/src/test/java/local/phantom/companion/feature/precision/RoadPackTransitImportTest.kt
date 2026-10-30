package local.phantom.companion.feature.precision

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.sensor.nav.RoadMeshStore
import local.phantom.companion.core.sensor.nav.TransitCodec
import local.phantom.companion.core.sensor.nav.railCount
import local.phantom.companion.core.sensor.nav.routeCount
import local.phantom.companion.core.sensor.nav.routesServing
import local.phantom.companion.core.sensor.nav.stopCount
import local.phantom.companion.core.sensor.nav.stopsIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ПРИЙМАЛЬНА СТОРОНА ТРАНСПОРТУ — на справжньому файлі пакета.
 *
 * ЧОМУ САМЕ ТАК. Пакет пече `mesh_bake.py` за межами цього дерева, і між ним
 * та [RoadPackImporter] немає жодного спільного рядка коду — лише
 * домовленість про форму таблиць. Тому тут пакет БУДУЄТЬСЯ вручну, тими
 * самими `CREATE TABLE`, що їх друкує пекар, і читається справжнім
 * імпортером. Тест, який годував би імпортер його ж власними структурами,
 * стеріг би домовленість із самим собою.
 *
 * Три стани, і всі три вже траплялись у цьому продукті:
 *  1. пакет несе транспорт — його видно на пристрої;
 *  2. пакет старий і транспорту не має — це НЕ помилка й не «тут порожньо»;
 *  3. пакет спечено рецептом без ключа, який пристрій уміє читати — дані
 *     вносяться повністю, але ключ називається поіменно.
 */
@RunWith(RobolectricTestRunner::class)
class RoadPackTransitImportTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<Context>()

    /** Точка, що лежить у Києві — щоб bbox-запити мали де її знайти. */
    private val lat = 50.51231
    private val lon = 30.42545

    private fun packFile(name: String): File {
        val f = File(ctx().cacheDir, name)
        f.delete()
        return f
    }

    /** Рівно ті `CREATE TABLE`, що їх друкує `mesh_bake.py`. */
    private fun buildPack(
        file: File,
        withTransit: Boolean = true,
        vocabulary: Map<String, String> = TransitCodec.vocabulary(),
    ) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE ways(cell INTEGER NOT NULL, id INTEGER NOT NULL, name TEXT, " +
                "oneway INTEGER NOT NULL, pts TEXT NOT NULL, tags TEXT, PRIMARY KEY(cell, id))",
        )
        db.execSQL(
            "INSERT INTO ways(cell,id,name,oneway,pts,tags) VALUES(1,101,'Хрещатик',0," +
                "'50.447000,30.522000;50.448000,30.523000','highway=primary|surface=asphalt')",
        )
        if (withTransit) {
            db.execSQL(
                "CREATE TABLE stops(cell INTEGER NOT NULL, id INTEGER NOT NULL, name TEXT, " +
                    "lat REAL NOT NULL, lon REAL NOT NULL, tags TEXT, routes TEXT, " +
                    "PRIMARY KEY(cell, id))",
            )
            // Одна зупинка у ДВОХ клітинках — так її кладе пекар, і імпортер
            // мусить порахувати її ОДИН раз.
            db.execSQL(
                "INSERT INTO stops(cell,id,name,lat,lon,tags,routes) VALUES" +
                    "(1,7024197696,'Медичний центр',$lat,$lon," +
                    "'public_transport=stop_position|trolleybus=yes','trolleybus:24|bus:72')," +
                    "(2,7024197696,'Медичний центр',$lat,$lon," +
                    "'public_transport=stop_position|trolleybus=yes','trolleybus:24|bus:72')",
            )
            db.execSQL(
                "CREATE TABLE rails(cell INTEGER NOT NULL, id INTEGER NOT NULL, name TEXT, " +
                    "pts TEXT NOT NULL, tags TEXT, PRIMARY KEY(cell, id))",
            )
            db.execSQL(
                "INSERT INTO rails(cell,id,name,pts,tags) VALUES(1,4331838,NULL," +
                    "'50.412981,30.645086;50.413100,30.645400','railway=tram|usage=main')",
            )
            db.execSQL(
                "CREATE TABLE routes(id INTEGER NOT NULL PRIMARY KEY, mode TEXT NOT NULL, " +
                    "ref TEXT, name TEXT, from_name TEXT, to_name TEXT, operator TEXT, " +
                    "colour TEXT, stops TEXT)",
            )
            db.execSQL(
                "INSERT INTO routes VALUES(177091,'trolleybus','24','Тролейбус 24'," +
                    "'Ботанічний сад','Вокзал','Київпастранс','','7024197696;5;6')",
            )
            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
            for ((k, v) in vocabulary) {
                db.execSQL("INSERT INTO meta(k,v) VALUES(?,?)", arrayOf<Any>(k, v))
            }
            db.execSQL("PRAGMA user_version = 3")
        } else {
            db.execSQL("PRAGMA user_version = 2")
        }
        db.close()
    }

    private fun freshStore(): RoadMeshStore {
        ctx().deleteDatabase(RoadMeshStore.DB_NAME)
        return RoadMeshStore(ctx())
    }

    @Test
    fun `a v3 pack lands its stops, rails and routes on the device`() = runTest {
        freshStore().close()
        val file = packFile("pack_v3.db")
        buildPack(file)

        val result = RoadPackImporter.importFromFile(ctx(), file, deleteAfter = false)
        assertTrue("$result", result is RoadPackImporter.Result.Success)
        result as RoadPackImporter.Result.Success
        assertEquals(3, result.formatVersion)
        assertEquals(1, result.stopCount)
        assertEquals(1, result.railCount)
        assertEquals(1, result.routeCount)
        assertTrue(result.missingKeys.isEmpty())

        val store = RoadMeshStore(ctx())
        assertEquals(1L, store.stopCount())
        assertEquals(1L, store.railCount())
        assertEquals(1L, store.routeCount())
        val stops = store.stopsIn(lat - 0.01, lon - 0.01, lat + 0.01, lon + 0.01)
        assertEquals(1, stops.size)
        assertEquals("Медичний центр", stops[0].name)
        assertEquals(listOf("trolleybus" to "24", "bus" to "72"), stops[0].serving)
        // «Куди йде цей номер» — з таблиці маршрутів, без жодної хвилини.
        val serving = store.routesServing(stops[0].id)
        assertEquals(1, serving.size)
        assertEquals("Ботанічний сад", serving[0].fromName)
        assertEquals("Вокзал", serving[0].toName)
        store.close()
    }

    /**
     * Пакет до v3 транспортних таблиць не має — і це ЧЕСНА відсутність, а не
     * збій. Кожен пакет на кожному диску сьогодні саме такий: пічка
     * фільтрувала `w/highway`, тож зупинок у ньому нема куди було покласти.
     */
    @Test
    fun `a pre-v3 pack still imports, and says it carried no transit`() = runTest {
        freshStore().close()
        val file = packFile("pack_v2.db")
        buildPack(file, withTransit = false)

        val result = RoadPackImporter.importFromFile(ctx(), file, deleteAfter = false)
        assertTrue("$result", result is RoadPackImporter.Result.Success)
        result as RoadPackImporter.Result.Success
        assertEquals(1, result.wayCount)
        assertEquals(2, result.formatVersion)
        assertEquals(0, result.stopCount)
        assertEquals(0, result.railCount)
        assertEquals(0, result.routeCount)
        assertTrue(result.missingKeys.isEmpty())
    }

    /**
     * ЦЕЙ ТЕСТ РАНІШЕ ВИМАГАВ ПРОТИЛЕЖНОГО, і це було хибно.
     *
     * Він закріплював ВІДМОВУ від транспортного шару, щойно словник пакета
     * відрізнявся, на підставі «чужий порядок ключів прочитався б як свій».
     * Підстава не витримує перевірки: рядок `k=v` самоописовий, розбір іде
     * за іменами й порядку не знає. Тобто відмова стояла на ризику, якого
     * не існує, а ціна була б тиха й велика: варто печі навчитись одного
     * ключа раніше за телефон — і ВЕСЬ транспорт перестав би вноситись, без
     * жодного слова про причину.
     *
     * Тепер: дані вносяться повністю, а різниця словника стає ІМЕНЕМ
     * ключа, якого пакету бракує.
     */
    @Test
    fun `a pack baked before a key existed still imports, and names the key`() = runTest {
        freshStore().close()
        val file = packFile("pack_older.db")
        val older = TransitCodec.vocabulary().toMutableMap()
        older["routing_keys"] = older["routing_keys"]!!.split("|")
            .filter { it != "motorroad" }.joinToString("|")
        buildPack(file, vocabulary = older)

        val result = RoadPackImporter.importFromFile(ctx(), file, deleteAfter = false)
        assertTrue("$result", result is RoadPackImporter.Result.Success)
        result as RoadPackImporter.Result.Success
        assertEquals(1, result.wayCount)
        // Транспорт таки внесено — саме це раніше мовчки не відбувалось.
        assertEquals(1, result.stopCount)
        assertEquals(1, result.railCount)
        assertEquals(1, result.routeCount)
        // І пристрій каже, чого пакет не привіз.
        assertEquals(listOf("motorroad"), result.missingKeys)

        val store = RoadMeshStore(ctx())
        assertEquals(1L, store.stopCount())
        store.close()
    }
}
