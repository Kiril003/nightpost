package local.phantom.companion.core.data.poi

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * HIGHWAY — the gazetteer: every place PHANTOM has ever seen, kept.
 *
 * This is NOT [PoiEntity]/`poi_cache`. That table is a 15-minute viewport
 * cache with a TTL and a pruner, which is exactly right for "what should the
 * map draw right now" and exactly useless for "find me a fuel station" in a
 * dead zone: by design, it has already thrown the answer away. Offline search
 * needs a corpus that *accumulates* instead of one that expires, so the two
 * live side by side and are fed from the same Overpass responses.
 *
 * Nothing here is fetched on purpose. The corpus is a by-product of driving:
 * every viewport the operator panned, every «поруч», every «по дорозі» pours
 * its results in. A tank of petrol's worth of city is enough to answer most of
 * what he will ask offline.
 */
@Entity(
    tableName = "poi_gazetteer",
    indices = [
        // The search path always filters by a lat/lon window BEFORE ranking —
        // an FTS hit on the other side of the country is not an answer.
        Index(value = ["lat", "lon"]),
        Index(value = ["category"]),
        // Виміряно 12.09 на справжньому корпусі (496 806 рядків): без цього
        // індексу [PoiGazetteerDao.byCategoryInBox] бере діапазон ЛИШЕ за
        // широтою (`index_poi_gazetteer_lat_lon`), перебирає 52 745 рядків
        // київської коробки по одному, відсіює `subcategory` вручну й сортує
        // їх у тимчасовому B-дереві: 57–80 мс проти 0,7–3,1 мс тут. Ціна —
        // +6 МБ на базі 125 МБ. Порядок стовпців обовʼязковий: рівність
        // попереду діапазону, інакше SQLite індекс не візьме.
        Index(value = ["subcategory", "lat", "lon"]),
    ],
)
data class PoiGazetteerEntity(
    /** OSM id — stable across re-fetches, so re-seeing a place updates it. */
    @PrimaryKey val id: Long,
    val lat: Double,
    val lon: Double,
    val name: String,
    val category: String,
    val subcategory: String?,
    /** When PHANTOM last saw this place. Never used to expire it — only to
     *  break ties and to let a future compactor drop the coldest first. */
    val seenAt: Long = System.currentTimeMillis(),
    /**
     * What this place is, in words a person types — built once at write time by
     * [PoiVocabulary]. Indexed alongside the name, which is the whole reason
     * «аптека» can now find «Подорожник».
     */
    val keywords: String = "",
    /** The OSM tags themselves, for the dossier: hours, phone, floor, cuisine. */
    val tags: Map<String, String> = emptyMap(),
)

/**
 * The search index over the name AND [PoiGazetteerEntity.keywords].
 *
 * **FTS4, not FTS5 — and that is not a preference.** Android's system SQLite
 * does not compile the FTS5 module in: `CREATE VIRTUAL TABLE … USING fts5`
 * fails with `no such module: fts5`. Re-verified on metal 11.08.2026 —
 * `libsqlite.so` on SM-A566B / Android 16 contains fts2, fts3 and fts4 symbols
 * and exactly zero fts5 ones. Reaching FTS5 would mean bundling a replacement
 * SQLite under a database that already carries the vault and the whole stream
 * across twenty migrations — a large risk bought for lexical ranking that this
 * feature does not need, because a driver's results are ranked by DISTANCE.
 *
 * `tokenize=unicode61` is the load-bearing option: the default `simple`
 * tokenizer only case-folds ASCII, so «Львівська» would not match a query of
 * «львівськ». Device-verified to fold Cyrillic.
 *
 * `contentEntity` makes this an external-content index — the terms live here,
 * the rows stay in [PoiGazetteerEntity], and nothing is stored twice. Room
 * generates the content-sync triggers; [PoiGazetteerDao.upsert] additionally
 * writes the terms itself so a rebuilt index cannot drift from its content.
 */
@Fts4(
    contentEntity = PoiGazetteerEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
)
@Entity(tableName = "poi_fts")
data class PoiFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val name: String,
    val keywords: String,
)

