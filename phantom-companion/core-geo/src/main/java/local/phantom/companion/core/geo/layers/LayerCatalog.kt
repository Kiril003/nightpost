package local.phantom.companion.core.geo.layers

import local.phantom.companion.core.geo.license.GeoLayer
import local.phantom.companion.core.geo.license.LayerRole
import local.phantom.companion.core.geo.license.LicenseClass
import local.phantom.companion.core.geo.license.LicenseFirewall
import local.phantom.companion.core.geo.license.LicenseViolation

/**
 * Як шар оновлюється. Витягнуто в тип, бо від цього залежить і TTL кешу, і
 * поведінка в офлайні: `BAKED_PMTILES` працює без мережі взагалі, а
 * `REALTIME_FEED` без неї не існує, і показувати його старі дані як актуальні —
 * гірше, ніж не показувати нічого.
 */
enum class UpdateMethod {
    /** Запечено в PMTiles-архів, оновлюється разом із пакетом регіону. */
    BAKED_PMTILES,

    /** Тягнеться з Overpass на вимогу під конкретний екстент. */
    OVERPASS_ON_DEMAND,

    /** Періодичний імпорт статичного набору (GTFS, GeoTIFF, CSV). */
    PERIODIC_IMPORT,

    /** Стрічка реального часу: секунди-хвилини актуальності. */
    REALTIME_FEED,

    /** Власна база PHANTOM — оновлюється нами. */
    INTERNAL,
}

/**
 * Скільки шар має жити на карті, перш ніж його показ стане брехнею.
 *
 * Живе тут, а не в місці показу, бо це властивість СПОСОБУ ОНОВЛЕННЯ, а не
 * екрана: той самий шар, покладений з чату, з меню карти чи з фонового
 * завдання, має протухати однаково. Розкид у три порядки — навмисний:
 *
 *  - бетон станції та запечені тайли не рухаються — `null`, шар живе, доки
 *    його не приберуть;
 *  - витягнуте з Overpass на вимогу застаріває за години (ремонт, закритий
 *    вихід), тож шість годин — стеля, після якої краще перепитати;
 *  - стрічка реального часу без мережі не існує взагалі: показати позицію
 *    трамвая пʼятихвилинної давнини як актуальну — гірше, ніж не показати
 *    нічого.
 */
val UpdateMethod.defaultTtlMs: Long?
    get() = when (this) {
        UpdateMethod.BAKED_PMTILES, UpdateMethod.INTERNAL -> null
        UpdateMethod.OVERPASS_ON_DEMAND, UpdateMethod.PERIODIC_IMPORT -> 6 * 60 * 60_000L
        UpdateMethod.REALTIME_FEED -> 5 * 60_000L
    }

/**
 * Тематична група шару — для екрана «Джерела даних» і для політики кешування.
 */
enum class LayerTheme {
    BASE, TRANSIT, RAIL, INDOOR, ENFORCEMENT, ADDRESS, TERRAIN, POPULATION, HAZARD, OWN,
}

/**
 * Повний опис шару: юридична частина ([GeoLayer]) плюс експлуатаційна.
 *
 * Розділення не косметичне: [GeoLayer] — це те, що перевіряє [LicenseFirewall],
 * і воно має лишатись мінімальним і незмінним, щоб аудит не залежав від того,
 * скільки полів ми додумали для рендеру.
 */
