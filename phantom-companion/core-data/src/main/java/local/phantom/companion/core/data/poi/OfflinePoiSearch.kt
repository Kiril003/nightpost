package local.phantom.companion.core.data.poi

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HIGHWAY — the pure half of offline POI search: what the operator typed
 * becomes a legal FTS expression, and what SQLite returns becomes an ordering
 * a driver would agree with. No Room, no Android, no network — so every rule
 * here is a JVM unit test.
 */
object OfflinePoiSearch {

    /** Below this, a query is a letter or two of noise, not an intent. */
    const val MIN_QUERY_CHARS = 2

    /**
     * Operator text → an FTS4 MATCH expression.
     *
     * This exists because MATCH takes a *query language*, not a string, and
     * feeding it raw operator input is a crash (or worse, a silent wrong
     * answer). «Кавʼярня» — an ordinary Ukrainian word — contains the FTS
     * string delimiter; `АЗС "ОККО` has an unbalanced quote; `NEAR`, `AND`,
     * `OR`, `NOT` and `*` are all operators, and a hyphen begins a column
     * filter. So every token is stripped of syntax, quoted, and only then
     * given the one operator we actually want: a trailing `*`, because a
     * driver types «око» and means «ОККО», and he is typing at 90 km/h.
     *
     * Tokens are AND-ed (FTS4's implicit default): «окко азс» must match a row
     * containing both, not either — an OR here would bury the right answer
     * under everything that merely says «АЗС».
     *
     * Returns null when nothing searchable survives — the caller must treat
     * that as "no query", never as "match everything".
     */
    fun toMatchQuery(raw: String): String? {
        val tokens = raw
            // Anything that is not a letter or a digit is a separator. This is
            // deliberately brutal: it removes the apostrophe, the quote, the
            // hyphen, the caret and the asterisk in one pass, so no fragment of
            // FTS syntax can survive into the expression.
            //
            // Апостроф — роздільник у ВСІХ пʼяти виглядах. ʼ (U+02BC) — це
            // «літера-модифікатор» (\p{Lm}), тож правило «не літера» його не
            // ловило: «Кавʼярня», набрана з ʼ, ішла одним токеном і не
            // знаходила «кавʼярні», яку індекс (з ASCII-апострофом у даних) розклав на «кав» і «ярня».
            .split(Regex("[^\\p{L}\\p{N}]+|[ʼ’‘'`´]+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            // A bare "or"/"and"/"not"/"near" would still be read as an operator
            // even unquoted — but we quote every token below, which neutralises
            // them. They stay in: «АЗС і НЕ ОККО» is not a query language the
            // operator is speaking, he is just naming a place.
            .filter { it.length >= 1 }

        if (tokens.isEmpty()) return null
        if (tokens.sumOf { it.length } < MIN_QUERY_CHARS) return null

        // Bare token + star — NOT `"token"*`.
        //
        // A quoted-phrase prefix is FTS5 syntax. FTS3/4 accepts the star only
        // on a bare token, and silently treats `"львівськ"*` as an exact phrase
        // instead of a prefix: every query still "works", it just stops
        // matching anything the operator did not type in full. Device-verified
        // — the quoted form passed every exact-match test and failed the only
        // one that actually needed a prefix.
        //
        // Dropping the quotes is safe precisely because the split above already
        // reduced each token to letters and digits: there is no apostrophe, no
        // quote, no hyphen and no star left to be read as syntax. FTS4's
        // operators (AND/OR/NOT/NEAR) are recognised only in UPPERCASE, and
        // every token here is lowercased — so «АЗС OR ОККО» searches for the
        // word «or», which is exactly what a man naming a petrol station meant.
        return tokens.joinToString(" ") { "$it*" }
    }

    /** Коротший корінь — це вже не слово, а кожне слово, що з нього починається. */
    const val ROOT_CHARS = 3

    /**
     * Корені запиту — кандидати для проходу, у якому FTS має зазнати невдачі.
     *
     * Зіставляч [PoiTextMatch] уміє і дві абетки, і одну описку, і роками це
     * було записано в ньому словом. Не вмів цього ПОШУК: єдиним постачальником
     * кандидатів був префікс FTS у тій самій абетці й без жодної помилки, тож
     * «Khreshchatyk» і «аптіка» не давали зіставлячу ЖОДНОГО рядка, і його
     * правила ні до чого не застосовувались.
     *
     * Тут із запиту здобуваються корені: свій, транслітерований у кирилицю і
     * транслітерований у латиницю — по [ROOT_CHARS] літери, бо описка рідко
     * стоїть у перших трьох. Точність від кореня не потрібна: він лише набирає
     * кандидатів, вирок далі виносить [PoiTextMatch.match] за ПОВНИМ запитом.
     *
     * Згортання ([PoiTextMatch.fold]) сюди не годиться для свого ж кореня:
     * воно зливає і→и і зʼїдає мʼякий знак, тож «Сільпо» стало б «сил*» і не
     * знайшло б себе в індексі, де лежить сире «сільпо».
     */
    fun roots(raw: String): List<String> {
        val tokens = raw.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= ROOT_CHARS }
            .map { it.lowercase() }
        if (tokens.isEmpty()) return emptyList()
        val forms = LinkedHashSet<String>()
        forms += expression(tokens)
        forms += expression(tokens.map { PoiTextMatch.cyrillize(it) })
        forms += expression(tokens.map { PoiTextMatch.latinize(PoiTextMatch.fold(it)) })
        return forms.filter { it.isNotBlank() }
    }

    private fun expression(tokens: List<String>): String =
        tokens.joinToString(" ") { it.take(ROOT_CHARS) + "*" }

    /**
     * Той самий вираз БЕЗ зірки — тобто «слово цілком», а не «починається з».
     *
     * Потрібен рівно для одного питання: «чи є десь місце, яке ЗВЕТЬСЯ саме
     * так». Зірка на це не годиться — «вули*» збігається з двомастами тисячами
     * вулиць і коштує 42 мс, тоді як «вули» без зірки не збігається ні з чим
     * (у токені «вулиця» немає такого слова), а «моршин» дає три рядки за 0 мс.
     * Тобто недодрукований запит сам себе й гасить.
     */
    fun exactQuery(raw: String): String? {
        val tokens = raw.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
        if (tokens.isEmpty() || tokens.sumOf { it.length } < MIN_QUERY_CHARS) return null
        return tokens.joinToString(" ")
    }

    /** Чи є серед видачі місце, яке зветься саме так, як спитали. */
    fun hasExactName(hits: List<PoiHit>, query: String): Boolean =
        hits.any { PoiTextMatch.match(it.name, "", query)?.rank == PoiTextMatch.EXACT }

    /** Лише точні збіги НАЗВИ, найближчі попереду. */
    fun exactNamed(
        rows: List<PoiGazetteerEntity>,
        lat: Double,
        lon: Double,
        query: String,
        limit: Int,
    ): List<PoiHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return rows.asSequence()
            .map { Scored(it, haversineMeters(lat, lon, it.lat, it.lon), verdict(it, needle)) }
            .filter { it.verdict?.rank == PoiTextMatch.EXACT && it.verdict.matchedName }
            .sortedBy { it.distance }
            .take(limit)
            .map { it.toHit(needle) }
            .toList()
    }

