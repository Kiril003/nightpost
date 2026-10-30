package local.phantom.companion.core.sensor.nav

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * ТРАНСПОРТНИЙ СКЕЛЕТ — читацька половина шва v3.
 *
 * ЧОМУ ЦЬОГО НЕ БУЛО. Пічка (`~/phantom_ai/road-mesh-bake/bake.sh`) першим
 * кроком фільтрувала `w/highway`. Зупинка в OSM — це ВУЗОЛ, маршрут —
 * ВІДНОШЕННЯ, колія — лінія БЕЗ ключа `highway`. Тобто жоден із трьох шарів
 * не заходив у конвеєр узагалі, і мовчав про це: пакет не ставав битим, він
 * ставав бідним. Заміряно 5 вересня 2026 на свіжому екстракті України:
 * 65 494 зупинки й станції, 3 735 маршрутів, ~64,7 тис. колійних ліній у
 * країні проти **25 зупинок, нуля маршрутів і нуля колій** у пакеті, що їде
 * в телефон.
 *
 * ЩО ТУТ Є І ЧОГО НЕ БУДЕ. OSM не несе ні розкладів, ні тарифів, і ми їх не
 * вигадуємо: [TransitStop] знає, ЩО це, як зветься і які номери тут
 * зупиняються; [TransitRoute] знає, куди йде лінія. Хвилини й ціни сюди не
 * приходять — саме ця поверхня колись показувала «Проїзд: 8.00 ₴» літералом
 * на кожній зупинці країни. Єдиний чесний прецедент поруч — київська
 * модельована мережа, яка тягне `timesAreModelled` аж до планувальника;
 * тут навіть модельованої хвилини немає.
 *
 * ЧОМУ КОЛІЇ ОКРЕМОЮ ТАБЛИЦЕЮ, А НЕ В `ways`. Це вимір, не смак:
 * [RoadProfile.blocked] для лінії з `railway=rail` і без `highway` повертає
 * **false** — усі його правила питають `highway`, тож жодне не спрацьовує.
 * Колія в `ways` стала б звичайним ребром графа ціною голої довжини, і
 * [LocalRouter] повів би людину коліями, а [RoadMatcher] приліпив би до них
 * її позицію. У [RoadMeshStore.RAILS] геометрія колій у граф не потрапляє
 * ЗА ПОБУДОВОЮ — а не за правилом, яке комусь треба памʼятати.
 */

/** Зупинка або станція: один вузол OSM. */
data class TransitStop(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    val tags: Map<String, String> = emptyMap(),
    /** Які номери тут зупиняються, у порядку показу: `bus:5|tram:14`. */
    val routes: String = "",
) {
    /** Розібрані пари (режим, номер) у тому ж порядку, що й у [routes]. */
    val serving: List<Pair<String, String>> get() = TransitCodec.decodeRoutes(routes)
}

/** Колійна лінія: одна лінія OSM із `railway=*`. Ніколи не ребро графа. */
data class RailLine(
    val id: Long,
    val points: List<Pair<Double, Double>>,
    val name: String? = null,
    val tags: Map<String, String> = emptyMap(),
)

/**
 * Маршрутне відношення: куди йде лінія.
 *
 * [stops] — упорядкований ланцюг id зупинок, узятий із порядку членів
 * відношення (у схемі PTv2 він і є порядком руху). Ані часу, ані частоти
 * тут немає й не передбачено.
 */
data class TransitRoute(
    val id: Long,
    val mode: String,
    val ref: String = "",
    val name: String = "",
    val fromName: String = "",
    val toName: String = "",
    val operator: String = "",
    val colour: String = "",
    val stops: List<Long> = emptyList(),
)

/**
 * Кодек транспортного шару. Дзеркало `mesh_bake.py`: списки ключів ідуть у
 * ТОМУ САМОМУ порядку оголошення.
 *
 * Порядок вирішує байти при ЗАПИСІ — кодувальник обходить білий список, а не
 * вхід. При ЧИТАННІ він не важить нічого: рядок `k=v|k=v` самоописовий, і
 * [decodeTags] бере пари за іменами. Тримати списки однаковими треба не щоб
 * читалось, а щоб дві печі давали для тієї самої дороги ті самі байти —
 * інакше пакети перестають бути звірюваними, і «що змінилось» більше не
 * відповісти.
 */
object TransitCodec {

