package local.phantom.companion.feature.precision

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.core.sensor.nav.RailLine
import local.phantom.companion.core.sensor.nav.RoadMeshStore
import local.phantom.companion.core.sensor.nav.RoadWay
import local.phantom.companion.core.sensor.nav.RoadWayCodec
import local.phantom.companion.core.sensor.nav.TransitCodec
import local.phantom.companion.core.sensor.nav.TransitRoute
import local.phantom.companion.core.sensor.nav.TransitStop
import local.phantom.companion.core.sensor.nav.putRails
import local.phantom.companion.core.sensor.nav.putRoutes
import local.phantom.companion.core.sensor.nav.putStops
import java.io.File

/**
 * ОФЛАЙН-ДОРОГИ — приймальна сторона дорожнього пакета на пристрої.
 *
 * Причина існування: без інтернету прокладання маршруту працює лише там, де
 * дорожню мережу вже колись підтягнули з мережі (Overpass), а Overpass під РЕБ
 * недосяжний саме тоді, коли він найпотрібніший. Дорожній пакет — це вся
 * область/країна доріг, спечена офлайн (див. `~/phantom_ai/road-mesh-bake` та
 * docs/MASSIVE_CARTOGRAPHY.md §2), яку користувач разово вносить у застосунок.
 * Після імпорту `LocalRouter` має повний граф і прокладає маршрут без жодного
 * сервера й без жодного налаштування.
 *
 * **Межа власності.** Цей імпортер НЕ змінює жодного файлу `:core-sensor` чи
 * `AppContainer`. Він лише ВИКЛИКАЄ публічний, спроєктований під це шов —
 * [RoadMeshStore.bulkImport] — який чужа сесія написала саме для країнових
 * пакетів. Пакет має ту саму схему, що й сховище (`ways(cell,id,name,oneway,
 * pts[,tags])`); ми читаємо його як звичайний SQLite і транзакційно вливаємо у
 * постійну мережу пристрою.
 *
 * **Змішаний світ.** Пекар (`~/phantom_ai/road-mesh-bake/mesh_bake.py`) уміє
 * класти теги з 15 серпня 2026, але кожен пакет, спечений до того, їх не має —
 * а таких на дисках і на пристроях більшість. Тож пакет може мати колонку або
 * не мати, і імпортер приймає обидва: з нею дорога приходить зі
 * своїм класом, покриттям і обмеженням швидкості; без неї — рівно як досі,
 * сама геометрія. Друге не гірше за вчорашнє, але й не краще, і [Result.Success]
 * каже, що саме внесено.
 */
/**
 * Що сказати про внесений пакет — вирок за версією рецепта, не за симптомом.
 *
 * ЧОМУ ЦЕ ОКРЕМА ФУНКЦІЯ. 15 серпня 2026 о 19:18 пекар навчився класти теги
 * доріг. Найновіший артефакт на дисках спечено того ж дня о 17:19 — на дві
 * години раніше; національний — на три тижні. Ліки написали, і **ніхто не
 * перезапустив те, що виробляє річ, якою користуються люди**. Нічого не
 * почервоніло, бо втрата тегів нічого не ламає: вона мовчки знижує клас, і
 * маршрут просто йде по голій довжині, а час лишається пласким здогадом.
 *
 * Виявили це виміром через півтора місяця. Відтепер про це каже сам продукт,
 * у мить внесення, вголос — і каже ПРИЧИНУ («спечено старим рецептом»), а не
 * лише наслідок («без тегів»), бо причина підказує дію: перепекти.
 */
internal enum class PackVerdict {
    /** Рецепт свіжий: пакет несе все, що пристрій уміє читати. */
    FRESH,

    /** Пакет спечено рецептом, старішим за той, що вміє пристрій. */
    STALE_RECIPE,

    /** Версії не оголошує, тегів не має — найстаріші пакети. */
    UNVERSIONED,
}

/**
 * @param packVersion `PRAGMA user_version` пакета, 0 — не оголошує.
 * @param appVersion  що вміє читати пристрій — [RoadMeshStore.DB_VERSION].
 */
