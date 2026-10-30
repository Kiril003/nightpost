package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.net.map.PlaceDossierClient

/**
 * Усе, чого досьє питає у зовнішнього світу — одним вузьким інтерфейсом.
 *
 * ЧОМУ інтерфейс, а не `PlaceDossierClient` напряму. Завантажувач досьє
 * тепер прогресивний: він вирішує, ЩО показати першим, у якому порядку
 * доливати слоти і що робити з таймаутом кожного. Це логіка, а не мережа —
 * і поки вона зшита з конкретним HTTP-клієнтом (клас, не інтерфейс, методи
 * не `open`), її неможливо покрити жодним JVM-тестом. Один шов тут
 * перетворює «сподіваюсь, працює» на «доведено тестом».
 *
 * Кожен метод має той самий контракт, що й джерела під ним: тиха відмова —
 * це null або порожній список, ніколи не виняток (Закон 1: відсутнє
 * джерело = відсутня секція, а не заглушка).
 */
internal interface DossierSources {

    /**
     * Локальні кандидати під тапом — намальовані POI, газетир, сітка доріг.
     * Жодної мережі, тож викликається ПЕРШИМ. Порожньо = нічого локального,
     * а не збій.
     */
    suspend fun localHits(lat: Double, lon: Double, nameHint: String?): List<DossierLocalHit> =
        emptyList()

    /** Іменовані OSM-елементи навколо точки, найближчі перші. */
    suspend fun namedAround(lat: Double, lon: Double): List<PlaceDossierClient.PlaceDetail>

    /** Будівля під точкою (поверхи/висота/адреса) або null. */
    suspend fun buildingAt(lat: Double, lon: Double): PlaceDossierClient.BuildingCard?

    /** Маршрути громадського транспорту, що обслуговують зупинку. */
    suspend fun stopRoutes(lat: Double, lon: Double, radiusM: Int): List<PlaceDossierClient.RouteLineRef>

    /** POI тієї ж категорії поруч («схоже поруч»). */
    suspend fun nearby(lat: Double, lon: Double, radiusM: Int, selector: String): List<PoiEntity>

    /**
     * Заклади всередині контейнера — офлайн, із того самого паку, що годує
     * мапу. Порожньо = нічого не знайдено, і це не збій.
     *
     * Це збіг ЗА ВІДСТАННЮ, не входження в полігон: футпринта мола в паку
     * нема, тож секція підписується «межі будівлі мапа не знає».
     */
    suspend fun venuesAround(lat: Double, lon: Double, radiusM: Int): List<PoiEntity> = emptyList()

    /**
     * Входи в будівлю (`entrance=yes`) — реальні двері замість центроїда.
     * Порожньо за замовчуванням: у бейку POI входів нема (вони безіменні), а
     * мережевий запит існує лише там, де його дописали. Поки список порожній,
     * маршрут веде туди ж, куди й раніше — жодного вигаданого входу.
     */
    suspend fun entrances(lat: Double, lon: Double, radiusM: Int): List<Pair<Double, Double>> =
        emptyList()

    /** Зворотне геокодування (той самий тротлений Nominatim, що й у мапи). */
    suspend fun reverseGeocode(lat: Double, lon: Double): String?

    /** Найближча стаття Вікіпедії — КАНДИДАТ, а не довідка (див. P0). */
    suspend fun nearestArticle(lat: Double, lon: Double, nameHint: String?): DossierArticle?

    /** Стаття за назвою з тегу `wikipedia`. */
    suspend fun articleByTitle(ref: WikiTitleRef): DossierArticle?

    /** Q-ідентифікатор → назва статті у доступному розділі. */
    suspend fun articleTitleForWikidata(wikidataId: String): WikiTitleRef?
}

/**
 * Бойова реалізація: Overpass + Nominatim + Вікіпедія, рівно ті самі
 * клієнти, що й раніше. [wikiLookup] — сутнісні запити з
 * [MediaWikiEntityLookup]; null вимикає їх (тоді лишається лише геопошук,
 * і досьє чесно демотує його в «ПОРУЧ»).
 */
