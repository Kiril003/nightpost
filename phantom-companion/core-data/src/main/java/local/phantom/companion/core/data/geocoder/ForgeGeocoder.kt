package local.phantom.companion.core.data.geocoder

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import local.phantom.companion.core.data.poi.OfflinePlacePack
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiTextMatch
import local.phantom.companion.core.data.poi.PoiVocabulary
import local.phantom.companion.core.net.map.TagSelector

/** Одне влучання пакета кузні — рівно ті поля, які несуть вектори кузні. */
data class ForgeHit(
    val kind: String,
    val osmType: String,
    val osmId: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val cat: String?,
    val sub: String?,
    val city: String?,
    val housenumber: String? = null,
    val distanceMeters: Double? = null,
    /**
     * Бал пакета — менше означає ближче до істини (0 адреса · 1 точна назва ·
     * 1,5 категорія · 2 префікс · 3+ нечіткий). Доїжджає назовні тому, що
     * поставлених пакетів буває кілька, і зводити їхні відповіді за самою лише
     * відстанню означало б поставити випадковий префікс за рогом попереду
     * точної назви в сусідній країні.
     */
    val score: Double = 0.0,
)

/**
 * Читач геокодера кузні — окремий SQLite-пакет, не Room.
 *
 * Порядок кроків (адреса → категорія → назва → триграми) і ваги збігів —
 * двійник `forge/geocode.py`; вектори кузні звіряють відповіді рядок у рядок.
 */
class ForgeGeocoder internal constructor(private val db: PackDb) : Closeable {

    val meta: Map<String, String> = buildMap {
        db.query("SELECT k, v FROM meta", EMPTY).use { c ->
            while (c.next()) put(c.text(0).orEmpty(), c.text(1).orEmpty())
        }
    }

    /** ISO-код країни пакета — на ньому тримається і вибір домашнього пакета, і підпис у рядку. */
    val country: String get() = meta["country"].orEmpty()

    /** Ярлик категорії українською — він же єдине, що бачить око в рядку видачі. */
    val categoryLabels: Map<String, String>

    private val categoryOfWord: Map<String, String>

    init {
        val labels = LinkedHashMap<String, String>()
        val words = LinkedHashMap<String, String>()
        db.query("SELECT cat, label_uk, label_en, synonyms FROM categories", EMPTY).use { c ->
            while (c.next()) {
                val cat = c.text(0).orEmpty()
                val uk = c.text(1).orEmpty()
                labels[cat] = uk
                val all = uk + " " + c.text(2).orEmpty() + " " + c.text(3).orEmpty()
                for (word in ForgeGeocoderText.words(all)) words.putIfAbsent(word, cat)
            }
        }
        categoryLabels = labels
        categoryOfWord = words
    }

