package local.phantom.companion.feature.stream.ui.map.icons

import local.phantom.companion.core.data.poi.PlaceTier
import local.phantom.companion.core.data.poi.PlaceTiers
import local.phantom.companion.core.data.poi.PoiEntity

/**
 * Ярус піна — те, ЩО лишається на склі, коли місця на всіх нема.
 *
 * Псевдонім, а не свій список, і це виправлення. Ярус визначає і колір кільця,
 * і чергу на розрідження — але РІЖЕ не скло: ріже стеля кадру `PoiDensity`, на
 * крок раніше. Поки «критичність» була оголошена тут, у неї стояв другий,
 * незалежний примірник там, і вони розійшлись рівно так, як і мали: скло знало
 * джерело, криницю й дефібрилятор критичними, диск не знав цих родів зовсім і
 * викидав їх першими. Тепер колонка ярусу одна — [PlaceTiers] у `core-data`, —
 * а тут лишилась своя: знак.
 */
internal typealias PinTier = PlaceTier

internal data class PinFamily(val glyph: PhantomGlyph, val tier: PinTier)

/**
 * Тег OSM → родина знаків. Явна таблиця без жодного «інакше візьмемо емодзі»:
 * хвіст таксономії дістає [DEFAULT] — ромб «просто місце», а не чужий шрифт.
 *
 * Ключі — це те, що [PoiRepository] реально кладе в `subcategory`: значення
 * тега, який дав категорію (`amenity=fuel` → «fuel»). Ключа, якого тут нема,
 * не існує помилки: він просто йде на категорію, а потім на [DEFAULT].
 */
internal object PoiGlyphTable {

    val DEFAULT = PinFamily(PhantomGlyph.PLACE, PinTier.ORDINARY)

    /**
     * Знак — одна колонка, ярус — жодної: його дає [PlaceTiers] по тому самому
     * ключу. Рядок, про який [PlaceTiers] нічого не знає, лишається звичайним —
     * рівно те, що робив [DEFAULT], тільки тепер це видно.
     */
    private val glyphsBySubcategory: Map<String, PhantomGlyph> = mapOf(
        // Життя і здоровʼя
        "shelter" to PhantomGlyph.SHELTER,
        "hospital" to PhantomGlyph.HOSPITAL,
        "clinic" to PhantomGlyph.HOSPITAL,
        "doctors" to PhantomGlyph.PULSE,
        "ambulance_station" to PhantomGlyph.PULSE,
        "defibrillator" to PhantomGlyph.HEART,
        "pharmacy" to PhantomGlyph.PHARMACY,
        "dentist" to PhantomGlyph.TOOTH,
        "veterinary" to PhantomGlyph.PAW,
        "police" to PhantomGlyph.POLICE,
        "fire_station" to PhantomGlyph.FIRE,
        "drinking_water" to PhantomGlyph.WATER,
        "water_well" to PhantomGlyph.WATER,
        "spring" to PhantomGlyph.WATER,
        // Пункт незламності — тепло, світло і звʼязок у блекаут.
        "community_centre" to PhantomGlyph.CIVIC,

        // Рух і живлення
        "fuel" to PhantomGlyph.FUEL,
        "charging_station" to PhantomGlyph.EV,
        "generator" to PhantomGlyph.POWER,
        "substation" to PhantomGlyph.POWER,
        "transformer" to PhantomGlyph.POWER,
        "car_repair" to PhantomGlyph.REPAIR,
        "tyres" to PhantomGlyph.TYRE,
        "bus_station" to PhantomGlyph.BUS,
        "bus_stop" to PhantomGlyph.STOP,
        "platform" to PhantomGlyph.STOP,
        "tram_stop" to PhantomGlyph.TRAM,
        "station" to PhantomGlyph.TRAIN,
        "halt" to PhantomGlyph.TRAIN,
        "subway_entrance" to PhantomGlyph.METRO,
        "subway" to PhantomGlyph.METRO,
        "ferry_terminal" to PhantomGlyph.BOAT,
        "taxi" to PhantomGlyph.TAXI,
        "atm" to PhantomGlyph.CARD,
        "bank" to PhantomGlyph.CARD,
        "post_office" to PhantomGlyph.POST,
        "parcel_locker" to PhantomGlyph.PARCEL,
        "toilets" to PhantomGlyph.PERSON,

        // Побут
        "parking" to PhantomGlyph.PARKING,
        "car_wash" to PhantomGlyph.CAR,
        "toll_booth" to PhantomGlyph.CARD,
        "bureau_de_change" to PhantomGlyph.CARD,
        "supermarket" to PhantomGlyph.CART,
        "convenience" to PhantomGlyph.SHOP,
        "mall" to PhantomGlyph.SHOP,
        "marketplace" to PhantomGlyph.SHOP,
        "hardware" to PhantomGlyph.SHOP,
        "doityourself" to PhantomGlyph.SHOP,
        "bakery" to PhantomGlyph.BREAD,
        "cafe" to PhantomGlyph.CAFE,
        "restaurant" to PhantomGlyph.FOOD,
        "fast_food" to PhantomGlyph.FOOD,
        "bar" to PhantomGlyph.BAR,
        "pub" to PhantomGlyph.BAR,
        "hotel" to PhantomGlyph.BED,
        "hostel" to PhantomGlyph.BED,
        "guest_house" to PhantomGlyph.BED,
        "internet_cafe" to PhantomGlyph.WIFI,

        // Держава й культура
        "townhall" to PhantomGlyph.CIVIC,
        "courthouse" to PhantomGlyph.CIVIC,
        "government" to PhantomGlyph.CIVIC,
        "museum" to PhantomGlyph.CIVIC,
        "library" to PhantomGlyph.BOOK,
        // PoiVocabulary уже знає "books" як окреме ключове слово — до цього
        // рядка книгарня падала на родовий SHOP разом із перукарнею й АЗС.
        "books" to PhantomGlyph.BOOK,
        "school" to PhantomGlyph.SCHOOL,
        "college" to PhantomGlyph.SCHOOL,
        "university" to PhantomGlyph.SCHOOL,
        "kindergarten" to PhantomGlyph.SCHOOL,
        "place_of_worship" to PhantomGlyph.CHURCH,
        "works" to PhantomGlyph.FACTORY,

        // Природа й огляд
        "park" to PhantomGlyph.TREE,
        "garden" to PhantomGlyph.TREE,
        "playground" to PhantomGlyph.TREE,
        "peak" to PhantomGlyph.MOUNTAIN,
        "viewpoint" to PhantomGlyph.VIEW,
        "attraction" to PhantomGlyph.VIEW,
        "monument" to PhantomGlyph.VIEW,
        "memorial" to PhantomGlyph.VIEW,
        "castle" to PhantomGlyph.VIEW,
        "ruins" to PhantomGlyph.VIEW,
        "gallery" to PhantomGlyph.VIEW,
        "zoo" to PhantomGlyph.TREE,

        // Перешкоди й проходи
        "barrier" to PhantomGlyph.BARRIER,
        "gate" to PhantomGlyph.BARRIER,
        "lift_gate" to PhantomGlyph.BARRIER,
        "bollard" to PhantomGlyph.BARRIER,
        "checkpoint" to PhantomGlyph.CHECKPOINT,
        "speed_camera" to PhantomGlyph.CAMERA,
        "entrance" to PhantomGlyph.DOOR,
        "building_passage" to PhantomGlyph.DOOR,
        "elevator" to PhantomGlyph.ELEVATOR,
        // Ескалатор носить сходи: на 14dp окремий знак для нього перетворився
        // б на ті самі сходи з шумом, а підпис і так поруч.
        "escalator" to PhantomGlyph.STAIRS,
        "steps" to PhantomGlyph.STAIRS,
        "stairs" to PhantomGlyph.STAIRS,
        "ramp" to PhantomGlyph.STAIRS,
    )