/** One row of an offline search hit — the place, plus how far away it is. */
data class PoiHit(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val name: String,
    val category: String,
    val subcategory: String?,
    /** Metres from the point the search was anchored at. */
    val distanceMeters: Double,
    val tags: Map<String, String> = emptyMap(),
    /** False when only the keywords matched — «аптека» finding «Подорожник». */
    val matchedName: Boolean = true,
)

@Dao
interface PoiGazetteerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaces(places: List<PoiGazetteerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerms(terms: List<PoiFtsEntity>)

    /**
     * The only write path. Both tables or neither: an external-content FTS
     * index that has lost sync with its content table does not fail loudly, it
     * just quietly returns rows that no longer exist (or hides ones that do),
     * which is the worst possible failure for a driver asking for fuel.
     */
    @Transaction
    suspend fun upsert(places: List<PoiGazetteerEntity>) {
        if (places.isEmpty()) return
        insertPlaces(places)
        insertTerms(places.map { PoiFtsEntity(it.id, it.name, it.keywords) })
    }

    /**
     * Offline name search, windowed then ranked.
     *
     * The lat/lon window is applied in SQL rather than after the fact: FTS will
     * happily return every «АЗС» in the country, and sorting a thousand rows by
     * distance in Kotlin to keep five of them is work done for nothing. The
     * window is a cheap box (the index above); true distance ordering happens
     * on the survivors, in the caller, with real haversine.
     *
     * [query] must already be a valid FTS MATCH expression — see
     * [OfflinePoiSearch.toMatchQuery], which is where operator text is made
     * safe. Passing raw operator input here is a syntax error waiting to
     * happen (an apostrophe in «Кавʼярня» is enough).
     *
     * **ORDER BY — не косметика, а виправлення мовчазної вади.** Донедавна
     * запит мав `LIMIT` і не мав жодного порядку, тож із трьохсот аптек у
     * коробці SQLite повертала ті сто, до яких дійшла ПЕРШОЮ — тобто в
     * порядку запису, не за відстанню. Ранжування за відстанню відбувалося
     * вже після обрізання, отже найближча аптека могла бути викинута ще до
     * того, як хтось порівняв відстані. Порядок тут — квадрат пласкої
     * відстані ([atLat], [atLon], [lonScaleSq] = cos²φ), бо корінь не змінює
     * порядку, а SQLite не має ні haversine, ні тригонометрії.
     */
    @Query(
        """
        SELECT g.* FROM poi_gazetteer g
        JOIN poi_fts f ON f.rowid = g.id
        WHERE poi_fts MATCH :query
          AND g.lat BETWEEN :minLat AND :maxLat
          AND g.lon BETWEEN :minLon AND :maxLon
        ORDER BY ((g.lat - :atLat) * (g.lat - :atLat))
               + ((g.lon - :atLon) * (g.lon - :atLon) * :lonScaleSq)
        LIMIT :limit
        """,
    )
    suspend fun searchInBox(
        query: String,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        atLat: Double,
        atLon: Double,
        lonScaleSq: Double,
        limit: Int,
    ): List<PoiGazetteerEntity>

    /**
     * Той самий пошук БЕЗ коробки — по всій країні, найближчі попереду.
     *
     * Існує тому, що коробка була не оптимізацією, а стелею: пристрій носить
     * 496 806 рядків усієї країни, а [searchInBox] показував лише ті, що в
     * 25 км. «Бориспіль» зі Львова, «Затока» з Харкова і назва села за 30 км
     * давали нуль рядків при повному довіднику в кишені — виміряно 12.09.
     *
     * Кличеться ЛИШЕ після того, як коробка нічого не дала, і саме тому
     * дозволений повний обхід: для справжньої назви FTS віддає десятки рядків
     * («борисп*» — 80, «хрещатик*» — 45, 0,1–0,3 мс), а широкий префікс,
     * здатний коштувати десятки мілісекунд, до цього запиту не доходить —
     * він завжди щось знаходить у коробці.
     */
    @Query(
        """
        SELECT g.* FROM poi_gazetteer g
        JOIN poi_fts f ON f.rowid = g.id
        WHERE poi_fts MATCH :query
        ORDER BY ((g.lat - :atLat) * (g.lat - :atLat))
               + ((g.lon - :atLon) * (g.lon - :atLon) * :lonScaleSq)
        LIMIT :limit
        """,
    )
    suspend fun searchAnywhere(
        query: String,
        atLat: Double,
        atLon: Double,
        lonScaleSq: Double,
        limit: Int,
    ): List<PoiGazetteerEntity>

    /**
     * Category search with no text at all — «АЗС» / «кава» on the car head are
     * taps, not typing. Matches the OSM subcategory (`fuel`, `cafe`, …).
     *
     * Той самий порядок за відстанню, що й у [searchInBox], і з тієї самої
     * причини: коментар у `PoiRepository` роками стверджував, що категорійний
     * запит «lat/lon індекс уміє впорядкувати», — а `ORDER BY` тут не було
     * взагалі. У щільному місті це означало «три сотні аптек, з яких видано
     * випадкові сто».
     */
    @Query(
        """
        SELECT * FROM poi_gazetteer
        WHERE subcategory IN (:subcategories)
          AND lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        ORDER BY ((lat - :atLat) * (lat - :atLat))
               + ((lon - :atLon) * (lon - :atLon) * :lonScaleSq)
        LIMIT :limit
        """,
    )
    suspend fun byCategoryInBox(
        subcategories: List<String>,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        atLat: Double,
        atLon: Double,
        lonScaleSq: Double,
        limit: Int,
    ): List<PoiGazetteerEntity>

    /**
     * Найближчі відомі місця, БЕЗ жодного тексту й категорії — корпус для
     * рятувального проходу [OfflinePoiSearch.rescue].
     *
     * Це не «показати все навколо»: сюди звертаються лише тоді, коли суворий
     * FTS-пошук не знайшов нічого, і саме тому межа [limit] є частиною
     * контракту, а не оптимізацією. Описка й інша абетка ловляться серед
     * найближчих `limit` місць — далі за них ніхто не ручається, і це
     * записано словом у `PoiRepository.RESCUE_SCAN`.
     */
    @Query(
        """
        SELECT * FROM poi_gazetteer
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        ORDER BY ((lat - :atLat) * (lat - :atLat))
               + ((lon - :atLon) * (lon - :atLon) * :lonScaleSq)
        LIMIT :limit
        """,
    )
    suspend fun nearestInBox(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        atLat: Double,
        atLon: Double,
        lonScaleSq: Double,
        limit: Int,
    ): List<PoiGazetteerEntity>

    /**
     * [nearestInBox] без вулиць і поселень — для значків мапи. У газетирі
     * 265 тисяч вулиць і 68 тисяч поселень поруч із місцями, і в центрі міста
     * вони зʼїдали вікно найближчих рядків раніше, ніж до нього доходила аптека.
     */
    @Query(
        """
        SELECT * FROM poi_gazetteer
        WHERE category NOT IN ('street', 'place')
          AND lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        ORDER BY ((lat - :atLat) * (lat - :atLat))
               + ((lon - :atLon) * (lon - :atLon) * :lonScaleSq)
        LIMIT :limit
        """,
    )
    suspend fun nearestPlacesInBox(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        atLat: Double,
        atLon: Double,
        lonScaleSq: Double,
        limit: Int,
    ): List<PoiGazetteerEntity>

    /**
     * Everything already seen inside a viewport, freshest first — the instant
     * local answer the map draws while the live Overpass call is still in the
     * air. No text, no category: this is «what do I already know is here».
     */
    @Query(
        """
        SELECT * FROM poi_gazetteer
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        ORDER BY seenAt DESC
        LIMIT :limit
        """,
    )
    suspend fun inBox(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int,
    ): List<PoiGazetteerEntity>

    /**
     * Рядки за списком id — вихід смислового пошуку, який знає лише id.
     *
     * Повертає МЕНШЕ, ніж просили, коли частина id уже вичищена з газетира:
     * векторний індекс спечено раніше, ніж востаннє сіявся довідник, і
     * посилання на зниклий рядок — не збій, а порожнеча, яку викидає читач.
     */
    @Query("SELECT * FROM poi_gazetteer WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<PoiGazetteerEntity>

    @Query("SELECT COUNT(*) FROM poi_gazetteer")
    suspend fun size(): Int
}
