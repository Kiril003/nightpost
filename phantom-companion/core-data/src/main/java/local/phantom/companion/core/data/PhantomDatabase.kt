package local.phantom.companion.core.data

import android.content.Context
import android.util.Base64
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.sqlite.db.SupportSQLiteDatabase
import local.phantom.companion.core.data.diary.DecisionEntry
import local.phantom.companion.core.data.diary.DecisionEntryDao
import local.phantom.companion.core.data.diary.PendingPublish
import local.phantom.companion.core.data.diary.PendingPublishDao
import local.phantom.companion.core.data.pending.PendingIntentEntity
import local.phantom.companion.core.data.profile.PairedHostEntity
import local.phantom.companion.core.data.profile.ProfileConverters
import local.phantom.companion.core.data.profile.ProfileDao
import local.phantom.companion.core.data.profile.ProfileEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

/**
 * Top-level Room database for PHANTOM Companion v2.
 *
 * Encryption: the on-disk SQLite file is opened through SQLCipher's
 * [SupportOpenHelperFactory]. The 32-byte database key is generated
 * once per install and persisted in [EncryptedSharedPreferences],
 * which itself wraps the value in an AES-256-GCM key sealed by the
 * Android Keystore (StrongBox-backed on hardware that supports it).
 * The DB key never leaves the device, never appears in logs, and
 * survives `adb backup` only if the operator explicitly opts in.
 *
 * Why SQLCipher, not just EncryptedSharedPreferences for everything:
 * the profile store grows into per-profile journals, vault entries,
 * sensor batches and decision-tree replay buffers — all things that
 * benefit from real SQL semantics (joins, indexes, observers). Doing
 * that on top of a key/value blob would be a worse fit.
 *
 * The companion [openInMemory] helper is JVM-friendly and used by the
 * unit tests; it skips SQLCipher (no Keystore on Robolectric / pure
 * JVM) but exercises the same DAO surface so schema regressions are
 * caught at build time.
 */