    /** Ключ тега, який дав категорію — остання зупинка перед [DEFAULT]. */
    private val glyphsByCategory: Map<String, PhantomGlyph> = mapOf(
        "amenity" to PhantomGlyph.PLACE,
        "shop" to PhantomGlyph.SHOP,
        "tourism" to PhantomGlyph.VIEW,
        // `historic` тут стояв від початку — і не спрацьовував ЖОДНОГО разу:
        // [PoiRepository] цієї категорії не робив, а Overpass-запит про
        // памʼятки не питав узагалі. Стіл був накритий для гостя, якого не
        // покликали. Обидві ланки дороблено 15.09.
        "historic" to PhantomGlyph.VIEW,
        // Вежа, маяк, вітряк — рукотворний орієнтир, а не «місце».
        "man_made" to PhantomGlyph.FACTORY,
        "leisure" to PhantomGlyph.TREE,
        "office" to PhantomGlyph.CIVIC,
        "railway" to PhantomGlyph.TRAIN,
        "highway" to PhantomGlyph.STOP,
        "public_transport" to PhantomGlyph.STOP,
        "barrier" to PhantomGlyph.BARRIER,
        "entrance" to PhantomGlyph.DOOR,
        "building" to PhantomGlyph.DOOR,
        "bridge" to PhantomGlyph.BRIDGE,
        "other" to PhantomGlyph.PLACE,
    )

    /** Знак свій, ярус позичений — і саме тому вони не можуть розійтись. */
    val bySubcategory: Map<String, PinFamily> =
        glyphsBySubcategory.mapValues { (key, glyph) -> PinFamily(glyph, PlaceTiers.of(null, key)) }

    val byCategory: Map<String, PinFamily> =
        glyphsByCategory.mapValues { (key, glyph) -> PinFamily(glyph, PlaceTiers.of(key, null)) }

    fun resolve(category: String?, subcategory: String?): PinFamily {
        subcategory?.takeIf { it.isNotBlank() }?.let { bySubcategory[it]?.let { f -> return f } }
        category?.takeIf { it.isNotBlank() }?.let { byCategory[it]?.let { f -> return f } }
        return DEFAULT
    }

    /**
     * Підкатегорія, з якою пін іде на скло. Таблиця тегів не бачить, а
     * `amenity=shelter` в OSM — це НАВІС: під сиреною тридцять дві альтанки
     * малювались щитом найвищого рангу. Порожня підкатегорія роняє їх на
     * категорію `amenity` — «просто місце», як і має бути.
     *
     * Саме правило живе в [PlaceTiers.pinKey]: ним же рахується РІД для стелі
     * кадру, і навіс, що падає на «просто місце» для ока, мусить падати туди ж
     * у черзі на виживання.
     */
    fun pinSubcategory(poi: PoiEntity): String? = PlaceTiers.pinKey(poi)

    /** [resolve], але з тегами місця — єдина форма, якій можна вірити під сиреною. */
    fun resolve(poi: PoiEntity): PinFamily = resolve(poi.category, pinSubcategory(poi))

    /** Ключі, що переживають розрідження першими — виводяться з таблиці. */
    val criticalKeys: Set<String> = keysOf(PinTier.CRITICAL)
    val sustainKeys: Set<String> = keysOf(PinTier.SUSTAIN)

    private fun keysOf(tier: PinTier): Set<String> =
        (bySubcategory + byCategory).filterValues { it.tier == tier }.keys
}
