package local.phantom.companion.core.data.country

import local.phantom.companion.core.data.law.CurfewRule
import local.phantom.companion.core.data.transit.GtfsCityFeed
import local.phantom.companion.core.data.transit.GtfsScheduleAsset
import local.phantom.companion.core.data.transit.GtfsScheduleFeed
import local.phantom.companion.core.net.map.AdapterSchema

/** Осьова рамка пака у WGS84 — перевірка належності мусить коштувати нуль. */
data class PackBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon
}

/** Куди дивиться мапа, коли фікса ще нема. */
data class MapHome(val lat: Double, val lon: Double, val zoom: Double)

/**
 * Нульовий меридіан, глобус. Не «десь у Гвінейській затоці»: на цьому зумі
 * видно планету, і це чесна відповідь на «я не знаю, де ти».
 */
val WORLD_VIEW = MapHome(lat = 0.0, lon = 0.0, zoom = 1.5)

/**
 * Одна оголошена стрічка тривог.
 *
 * [schema] не-null — універсальний адаптер їсть цю стрічку цілком, писати
 * клієнта не треба: друга країна додається рядком даних. Null — форма дроту
 * потребує коду (типізовані загрози, bearer-токен): пак усе одно володіє
 * тим, ДЕ і ЧИЄ, а читач — лише тим, ЯК.
 */
data class AlarmFeed(
    val role: String,
    val endpoint: String,
    val userAgent: String,
    val schema: AdapterSchema? = null,
    /** Ключ у сховищі; нема значення — стрічка мовчить, а не шле порожній токен. */
    val tokenKey: String? = null,
) {
    /** Хост без схеми й шляху — так стрічку називають оператору в «Джерелах». */
    val host: String get() = endpoint.substringAfter("://").substringBefore("/")
}

/** Ролі стрічок, на які дивляться споживачі. Пак може не оголосити жодної. */
object AlarmFeedRole {
    /** Хто зараз під тривогою — авторитет активного набору. */
    const val REGION_STATE = "region-state"

    /** Типізована загроза й громади, без ключа. */
    const val TYPED = "typed"

    /** Уточнення за токеном. */
    const val DETAIL = "detail"
}

/**
 * Країна як плагін: дані плюс оголошені стрічки, а не гілка в коді.
 *
 * Кожен споживач питає реєстр пакiв, ніколи не клієнта країни. Нема пака —
 * нема тривог, нема комендантської, нема домашнього центру: типізована
 * відсутність, ніколи не порожній список у ролі «тут нічого нема».
 */
interface CountryPack {
    /** ISO-3166-1 alpha-2. Той самий ключ, що й `CurfewRule.regionId`. */
    val id: String

    val bounds: PackBounds

    val home: MapHome

    /** Шлях до полігонів регіонів в асетах застосунку, або null. */
    val regionGeometryAsset: String?

    /** Дерево id регіонів стрічки тривог в асетах, або null. */
    val regionIndexAsset: String?

    /**
     * Офлайнова підлога пінів: витяг POI країни. Нема — мапа малює те, що
     * прийшло живим Overpass, і нічого понад те.
     */
    val poiAsset: String? get() = null

    /**
     * Офлайновий довідник назв вулиць, без номерів будинків. Рядки цього
     * асета сіються в ТОЙ САМИЙ газетир, що й [poiAsset] ([PoiGazetteerEntity]
     * з `category = "street"`) — окремої таблиці й окремого пошуку нема.
     * Нема асета — газетир знає лише вулиці, які бачив живий Overpass.
     */
    val streetsAsset: String? get() = null

    /**
     * НАЦІОНАЛЬНІ МІСЦЯ — вода, пальне, ліки, пошта, туалети, броди по ВСІЙ
     * країні, а не по столиці.
     *
     * Та сама форма, що й [streetsAsset], і з тієї самої причини: рядок на
     * місце, потоком у той самий газетир, нічого не лишається в памʼяті.
     * [poiAsset] тримає свій вміст живими обʼєктами довіку — правильно на
     * 21 тисячі рядків міста і неправильно на 108 тисячах країни.
     *
     * Нема асета — довідник знає лише те, що бачив живий Overpass, і поза
     * містом це нуль.
     */
    val placesAsset: String? get() = null

