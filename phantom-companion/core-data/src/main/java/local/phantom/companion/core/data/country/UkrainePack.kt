package local.phantom.companion.core.data.country

import local.phantom.companion.core.data.law.CurfewRule
import local.phantom.companion.core.data.law.CurfewScope
import local.phantom.companion.core.data.transit.GtfsCityFeed
import local.phantom.companion.core.data.transit.GtfsScheduleAsset
import local.phantom.companion.core.data.transit.GtfsScheduleFeed
import local.phantom.companion.core.net.map.AdapterSchema

/**
 * Перший пак: Україна перестає бути ядром і стає плагіном.
 *
 * Усе, що тут лежить, раніше було вшите в шість місць коду — фолбек Києва,
 * шляхи асетів, три клієнти стрічок, комендантська, реєстр GTFS і назва
 * окупованої області рядком у виразі. Додати другу країну тепер означає
 * написати другий такий файл, а не другий клієнт.
 *
 * Асети лишаються в збірці (ця хвиля — шви, не завантаження); хвиля 2 віддає
 * той самий контракт з R2.
 */
object UkrainePack : CountryPack {

    override val id = "UA"

    override val bounds = PackBounds(
        minLat = 44.0,
        minLon = 22.0,
        maxLat = 52.5,
        maxLon = 40.3,
    )

    override val home = MapHome(lat = 50.4501, lon = 30.5234, zoom = 12.0)

    override val regionGeometryAsset = "country/ua/oblasts.geojson"

    override val regionIndexAsset = "country/ua/region_index.json"

    override val poiAsset = "country/ua/poi.json"

    override val streetsAsset = "country/ua/streets.tsv"

    override val placesAsset = "country/ua/places.tsv"

    override val settlementsAsset = "country/ua/settlements.tsv"

    override val metroAsset = "country/ua/metro.json"

    override val surfaceAsset = "country/ua/surface.json"

    /**
     * Київ їздить за розкладом КП «Київпастранс», не за моделлю інтервалів.
     * Перевірено 13.09.2026: 200 без ключа, 159 маршрутів, 24 091 рейс,
     * 103 184 відправлення, версія фіда того ж дня, нуль координат поза
     * межами міста. Метро сюди не входить — це інший перевізник, який не
     * публікує нічого, тож підземка лишається моделлю й мусить бути так
     * і підписана.
     */
    override val scheduleAssets = listOf(
        GtfsScheduleAsset(
            city = "Київ",
            asset = "country/ua/kyiv_gtfs.zip",
            attribution = ATTR_KPT,
        ),
    )

    override val officialFacilitiesAsset = "country/ua/official_facilities.json"

    override val invincibilityAsset = "country/ua/invincibility_points.json"

    override val needsIndexAsset = "country/ua/needs_index.bin"

    /**
     * Виміряно 06.09.2026: державний реєстр пунктів незламності публікує
     * дані лише для Києва (міське API), Чернівецької та Одеської областей —
     * решта двадцяти однієї не віддає нічого жодному з трьох запитів. Імена
     * дослівно ті самі, що й `name` у oblasts.geojson (див. [OblastShapes]),
     * бо саме з ним звіряють, «твоя область покрита чи ні».
     */
    override val invincibilityCoveredRegions = setOf(
        "м. Київ",
        "Чернівецька область",
        "Одеська область",
    )

    private const val ATTR_OSM = "© Учасники OpenStreetMap (ODbL 1.0)"
    private const val ATTR_NSZU = "© Національна служба здоровʼя України (CC BY 4.0)"
    private const val ATTR_KYIV = "© Київська міська державна адміністрація, kyiv.digital (CC BY 4.0)"
    private const val ATTR_CHERNIVTSI = "© Чернівецька обласна військова адміністрація (CC BY 4.0)"
    private const val ATTR_ODESA = "© Одеська обласна військова адміністрація (CC BY 4.0)"
    private const val ATTR_KPT = "© КП «Київпастранс» (CC BY 4.0)"

