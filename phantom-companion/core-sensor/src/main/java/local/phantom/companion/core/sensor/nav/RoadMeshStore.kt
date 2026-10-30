package local.phantom.companion.core.sensor.nav

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import kotlin.math.floor

/**
 * IRONCLAD-2 — the permanent road mesh. The routing world this device has
 * EARNED, kept forever.
 *
 * The failure this closes: [RoadTruth]'s tile cache is RAM-only, six tiles
 * deep, thirty minutes to live — the entire offline routing mesh (which is
 * also the evacuation fallback's road world and the shelter isochrone's
 * ground) evaporated on every process death and every half hour. Under a
 * real blackout the app held whatever it happened to fetch since the last
 * restart, which is to say: nearly nothing.
 *
 * This store makes the mesh MONOTONIC. Every Overpass tile the app ever
 * fetches is folded in; nothing expires (asphalt does not move — a stale
 * road beats no road in every tactical scenario this app exists for); and
 * [bulkImport] is the seam for country-scale packs generated off-device
 * (see docs/MASSIVE_CARTOGRAPHY.md — the phone must never parse a raw OSM
 * extract itself).
 *
 * Geometry: ways are sharded into [CELL_DEG] grid cells by bounding box —
 * one row per (cell, way), duplicates deduplicated on read by way id. A
 * cell query is an indexed IN-list; no R*Tree dependency, no Room, no new
 * libraries in the one module that has to keep working when everything
 * else is on fire.
 */
