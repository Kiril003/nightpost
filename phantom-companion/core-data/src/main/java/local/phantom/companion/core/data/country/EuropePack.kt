package local.phantom.companion.core.data.country

import local.phantom.companion.core.data.law.CurfewRule
import local.phantom.companion.core.data.transit.GtfsCityFeed
import local.phantom.companion.core.data.transit.GtfsScheduleFeed

/**
 * Європейський пак: покриття континентальної Європи (без України, яка в UkrainePack).
 *
 * Пак не має бандлених асетів (геометрії, POI, вулиць) — всі дані йдуть
 * через живий Overpass і Transitous MOTIS. Він дає:
 *  - рамку, щоб CountryPackRegistry знав «оператор у Європі»;
 *  - домашню точку (Берлін) як фолбек;
 *  - перевірені відкриті GTFS-RT VehiclePositions фіди великих міст.
 *
 * Маршрутизація транспорту — через GlobalTransitRouter → Transitous MOTIS,
 * який покриває всю Європу і Північну Америку з відкритих даних. Фіди тут —
 * лише для живих позицій транспорту на мапі (рухомі маркери).
 */
object EuropePack : CountryPack {

    override val id = "EU"

    override val bounds = PackBounds(
        minLat = 35.0,
        minLon = -10.0,
        maxLat = 71.5,
        maxLon = 40.0,
    )

    override val home = MapHome(lat = 52.5200, lon = 13.4050, zoom = 5.0)

    override val regionGeometryAsset: String? = null
    override val regionIndexAsset: String? = null

    override val alarmFeeds: List<AlarmFeed> = emptyList()
    override val curfewRules: List<CurfewRule> = emptyList()
    override val rememberedRegions: Set<String> = emptySet()

