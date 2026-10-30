package local.phantom.companion.core.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Does upgrading a PHONE THAT IS ALREADY FULL survive v21 → v22?
 *
 * [MigrationLadderTest] answers a different, weaker question: it runs each
 * migration against a dynamic proxy that records SQL and executes nothing, so
 * "the edge issues an ALTER" is all it can prove. A migration that runs, throws
 * nothing, and empties a table passes it. That is the exact failure mode this
 * file exists to rule out.
 *
 * So: a REAL SQLite file is built at v21 from the exported `21.json` — the same
 * DDL Room itself would have written on a fresh v21 install, content-sync
 * triggers included — filled with the kind of rows a person accumulates over
 * months (chats in Ukrainian, pinned moments, embeddings, memory facts, saved
 * places, the paired phantom, vault entries, the gazetteer), and then opened
 * through **Room's own upgrade path**: `Room.databaseBuilder(...)` +
 * `addMigrations(ALL_MIGRATIONS)`. That path runs `MIGRATION_21_22` and then
 * Room's schema validation, so both halves of a bad migration — data loss and
 * «Migration didn't properly handle» — are visible here.
 *
 * Every assertion reads a VALUE back. Row counts alone would pass on a database
 * whose columns got shuffled by a bad `INSERT … SELECT`.
 *
 * JVM (Robolectric), not `androidTest`: this must fail the build on a laptop
 * before a release, not on a device nobody has plugged in. Robolectric runs the
 * real SQLite; `MigrationTestHelper` is not usable here because
 * `androidx.room:room-testing` is not a dependency of this module.
 */