    /**
     * Ліцензії на дані пака. Тут вони — поле, а не примітка в README: коли пак
     * приїде дротом, рівно ці картки пройдуть `LicenseFirewall` перед тим, як
     * його геометрія торкнеться мапи. Вбудований пак ходить тим самим аудитом
     * у тесті — інакше правило перевіряло б лише чужі паки.
     */
    override val sources = listOf(
        PackSource(
            id = "ua-oblasts",
            title = "Полігони областей",
            source = "OpenStreetMap (адміністративні межі)",
            license = "ODBL",
            role = "OVERLAY",
            attribution = ATTR_OSM,
            asset = regionGeometryAsset,
        ),
        PackSource(
            id = "ua-poi",
            title = "Офлайновий витяг POI",
            source = "OpenStreetMap (Overpass)",
            license = "ODBL",
            role = "OVERLAY",
            attribution = ATTR_OSM,
            asset = poiAsset,
        ),
        PackSource(
            id = "ua-streets",
            title = "Офлайновий довідник вулиць",
            source = "OpenStreetMap (через road-mesh-bake)",
            license = "ODBL",
            role = "OVERLAY",
            attribution = ATTR_OSM,
            asset = streetsAsset,
        ),
        PackSource(
            id = "ua-places",
            // Назва каже, що це, і чого це НЕ обіцяє: броди тут — позначені
            // переправи, а не судження про прохідність.
            title = "Національний довідник місць — вода, пальне, ліки, позначені броди",
            source = "OpenStreetMap (витяг країни)",
            license = "ODBL",
            role = "OVERLAY",
            attribution = ATTR_OSM,
            asset = placesAsset,
        ),
        PackSource(
            id = "ua-settlements",
            title = "Населені пункти — 68 010 міст, селищ, сіл і хуторів",
            source = "OpenStreetMap (витяг країни, кузня)",
            license = "ODBL",
            role = "OVERLAY",
            attribution = ATTR_OSM,
            asset = settlementsAsset,
        ),
        PackSource(
            id = "ua-metro",
            title = "Змодельоване метро Києва",
            source = "OpenStreetMap (топологія) + модель інтервалів",
            license = "ODBL",
            role = "OVERLAY",
            attribution = ATTR_OSM,
            asset = metroAsset,
        ),
        // Назва каже рівно те, чим воно лишилось після переходу на розклад:
        // ТРАСИ, якими маршрут фізично їде. Хвилини звідси більше не беруть.
        PackSource(
            id = "ua-surface",
            title = "Траси наземного транспорту Києва",
            source = "OpenStreetMap (топологія й геометрія колії)",
            license = "ODBL",
            role = "OVERLAY",
            attribution = ATTR_OSM,
            asset = surfaceAsset,
        ),
        PackSource(
            id = "ua-kyiv-schedule",
            title = "Розклад наземного транспорту Києва — 103 184 відправлення",
            source = "КП «Київпастранс», відкриті дані (GTFS)",
            license = "CC_BY",
            role = "OVERLAY",
            attribution = ATTR_KPT,
            asset = "country/ua/kyiv_gtfs.zip",
        ),
        PackSource(
            id = "ua-region-index",
            title = "Індекс регіонів стрічки тривог",
            source = "siren.pp.ua (дерево регіонів)",
            license = "PUBLIC_DOMAIN",
            role = "OVERLAY",
            asset = regionIndexAsset,
        ),
        PackSource(
            id = "ua-official-health",
            title = "Аптеки та медзаклади — реєстр НСЗУ",
            source = "Національна служба здоровʼя України (набори «pharmacies_divisions», «providers_divisions»)",
            license = "CC_BY",
            role = "OVERLAY",
            attribution = ATTR_NSZU,
            asset = officialFacilitiesAsset,
        ),
        // Три картки на ОДИН асет: реєстр пунктів незламності зшитий з трьох
        // незалежних міських/обласних джерел, і кожне має власну атрибуцію —
        // обʼєднання в асеті не має ховати, хто саме що опублікував.
        PackSource(
            id = "ua-invincibility-kyiv",
            title = "Пункти незламності — Київ",
            source = "kyiv.digital (відкриті дані КМДА)",
            license = "CC_BY",
            role = "OVERLAY",
            attribution = ATTR_KYIV,
            asset = invincibilityAsset,
        ),
        PackSource(
            id = "ua-invincibility-chernivtsi",
            title = "Пункти незламності — Чернівецька область",
            source = "Чернівецька ОВА (відкритий реєстр)",
            license = "CC_BY",
            role = "OVERLAY",
            attribution = ATTR_CHERNIVTSI,
            asset = invincibilityAsset,
        ),
        PackSource(
            id = "ua-invincibility-odesa",
            title = "Пункти незламності — Одеська область",
            source = "Одеська ОВА (перелік адрес; координати — офлайн-геокодування власним витягом OSM)",
            license = "CC_BY",
            role = "OVERLAY",
            attribution = ATTR_ODESA,
            asset = invincibilityAsset,
        ),
        // Індекс потреб — не нове джерело, а похідна від чотирьох попередніх:
        // векторами закодовано ті самі рядки НСЗУ й трьох реєстрів
        // незламності. Тому чотири картки на один асет, а не одна власна:
        // похідне не звільняє від атрибуції того, з чого воно вийшло.
        PackSource(
            id = "ua-needs-index-nszu",
            title = "Векторний індекс потреб — НСЗУ",
            source = "Національна служба здоровʼя України (через bake_needs_index)",
            license = "CC_BY",
            role = "OVERLAY",
            attribution = ATTR_NSZU,
            asset = needsIndexAsset,
        ),
        PackSource(
            id = "ua-needs-index-kyiv",
            title = "Векторний індекс потреб — Київ",
            source = "kyiv.digital (через bake_needs_index)",
            license = "CC_BY",
            role = "OVERLAY",
            attribution = ATTR_KYIV,
            asset = needsIndexAsset,
        ),
        PackSource(
            id = "ua-needs-index-chernivtsi",
            title = "Векторний індекс потреб — Чернівецька область",
            source = "Чернівецька ОВА (через bake_needs_index)",
            license = "CC_BY",
            role = "OVERLAY",
            attribution = ATTR_CHERNIVTSI,
            asset = needsIndexAsset,
        ),
        PackSource(
            id = "ua-needs-index-odesa",
            title = "Векторний індекс потреб — Одеська область",
            source = "Одеська ОВА (через bake_needs_index)",
            license = "CC_BY",
            role = "OVERLAY",
            attribution = ATTR_ODESA,
            asset = needsIndexAsset,
        ),
    )