    /**
     * Порядок для проходів, де відстань уже НЕ вирішує: спершу якість збігу.
     *
     * [rank] правильний там, де FTS довела збіг кожного рядка й лишилось
     * питання «що ближче». Ці проходи інші: вони йдуть по всій країні саме
     * тому, що поруч не знайшлось нічого, і випадковий «Бориспільський
     * провулок» за рогом не має ставати попереду міста Бориспіль.
     */
    fun rankByQuality(
        rows: List<PoiGazetteerEntity>,
        lat: Double,
        lon: Double,
        query: String,
        limit: Int = 20,
    ): List<PoiHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return rows.asSequence()
            .map { Scored(it, haversineMeters(lat, lon, it.lat, it.lon), verdict(it, needle)) }
            // Доказ мусить бути в НАЗВІ. Збіг лише за суттю місця
            // ([PoiTextMatch.KEYWORD_ONLY]) — це відповідь на «що поруч», а
            // поруч тут якраз і немає: під тривогою «укриття» показало б
            // пункт незламності за 400 км першим рядком. Імʼя — інша річ:
            // хто набрав «Бориспіль», шукає Бориспіль, де б той не був.
            .filter { it.verdict != null && it.verdict.matchedName }
            .sortedWith(compareBy<Scored> { it.verdict?.rank ?: WORST_RANK }.thenBy { it.distance })
            .take(limit)
            .map { it.toHit(needle) }
            .toList()
    }

    /**
     * A lat/lon box that certainly contains everything within [radiusMeters].
     *
     * Deliberately a box and not a circle: this is the *pre-filter* that keeps
     * SQLite from handing us every «АЗС» in Ukraine. It over-selects at the
     * corners by design — the true circle is enforced later, by real distance,
     * on the handful of rows that survive.
     *
     * The longitude degree shrinks with latitude (cos φ), which is why this is
     * not symmetric. At Kyiv's 50°N a degree of longitude is ~64 km against a
     * degree of latitude's ~111 km; using one figure for both would make the
     * box a third too narrow east-west and silently lose real hits.
     */
    fun boundingBox(lat: Double, lon: Double, radiusMeters: Int): DoubleArray {
        val dLat = radiusMeters / METERS_PER_DEG_LAT
        // Guard the poles: cos φ → 0 makes the longitude span explode to
        // infinity. Clamped, the box simply becomes the whole longitude range,
        // which is the correct answer there anyway.
        val cosLat = max(cos(Math.toRadians(lat)), 0.01)
        val dLon = radiusMeters / (METERS_PER_DEG_LAT * cosLat)
        return doubleArrayOf(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
    }

    /**
     * Rank by what a driver actually means by "best": near, then named well.
     *
     * Distance dominates on purpose. This is the whole reason FTS5's bm25 is
     * not missed: no lexical score should ever put a better-spelled match 8 km
     * behind him ahead of the adequate one 300 m ahead. Term quality only
     * breaks ties inside the same distance band — two petrol stations on the
     * same street, one of which is actually called what he typed.
     */
    fun rank(
        hits: List<PoiGazetteerEntity>,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        query: String? = null,
        limit: Int = 20,
        kinds: List<String> = emptyList(),
        rest: String = "",
    ): List<PoiHit> {
        val needle = query?.trim().orEmpty()
        val askedWords = PoiTextMatch.words(needle).toSet()
        return hits.asSequence()
            // Зіставлення рахується РАЗ на рядок, а не всередині компаратора:
            // інакше кожне порівняння перескладало б назву заново.
            .map { Scored(it, haversineMeters(lat, lon, it.lat, it.lon), verdict(it, needle)) }
            // The circle the box only approximated.
            .filter { it.distance <= radiusMeters }
            .filter { kinds.isEmpty() || belongs(it.poi, kinds, rest, askedWords) }
            .sortedWith(
                // «Банк» — це банк, а не банкомат «Райффайзен Банк» на тому ж розі;
                // «Нова пошта» — відділення Нової пошти раніше за Укрпошту ближче.
                compareBy<Scored> { if (kinds.isEmpty() || it.poi.subcategory in kinds) 0 else 1 }.thenBy {
                    // 250 m bands: inside one band, distance is noise to a
                    // driver — 120 m and 240 m are both "just there" — so the
                    // name gets to decide. Across bands, distance always wins.
                    (it.distance / DISTANCE_BAND_METERS).toInt()
                }
                    .thenBy { if (needle.isEmpty()) 0 else (it.verdict?.rank ?: WORST_RANK) }
                    .thenBy { it.distance },
            )
            .take(limit)
            .map { it.toHit(needle) }
            .toList()
    }

    /**
     * Рятувальний прохід: рядки прийшли НЕ з FTS, тож збіг доводиться тут.
     *
     * Потрібен рівно тоді, коли суворий пошук не знайшов нічого. FTS уміє лише
     * початок слова у тій самій абетці й без жодної описки — «крещатик» не
     * знаходить «Хрещатик», «Lviv» не знаходить «Львів». [PoiTextMatch] уміє,
     * але коштує повного перебору, тож перебирати дозволено тільки те, що
     * поруч: далі за це рішення відповідає межа `RESCUE_SCAN` у
     * [PoiRepository].
     *
     * Порядок тут ІНШИЙ, ніж у [rank], і навмисно: спершу якість збігу, потім
     * відстань. У суворому проході FTS уже гарантувала, що всі рядки збіглися,
     * тож там вирішує відстань. Тут збіги різної якості лежать поруч, і
     * випадкова описка за 100 м не має ставати попереду точної назви за 2 км.
     */
    fun rescue(
        rows: List<PoiGazetteerEntity>,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        query: String,
        limit: Int = 20,
    ): List<PoiHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return rows.asSequence()
            .map { Scored(it, haversineMeters(lat, lon, it.lat, it.lon), verdict(it, needle)) }
            .filter { it.verdict != null && it.distance <= radiusMeters }
            .sortedWith(compareBy<Scored> { it.verdict?.rank ?: WORST_RANK }.thenBy { it.distance })
            .take(limit)
            .map { it.toHit(needle) }
            .toList()
    }

    /**
     * ОДНЕ МІСЦЕ — ОДИН РЯДОК У ВІДПОВІДІ, навіть коли воно записане двічі.
     *
     * Та сама аптека потрапляє в газетир двома шляхами: з національного
     * довідника (id виведений з `ref`) і з живої відповіді Overpass (id —
     * сирий номер OSM). Це два різні первинні ключі, тож попереднє звуження
     * `distinctBy { it.id }` їх не бачило, і людині показувало «Подорожник»
     * двічі поспіль.
     *
     * Зшивається за [OfflinePlacePack.REF_TAG] — єдиним, що в обох рядків
     * однакове. Рядок без цього ключа (старий асет, київський `poi.json`,
     * запис із часів до цієї хвилі) звужується за id, тобто рівно як раніше:
     * зшивання нічого не ламає там, де не має чим зшивати.
     *
     * ЯКИЙ ІЗ ДВОХ ЛИШАЄТЬСЯ — не байдуже. Рядок довідника несе лише `_ref`,
     * живий несе години, телефон, поверх. Тому виграє БАГАТШИЙ на теги, а за
     * рівності — свіжіший. Протилежний вибір показав би картку без годин у
     * місті, де застосунок їх уже знав.
     *
     * Порядок відповіді (за відстанню) зберігається: [LinkedHashMap] лишає
     * ключ на позиції ПЕРШОЇ появи, навіть коли значення потім замінили.
     */
    fun collapseByRef(rows: List<PoiGazetteerEntity>): List<PoiGazetteerEntity> =
        collapse(
            rows = rows,
            key = { it.tags[OfflinePlacePack.REF_TAG] ?: it.id },
            better = { a, b -> richer(a.tags.size, a.seenAt, b.tags.size, b.seenAt) },
        )

    /**
     * Те саме зшивання для шпильок на мапі — [PoiEntity] замість рядка
     * газетира. Той самий ключ, те саме правило; різняться лише поля, у яких
     * ці двоє тримають теги й час.
     *
     * Імʼя інше, а не перевантаження, бо після стирання типів обидва мали б
     * один підпис `List` і Kotlin відмовився б їх компілювати.
     */
    fun collapsePinsByRef(rows: List<PoiEntity>): List<PoiEntity> =
        collapse(
            rows = rows,
            key = { it.tags[OfflinePlacePack.REF_TAG] ?: it.id },
            better = { a, b -> richer(a.tags.size, a.timestamp, b.tags.size, b.timestamp) },
        )

    private fun richer(aTags: Int, aSeen: Long, bTags: Int, bSeen: Long): Boolean =
        aTags > bTags || (aTags == bTags && aSeen > bSeen)

    private inline fun <T> collapse(
        rows: List<T>,
        key: (T) -> Any,
        better: (T, T) -> Boolean,
    ): List<T> {
        if (rows.size < 2) return rows
        val best = LinkedHashMap<Any, T>(rows.size)
        for (row in rows) {
            val k = key(row)
            val kept = best[k]
            if (kept == null || better(row, kept)) best[k] = row
        }
        // Немає що зшивати — віддаємо той самий список, а не його копію.
        return if (best.size == rows.size) rows else best.values.toList()
    }

    /** Рядок довідника разом із тим, що про нього вже пораховано. */
    private data class Scored(
        val poi: PoiGazetteerEntity,
        val distance: Double,
        val verdict: PoiTextMatch.Verdict?,
    ) {
        fun toHit(needle: String) = PoiHit(
            id = poi.id,
            lat = poi.lat,
            lon = poi.lon,
            name = poi.name,
            category = poi.category,
            subcategory = poi.subcategory,
            distanceMeters = distance,
            tags = poi.tags,
            // Раніше тут стояв сирий `contains` без згортання, тож рядок
            // «Кавʼярня» з друкарським апострофом не рахувався збігом із
            // запитом «кавʼярня»: місце знаходилось — і тієї ж миті
            // оголошувалось збігом лише «по суті», не за назвою.
            matchedName = needle.isEmpty() || verdict?.matchedName == true,
        )
    }

    /**
     * Питання про вид місця: місце або цього виду, або назване цим словом цілим.
     * «Вокзал» — не «Вокзальна вулиця» і не аптека «на Вокзальній, 10».
     * З уточненням («Оперний театр») уточнення мусить стояти в назві.
     */
    fun belongs(poi: PoiGazetteerEntity, kinds: List<String>, rest: String, askedWords: Set<String>): Boolean {
        if (rest.isNotBlank()) return PoiTextMatch.matchedName(poi.name, rest)
        if (poi.subcategory in kinds) return true
        return PoiTextMatch.words(poi.name).any { it in askedWords }
    }

    private fun verdict(poi: PoiGazetteerEntity, needle: String): PoiTextMatch.Verdict? =
        if (needle.isEmpty()) null else PoiTextMatch.match(poi.name, poi.keywords, needle)

    /** Гірше за будь-який ранг [PoiTextMatch] — місце, що збіглося невідомо чим. */
    private const val WORST_RANK = 9

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(abs(a))))
    }

    private fun max(a: Double, b: Double): Double = if (a > b) a else b

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val METERS_PER_DEG_LAT = 111_320.0
    private const val DISTANCE_BAND_METERS = 250.0
}