/**
 * Яке саме речення сказати про рецепт пакета.
 *
 * ЧОМУ ЦЕ НЕ ОДИН РЯДОК НА ВЕСЬ [PackVerdict.STALE_RECIPE]. Підняття
 * [RoadMeshStore.DB_VERSION] до 3 робить старим КОЖЕН пакет, що існує, —
 * зокрема ті, що теги доріг МАЮТЬ. Сказати їм «класів доріг і обмежень
 * швидкості в ньому немає» означало б назвати хибну причину, а причина тут
 * і є вся цінність повідомлення: вона підказує дію. Тому речення обирає
 * версія пакета, тобто те, чого йому справді бракує.
 */
internal fun recipeSentence(verdict: PackVerdict, packVersion: Int): Int = when (verdict) {
    PackVerdict.FRESH -> R.string.roadpack_done_tagged
    PackVerdict.UNVERSIONED -> R.string.roadpack_done_tagless
    PackVerdict.STALE_RECIPE ->
        if (packVersion >= 2) R.string.roadpack_done_no_transit
        else R.string.roadpack_done_stale_recipe
}

internal fun packVerdict(packVersion: Int, hasTags: Boolean, appVersion: Int): PackVerdict = when {
    packVersion <= 0 && !hasTags -> PackVerdict.UNVERSIONED
    packVersion < appVersion -> PackVerdict.STALE_RECIPE
    else -> PackVerdict.FRESH
}

object RoadPackImporter {

    /**
     * Найновіший `PRAGMA user_version`, чию форму `ways` цей код звірив
     * рукою й знає, що вона не змінилась: v1 — база; v2 (mesh_bake.py)
     * додав колонку `tags`; v3 (05.09.2026, транспортний шар) додав окремі
     * таблиці `stops`/`rails`/`routes`/`meta` — `ways` та сама. Пакет
     * `kyiv-v3.db` власника несе саме 3.
     *
     * Це НЕ [local.phantom.companion.core.sensor.nav.RoadMeshStore.DB_VERSION]:
     * та константа рахує міграції ВЛАСНОЇ схеми пристрою і з форматом
     * зовнішнього пакета більше не збігається відтоді, як пекар пішов далі
     * (v3), а сховище — ні. Змішати їх — це і є спосіб, яким «пакет новіший
     * за пристрій» мовчки перетворюється або на хибну відмову (STALE), або
     * (тут) на сліпу довіру до формату, якого ніхто не перевіряв.
     *
     * Той, хто наступного разу змінить ФОРМУ `ways` в mesh_bake.py, мусить
     * підняти це число — інакше застосунок мовчки прочитає її як стару,
     * замість голосної відмови нижче.
     */
    internal const val MAX_KNOWN_PACK_FORMAT = 3