    /**
     * Словник загроз пака — найспецифічніше першим, щоб «kn-23» не проковтнув
     * ширший «ракет». Порядок несе сенс: і адаптер, і вільнотекстовий детектор
     * беруть ПЕРШУ категорію зі збігом, тож це LinkedHashMap, не набір.
     *
     * Ключі — імена `ThreatType` у feature-stream: перелік лишається кодом
     * (гліфи, кольори, суворість), словник під нього — даними пака.
     */
    private val threatKeywords: Map<String, List<String>> = mapOf(
        "BALLISTIC" to listOf("балістик", "балистик", "іскандер", "ісквндер", "kn-23", "с-300"),
        "SHAHED" to listOf("шахед", "шахід", "бпла", "дрон", "uav", "вхід у простір"),
        "MIG" to listOf("міг", "миг", "31к", "кинджал", "кинжал"),
        "KAB" to listOf("каб", "фаб", "керовані авіабомби", "тактична авіація"),
        "MISSILE" to listOf("ракет", "крилат", "калібр"),
        "ARTILLERY" to listOf("артобстріл", "артилер", "обстріл", "рсзв"),
    )

    private const val UBILLING = "https://ubilling.net.ua/aerialalerts/?json"

    override val alarmFeeds = listOf(
        // Ubilling — чиста декларація: жодного клієнта, лише інструкції для
        // UniversalContentAdapter. `states` — обʼєкт, ключований назвою
        // області, тож "id" не мапиться: рушій бере ключ. Формат часу
        // обовʼязковий — `changed` наївний локальний ("2026-07-06 04:02:13"),
        // і загальний epoch/ISO-парсер його не розрізнить.
        AlarmFeed(
            role = AlarmFeedRole.REGION_STATE,
            endpoint = UBILLING,
            userAgent = "PhantomCompanion/0.1 (map air-alert overlay)",
            schema = AdapterSchema(
                sourceUrl = UBILLING,
                rootPath = "states",
                fieldMapping = mapOf(
                    "isActive" to listOf("alertnow", "active", "is_active"),
                    "timestamp" to listOf("changed", "time", "updated_at"),
                    "text" to listOf(
                        "notes", "note", "title", "message",
                        "text", "type", "reason", "desc", "description",
                    ),
                ),
                classificationRules = threatKeywords,
                timestampFormat = "yyyy-MM-dd HH:mm:ss",
                timeZoneId = "Europe/Kyiv",
                userAgent = "PhantomCompanion/0.1 (map air-alert overlay)",
            ),
        ),
        // Типізовані загрози й громади: масив рядків із вкладеним activeAlerts,
        // розвʼязуваний лише через дерево регіонів — форма, якої схема не
        // описує. Пак володіє адресою, читач у feature-stream — розбором.
        AlarmFeed(
            role = AlarmFeedRole.TYPED,
            endpoint = "https://siren.pp.ua/api/v3/alerts",
            userAgent = "PhantomCompanion/0.1 (air-alert types)",
        ),
        AlarmFeed(
            role = AlarmFeedRole.DETAIL,
            endpoint = "https://api.alerts.in.ua/v1/alerts/active.json",
            userAgent = "PhantomCompanion/0.1",
            tokenKey = "secrets.alerts_in_ua_token",
        ),
    )