data class GeoLayerSpec(
    val layer: GeoLayer,
    val theme: LayerTheme,
    val update: UpdateMethod,
    /** Теги OSM або назви тем джерела, які формують шар. Порожньо — джерело не тегове. */
    val sourceSelectors: List<String> = emptyList(),
    /** Мінімальний і максимальний зум, на яких шар має сенс. */
    val minZoom: Int = 0,
    val maxZoom: Int = 14,
    /**
     * Місце шару в 12-доменному дереві ([LayerDomain]) — вісь, ЯК оператор
     * шар шукає, окрема від [theme] (вісь, ЯК шар кешується/оновлюється; див.
     * ia-map-structure.md §1). `null` — шар-підкладка, не контент: полотно,
     * на якому домени малюються, не те, до чого оператор гортає.
     */
    val domain: LayerDomain? = null,
    /**
     * Увімкнено без запиту. За ia-map-structure.md §3 Tier 0 — лише для
     * контенту, чия тиша небезпечна САМЕ тому, що вона зазвичай і є нормою
     * (пропустити тривогу один раз на місяць — катастрофа; пропустити шар
     * камер — ні).
     */
    val defaultActive: Boolean = false,
    /** Доступний, але вимкнений, поки оператор сам не увімкне в налаштуваннях. */
    val requireSetting: String? = null,
    /** За підвищеною довірою (root/платний рівень). */
    val requireRoot: Boolean = false,
    /**
     * Запечатано/GHOST — ніколи не показується поза явним розблокуванням.
     * PC-схема називає це поле `private`; тут інша назва, бо `private` —
     * зарезервоване слово Kotlin.
     */
    val sealedAccess: Boolean = false,
    /** Tier 1 — один дотик у капсулі, а не три меню вглиб. */
    val quickAccess: Boolean = false,
) {
    val id: String get() = layer.id
}

/**
 * Декларативний каталог усіх шарів карти PHANTOM.
 *
 * Головна теза: **ліцензія — це поле в коді, а не примітка в README.** Каталог
 * проходить [LicenseFirewall] у тесті, тож юридичне порушення ламає збірку так
 * само, як синтаксична помилка. Спроба «швиденько додати шар» без атрибуції або
 * з комерційним прапорцем над OSM-топологією не доживає до релізу.
 *
 * Найтонше місце — Overture Maps. Це не одна ліцензія, а гібридне поле: теми,
 * зібрані учасниками з власних джерел, роздаються під CDLA-Permissive 2.0
 * (можна все), а теми, похідні від OSM, лишаються під ODbL зі share-alike. Тому
 * тут вони — ДВА окремі записи, а не один із приміткою: рендерер і аудит мають
 * бачити різницю без читання коментарів.
 */
object LayerCatalog {

    // — Атрибуції. Винесені в константи, бо один і той самий рядок обовʼязковий
    //   для кількох шарів, а розбіжність у формулюванні — типовий шлях загубити
    //   вимогу ліцензії при копіюванні.
    private const val ATTR_OSM = "© Учасники OpenStreetMap (ODbL 1.0)"
    private const val ATTR_ORM = "© Учасники OpenStreetMap, OpenRailwayMap (ODbL 1.0)"
    private const val ATTR_OSM_INDOOR = "© Учасники OpenStreetMap, OpenStationMap (ODbL 1.0)"
    private const val ATTR_OVERTURE_CDLA = "© Overture Maps Foundation (CDLA-Permissive 2.0)"
    private const val ATTR_OVERTURE_ODBL = "© Overture Maps Foundation; дані OSM (ODbL 1.0)"
    private const val ATTR_COPERNICUS = "© ESA / Copernicus DEM GLO-30"
    private const val ATTR_GHSL = "© Європейська комісія, JRC — GHSL (CC BY 4.0)"
    private const val ATTR_WORLDPOP = "© WorldPop, Університет Саутгемптона (CC BY 4.0)"
    private const val ATTR_AQICN = "Дані якості повітря — World Air Quality Index Project (aqicn.org)"