    /**
     * [allowFuzzy] вимикають у ЧУЖИХ пакетах, і це не економія, а вимір.
     *
     * Нечіткий прохід рятує описку ПОРУЧ, а коштує повного `tri:` АБО з
     * упорядкуванням за відстанню. Виміряно 12.09 на трьох поставлених пакетах
     * із Кошиць: «Kosice» в українському пакеті — 308 мс, «lekáreň» — 306 мс,
     * і це рівно та робота, яка не могла дати відповіді. Без нього найдовший
     * пакет у запиті падає з 308 мс до 87 мс, а більшість запитів — до 1–21 мс.
     */
    fun search(
        query: String,
        near: Pair<Double, Double>?,
        limit: Int = 10,
        allowFuzzy: Boolean = true,
    ): List<ForgeHit> {
        val q = ForgeGeocoderText.sanitize(query)
        if (q.isEmpty()) return emptyList()

        val found = LinkedHashMap<Any, Scored>()

        ForgeGeocoderText.ADDRESS.find(q.lowercase())?.let { addr ->
            for (scored in addresses(streetsNamed(addr.groupValues[1], near), addr.groupValues[2])) {
                val row = scored.row
                found[listOf("a", row.osmType, row.osmId, row.housenumber.orEmpty())] = scored
            }
        }

        val words = ForgeGeocoderText.words(q)
        val own = words.firstNotNullOfOrNull { categoryOfWord[it] }
        // Словник пакета знає лише українські слова; «pharmacy», «apteka», «аптіка» — через наш.
        val asked = if (own == null) PoiVocabulary.kindOf(q) else null
        val cat = own ?: asked?.kinds?.firstNotNullOfOrNull { k ->
            // Лише кирилиця: англійське «shelter» у пакеті — бомбосховище, а в нас — альтанка.
            PoiVocabulary.wordsOf(k).filter { w -> w.any { it in 'а'..'я' || it in "іїєґ" } }
                .firstNotNullOfOrNull { categoryOfWord[ForgeGeocoderText.fold(it)] }
        }
        val rest = if (own != null || cat == null) {
            words.filter { categoryOfWord[it] != cat }
        } else {
            ForgeGeocoderText.words(asked?.rest.orEmpty())
        }
        val categoryBrowse = cat != null && near != null

        if (cat != null && near != null) {
            val (lat, lon) = near
            val k = cos(Math.toRadians(lat)).let { it * it }
            val rows = select(
                ForgeGeocoderSql.CATEGORY,
                arrayOf(
                    cat, num(lat - 0.5), num(lat + 0.5), num(lon - 0.7), num(lon + 0.7),
                    num(lat), num(lat), num(lon), num(lon), num(k), "200",
                ),
            )
            // Пакет зводить кавʼярні й ресторани в одне «food»; людина, що питала каву, питала кавʼярню.
            val kinds = asked?.kinds.orEmpty()
            val sameKind = rows.filter { it.sub?.substringAfter('=') in kinds }
            for (row in sameKind.ifEmpty { rows }) {
                val haystack = ForgeGeocoderText.fold(row.name.orEmpty() + " " + row.city.orEmpty())
                val named = rest.isEmpty() || rest.any { it in haystack } ||
                    PoiTextMatch.matchedName(row.name.orEmpty(), rest.joinToString(" "))
                if (named) found[row.id] = Scored(row, if (own == null) 0.9 else 1.5)
            }
        }

        val expr = if (!categoryBrowse || rest.isNotEmpty() || own == null) ForgeGeocoderText.namesExpression(q) else null
        if (expr != null) {
            val folded = ForgeGeocoderText.fold(q)
            for (row in matched(expr, near, 300)) {
                if (found.containsKey(row.id)) continue
                val exact = listOf(row.name, row.nameUk, row.nameEn, row.nameRu)
                    .any { ForgeGeocoderText.fold(it.orEmpty()) == folded }
                found[row.id] = Scored(row, if (exact) 1.0 else 2.0)
            }
        }

        if (allowFuzzy && found.size < limit && !categoryBrowse) {
            for (scored in fuzzy(q, near)) found.putIfAbsent(scored.row.id, scored)
        }

        return found.values
            .map { it to distanceOf(near, it.row) }
            .sortedWith(
                compareBy<Pair<Scored, Double?>> { it.first.score }
                    .thenBy { if (near == null) kindWeight(it.first.row.kind) else 0 }
                    .thenBy { if (near != null) it.second ?: 0.0 else it.first.row.rank.toDouble() }
                    .thenBy { it.first.row.rank },
            )
            .take(limit)
            .map { (scored, metres) -> scored.row.toHit(metres, scored.score) }
    }

    fun reverse(lat: Double, lon: Double): ForgeHit? {
        val d = 0.004
        var bestDistance = Double.MAX_VALUE
        var best: Row? = null
        val nearby = addressRows(
            ForgeGeocoderSql.REVERSE_ADDR,
            arrayOf(
                ((lat - d) * 1e6).roundToLong().toString(), ((lat + d) * 1e6).roundToLong().toString(),
                ((lon - d) * 1e6).roundToLong().toString(), ((lon + d) * 1e6).roundToLong().toString(),
            ),
        )
        for (row in nearby) {
            val metres = haversine(lat, lon, row.lat, row.lon)
            if (metres < bestDistance) {
                bestDistance = metres
                best = row
            }
        }
        val exactAddress = best
        if (exactAddress != null && bestDistance < 80.0) {
            return exactAddress.toHit(round(bestDistance))
        }

        for (row in select(
            ForgeGeocoderSql.REVERSE_PLACE,
            arrayOf(num(lat - 0.02), num(lat + 0.02), num(lon - 0.03), num(lon + 0.03)),
        )) {
            // Місто карається вчетверо: його точка — центр, а не те, на чому стоїш.
            val metres = haversine(lat, lon, row.lat, row.lon) * (if (row.kind == "street") 1.0 else 4.0)
            if (metres < bestDistance) {
                bestDistance = metres
                best = row
            }
        }
        val hit = best ?: return null
        return hit.toHit(round(haversine(lat, lon, hit.lat, hit.lon)))
    }