    /**
     * Лише загальнонаціональний дефолт воєнного стану. Області ставлять власні
     * години і вони різні — доки кожну не звірено окремо, це чесна підлога з
     * джерелом, а не вигаданий закон.
     */
    override val curfewRules = listOf(
        CurfewRule(
            regionId = id,
            scope = CurfewScope.NATIONAL,
            zoneId = "Europe/Kyiv",
            startMinuteOfDay = 0,
            endMinuteOfDay = 5 * 60,
            source = "загальний дефолт воєнного стану",
            verifiedAt = "2026-08",
        ),
    )

    /**
     * track.ua-gis.com, без ключа. HTTPS навмисно — http віддає 301, а
     * cleartext заблоковано.
     *
     * Кожен рядок доданий лише після розбору протобуфа руками. Виміряно
     * 13.09.2026: Львів 418 машин, Ужгород 20, Стрий 13 — усі з придатними
     * координатами над своїм містом. Решта міст, які раніше значились у
     * розвідці, на цьому хості віддають 404: їх тут немає, і мапа за них
     * мовчить.
     *
     * Київ свідомо ВІДСУТНІЙ: позицій київського транспорту не публікує
     * ніхто без ключа, тож на склі його рухомих міток не буде — лише
     * розклад, і тільки як розклад.
     */
    override val transitFeeds = listOf(
        GtfsCityFeed(
            city = "Львів",
            anchorLat = 49.8397,
            anchorLon = 24.0297,
            radiusKm = 25.0,
            vehiclePositionsUrl = "https://track.ua-gis.com/gtfs/lviv/vehicle_position",
        ),
        GtfsCityFeed(
            city = "Ужгород",
            anchorLat = 48.6208,
            anchorLon = 22.2879,
            radiusKm = 15.0,
            vehiclePositionsUrl = "https://track.ua-gis.com/gtfs/uzhhorod/vehicle_position",
        ),
        GtfsCityFeed(
            city = "Стрий",
            anchorLat = 49.2617,
            anchorLon = 23.8567,
            radiusKm = 15.0,
            vehiclePositionsUrl = "https://track.ua-gis.com/gtfs/stryi/vehicle_position",
        ),
    )

    /**
     * ОПУБЛІКОВАНІ РОЗКЛАДИ. Переміряно 13.09.2026, обидва без ключа.
     *
     * Київ віддає `feed_version` = дата збірки, 1 204 372 Б, 1 479 зупинок
     * (100 % у межах міста), 24 091 рейс, з них 10 601 на день виміру;
     * `route_type` 0/3/11 — трамвай, автобус, тролейбус. Метро в архіві
     * НЕМА, і саме тому метро лишається за [metroAsset]: з трьох закидів
     * старого коментаря в scripts/fetch_surface_transit.py («403-gated,
     * metro-less, half-dead») справдився рівно один — цей.
     *
     * 403 не справдився: тим самим `curl` і каталог, і файл дають 200.
     * Хибний вирок стояв в архітектурі весь той час, поки наземний
     * транспорт Києва малювався інтервальною моделлю.
     *
     * ЧОМУ http. HTTPS на 193.23.225.211:8002 не відповідає взагалі, а
     * https-посилання data.gov.ua віддає 302 РІВНО СЮДИ. Виняток у
     * `network_security_config.xml` звужено до цієї адреси й нічого
     * більше. Загроза тут — підміна ЧИСЕЛ у дорозі (цілісність), не
     * розкриття: запит анонімний, тіло — опублікований розклад. Це гірше
     * за TLS і краще за модель, яка вигадує гарантовано.
     */
    override val scheduleFeeds = listOf(
        GtfsScheduleFeed(
            city = "Київ",
            anchorLat = 50.4501,
            anchorLon = 30.5234,
            radiusKm = 35.0,
            staticUrl = "http://193.23.225.211:8002/export-gtfs-static",
            attribution = "КП «Київпастранс» / КМДА, data.gov.ua, CC-BY",
        ),
        GtfsScheduleFeed(
            city = "Львів",
            anchorLat = 49.8397,
            anchorLon = 24.0297,
            radiusKm = 25.0,
            staticUrl = "https://track.ua-gis.com/gtfs/lviv/static.zip",
            attribution = "Львівська міська рада, data.gov.ua, CC-BY",
        ),
    )

    /**
     * Області під окупацією з 2014-го: присутність обʼєкта в даних там більше
     * не свідчить про сьогодні (Маріуполь — 96,9% поверховості, 2,9% правок
     * після 2022-го). Свідомо БЕЗ прифронтових областей 2022-го: у них великі
     * вільні міста, і загальний даунгрейд був би іншою неправдою, а не
     * обережним недорахунком. Назви збігаються з полігонами пака дослівно.
     */
    override val rememberedRegions = setOf(
        "Донецька область",
        "Луганська область",
        "Автономна Республіка Крим",
    )
}
