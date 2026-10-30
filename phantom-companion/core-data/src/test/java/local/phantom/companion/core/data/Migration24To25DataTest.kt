package local.phantom.companion.core.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Чи переживе телефон, який УЖЕ відкривав v24, дроп `trip_points`?
 *
 * Таблиця встигла народитись на пристроях (збірки з v24 існують), тому зняти
 * сутність без підняття версії не можна. Тут будується справжній файл v24 з
 * експортованої `24.json` — тим самим DDL, який написав би Room, — у ньому
 * лежать і рядки приреченої таблиці, і сусідні намальовані лінії, і тоді файл
 * відкривається ПРОДУКТОВИМ шляхом: `Room.databaseBuilder` + `ALL_MIGRATIONS`.
 *
 * Головне тут — негативний контроль нижче: Room НЕ помічає зайвої таблиці на
 * диску, тож його власна перевірка схеми зеленіє і без дропу. Єдине, що тут
 * уміє почервоніти від забутого `DROP`, — прямий запит до `sqlite_master`.
 */
@RunWith(RobolectricTestRunner::class)
class Migration24To25DataTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "phantom-migration-24-fixture.db"
    private var room: PhantomDatabase? = null

    @Before
    fun freshFile() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun cleanup() {
        room?.close()
        room = null
        ctx.deleteDatabase(dbName)
    }

    @Test
    fun `таблиця точок виїзду зникає з диска, а лінії лишаються цілими`() {
        buildV24 { db -> seed(db) }
        assertEquals("фікстура мусить мати точки до міграції", 3, countIn24("trip_points"))

        val db = openThroughRoom()

        assertFalse(
            "trip_points лишилась на диску: DROP не відпрацював",
            tableExists(db, "trip_points"),
        )

        runBlocking {
            val lines = db.drawnLineDao().getAll()
            assertEquals(2, lines.size)
            val obhid = db.drawnLineDao().byId("line-1")!!
            assertEquals("Обхід Оболоні", obhid.label)
            assertEquals("50.4501,30.5234;50.4600,30.5300", obhid.vertices)
            assertEquals(1_751_000_000_000L, obhid.createdAt)
        }
        assertEquals("ok", scalar(db, "PRAGMA integrity_check"))
    }

    @Test
    fun `телефон, який ніколи не мав жодної точки, теж відкривається на v25`() {
        buildV24 { /* перший запуск: порожньо скрізь */ }
        val db = openThroughRoom()
        assertFalse(tableExists(db, "trip_points"))
        runBlocking { assertEquals(0, db.drawnLineDao().count()) }
    }

    /**
     * НЕГАТИВНИЙ КОНТРОЛЬ — доказ, що перевірка вище не декорація.
     *
     * Room звіряє зі схемою лише ті таблиці, які оголошені сутностями; ЗАЙВА
     * таблиця на диску його не турбує. Тому порожня міграція 24→25 відкриває
     * базу без жодної скарги — і `trip_points` тихо лишається жити. Якщо цей
     * тест колись почне падати на першому твердженні, значить Room навчився
     * бачити зайве, і тоді перевірка через sqlite_master зайва.
     */
    @Test
    fun `негативний контроль - порожня міграція лишає таблицю, і Room цього не бачить`() {
        buildV24 { db -> seed(db) }
        val doesNothing = object : androidx.room.migration.Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }
        // Порожня 24→25 — навмисно зламаний ПЕРШИЙ крок; решту шляху до
        // поточної версії (зараз 28) бере справжніми міграціями, інакше тест
        // ламається щоразу, як зʼявляється нова версія схеми, і про це не
        // говорить нічого про саму перевірку.
        val db = Room.databaseBuilder(ctx, PhantomDatabase::class.java, dbName)
            .addMigrations(
                doesNothing,
                PhantomDatabase.MIGRATION_25_26,
                PhantomDatabase.MIGRATION_26_27,
                PhantomDatabase.MIGRATION_27_28,
                PhantomDatabase.MIGRATION_28_29,
            )
            .allowMainThreadQueries()
            .build()
        room = db
        db.openHelper.writableDatabase // відкривається без нарікань

        assertTrue(
            "Room відкинув базу із зайвою таблицею — тоді твердження через " +
                "sqlite_master більше не єдиний сторож",
            tableExists(db, "trip_points"),
        )
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM trip_points"))
    }

    // -------------------------------------------------------------- фікстура

    private val schemaDir: File by lazy {
        val relative = "schemas/local.phantom.companion.core.data.PhantomDatabase"
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .take(6)
            .map { File(it, relative) }
            .firstOrNull { it.isDirectory }
            ?: error("exported Room schemas not found — is room.schemaLocation still set?")
    }

    /** Усе, що Room виконав би, створюючи свіжу v24, плюс її identity hash. */
    private fun ddlFor(version: Int): List<String> {
        val root = Json.parseToJsonElement(File(schemaDir, "$version.json").readText())
            .jsonObject["database"]!!.jsonObject
        val out = mutableListOf<String>()
        for (element in root["entities"]!!.jsonArray) {
            val entity = element.jsonObject
            val table = entity["tableName"]!!.jsonPrimitive.content
            out += entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            entity["indices"]?.jsonArray?.forEach {
                out += it.jsonObject["createSql"]!!.jsonPrimitive.content
                    .replace("\${TABLE_NAME}", table)
            }
            entity["contentSyncTriggers"]?.jsonArray?.forEach { out += it.jsonPrimitive.content }
        }
        out += "CREATE TABLE IF NOT EXISTS room_master_table " +
            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
        out += "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '" +
            root["identityHash"]!!.jsonPrimitive.content + "')"
        return out
    }

    private fun buildV24(populate: (SupportSQLiteDatabase) -> Unit) {
        val callback = object : SupportSQLiteOpenHelper.Callback(24) {
            override fun onCreate(db: SupportSQLiteDatabase) = ddlFor(24).forEach(db::execSQL)
            override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { db ->
            assertEquals("фікстура мусить бути файлом v24", 24, db.version)
            populate(db)
        }
        helper.close()
    }

    private fun openThroughRoom(): PhantomDatabase {
        val db = Room.databaseBuilder(ctx, PhantomDatabase::class.java, dbName)
            .addMigrations(*PhantomDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        room = db
        db.openHelper.writableDatabase
        assertEquals(currentDbVersion(), db.openHelper.writableDatabase.version)
        return db
    }

    /** Версія, яку оголошує застосунок, — не число, яке памʼятає цей файл. */
    private fun currentDbVersion(): Int =
        schemaDir.listFiles()!!
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .max()

    private fun tableExists(db: PhantomDatabase, table: String): Boolean =
        db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun scalar(db: PhantomDatabase, sql: String): String? =
        db.openHelper.writableDatabase.query(sql).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /** Кількість рядків у ще не мігрованому файлі v24. */
    private fun countIn24(table: String): Int {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, o: Int, n: Int) = Unit
                })
                .build(),
        )
        return helper.writableDatabase.use { db ->
            db.query("SELECT COUNT(*) FROM $table").use { if (it.moveToFirst()) it.getInt(0) else -1 }
        }.also { helper.close() }
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO trip_points VALUES (?,?,?,?,?,?)",
            arrayOf(1L, 1, 50.4501, 30.5234, "Хрещатик", 1_751_000_000_000L),
        )
        db.execSQL(
            "INSERT INTO trip_points VALUES (?,?,?,?,?,?)",
            arrayOf(2L, 2, 50.4600, 30.5300, null, 1_751_000_060_000L),
        )
        db.execSQL(
            "INSERT INTO trip_points VALUES (?,?,?,?,?,?)",
            arrayOf(3L, 3, 50.4700, 30.5400, "Оболонь", 1_751_000_120_000L),
        )
        db.execSQL(
            "INSERT INTO drawn_lines VALUES (?,?,?,?,?)",
            arrayOf(
                "line-1", "Обхід Оболоні", "50.4501,30.5234;50.4600,30.5300",
                1_751_000_000_000L, 1_751_000_000_000L,
            ),
        )
        db.execSQL(
            "INSERT INTO drawn_lines VALUES (?,?,?,?,?)",
            arrayOf(
                "line-2", "Обʼїзд мосту", "50.4300,30.5100;50.4400,30.5150",
                1_751_100_000_000L, 1_751_100_000_000L,
            ),
        )
    }
}