    sealed interface Result {
        /** [hasTags] — чи ніс пакет теги доріг (клас, покриття, `maxspeed`).
         *  Без них маршрут по цих дорогах прокладається лише по довжині, а час
         *  лишається пласким припущенням. */
        data class Success(
            val wayCount: Int,
            val hasTags: Boolean = false,
            /**
             * `PRAGMA user_version` пакета — яким рецептом його спекли.
             *
             * Окремо від [hasTags], хоч сьогодні вони й збігаються, бо кажуть
             * різне: [hasTags] — це симптом, версія — причина. Коли рецепт
             * навчиться ще чогось (v3), пакет із тегами, але старий, матиме
             * `hasTags = true` і при цьому не нестиме нового; лише версія
             * помітить це, не чекаючи, поки хтось зміряє наслідки.
             *
             * 0 — пакет версії не оголошує взагалі.
             */
            val formatVersion: Int = 0,
            /**
             * Транспортний скелет пакета. Нулі означають «пакет цього не
             * ніс», і це РІЗНЕ з «тут транспорту немає»: пакети до v3 не
             * мали куди його класти — пічка фільтрувала `w/highway` першим
             * кроком, а зупинка є вузлом.
             */
            val stopCount: Int = 0,
            val railCount: Int = 0,
            val routeCount: Int = 0,
            /**
             * Ключі, на які пристрій уміє діяти, а пакет їх не несе.
             *
             * Це НЕ відмова: дані внесено повністю. Це попередження того
             * самого класу, що вже тричі коштував тижнів, — правило на
             * телефоні є, даних під нього немає, і ніщо не червоніє. Тепер
             * воно називає ключ поіменно в мить внесення.
             */
            val missingKeys: List<String> = emptyList(),
        ) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Внести дорожній пакет із [uri] (обраний системним провідником файлів) у
     * постійну дорожню мережу. Важка робота — на IO. Повертає чесний результат:
     * скільки доріг влито, або причину відмови.
     */
    suspend fun importFrom(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        // SQLite потребує файлового шляху, а SAF дає content:// — тож спершу
        // копіюємо вміст у кеш. Тимчасовий файл прибираємо в будь-якому випадку.
        val temp = File(context.cacheDir, "roadpack_import.db")
        try {
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { input.copyTo(it) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            if (!copied || temp.length() == 0L) {
                return@withContext Result.Failure("Не вдалося прочитати файл пакета.")
            }
            importFromPath(context, temp.path)
        } catch (t: Throwable) {
            Result.Failure(t.message ?: "Помилка імпорту.")
        } finally {
            temp.delete()
        }
    }

    /**
     * Внести дорожній пакет, який уже лежить локальним файлом — шов для
     * мережевого джерела (ПК через симбіот-лінк), а не системного провідника.
     *
     * На відміну від [importFrom], тут немає SAF-копіювання: [file] уже
     * пройшов завантаження й перевірку хеша (RoadPackLinkClient) до виклику
     * цього методу, тож другого повного проходу гігабайтного файлу не
     * платимо. [deleteAfter] прибирає файл по завершенню в будь-якому
     * випадку — виклик очікує тимчасовий файл, не оригінал користувача.
     */
    suspend fun importFromFile(
        context: Context,
        file: File,
        deleteAfter: Boolean = true,
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.Failure("Файл пакета порожній або відсутній.")
            }
            importFromPath(context, file.path)
        } catch (t: Throwable) {
            Result.Failure(t.message ?: "Помилка імпорту.")
        } finally {
            if (deleteAfter) file.delete()
        }
    }

