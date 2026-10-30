package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import local.phantom.companion.core.data.evac.ShelterRegistryVerdict
import local.phantom.companion.core.data.evac.ShelterTruth
import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.data.places.SavedPlaceEntity
import local.phantom.companion.core.data.places.SavedPlaceMigration
import local.phantom.companion.core.data.places.SavedPlaceStore
import local.phantom.companion.core.data.poi.OfflinePoiSearch
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiHit
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.geo.place.NamedPlace
import local.phantom.companion.core.geo.place.PlaceGazetteer
import local.phantom.companion.core.geo.place.PlaceKind
import local.phantom.companion.core.data.vault.VaultEntity
import local.phantom.companion.core.data.vault.VaultStore
import local.phantom.companion.core.net.map.CrimeaUa
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.valueOrNull
import local.phantom.companion.feature.stream.R

/**
 * POI discovery on the full map, extracted verbatim from the MapViewModel
 * monolith (500-line rule): the debounced viewport fetch, the pinned
 * «поруч» category floods (radial and along-route), and the operator's
 * saved Vault pins.
 */
@OptIn(FlowPreview::class)
internal class MapPoiSearch(
    private val scope: CoroutineScope,
    private val poiRepository: PoiRepository,
    private val vaultStore: VaultStore?,
    /** Єдина памʼять місць. Null лише в превʼю і тестах — тоді міток немає. */
    private val savedPlaceStore: SavedPlaceStore?,
    private val postNotice: (String) -> Unit,
    /** The selected route variant's geometry, for «по дорозі» searches. */
    private val activeCorridor: () -> List<Pair<Double, Double>>?,
    /** Fit the camera to a flood's results so «всі на мапі» are actually on screen. */
    private val fitCamera: (south: Double, west: Double, north: Double, east: Double) -> Unit = { _, _, _, _ -> },
    /**
     * Offline oblast lookup ([OblastShapes.oblastAt] in production) — lets
     * [showNearby] tell a genuine regional gap (the invincibility-point
     * registry covers 3 of 24 oblasts) from an ordinary empty result. Null
     * default so every existing test/preview keeps compiling; without it the
     * gap message simply never fires and «поруч не знайшов» stands as before.
     */
    private val oblastAt: (lat: Double, lon: Double) -> String? = { _, _ -> null },
) {
    private val _pois = MutableStateFlow<List<PoiEntity>>(emptyList())
    val pois: StateFlow<List<PoiEntity>> = _pois.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // M-E01 — active "поруч" category label (null = showing normal bbox POIs).
    private val _nearbyActive = MutableStateFlow<String?>(null)
    val nearbyActive: StateFlow<String?> = _nearbyActive.asStateFlow()

    private var cameraSeq: Long = 0L
    private val _cameraBounds = MutableStateFlow<OfflineBounds?>(null)
    private val _cameraTick = MutableStateFlow<Pair<OfflineBounds, Long>?>(null)

    /**
     * Мітки оператора — ОДНА памʼять на застосунок.
     *
     * Раніше їх було дві, що не бачили одна одної: мапа малювала Vault-рядки
     * `metadata="map-poi"`, а чат писав у таблицю `saved_places`. Тому «збережи
     * дім» у чаті давало порожню мапу, а зірка з мапи — «збережених місць
     * немає». Тепер джерело одне, і обидві руки сходяться в ньому.
     *
     * Тип лишається [PoiEntity] свідомо: мапа й пошуковий аркуш уже вміють його
     * малювати, а `category` тут несе категорію мітки, за якою пін бере гліф.
     */
    val savedPlaces: StateFlow<List<SavedPlaceEntity>> =
        (savedPlaceStore?.liveFlow() ?: flowOf(emptyList()))
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedPois: StateFlow<List<PoiEntity>> = savedPlaces
        .map { places -> places.map(::asPin) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun asPin(place: SavedPlaceEntity): PoiEntity = PoiEntity(
        id = place.id.hashCode().toLong(),
        lat = place.lat,
        lon = place.lon,
        name = place.label,
        category = SavedPlaceCategory.canonical(place.category),
        subcategory = place.address,
        tags = emptyMap(),
    )

    // `searchMarks` знято: екран уже фільтрує ЖИВИЙ список міток тією самою
    // брамою `SavedPlaceCategory.matchesQuery` (MapSearchResults), тож це був
    // другий шлях до того самого результату — зайвий похід у DAO і друге
    // джерело правди, яке рано чи пізно розійшлося б із першим.

    /**
     * Дім і робота — якорі, а не звичайні мітки: на них спирається
     * `transit.home`, який шукає ПЕРШИЙ рядок із цією категорією.
     *
     * Тому старий якір спершу зникає, і тільки потім лягає новий. Інакше
     * переїзд лишав би два рядки category=home, а «додому» вело б у
     * попереднє життя — мовчки, бо `firstOrNull` не має думки про те, який
     * із двох правдивий. Імʼя приходить локалізованим: воно для ока, а
     * впізнає якір саме категорія.
     */
    fun setAnchor(category: String, label: String, lat: Double, lon: Double) {
        val store = savedPlaceStore ?: return
        val canonical = SavedPlaceCategory.canonical(category)
        scope.launch {
            runCatching {
                store.list()
                    .filter { SavedPlaceCategory.canonical(it.category) == canonical }
                    .forEach { store.remove(it.id) }
                store.save(label = label, lat = lat, lon = lon, category = canonical)
            }
        }
    }

    // Recent queries — persisted in the Vault (survives restarts), newest first.
    val recentSearches: StateFlow<List<String>> = (vaultStore?.getEntries() ?: flowOf(emptyList()))
        .map { entries ->
            entries.filter { it.metadata == RECENT_META }
                .sortedByDescending { it.updatedAt }
                .map { it.value }
                .distinct()
                .take(RECENT_MAX)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun rememberSearch(query: String) {
        val q = query.trim()
        if (q.length < 2 || vaultStore == null) return
        scope.launch {
            runCatching {
                // Key on the text so a repeat just bumps updatedAt, never piles up.
                vaultStore.saveEntry(
                    VaultEntity(
                        id = "recent-${q.lowercase().hashCode()}",
                        key = "map.recent",
                        value = q,
                        type = "note",
                        metadata = RECENT_META,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun clearRecent() {
        val store = vaultStore ?: return
        scope.launch {
            runCatching {
                store.getEntries().first()
                    .filter { it.metadata == RECENT_META }
                    .forEach { store.deleteEntry(it.id) }
            }
        }
    }

    /**
     * Пошук на пристрої — ПЕРШЕ джерело, не остання надія.
     *
     * Дві речі, яких тут раніше не було:
     *
     * **1. Порожнеча має два різні значення.** Список у нуль рядків однаково
     * означав і «такого тут немає», і «шукати нема де» — а це різні речення
     * для людини: перше не лікується нічим, друге лікується встановленням
     * пакета місць. Тепер репозиторій виносить вирок, а не список, і другий
     * випадок людина чує словом. Сказано ОДИН раз на порожній довідник, бо
     * пошук біжить на кожну літеру, і вісім однакових сповіщень поспіль —
     * це вже не чесність, а галас.
     *
     * **2. Населені пункти.** Довідник міст ([PlaceGazetteer]) лежав у
     * застосунку і був доступний рівно з одного екрана — пікера офлайн-пакетів.
     * Через це набране в пошуку «Львів» не знаходило Львова: у видачі були
     * тільки ті POI, які телефон колись бачив на мапі. Місто — теж місце.
     */
    suspend fun offlineSearch(query: String, lat: Double, lon: Double): List<PoiHit> {
        // Викликається з `LaunchedEffect` у композиції, тобто з ГОЛОВНОГО
        // потоку. Room свої запити відводить на власний виконавець, а все
        // інше тут — ні: ранжування, рятувальний прохід на 500 рядків, лінива
        // побудова [PlaceGazetteer] при першому дотику і зведення поселень
        // рахувались просто в кадрі, на кожну натиснуту літеру.
        val verdict = withContext(Dispatchers.Default) {
            runCatching { poiRepository.searchOfflineVerdict(query, lat, lon) }
                .getOrDefault(PoiRepository.OfflineSearch.NothingHere(corpusSize = 0))
        }

        _searchGap.value = searchGapOf(verdict)

        return withContext(Dispatchers.Default) {
            blendSettlements(
                poiHits = verdict.hits,
                settlements = runCatching { PlaceGazetteer.searchEverywhere(query, limit = SETTLEMENT_SCAN) }
                    .getOrDefault(emptyList()),
                query = query,
                lat = lat,
                lon = lon,
            )
        }
    }

    private val _searchGap = MutableStateFlow<SearchGap?>(null)

    /**
     * ЧОМУ порожньо — окремо від самої порожнечі.
     *
     * «Нічого не знайшов» однаково стояло і там, де довідник повний, і там, де
     * його немає зовсім. Для людини це різні речення: перше не лікується
     * нічим, друге лікується пакетом країни. Вирок репозиторій виносив уже
     * давно, але до ока він не доїжджав.
     */
    val searchGap: StateFlow<SearchGap?> = _searchGap.asStateFlow()

    /** Last camera zoom, so the fetch can refuse city-wide sweeps. */
    private var lastZoom: Double = 0.0

    /**
     * Чи ми зараз мовчимо через заслін масштабу.
     *
     * Потрібне саме як ПЕРЕХІД, а не як стан: підказку кажуть один раз, коли
     * обʼєкти щойно зникли, і більше не повторюють, доки людина не наблизиться
     * й не відпустить мапу знову.
     */
    private var poiHiddenByZoom: Boolean = false

    /**
     * Готове речення про заслін масштабу, розвʼязане композицією.
     *
     * Null, доки екран його не вручив (превʼю, тести): тоді заслін просто
     * мовчить, як мовчав досі, — але НЕ вигадує англійського тексту й не
     * тягне сюди українського літерала.
     */
    private var zoomHint: String? = null

    /** Композиція вручає переклад один раз — див. [ZOOM_IN_FOR_POIS] вище. */
    fun setZoomHint(text: String) {
        zoomHint = text
    }

    private var offlineKindHint: String? = null

    fun setOfflineKindHint(template: String) {
        offlineKindHint = template
    }

    /** The in-flight viewport fetch; a newer viewport cancels it. */
    private var poiJob: Job? = null

    /**
     * Зведення двох памʼятей в одну, один раз. Ідемпотентно без прапорця:
     * перенесені Vault-рядки видаляються, тож удруге переносити нічого.
     * Мовчить, коли переносити не було чого — сповіщення тут означало б
     * розповідь про власну внутрішню кухню.
     */
    private fun migrateVaultMarks() {
        val vault = vaultStore ?: return
        val places = savedPlaceStore ?: return
        scope.launch {
            val report = runCatching { SavedPlaceMigration(vault, places).run() }.getOrNull()
                ?: return@launch
            if (report.renamed.isNotEmpty()) {
                postNotice(MapText.s(R.string.mapl_f_migration_renamed, report.renamed.joinToString(", ")))
            }
            if (report.unreadable.isNotEmpty()) {
                postNotice(MapText.s(R.string.mapl_f_migration_unreadable, report.unreadable.size))
            }
        }
    }

    init {
        migrateVaultMarks()

        // Trigger POI fetch when camera bounds change (debounced)
        _cameraTick
            .filterNotNull()
            .debounce(250L)
            .onEach { (bounds, _) ->
                // While a "поруч" result set is pinned, don't let the bbox
                // auto-fetch overwrite it (M-E01).
                if (_nearbyActive.value != null) return@onEach
                // Zoom gate — the whole reason the map felt slow and empty:
                // at city/country zoom the visible bbox is enormous, so the
                // Overpass query returned thousands of rows or timed out (=no
                // pins at all). POIs are a street-level concern; below the gate
                // we draw nothing and stay fast, above it the bbox is small.
                if (lastZoom < MIN_POI_ZOOM) {
                    if (_pois.value.isNotEmpty()) _pois.value = emptyList()
                    // Заслін лишається — він виміряний, а не обережний: коробка
                    // зуму 12 (уся Острава) 15.09 поклала Overpass у таймаут на
                    // 60 с і не віддала жодного байта. Але доти він МОВЧАВ, і
                    // це вада поруч із ним: людина водила мапу й бачила чисте
                    // полотно, не відрізняючи «тут нічого немає» від «я не
                    // показую». Тепер каже — і РІВНО ОДИН раз на перехід, а не
                    // на кожен зсув камери: підказка, що повторюється, це вже
                    // не підказка.
                    if (!poiHiddenByZoom) {
                        poiHiddenByZoom = true
                        zoomHint?.let(postNotice)
                    }
                    return@onEach
                }
                poiHiddenByZoom = false
                fetchPois(bounds)
            }
            .launchIn(scope)
    }

    fun updateCameraBounds(bounds: OfflineBounds, zoom: Double = lastZoom) {
        lastZoom = zoom
        _cameraBounds.value = bounds
        _cameraTick.value = bounds to ++cameraSeq
    }

    /**
     * NAV-Ω1 — «по дорозі»: flood the map with a category found ALONG the
     * active route (first-reachable first), instead of radially around a
     * point. Falls back to nothing (honest notice) when the corridor has
     * no such stop within ~400 м off the way.
     */
    fun showNearbyAlongRoute(selector: String, label: String) {
        val route = activeCorridor()
        if (route == null || route.size < 2) {
            postNotice(MapText.s(R.string.mapl_f_no_active_route_along))
            return
        }
        poiJob?.cancel()
        scope.launch {
            _loading.value = true
            _nearbyActive.value = MapText.s(R.string.mapl_f_along_route_suffix, label)
            val probe = poiRepository.getAlongRouteResult(route, selector)
            val res = probe.valueOrNull().orEmpty()
            _pois.value = res
            _loading.value = false
            when {
                probe is GeoResult.Unavailable -> postNotice(sourceDownNotice(label))
                res.isEmpty() -> postNotice(MapText.s(R.string.mapl_f_not_found_along_route, label))
            }
        }
    }

    /**
     * M-E01 — flood the map with a category around a point, sorted by
     * distance. Reuses the emoji POI overlay; pinned until [clearNearby].
     */
    fun showNearby(
        selector: String,
        label: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int = 3000,
    ) {
        poiJob?.cancel()
        _pois.value = emptyList()
        _loading.value = true
        _nearbyActive.value = label
        poiJob = scope.launch {
            harvestJob?.join()
            val probe = poiRepository.getNearbyResult(lat, lon, radiusMeters, selector)
            val res = probe.valueOrNull().orEmpty()
                .sortedBy { haversine(lat, lon, it.lat, it.lon) }
            _pois.value = res
            _loading.value = false
            when {
                res.isEmpty() && probe is GeoResult.Unavailable && !poiRepository.knowsPlacesNear(lat, lon) -> {
                    _searchGap.value = SearchGap.NO_PACK
                }
                res.isEmpty() && probe is GeoResult.Unavailable && !poiRepository.offlineCarries(lat, lon, selector) ->
                    postNotice(
                        shelterGapNotice(selector)
                            ?: offlineKindHint?.replace("%1\$s", label)
                            ?: sourceDownNotice(label),
                    )
                res.isEmpty() -> postNotice(
                    shelterGapNotice(selector)
                        ?: invincibilityGapNotice(selector, lat, lon)
                        ?: MapText.s(R.string.mapl_f_not_found_nearby, label),
                )
            }
        }
    }

    private fun shelterGapNotice(selector: String): String? {
        if (selector != ShelterTruth.BOMB_SHELTER_SELECTOR) return null
        if (poiRepository.offlineShelterRegistry() == ShelterRegistryVerdict.PRESENT) return null
        return MapText.s(R.string.mapl_f_shelter_registry_gap)
    }

    private fun invincibilityGapNotice(selector: String, lat: Double, lon: Double): String? {
        if (selector != INVINCIBILITY_POINT_SELECTOR) return null
        val covered = local.phantom.companion.core.data.country.CountryPackRegistry.invincibilityCoveredRegions()
        val oblast = oblastAt(lat, lon)
        if (oblast == null || covered.isEmpty() || oblast in covered) return null
        return MapText.s(R.string.mapl_f_invincibility_gap, covered.sorted().joinToString(", "), oblast)
    }

    fun showNearbyByName(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int = 5_000,
    ) {
        val q = query.trim()
        if (q.isBlank()) return
        poiJob?.cancel()
        _pois.value = emptyList()
        _loading.value = true
        _nearbyActive.value = q
        poiJob = scope.launch {
            harvestJob?.join()
            val probe = poiRepository.getNearbyByNameResult(q, lat, lon, radiusMeters)
            val res = probe.valueOrNull().orEmpty()
                .sortedBy { haversine(lat, lon, it.lat, it.lon) }
            _pois.value = res
            _loading.value = false
            if (probe is GeoResult.Unavailable) {
                postNotice(sourceDownNotice(MapText.s(R.string.mapl_f_quote, q)))
            } else if (res.isEmpty()) {
                postNotice(MapText.s(R.string.mapl_f_not_found_nearby_quoted, q))
            } else {
                fitToResults(res, lat, lon)
                postNotice(MapText.s(R.string.mapl_f_found_on_map, q, res.size))
            }
        }
    }

    /** Frame the flood: bbox of all hits (+ the anchor), padded a touch. */
    private fun fitToResults(res: List<PoiEntity>, anchorLat: Double, anchorLon: Double) {
        var south = anchorLat; var north = anchorLat
        var west = anchorLon; var east = anchorLon
        for (p in res) {
            if (p.lat < south) south = p.lat
            if (p.lat > north) north = p.lat
            if (p.lon < west) west = p.lon
            if (p.lon > east) east = p.lon
        }
        val padLat = ((north - south) * 0.15).coerceAtLeast(0.003)
        val padLon = ((east - west) * 0.15).coerceAtLeast(0.003)
        fitCamera(south - padLat, west - padLon, north + padLat, east + padLon)
    }

    private var harvestJob: Job? = null

    fun rememberTilePlaces(places: List<PoiEntity>, onNew: () -> Unit) {
        if (places.isEmpty()) return
        val before = harvestJob
        harvestJob = scope.launch {
            before?.join()
            if (poiRepository.rememberTilePlaces(places) > 0) onNew()
        }
    }

    fun clearNearby() {
        _nearbyActive.value = null
        _pois.value = emptyList()
        // Next camera-idle will refetch the normal bbox POIs.
        _cameraBounds.value?.let { fetchPois(it) }
    }

    /**
     * The viewport pump. Two emissions: what is already on disk, then the live
     * answer if it arrives. A newer viewport cancels the older fetch, so a slow
     * Overpass can never repaint a map the operator has already panned away.
     */
    private fun fetchPois(bounds: OfflineBounds) {
        poiJob?.cancel()
        poiJob = scope.launch {
            delay(120L)
            _loading.value = true
            try {
                poiRepository.getPois(
                    minLat = bounds.southLat,
                    minLon = bounds.westLon,
                    maxLat = bounds.northLat,
                    maxLon = bounds.eastLon,
                    zoom = lastZoom,
                ).collect { _pois.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                android.util.Log.e("PhantomMapPois", "fetchPois failed for bounds=$bounds", t)
            }
            _loading.value = false
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun sourceDownNotice(label: String): String =
        MapText.s(R.string.mapl_f_source_down, label)

    private companion object {
        const val RECENT_META = "map-recent"
        const val RECENT_MAX = 8
        /** Below this zoom the visible area is too big for a useful POI fetch. */
        const val MIN_POI_ZOOM = 13.0

        /** Скільки рядків довідника міст переглянути, перш ніж відсіювати. */
        const val SETTLEMENT_SCAN = 12
    }
}

/**
 * «Шукати нема де» — це не «нічого не знайдено».
 *
 * Порожній довідник означає стан ПРИСТРОЮ, а не світу: пакет місць не
 * встановлено, і телефон жодного разу не бачив мережі там, де його питають.
 * Сказати на це «нічого не знайдено» — збрехати про світ замість зізнатися
 * про себе.
 */
internal val NOWHERE_TO_LOOK: String
    get() = MapText.s(R.string.mapl_f_nowhere_to_look)

// ── Чому мапа порожня, коли вона порожня не тому, що там нічого немає ──────
//
// Межа MapPoiSearch.MIN_POI_ZOOM виміряна, а не обережна: коробка зуму 12
// поклала Overpass у таймаут і не віддала нічого. Але мовчазний заслін
// неможливо відрізнити від «тут пусто», і саме так це й читалось.
//
// Сам текст живе в strings.xml (map_poi_zoom_hint і його англійська пара), а
// не тут: ратчет кирилиці в **/ui/** тримає саме те, щоб український рядок не
// осідав у композиції без перекладу. Композиція розвʼязує його через
// stringResource і вручає в MapPoiSearch.setZoomHint — рівно так, як це вже
// робить map_shelter_no_fix для евакуації.

/**
 * Домішати населені пункти до знайденого на пристрої.
 *
 * Довідник на 315 українських місць плюс країни світу лежав у застосунку і був
 * доступний рівно з ОДНОГО екрана — пікера офлайн-пакетів. Через це набране в
 * пошуку «Львів» не знаходило Львова: у видачі були самі POI, які телефон
 * колись бачив на мапі.
 *
 * ПРАВИЛО ПОРЯДКУ, і воно навмисне. Точний збіг назви йде ПЕРШИМ: той, хто
 * набрав «Львів» цілком, назвав місто, а не кавʼярню «Львівська». Частковий
 * збіг («льв») іде В КІНЕЦЬ: там людина ще друкує, і поховати під областями
 * те, що поруч, було б гірше за все. Місто ніколи не витісняє POI з видачі —
 * воно або веде її, або замикає.
 *
 * СИРЕНА-4 — і всередині кожного відра ближче йде першим. [settlements]
 * приходить від [PlaceGazetteer.searchEverywhere], який рахує лише якість
 * текстового збігу і зовсім не знає ні [lat], ні [lon] — це довідник для
 * «привʼязати назву до координат звідки завгодно», а не для «що поруч».
 * Без другого ключа сортування Ратибор за 27 км і Братислава за 170 могли
 * помінятись місцями лише тому, що рядковий ранжувальник так вирішив: рівно
 * та скарга, з якою людина стояла на власній вулиці й бачила Польщу першою,
 * а сусіднє місто — третім.
 *
 * Чиста функція: [settlements] уже знайдено викликачем, і жодного стану тут
 * немає — тому кожне правило вище є звичайним JVM-тестом.
 */
internal fun blendSettlements(
    poiHits: List<PoiHit>,
    settlements: List<NamedPlace>,
    query: String,
    lat: Double,
    lon: Double,
    limit: Int = 3,
): List<PoiHit> {
    val q = PlaceGazetteer.fold(query)
    if (q.isEmpty() || settlements.isEmpty()) return poiHits

    val exact = mutableListOf<PoiHit>()
    val partial = mutableListOf<PoiHit>()
    // Повний прохід, а не обрив на перших `limit`: [settlements] — щонайбільше
    // SETTLEMENT_SCAN (12) рядків, і зупинка РАНІШЕ за сортування за відстанню
    // могла відкинути ближчий збіг, до якого черга просто ще не дійшла.
    for (place in settlements) {
        val ua = PlaceGazetteer.fold(place.nameUa)
        val en = PlaceGazetteer.fold(place.nameEn)
        val hit = place.asHit(lat, lon)
        when {
            ua == q || en == q -> exact += hit
            ua.startsWith(q) || en.startsWith(q) -> partial += hit
            // Збіг усередині слова містом не є: «ів» у «Київ» — не назва.
            else -> Unit
        }
    }
    val exactByDistance = exact.sortedBy { it.distanceMeters }.take(limit)
    val partialByDistance = partial.sortedBy { it.distanceMeters }
        .take((limit - exactByDistance.size).coerceAtLeast(0))
    // Місце вже знайдене серед POI не варто показувати двічі.
    val seen = poiHits.map { PlaceGazetteer.fold(it.name) }.toSet()
    return exactByDistance.filterNot { PlaceGazetteer.fold(it.name) in seen } +
        poiHits +
        partialByDistance.filterNot { PlaceGazetteer.fold(it.name) in seen }
}

/**
 * Рамка — не точка, тож координата міста тут — центр його рамки. Це чесно
 * настільки, наскільки чесна сама рамка: для області центр прямокутника може
 * лежати в полі, і саме тому поруч завжди стоїть слово про те, що це за рівень
 * («місто», «область»), а не самі лише координати.
 */
private fun NamedPlace.asHit(lat: Double, lon: Double): PoiHit {
    val centerLat = (northLat + southLat) / 2.0
    val centerLon = (eastLon + westLon) / 2.0
    return PoiHit(
        // Простір ідентифікаторів у POI — це osm id; щоб не зіткнутися з ним,
        // місто отримує свій, породжений із власного рядка довідника.
        id = "place:$id".hashCode().toLong(),
        lat = centerLat,
        lon = centerLon,
        name = nameUa,
        category = "place",
        subcategory = when (kind) {
            PlaceKind.Country -> "country"
            PlaceKind.Oblast -> "oblast"
            PlaceKind.Raion -> "raion"
            PlaceKind.City -> "city"
        },
        distanceMeters = OfflinePoiSearch.haversineMeters(lat, lon, centerLat, centerLon),
        tags = emptyMap(),
        matchedName = true,
    )
}

/**
 * Країна під якорем — рамками країн [PlaceGazetteer], без жодного запиту до
 * пакетів. Точність рамкова, і цього досить: відповідь вирішує, ЩО написати,
 * а не куди вести людину по мапі.
 */
internal fun countryAt(here: Pair<Double, Double>?, inLocalCountry: Boolean = false): NamedPlace? {
    val (lat, lon) = here ?: return null
    // СВОЯ КРАЇНА — ЗА ПОЛІГОНОМ, решта — за рамкою, і різниця тут виміряна.
    //
    // Рамки країн прямокутні й вкладаються одна в одну. Львів (49,84 / 24,03)
    // лежить у рамці ПОЛЬЩІ (49,00–55,04 / 14,07–24,15), Ужгород — у рамці
    // Словаччини, а Кошице — ще й у материковій рамці Росії, яка через
    // Калінінград починається з ~19,6° сх. д. Назвати людині у Львові
    // «Польщу» — видима брехня, тож там, де в нас є справжні полігони
    // ([OblastShapes] — області України), питаємо їх, а не рамку.
    //
    // МЕЖА, названа чесно: поза Україною лишаються самі рамки. У глибині
    // країни вони праві, біля кордону можуть помилитись на сусіда. Тому
    // найменша площа й без «материкових» рамок — це найкраще, що дає
    // прямокутник, а не істина.
    if (inLocalCountry) return PlaceGazetteer.country
    return PlaceGazetteer.countries(lat, lon)
        .filter { it.contains(lat, lon) && !PlaceGazetteer.isMainlandFramed(it) }
        .minByOrNull { it.landAreaKm2 ?: Double.MAX_VALUE }
}

/**
 * ISO-код країни під якорем. Потрібен ОДНІЙ поверхні: рядок видачі називає
 * країну лише тоді, коли вона не та, у якій людина стоїть. «UA» на кожному
 * рядку в Україні нічого не розрізняє, а «SK» поруч із «UA» — розрізняє все.
 */
internal fun countryCodeAt(here: Pair<Double, Double>?, inLocalCountry: Boolean = false): String? =
    countryAt(here, inLocalCountry)?.id
        ?.removePrefix(PlaceGazetteer.WORLD_ID_PREFIX)
        ?.uppercase()
        ?.takeIf { it.length == 2 }

/**
 * Чи стоїть точка всередині СВОЄЇ країни — за справжніми полігонами областей,
 * не за рамкою. [shapes] уже завантажені екраном; другого читання асета тут
 * немає навмисно.
 */
internal fun Pair<Double, Double>?.inLocalCountry(shapes: OblastShapes): Boolean {
    val (lat, lon) = this ?: return false
    // Севастополя в пакеті областей немає — без цього він був би «за кордоном».
    return shapes.oblastAt(lat, lon) != null || (shapes.describesUkraine && CrimeaUa.contains(lat, lon))
}

internal data class MissingPack(
    val code: String,
    val nameEn: String,
    val nameUa: String,
    val sizeMb: Int? = null,
) {
    constructor(code: String, nameUa: String) : this(
        code = code,
        nameEn = nameUa,
        nameUa = nameUa,
        sizeMb = null,
    )
}

internal fun missingPackFor(
    here: Pair<Double, Double>?,
    installedCountries: List<String>,
    inLocalCountry: Boolean = false,
    sizeMb: Int? = null,
): MissingPack? {
    val place = countryAt(here, inLocalCountry) ?: return null
    val code = place.id.removePrefix(PlaceGazetteer.WORLD_ID_PREFIX).uppercase()
        .takeIf { it.length == 2 } ?: return null
    if (installedCountries.any { it.equals(code, ignoreCase = true) }) return null
    return MissingPack(
        code = code,
        nameEn = place.nameEn,
        nameUa = place.nameUa,
        sizeMb = sizeMb?.takeIf { it > 0 },
    )
}

/** Вирок репозиторію → причина, яку видно оку. Знахідка причини не має. */
internal fun searchGapOf(verdict: PoiRepository.OfflineSearch): SearchGap? = when (verdict) {
    is PoiRepository.OfflineSearch.NowhereToLook -> SearchGap.NO_PACK
    is PoiRepository.OfflineSearch.NothingHere -> SearchGap.NOT_IN_CORPUS
    is PoiRepository.OfflineSearch.Hits -> null
}

/**
 * Чому порожній аркуш порожній. Два стани, а не один, бо лікуються різним.
 */
internal enum class SearchGap {
    /** Довідника немає взагалі — пакет країни не встановлено. */
    NO_PACK,

    /** Довідник повний, і цього в ньому немає. */
    NOT_IN_CORPUS,
}
