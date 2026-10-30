package local.phantom.companion.core.data.poi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlin.math.cos
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.data.geocoder.ForgeGeocoder
import local.phantom.companion.core.data.geocoder.toPoiHit
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.geosrc.valueOrNull
import local.phantom.companion.core.data.evac.ShelterRegistryVerdict
import local.phantom.companion.core.data.evac.ShelterTruth
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement
import local.phantom.companion.core.net.map.TagSelector

class PoiRepository(
    private val poiDao: PoiDao,
    /** Null = no live source at all: everything is answered from disk. */
    private val overpassClient: OverpassClient? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
    /**
     * HIGHWAY — the persistent gazetteer. Optional so the many tests that
     * construct this repository with a fake DAO keep compiling; when absent,
     * offline search simply has no corpus and says so honestly.
     */
    private val gazetteer: PoiGazetteerDao? = null,
    /**
     * The bundled OSM extract behind [getPois]. When the live Overpass call
     * comes back empty — jamming, roaming, a dead zone — the map falls back to
     * this so the icons never simply vanish. Optional: tests and non-Kyiv
     * builds run without it and just have no offline floor.
     */
    /**
     * The bundled extract, resolved on FIRST USE rather than on construction.
     *
     * A 4 MB JSON parse belongs to whoever asks for a POI, and everyone who
     * asks is already in a coroutine. Handed as a value, the parse moved to
     * whoever built the graph — which is composition, on the main thread, at
     * the very first frame of the map.
     */
    private val offlinePack: (() -> OfflinePoiPack?)? = null,
    /**
     * Геокодер кузні — країнний пакет місць, вулиць і адрес. Розвʼязується на
     * ПЕРШОМУ вжитку, як і [offlinePack]: відкриття SQLite належить тому, хто
     * питає, а не композиції першого кадру.
     */
    private val forgeGeocoder: (() -> ForgeGeocoder?)? = null,
    /**
     * УСІ поставлені пакети кузні, а не перший-ліпший. Людина в Кошицях має
     * SK, UA і PL одночасно, і пошук мусить відповідати з усіх трьох.
     */
    private val forgeGeocoders: (() -> List<ForgeGeocoder>)? = null,
    /**
     * ISO-код країни, у якій людина зараз. Від нього залежить ОДНЕ: у чиєму
     * пакеті дозволено нечіткий прохід. Null — дозволено в усіх, як було.
     */
    private val homeCountry: ((Double, Double) -> String?)? = null,
    /** The whole live stage's budget. Overpass has no deadline of its own. */
    private val liveTimeoutMs: Long = LIVE_TIMEOUT_MS,
    /** Test seam for the live viewport fetch. */
    private val liveFetch: (suspend (Double, Double, Double, Double) -> List<PoiEntity>)? = null,
) {
    /**
     * HIGHWAY — search the places PHANTOM has already seen, with no network at
     * all.
     *
     * Every Overpass response the app has ever received is poured into the
     * gazetteer ([remember]), so this answers from the road the operator has
     * actually driven. It is not a substitute for Overpass when online — it is
     * what remains when the tunnel, the jamming or the dead zone takes Overpass
     * away, which on a highway is exactly when a driver asks for fuel.
     *
     * [query] is operator text, not FTS syntax: it is sanitised here. A blank
     * query with [subcategories] is a category browse (the car head's chips).
     */
    suspend fun searchOffline(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int = 25_000,
        subcategories: List<String> = emptyList(),
        limit: Int = 20,
    ): List<PoiHit> = searchOfflineVerdict(query, lat, lon, radiusMeters, subcategories, limit).hits

    /**
     * Той самий пошук, але з ВИРОКОМ — бо «нічого не знайшов» і «шукати нема
     * де» це два різні речення для людини, а список у нуль рядків їх зливав.
     *
     * Порожній довідник — не результат пошуку, а стан пристрою: пакет місць не
     * встановлено, або телефон іще жодного разу не бачив мережі в цьому місті.
     * Сказати на це «нічого не знайдено» — збрехати про світ замість того, щоб
     * зізнатися про себе.
     */
    /**
     * Скільки місць пристрій узагалі знає. Нуль — це НЕ «нічого не знайдено»,
     * це «шукати нема де», і для людини це різні речення.
     *
     * Існує тому, що автоголова не отримує вироку пошуку, а лише список; без
     * цього числа вона малювала «поруч такого немає» навіть із порожнім
     * довідником — і ховала від нас справжню причину скарги «пошук не
     * спроможний ні на що».
     */
    suspend fun corpusSize(): Int {
        val dao = gazetteer ?: return 0
        return runCatching { dao.size() }.getOrDefault(0)
    }

    suspend fun searchOfflineVerdict(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int = 25_000,
        subcategories: List<String> = emptyList(),
        limit: Int = 20,
    ): OfflineSearch {
        val asked = PoiVocabulary.brandSpelling(query) ?: query
        // «Городоцька 12»: номерів будинків довідник не несе, і поки поруч немає
        // збігу з номером, вулиця поруч краща за «№12» аптеки за 65 км.
        val street = (if (subcategories.isEmpty()) HOUSE_NUMBER.find(asked.trim())?.groupValues?.get(1) else null)
            ?: return searchSpelled(asked, lat, lon, radiusMeters, subcategories, limit, widen = true)
        searchSpelled(asked, lat, lon, radiusMeters, subcategories, limit, widen = false)
            .let { if (it is OfflineSearch.Hits) return it }
        searchSpelled(street, lat, lon, radiusMeters, subcategories, limit, widen = false)
            .let { if (it is OfflineSearch.Hits) return it }
        return searchSpelled(asked, lat, lon, radiusMeters, subcategories, limit, widen = true)
    }

    private suspend fun searchSpelled(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        subcategories: List<String>,
        limit: Int,
        widen: Boolean,
    ): OfflineSearch {
        // Пакет кузні йде ПЕРШИМ і мовчки поступається: він знає вулиці й
        // адреси країни, яких газетир не бачив ніколи, але накритий лише там,
        // де пакет завантажено. Чеський пакет у Києві не відповідає за Київ:
        // його McDonald's за 1100 км поступається київському газетиру.
        val forge = forgeSearch(query, lat, lon, limit)
        if (forge != null && forge.hits.any { it.distanceMeters <= radiusMeters }) return forge
        val local = gazetteerSpelled(query, lat, lon, radiusMeters, subcategories, limit, widen)
        if (forge == null) return local
        val nameAsked = widen && subcategories.isEmpty() && PoiVocabulary.intent(query).isEmpty()
        if (local is OfflineSearch.Hits) {
            // «Coffee» — це кава поруч, а не кіоск «Coffee» за 400 км, хоч назва й точна.
            val exactHere = local.hits.any { named(it.name, query) }
            return if (!nameAsked || exactHere || forge.hits.none { named(it.name, query) }) local else forge
        }
        if (!nameAsked) return local
        return when (local) {
            is OfflineSearch.NothingHere -> forge
            // У Празі з чеським пакетом «Брно» — за 180 км, і це відповідь; у Празі з молдовським — ні.
            else -> if (forgeCoversHere(lat, lon)) forge else local
        }
    }

    private suspend fun forgeCoversHere(lat: Double, lon: Double): Boolean {
        val box = OfflinePoiSearch.boundingBox(lat, lon, COVERAGE_RADIUS_M)
        return forgePins(box[0], box[2], box[1], box[3], 1).isNotEmpty()
    }

    private fun named(name: String, query: String) =
        PoiTextMatch.match(name, "", query)?.rank == PoiTextMatch.EXACT

    private suspend fun gazetteerSpelled(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        subcategories: List<String>,
        limit: Int,
        widen: Boolean,
    ): OfflineSearch {
        val dao = gazetteer ?: return OfflineSearch.NowhereToLook
        val box = OfflinePoiSearch.boundingBox(lat, lon, radiusMeters)
        val match = OfflinePoiSearch.toMatchQuery(query)

        // Over-fetch from SQL, then rank: the box is square and the answer is
        // round, so some of what SQL returns is going to be thrown away.
        val sqlLimit = limit * OVERFETCH

        // Довгота коротшає з широтою, тож без cos²φ порядок «за відстанню» у
        // SQL був би витягнутий зі сходу на захід. Той самий множник, що й у
        // [OfflinePoiSearch.boundingBox], лише в квадраті — бо порядок
        // рахується за квадратом відстані.
        val lonScale = cos(Math.toRadians(lat))
        val lonScaleSq = lonScale * lonScale

        // Two questions, asked at once, because the operator's one word is
        // usually both. «Сільпо» is a name. «Аптека» is a kind. «Фора» is a
        // name that is also nearly the word «форум». Running only the text
        // match answers the first and misses the second: a query of «кава»
        // MATCHes the keyword column of every café, but a query of «аптек»
        // that hits three hundred pharmacies inside the box would be truncated
        // by the SQL LIMIT. Обидва запити тепер упорядковані за відстанню в
        // SQL, тож обрізає найдальших, а не випадкових.
        val kind = if (subcategories.isEmpty()) PoiVocabulary.kindOf(query) else PoiVocabulary.Kind(subcategories, "")
        val intent = kind.kinds
        val textRows = match?.let {
            dao.searchInBox(
                query = it,
                minLat = box[0], maxLat = box[1], minLon = box[2], maxLon = box[3],
                atLat = lat, atLon = lon, lonScaleSq = lonScaleSq,
                limit = sqlLimit,
            )
        }.orEmpty()
        val categoryRows = if (intent.isEmpty()) {
            emptyList()
        } else {
            dao.byCategoryInBox(
                subcategories = intent,
                minLat = box[0], maxLat = box[1], minLon = box[2], maxLon = box[3],
                atLat = lat, atLon = lon, lonScaleSq = lonScaleSq,
                limit = sqlLimit,
            ).let { rows ->
                // «Оперний театр» — театр, що зветься «оперний», а не будь-який театр поруч.
                if (kind.rest.isEmpty()) rows else rows.filter { PoiTextMatch.match(it.name, "", kind.rest) != null }
            }
        }

        val strict = if (textRows.isEmpty() && categoryRows.isEmpty()) {
            emptyList()
        } else {
            OfflinePoiSearch.rank(
                hits = OfflinePoiSearch.collapseByRef(textRows + categoryRows),
                lat = lat,
                lon = lon,
                radiusMeters = radiusMeters,
                query = query,
                limit = limit,
                kinds = intent,
                rest = kind.rest,
            )
        }
        if (strict.isNotEmpty()) {
            // МІСТО ПОПЕРЕДУ ОДНОКОРЕНЕВОЇ ВУЛИЦІ ЗА РОГОМ.
            //
            // Коробка чесно віддає найближче, і це правильно доти, доки поруч
            // є те, що людина мала на увазі. «Моршин» зі Львова давав
            // «Моршинську вулицю» за 300 м, а саме місто — за 80 км — не
            // показував узагалі: коробка знайшла ЩОСЬ і далі ніхто не питав.
            // Тому, коли серед близьких немає жодного місця з такою САМЕ
            // назвою, один додатковий запит по країні шукає точний збіг і
            // веде видачу. Ціна виміряна: «моршин» — 3 рядки, 0 мс; недо-
            // друковане «вули» — 0 рядків, бо вираз тут без зірки.
            val lead = if (intent.isEmpty() && !OfflinePoiSearch.hasExactName(strict, query)) {
                OfflinePoiSearch.exactQuery(query)?.let { exact ->
                    OfflinePoiSearch.exactNamed(
                        rows = OfflinePoiSearch.collapseByRef(
                            dao.searchAnywhere(exact, lat, lon, lonScaleSq, EXACT_SCAN),
                        ),
                        lat = lat, lon = lon, query = query, limit = EXACT_LEAD,
                    )
                }.orEmpty()
            } else {
                emptyList()
            }
            return OfflineSearch.Hits(
                if (lead.isEmpty()) strict else (lead + strict).distinctBy { it.id }.take(limit),
            )
        }

        // Рятувальний прохід. FTS уміє лише початок слова в тій самій абетці й
        // без жодної описки: «крещатик» не знаходить «Хрещатик», «Lviv» не
        // знаходить «Львів». Тому коли суворий пошук порожній — і ЛИШЕ тоді —
        // беремо найближчі місця й зіставляємо їх у Kotlin.
        val rescued = if (query.trim().length >= OfflinePoiSearch.MIN_QUERY_CHARS) {
            OfflinePoiSearch.rescue(
                // Зшивання ДО зіставлення, а не після: інакше дубль аптеки
                // зʼїдав би одне з [RESCUE_SCAN] місць, які цей прохід узагалі
                // встигає роздивитись.
                rows = OfflinePoiSearch.collapseByRef(
                    dao.nearestInBox(
                        minLat = box[0], maxLat = box[1], minLon = box[2], maxLon = box[3],
                        atLat = lat, atLon = lon, lonScaleSq = lonScaleSq,
                        limit = RESCUE_SCAN,
                    ),
                ),
                lat = lat,
                lon = lon,
                radiusMeters = radiusMeters,
                query = query,
                limit = limit,
            )
        } else {
            emptyList()
        }
            // «railway station» без жодного вокзалу поруч не має відповідати заправкою, у словах якої є «station».
            .filter {
                when {
                    intent.isEmpty() -> true
                    kind.rest.isNotEmpty() -> PoiTextMatch.matchedName(it.name, kind.rest)
                    else -> it.subcategory in intent || it.matchedName
                }
            }
        if (rescued.isNotEmpty()) return OfflineSearch.Hits(rescued)

        // Довідник тут нічого не знає — людина за межами пакета. Назва з
        // іншої країни за тисячу кілометрів — не відповідь, а «шукати нема де».
        val covered = dao.nearestInBox(
            minLat = box[0], maxLat = box[1], minLon = box[2], maxLon = box[3],
            atLat = lat, atLon = lon, lonScaleSq = lonScaleSq, limit = 1,
        ).isNotEmpty()
        if (!covered) return OfflineSearch.NowhereToLook
        if (!widen) return OfflineSearch.NothingHere(corpusSize = 0)

        // ПИТАННЯ ПРО ВИД МІСЦЯ НЕ РОЗШИРЮЄТЬСЯ. «Аптека» і «укриття» питають
        // «що поруч», і чесна відповідь на порожнечу — «поруч такого немає», а
        // не аптека за 400 км. Розширюється лише НАЗВА: людина, що набрала
        // «Бориспіль», справді шукає Бориспіль, де б він не був.
        if (intent.isNotEmpty()) {
            val corpusForCategory = runCatching { dao.size() }.getOrDefault(0)
            return if (corpusForCategory == 0) {
                OfflineSearch.NowhereToLook
            } else {
                OfflineSearch.NothingHere(corpusForCategory)
            }
        }

        // ЗА МЕЖАМИ КОРОБКИ. Довідник країнний, а пошук був районний: 496 806
        // рядків у кишені й вікно в 25 км навколо людини. «Бориспіль» зі
        // Львова, «Затока» з Харкова, назва села за 30 км — нуль рядків при
        // повному довіднику. Сюди доходить лише те, що поруч не знайшлось.
        val wide = if (match != null) {
            OfflinePoiSearch.rankByQuality(
                rows = OfflinePoiSearch.collapseByRef(
                    dao.searchAnywhere(match, lat, lon, lonScaleSq, WIDE_SCAN),
                ),
                lat = lat, lon = lon, query = query, limit = limit,
            )
        } else {
            emptyList()
        }
        if (wide.isNotEmpty()) return OfflineSearch.Hits(wide)

        // ЗА КОРЕНЕМ. Остання спроба, і єдина, що дає зіставлячу кандидатів в
        // ІНШІЙ абетці й з опискою: доти «Khreshchatyk» і «аптіка» не давали
        // FTS жодного рядка, тож правила [PoiTextMatch] про дві абетки й одну
        // помилку не мали до чого застосуватись.
        for (root in OfflinePoiSearch.roots(query)) {
            val byRoot = OfflinePoiSearch.rankByQuality(
                rows = OfflinePoiSearch.collapseByRef(
                    dao.searchAnywhere(root, lat, lon, lonScaleSq, ROOT_SCAN),
                ),
                lat = lat, lon = lon, query = query, limit = limit,
            )
            if (byRoot.isNotEmpty()) return OfflineSearch.Hits(byRoot)
        }

        // Порожньо. Тепер — і тільки тепер — питаємо, чи було де шукати.
        val corpus = runCatching { dao.size() }.getOrDefault(0)
        return if (corpus == 0) OfflineSearch.NowhereToLook else OfflineSearch.NothingHere(corpus)
    }

    /**
     * Null — «пакета кузні тут немає або він мовчить», і тоді відповідає
     * газетир. Порожнеча пакета ще не вирок: місце, яке телефон бачив сам,
     * може лежати поза країною пакета.
     */
    private suspend fun forgeSearch(query: String, lat: Double, lon: Double, limit: Int): OfflineSearch? {
        val many = forgeGeocoders
        val one = forgeGeocoder
        if (many == null && one == null) return null
        return withContext(Dispatchers.IO) {
            val packs = when {
                many != null -> runCatching { many.invoke() }.getOrDefault(emptyList())
                else -> listOfNotNull(runCatching { one?.invoke() }.getOrNull())
            }
            if (packs.isEmpty()) return@withContext null

            // Нечіткий прохід — ЛИШЕ в пакеті країни, де людина стоїть.
            //
            // Він рятує описку поруч, а коштує повного `tri:` АБО з
            // упорядкуванням за відстанню. Виміряно 12.09 на трьох пакетах із
            // Кошиць: «Kosice» в українському пакеті — 308 мс, «lekáreň» —
            // 306 мс, і жодне з цих чисел не могло дати відповіді. Без нього
            // найдовший пакет падає з 308 мс до 87 мс.
            val home = if (packs.size > 1) {
                runCatching { homeCountry?.invoke(lat, lon) }.getOrNull()
            } else {
                null
            }

            val hits = packs.flatMap { geocoder ->
                val here = home == null || geocoder.country.equals(home, ignoreCase = true)
                runCatching { geocoder.search(query, lat to lon, limit, allowFuzzy = here) }
                    .getOrDefault(emptyList())
                    .map { hit ->
                        val tags = runCatching { geocoder.tagsOf(hit) }.getOrDefault(emptyMap())
                        // Країна їде в теги, бо список із двох «Нових» — у
                        // Словаччині й в Україні — без неї неможливо читати.
                        hit.toPoiHit(
                            if (geocoder.country.isBlank()) {
                                tags
                            } else {
                                tags + (COUNTRY_TAG to geocoder.country)
                            },
                        ) to hit.score
                    }
            }
            if (hits.isEmpty()) {
                null
            } else {
                // Зведення пакетів: спершу бал (точна назва в сусідній країні
                // бʼє випадковий префікс за рогом), потім відстань.
                OfflineSearch.Hits(
                    hits.sortedWith(compareBy({ it.second }, { it.first.distanceMeters }))
                        .map { it.first }
                        .distinctBy { it.id }
                        .take(limit),
                )
            }
        }
    }

    /**
     * Чим скінчився пошук на пристрої.
     *
     * Три стани, а не два, бо саме їх плутав список у нуль рядків:
     * [NothingHere] — «є де шукати, цього тут немає», а [NowhereToLook] —
     * «шукати нема де». Друге лікується встановленням пакета місць, перше не
     * лікується нічим, і сказати людині не те означає відправити її робити
     * марну роботу.
     */
    sealed interface OfflineSearch {
        val hits: List<PoiHit>

        /** Знайдено. */
        data class Hits(override val hits: List<PoiHit>) : OfflineSearch

        /** Довідник має [corpusSize] місць, і жодне з них не підходить. */
        data class NothingHere(val corpusSize: Int) : OfflineSearch {
            override val hits: List<PoiHit> get() = emptyList()
        }

        /** Довідника немає взагалі: пакет місць не встановлено. */
        data object NowhereToLook : OfflineSearch {
            override val hits: List<PoiHit> get() = emptyList()
        }
    }

    /** How many places PHANTOM can answer from with no network. */
    suspend fun gazetteerSize(): Int = gazetteer?.size() ?: 0

    /**
     * Pour the bundled extract into the searchable gazetteer, once per bake.
     *
     * Until this existed the pack was drawn and never found: [getPois] read it
     * for pins, [searchOffline] read only the gazetteer, and the gazetteer only
     * filled from live Overpass answers. A phone that had never had a working
     * network in Kyiv could show a screen full of pharmacies and answer
     * «аптека» with nothing.
     *
     * Returns how many places were written. Chunked so one transaction never
     * holds tens of thousands of rows, and cancellable — this runs off the hot
     * path at startup and must yield the moment anything else needs the disk.
     */
    suspend fun seedGazetteerFromPack(): Int {
        val dao = gazetteer ?: return 0
        val pack = offlinePack?.invoke() ?: return 0
        var written = 0
        for (chunk in pack.chunked()) {
            val rows = chunk.mapNotNull { it.toGazetteerRow() }
            if (rows.isEmpty()) continue
            runCatching { dao.upsert(rows) }.onSuccess { written += rows.size }
        }
        return written
    }

    /**
     * Pour a fetch into the gazetteer. Fire-and-forget by contract: a failure
     * to *remember* a place must never fail the fetch the operator asked for.
     * Nameless OSM nodes are dropped — a corpus of «Unknown» is not searchable.
     */
    private suspend fun remember(entities: List<PoiEntity>) {
        val dao = gazetteer ?: return
        val rows = entities.mapNotNull { it.toGazetteerRow() }
        if (rows.isEmpty()) return
        runCatching { dao.upsert(rows) }
    }

    private fun PoiEntity.toGazetteerRow(): PoiGazetteerEntity? {
        if (name.isBlank() || name == "Unknown") return null
        return PoiGazetteerEntity(
            id = id,
            lat = lat,
            lon = lon,
            name = name,
            category = category,
            subcategory = subcategory,
            seenAt = clockMs(),
            keywords = PoiVocabulary.keywords(tags, category, subcategory),
            tags = tags,
        )
    }
    /**
     * POIs for a viewport, local first.
     *
     * Emits at most twice: what PHANTOM already has on disk (instant — TTL
     * cache, bundled pack, gazetteer), then the live Overpass answer once it
     * lands inside [liveTimeoutMs]. Blocking on Overpass for the first pin is
     * why the map used to sit bare for seconds; the local answer is never
     * worse than nothing, and the live one only ever adds to it.
     */
    fun getPois(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        zoom: Double = DEFAULT_ZOOM,
    ): Flow<List<PoiEntity>> = flow {
        // `clearExpired` existed on the DAO but was never called anywhere in
        // the app — poi_cache only ever grew.
        runCatching { poiDao.clearExpired(clockMs() - CACHE_TTL_MS) }

        val cap = PoiDensity.capForZoom(zoom)
        val centerLat = (minLat + maxLat) / 2
        val centerLon = (minLon + maxLon) / 2

        val cached = runCatching {
            poiDao.getPoisInBoundingBox(minLat, maxLat, minLon, maxLon).first()
        }.getOrDefault(emptyList())

        // Зшивання, а не `distinctBy { it.id }`: у пари «довідник + жива
        // відповідь» два різні id на одне місце, і просте звуження за id
        // лишало б випадкового з двох — інколи бідніший на теги.
        val local = OfflinePoiSearch.collapsePinsByRef(
            cached + diskFloor(minLat, minLon, maxLat, maxLon, cap),
        )

        // `cached.isEmpty()` alone is not a real staleness check: the query
        // matches ANY previously-cached POI in this lat/lon range, even one
        // stray point left over from a much wider fetch. Too few rows to be a
        // real fetch counts as none.
        //
        // ЩО ТУТ РОЗДІЛЕНО, 17.09. Досі цей заслін вирішував ДВІ речі одним
        // `return`: «не ходи в мережу знову» (правильно) і «віддай самий лише
        // кеш» (тихо згубна). Пʼяти рядків — `MIN_CACHED_DENSITY` — досить,
        // щоб на чверть години вимкнути весь національний довідник: 176 558
        // місць країни й увесь вкладений пак. Наслідок адресний — запит
        // Overpass знає `drinking_water`, але не знає `natural=spring` і
        // `man_made=water_well`, тобто 9 830 джерел і криниць. Один успішний
        // похід у мережу прибирав із мапи саме воду, рівно тоді, коли людина
        // вже бачила, що мережа є, і повірила б мапі.
        //
        // Тепер заслін стримує лише похід у мережу. Диск під ним лишається —
        // тим самим виразом, що й нижче, а не другою його редакцією.
        if (cached.size >= MIN_CACHED_DENSITY) {
            emit(PoiDensity.rank(local, centerLat, centerLon, cap))
            return@flow
        }

        if (local.isNotEmpty()) emit(PoiDensity.rank(local, centerLat, centerLon, cap))

        val live = try {
            withTimeoutOrNull(liveTimeoutMs) { fetchLive(minLat, minLon, maxLat, maxLon) }.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            emptyList()
        }

        if (live.isNotEmpty()) {
            runCatching { poiDao.insertPois(live) }
            // Everything the map draws, PHANTOM also keeps — the TTL cache
            // forgets in 15 minutes, the gazetteer does not.
            remember(live)
            emit(PoiDensity.rank(live + local, centerLat, centerLon, cap))
        } else if (local.isEmpty()) {
            // Honest: nothing here. Also clears whatever the last viewport drew.
            emit(emptyList())
        }
    }

    /**
     * What is already on disk for this box. The bundled pack is not written back
     * into the TTL cache: its rows would masquerade as a real fetch and suppress
     * the next live retry once the network returns.
     *
     * ВІКНО БЕРЕТЬСЯ ВІД ЦЕНТРА КАДРУ, А НЕ ВІД ПІВНОЧІ.
     *
     * Довідник віддавав `inBox`, а той упорядкований `ORDER BY seenAt DESC` —
     * «найсвіжіше побачене першим». Для рядків, які телефон СПРАВДІ бачив
     * живими, це має сенс. Для національного корпусу — ні: усі 176 558 рядків
     * засіваються одним потоком, тож `seenAt` у них це порядок РЯДКА У ФАЙЛІ, а
     * файл упорядкований за широтою (44.38 → 52.34). Отже `DESC` означало
     * «найпівнічніші», і `LIMIT cap * 4` вирізав із кадру смугу вздовж його
     * верхнього краю.
     *
     * Заміряно 17.09 на справжньому корпусі, найщільніші міські кадри:
     *   z13, стеля 25, вікно 100 — корпус покривав 17–23% ВИСОТИ кадру,
     *   нижній край смуги стояв на 77–82% від низу екрана.
     * Тобто нижні дві третини мапи не мали шансу показати нічого з довідника,
     * і жодне ранжування цього не лікує: ті рядки до нього не доходили.
     *
     * Та сама пастка, яку вже названо на id: властивість ФАЙЛУ, вжита як
     * властивість МІСЦЯ. Ліки вже лежали написані поруч — `nearestInBox`
     * упорядкований за квадратом відстані з поправкою cos²φ; його кликав лише
     * рятувальний прохід пошуку. Пак цієї вади не мав: [OfflinePoiPack.inBox]
     * сортує від центра від самого початку.
     */
    private suspend fun diskFloor(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        cap: Int,
    ): List<PoiEntity> {
        val pack = offlinePack?.invoke()
            ?.inBox(minLat, minLon, maxLat, maxLon, limit = cap * 4).orEmpty()
        val atLat = (minLat + maxLat) / 2
        val atLon = (minLon + maxLon) / 2
        val lonScale = cos(Math.toRadians(atLat))
        val seen = runCatching {
            gazetteer?.nearestPlacesInBox(
                minLat = minLat, maxLat = maxLat, minLon = minLon, maxLon = maxLon,
                atLat = atLat, atLon = atLon, lonScaleSq = lonScale * lonScale,
                limit = cap * 4,
            ).orEmpty()
        }.getOrDefault(emptyList())
        return pack + forgePins(minLat, minLon, maxLat, maxLon, cap * 4) + seen.map {
            PoiEntity(
                id = it.id,
                lat = it.lat,
                lon = it.lon,
                name = it.name,
                category = it.category,
                subcategory = it.subcategory,
                tags = it.tags,
            )
        }
    }

    // Пакет кузні несе їжу, житло, вокзали й храми, яких немає в places.tsv, і
    // за кордоном він єдине джерело. Досі його читав лише пошук, а мапа — ні.
    private suspend fun forgePins(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        limit: Int,
    ): List<PoiEntity> {
        if (forgeGeocoders == null && forgeGeocoder == null) return emptyList()
        return withContext(Dispatchers.IO) {
            val packs = forgePacks()
            packs.flatMap { pack ->
                runCatching { pack.pinsInBox(minLat, minLon, maxLat, maxLon, limit) }.getOrDefault(emptyList())
            }
        }
    }

    private suspend fun fetchLive(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<PoiEntity> {
        liveFetch?.let { return it(minLat, minLon, maxLat, maxLon) }
        val client = overpassClient ?: return emptyList()
        return client.fetchPois(minLat, minLon, maxLat, maxLon).mapNotNull { it.toEntity() }
    }

    /**
     * M-E01 — "поруч" category search around a point. Does NOT touch the
     * bbox cache (results are ephemeral, sorted by distance by the caller).
     * [selector] is a raw Overpass tag selector (e.g. `amenity=pharmacy`).
     * What the device already knows first (pack, gazetteer, forge); Overpass
     * only when that floor is empty.
     */
    suspend fun getNearby(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        selector: String,
    ): List<PoiEntity> = getNearbyResult(lat, lon, radiusMeters, selector).valueOrNull().orEmpty()

    /**
     * Скільки сертифікованих сховищ пакет нарахував — питання про ПРИСТРІЙ.
     *
     * Читається із заголовка пакета, а не виводиться з наявності тега:
     * альтанка з `shelter_type=gazebo` теж несе «розрізнювач», і висновок за
     * ключем зробив би застосунок упевнено неправим у другий бік.
     */
    fun offlineShelterRegistry(): ShelterRegistryVerdict {
        val pack = offlinePack?.invoke() ?: return ShelterRegistryVerdict.ABSENT
        if (pack.size == 0) return ShelterRegistryVerdict.ABSENT
        val counted = pack.certifiedShelters ?: return ShelterRegistryVerdict.SILENT
        return if (counted > 0) ShelterRegistryVerdict.PRESENT else ShelterRegistryVerdict.SILENT
    }

    /**
     * [getNearby] with the source's own verdict kept. An unreachable Overpass
     * and an area with no pharmacies both used to arrive as an empty list;
     * only the offline floor's own emptiness is honestly «тут нічого нема».
     */
    suspend fun getNearbyResult(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        selector: String,
    ): GeoResult<List<PoiEntity>> {
        // Спершу те, що пристрій уже знає: мертве дзеркало Overpass висіло тут десятки секунд.
        val floor = nearbyFloor(lat, lon, radiusMeters, selector)
        if (floor.isNotEmpty()) return GeoResult.Ok(floor)
        val probe = overpassClient?.let { client ->
            try {
                client.fetchAroundResult(lat, lon, radiusMeters, selector)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                GeoResult.Unavailable(SourceUnavailable.Transport(t.javaClass.simpleName))
            }
        }
        val live = (probe as? GeoResult.Ok)?.value?.mapNotNull { it.toEntity() }.orEmpty()
        if (live.isNotEmpty()) return GeoResult.Ok(live.also { remember(it) })
        return (probe as? GeoResult.Unavailable) ?: GeoResult.Ok(emptyList())
    }

    private suspend fun nearbyFloor(lat: Double, lon: Double, radiusMeters: Int, selector: String): List<PoiEntity> =
        // Київський витяг — лише Київ. Решта країни лежить у газетирі, закордон — у пакетах кузні.
        withoutTileTwins(
            OfflinePoiSearch.collapsePinsByRef(
                offlinePack?.invoke()?.nearby(lat, lon, radiusMeters, selector).orEmpty() +
                    gazetteerNearby(lat, lon, radiusMeters, selector) +
                    forgeNearby(lat, lon, radiusMeters, selector),
            ),
        ).sortedBy { OfflinePoiSearch.haversineMeters(lat, lon, it.lat, it.lon) }.take(NEARBY_LIMIT)

    private fun withoutTileTwins(rows: List<PoiEntity>): List<PoiEntity> {
        val rich = rows.filterNot { TilePlaces.isTile(it.tags) }
        if (rich.size == rows.size || rich.isEmpty()) return rows
        return rows.filter { row ->
            !TilePlaces.isTile(row.tags) || rich.none {
                TilePlaces.sameName(it.name, row.name) &&
                    OfflinePoiSearch.haversineMeters(it.lat, it.lon, row.lat, row.lon) <= TilePlaces.SAME_PLACE_M
            }
        }
    }

    /**
     * Місця, які мапа вже намалювала з плиток, — у газетир, щоб чіпи, «Поруч» і
     * пошук знаходили саме те, що людина бачить. Двійник уже відомого місця
     * (та сама назва в межах [TilePlaces.SAME_PLACE_M]) не пишеться.
     */
    suspend fun rememberTilePlaces(places: List<PoiEntity>): Int {
        val dao = gazetteer ?: return 0
        if (places.isEmpty()) return 0
        val pad = 0.001
        val minLat = places.minOf { it.lat } - pad
        val maxLat = places.maxOf { it.lat } + pad
        val minLon = places.minOf { it.lon } - pad
        val maxLon = places.maxOf { it.lon } + pad
        val atLat = (minLat + maxLat) / 2
        val k = cos(Math.toRadians(atLat)).let { it * it }
        val known = runCatching {
            dao.nearestPlacesInBox(minLat, maxLat, minLon, maxLon, atLat, (minLon + maxLon) / 2, k, places.size * 4 + 500)
        }.getOrDefault(emptyList())
        val rows = withContext(Dispatchers.Default) {
            val byName = known.groupBy { it.name.trim().lowercase() }
            places.filter { p ->
                byName[p.name.trim().lowercase()].orEmpty().none {
                    it.id != p.id && OfflinePoiSearch.haversineMeters(it.lat, it.lon, p.lat, p.lon) <= TilePlaces.SAME_PLACE_M
                }
            }.mapNotNull { it.toGazetteerRow() }
        }
        if (rows.isEmpty()) return 0
        return runCatching { dao.upsert(rows) }.map { rows.size }.getOrDefault(0)
    }

    /** Чи знає пристрій хоч одне місце в 25 км — інакше «поруч нічого» було б неправдою про світ. */
    suspend fun knowsPlacesNear(lat: Double, lon: Double): Boolean {
        val box = OfflinePoiSearch.boundingBox(lat, lon, COVERAGE_RADIUS_M)
        if (offlinePack?.invoke()?.inBox(box[0], box[2], box[1], box[3], limit = 1).orEmpty().isNotEmpty()) return true
        val k = cos(Math.toRadians(lat)).let { it * it }
        val seen = runCatching {
            gazetteer?.nearestPlacesInBox(box[0], box[1], box[2], box[3], lat, lon, k, 1).orEmpty()
        }.getOrDefault(emptyList())
        return seen.isNotEmpty() || forgePins(box[0], box[2], box[1], box[3], 1).isNotEmpty()
    }

    /**
     * Чи везе пристрій без мережі місця цього виду тут. Довідник країни не несе
     * кавʼярень і вокзалів; порожнеча за ними у Львові — незнання, а не світ.
     */
    suspend fun offlineCarries(lat: Double, lon: Double, selector: String): Boolean {
        val values = TagSelector.clauses(selector).flatMap { c -> if (c.regex) c.values.flatMap { it.split('|') } else c.values }
        if (values.any { it in NationalCorpus.KINDS }) return true
        if (offlinePack?.invoke()?.nearby(lat, lon, COVERAGE_RADIUS_M, selector).orEmpty().isNotEmpty()) return true
        return forgeCoversHere(lat, lon)
    }

    private suspend fun gazetteerNearby(lat: Double, lon: Double, radiusMeters: Int, selector: String): List<PoiEntity> {
        val dao = gazetteer ?: return emptyList()
        val clauses = TagSelector.clauses(selector)
        val values = clauses.flatMap { c -> if (c.regex) c.values.flatMap { it.split('|') } else c.values }
        if (values.isEmpty()) return emptyList()
        val box = OfflinePoiSearch.boundingBox(lat, lon, radiusMeters)
        val k = cos(Math.toRadians(lat)).let { it * it }
        return runCatching {
            dao.byCategoryInBox(values, box[0], box[1], box[2], box[3], lat, lon, k, NEARBY_LIMIT * 2)
        }.getOrDefault(emptyList())
            .filter { TagSelector.matches(clauses, it.tags, it.category, it.subcategory) }
            .filter { OfflinePoiSearch.haversineMeters(lat, lon, it.lat, it.lon) <= radiusMeters }
            .map { PoiEntity(it.id, it.lat, it.lon, it.name, it.category, it.subcategory, it.tags) }
    }

    private fun forgePacks(): List<ForgeGeocoder> {
        forgeGeocoders?.let { many -> return runCatching { many.invoke() }.getOrDefault(emptyList()) }
        return listOfNotNull(runCatching { forgeGeocoder?.invoke() }.getOrNull())
    }

    private suspend fun forgeNearby(lat: Double, lon: Double, radiusMeters: Int, selector: String): List<PoiEntity> {
        if (forgeGeocoders == null && forgeGeocoder == null) return emptyList()
        return withContext(Dispatchers.IO) {
            val packs = forgePacks()
            packs.flatMap { runCatching { it.nearby(lat, lon, radiusMeters, selector, NEARBY_LIMIT) }.getOrDefault(emptyList()) }
        }
    }

    /**
     * Brand / name flood — every POI whose name/brand/operator matches
     * [query] near a point («Нова Пошта», «АТБ», «Сільпо»). Ephemeral like
     * [getNearby]; results are remembered so they back offline search later.
     */
    suspend fun getNearbyByName(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int = 5_000,
    ): List<PoiEntity> = getNearbyByNameResult(query, lat, lon, radiusMeters).valueOrNull().orEmpty()

    suspend fun getNearbyByNameResult(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int = 5_000,
    ): GeoResult<List<PoiEntity>> {
        val known = runCatching { searchOffline(query, lat, lon, radiusMeters, limit = BY_NAME_LIMIT) }.getOrDefault(emptyList())
            .filter { it.category != "street" && it.category != "place" }
            .map { PoiEntity(it.id, it.lat, it.lon, it.name, it.category, it.subcategory, it.tags) }
        if (known.isNotEmpty()) return GeoResult.Ok(known)
        val client = overpassClient ?: return GeoResult.Unavailable(SourceUnavailable.RequestRejected(NO_CLIENT))
        return when (val probe = client.fetchByNameResult(query, lat, lon, radiusMeters)) {
            is GeoResult.Ok -> GeoResult.Ok(probe.value.mapNotNull { it.toEntity() }.also { remember(it) })
            is GeoResult.Unavailable -> probe
        }
    }

    /**
     * NAV-Ω1 — «по дорозі»: category search along a route, ordered by
     * distance ALONG the way (first-reachable first), hits further than
     * ~400 м off the corridor dropped. Ephemeral like [getNearby].
     */
    suspend fun getAlongRoute(
        route: List<Pair<Double, Double>>,
        selector: String,
        radiusMeters: Int = 400,
    ): List<PoiEntity> = getAlongRouteResult(route, selector, radiusMeters).valueOrNull().orEmpty()

    suspend fun getAlongRouteResult(
        route: List<Pair<Double, Double>>,
        selector: String,
        radiusMeters: Int = 400,
    ): GeoResult<List<PoiEntity>> {
        val client = overpassClient ?: return GeoResult.Unavailable(SourceUnavailable.RequestRejected(NO_CLIENT))
        return when (val probe = client.fetchAlongResult(route, radiusMeters, selector)) {
            is GeoResult.Ok -> GeoResult.Ok(
                local.phantom.companion.core.net.map.rankAlongRoute(probe.value, route)
                    .mapNotNull { it.element.toEntity() }
                    .also { remember(it) },
            )
            is GeoResult.Unavailable -> probe
        }
    }

    private fun OverpassElement.toEntity(): PoiEntity? {
        val lat = this.effectiveLat ?: return null
        val lon = this.effectiveLon ?: return null
        // ОСОБА ОБʼЄКТА, ЯКУ ДОСІ ВИКИДАЛИ. `type` приходить у кожній справжній
        // відповіді Overpass і тут просто не читався, тож жива аптека і та сама
        // аптека з довідника лишались двома незіставними рядками — про це і
        // йшлося у скарзі на дубль. Ключ той самий, що ставить пак
        // ([OfflinePlacePack.REF_TAG]), і форма та сама: `n123` / `w456`.
        //
        // Це НЕ тег OSM, і саме тому підкреслення: ані `PoiVocabulary`
        // (читає лише перелічені ключі), ані картка обʼєкта (читає
        // `opening_hours`, `fuel:*`, `socket:*`, `parking*`) його не бачать.
        val tags = (this.tags ?: emptyMap()).let { osm ->
            val ref = OfflinePlacePack.osmRef(this.type, this.id) ?: return@let osm
            osm + (OfflinePlacePack.REF_TAG to ref)
        }
        // A nameless OSM node is a bin or a bench, not a place. Inventing
        // «Unknown» for it put that word on the map hundreds of times over.
        val name = poiDisplayName(tags) ?: return null

        // Infer category. Beyond the four amenity-family keys, recognise the
        // ones that carry stations, state offices and stops — so a вокзал, a
        // ЦНАП or a зупинка renders with its own glyph instead of a blank dot.
        val category = when {
            // Сертифіковане сховище — насамперед сховище, хоч би як OSM його
            // підписав. У Києві чотири з пʼяти таких — `military=bunker` +
            // `bunker_type=bomb_shelter` без жодного з ключів нижче: вони
            // падали в «other» і малювались ромбом «просто місце», а не щитом.
            ShelterTruth.isBombShelter(tags) -> SHELTER_CATEGORY
            tags.containsKey("amenity") -> "amenity"
            tags.containsKey("shop") -> "shop"
            // `historic` СТОЇТЬ ПЕРЕД `tourism`, і це рішення, а не порядок
            // рядків. Памʼятник у OSM часто несе обидва ключі
            // (`historic=memorial` + `tourism=artwork`); з `tourism` попереду
            // він ставав «туризмом» і втрачав саме те, чим є. А доки цих двох
            // ключів тут не було взагалі, КОЖЕН замок, меморіал і придорожній
            // хрест падав у `other` — порожній пін без гліфа. Заміряно 15.09
            // на живому Overpass: навколо Острави це 200 обʼєктів, серед них
            // Schloss Witkowitz і Hornické muzeum.
            tags.containsKey("historic") -> "historic"
            tags.containsKey("man_made") -> "man_made"
            tags.containsKey("tourism") -> "tourism"
            tags.containsKey("leisure") -> "leisure"
            tags.containsKey("office") -> "office"
            tags.containsKey("railway") -> "railway"
            tags["highway"] == "bus_stop" || tags["highway"] == "platform" -> "highway"
            tags.containsKey("public_transport") -> "public_transport"
            // Шлагбаум приходив сюди як «other», тож відбір знаків подій за
            // `category == "barrier"` не спрацьовував з живого Overpass ЖОДНОГО
            // разу — працювала лише гілка за підрядком назви.
            tags.containsKey("barrier") -> "barrier"
            else -> "other"
        }
        val subcategory = if (ShelterTruth.isBombShelter(tags)) SHELTER_SUBCATEGORY else tags[category]

        return PoiEntity(
            id = id,
            lat = lat,
            lon = lon,
            name = name,
            category = category,
            subcategory = subcategory,
            tags = tags
        )
    }

    companion object {
        /** Рід, під яким сховище йде на скло: щит і критичний ярус ([PlaceTiers]). */
        private const val SHELTER_CATEGORY = "amenity"
        private const val SHELTER_SUBCATEGORY = "shelter"
        private const val CACHE_TTL_MS = 15 * 60_000L
        private const val MIN_CACHED_DENSITY = 5

        /** Overpass was measured at 7.3 s for 408 bytes; past this it is gone. */
        private const val LIVE_TIMEOUT_MS = 10_000L
        private const val DEFAULT_ZOOM = 16.0

        /** SQL returns a square; the answer is a circle. Fetch extra, then rank. */
        private const val OVERFETCH = 5

        /**
         * Скільки найближчих місць переглядає рятувальний прохід.
         *
         * Це НАЗВАНА межа, а не число з повітря: описку («крещатик») і чужу
         * абетку («Lviv») ловлять перебором у Kotlin, а перебір коштує. Пʼятсот
         * рядків — це кілька кварталів щільного міста або десятки кілометрів
         * траси; далі за них цей пошук не ручається й не вдає, що ручається.
         * Прохід відбувається ЛИШЕ тоді, коли суворий пошук порожній, тобто
         * ніколи на щасливому шляху.
         */
        private const val RESCUE_SCAN = 500
        private const val NEARBY_LIMIT = 120
        private const val BY_NAME_LIMIT = 60
        private const val COVERAGE_RADIUS_M = 25_000
        private val HOUSE_NUMBER = Regex("^(.*\\p{L}.*?)\\s+\\d{1,4}\\s*\\p{L}?$")

        /** Ключ тега з ISO-кодом країни пакета, з якого прийшов рядок. */
        const val COUNTRY_TAG = "_country"

        /** Скільки країнних влучань FTS роздивитись, коли коробка мовчить. */
        private const val WIDE_SCAN = 200

        /** Скільки роздивитись у пошуках точної назви по країні. */
        private const val EXACT_SCAN = 40

        /** Скільки таких повести перед близькими — рівно стільки, щоб їх було видно. */
        private const val EXACT_LEAD = 3

        /**
         * Корінь із трьох літер ловить набагато більше за повний префікс, і
         * саме тому межа вища: «апт*» — 33 099 рядків по країні, з яких
         * потрібні найближчі. Виміряно 12.09: цей прохід коштує 0,2–38 мс
         * залежно від того, наскільки поширений корінь.
         */
        private const val ROOT_SCAN = 600

        private const val NO_CLIENT = "онлайн-джерело POI не налаштоване"
    }
}

/**
 * The name to put on the map, Ukrainian first. Null means the node has no
 * name at all and does not belong on the map — never a placeholder.
 */
fun poiDisplayName(tags: Map<String, String>): String? {
    val explicit = sequenceOf("name:uk", "name", "int_name", "name:en", "brand", "operator")
        .mapNotNull { tags[it]?.trim() }
        .firstOrNull { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
    if (explicit != null) return explicit

    // Сховище без імені — правило, а не виняток: в OSM їх майже ніколи не
    // називають. Без цього рядка сертифіковане сховище викидалось разом з
    // урнами й лавками, і на мапі під тривогою його не було зовсім.
    if (ShelterTruth.isBombShelter(tags)) return "Укриття"

    // Semantic names for infrastructure nodes that are rarely tagged with explicit name:*
    val barrier = tags["barrier"]
    if (barrier != null) {
        return when (barrier.lowercase()) {
            "lift_gate", "sliding_gate", "swing_gate" -> "Шлагбаум"
            "gate" -> "Ворота"
            "bollard" -> "Болард"
            "block" -> "Бетонний блок"
            "checkpoint", "border_control" -> "Пункт пропуску"
            "toll_booth" -> "Пункт оплати"
            else -> "Шлагбаум"
        }
    }

    if (tags["highway"] == "speed_camera") {
        val spd = tags["maxspeed"]
        return if (spd != null) "Камера $spd" else "Камера швидкості"
    }

    val entrance = tags["entrance"]
    if (entrance != null || tags["railway"] == "subway_entrance") {
        return when {
            tags["railway"] == "subway_entrance" -> "Вхід до метро"
            entrance == "main" -> "Головний вхід"
            entrance == "emergency" -> "Аварійний вихід"
            entrance == "service" -> "Службовий вхід"
            tags["wheelchair"] == "yes" -> "Безбарʼєрний вхід"
            else -> "Вхід"
        }
    }

    val isTram = tags["railway"] == "tram_stop" || tags["tram"] == "yes"
    val isTrolley = tags["trolleybus"] == "yes"
    val isBus = tags["highway"] == "bus_stop" || tags["bus"] == "yes" || tags["amenity"] == "bus_station"
    val isTrain = tags["railway"] in setOf("station", "halt") || tags["train"] == "yes"
    val isTransit = tags["public_transport"] in setOf("platform", "stop_position", "station") ||
        isBus || isTram || isTrolley || isTrain

    if (isTransit) {
        val ref = tags["ref"] ?: tags["route_ref"] ?: tags["local_ref"]
        val routeDesc = if (ref != null) " №$ref" else ""
        return when {
            isTram -> "Зупинка трамвая$routeDesc"
            isTrolley -> "Зупинка тролейбуса$routeDesc"
            tags["amenity"] == "bus_station" -> "Автостанція$routeDesc"
            tags["railway"] == "station" -> "Залізнична станція$routeDesc"
            tags["railway"] == "halt" -> "Зупинний пункт$routeDesc"
            isBus -> "Зупинка автобуса$routeDesc"
            tags["public_transport"] == "platform" -> "Посадковий майданчик$routeDesc"
            else -> "Зупинка транспорту$routeDesc"
        }
    }

    return null
}