    /**
     * Усі шари продукту. Порядок — це порядок відображення в списку джерел,
     * не порядок рендерингу.
     */
    val specs: List<GeoLayerSpec> = listOf(
        // — Базова підкладка ————————————————————————————————————————————
        GeoLayerSpec(
            layer = GeoLayer(
                id = "overture-base-cdla",
                title = "Базова підкладка — власні теми Overture",
                source = "Overture Maps Foundation (buildings, places — внески учасників)",
                license = LicenseClass.CDLA_PERMISSIVE,
                role = LayerRole.BASEMAP,
                attribution = ATTR_OVERTURE_CDLA,
                joinsOsmTopology = false,
                updateMethod = "щомісячний реліз Overture, перепікання PMTiles",
            ),
            theme = LayerTheme.BASE,
            update = UpdateMethod.BAKED_PMTILES,
            sourceSelectors = listOf("theme=buildings", "theme=places"),
            maxZoom = 15,
        ),
        GeoLayerSpec(
            layer = GeoLayer(
                id = "overture-base-odbl",
                title = "Базова підкладка — теми Overture, похідні від OSM",
                source = "Overture Maps Foundation (transportation, divisions — походять з OSM)",
                license = LicenseClass.ODBL,
                role = LayerRole.BASEMAP,
                attribution = ATTR_OVERTURE_ODBL,
                // Ці теми САМІ є похідними від OSM, тому лишаються під ODbL.
                // Прапорець описує наше обʼєднання, а не походження джерела:
                // ми рендеримо їх як є, нічого з OSM не зшиваючи.
                joinsOsmTopology = false,
                updateMethod = "щомісячний реліз Overture, перепікання PMTiles",
            ),
            theme = LayerTheme.BASE,
            update = UpdateMethod.BAKED_PMTILES,
            sourceSelectors = listOf("theme=transportation", "theme=divisions"),
            maxZoom = 15,
        ),
        GeoLayerSpec(
            layer = GeoLayer(
                id = "overture-addresses",
                title = "Адреси",
                source = "Overture Maps Foundation — Addresses",
                license = LicenseClass.CDLA_PERMISSIVE,
                role = LayerRole.OVERLAY,
                attribution = ATTR_OVERTURE_CDLA,
                joinsOsmTopology = false,
                updateMethod = "щомісячний реліз Overture",
            ),
            theme = LayerTheme.ADDRESS,
            update = UpdateMethod.BAKED_PMTILES,
            minZoom = 14,
            maxZoom = 16,
        ),

        // — Метро, indoor, залізниця ————————————————————————————————————
        GeoLayerSpec(
            layer = GeoLayer(
                id = "metro-indoor",
                title = "Метро та внутрішня навігація",
                source = "OpenStreetMap / OpenStationMap",
                license = LicenseClass.ODBL,
                role = LayerRole.OVERLAY,
                attribution = ATTR_OSM_INDOOR,
                joinsOsmTopology = false,
                updateMethod = "Overpass на вимогу для конкретної станції",
            ),
            theme = LayerTheme.INDOOR,
            update = UpdateMethod.OVERPASS_ON_DEMAND,
            sourceSelectors = listOf(
                "railway=subway_entrance", "highway=elevator", "highway=steps",
                "railway=platform", "public_transport=platform", "indoor=*", "level=*",
            ),
            minZoom = 16,
            maxZoom = 20,
        ),
        GeoLayerSpec(
            layer = GeoLayer(
                id = "railway-network",
                title = "Залізнична мережа",
                source = "OpenRailwayMap (на даних OpenStreetMap)",
                license = LicenseClass.ODBL,
                role = LayerRole.OVERLAY,
                attribution = ATTR_ORM,
                joinsOsmTopology = false,
                updateMethod = "перепікання PMTiles з щотижневого дампа",
            ),
            theme = LayerTheme.RAIL,
            update = UpdateMethod.BAKED_PMTILES,
            sourceSelectors = listOf("railway=rail", "railway=subway", "railway=tram", "railway=station"),
            minZoom = 6,
        ),

        // — Транзит ——————————————————————————————————————————————————————
        GeoLayerSpec(
            layer = GeoLayer(
                id = "transit-static",
                title = "Пасажирський транзит — розклад",
                source = "GTFS-фіди перевізників",
                license = LicenseClass.CC_BY,
                role = LayerRole.OVERLAY,
                // Умови GTFS визначає перевізник; більшість українських і
                // європейських фідів — CC-BY. Атрибуція формується підстановкою
                // назви перевізника при імпорті конкретного фіда.
                attribution = "Дані розкладу надано перевізниками (GTFS); умови — за джерелом фіда",
                joinsOsmTopology = false,
                updateMethod = "щоденний імпорт статичного GTFS",
            ),
            theme = LayerTheme.TRANSIT,
            update = UpdateMethod.PERIODIC_IMPORT,
            minZoom = 10,
        ),
        GeoLayerSpec(
            layer = GeoLayer(
                id = "transit-realtime",
                title = "Пасажирський транзит — рух у реальному часі",
                source = "GTFS-Realtime фіди перевізників",
                license = LicenseClass.CC_BY,
                role = LayerRole.OVERLAY,
                attribution = "Дані руху надано перевізниками (GTFS-RT); умови — за джерелом фіда",
                joinsOsmTopology = false,
                updateMethod = "опитування кожні 20–30 с",
            ),
            theme = LayerTheme.TRANSIT,
            update = UpdateMethod.REALTIME_FEED,
            minZoom = 12,
        ),

        // — Контроль швидкості ——————————————————————————————————————————
        GeoLayerSpec(
            layer = GeoLayer(
                id = "speed-cameras",
                title = "Камери контролю швидкості",
                source = "OpenStreetMap",
                license = LicenseClass.ODBL,
                role = LayerRole.OVERLAY,
                attribution = ATTR_OSM,
                joinsOsmTopology = false,
                updateMethod = "Overpass по коридору маршруту",
            ),
            theme = LayerTheme.ENFORCEMENT,
            update = UpdateMethod.OVERPASS_ON_DEMAND,
            sourceSelectors = listOf("highway=speed_camera", "type=enforcement", "enforcement=maxspeed"),
            minZoom = 10,
            // Ознака впізнаваності, не предмету — той самий гейт, який
            // старий `hacker` ділив випадково, не за задумом.
            domain = LayerDomain.SIGNALS_SURVEILLANCE,
            quickAccess = true,
        ),

        // — Рельєф ————————————————————————————————————————————————————————
        GeoLayerSpec(
            layer = GeoLayer(
                id = "terrain-copernicus",
                title = "Рельєф — Copernicus DEM GLO-30",
                source = "ESA Copernicus DEM GLO-30",
                license = LicenseClass.CC_BY,
                role = LayerRole.OVERLAY,
                attribution = ATTR_COPERNICUS,
                joinsOsmTopology = false,
                updateMethod = "разове завантаження, оновлення за релізом ESA",
            ),
            theme = LayerTheme.TERRAIN,
            update = UpdateMethod.PERIODIC_IMPORT,
            maxZoom = 12,
        ),
        GeoLayerSpec(
            layer = GeoLayer(
                id = "terrain-nasadem",
                title = "Рельєф — NASADEM",
                source = "NASA NASADEM (перероблений SRTM)",
                // Продукти NASA — суспільне надбання; атрибуція бажана, але не є умовою.
                license = LicenseClass.PUBLIC_DOMAIN,
                role = LayerRole.OVERLAY,
                attribution = "Рельєф — NASA NASADEM",
                joinsOsmTopology = false,
                updateMethod = "разове завантаження",
            ),
            theme = LayerTheme.TERRAIN,
            update = UpdateMethod.PERIODIC_IMPORT,
            maxZoom = 12,
        ),

        // — Демографія ——————————————————————————————————————————————————
        GeoLayerSpec(
            layer = GeoLayer(
                id = "population-ghsl",
                title = "Щільність населення — GHSL",
                source = "Global Human Settlement Layer, JRC",
                license = LicenseClass.CC_BY,
                role = LayerRole.OVERLAY,
                attribution = ATTR_GHSL,
                joinsOsmTopology = false,
                updateMethod = "разове завантаження растру",
            ),
            theme = LayerTheme.POPULATION,
            update = UpdateMethod.PERIODIC_IMPORT,
            maxZoom = 12,
        ),
        GeoLayerSpec(
            layer = GeoLayer(
                id = "population-worldpop",
                title = "Щільність населення — WorldPop",
                source = "WorldPop",
                license = LicenseClass.CC_BY,
                role = LayerRole.OVERLAY,
                attribution = ATTR_WORLDPOP,
                joinsOsmTopology = false,
                updateMethod = "разове завантаження растру",
            ),
            theme = LayerTheme.POPULATION,
            update = UpdateMethod.PERIODIC_IMPORT,
            maxZoom = 12,
        ),

        // — Небезпеки ————————————————————————————————————————————————————
        GeoLayerSpec(
            layer = GeoLayer(
                id = "fires-firms",
                title = "Теплові аномалії та пожежі",
                source = "NASA FIRMS (VIIRS/MODIS)",
                license = LicenseClass.PUBLIC_DOMAIN,
                role = LayerRole.OVERLAY,
                attribution = "Дані пожеж — NASA FIRMS",
                joinsOsmTopology = false,
                updateMethod = "опитування кожні 15 хв",
            ),
            theme = LayerTheme.HAZARD,
            update = UpdateMethod.REALTIME_FEED,
            maxZoom = 12,
            domain = LayerDomain.DANGER,
        ),
        GeoLayerSpec(
            layer = GeoLayer(
                id = "air-quality-aqicn",
                title = "Якість повітря",
                source = "World Air Quality Index Project (AQICN)",
                license = LicenseClass.CC_BY,
                role = LayerRole.OVERLAY,
                attribution = ATTR_AQICN,
                joinsOsmTopology = false,
                updateMethod = "опитування кожні 30 хв",
            ),
            theme = LayerTheme.HAZARD,
            update = UpdateMethod.REALTIME_FEED,
            maxZoom = 12,
            domain = LayerDomain.ENVIRONMENT,
        ),
        GeoLayerSpec(
            layer = GeoLayer(
                id = "earthquakes-usgs",
                title = "Землетруси",
                source = "USGS Earthquake Hazards Program",
                license = LicenseClass.PUBLIC_DOMAIN,
                role = LayerRole.OVERLAY,
                attribution = "Дані сейсмічності — USGS",
                joinsOsmTopology = false,
                updateMethod = "опитування кожні 5 хв",
            ),
            theme = LayerTheme.HAZARD,
            update = UpdateMethod.REALTIME_FEED,
            maxZoom = 10,
            domain = LayerDomain.DANGER,
        ),

        // — Рух і місцевість — сфера сипу (build-now) ——————————————————————
        GeoLayerSpec(
            layer = GeoLayer(
                id = "power-towers",
                title = "Опори ЛЕП",
                source = "OpenStreetMap",
                license = LicenseClass.ODBL,
                role = LayerRole.OVERLAY,
                attribution = ATTR_OSM,
                joinsOsmTopology = false,
                updateMethod = "Overpass навколо точки",
            ),
            theme = LayerTheme.TERRAIN,
            update = UpdateMethod.OVERPASS_ON_DEMAND,
            sourceSelectors = listOf("power=tower"),
            minZoom = 12,
            maxZoom = 18,
            // Найгустіша мережа орієнтирів у країні (629 790 обʼєктів) — тут
            // навмисно НЕ як мережа електропостачання (це б успадкувало
            // require_root від PC-шару «grid presence»), а як видимий з
            // дороги орієнтир. Жодних атрибутів напруги/власника не показано.
            domain = LayerDomain.MOVEMENT_TERRAIN,
            quickAccess = true,
        ),
        GeoLayerSpec(
            layer = GeoLayer(
                id = "drainage-crossings",
                title = "Канави та труби",
                source = "OpenStreetMap",
                license = LicenseClass.ODBL,
                role = LayerRole.OVERLAY,
                attribution = ATTR_OSM,
                joinsOsmTopology = false,
                updateMethod = "Overpass навколо точки",
            ),
            theme = LayerTheme.TERRAIN,
            update = UpdateMethod.OVERPASS_ON_DEMAND,
            // waterway=culvert — майже порожній тег (15 нацією); справжні
            // труби під дорогою позначені tunnel=culvert (62 932) поверх
            // іншого шляху. drain/ditch — 111 428 обʼєктів, перший-клас
            // перешкода для проїзду. Один шар, дві чесні позначки, а не
            // приховане «прохідно» — жодна з цих ознак не каже про поточний
            // стан, лише про те, що OSM це записав.
            sourceSelectors = listOf("waterway=drain", "waterway=ditch", "tunnel=culvert"),
            minZoom = 12,
            maxZoom = 18,
            domain = LayerDomain.MOVEMENT_TERRAIN,
            quickAccess = true,
        ),
        GeoLayerSpec(
            layer = GeoLayer(
                id = "fords",
                title = "Броди",
                source = "OpenStreetMap",
                license = LicenseClass.ODBL,
                role = LayerRole.OVERLAY,
                attribution = ATTR_OSM,
                joinsOsmTopology = false,
                updateMethod = "Overpass навколо точки",
            ),
            theme = LayerTheme.TERRAIN,
            update = UpdateMethod.OVERPASS_ON_DEMAND,
            sourceSelectors = listOf("ford=yes"),
            minZoom = 12,
            maxZoom = 18,
            domain = LayerDomain.MOVEMENT_TERRAIN,
            quickAccess = true,
        ),

        // — Власні дані ——————————————————————————————————————————————————
        GeoLayerSpec(
            layer = GeoLayer(
                id = "phantom-own",
                title = "Дані Nightpost",
                source = "Власна база Nightpost",
                license = LicenseClass.PROPRIETARY,
                role = LayerRole.PROPRIETARY_DATA,
                attribution = "",
                // Ключове рішення продукту: власні обʼєкти звʼязуються з картою
                // ЛИШЕ за стабільним ідентифікатором (див. gers/GersId.kt) на
                // рівні рендерингу. Жодного просторового обʼєднання з геометрією
                // OSM — інакше вся база стає похідною і підпадає під share-alike.
                joinsOsmTopology = false,
                updateMethod = "внутрішнє оновлення",
            ),
            theme = LayerTheme.OWN,
            update = UpdateMethod.INTERNAL,
            minZoom = 0,
            maxZoom = 20,
        ),
    )