@RunWith(RobolectricTestRunner::class)
class Migration21To22DataTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "phantom-migration-fixture.db"
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

    // ---------------------------------------------------------------- tests

    @Test
    fun `a populated v21 database keeps every row and every value through v22`() {
        buildV21 { db -> seed(db) }

        val db = openThroughRoom()

        // 1. Nothing was dropped on the floor. Counted first so a wipe shows up
        //    as one obvious failure instead of twenty confusing ones.
        assertEquals("stream_items", 5, count(db, "stream_items"))
        assertEquals("memory_facts", 2, count(db, "memory_facts"))
        assertEquals("saved_places", 2, count(db, "saved_places"))
        assertEquals("poi_gazetteer", 3, count(db, "poi_gazetteer"))
        assertEquals("profiles", 1, count(db, "profiles"))
        assertEquals("paired_hosts", 1, count(db, "paired_hosts"))
        assertEquals("vault_entries", 2, count(db, "vault_entries"))
        assertEquals("threads_meta", 2, count(db, "threads_meta"))
        assertEquals("spatial_layers", 1, count(db, "spatial_layers"))
        assertEquals("life_log_entries", 1, count(db, "life_log_entries"))
        assertEquals("calendar_events", 1, count(db, "calendar_events"))
        assertEquals("tasks", 1, count(db, "tasks"))
        assertEquals("reminders", 1, count(db, "reminders"))
        assertEquals("decision_entries", 1, count(db, "decision_entries"))
        assertEquals("pending_publish", 1, count(db, "pending_publish"))
        assertEquals("pending_intents", 1, count(db, "pending_intents"))
        assertEquals("patterns", 1, count(db, "patterns"))
        assertEquals("call_screen_log пішов разом із відсіювачем у v29", 0, count(db, "sqlite_master WHERE name = 'call_screen_log'"))
        assertEquals("notification_digest", 1, count(db, "notification_digest"))
        assertEquals("poi_cache", 1, count(db, "poi_cache"))

        // 2. The chat reads back the way it was written — every column, in its
        //    own column. A shuffled INSERT…SELECT dies here.
        runBlocking {
            val thread = db.streamItemDao().byThread("thread-now", limit = 100)
            assertEquals(4, thread.size)
            assertEquals(listOf("m1", "m2", "m3", "m4"), thread.map { it.id })

            val asked = thread[0]
            assertEquals("Де найближче укриття?", asked.textContent)
            assertEquals("user", asked.role)
            assertEquals("text", asked.kind)
            assertEquals(1L, asked.seqNo)
            assertEquals(1_752_000_000_000L, asked.createdAt)
            assertEquals(false, asked.pinned)

            val pinned = thread[1]
            assertEquals("Школа №5, 400 м на північ.", pinned.textContent)
            assertEquals("assistant", pinned.role)
            assertTrue("the operator's pin must survive", pinned.pinned)
            assertEquals(50.4501, pinned.locationLat!!, 1e-9)
            assertEquals(30.5234, pinned.locationLon!!, 1e-9)
            assertEquals("Майдан", pinned.placeName)
            assertEquals("focused", pinned.phantomState)
            assertEquals("укриття,важливе", pinned.tags)

            val widget = thread[2]
            assertEquals("widget", widget.kind)
            assertEquals("route", widget.widgetType)
            assertEquals("""{"title":"Маршрут","points":42}""", widget.widgetPayload)

            val embedded = thread[3]
            assertNotNull("the embedding blob must not be nulled", embedded.embedding)
            assertArrayEquals(floatArrayOf(0.5f, -0.25f, 1.5f), embedded.embedding!!, 0f)
            assertEquals("Нагадай про ліки о 9", embedded.textContent)

            // The other thread is still its own thread.
            val other = db.streamItemDao().byThread("thread-auto", limit = 100)
            assertEquals(1, other.size)
            assertEquals("Виїзд о 07:40.", other[0].textContent)
        }

        // 3. What PHANTOM believes about the person.
        runBlocking {
            val facts = db.memoryFactDao().getAll().sortedBy { it.id }
            assertEquals(listOf("f1", "f2"), facts.map { it.id })
            assertEquals("Кава без цукру", facts[0].text)
            assertEquals("preference", facts[0].category)
            assertEquals(0.87f, facts[0].confidence, 1e-6f)
            assertEquals(3, facts[0].reinforceCount)
            assertEquals("chat", facts[0].source)
            assertArrayEquals(byteArrayOf(7, -3, 42, 0, 127), facts[0].embedding)
            assertEquals("Škoda Octavia 2014", facts[1].text)
        }

        // 4. Places, the phantom he is paired with, and the vault.
        runBlocking {
            val places = db.savedPlaceDao().getAll().sortedBy { it.id }
            assertEquals("Дім", places[0].label)
            assertEquals(50.4501, places[0].lat, 1e-9)
            assertEquals(30.5234, places[0].lon, 1e-9)
            assertEquals("вул. Хрещатик, 1", places[0].address)
            assertEquals("код 4512", places[0].note)
            assertEquals("Робота", places[1].label)

            val host = db.profileDao().getHostsForProfile("p1")
            assertEquals(1, host.size)
            assertEquals("http://192.168.1.50:8443", host[0].lanUrl)
            assertEquals("eyJhbGciOi.DEVICE.JWT", host[0].deviceJwt)
        }
        assertEquals(
            "vault ciphertext must not be re-written by this migration",
            "vault:v1:c2VjcmV0LXBheWxvYWQ=",
            scalar(db, "SELECT value FROM vault_entries WHERE id='vault-1'"),
        )
        assertEquals("Кирило", scalar(db, "SELECT display_name FROM profiles WHERE id='p1'"))

        // 5. The gazetteer: old rows kept, new NOT NULL columns filled with the
        //    declared defaults (a NULL here means every read throws later).
        runBlocking {
            val kyiv = db.poiGazetteerDao().inBox(50.0, 51.0, 30.0, 31.0, limit = 50)
            assertEquals(3, kyiv.size)
            val cafe = kyiv.single { it.id == 1L }
            assertEquals("Кавʼярня Львівська", cafe.name)
            assertEquals("amenity", cafe.category)
            assertEquals("cafe", cafe.subcategory)
            assertEquals(1_751_000_000_000L, cafe.seenAt)
            assertEquals("", cafe.keywords)
            assertEquals(emptyMap<String, String>(), cafe.tags)
        }
        assertEquals(0, count(db, "poi_gazetteer WHERE keywords IS NULL OR tags IS NULL"))
    }

    @Test
    fun `the rebuilt fts index still finds a place that was saved before the upgrade`() {
        buildV21 { db -> seed(db) }
        val db = openThroughRoom()

        runBlocking {
            val hits = db.poiGazetteerDao()
                .searchInBox("львівськ*", 50.0, 51.0, 30.0, 31.0, AT_LAT, AT_LON, LON_SCALE_SQ, limit = 20)
            assertEquals(
                "the FTS index must be repopulated from the gazetteer, not left empty",
                listOf(1L),
                hits.map { it.id },
            )
            val fuel = db.poiGazetteerDao()
                .searchInBox("окко", 50.0, 51.0, 30.0, 31.0, AT_LAT, AT_LON, LON_SCALE_SQ, limit = 20)
            assertEquals(listOf(2L), fuel.map { it.id })
        }
    }

    @Test
    fun `an empty v21 database - a phone that never used the map - still opens at v22`() {
        buildV21 { /* first run: no rows anywhere */ }
        val db = openThroughRoom()
        assertEquals(0, count(db, "poi_gazetteer"))
        assertEquals(0, count(db, "stream_items"))
        // The FTS index has to exist and be queryable even with nothing in it.
        runBlocking {
            assertEquals(
                emptyList<Long>(),
                db.poiGazetteerDao().searchInBox("окко", -90.0, 90.0, -180.0, 180.0, AT_LAT, AT_LON, LON_SCALE_SQ, 10).map { it.id },
            )
        }
    }

    @Test
    fun `the migrated file is not corrupt`() {
        buildV21 { db -> seed(db) }
        val db = openThroughRoom()
        assertEquals(
            "ok",
            scalar(db, "PRAGMA integrity_check"),
        )
    }

    /**
     * NEGATIVE CONTROL — proof that the four tests above can fail.
     *
     * A green suite means nothing if the harness cannot see a broken migration.
     * This runs a `Migration(21, 22)` that does nothing at all through the same
     * open path; Room must refuse the database with «Migration didn't properly
     * handle». If this test ever passes silently, every other assertion in this
     * file is decoration.
     */
    @Test
    fun `negative control - a migration that changes nothing is rejected, not accepted`() {
        buildV21 { db -> seed(db) }
        val doesNothing = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }
        val db = Room.databaseBuilder(ctx, PhantomDatabase::class.java, dbName)
            .addMigrations(doesNothing, *migrationsAbove21())
            .allowMainThreadQueries()
            .build()
        room = db
        val thrown = try {
            db.openHelper.writableDatabase
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull("Room accepted a database that never got the v22 columns", thrown)
        assertTrue(
            "unexpected failure: ${thrown!!.message}",
            thrown.message.orEmpty().contains("Migration didn't properly handle"),
        )
    }

    /**
     * NEGATIVE CONTROL — proof that the row assertions are load-bearing.
     *
     * A migration can satisfy Room's schema validation perfectly and still hand
     * back an empty chat. This one does the correct DDL and then empties
     * `stream_items` and `poi_gazetteer`; the database opens cleanly — Room
     * never notices — and only a read-back of the rows shows what happened.
     * That is the failure this file exists to catch.
     */
    @Test
    fun `negative control - a schema-valid migration that empties the chat is caught by reading rows`() {
        buildV21 { db -> seed(db) }
        val destructive = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                PhantomDatabase.MIGRATION_21_22.migrate(db)
                db.execSQL("DELETE FROM stream_items")
                db.execSQL("DELETE FROM poi_gazetteer")
            }
        }
        val db = Room.databaseBuilder(ctx, PhantomDatabase::class.java, dbName)
            .addMigrations(destructive, *migrationsAbove21())
            .allowMainThreadQueries()
            .build()
        room = db
        db.openHelper.writableDatabase // opens without complaint: schema is fine

        assertEquals("a wipe passes schema validation — only rows expose it", 0, count(db, "stream_items"))
        assertEquals(0, count(db, "poi_gazetteer"))
    }

    /**
     * The gazetteer is not small. It fills from every viewport the operator
     * panned — «a tank of petrol's worth of city» — and this migration rebuilds
     * the whole FTS index inside the upgrade transaction, on the launch thread,
     * behind SQLCipher. 20 000 places is a modest few weeks of driving.
     */
    @Test
    fun `a large gazetteer survives the index rebuild`() {
        buildV21 { db ->
            seed(db)
            db.beginTransaction()
            try {
                for (i in 100L until 20_100L) {
                    db.execSQL(
                        "INSERT INTO poi_gazetteer VALUES (?,?,?,?,?,?,?)",
                        arrayOf(
                            i, 50.0 + i / 100_000.0, 30.0 + i / 100_000.0,
                            "Місце №$i", "amenity", "cafe", 1_751_000_000_000L + i,
                        ),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        val started = System.currentTimeMillis()
        val db = openThroughRoom()
        val elapsed = System.currentTimeMillis() - started
        println("[migration 21->22] 20 003 gazetteer rows migrated in ${elapsed} ms (JVM/Robolectric)")

        assertEquals(20_003, count(db, "poi_gazetteer"))
        runBlocking {
            val hits = db.poiGazetteerDao()
                .searchInBox("львівськ*", 50.0, 51.0, 30.0, 31.0, AT_LAT, AT_LON, LON_SCALE_SQ, limit = 20)
            assertEquals(listOf(1L), hits.map { it.id })
        }
    }

    // -------------------------------------------------------------- fixture

    /** The exported schema directory, located the way [MigrationLadderTest] does. */
    private val schemaDir: File by lazy {
        val relative = "schemas/local.phantom.companion.core.data.PhantomDatabase"
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .take(6)
            .map { File(it, relative) }
            .firstOrNull { it.isDirectory }
            ?: error("exported Room schemas not found — is room.schemaLocation still set?")
    }

    /**
     * Every statement Room would have run to create a fresh v21 database:
     * tables, indices, and — the part hand-written migrations forget — the FTS
     * content-sync triggers, plus `room_master_table` carrying v21's identity
     * hash so Room takes the genuine upgrade path.
     */
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

    private fun buildV21(populate: (SupportSQLiteDatabase) -> Unit) {
        val callback = object : SupportSQLiteOpenHelper.Callback(21) {
            override fun onCreate(db: SupportSQLiteDatabase) = ddlFor(21).forEach(db::execSQL)
            override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { db ->
            assertEquals("fixture must be a v21 file", 21, db.version)
            populate(db)
        }
        helper.close()
    }

    /**
     * The production open path: Room, the registered ladder, nothing else. Room
     * runs `MIGRATION_21_22` and then validates the result against the compiled
     * v22 schema — a forgotten column surfaces here as
     * «Migration didn't properly handle», exactly as it would on the phone.
     */
    private fun openThroughRoom(): PhantomDatabase {
        val db = Room.databaseBuilder(ctx, PhantomDatabase::class.java, dbName)
            .addMigrations(*PhantomDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        room = db
        db.openHelper.writableDatabase // force onUpgrade + schema validation now
        // Read from the class, never a literal: this file was red for every
        // agent because 22 was hardcoded here and the ladder grew to 23.
        assertEquals(currentDbVersion(), db.openHelper.writableDatabase.version)
        return db
    }

    /** The version the app actually declares — not a number this file remembers. */
    private fun currentDbVersion(): Int =
        // Room's @Database is not retained at runtime, so the exported schemas
        // are the only in-test source of truth: KSP writes one per version.
        schemaDir.listFiles()!!
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .max()

    /**
     * Every real migration ABOVE 21, so a negative control's fake 21->22 still
     * reaches today's version. Without these Room fails with «a migration from
     * 21 to N was required but not found» — a different failure than the one
     * the control is written to prove.
     */
    private fun migrationsAbove21(): Array<androidx.room.migration.Migration> =
        PhantomDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 22 }.toTypedArray()

    /** Months of a real phone, compressed. */
    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO profiles VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "p1", "Кирило", "owner", null, 1_700_000_000_000L,
                "\$argon2id\$v=19\$m=65536,t=3,p=4\$c2FsdA\$aGFzaA", 1L, null, null,
                "rare", "YmVoYXZpb3VyLW1vZGVs", 0.92, 1L,
            ),
        )
        db.execSQL(
            "INSERT INTO paired_hosts VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "p1", "host-1", "http://192.168.1.50:8443", null,
                "eyJhbGciOi.DEVICE.JWT", "cHJpdmF0ZS1rZXk=", 1_751_900_000_000L, 1L,
                "chat,vision,geo",
            ),
        )
        db.execSQL(
            "INSERT INTO vault_entries VALUES (?,?,?,?,?,?,?)",
            arrayOf(
                "vault-1", "passport", "vault:v1:c2VjcmV0LXBheWxvYWQ=", "document",
                """{"issued":"2019"}""", 1_700_000_100_000L, 1_751_000_100_000L,
            ),
        )
        db.execSQL(
            "INSERT INTO vault_entries VALUES (?,?,?,?,?,?,?)",
            arrayOf(
                "vault-2", "код домофона", "vault:v1:ZG9vci1jb2Rl", "secret",
                null, 1_700_000_200_000L, 1_751_000_200_000L,
            ),
        )

        db.execSQL(
            "INSERT INTO threads_meta VALUES (?,?,?,?)",
            arrayOf("thread-now", "Зараз", 1_752_000_400_000L, "task"),
        )
        db.execSQL(
            "INSERT INTO threads_meta VALUES (?,?,?,?)",
            arrayOf("thread-auto", "Авто", 1_751_500_000_000L, "device"),
        )

        // id, thread_id, seq_no, created_at, kind, role, text_content, widget_type,
        // widget_payload, streaming, location_lat, location_lon, place_name,
        // phantom_state, emotion_tag, pinned, tags, embedding
        db.execSQL(
            "INSERT INTO stream_items VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "m1", "thread-now", 1L, 1_752_000_000_000L, "text", "user",
                "Де найближче укриття?", null, null, 0L,
                null, null, null, null, null, 0L, null, null,
            ),
        )
        db.execSQL(
            "INSERT INTO stream_items VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "m2", "thread-now", 2L, 1_752_000_100_000L, "text", "assistant",
                "Школа №5, 400 м на північ.", null, null, 0L,
                50.4501, 30.5234, "Майдан", "focused", "calm", 1L, "укриття,важливе", null,
            ),
        )
        db.execSQL(
            "INSERT INTO stream_items VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "m3", "thread-now", 3L, 1_752_000_200_000L, "widget", null,
                null, "route", """{"title":"Маршрут","points":42}""", 0L,
                null, null, null, null, null, 0L, null, null,
            ),
        )
        db.execSQL(
            "INSERT INTO stream_items VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "m4", "thread-now", 4L, 1_752_000_300_000L, "text", "user",
                "Нагадай про ліки о 9", null, null, 0L,
                null, null, null, null, null, 0L, null,
                floatsToBlob(floatArrayOf(0.5f, -0.25f, 1.5f)),
            ),
        )
        db.execSQL(
            "INSERT INTO stream_items VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "m5", "thread-auto", 1L, 1_751_500_000_000L, "text", "assistant",
                "Виїзд о 07:40.", null, null, 0L,
                null, null, null, null, null, 0L, null, null,
            ),
        )

        db.execSQL(
            "INSERT INTO memory_facts VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "f1", "Кава без цукру", "preference", byteArrayOf(7, -3, 42, 0, 127),
                0.87, 1_700_000_000_000L, 1_751_000_000_000L, 3L, "chat",
            ),
        )
        db.execSQL(
            "INSERT INTO memory_facts VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "f2", "Škoda Octavia 2014", "vehicle", byteArrayOf(1, 2, 3),
                0.99, 1_700_000_000_000L, 1_752_000_000_000L, 11L, "manual",
            ),
        )

        db.execSQL(
            "INSERT INTO saved_places VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "sp1", "Дім", "home", 50.4501, 30.5234, "вул. Хрещатик, 1", "код 4512",
                1_700_000_000_000L, 1_752_000_000_000L,
            ),
        )
        db.execSQL(
            "INSERT INTO saved_places VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "sp2", "Робота", "work", 50.4600, 30.5300, null, null,
                1_700_000_000_000L, 1_751_000_000_000L,
            ),
        )

        // The gazetteer: written through the v21 triggers, exactly as a fresh
        // v21 install would have.
        db.execSQL(
            "INSERT INTO poi_gazetteer VALUES (?,?,?,?,?,?,?)",
            arrayOf(1L, 50.4510, 30.5240, "Кавʼярня Львівська", "amenity", "cafe", 1_751_000_000_000L),
        )
        db.execSQL(
            "INSERT INTO poi_gazetteer VALUES (?,?,?,?,?,?,?)",
            arrayOf(2L, 50.4530, 30.5250, "АЗС ОККО", "amenity", "fuel", 1_751_000_100_000L),
        )
        db.execSQL(
            "INSERT INTO poi_gazetteer VALUES (?,?,?,?,?,?,?)",
            arrayOf(3L, 50.4600, 30.5300, "Аптека Подорожник", "amenity", "pharmacy", 1_751_000_200_000L),
        )

        db.execSQL(
            "INSERT INTO poi_cache VALUES (?,?,?,?,?,?,?,?)",
            arrayOf(99L, 50.45, 30.52, "Сільпо", "shop", "supermarket", "{}", 1_752_000_000_000L),
        )
        db.execSQL(
            "INSERT INTO spatial_layers VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "layer-1", "Укриття поруч", "phantom", "marker", "thread-now",
                """{"type":"Point","coordinates":[30.5234,50.4501]}""", "#ff0000", "shield",
                1_752_000_000_000L, null, 1L,
            ),
        )
        db.execSQL(
            "INSERT INTO life_log_entries VALUES (?,?,?,?,?,?)",
            arrayOf(
                "ll1", 1_752_000_000_000L, "movement", """{"from":"Дім","to":"Робота"}""",
                floatsToBlob(floatArrayOf(1f, 2f)), "авто",
            ),
        )
        db.execSQL(
            "INSERT INTO calendar_events VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "ev1", "Зустріч з лікарем", "взяти виписку", 1_752_100_000_000L,
                1_752_103_600_000L, 0L, "Поліклініка №3", 50.44, 30.51, "health", null,
                1_751_000_000_000L, 1_751_000_000_000L,
            ),
        )
        db.execSQL(
            "INSERT INTO tasks VALUES (?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "t1", "Купити ліки", null, 0L, 2L, 1_752_200_000_000L, "Особисте", 1L,
                1_751_000_000_000L, null,
            ),
        )
        db.execSQL(
            "INSERT INTO reminders VALUES (?,?,?,?,?,?,?)",
            arrayOf("r1", "Ліки о 9", 1_752_200_000_000L, 10L, "daily", 0L, 1_751_000_000_000L),
        )
        db.execSQL(
            "INSERT INTO decision_entries VALUES (?,?,?,?,?)",
            arrayOf("d1", 1_751_000_000_000L, "Змінити маршрут", "затор на мосту", "accepted"),
        )
        db.execSQL(
            "INSERT INTO pending_publish (verb, payload, enqueuedAt) VALUES (?,?,?)",
            arrayOf("decision_diary/create", """{"id":"d1"}""", 1_751_000_000_000L),
        )
        db.execSQL(
            "INSERT INTO pending_intents VALUES (?,?,?,?,?,?)",
            arrayOf(
                "pi1", "thread-now", """{"prompt":"погода"}""", 1_752_000_000_000L, 0L, null,
            ),
        )
        db.execSQL(
            "INSERT INTO patterns VALUES (?,?,?,?,?,?,?,?)",
            arrayOf(
                "pat1", "location", "Робота", "увімкнути тихий режим", 0.75,
                12L, 1_752_000_000_000L, 0L,
            ),
        )
        db.execSQL(
            "INSERT INTO call_screen_log VALUES (?,?,?,?,?,?,?,?)",
            arrayOf(
                "c1", "+380671234567", "Мама", "allow", "у контактах", "answered",
                1_752_000_000_000L, 1_752_000_010_000L,
            ),
        )
        db.execSQL(
            "INSERT INTO notification_digest VALUES (?,?,?,?,?,?,?)",
            arrayOf(
                "n1", "com.example.bank", "tx", "Списання", "•••• 4512 — 250 ₴",
                1_752_000_000_000L, 0L,
            ),
        )
    }

    // ---------------------------------------------------------------- utils

    private fun floatsToBlob(values: FloatArray): ByteArray =
        local.phantom.companion.core.data.lifelog.LifeLogConverters().fromFloatArray(values)!!

    private fun count(db: PhantomDatabase, table: String): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst(); it.getInt(0)
        }

    private fun scalar(db: PhantomDatabase, sql: String): String? =
        db.openHelper.writableDatabase.query(sql).use {
            it.moveToFirst(); it.getString(0)
        }

    private companion object {
        /**
         * Якір порядку для `searchInBox`. Цей набір перевіряє МІГРАЦІЮ, а не
         * ранжування: індекс мусить пережити оновлення й лишитися придатним
         * для запиту. Тому якір — просто Київ, і жодне тут твердження не
         * залежить від того, що ближче.
         */
        const val AT_LAT = 50.45
        const val AT_LON = 30.52

        /** cos²φ на широті Києва — той самий множник, що й у репозиторії. */
        val LON_SCALE_SQ = Math.cos(Math.toRadians(AT_LAT)).let { it * it }
    }
}