    /**
     * Рівно те, що поверхня може ЧЕСНО показати. Кожен ключ множиться на 65
     * тисяч зупинок, тож «про всяк випадок» тут коштує мегабайти.
     */
    val STOP_KEYS = listOf(
        // що це за обʼєкт
        "highway", "railway", "public_transport", "station",
        // який транспорт тут зупиняється (OSM каже це окремими прапорцями)
        "bus", "trolleybus", "tram", "train", "subway", "share_taxi",
        "light_rail", "monorail", "funicular",
        // як його називають у мережі
        "ref", "local_ref", "operator", "network",
        // що людина застане на місці
        "shelter", "bench", "covered", "tactile_paving", "wheelchair",
        "departures_board",
    )

    /** `usage` і `service` — єдине, що відрізняє магістраль від колії депо. */
    val RAIL_KEYS = listOf(
        "railway", "usage", "service", "electrified", "gauge",
        "bridge", "tunnel", "layer", "ref", "operator",
    )

    /** Режими маршрутних відношень, у порядку показу на картці зупинки. */
    val ROUTE_MODES = listOf(
        "subway", "train", "light_rail", "tram", "trolleybus", "bus",
        "share_taxi", "monorail", "funicular",
    )

    /** `bus:5|tram:14` — двокрапка розділяє, отже в номері вона екранується. */
    const val COLON_ESCAPE = "%3A"
    private const val PIPE_ESCAPE = "%7C"

    fun encodeTags(tags: Map<String, String>, keys: List<String>): String {
        if (tags.isEmpty()) return ""
        val sb = StringBuilder()
        for (k in keys) {
            val v = tags[k] ?: continue
            if (v.isEmpty() || v.contains('=')) continue
            if (sb.isNotEmpty()) sb.append('|')
            sb.append(k).append('=').append(v.replace("|", PIPE_ESCAPE))
        }
        return sb.toString()
    }

    /** Той самий розбір, що й у [RoadWayCodec.decodeTags] — формат один. */
    fun decodeTags(encoded: String): Map<String, String> = RoadWayCodec.decodeTags(encoded)