class RoadMeshStore(
    context: Context,
    databaseName: String = DB_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $WAYS(" +
                "cell INTEGER NOT NULL, " +
                "id INTEGER NOT NULL, " +
                "name TEXT, " +
                "oneway INTEGER NOT NULL, " +
                "pts TEXT NOT NULL, " +
                "tags TEXT, " +
                "PRIMARY KEY(cell, id))",
        )
        createTransitTables(db)
    }

    /**
     * Транспортний скелет — див. [RoadMeshTransit.kt] про те, чому колії
     * лежать ОКРЕМО від [WAYS], а не в них.
     */
    private fun createTransitTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $STOPS(" +
                "cell INTEGER NOT NULL, " +
                "id INTEGER NOT NULL, " +
                "name TEXT, " +
                "lat REAL NOT NULL, " +
                "lon REAL NOT NULL, " +
                "tags TEXT, " +
                "routes TEXT, " +
                "PRIMARY KEY(cell, id))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $RAILS(" +
                "cell INTEGER NOT NULL, " +
                "id INTEGER NOT NULL, " +
                "name TEXT, " +
                "pts TEXT NOT NULL, " +
                "tags TEXT, " +
                "PRIMARY KEY(cell, id))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $ROUTES(" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "mode TEXT NOT NULL, " +
                "ref TEXT, " +
                "name TEXT, " +
                "from_name TEXT, " +
                "to_name TEXT, " +
                "operator TEXT, " +
                "colour TEXT, " +
                "stops TEXT)",
        )
    }

    /**
     * v1 → v2: ways learn what they are.
     *
     * Additive, and deliberately not a rebuild: the mesh is the routing world
     * this device has EARNED over months of driving, and throwing it away to
     * gain a column would cost far more than the column is worth. Rows written
     * before this carry no tags and route exactly as they did; every tile
     * fetched afterwards folds its tags in.
     */
    /**
     * v2 → v3: мережа дізнається про транспорт.
     *
     * Так само додаванням, і з тієї самої причини, що й v1 → v2: дорожня
     * мережа — це світ, який пристрій ЗАРОБИВ, і перебудовувати його заради
     * трьох порожніх таблиць коштувало б більше, ніж вони варті. Старі рядки
     * лишаються рівно такими, як були; зупинки й колії приходять із наступним
     * пакетом.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE $WAYS ADD COLUMN tags TEXT") }
        }
        if (oldVersion < 3) {
            runCatching { createTransitTables(db) }
        }
    }

    /** Fold one fetched tile's ways into the permanent mesh. Idempotent. */
    /**
     * Скільки разів мережа мінялась. Кеш коридору ([CorridorGraphCache]) звіряє
     * саме це число: граф, зібраний до приходу нових тайлів, їх не знає, і
     * віддавати його після запису означало б вести людину по вчорашній карті.
     */
    /**
     * Скільки разів мережа мінялась — на весь процес, а не на цей примірник.
     *
     * Було полем примірника, і це тихо ламало саме той шлях, яким пакети
     * потрапляють у пристрій: [local.phantom.companion.feature.precision.RoadPackImporter]
     * (за назвою — модуль вище) відкриває ВЛАСНЕ сховище, вливає в нього
     * область і закриває. Кеш коридорів звіряється з `revision` того
     * примірника, що живе в контейнері, — а той не мінявся, бо писали не в
     * нього. Обидва примірники ведуть до одного файла, тож дані змінились, а
     * лічильник — ні.
     *
     * Наслідок був обмежений пʼятихвилинним віком запису в кеші, тобто не
     * вічний, — але «за кілька хвилин саме розсмокчеться» не є контрактом.
     */
    val revision: Long get() = REVISIONS.get()

    fun put(ways: List<RoadWay>) {
        if (ways.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (way in ways) insertWay(db, way)
            db.setTransactionSuccessful()
            // Мережа змінилась — усе, що зібране з неї раніше, застаріло.
            REVISIONS.incrementAndGet()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * The seam for MASSIVE packs: oblast- or country-scale way sets built
     * off-device. Batched so a million-way import neither holds one giant
     * transaction nor pays per-row commit costs.
     */
    fun bulkImport(ways: Iterable<RoadWay>, batchSize: Int = BULK_BATCH) {
        val db = writableDatabase
        var inBatch = 0
        db.beginTransaction()
        try {
            for (way in ways) {
                insertWay(db, way)
                if (++inBatch >= batchSize) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    db.beginTransaction()
                    inBatch = 0
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Ціла область щойно лягла в мережу. Не сказати про це кешу означає
        // вести людину вчорашньою картою по свіжих даних.
        REVISIONS.incrementAndGet()
    }

    /**
     * Спільний транзакційний шов для транспортних розширень
     * ([putStops], [putRails], [putRoutes]) — та сама пакетна дисципліна, що
     * й у [bulkImport], і той самий обовʼязок підняти [revision]: усе, що
     * зібране з мережі раніше, після запису застаріло.
     */
    internal fun <T> bulkWrite(items: Iterable<T>, batchSize: Int, write: (SQLiteDatabase, T) -> Unit) {
        val db = writableDatabase
        var inBatch = 0
        var wrote = false
        db.beginTransaction()
        try {
            for (item in items) {
                write(db, item)
                wrote = true
                if (++inBatch >= batchSize) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    db.beginTransaction()
                    inBatch = 0
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (wrote) REVISIONS.incrementAndGet()
    }

    /** Every stored way whose cell shard touches the bbox, deduplicated. */
    fun waysIn(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<RoadWay> {
        val cells = cellsCovering(minLat, minLon, maxLat, maxLon)
        if (cells.isEmpty()) return emptyList()
        val placeholders = cells.joinToString(",") { "?" }
        val args = Array(cells.size) { cells[it].toString() }
        val out = LinkedHashMap<Long, RoadWay>()
        readableDatabase.rawQuery(
            "SELECT id, name, oneway, pts, tags FROM ways WHERE cell IN ($placeholders)",
            args,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (out.containsKey(id)) continue
                val pts = RoadWayCodec.decodePoints(c.getString(3)) ?: continue
                out[id] = RoadWay(
                    id = id,
                    points = pts,
                    name = if (c.isNull(1)) null else c.getString(1),
                    oneway = c.getInt(2) != 0,
                    tags = if (c.isNull(4)) emptyMap() else RoadWayCodec.decodeTags(c.getString(4)),
                )
            }
        }
        return out.values.toList()
    }

    /** ПРОВИДЕЦЬ — which of [cells] already hold at least one way. */
    fun heldCells(cells: LongArray): LongArray {
        if (cells.isEmpty()) return LongArray(0)
        val held = HashSet<Long>()
        var i = 0
        while (i < cells.size) {
            val chunk = cells.copyOfRange(i, minOf(i + IN_CHUNK, cells.size))
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT DISTINCT cell FROM ways WHERE cell IN ($placeholders)",
                Array(chunk.size) { chunk[it].toString() },
            ).use { c -> while (c.moveToNext()) held.add(c.getLong(0)) }
            i += IN_CHUNK
        }
        return cells.filter { it in held }.toLongArray()
    }

    /** Distinct ways held — the size of the earned world, for the QA HUD. */
    fun wayCount(): Long =
        readableDatabase.rawQuery("SELECT COUNT(DISTINCT id) FROM ways", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    private fun insertWay(db: SQLiteDatabase, way: RoadWay) {
        if (way.points.size < 2) return
        var minLat = way.points[0].first
        var maxLat = minLat
        var minLon = way.points[0].second
        var maxLon = minLon
        for ((la, lo) in way.points) {
            if (la < minLat) minLat = la
            if (la > maxLat) maxLat = la
            if (lo < minLon) minLon = lo
            if (lo > maxLon) maxLon = lo
        }
        val pts = RoadWayCodec.encodePoints(way.points)
        val tags = RoadWayCodec.encodeTags(way.tags)
        for (cell in cellsCovering(minLat, minLon, maxLat, maxLon)) {
            val values = ContentValues(6).apply {
                put("cell", cell)
                put("id", way.id)
                put("name", way.name)
                put("oneway", if (way.oneway) 1 else 0)
                put("pts", pts)
                put("tags", tags)
            }
            if (tags.isNotEmpty()) {
                db.insertWithOnConflict("ways", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                continue
            }
            // A producer that carries no tags — the national bake, whose `ways`
            // table has no such column — must not blank what the device earned
            // from Overpass. REPLACE would: it rewrites the whole row. So the
            // tagless path inserts only when the row is new, and otherwise
            // refreshes the geometry with `tags` left out of the update.
            values.remove("tags")
            if (db.insertWithOnConflict("ways", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                db.update("ways", values, "cell=? AND id=?", arrayOf(cell.toString(), way.id.toString()))
            }
        }
    }

    companion object {
        /** Спільний на процес — див. [revision]. */
        private val REVISIONS = java.util.concurrent.atomic.AtomicLong(0L)

        const val DB_NAME = "phantom_road_mesh.db"

        /**
         * v3 == пакет несе транспортний скелет (`stops`/`rails`/`routes`).
         *
         * Підняття версії саме по собі вже щось робить, і це навмисно:
         * `packVerdict` у RoadPackImporter оголосить КОЖЕН наявний пакет
         * STALE_RECIPE, бо жоден із них зупинок не має. Це не косметика —
         * це рівно та скарга, якої не було, коли пекар навчився тегам
         * 15 серпня, а артефакти лишились старішими за ліки.
         */
        const val DB_VERSION = 3

        const val WAYS = "ways"
        const val STOPS = "stops"
        const val RAILS = "rails"
        const val ROUTES = "routes"
        /** Таблиця, якою пакет оголошує свій словник кодека. Лише в пакеті. */
        const val META = "meta"

        /** Same grid density as RoadTruth's live tiles (~2.2 km of latitude). */
        const val CELL_DEG = 0.02

        const val BULK_BATCH = 500

        /** SQLite bind-arg headroom for heldCells' IN-lists. */
        const val IN_CHUNK = 500

        /** A bbox may never explode into an unbounded cell list — a corrupt
         *  way spanning a continent shards into nothing rather than into a
         *  million rows. */
        const val MAX_COVER_CELLS = 1_024

        /** Grid cells covering a bbox, in one packed long per cell. Pure —
         *  unit-tested on the JVM without a database. */
        fun cellsCovering(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): LongArray {
            val latFrom = floor(minLat / CELL_DEG).toLong()
            val latTo = floor(maxLat / CELL_DEG).toLong()
            val lonFrom = floor(minLon / CELL_DEG).toLong()
            val lonTo = floor(maxLon / CELL_DEG).toLong()
            val count = (latTo - latFrom + 1) * (lonTo - lonFrom + 1)
            if (count <= 0 || count > MAX_COVER_CELLS) return LongArray(0)
            val out = LongArray(count.toInt())
            var i = 0
            for (la in latFrom..latTo) {
                for (lo in lonFrom..lonTo) {
                    out[i++] = (la shl 32) xor (lo and 0xFFFFFFFFL)
                }
            }
            return out
        }
    }
}

/**
 * Locale-proof, allocation-light way-geometry codec. Text on purpose: it is
 * inspectable with any sqlite3 shell in the field, and 1e-6° (~0.11 m) is
 * far below every consumer's noise floor.
 */
object RoadWayCodec {

    /** "lat,lon;lat,lon;…" with 6 decimals, Locale.US — a Ukrainian system
     *  locale writes decimal COMMAS, which would corrupt the format. */
    fun encodePoints(points: List<Pair<Double, Double>>): String {
        val sb = StringBuilder(points.size * 22)
        for ((i, p) in points.withIndex()) {
            if (i > 0) sb.append(';')
            sb.append(String.format(Locale.US, "%.6f,%.6f", p.first, p.second))
        }
        return sb.toString()
    }

    /**
     * Tags as `k=v|k=v`. Only the keys routing can act on are kept — the full
     * OSM tag set of a way is unbounded, and the mesh is already the largest
     * thing this device stores.
     */
    private val ROUTING_KEYS = listOf(
        "highway", "access", "foot", "vehicle", "motor_vehicle", "bicycle",
        "service", "surface", "maxspeed", "lit", "bridge", "tunnel",
        "junction", "sidewalk", "smoothness", "width", "incline",
        // Смуги. Офлайн їх не було ВЗАГАЛІ: GuidanceProgress.lanes наповнювався
        // лише з мережевого OSRM (OsrmClient читає intersections.lanes), тож
        // людина без мережі не бачила, у яку смугу перелаштуватись — саме тоді,
        // коли підказка потрібна найбільше.
        "lanes", "turn:lanes", "turn:lanes:forward", "turn:lanes:backward",
        // Напис на вказівнику: «поверніть на Житомир» замість «поверніть».
        "destination",
        // `motorroad=yes` — не клас дороги, а властивість: «правила як на
        // автомагістралі», без пішоходів. [RoadProfile.blocked] питає саме
        // його, і без ключа те правило не спрацьовувало ніколи.
        //
        // ЦЕЙ СПИСОК КЕРУЄ ЗАПИСОМ, А НЕ ЧИТАННЯМ, і саме тому правка тут
        // потрібна попри те, що [decodeTags] білого списку не знає. Телефон
        // не лише читає пакети — він САМ пише теги для тайлів, узятих з
        // Overpass (`AppContainer` -> [put] -> [insertWay] -> [encodeTags]).
        // Навчити ключа тільки піч означало б, що офлайн-пакет його несе, а
        // жива мережа під тим самим застосунком — ні.
        //
        // У хвіст, а не поруч з `access`: порядок оголошення вирішує байти,
        // тож дописане в кінець лишає взаємний порядок наявних пар незмінним
        // і пакет «до»/«після» різняться рівно однією парою.
        "motorroad",
        // БРІД. По країні `ford` стоїть на 25 848 обʼєктах; у спеченому пакеті
        // їх було НУЛЬ — не через складність, а через відсутність рядка в цьому
        // списку. Тобто дані лежали в тому самому екстракті, з якого пекли пакет.
        //
        // ЩО ЦЕЙ КЛЮЧ ДОЗВОЛЯЄ СКАЗАТИ І ЧОГО НЕ ДОЗВОЛЯЄ. Він дозволяє рівно
        // одне твердження: «на цьому шляху позначено брід». Він НЕ дозволяє
        // сказати «тут проїде» і не дозволяє сказати «тут не проїде». Глибини
        // OSM не несе (`depth` — сотні обʼєктів на країну), сезону не несе,
        // рівня води не несе, а рівень води — це те, що змінюється за годину.
        // Саме тому [RoadProfile] цього ключа НЕ читає: ані [RoadProfile.blocked],
        // ані [RoadProfile.costFactor] — жодного правила, жодного штрафу. Ціна
        // помилки тут та сама, що в кризі, де проєкт уже свідомо відмовився
        // обіцяти прохідність криги, і межа тримається та сама: показуємо факт
        // розмітки, рішення лишаємо людині на березі.
        //
        // Наслідок, за яким варто стежити: доданий ключ робить видимою прогалину
        // в УЖЕ РОЗДАНИХ пакетах — [RoadMeshTransit.vocabularyGap] назве `ford`
        // поіменно на кожному пакеті, спеченому до цієї правки. Це не поломка,
        // це той самий сторож, задля якого словник і заведено.
        "ford",
    )

    /**
     * Вертикальна риска у ЗНАЧЕННІ тега — і чому без цього смуги були б биті
     * від народження.
     *
     * Формат тримається на `k=v|k=v`, а `turn:lanes` записує смуги саме через
     * риску: `left|through;right`. Сире значення розірвало б рядок, і сусідній
     * тег став би сміттям. Тому старий [encodeTags] такі значення мовчки
     * ВИКИДАВ — тобто смуги не просто були відсутні, їм не було куди
     * поміститись, і додати їх у список ключів було б замало.
     *
     * Старі пакети цієї послідовності не містять, тож розекранування на них —
     * порожня дія: жодного пакета в чиємусь телефоні не зіпсовано.
     */
    private const val PIPE_ESCAPE = "%7C"

    /** Білий список у порядку оголошення — той самий, що вирішує байти. */
    fun routingKeys(): List<String> = ROUTING_KEYS

    fun encodeTags(tags: Map<String, String>): String {
        if (tags.isEmpty()) return ""
        val sb = StringBuilder()
        for (k in ROUTING_KEYS) {
            val v = tags[k] ?: continue
            if (v.isEmpty() || v.contains('=')) continue
            if (sb.isNotEmpty()) sb.append('|')
            sb.append(k).append('=').append(v.replace("|", PIPE_ESCAPE))
        }
        return sb.toString()
    }

    fun decodeTags(encoded: String): Map<String, String> {
        if (encoded.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (part in encoded.split('|')) {
            val eq = part.indexOf('=')
            if (eq <= 0 || eq == part.length - 1) continue
            out[part.substring(0, eq)] = part.substring(eq + 1).replace(PIPE_ESCAPE, "|")
        }
        return out
    }

    /** Null on any malformation — a corrupt row is dropped, never trusted. */
    fun decodePoints(encoded: String): List<Pair<Double, Double>>? {
        if (encoded.isEmpty()) return null
        val parts = encoded.split(';')
        if (parts.size < 2) return null
        val out = ArrayList<Pair<Double, Double>>(parts.size)
        for (part in parts) {
            val comma = part.indexOf(',')
            if (comma <= 0) return null
            val lat = parseOrNull(part.substring(0, comma)) ?: return null
            val lon = parseOrNull(part.substring(comma + 1)) ?: return null
            out.add(lat to lon)
        }
        return out
    }

    // Kotlin's toDoubleOrNull validates with an ICU regex first: with the map open that was
    // ~3.6 GB of native churn per 25 s (heapprofd, A52), the sawtooth in native heap.
    private fun parseOrNull(s: String): Double? =
        try {
            java.lang.Double.parseDouble(s).takeIf { it.isFinite() }
        } catch (_: NumberFormatException) {
            null
        }
}