    /** Спільне тіло [importFrom]/[importFromFile]: файл уже лежить локально
     *  за [path] — відкрити як SQLite, влити в [RoadMeshStore]. Той самий
     *  транзакційний шов, що й у чужій сесії: сховище САМЕ перераховує
     *  клітинки з геометрії, тож формат клітинок пакета неважливий — потрібні
     *  лише дороги. */
    private fun importFromPath(context: Context, path: String): Result {
        val pack = runCatching {
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return Result.Failure("Файл не є базою SQLite.")
        return try {
            if (!hasWaysTable(pack)) {
                return Result.Failure("Це не дорожній пакет Nightpost.")
            }
            val formatVersion = userVersion(pack)
            if (formatVersion > MAX_KNOWN_PACK_FORMAT) {
                // Стеля ПЕРЕД читанням: `ways` могла змінити форму, тож
                // читати її за старою формою тут — це або сирий виняток
                // SQLite, або (гірше) хибно розібрані рядки. Голосна відмова
                // з поясненням і дією — рівно те, чого немає в жодному з двох.
                return Result.Failure(
                    "Пакет спечено рецептом v$formatVersion — новішим, ніж розуміє це видання " +
                        "застосунку (до v$MAX_KNOWN_PACK_FORMAT). Оновіть застосунок, перш ніж " +
                        "вносити цей пакет.",
                )
            }
            readPack(context, pack, formatVersion)
        } finally {
            pack.close()
        }
    }

    private fun readPack(context: Context, pack: SQLiteDatabase, formatVersion: Int): Result {
        val seen = HashSet<Long>()
        val hasTags = hasTagsColumn(pack)
        val cursor = pack.rawQuery(
            if (hasTags) "SELECT id, name, oneway, pts, tags FROM ${RoadMeshStore.WAYS}"
            else "SELECT id, name, oneway, pts FROM ${RoadMeshStore.WAYS}",
            null,
        )
        cursor.use { c ->
            val ways = Iterable {
                object : Iterator<RoadWay> {
                    private var pending: RoadWay? = advance()
                    private fun advance(): RoadWay? {
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            if (!seen.add(id)) continue // одна дорога може лежати в кількох клітинках
                            val pts = decodePoints(c.getString(3)) ?: continue
                            return RoadWay(
                                id = id,
                                points = pts,
                                name = if (c.isNull(1)) null else c.getString(1),
                                oneway = c.getInt(2) != 0,
                                tags = if (hasTags && !c.isNull(4)) {
                                    RoadWayCodec.decodeTags(c.getString(4))
                                } else {
                                    emptyMap()
                                },
                            )
                        }
                        return null
                    }
                    override fun hasNext() = pending != null
                    override fun next(): RoadWay {
                        val r = pending ?: throw NoSuchElementException()
                        pending = advance()
                        return r
                    }
                }
            }
            // SQLiteOpenHelper не Closeable на всіх API — тому явний
            // try/finally, не .use.
            val store = RoadMeshStore(context)
            try {
                store.bulkImport(ways)
            } finally {
                store.close()
            }
        }
        if (seen.isEmpty()) return Result.Failure("Пакет порожній — жодної дороги.")

        // ── ТРАНСПОРТНИЙ СКЕЛЕТ ─────────────────────────────────────────
        // Пакети до v3 цих таблиць не мають, і це не помилка: зупинка є
        // ВУЗЛОМ, а пічка фільтрувала `w/highway` першим кроком, тож у
        // конвеєр вона не заходила взагалі. Тому кожну таблицю питаємо
        // окремо і мовчки пропускаємо відсутню — рівно як `tags` вище.
        //
        // Словник кодека пакета читаємо, але НЕ робимо з нього відмови.
        //
        // Тут стояла відмова: розійшовся словник — транспорт не вносимо. Її
        // підстава («чужий порядок ключів прочитався б як свій») хибна:
        // рядок `k=v` самоописовий, розбір іде за іменами й порядку не знає.
        // Ціна тієї відмови була б висока й тиха — варто печі навчитись
        // одного ключа раніше за телефон, і ВЕСЬ транспорт перестав би
        // вноситись без жодного повідомлення про причину.
        //
        // Корисне ж у словнику інше: він каже, яких ключів пакет НЕ несе.
        val missing = TransitCodec.vocabularyGap(readMeta(pack))
        var stops = 0
        var rails = 0
        var routes = 0
        val store = RoadMeshStore(context)
        try {
            if (hasTable(pack, RoadMeshStore.STOPS)) stops = importStops(pack, store)
            if (hasTable(pack, RoadMeshStore.RAILS)) rails = importRails(pack, store)
            if (hasTable(pack, RoadMeshStore.ROUTES)) routes = importRoutes(pack, store)
        } finally {
            store.close()
        }
        return Result.Success(seen.size, hasTags, formatVersion, stops, rails, routes, missing)
    }