    fun encodeRoutes(pairs: List<Pair<String, String>>): String {
        if (pairs.isEmpty()) return ""
        val sb = StringBuilder()
        for ((mode, ref) in pairs) {
            if (mode.isEmpty() || ref.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append('|')
            sb.append(mode).append(':')
                .append(ref.replace("|", PIPE_ESCAPE).replace(":", COLON_ESCAPE))
        }
        return sb.toString()
    }

    fun decodeRoutes(encoded: String): List<Pair<String, String>> {
        if (encoded.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (part in encoded.split('|')) {
            val colon = part.indexOf(':')
            if (colon <= 0 || colon == part.length - 1) continue
            val mode = part.substring(0, colon)
            val ref = part.substring(colon + 1)
                .replace(COLON_ESCAPE, ":").replace(PIPE_ESCAPE, "|")
            out.add(mode to ref)
        }
        return out
    }

    /**
     * Словник, який пакет оголошує про себе в таблиці `meta`.
     *
     * ЩО ЦЕ НАСПРАВДІ ДАЄ — і чого я спершу від нього хотіла помилково.
     *
     * Перша версія цього сторожа ВІДМОВЛЯЛА в транспортному шарі, щойно
     * словник пакета відрізнявся від нашого, на підставі «порядок ключів
     * вирішує байти, тож чужий порядок прочитався б як свій». Ця підстава
     * хибна. Байти вирішує порядок лише при ЗАПИСІ; рядок `k=v|k=v`
     * самоописовий, [decodeTags] розбирає пари за іменами і порядку не
     * знає взагалі. Тобто відмова стояла на ризику, якого не існує, — і
     * коштувала б дорого: варто печі навчитись одного ключа раніше за
     * телефон, і ВЕСЬ транспорт мовчки перестав би вноситись.
     *
     * Що словник дає насправді — інше й корисніше: він каже, ЯКИХ ключів
     * пакет не несе. Це рівно той клас, що вже тричі коштував цьому проєкту
     * тижнів («ліки, що не доїхали до артефакта»): правило на телефоні є,
     * даних під нього немає, і ніщо не червоніє. Тепер це видно в мить
     * внесення й називає ключ поіменно.
     *
     * Зайві ключі в пакеті — не проблема: ми їх просто не читаємо.
     */
    fun vocabulary(): Map<String, String> = mapOf(
        "stop_keys" to STOP_KEYS.joinToString("|"),
        "rail_keys" to RAIL_KEYS.joinToString("|"),
        "route_modes" to ROUTE_MODES.joinToString("|"),
        "routing_keys" to RoadWayCodec.routingKeys().joinToString("|"),
    )

    /**
     * Ключі, на які цей пристрій уміє діяти, а пакет їх не несе.
     *
     * Порожньо означає або «пакет несе все», або «пакет словника не
     * оголошує» (усе до v3) — і це різні речі, які тут навмисно збігаються:
     * судити пакет, що про себе нічого не сказав, нема по чому.
     */
    fun vocabularyGap(packMeta: Map<String, String>): List<String> {
        if (packMeta.isEmpty()) return emptyList()
        val gap = LinkedHashSet<String>()
        for ((row, mine) in vocabulary()) {
            val theirs = packMeta[row]?.split('|')?.toSet() ?: continue
            for (key in mine.split('|')) if (key.isNotEmpty() && key !in theirs) gap += key
        }
        return gap.toList()
    }
}

// ── ЗАПИС ──────────────────────────────────────────────────────────────

/** Влити зупинки в постійну мережу. Ідемпотентно, як і [RoadMeshStore.put]. */
fun RoadMeshStore.putStops(stops: Iterable<TransitStop>, batchSize: Int = RoadMeshStore.BULK_BATCH) {
    bulkWrite(stops, batchSize) { db, stop ->
        val tags = TransitCodec.encodeTags(stop.tags, TransitCodec.STOP_KEYS)
        for (cell in RoadMeshStore.cellsCovering(stop.lat, stop.lon, stop.lat, stop.lon)) {
            val values = ContentValues(7).apply {
                put("cell", cell)
                put("id", stop.id)
                put("name", stop.name)
                put("lat", stop.lat)
                put("lon", stop.lon)
                put("tags", tags)
                put("routes", stop.routes)
            }
            db.insertWithOnConflict(
                RoadMeshStore.STOPS, null, values, SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }
}

/** Влити колії. Ці рядки НІКОЛИ не стають ребрами графа — див. заголовок. */
fun RoadMeshStore.putRails(rails: Iterable<RailLine>, batchSize: Int = RoadMeshStore.BULK_BATCH) {
    bulkWrite(rails, batchSize) { db, rail ->
        if (rail.points.size < 2) return@bulkWrite
        var minLat = rail.points[0].first
        var maxLat = minLat
        var minLon = rail.points[0].second
        var maxLon = minLon
        for ((la, lo) in rail.points) {
            if (la < minLat) minLat = la
            if (la > maxLat) maxLat = la
            if (lo < minLon) minLon = lo
            if (lo > maxLon) maxLon = lo
        }
        val pts = RoadWayCodec.encodePoints(rail.points)
        val tags = TransitCodec.encodeTags(rail.tags, TransitCodec.RAIL_KEYS)
        for (cell in RoadMeshStore.cellsCovering(minLat, minLon, maxLat, maxLon)) {
            val values = ContentValues(5).apply {
                put("cell", cell)
                put("id", rail.id)
                put("name", rail.name)
                put("pts", pts)
                put("tags", tags)
            }
            db.insertWithOnConflict(
                RoadMeshStore.RAILS, null, values, SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }
}

/** Влити маршрутні відношення. */
fun RoadMeshStore.putRoutes(routes: Iterable<TransitRoute>, batchSize: Int = RoadMeshStore.BULK_BATCH) {
    bulkWrite(routes, batchSize) { db, r ->
        val values = ContentValues(9).apply {
            put("id", r.id)
            put("mode", r.mode)
            put("ref", r.ref)
            put("name", r.name)
            put("from_name", r.fromName)
            put("to_name", r.toName)
            put("operator", r.operator)
            put("colour", r.colour)
            put("stops", r.stops.joinToString(";"))
        }
        db.insertWithOnConflict(RoadMeshStore.ROUTES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

// ── ЧИТАННЯ ────────────────────────────────────────────────────────────

/** Усі зупинки, чия клітинка дотикається bbox, дедупльовані по id вузла. */
fun RoadMeshStore.stopsIn(
    minLat: Double,
    minLon: Double,
    maxLat: Double,
    maxLon: Double,
): List<TransitStop> {
    val cells = RoadMeshStore.cellsCovering(minLat, minLon, maxLat, maxLon)
    if (cells.isEmpty()) return emptyList()
    val out = LinkedHashMap<Long, TransitStop>()
    val placeholders = cells.joinToString(",") { "?" }
    readableDatabase.rawQuery(
        "SELECT id, name, lat, lon, tags, routes FROM ${RoadMeshStore.STOPS} " +
            "WHERE cell IN ($placeholders)",
        Array(cells.size) { cells[it].toString() },
    ).use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            if (out.containsKey(id)) continue
            out[id] = TransitStop(
                id = id,
                lat = c.getDouble(2),
                lon = c.getDouble(3),
                name = if (c.isNull(1)) null else c.getString(1),
                tags = if (c.isNull(4)) emptyMap() else TransitCodec.decodeTags(c.getString(4)),
                routes = if (c.isNull(5)) "" else c.getString(5),
            )
        }
    }
    return out.values.toList()
}

/** Усі колійні лінії, чия клітинка дотикається bbox. */
fun RoadMeshStore.railsIn(
    minLat: Double,
    minLon: Double,
    maxLat: Double,
    maxLon: Double,
): List<RailLine> {
    val cells = RoadMeshStore.cellsCovering(minLat, minLon, maxLat, maxLon)
    if (cells.isEmpty()) return emptyList()
    val out = LinkedHashMap<Long, RailLine>()
    val placeholders = cells.joinToString(",") { "?" }
    readableDatabase.rawQuery(
        "SELECT id, name, pts, tags FROM ${RoadMeshStore.RAILS} WHERE cell IN ($placeholders)",
        Array(cells.size) { cells[it].toString() },
    ).use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            if (out.containsKey(id)) continue
            val pts = RoadWayCodec.decodePoints(c.getString(2)) ?: continue
            out[id] = RailLine(
                id = id,
                points = pts,
                name = if (c.isNull(1)) null else c.getString(1),
                tags = if (c.isNull(3)) emptyMap() else TransitCodec.decodeTags(c.getString(3)),
            )
        }
    }
    return out.values.toList()
}

/** Маршрути за id — «куди йде цей номер». Порожній вхід питань не ставить. */
fun RoadMeshStore.routesByIds(ids: Collection<Long>): List<TransitRoute> {
    if (ids.isEmpty()) return emptyList()
    val out = ArrayList<TransitRoute>(ids.size)
    val list = ids.toList()
    var i = 0
    while (i < list.size) {
        val chunk = list.subList(i, minOf(i + RoadMeshStore.IN_CHUNK, list.size))
        val placeholders = chunk.joinToString(",") { "?" }
        readableDatabase.rawQuery(
            "SELECT id, mode, ref, name, from_name, to_name, operator, colour, stops " +
                "FROM ${RoadMeshStore.ROUTES} WHERE id IN ($placeholders)",
            Array(chunk.size) { chunk[it].toString() },
        ).use { c -> while (c.moveToNext()) out.add(c.toRoute()) }
        i += RoadMeshStore.IN_CHUNK
    }
    return out
}

/** Маршрути, що обслуговують цю зупинку. Порожньо — не «немає», а «не знаємо». */
fun RoadMeshStore.routesServing(stopId: Long): List<TransitRoute> {
    val out = ArrayList<TransitRoute>()
    readableDatabase.rawQuery(
        "SELECT id, mode, ref, name, from_name, to_name, operator, colour, stops " +
            "FROM ${RoadMeshStore.ROUTES} WHERE stops = ? OR stops LIKE ? OR stops LIKE ? OR stops LIKE ?",
        arrayOf("$stopId", "$stopId;%", "%;$stopId", "%;$stopId;%"),
    ).use { c -> while (c.moveToNext()) out.add(c.toRoute()) }
    return out
}

private fun android.database.Cursor.toRoute() = TransitRoute(
    id = getLong(0),
    mode = getString(1) ?: "",
    ref = if (isNull(2)) "" else getString(2),
    name = if (isNull(3)) "" else getString(3),
    fromName = if (isNull(4)) "" else getString(4),
    toName = if (isNull(5)) "" else getString(5),
    operator = if (isNull(6)) "" else getString(6),
    colour = if (isNull(7)) "" else getString(7),
    stops = if (isNull(8)) emptyList() else getString(8)
        .split(';').mapNotNull { it.toLongOrNull() },
)

/** Скільки зупинок тримає пристрій — для QA-HUD, поруч із [RoadMeshStore.wayCount]. */
fun RoadMeshStore.stopCount(): Long = countDistinct(RoadMeshStore.STOPS)

fun RoadMeshStore.railCount(): Long = countDistinct(RoadMeshStore.RAILS)

fun RoadMeshStore.routeCount(): Long = countDistinct(RoadMeshStore.ROUTES)

private fun RoadMeshStore.countDistinct(table: String): Long =
    readableDatabase.rawQuery("SELECT COUNT(DISTINCT id) FROM $table", null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else 0L
    }