    /**
     * Відкриті GTFS-RT VehiclePositions для міст і мереж Європи.
     *
     * [GtfsRealtimeClient] читає лише поле `vehicle` сутності фіда, тож сюди
     * годиться ТІЛЬКИ VehiclePositions: TripUpdates чи JSON-API віддадуть
     * порожній список назавжди й без жодної скарги на склі.
     *
     * 12.09 звідси знято два записи, які цього не могли: Гданськ вів на
     * `…/tripupdates.pb`, Варшава — на JSON `api/action/busestrams_get`.
     * Обидва суперечили підпису «перевірено curl» прямо у власному літералі.
     *
     * 14.09 стара Прага (api.golemio.cz/v2/public/…) і Вроцлав
     * (www.wroclaw.pl) перевірені ЗНОВУ — обидва вже мертві (401 і 403
     * відповідно): саме та підозра з коментаря вище справдилась. Вроцлав
     * знято без заміни; Прага замінена на живий шлях того самого
     * постачальника. Весь перелік нижче зібрано з каталогу
     * MobilityData (github.com/MobilityData/mobility-database-catalogs,
     * authentication_type=0 — без ключа) і КОЖЕН рядок продзвонений curl
     * 14.09: HTTP 200, тіло розбирається як GTFS-RT FeedMessage (protobuf
     * тег 0x0a — заголовок), розмір тіла нетривіальний (не порожній фід).
     * Якір і радіус — з реальної bounding_box статичного GTFS-побратима
     * каталогу де вона є, інакше з геокодування назви міста (Nominatim);
     * нічого не вигадано на око. Renfe Cercanías, OVapi і Entur — це один
     * фід на всю мережу (нацагрегат): анкери в кількох вузлах замість
     * одного величезного радіуса, щоб не заявляти живе покриття там, де
     * дроту насправді нема.
     */
    override val transitFeeds = listOf(
        GtfsCityFeed(
            city = "Прага (PID)",
            anchorLat = 49.9795,
            anchorLon = 14.4445,
            radiusKm = 154.7,
            vehiclePositionsUrl = "https://api.golemio.cz/v2/vehiclepositions/gtfsrt/vehicle_positions.pb",
        ),
        GtfsCityFeed(
            city = "Софія",
            anchorLat = 42.6977,
            anchorLon = 23.3217,
            radiusKm = 25.0,
            vehiclePositionsUrl = "https://gtfs.sofiatraffic.bg/api/v1/vehicle-positions",
        ),
        GtfsCityFeed(
            city = "Elron (нацмережа заліз. Естонії)",
            anchorLat = 59.4372,
            anchorLon = 24.7573,
            radiusKm = 230.0,
            vehiclePositionsUrl = "https://jbb.ghsq.de/gtfs/elron/VehiclePositions",
        ),
        GtfsCityFeed(
            city = "Малага",
            anchorLat = 36.7132,
            anchorLon = -4.4559,
            radiusKm = 15.0,
            vehiclePositionsUrl = "https://gtfs.emtmalaga.es/GTFSRTapi/api/vehicleposition",
        ),
        GtfsCityFeed(
            city = "Фуенлабрада (Мадрид)",
            anchorLat = 40.2825,
            anchorLon = -3.7923,
            radiusKm = 15.0,
            vehiclePositionsUrl = "https://api.control.optibus.co/opendata/v1/gtfs-rt/vehicle-positions?uid=c-5cfcd2d1",
        ),
        GtfsCityFeed(
            city = "Metro Більбао",
            anchorLat = 43.263,
            anchorLon = -2.935,
            radiusKm = 20.0,
            vehiclePositionsUrl = "https://ctb-gtfs-rt.s3.eu-south-2.amazonaws.com/metro-bilbao-vehicle-positions.pb",
        ),
        GtfsCityFeed(
            city = "Renfe Cercanías Мадрид",
            anchorLat = 40.4168,
            anchorLon = -3.7038,
            radiusKm = 70.0,
            vehiclePositionsUrl = "https://gtfsrt.renfe.com/vehicle_positions.pb",
        ),
        GtfsCityFeed(
            city = "Renfe Cercanías Барселона",
            anchorLat = 41.3874,
            anchorLon = 2.1686,
            radiusKm = 60.0,
            vehiclePositionsUrl = "https://gtfsrt.renfe.com/vehicle_positions.pb",
        ),
        GtfsCityFeed(
            city = "Renfe Cercanías Валенсія",
            anchorLat = 39.4699,
            anchorLon = -0.3763,
            radiusKm = 40.0,
            vehiclePositionsUrl = "https://gtfsrt.renfe.com/vehicle_positions.pb",
        ),
        GtfsCityFeed(
            city = "Сан-Себастьян (dBus)",
            anchorLat = 43.3224,
            anchorLon = -1.9839,
            radiusKm = 15.0,
            vehiclePositionsUrl = "https://opendata.euskadi.eus/transport/moveuskadi/dbus/gtfsrt_dbus_vehicle_positions.pb",
        ),
        GtfsCityFeed(
            city = "Арройо-де-ла-Енкомʼєнда (Вальядолід)",
            anchorLat = 41.6211,
            anchorLon = -4.781,
            radiusKm = 10.0,
            vehiclePositionsUrl = "https://enzeyiwpoomhlxmcjivn.supabase.co/functions/v1/gtfs-rt",
        ),
        GtfsCityFeed(
            city = "Гельсінкі (HSL)",
            anchorLat = 60.4819,
            anchorLon = 25.3615,
            radiusKm = 45.0,
            vehiclePositionsUrl = "https://realtime.hsl.fi/realtime/vehicle-positions/v2/hsl",
        ),
        GtfsCityFeed(
            city = "Бордо (TBM)",
            anchorLat = 44.8412,
            anchorLon = -0.58,
            radiusKm = 20.0,
            vehiclePositionsUrl = "https://bdx.mecatran.com/utw/ws/gtfsfeed/vehicles/bordeaux?apiKey=opendata-bordeaux-metropole-flux-gtfs-rt",
        ),
        GtfsCityFeed(
            city = "Нім (Tango Bus)",
            anchorLat = 43.8374,
            anchorLon = 4.3601,
            radiusKm = 15.0,
            vehiclePositionsUrl = "http://185.220.72.38:8085/ProfilGtfsRt2_0RSProducer-TANGO/VehiclePosition.pb",
        ),
        GtfsCityFeed(
            city = "Орошгаза",
            anchorLat = 46.5583,
            anchorLon = 20.672,
            radiusKm = 12.0,
            vehiclePositionsUrl = "https://gtfs.gpspositions.net/storage/realtime/OROSH_V_ONK/vehicle_positions.pb",
        ),
        GtfsCityFeed(
            city = "Венеція (ACTV, судна+автобуси)",
            anchorLat = 45.3594,
            anchorLon = 12.3529,
            radiusKm = 19.3,
            vehiclePositionsUrl = "https://tpl.actv.it/nav/GTFSRT/vehiclePositions",
        ),
        GtfsCityFeed(
            city = "Венеція-суходіл (ACTV автобус)",
            anchorLat = 45.3594,
            anchorLon = 12.3529,
            radiusKm = 19.3,
            vehiclePositionsUrl = "https://tpl.actv.it/aut/GTFSRT/vehiclePositions",
        ),
        GtfsCityFeed(
            city = "Рим (Roma Mobilità)",
            anchorLat = 42.045,
            anchorLon = 12.4485,
            radiusKm = 55.0,
            vehiclePositionsUrl = "https://romamobilita.it/sites/default/files/rome_rtgtfs_vehicle_positions_feed.pb",
        ),
        GtfsCityFeed(
            city = "Lazio регіон (Cotral)",
            anchorLat = 41.8933,
            anchorLon = 12.4829,
            radiusKm = 60.0,
            vehiclePositionsUrl = "https://proxy.busone.app/cotral/gtfs-rt/vehicle-positions.pb",
        ),
        GtfsCityFeed(
            city = "Парма (TEP)",
            anchorLat = 44.8015,
            anchorLon = 10.3279,
            radiusKm = 15.0,
            vehiclePositionsUrl = "https://s3.eu-south-1.amazonaws.com/temporeale.cl.tep.pr.it/temporeale/vehicle_positions.pb",
        ),
        GtfsCityFeed(
            city = "Вільнюс",
            anchorLat = 54.7029,
            anchorLon = 25.2811,
            radiusKm = 23.7,
            vehiclePositionsUrl = "https://www.stops.lt/vilnius/vehicle_positions.pb",
        ),
        GtfsCityFeed(
            city = "Нідерланди (OVapi, нацагрегат)",
            anchorLat = 52.1326,
            anchorLon = 5.2913,
            radiusKm = 200.0,
            vehiclePositionsUrl = "http://gtfs.ovapi.nl/nl/vehiclePositions.pb",
        ),
        GtfsCityFeed(
            city = "Краків",
            anchorLat = 50.0738,
            anchorLon = 19.9683,
            radiusKm = 38.4,
            vehiclePositionsUrl = "https://gtfs.ztp.krakow.pl/VehiclePositions.pb",
        ),
        GtfsCityFeed(
            city = "Щецин (ZDiTM)",
            anchorLat = 53.4385,
            anchorLon = 14.5462,
            radiusKm = 26.9,
            vehiclePositionsUrl = "https://www.zditm.szczecin.pl/storage/gtfs/gtfs-rt-vehicles.pb",
        ),
        GtfsCityFeed(
            city = "Жешув (MPK)",
            anchorLat = 50.0133,
            anchorLon = 22.0162,
            radiusKm = 15.0,
            vehiclePositionsUrl = "https://www.mpkrzeszow.pl/gtfs/rt/gtfsrt.pb",
        ),
        GtfsCityFeed(
            city = "Жешувське воєводство (PKS)",
            anchorLat = 50.0133,
            anchorLon = 22.0162,
            radiusKm = 60.0,
            vehiclePositionsUrl = "https://www.mpkrzeszow.pl/gtfs-pks/rt/gtfsrt.pb",
        ),
        GtfsCityFeed(
            city = "Лісабонський регіон (Carris Metropolitana)",
            anchorLat = 38.7497,
            anchorLon = -8.9772,
            radiusKm = 68.3,
            vehiclePositionsUrl = "https://api.carrismetropolitana.pt/vehicles.pb",
        ),
        GtfsCityFeed(
            city = "Осло (Entur)",
            anchorLat = 59.9139,
            anchorLon = 10.7522,
            radiusKm = 220.0,
            vehiclePositionsUrl = "https://api.entur.io/realtime/v1/gtfs-rt/vehicle-positions",
        ),
        GtfsCityFeed(
            city = "Берген (Entur)",
            anchorLat = 60.3913,
            anchorLon = 5.3221,
            radiusKm = 220.0,
            vehiclePositionsUrl = "https://api.entur.io/realtime/v1/gtfs-rt/vehicle-positions",
        ),
        GtfsCityFeed(
            city = "Тронгейм (Entur)",
            anchorLat = 63.4305,
            anchorLon = 10.3951,
            radiusKm = 260.0,
            vehiclePositionsUrl = "https://api.entur.io/realtime/v1/gtfs-rt/vehicle-positions",
        ),
        GtfsCityFeed(
            city = "Тромсе (Entur, Арктика)",
            anchorLat = 69.6492,
            anchorLon = 18.9553,
            radiusKm = 320.0,
            vehiclePositionsUrl = "https://api.entur.io/realtime/v1/gtfs-rt/vehicle-positions",
        ),
    )