@Database(
    entities = [
        ProfileEntity::class,
        PairedHostEntity::class,
        local.phantom.companion.core.data.vault.VaultEntity::class,
        DecisionEntry::class,
        PendingPublish::class,
        local.phantom.companion.core.data.stream.StreamItemEntity::class,
        local.phantom.companion.core.data.stream.ThreadMetaEntity::class,
        local.phantom.companion.core.data.notifications.NotificationDigestEntity::class,
        local.phantom.companion.core.data.patterns.PatternEntity::class,
        PendingIntentEntity::class,
        local.phantom.companion.core.data.poi.PoiEntity::class,
        local.phantom.companion.core.data.poi.PoiGazetteerEntity::class,
        local.phantom.companion.core.data.poi.PoiFtsEntity::class,
        local.phantom.companion.core.data.lifelog.LifeLogEntryEntity::class,
        local.phantom.companion.core.data.spatial.SpatialLayerEntity::class,
        local.phantom.companion.core.data.memory.MemoryFactEntity::class,
        local.phantom.companion.core.data.memory.MemoryEntityEntity::class,
        local.phantom.companion.core.data.calendar.CalendarEventEntity::class,
        local.phantom.companion.core.data.tasks.TaskEntity::class,
        local.phantom.companion.core.data.reminders.ReminderEntity::class,
        local.phantom.companion.core.data.places.SavedPlaceEntity::class,
        local.phantom.companion.core.data.places.DrawnLineEntity::class,
        local.phantom.companion.core.data.toolaudit.ToolAuditEntity::class,
    ],
    version = 29,
    exportSchema = true,
)
@TypeConverters(
    ProfileConverters::class,
    local.phantom.companion.core.data.poi.PoiConverters::class,
    local.phantom.companion.core.data.lifelog.LifeLogConverters::class
)
abstract class PhantomDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun vaultDao(): local.phantom.companion.core.data.vault.VaultDao
    abstract fun decisionEntryDao(): DecisionEntryDao
    abstract fun pendingPublishDao(): PendingPublishDao
    abstract fun streamItemDao(): local.phantom.companion.core.data.stream.StreamItemDao
    abstract fun threadMetaDao(): local.phantom.companion.core.data.stream.ThreadMetaDao
    abstract fun notificationDigestDao(): local.phantom.companion.core.data.notifications.NotificationDigestDao
    abstract fun patternDao(): local.phantom.companion.core.data.patterns.PatternDao
    abstract fun poiDao(): local.phantom.companion.core.data.poi.PoiDao
    abstract fun poiGazetteerDao(): local.phantom.companion.core.data.poi.PoiGazetteerDao
    abstract fun lifeLogDao(): local.phantom.companion.core.data.lifelog.LifeLogDao
    abstract fun spatialLayerDao(): local.phantom.companion.core.data.spatial.SpatialLayerDao
    abstract fun memoryFactDao(): local.phantom.companion.core.data.memory.MemoryFactDao
    abstract fun memoryEntityDao(): local.phantom.companion.core.data.memory.MemoryEntityDao
    abstract fun calendarEventDao(): local.phantom.companion.core.data.calendar.CalendarEventDao
    abstract fun taskDao(): local.phantom.companion.core.data.tasks.TaskDao
    abstract fun reminderDao(): local.phantom.companion.core.data.reminders.ReminderDao
    abstract fun savedPlaceDao(): local.phantom.companion.core.data.places.SavedPlaceDao
    abstract fun drawnLineDao(): local.phantom.companion.core.data.places.DrawnLineDao
    abstract fun toolAuditDao(): local.phantom.companion.core.data.toolaudit.ToolAuditDao

    companion object {

        /**
         * On-disk database file name. Versioning lives in the Room
         * `@Database(version = …)` annotation — bumping the version
         * + adding a Migration is the only sanctioned way to evolve
         * the schema.
         */
        const val DB_NAME: String = "phantom-data.db"

        private const val PREFS_FILE: String = "phantom_db_keys"
        private const val PREF_KEY: String = "phantom_db_key_v1_b64"
        private const val KEY_SIZE_BYTES: Int = 32

        @Volatile
        private var INSTANCE: PhantomDatabase? = null

        /**
         * Production builder. Lazy-initialised process-wide.
         * Idempotent across re-entrant calls (double-checked locking).
         */
        fun open(context: Context): PhantomDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun build(appContext: Context): PhantomDatabase {
            val key = loadOrCreateDatabaseKey(appContext)
            val factory = SupportOpenHelperFactory(key)
            // Note on downgrades: Room's default (no
            // `fallbackToDestructiveMigrationOnDowngrade(...)` call)
            // raises an exception when the on-disk schema version is
            // higher than the compiled one. That is the behaviour we
            // want — a downgraded schema is almost always a bug, and
            // the operator should see it surface as a crash on the
            // next launch instead of silent data loss.
            return Room.databaseBuilder(
                appContext,
                PhantomDatabase::class.java,
                DB_NAME,
            )
                .openHelperFactory(factory)
                .addMigrations(*ALL_MIGRATIONS)
                // No fallbackToDestructiveMigration(). The ladder below is
                // contiguous 1 → CURRENT_VERSION and MigrationLadderTest fails
                // the build if a version bump ever lands without its edge, so a
                // missing migration surfaces in CI rather than deleting the
                // operator's vault, chats and memory on their phone.
                .build()
        }

        /**
         * The one registered ladder. Keep it exhaustive: [MigrationLadderTest]
         * reads it back and asserts every edge from 1 to the newest exported
         * schema exists.
         */
        internal val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
                MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
                MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28,
                MIGRATION_28_29,
            )

        /**
         * Слід викликів інструментів доживає до наступного запуску.
         *
         * Досі він писався в `InMemoryDecisionDiary` — 500 записів у купі, що
         * вмирали разом із процесом; питання «що воно зробило й за чиїм словом»
         * не мало де дістати відповідь після перезапуску.
         *
         * Суто додаткова міграція: жодна наявна таблиця не змінює форми. Форма
         * колонок і ІМʼЯ індексу мусять збігтися з тим, що Room виводить із
         * [local.phantom.companion.core.data.toolaudit.ToolAuditEntity] (він
         * порівнює TableInfo при відкритті) — автоімʼя `index_<таблиця>_<колонка>`,
         * без DEFAULT.
         *
         * `tool_audit`, а не `decision_entries`: та таблиця належить особистому
         * журналу рішень оператора і не має до цього сліду стосунку.
         */
        internal val MIGRATION_25_26: Migration = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tool_audit` (`id` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `toolId` TEXT NOT NULL, " +
                        "`args` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
                        "`outputDigest` TEXT NOT NULL, `agentId` TEXT, " +
                        "`agentDepth` INTEGER NOT NULL, `risk` TEXT NOT NULL, " +
                        "`decision` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tool_audit_timestamp` " +
                        "ON `tool_audit` (`timestamp`)",
                )
            }
        }

        /**
         * Категорійний пошук нарешті має свій індекс.
         *
         * `byCategoryInBox` фільтрує за `subcategory`, а індексів було два —
         * `(lat, lon)` і `category`. Тож SQLite звужувала лише за широтою і
         * перебирала всю смугу вручну: у київській коробці це 52 745 рядків
         * і тимчасове B-дерево на сортування. Виміряно 12.09 на копії
         * справжнього корпусу: «аптека» 62,1 → 2,8 мс, «АЗС» 57,1 → 0,7 мс.
         *
         * Стояла 25→26 у своїй гілці, як і слід інструментів у сусідній: обидві
         * зайняли той самий номер незалежно одна від одної. Зведення розводить
         * їх по своїх сходинках — узяти одну означало б мовчки загубити другу,
         * і Room упав би вже на відкритті, звіряючи TableInfo.
         */
        internal val MIGRATION_26_27: Migration = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_poi_gazetteer_subcategory_lat_lon` " +
                        "ON `poi_gazetteer` (`subcategory`, `lat`, `lon`)",
                )
            }
        }

        /**
         * Памʼять про особу дістає дві речі, яких у неї не було: чи людина
         * факт підтвердила, і кого/чого він стосується.
         *
         * `confirmed` мусить мати DEFAULT у SQL і `defaultValue = "0"` в
         * сутності одночасно — Room звіряє TableInfo при відкритті й вважає
         * розбіжність у DEFAULT зміною схеми. Імена індексів автоматичні
         * (`index_<таблиця>_<колонка>`), інакше та сама звірка червоніє.
         *
         * Зворотне заповнення наприкінці — не косметика: рядки, які людина
         * сама продиктувала через `/fact`, уже підтверджені за походженням, і
         * без цього UPDATE вони б мовчки втратили якір у підказці після
         * оновлення застосунку.
         *
         * Номер 27→28, не 25→26: сусідні гілки (інструменти, POI-індекс) уже
         * зайняли 25→26 і 26→27 незалежно від цієї. Зведення розводить усі
         * три по своїх сходинках — див. коментар над MIGRATION_26_27.
         */
        /** Відсіювач дзвінків ніколи не міг бути звʼязаний системою (роль не запитувалась), тож його журнал пішов разом із ним. */
        internal val MIGRATION_28_29: Migration = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `call_screen_log`")
            }
        }

        internal val MIGRATION_27_28: Migration = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memory_facts` ADD COLUMN `confirmed` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `memory_facts` ADD COLUMN `entity_id` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_facts_entity_id` " +
                        "ON `memory_facts` (`entity_id`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_entities` (" +
                        "`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`name_key` TEXT NOT NULL, `aliases` TEXT NOT NULL, `peer_id` TEXT, " +
                        "`label` TEXT, `note` TEXT NOT NULL, `first_seen_ms` INTEGER NOT NULL, " +
                        "`last_seen_ms` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_entities_kind` ON `memory_entities` (`kind`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_entities_name_key` " +
                        "ON `memory_entities` (`name_key`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_entities_peer_id` " +
                        "ON `memory_entities` (`peer_id`)",
                )
                db.execSQL(
                    "UPDATE `memory_facts` SET `confirmed` = 1 " +
                        "WHERE `source` IN ('operator', 'tool.memory.save')",
                )
            }
        }

        /**
         * `trip_points` іде з диска: таблиця без писаря — це обіцянка без діла.
         *
         * Сутність, DAO і сховище точок виїзду прожили в дереві три тижні, не
         * маючи ЖОДНОГО викликача поза `core-data`: ані властивості в
         * `AppContainer`, ані екрана. Єдиний читач, що колись існував, вирізали
         * раніше за них. Робочі зупинки в продукті веде `RoutePlanner.waypoints`
         * (старт, проміжні, ціль) і показує смуга «Зупинки» на мапі — лишити
         * поруч другу таблицю тих самих точок означало б два джерела правди,
         * які розійдуться на першій же перебудові маршруту.
         *
         * DROP, а не тихе зняття сутності з v24: збірки з v24 уже існують, і
         * прибрати таблицю, не піднявши версію, дало б «Room cannot verify the
         * data integrity» на кожному запуску. Індекс падає разом із таблицею.
         */
        internal val MIGRATION_24_25: Migration = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `trip_points`")
            }
        }

        /**
         * The gazetteer learns what places *are*, not just what they are called.
         *
         * `keywords` carries the OSM tags rendered into words a person types
         * ([local.phantom.companion.core.data.poi.PoiVocabulary]) and joins the
         * FTS index; `tags` carries the raw tags so the dossier has hours, a
         * phone and a floor with no network. The virtual table has to be rebuilt
         * rather than altered — FTS4 has no ADD COLUMN — so its triggers are
         * dropped with it and re-declared to match what Room now expects. The
         * repopulate at the end is what keeps an external-content index from
         * silently returning rows that no longer exist.
         */
        /**
         * Chasm Wave 2 §3 Proposal 2 — `stream_items.turn_id` lets the
         * renderer group items into Доручення blocks past a cold launch.
         * The in-memory field (`StreamItem.turnId`) has existed since Wave
         * 0; this is only the disk side catching up. Nullable, no DEFAULT —
         * same shape as MIGRATION_2_3 — so every pre-migration row simply
         * reads back as `turnId = null` (renders flat/ungrouped) rather
         * than being back-filled with a turn it never had.
         */
        /**
         * Намальовані лінії доходять до диска; `trip_points` — ні.
         *
         * Тіло лишається дослівним, бо на пристроях із v23 воно вже
         * відпрацювало: переписати минулу сходинку означало б, що дві збірки
         * розходяться в тому, яка база в них на диску. `trip_points` тут
         * створюється й одразу по тому дропається в [MIGRATION_24_25] — писаря
         * вона так і не дістала (див. коментар там).
         *
         * Форма колонок і ІМЕНА індексів мусять збігтися з тим, що Room
         * виводить із сутностей (він порівнює TableInfo при відкритті): без
         * DEFAULT, автоімена `index_<таблиця>_<колонка>`. Наявні таблиці тут
         * не чіпаються — міграція лише додає, тому мітки й місця цілі.
         */
        internal val MIGRATION_23_24: Migration = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trip_points` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ord` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, " +
                        "`label` TEXT, `created_ms` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_points_ord` ON `trip_points` (`ord`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drawn_lines` (`id` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, `vertices` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_drawn_lines_created_at` ON `drawn_lines` (`created_at`)")
            }
        }

        internal val MIGRATION_22_23: Migration = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stream_items` ADD COLUMN `turn_id` TEXT")
            }
        }

        internal val MIGRATION_21_22: Migration = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `poi_gazetteer` ADD COLUMN `keywords` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `poi_gazetteer` ADD COLUMN `tags` TEXT NOT NULL DEFAULT '{}'")
                for (event in FTS_SYNC_EVENTS) {
                    db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_poi_fts_$event")
                }
                db.execSQL("DROP TABLE IF EXISTS `poi_fts`")
                db.execSQL(POI_FTS_CREATE)
                for (sql in POI_FTS_TRIGGERS) db.execSQL(sql)
                db.execSQL(
                    "INSERT INTO `poi_fts`(`docid`, `name`, `keywords`) " +
                        "SELECT `rowid`, `name`, `keywords` FROM `poi_gazetteer`",
                )
            }
        }

        private val FTS_SYNC_EVENTS =
            listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT")

        private const val POI_FTS_CREATE =
            "CREATE VIRTUAL TABLE IF NOT EXISTS `poi_fts` USING FTS4(" +
                "`name` TEXT NOT NULL, `keywords` TEXT NOT NULL, " +
                "tokenize=unicode61, content=`poi_gazetteer`)"

        private val POI_FTS_TRIGGERS = listOf(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_poi_fts_BEFORE_UPDATE " +
                "BEFORE UPDATE ON `poi_gazetteer` BEGIN " +
                "DELETE FROM `poi_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_poi_fts_BEFORE_DELETE " +
                "BEFORE DELETE ON `poi_gazetteer` BEGIN " +
                "DELETE FROM `poi_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_poi_fts_AFTER_UPDATE " +
                "AFTER UPDATE ON `poi_gazetteer` BEGIN " +
                "INSERT INTO `poi_fts`(`docid`, `name`, `keywords`) " +
                "VALUES (NEW.`rowid`, NEW.`name`, NEW.`keywords`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_poi_fts_AFTER_INSERT " +
                "AFTER INSERT ON `poi_gazetteer` BEGIN " +
                "INSERT INTO `poi_fts`(`docid`, `name`, `keywords`) " +
                "VALUES (NEW.`rowid`, NEW.`name`, NEW.`keywords`); END",
        )

        /**
         * The vault stops storing secrets in the clear. No shape change — the
         * `value` column still holds TEXT — but every existing row is sealed
         * with the Keystore key on the way through, so the plaintext that was
         * sitting inside the SQLCipher file is gone after this runs once.
         */
        internal val MIGRATION_20_21: Migration = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                local.phantom.companion.core.data.vault.reencryptLegacyValues(
                    db,
                    local.phantom.companion.core.data.vault.VaultCipher.deviceDefault(),
                )
            }
        }

        /**
         * Phase 2-B-E — the vault lands. Reconstructed from the exported
         * `2.json` after the fact: v1 and v2 both shipped, but the ladder used
         * to start at 3 and let the destructive fallback swallow the gap.
         */
        internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_entries` (`id` TEXT NOT NULL, " +
                        "`key` TEXT NOT NULL, `value` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`metadata` TEXT, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /**
         * companion-v2-phase-0 — pairing splits the single `base_url` into a
         * LAN address plus an optional relay. RENAME COLUMN keeps the rows and
         * the foreign key intact; a rebuild would have to re-declare the FK by
         * hand for no gain.
         */
        internal val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `paired_hosts` RENAME COLUMN `base_url` TO `lan_url`")
                db.execSQL("ALTER TABLE `paired_hosts` ADD COLUMN `relay_url` TEXT")
            }
        }

        /**
         * Phase 1.5-D — Decision Diary lands a new `decision_entries` table
         * alongside profiles + vault. Pure additive migration: no existing
         * table changes shape, so the safe path is a single `CREATE TABLE
         * IF NOT EXISTS`. Schema is mirrored from [DecisionEntry] — keep the
         * column names and types in sync if the entity ever evolves (Room
         * verifies this against the exported `4.json` schema at compile time).
         */
        internal val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS decision_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        createdAt INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        context TEXT NOT NULL,
                        status TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Phase 3-D3 — `pending_publish` outbound queue for
         * `decision_diary/<verb>` envelopes that couldn't be sent at
         * write time (link offline, send rejected, etc.). Pure
         * additive: existing tables untouched, single new table mirrors
         * [PendingPublish]'s schema. The drainer iterates by ascending
         * rowId so FIFO order is preserved across reconnects.
         */
        internal val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_publish (
                        rowId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        verb TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        enqueuedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Phase 2.5 / W2-1 — Stream history persistence. Pure additive
         * migration: a new `stream_items` table mirrors
         * [local.phantom.companion.core.data.stream.StreamItemEntity]
         * exactly (Room verifies the shape against the exported
         * `6.json` schema at compile time). Two indexes — the per-
         * thread render path uses (thread_id, seq_no) for ordering;
         * the W2-5 cleanup TTL path uses created_at.
         */
        internal val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stream_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        thread_id TEXT NOT NULL,
                        seq_no INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        role TEXT,
                        text_content TEXT,
                        widget_type TEXT,
                        widget_payload TEXT,
                        streaming INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_stream_thread_seq ON stream_items(thread_id, seq_no)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_stream_created_at ON stream_items(created_at)",
                )
            }
        }

        /**
         * Phase 2.5 / W2-4 — `threads_meta` table for the ThreadRail.
         * Pure additive: existing tables unchanged. The default
         * «Зараз» row is inserted by ThreadMetaRepository.ensureDefault
         * on first launch (not in the migration so the value lives
         * in code where the test can pin it).
         */
        internal val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS threads_meta (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        last_active_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Phase 3 W4-5 — `notification_digest` table for the daily
         * 9am digest queue. Pure additive: existing tables untouched.
         * Schema mirrors
         * [local.phantom.companion.core.data.notifications.NotificationDigestEntity]
         * exactly (Room verifies against the exported `8.json` schema
         * at compile time). Two indexes — the digest read path uses
         * (delivered_at, package_name) to slice the 24h window grouped
         * by sender; the cleanup path uses delivered to prune already-
         * surfaced rows from the older-than-7-days tail.
         */
        /**
         * Phase 3 W4-6 — `call_screen_log` table for the
         * CallScreeningService policy decisions + operator acks.
         * Pure additive: existing tables untouched. Schema mirrors
         * [local.phantom.companion.core.data.calls.CallLogEntry]
         * exactly (Room verifies against the exported `9.json`
         * schema at compile time).
         */
        /**
         * Phase 3 W4-7 — `patterns` table for the Tasker pattern
         * detector. Pure additive: existing tables untouched.
         * Schema mirrors
         * [local.phantom.companion.core.data.patterns.PatternEntity]
         * exactly (Room verifies against the exported `10.json`
         * schema at compile time). Two indexes — the detector's
         * upsert path uses (trigger_kind, trigger_value) UNIQUE for
         * the duplicate-bucket lookup; the staleness pass uses
         * (last_observed_at) for the rolling-7d window cleanup.
         */
        /**
         * P4-3-5 — Symbiont Diary foundation. Adds context stamps to
         * `stream_items` so every chat moment carries where/when/what
         * state/what mood it landed in, plus a pin flag and a CSV tags
         * column for the upcoming facet UI. Pure additive; existing
         * rows keep nulls (`pinned` defaults to 0 to stay falsy).
         */
        internal val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stream_items ADD COLUMN location_lat REAL")
                db.execSQL("ALTER TABLE stream_items ADD COLUMN location_lon REAL")
                db.execSQL("ALTER TABLE stream_items ADD COLUMN place_name TEXT")
                db.execSQL("ALTER TABLE stream_items ADD COLUMN phantom_state TEXT")
                db.execSQL("ALTER TABLE stream_items ADD COLUMN emotion_tag TEXT")
                db.execSQL("ALTER TABLE stream_items ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stream_items ADD COLUMN tags TEXT")
            }
        }

        /**
         * Phase 3.B — `pending_intents` table for the offline AI intent queue.
         * Pure additive: existing tables untouched. Schema mirrors
         * [local.phantom.companion.core.data.pending.PendingIntentEntity]
         * exactly (Room verifies against the exported `12.json` schema at
         * compile time). Rows are ordered by [createdAtMs] ASC so the drain
         * worker replays intents in FIFO order across reconnects.
         */
        internal val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_intents (
                        id TEXT NOT NULL PRIMARY KEY,
                        threadId TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        lastErrorMessage TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Phase A1 — `poi_cache` table for high-fidelity POI density.
         * Pure additive: existing tables untouched. Schema mirrors
         * [local.phantom.companion.core.data.poi.PoiEntity] exactly.
         * Two spatial indexes on (lat, lon) and category for fast
         * bounding-box queries during map panning.
         */
        internal val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS poi_cache (
                        id INTEGER NOT NULL PRIMARY KEY,
                        lat REAL NOT NULL,
                        lon REAL NOT NULL,
                        name TEXT NOT NULL,
                        category TEXT NOT NULL,
                        subcategory TEXT,
                        tags TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_coords ON poi_cache(lat, lon)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_poi_category ON poi_cache(category)")
            }
        }

        internal val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS patterns (
                        id TEXT NOT NULL PRIMARY KEY,
                        trigger_kind TEXT NOT NULL,
                        trigger_value TEXT NOT NULL,
                        suggested_action TEXT NOT NULL,
                        confidence_score REAL NOT NULL,
                        occurrence_count INTEGER NOT NULL DEFAULT 0,
                        last_observed_at INTEGER NOT NULL,
                        suppressed INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_patterns_trigger " +
                        "ON patterns(trigger_kind, trigger_value)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_patterns_last_observed " +
                        "ON patterns(last_observed_at)",
                )
            }
        }

        internal val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS call_screen_log (
                        id TEXT NOT NULL PRIMARY KEY,
                        caller_id TEXT,
                        label TEXT,
                        decision TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        operator_action TEXT,
                        decided_at INTEGER NOT NULL,
                        operator_acted_at INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_call_screen_decided_at " +
                        "ON call_screen_log(decided_at)",
                )
            }
        }

        internal val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notification_digest (
                        id TEXT NOT NULL PRIMARY KEY,
                        package_name TEXT NOT NULL,
                        channel_id TEXT,
                        title TEXT NOT NULL,
                        redacted_text TEXT NOT NULL,
                        delivered_at INTEGER NOT NULL,
                        delivered INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_notif_digest_window " +
                        "ON notification_digest(delivered_at, package_name)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_notif_digest_delivered " +
                        "ON notification_digest(delivered)",
                )
            }
        }

        /**
         * In-memory variant for unit tests. Bypasses SQLCipher (the
         * Robolectric / JVM layer cannot speak to the Android
         * Keystore) so the DAO surface can still be exercised
         * without requiring an instrumented test runner.
         *
         * Production code should never call this — the @VisibleForTesting
         * annotation makes that audit-friendly.
         */
        @VisibleForTesting
        fun openInMemory(context: Context): PhantomDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                PhantomDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()

        /**
         * Read the per-install DB key from
         * EncryptedSharedPreferences, generating + sealing one if
         * the file is fresh. The byte array returned is the raw key
         * SQLCipher expects (32 bytes); base64 only exists at the
         * persistence layer.
         */
        private fun loadOrCreateDatabaseKey(appContext: Context): ByteArray {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val cached = prefs.getString(PREF_KEY, null)
            if (cached != null) {
                return Base64.decode(cached, Base64.NO_WRAP)
            }
            val fresh = ByteArray(KEY_SIZE_BYTES).also(::fillRandom)
            val encoded = Base64.encodeToString(fresh, Base64.NO_WRAP)
            prefs.edit().putString(PREF_KEY, encoded).apply()
            return fresh
        }

        private fun fillRandom(buffer: ByteArray) {
            // SecureRandom on Android is seeded from /dev/urandom + the
            // OpenSSL PRNG — explicit instantiation here documents the
            // intent (we want the system-provided CSPRNG, not a JVM
            // SecureRandom that might fall through to "SHA1PRNG").
            SecureRandom().nextBytes(buffer)
        }

        internal val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS life_log_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestamp_ms INTEGER NOT NULL,
                        event_type TEXT NOT NULL,
                        event_data TEXT NOT NULL,
                        embedding_blob BLOB NOT NULL,
                        tags TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        /** R2.5 — spaces: threads_meta gains a `kind` discriminator
         *  (task | person | device | system). Additive column with a
         *  default, so existing rows read back as "task". */
        internal val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE threads_meta ADD COLUMN kind TEXT NOT NULL DEFAULT 'task'",
                )
            }
        }

        /** R2.7 — History V3: per-item MiniLM embedding for vector search.
         *  Additive nullable BLOB; back-filled lazily as items finalize. */
        internal val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stream_items ADD COLUMN embedding BLOB")
            }
        }

        /**
         * R4.1 — the spatial spine: `spatial_layers`, the one personal +
         * live-world layer table the phantom pushes onto the map. Pure
         * additive: existing tables untouched. Schema mirrors
         * [local.phantom.companion.core.data.spatial.SpatialLayerEntity]
         * exactly (Room verifies against the exported `17.json` schema at
         * compile time). Indexes serve the three UI read paths — per-thread
         * grouping, recency ordering, and the TTL sweep on expires_at.
         */
        /**
         * HIGHWAY — the offline gazetteer + its FTS4 index.
         *
         * FTS4, not FTS5: Android's system SQLite does not compile the FTS5
         * module in (`no such module: fts5`, verified on the SM-A566B). The
         * index is EXTERNAL-CONTENT (`content=poi_gazetteer`), so it stores
         * terms only and the rows stay in the real table.
         *
         * The SQL below must match byte-for-byte what Room derives from the
         * entities — it compares TableInfo on open and throws "Migration didn't
         * properly handle" otherwise. It is copied from the generated
         * `schemas/…/18.json`, not written by hand.
         */
        internal val MIGRATION_17_18: Migration = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `poi_gazetteer` (
                        `id` INTEGER NOT NULL,
                        `lat` REAL NOT NULL,
                        `lon` REAL NOT NULL,
                        `name` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `subcategory` TEXT,
                        `seenAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_poi_gazetteer_lat_lon` " +
                        "ON `poi_gazetteer` (`lat`, `lon`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_poi_gazetteer_category` " +
                        "ON `poi_gazetteer` (`category`)",
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `poi_fts` USING FTS4(" +
                        "`name` TEXT NOT NULL, tokenize=unicode61, content=`poi_gazetteer`)",
                )
            }
        }

        /** Tier 2 long-term memory. Pure additive; SQL mirrors MemoryFactEntity + its two indices. */
        internal val MIGRATION_18_19: Migration = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_facts` (
                        `id` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `embedding` BLOB NOT NULL,
                        `confidence` REAL NOT NULL,
                        `first_seen_ms` INTEGER NOT NULL,
                        `last_confirmed_ms` INTEGER NOT NULL,
                        `reinforce_count` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_facts_category` ON `memory_facts` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_facts_last_confirmed_ms` ON `memory_facts` (`last_confirmed_ms`)")
            }
        }

        /**
         * DATA-SYSTEMS — the typed stores behind the chat widgets: an advanced
         * local event calendar, tasks/checklists, time-based reminders, and the
         * operator's personal saved places. Pure additive; the four CREATE TABLE
         * + index blocks mirror their entities byte-for-byte (copied from the
         * generated `20.json`, not hand-written).
         */
        internal val MIGRATION_19_20: Migration = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calendar_events` (`id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `notes` TEXT, `start_ms` INTEGER NOT NULL, " +
                        "`end_ms` INTEGER, `all_day` INTEGER NOT NULL, `location` TEXT, " +
                        "`lat` REAL, `lon` REAL, `category` TEXT NOT NULL, `rrule` TEXT, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_events_start_ms` ON `calendar_events` (`start_ms`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_events_category` ON `calendar_events` (`category`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `notes` TEXT, `done` INTEGER NOT NULL, " +
                        "`priority` INTEGER NOT NULL, `due_ms` INTEGER, `list_name` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`completed_at` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_list_name` ON `tasks` (`list_name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_done` ON `tasks` (`done`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_due_ms` ON `tasks` (`due_ms`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `trigger_at_ms` INTEGER NOT NULL, " +
                        "`lead_minutes` INTEGER NOT NULL, `recurrence` TEXT, " +
                        "`fired` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_trigger_at_ms` ON `reminders` (`trigger_at_ms`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_fired` ON `reminders` (`fired`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_places` (`id` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, `category` TEXT NOT NULL, `lat` REAL NOT NULL, " +
                        "`lon` REAL NOT NULL, `address` TEXT, `note` TEXT, " +
                        "`created_at` INTEGER NOT NULL, `last_used_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_places_category` ON `saved_places` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_places_lat_lon` ON `saved_places` (`lat`, `lon`)")
            }
        }

        internal val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Column shapes + index NAMES must match exactly what Room
                // derives from SpatialLayerEntity (Room compares TableInfo on
                // open): auto-generated `index_<table>_<col>` names, and NO
                // DEFAULT on `visible` (the entity declares none — inserts
                // always supply it). A mismatch here throws
                // "Migration didn't properly handle" on first launch.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS spatial_layers (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        origin TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        owner_thread_id TEXT,
                        geometry_json TEXT NOT NULL,
                        color TEXT,
                        glyph TEXT,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER,
                        visible INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spatial_layers_owner_thread_id ON spatial_layers(owner_thread_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spatial_layers_created_at ON spatial_layers(created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_spatial_layers_expires_at ON spatial_layers(expires_at)")
            }
        }
    }
}