    /**
     * НАСЕЛЕНІ ПУНКТИ — міста, селища, села, хутори.
     *
     * Та сама форма, що й [placesAsset], тим самим потоком, у той самий
     * газетир: `cat = "place"`, `sub` — рівень (`village`, `town`, `city`).
     *
     * Існує тому, що [placesAsset] попри назву не несе ЖОДНОГО поселення —
     * виміряно 12.09: у корпусі телефона `category='place'` дає нуль рядків,
     * там POI. Єдиним джерелом назв міст був `PlaceTable.kt` на 314 рядків,
     * вписаних рукою, тож назву села в Україні не можна було знайти за
     * побудовою — і жодне ранжування цього не лікує.
     *
     * Нема асета — лишаються ті 314 і рівно та сама вада.
     */
    val settlementsAsset: String? get() = null

    /**
     * Змодельована мережа міста (метро / наземний транспорт). Нема — ярус
     * `SIMULATED_MODEL` не існує тут узагалі, і цикл флоту не крутиться.
     */
    val metroAsset: String? get() = null

    val surfaceAsset: String? get() = null

    /**
     * Офіційний витяг аптек і медзакладів (НСЗУ) — та сама форма [poiAsset],
     * інше джерело: реєстр, не Overpass. Нема — немає офіційної підлоги
     * понад те, що вже принесла мапа.
     */
    val officialFacilitiesAsset: String? get() = null

    /**
     * Пункти незламності — та сама форма [poiAsset], але покриття НЕ
     * національне: держава публікує реєстр не для кожної області. Чесна
     * межа — [invincibilityCoveredRegions], не мовчазний нуль на мапі.
     */
    val invincibilityAsset: String? get() = null

    /**
     * Векторний індекс потреб: змістовий пошук «нема світла» → пункт
     * незламності. Спечений з [officialFacilitiesAsset] та
     * [invincibilityAsset], тож без них не існує. Нема асета — країна має
     * пошук за назвою і не має пошуку за потребою; це відсутність фічі, не збій.
     */
    val needsIndexAsset: String? get() = null

    /**
     * Області, для яких [invincibilityAsset] реально щось несе. Порожньо —
     * пак не заявляє покриття взагалі. Імена мусять дослівно збігатись зі
     * значенням `name` у [regionGeometryAsset], бо саме з ним їх звіряють.
     */
    val invincibilityCoveredRegions: Set<String> get() = emptySet()

    /** Ліцензії на дані пака — вхід аудиту перед установленням. */
    val sources: List<PackSource> get() = emptyList()

    /** Збірка чи каталог завантаженого пака. Читає [readAsset]. */
    val assetSource: PackAssetSource get() = PackAssetSource.Bundled

    val alarmFeeds: List<AlarmFeed>

    val curfewRules: List<CurfewRule>

    val transitFeeds: List<GtfsCityFeed>

    /**
     * Опубліковані розклади міст пака — ДВА незалежні шляхи доставки, обидва
     * справжні. [scheduleAssets] — GTFS, спечений у пак скриптом
     * `scripts/fetch_gtfs_schedule.py` і вбудований в APK (Київ так їде).
     * [scheduleFeeds] — той самий GTFS static, але фетчений прямо з
     * джерела за URL (data.gov.ua) без бекінгу в пак. Обидва окремі від
     * [transitFeeds]: той перелік про «де зараз машина», ці — про «о
     * котрій вона їде». Порожньо в обох — місто не має доведеного
     * розкладу, і досьє каже саме це, а не показує інтервальну модель
     * під виглядом часів.
     */
    val scheduleAssets: List<GtfsScheduleAsset> get() = emptyList()

    val scheduleFeeds: List<GtfsScheduleFeed> get() = emptyList()

    /**
     * Регіони, чия присутність у даних більше не свідчить про сьогодні
     * (окупація, замерзлий шар). Реєстр «памʼятаю», не «бачу».
     */
    val rememberedRegions: Set<String>

    fun alarmFeed(role: String): AlarmFeed? = alarmFeeds.firstOrNull { it.role == role }
}