    /**
     * Опубліковані СТАТИЧНІ розклади міст Європи — брат-близнюк
     * [transitFeeds]: той каже, ДЕ ЗАРАЗ машина, цей — О КОТРІЙ вона
     * відправляється. 12 записів, кожен — пряме посилання НА ФАЙЛ з каталогу
     * MobilityData (authentication_type=0, без ключа), продзвонене curl
     * 14.09: HTTP 200, `Content-Type: application/zip` (чи octet-stream),
     * тіло розбирається як ZIP. Місто без запису тут просто не має доведеного
     * розкладу — [GtfsScheduleRegistry.feedFor] поверне null, а не вигадану
     * інтервальну модель під виглядом часів.
     */
    override val scheduleFeeds = listOf(
        GtfsScheduleFeed(
            city = "Прага (PID)",
            anchorLat = 49.9795,
            anchorLon = 14.4445,
            radiusKm = 154.7,
            staticUrl = "http://data.pid.cz/PID_GTFS.zip",
            attribution = "Prazska integrovana doprava (PID), data.pid.cz",
        ),
        GtfsScheduleFeed(
            city = "Гельсінкі (HSL)",
            anchorLat = 60.4819,
            anchorLon = 25.3615,
            radiusKm = 45.0,
            staticUrl = "http://dev.hsl.fi/gtfs/hsl.zip",
            attribution = "Helsingin seudun liikenne (HSL), dev.hsl.fi, CC-BY 4.0",
        ),
        GtfsScheduleFeed(
            city = "Краків",
            anchorLat = 50.0738,
            anchorLon = 19.9683,
            radiusKm = 38.4,
            staticUrl = "https://gtfs.ztp.krakow.pl/GTFS_KRK_T.zip",
            attribution = "Zarząd Transportu Publicznego w Krakowie (ZTP)",
        ),
        GtfsScheduleFeed(
            city = "Вільнюс",
            anchorLat = 54.7029,
            anchorLon = 25.2811,
            radiusKm = 23.7,
            staticUrl = "http://www.stops.lt/vilnius/vilnius/gtfs.zip",
            attribution = "SĮ «Susisiekimo paslaugos», stops.lt",
        ),
        GtfsScheduleFeed(
            city = "Щецин (ZDiTM)",
            anchorLat = 53.4385,
            anchorLon = 14.5462,
            radiusKm = 26.9,
            staticUrl = "https://www.zditm.szczecin.pl/storage/gtfs/gtfs.zip",
            attribution = "Zarząd Dróg i Transportu Miejskiego (ZDiTM) Szczecin",
        ),
        GtfsScheduleFeed(
            city = "Рим (Roma Mobilità)",
            anchorLat = 42.045,
            anchorLon = 12.4485,
            radiusKm = 55.0,
            staticUrl = "https://romamobilita.it/sites/default/files/rome_static_gtfs.zip",
            attribution = "Roma Servizi per la Mobilità",
        ),
        GtfsScheduleFeed(
            city = "Венеція (ACTV, судна)",
            anchorLat = 45.3594,
            anchorLon = 12.3529,
            radiusKm = 19.3,
            staticUrl = "http://actv.avmspa.it/sites/default/files/attachments/opendata/navigazione/actv_nav.zip",
            attribution = "Azienda del Consorzio Trasporti Veneziano (ACTV)",
        ),
        GtfsScheduleFeed(
            city = "Бордо (TBM)",
            anchorLat = 44.8412,
            anchorLon = -0.58,
            radiusKm = 20.0,
            staticUrl = "https://bdx.mecatran.com/utw/ws/gtfsfeed/static/bordeaux?apiKey=opendata-bordeaux-metropole-flux-gtfs-rt",
            attribution = "Transports Bordeaux Métropole (TBM), публічний відкритий ключ каталогу",
        ),
        GtfsScheduleFeed(
            city = "Софія",
            anchorLat = 42.6977,
            anchorLon = 23.3217,
            radiusKm = 25.0,
            staticUrl = "https://gtfs.sofiatraffic.bg/api/v1/static",
            attribution = "Urban Mobility Center — Sofia Traffic",
        ),
        GtfsScheduleFeed(
            city = "Малага",
            anchorLat = 36.7132,
            anchorLon = -4.4559,
            radiusKm = 15.0,
            staticUrl = "https://gtfs.emtmalaga.es/GTFS/google_transit.zip",
            attribution = "Empresa Malagueña de Transportes (EMT)",
        ),
        GtfsScheduleFeed(
            city = "Фуенлабрада (Мадрид)",
            anchorLat = 40.2825,
            anchorLon = -3.7923,
            radiusKm = 15.0,
            staticUrl = "https://api.control.optibus.co/opendata/v1/gtfs?uid=c-5cfcd2d1",
            attribution = "Empresa Municipal de Transportes de Fuenlabrada (EMTF)",
        ),
        GtfsScheduleFeed(
            city = "Elron (нацмережа заліз. Естонії)",
            anchorLat = 59.4372,
            anchorLon = 24.7573,
            radiusKm = 230.0,
            staticUrl = "https://eu-gtfs.remix.com/elron.zip",
            attribution = "Elron, конверсія Transitous/Remix",
        ),
    )
}
