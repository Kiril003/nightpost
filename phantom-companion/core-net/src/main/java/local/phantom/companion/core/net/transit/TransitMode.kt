package local.phantom.companion.core.net.transit

/**
 * Нормалізований вид міського транспорту.
 *
 * GTFS-поле `route_type` — не надійне джерело істини: одна й та сама лінія
 * буває `1` (metro) в одному місті та `0`/`900` (tram/light rail) в
 * іншому, а українські фіди масово позначають тролейбуси як звичайний
 * автобус (`3`). Тому назовні віддається саме цей перелік, а не сире
 * число: іконка, пріоритет у пошуку і час пересадки залежать від виду
 * транспорту, і помилка тут одразу видима користувачеві.
 */
enum class TransitMode {
    /** Метрополітен, підземка, U-Bahn. */
    METRO,

    /** Класичний трамвай у вуличному русі. */
    TRAM,

    /** Швидкісний трамвай / легкорейковий транспорт з відокремленим полотном. */
    LIGHT_RAIL,

    /** Приміська та магістральна залізниця, S-Bahn, міська електричка. */
    RAIL,

    /** Автобус будь-якої місткості. */
    BUS,

    /** Тролейбус. */
    TROLLEYBUS,

    /** Водний транспорт: пором, річковий трамвай. */
    FERRY,

    /** Наземна канатна дорога / кабельний трамвай. */
    CABLE_CAR,

    /** Фунікулер. */
    FUNICULAR,

    /** Підвісна канатна дорога, гондола. */
    AERIAL_LIFT,

    /** Монорейка. */
    MONORAIL,

    /** Маршрутне таксі, що працює за розкладом. */
    TAXI,

    /** Вид не визначено — ні числом, ні назвою. */
    UNKNOWN,
}

/**
 * Грубе групування видів транспорту. Потрібне там, де точний вид не
 * важливий, а важлива поведінка: рейковий транспорт має фіксовану трасу
 * і передбачуваний час, вуличний — ні.
 */
enum class TransitModeGroup {
    /** Міський рейковий: метро, трамвай, легкорейковий, монорейка. */
    URBAN_RAIL,

    /** Магістральна та приміська залізниця. */
    REGIONAL_RAIL,

    /** Вуличний транспорт: автобус, тролейбус, маршрутка. */
    ROAD,

    /** Водний. */
    WATER,

    /** Підйомники: фунікулер, канатні дороги. */
    LIFT,

    /** Невідомо. */
    UNKNOWN,
}

/**
 * Евристика виду транспорту.
 *
 * Правило узгодження одне: число з фіда має пріоритет, а назва
 * втручається лише там, де число заздалегідь відоме як неоднозначне.
 * Зворотний порядок ламається на першому ж автобусі з назвою
 * «Ст. м. Лісова — вул. Милославська»: слово «метро» в назві приміського
 * автобуса означає пункт призначення, а не вид транспорту.
 */
object TransitModeHeuristic {

    /**
     * Визначає вид транспорту за GTFS [routeType] та назвами маршруту.
     *
     * @param routeType GTFS `route_type` — базовий (0..12) або розширений
     *   (100..1799). `null` допустимий: тоді рішення приймається за назвами.
     * @param shortName коротка назва маршруту («9», «Тр 27», «M1»).
     * @param longName повна назва маршруту.
     * @param operatorName назва перевізника — остання підказка, коли назв
     *   маршруту немає взагалі.
     */
    fun classify(
        routeType: Int?,
        shortName: String? = null,
        longName: String? = null,
        operatorName: String? = null,
    ): TransitMode {
        val declared = fromRouteType(routeType)
        val haystack = listOfNotNull(shortName, longName, operatorName)
            .joinToString(" ")
            .lowercase()
        val hinted = fromName(haystack)

        if (declared == TransitMode.UNKNOWN) return hinted ?: TransitMode.UNKNOWN
        if (hinted == null) return declared

        return when {
            // Київпастранс і більшість українських фідів віддають тролейбус
            // як `3`/`700`. Назва тут — єдина ознака, що це не автобус.
            declared == TransitMode.BUS && hinted == TransitMode.TROLLEYBUS -> TransitMode.TROLLEYBUS

            // «Річковий трамвай» — це судно. Слово «трамвай» у назві збиває
            // і людину, і фід, тому водна підказка сильніша за оголошений
            // вуличний вид.
            hinted == TransitMode.FERRY &&
                (declared == TransitMode.TRAM || declared == TransitMode.BUS) -> TransitMode.FERRY

            // `0` покриває і трамвай, і легкорейковий — розрізняє тільки назва.
            declared == TransitMode.TRAM && hinted == TransitMode.LIGHT_RAIL -> TransitMode.LIGHT_RAIL

            // Той самий випадок навпаки: місто оголосило лінію легкорейковою,
            // але називає її метро (київський Сирецько-Печерський випадок).
            declared == TransitMode.LIGHT_RAIL && hinted == TransitMode.METRO -> TransitMode.METRO

            else -> declared
        }
    }