    /**
     * Словник кодека, який пакет оголосив про себе. Порожньо — пакет його не
     * оголошує (усе до v3), і тоді судити нема по чому: транспортних таблиць
     * там усе одно немає.
     */
    private fun readMeta(db: SQLiteDatabase): Map<String, String> {
        if (!hasTable(db, RoadMeshStore.META)) return emptyMap()
        val out = HashMap<String, String>()
        runCatching {
            db.rawQuery("SELECT k, v FROM ${RoadMeshStore.META}", null).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getString(1) ?: ""
            }
        }
        return out
    }

    private fun importStops(pack: SQLiteDatabase, store: RoadMeshStore): Int {
        val seen = HashSet<Long>()
        pack.rawQuery(
            "SELECT id, name, lat, lon, tags, routes FROM ${RoadMeshStore.STOPS}", null,
        ).use { c ->
            val batch = ArrayList<TransitStop>(BATCH)
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!seen.add(id)) continue // одна зупинка може лежати в кількох клітинках
                batch.add(
                    TransitStop(
                        id = id,
                        lat = c.getDouble(2),
                        lon = c.getDouble(3),
                        name = if (c.isNull(1)) null else c.getString(1),
                        tags = if (c.isNull(4)) emptyMap() else RoadWayCodec.decodeTags(c.getString(4)),
                        routes = if (c.isNull(5)) "" else c.getString(5),
                    ),
                )
                if (batch.size >= BATCH) {
                    store.putStops(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) store.putStops(batch)
        }
        return seen.size
    }

    private fun importRails(pack: SQLiteDatabase, store: RoadMeshStore): Int {
        val seen = HashSet<Long>()
        pack.rawQuery("SELECT id, name, pts, tags FROM ${RoadMeshStore.RAILS}", null).use { c ->
            val batch = ArrayList<RailLine>(BATCH)
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!seen.add(id)) continue
                val pts = decodePoints(c.getString(2)) ?: continue
                batch.add(
                    RailLine(
                        id = id,
                        points = pts,
                        name = if (c.isNull(1)) null else c.getString(1),
                        tags = if (c.isNull(3)) emptyMap() else RoadWayCodec.decodeTags(c.getString(3)),
                    ),
                )
                if (batch.size >= BATCH) {
                    store.putRails(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) store.putRails(batch)
        }
        return seen.size
    }

    private fun importRoutes(pack: SQLiteDatabase, store: RoadMeshStore): Int {
        var n = 0
        pack.rawQuery(
            "SELECT id, mode, ref, name, from_name, to_name, operator, colour, stops " +
                "FROM ${RoadMeshStore.ROUTES}",
            null,
        ).use { c ->
            val batch = ArrayList<TransitRoute>(BATCH)
            while (c.moveToNext()) {
                batch.add(
                    TransitRoute(
                        id = c.getLong(0),
                        mode = c.getString(1) ?: "",
                        ref = if (c.isNull(2)) "" else c.getString(2),
                        name = if (c.isNull(3)) "" else c.getString(3),
                        fromName = if (c.isNull(4)) "" else c.getString(4),
                        toName = if (c.isNull(5)) "" else c.getString(5),
                        operator = if (c.isNull(6)) "" else c.getString(6),
                        colour = if (c.isNull(7)) "" else c.getString(7),
                        stops = if (c.isNull(8)) emptyList() else c.getString(8)
                            .split(';').mapNotNull { it.toLongOrNull() },
                    ),
                )
                n++
                if (batch.size >= BATCH) {
                    store.putRoutes(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) store.putRoutes(batch)
        }
        return n
    }

    /** Скільки рядків тримаємо в памʼяті між записами — як [RoadMeshStore.BULK_BATCH]. */
    private const val BATCH = 500

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    /** Яким рецептом спечено пакет. 0 — не оголошує версії взагалі. */
    private fun userVersion(db: SQLiteDatabase): Int =
        db.rawQuery("PRAGMA user_version", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Чи має `ways` колонку `tags`. Старіші пакети її не мають, і питати
     *  напряму не можна: `SELECT tags` по такій базі валиться. */
    private fun hasTagsColumn(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA table_info(ways)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            if (nameIdx < 0) return@use false
            var found = false
            while (!found && c.moveToNext()) found = c.getString(nameIdx) == "tags"
            found
        }

    private fun hasWaysTable(db: SQLiteDatabase): Boolean = hasTable(db, RoadMeshStore.WAYS)

    /** Дзеркало RoadWayCodec.decodePoints: "lat,lon;lat,lon;…" → точки, null на будь-якій хибі. */
    private fun decodePoints(encoded: String?): List<Pair<Double, Double>>? {
        if (encoded.isNullOrEmpty()) return null
        val parts = encoded.split(';')
        if (parts.size < 2) return null
        val out = ArrayList<Pair<Double, Double>>(parts.size)
        for (part in parts) {
            val comma = part.indexOf(',')
            if (comma <= 0) return null
            val lat = part.substring(0, comma).toDoubleOrNull() ?: return null
            val lon = part.substring(comma + 1).toDoubleOrNull() ?: return null
            out.add(lat to lon)
        }
        return out
    }
}