    /** Юридичні картки шарів — рівно те, що подається на вхід [LicenseFirewall]. */
    val layers: List<GeoLayer> get() = specs.map { it.layer }

    /** Пошук за ідентифікатором. */
    fun byId(id: String): GeoLayerSpec? = specs.firstOrNull { it.id == id }

    /** Шари однієї теми. */
    fun byTheme(theme: LayerTheme): List<GeoLayerSpec> = specs.filter { it.theme == theme }

    /** Шари одного домену дерева каталогу — той самий фільтр, яким живе Tier 2 (LayerLibrary). */
    fun byDomain(domain: LayerDomain): List<GeoLayerSpec> = specs.filter { it.domain == domain }

    /**
     * Tier 1 — реєстр-джерело для капсули «швидкого доступу». Раніше цей
     * список був окремим hardcoded union (MapLayerKey-подібна проблема) —
     * тепер він читається звідси, тож новий шар не потребує нового union-члена.
     */
    fun quickAccessLayers(): List<GeoLayerSpec> = specs.filter { it.quickAccess }

    /** Шари, які працюють без мережі. */
    fun offlineCapable(): List<GeoLayerSpec> = specs.filter { it.update == UpdateMethod.BAKED_PMTILES }

    /**
     * Аудит каталогу. Порушення ліцензій плюс структурні дефекти, які
     * [LicenseFirewall] не бачить (він працює зі списком, а не з реєстром):
     * дубльований id зробив би [byId] недетермінованим, а атрибуцію —
     * непередбачуваною.
     */
    fun audit(): List<LicenseViolation> {
        val out = mutableListOf<LicenseViolation>()
        out += LicenseFirewall.audit(layers)
        specs.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { dup ->
            out += LicenseViolation(dup, "duplicate-id", "Ідентифікатор шару «$dup» використано двічі.")
        }
        specs.filter { it.minZoom > it.maxZoom }.forEach {
            out += LicenseViolation(it.id, "impossible-zoom-range", "minZoom ${it.minZoom} > maxZoom ${it.maxZoom}.")
        }
        return out
    }

    /** Рядки для екрана «Джерела даних». */
    fun attributionLines(): List<String> = LicenseFirewall.attributionLines(layers)
}