    /** Група, до якої належить вид транспорту. */
    fun groupOf(mode: TransitMode): TransitModeGroup = when (mode) {
        TransitMode.METRO, TransitMode.TRAM, TransitMode.LIGHT_RAIL, TransitMode.MONORAIL ->
            TransitModeGroup.URBAN_RAIL
        TransitMode.RAIL -> TransitModeGroup.REGIONAL_RAIL
        TransitMode.BUS, TransitMode.TROLLEYBUS, TransitMode.TAXI -> TransitModeGroup.ROAD
        TransitMode.FERRY -> TransitModeGroup.WATER
        TransitMode.CABLE_CAR, TransitMode.FUNICULAR, TransitMode.AERIAL_LIFT -> TransitModeGroup.LIFT
        TransitMode.UNKNOWN -> TransitModeGroup.UNKNOWN
    }

    /**
     * Чистий розбір GTFS `route_type`, без назв.
     *
     * Базові значення описані специфікацією GTFS; діапазон 100..1799 —
     * розширення Google, де значення має сенс сотні (900 — трамвай,
     * 800 — тролейбус тощо), а конкретне число уточнює підвид.
     */
    fun fromRouteType(routeType: Int?): TransitMode {
        val type = routeType ?: return TransitMode.UNKNOWN
        return when (type) {
            0 -> TransitMode.TRAM
            1 -> TransitMode.METRO
            2 -> TransitMode.RAIL
            3 -> TransitMode.BUS
            4 -> TransitMode.FERRY
            5 -> TransitMode.CABLE_CAR
            6 -> TransitMode.AERIAL_LIFT
            7 -> TransitMode.FUNICULAR
            11 -> TransitMode.TROLLEYBUS
            12 -> TransitMode.MONORAIL
            in 100..199 -> TransitMode.RAIL
            in 200..299 -> TransitMode.BUS
            in 300..399 -> TransitMode.RAIL
            // 400..404 — міська залізниця/метро, 405 — монорейка.
            405 -> TransitMode.MONORAIL
            in 400..499 -> TransitMode.METRO
            in 500..699 -> TransitMode.RAIL
            in 700..799 -> TransitMode.BUS
            in 800..899 -> TransitMode.TROLLEYBUS
            in 900..999 -> TransitMode.TRAM
            in 1000..1099 -> TransitMode.FERRY
            in 1200..1299 -> TransitMode.FERRY
            in 1300..1399 -> TransitMode.AERIAL_LIFT
            in 1400..1499 -> TransitMode.FUNICULAR
            in 1500..1599 -> TransitMode.TAXI
            else -> TransitMode.UNKNOWN
        }
    }

    /**
     * Підказка за назвою. Порядок перевірок значущий: водні й фунікулерні
     * ключі йдуть першими, бо їхні назви містять слова з інших видів
     * («річковий трамвай»).
     */
    private fun fromName(text: String): TransitMode? {
        if (text.isBlank()) return null
        return when {
            containsAny(text, FERRY_KEYS) -> TransitMode.FERRY
            containsAny(text, FUNICULAR_KEYS) -> TransitMode.FUNICULAR
            containsAny(text, TROLLEY_KEYS) -> TransitMode.TROLLEYBUS
            containsAny(text, LIGHT_RAIL_KEYS) -> TransitMode.LIGHT_RAIL
            containsAny(text, METRO_KEYS) -> TransitMode.METRO
            containsAny(text, TRAM_KEYS) -> TransitMode.TRAM
            containsAny(text, RAIL_KEYS) -> TransitMode.RAIL
            containsAny(text, TAXI_KEYS) -> TransitMode.TAXI
            containsAny(text, BUS_KEYS) -> TransitMode.BUS
            else -> null
        }
    }

    private fun containsAny(text: String, keys: Array<String>): Boolean =
        keys.any { text.contains(it) }

    private val FERRY_KEYS = arrayOf("річков", "паром", "пором", "ferry", "waterbus", "river bus", "судно")
    private val FUNICULAR_KEYS = arrayOf("фунікул", "funicul")
    private val TROLLEY_KEYS = arrayOf("тролейб", "тролей", "trolley", "тб-", "trolejb")
    private val LIGHT_RAIL_KEYS = arrayOf("швидкісний трамвай", "швидкісного трамва", "light rail", "lightrail", "stadtbahn")
    private val METRO_KEYS = arrayOf("метропол", "метро", "metro", "subway", "u-bahn", "підземка")
    private val TRAM_KEYS = arrayOf("трамва", "tram", "streetcar", "straßenbahn")
    private val RAIL_KEYS = arrayOf("електричк", "залізни", "s-bahn", "railway", "regional train", "приміськ")
    private val TAXI_KEYS = arrayOf("маршрутк", "marshrutka", "share taxi", "shared taxi")
    private val BUS_KEYS = arrayOf("автобус", "avtobus", "bus", "autobus")
}