    /** Теги пакета: `k=v|k=v`, труба всередині значення — як `%7C`. */
    fun tagsOf(hit: ForgeHit): Map<String, String> {
        if (hit.kind == "address") {
            return emptyMap()
        }
        db.query(
            "SELECT tags FROM places WHERE osm_id = ? AND kind = ? LIMIT 1",
            arrayOf(hit.osmId.toString(), hit.kind),
        ).use { c ->
            return if (c.next()) decodeTags(c.text(0).orEmpty()) else emptyMap()
        }
    }

    /** Іменовані місця кадру, найближчі до центра — підлога значків мапи там, де стоїть пакет країни. */
    fun pinsInBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, limit: Int): List<PoiEntity> =
        pins(PINS_IN_BOX, emptyList(), minLat, minLon, maxLat, maxLon, limit)

    /**
     * «Поруч» без мережі: місця пакета під селектором Overpass. SQL відбирає
     * кандидатів за головним тегом і за рядком тегів, [TagSelector] виносить вирок.
     */
    fun nearby(lat: Double, lon: Double, radiusMeters: Int, selector: String, limit: Int): List<PoiEntity> {
        val clauses = TagSelector.clauses(selector)
        if (clauses.isEmpty()) return emptyList()
        val pairs = clauses.flatMap { c ->
            val values = if (c.regex) c.values.flatMap { it.split('|') } else c.values.toList()
            if (values.isNotEmpty() && values.all { v -> v.all { it.isLetterOrDigit() || it in "_:-" } }) {
                values.map { "${c.key}=$it" }
            } else {
                listOf("${c.key}=")
            }
        }
        val like = pairs.joinToString(" OR ") { "sub = ? OR tags LIKE ?" }
        val args = pairs.flatMap { listOf(it, "%$it%") }
        val dLat = radiusMeters / 111_320.0
        val dLon = dLat / maxOf(cos(Math.toRadians(lat)), 0.01)
        return pins(
            "SELECT ${ForgeGeocoderSql.COLS},tags FROM places WHERE kind='poi' AND ($like) " +
                "AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY (lat-?)*(lat-?)+(lon-?)*(lon-?)*? LIMIT ?",
            args, lat - dLat, lon - dLon, lat + dLat, lon + dLon, limit * 4, named = false,
        ).filter { TagSelector.matches(clauses, it.tags, it.category, it.subcategory) }
            .filter { haversine(lat, lon, it.lat, it.lon) <= radiusMeters }
            .take(limit)
    }

    private fun pins(
        sql: String,
        head: List<String>,
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        limit: Int,
        named: Boolean = true,
    ): List<PoiEntity> {
        val cLat = (minLat + maxLat) / 2
        val cLon = (minLon + maxLon) / 2
        val k = cos(Math.toRadians(cLat)).let { it * it }
        val out = ArrayList<PoiEntity>()
        db.query(
            sql,
            (head + listOf(
                num(minLat), num(maxLat), num(minLon), num(maxLon),
                num(cLat), num(cLat), num(cLon), num(cLon), num(k), limit.toString(),
            )).toTypedArray(),
        ).use { c ->
            while (c.next()) {
                val row = c.toRow()
                val tag = row.sub?.takeIf { '=' in it }
                val name = row.name?.takeIf { it.isNotBlank() }
                    ?: (if (named) null else PoiVocabulary.label(tag?.substringAfter('=')) ?: categoryLabels[row.cat])
                    ?: continue
                val ref = OfflinePlacePack.osmRef(fullType(row.osmType), row.osmId)
                val packed = decodeTags(c.text(COLS_COUNT).orEmpty())
                out += PoiEntity(
                    id = ref?.let { OfflinePlacePack.idForRef(it) } ?: row.osmId,
                    lat = row.lat,
                    lon = row.lon,
                    name = name,
                    category = tag?.substringBefore('=') ?: row.cls ?: row.cat.orEmpty(),
                    subcategory = tag?.substringAfter('=') ?: row.cat,
                    tags = if (ref == null) packed else packed + (OfflinePlacePack.REF_TAG to ref),
                )
            }
        }
        return out
    }

    override fun close() = db.close()

    private fun streetsNamed(text: String, near: Pair<Double, Double>?): List<Scored> {
        val found = LinkedHashMap<Long, Scored>()
        val expr = ForgeGeocoderText.namesExpression(text)
        if (expr != null) {
            val folded = ForgeGeocoderText.fold(text)
            for (row in matched(expr, near, 300)) {
                if (row.kind != "street") continue
                found[row.id] = Scored(row, if (ForgeGeocoderText.fold(row.name.orEmpty()) == folded) 1.0 else 2.0)
            }
        }
        if (found.size < 3) {
            for (scored in fuzzy(text, near)) {
                if (scored.row.kind == "street") found.putIfAbsent(scored.row.id, scored)
            }
        }
        return found.values.sortedBy { it.score }.take(40)
    }

    private fun addresses(streets: List<Scored>, housenumber: String, limit: Int = 50): List<Scored> {
        if (streets.isEmpty()) return emptyList()
        val ids = streets.map { it.row.id }
        val args = ids.map { it.toString() } + listOf(housenumber.lowercase(), limit.toString())
        return addressRows(ForgeGeocoderSql.expand(ForgeGeocoderSql.ADDRESS, ids.size), args.toTypedArray())
            .map { Scored(it, 0.0) }
    }

    private fun fuzzy(text: String, near: Pair<Double, Double>?, limit: Int = 4000): List<Scored> {
        val tri = ForgeGeocoderText.trigrams(text)
        if (tri.size < 3) return emptyList()
        val sorted = tri.sorted()
        val df = HashMap<String, Int>(tri.size)
        db.query(ForgeGeocoderSql.expand(ForgeGeocoderSql.DF, sorted.size), sorted.toTypedArray()).use { c ->
            while (c.next()) df[c.text(0).orEmpty()] = c.int(1)
        }
        // Рідкість вирішує, а однакову рідкість — абетка: без другого ключа
        // вибір чотирьох триграм залежав би від порядку обходу множини.
        val known = sorted.filter { df.containsKey(it) }.sortedWith(compareBy({ df.getValue(it) }, { it }))
        if (known.size < maxOf(2, tri.size / 2)) return emptyList()
        val expr = known.take(4).joinToString(" OR ") { "tri:$it" }
        val queryWords = ForgeGeocoderText.words(text).map { ForgeGeocoderText.wordTrigrams(it) }
        val out = ArrayList<Scored>()
        for (row in matched(expr, near, limit)) {
            val similarity = ForgeGeocoderText.similarity(queryWords, row.names.orEmpty())
            if (similarity >= 0.5) out += Scored(row, 3.0 + (1.0 - similarity))
        }
        return out
    }

    private fun matched(expr: String, near: Pair<Double, Double>?, limit: Int): List<Row> {
        if (near == null) return select(ForgeGeocoderSql.MATCH, arrayOf(expr, limit.toString()))
        val (lat, lon) = near
        val k = cos(Math.toRadians(lat)).let { it * it }
        return select(
            ForgeGeocoderSql.MATCH_NEAR,
            arrayOf(expr, num(lat), num(lat), num(lon), num(lon), num(k), limit.toString()),
        )
    }

    private fun select(sql: String, args: Array<String>): List<Row> {
        val out = ArrayList<Row>()
        db.query(sql, args).use { c ->
            while (c.next()) out += c.toRow()
        }
        return out
    }

    private fun addressRows(sql: String, args: Array<String>): List<Row> {
        val out = ArrayList<Row>()
        db.query(sql, args).use { c ->
            while (c.next()) {
                val number = c.text(1).orEmpty()
                out += Row(
                    id = c.long(0),
                    kind = "address",
                    osmType = c.text(4).orEmpty(),
                    osmId = c.long(5),
                    lat = c.long(2) / 1e6,
                    lon = c.long(3) / 1e6,
                    rank = 14,
                    cls = "addr",
                    sub = null,
                    cat = null,
                    name = c.text(6).orEmpty() + " " + number,
                    nameUk = null,
                    nameEn = null,
                    nameRu = null,
                    city = c.text(7),
                    names = null,
                    housenumber = number,
                )
            }
        }
        return out
    }

    private fun distanceOf(near: Pair<Double, Double>?, row: Row): Double? =
        near?.let { round(haversine(it.first, it.second, row.lat, row.lon)) }

    private fun PackCursor.toRow() = Row(
        id = long(0),
        kind = text(1).orEmpty(),
        osmType = text(2).orEmpty(),
        osmId = long(3),
        lat = double(4),
        lon = double(5),
        rank = int(6),
        cls = text(7),
        sub = text(8),
        cat = text(9),
        name = text(10),
        nameUk = text(11),
        nameEn = text(12),
        nameRu = text(13),
        city = text(14),
        names = text(15),
    )

    private data class Row(
        val id: Long,
        val kind: String,
        val osmType: String,
        val osmId: Long,
        val lat: Double,
        val lon: Double,
        val rank: Int,
        val cls: String?,
        val sub: String?,
        val cat: String?,
        val name: String?,
        val nameUk: String?,
        val nameEn: String?,
        val nameRu: String?,
        val city: String?,
        val names: String?,
        val housenumber: String? = null,
    ) {
        fun toHit(metres: Double?, score: Double = 0.0) = ForgeHit(
            kind = kind,
            osmType = osmType,
            osmId = osmId,
            name = name.orEmpty(),
            lat = lat,
            lon = lon,
            cat = cat,
            sub = sub,
            city = city,
            housenumber = housenumber,
            distanceMeters = metres,
            score = score,
        )
    }

    private data class Scored(val row: Row, val score: Double)

    companion object {

        const val FORMAT = 1

        internal val EMPTY = emptyArray<String>()

        fun open(path: String): ForgeGeocoder? {
            if (!File(path).isFile) return null
            val raw = runCatching {
                SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            }.getOrNull() ?: return null
            return over(AndroidPackDb(raw))
        }

        /** Формат пакета звіряється ТУТ, а не в імпортері: читач, який не
         *  впізнав пакет, мусить не відкритися зовсім. */
        fun over(db: PackDb): ForgeGeocoder? {
            val readable = runCatching {
                db.query("SELECT v FROM meta WHERE k = 'format'", EMPTY).use { c ->
                    c.next() && c.text(0)?.toIntOrNull() == FORMAT
                }
            }.getOrDefault(false)
            if (!readable) {
                runCatching { db.close() }
                return null
            }
            return ForgeGeocoder(db)
        }

        fun decodeTags(packed: String): Map<String, String> {
            if (packed.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in packed.split('|')) {
                val at = pair.indexOf('=')
                if (at <= 0) continue
                out[pair.substring(0, at).replace("%7C", "|")] = pair.substring(at + 1).replace("%7C", "|")
            }
            return out
        }

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p = Math.PI / 180
            val a = 0.5 - cos((lat2 - lat1) * p) / 2 +
                cos(lat1 * p) * cos(lat2 * p) * (1 - cos((lon2 - lon1) * p)) / 2
            return 12_742_000.0 * asin(sqrt(min(1.0, a)))
        }

        private fun round(metres: Double): Double = metres.roundToLong().toDouble()

        private fun kindWeight(kind: String): Int = when (kind) {
            "place" -> 0
            "street" -> 1
            "address" -> 2
            "poi" -> 3
            else -> 9
        }

        private fun num(value: Double): String = value.toString()

        private fun fullType(t: String): String? = when (t) {
            "n", "node" -> "node"
            "w", "way" -> "way"
            "r", "relation" -> "relation"
            else -> null
        }

        private const val COLS_COUNT = 16

        // Паркування — третина всіх місць пакета (Прага: 15 686 із 44 тисяч), і воно має власний шар.
        private const val PINS_IN_BOX =
            "SELECT ${ForgeGeocoderSql.COLS},tags FROM places WHERE kind='poi' AND cat IS NOT 'parking' " +
                "AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? " +
                "ORDER BY (lat-?)*(lat-?)+(lon-?)*(lon-?)*? LIMIT ?"
    }
}