internal class OverpassDossierSources(
    private val client: PlaceDossierClient,
    private val poiRepository: PoiRepository?,
    private val reverseGeocoder: suspend (Double, Double) -> String?,
    private val wikiLookup: WikiEntityLookup? = MediaWikiEntityLookup(),
    /** POI, які мапа зараз малює — з повними тегами і за нуль запитів. */
    private val viewportPois: () -> List<PoiEntity> = ::emptyList,
    /** Сітка доріг мапчера — назва вулиці під пальцем. Null = сітки нема. */
    private val roadGraph: () -> local.phantom.companion.core.sensor.nav.RoadGraph? = { null },
) : DossierSources {

    override suspend fun localHits(lat: Double, lon: Double, nameHint: String?): List<DossierLocalHit> =
        buildList {
            viewportPois().forEach { p ->
                val d = haversineMeters(lat, lon, p.lat, p.lon)
                if (d > sourceReachM(DossierSourceKind.VIEWPORT)) return@forEach
                add(
                    DossierLocalHit(
                        source = DossierSourceKind.VIEWPORT,
                        name = p.name,
                        lat = p.lat,
                        lon = p.lon,
                        distanceM = d,
                        osmId = p.id,
                        categoryHint = p.category,
                        subcategoryHint = p.subcategory,
                        tags = p.tags,
                    ),
                )
            }
            val query = nameHint?.trim().orEmpty()
            if (query.isNotEmpty() && poiRepository != null) {
                runCatching {
                    poiRepository.searchOffline(
                        query = query,
                        lat = lat,
                        lon = lon,
                        radiusMeters = GAZETTEER_RADIUS_M,
                        limit = GAZETTEER_LIMIT,
                    )
                }.getOrDefault(emptyList()).forEach { add(gazetteerHit(it)) }
            }
            roadGraph()?.near(lat, lon, ROAD_PROBE_M, ROAD_LIMIT)?.forEach { c ->
                add(
                    DossierLocalHit(
                        source = DossierSourceKind.ROAD_MESH,
                        name = c.way.name,
                        lat = c.lat,
                        lon = c.lon,
                        distanceM = c.perpM,
                        osmId = c.way.id,
                        categoryHint = "highway",
                    ),
                )
            }
        }

    override suspend fun namedAround(lat: Double, lon: Double) =
        client.fetchNamedAround(lat, lon)

    override suspend fun buildingAt(lat: Double, lon: Double) =
        client.fetchBuildingAt(lat, lon)

    override suspend fun stopRoutes(lat: Double, lon: Double, radiusM: Int) =
        client.fetchStopRoutes(lat, lon, radiusM)

    override suspend fun nearby(lat: Double, lon: Double, radiusM: Int, selector: String) =
        poiRepository?.getNearby(lat, lon, radiusM, selector) ?: emptyList()

    /**
     * Перше джерело — те, що мапа вже намалювала (повні теги, нуль запитів);
     * далі пак за кількома широкими селекторами. Селектори перелічені, а не
     * «будь-який POI», бо офлайн-пак уміє відповідати лише на `ключ=значення`.
     */
    override suspend fun venuesAround(lat: Double, lon: Double, radiusM: Int): List<PoiEntity> {
        val drawn = viewportPois().filter { haversineMeters(lat, lon, it.lat, it.lon) <= radiusM }
        val repo = poiRepository ?: return drawn.distinctBy { it.id }
        val fromPack = VENUE_SELECTORS.flatMap { selector ->
            runCatching { repo.getNearby(lat, lon, radiusM, selector) }.getOrDefault(emptyList())
        }
        return (drawn + fromPack).distinctBy { it.id }
    }

    override suspend fun reverseGeocode(lat: Double, lon: Double) = reverseGeocoder(lat, lon)

    override suspend fun nearestArticle(lat: Double, lon: Double, nameHint: String?): DossierArticle? =
        client.fetchWiki(lat, lon, nameHint)?.let { card ->
            DossierArticle(
                title = card.title,
                extract = card.extract,
                thumbnailUrl = card.thumbnailUrl,
                pageUrl = card.pageUrl,
                // Геопошук не віддає wikibase_item — і це чесно: тотожність
                // тут не доведена, її доводить [judgeWikiCandidate].
                wikidataId = null,
            )
        }

    override suspend fun articleByTitle(ref: WikiTitleRef) = wikiLookup?.byTitle(ref)

    override suspend fun articleTitleForWikidata(wikidataId: String) =
        wikiLookup?.titleForWikidata(wikidataId)

    private companion object {
        /** Чим торгує і що годує типовий ТРЦ — те, що бейк реально несе. */
        val VENUE_SELECTORS = listOf(
            "shop~\"clothes|shoes|supermarket|convenience|electronics|department_store|jewelry|" +
                "sports|cosmetics|bakery|gift|toys|books|mobile_phone|optician|furniture|chemist|" +
                "variety_store|beauty|hairdresser|mall|greengrocer|butcher|florist|kiosk\"",
            "amenity~\"cafe|fast_food|restaurant|bar|pub|ice_cream|food_court|pharmacy|bank|atm|" +
                "cinema|post_office|parcel_locker|toilets|theatre\"",
            "office~\"government|company|estate_agent|insurance|travel_agent\"",
            "leisure~\"fitness_centre|playground|sports_centre|bowling_alley\"",
        )
        const val GAZETTEER_RADIUS_M = 60
        const val GAZETTEER_LIMIT = 8
        const val ROAD_PROBE_M = 30.0
        const val ROAD_LIMIT = 4
    }
}
