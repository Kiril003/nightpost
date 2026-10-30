package local.phantom.companion.feature.stream.ui.map.icons

/**
 * Реєстр родини. Кожен знак мапи — запис звідси, і жодного іншого джерела
 * гліфів у мапи нема: ні системного емодзі, ні шрифту, ні drawable.
 *
 * Імʼя запису — це РОДИНА, не тег OSM: `card` носять і банкомат, і банк, і
 * пункт оплати, бо для водія це одна відповідь. Хто в яку родину потрапляє —
 * [PoiGlyphTable], і саме там ця відповідність перевіряється тестом.
 */
internal enum class PhantomGlyph(val draw: GlyphPen.() -> Unit) {
    // Безпека, держава, інфраструктура
    SHELTER(GlyphPen::shelter),
    POLICE(GlyphPen::police),
    CHECKPOINT(GlyphPen::checkpoint),
    BARRIER(GlyphPen::barrier),
    DANGER(GlyphPen::danger),
    ACCIDENT(GlyphPen::accident),
    CAMERA(GlyphPen::camera),
    BUMP(GlyphPen::bump),
    FIRE(GlyphPen::fire),
    CIVIC(GlyphPen::civic),
    CARD(GlyphPen::card),
    POST(GlyphPen::post),
    PARCEL(GlyphPen::parcel),
    BOOK(GlyphPen::book),
    SCHOOL(GlyphPen::school),
    CHURCH(GlyphPen::church),
    POWER(GlyphPen::power),
    WIFI(GlyphPen::wifi),
    FACTORY(GlyphPen::factory),

    // Медицина
    PHARMACY(GlyphPen::pharmacy),
    HOSPITAL(GlyphPen::hospital),
    PULSE(GlyphPen::pulse),
    HEART(GlyphPen::heart),
    TOOTH(GlyphPen::tooth),
    PAW(GlyphPen::paw),

    // Рух
    FUEL(GlyphPen::fuel),
    EV(GlyphPen::ev),
    PARKING(GlyphPen::parking),
    REPAIR(GlyphPen::repair),
    TYRE(GlyphPen::tyre),
    CAR(GlyphPen::car),
    TAXI(GlyphPen::taxi),
    BUS(GlyphPen::bus),
    TRAM(GlyphPen::tram),
    METRO(GlyphPen::metro),
    TRAIN(GlyphPen::train),
    STOP(GlyphPen::stop),
    BOAT(GlyphPen::boat),

    // Місця
    FOOD(GlyphPen::food),
    CAFE(GlyphPen::cafe),
    BAR(GlyphPen::bar),
    BREAD(GlyphPen::bread),
    CART(GlyphPen::cart),
    SHOP(GlyphPen::shop),
    BED(GlyphPen::bed),
    TREE(GlyphPen::tree),
    MOUNTAIN(GlyphPen::mountain),
    BRIDGE(GlyphPen::bridge),
    VIEW(GlyphPen::view),
    WATER(GlyphPen::water),
    PERSON(GlyphPen::person),
    HOME(GlyphPen::home),
    WORK(GlyphPen::work),
    STAR(GlyphPen::star),
    PLACE(GlyphPen::place),
    DOOR(GlyphPen::door),
    EXIT(GlyphPen::exit),
    STAIRS(GlyphPen::stairs),
    ELEVATOR(GlyphPen::elevator),

    // Загрози
    DRONE(GlyphPen::drone),
    MISSILE(GlyphPen::missile),
    JET(GlyphPen::jet),
    BOMB(GlyphPen::bomb),
    BURST(GlyphPen::burst),
    BALLISTIC(GlyphPen::ballistic),
    SWORDS(GlyphPen::swords),
    CHEMICAL(GlyphPen::chemical),
    NUCLEAR(GlyphPen::nuclear),

    // Керування мапою. Не місця й не події — те, ЧИМ людина мапу гортає.
    // До 30.08 ця частина реєстру не існувала, і дека носила стокові
    // material.icons: 131 імпорт на поверхні, 0 власних у контролях. Родину
    // заводили саме проти різнорукості, і на даних вона перемогла — а
    // на кнопках лишалась чужа рука. Див. GlyphsControls.kt.
    LAYERS(GlyphPen::layers),
    GROUND(GlyphPen::ground),
    PACKS(GlyphPen::packs),
    COMPASS(GlyphPen::compass),
    BEARING(GlyphPen::bearing),
    LOCATE_LOCKED(GlyphPen::locateLocked),
    LOCATE_SEEKING(GlyphPen::locateSeeking),
    LOCATE_BLIND(GlyphPen::locateBlind),
    COLLAPSE(GlyphPen::collapse),

    // Аркуші мапи. Не «яка мапа», а «що я роблю з ЦІЄЮ точкою»: картка тапнутого
    // місця, дії довгого натиску, картка збереженого місця, смуга маршруту.
    // Друга за гучністю поверхня після деки — і до 31.08 вся чужою рукою:
    // SheetHost.kt ніс 17 стокових імпортів, MapCommandSheet.kt — 10, тобто 27
    // із 123, що лишались на всій мапі. Див. GlyphsSheets.kt.
    //
    // Ще шість значків тих файлів нового малюнка не потребували: патруль уже мав
    // POLICE, блокпост — CHECKPOINT, ДТП — ACCIDENT, пальне — FUEL, авто — CAR,
    // автобус — BUS. Родина мала їх на даних; на кнопки їх просто не кликали.
    ROUTE_TO(GlyphPen::routeTo),
    ROUTE_FROM(GlyphPen::routeFrom),
    VIA(GlyphPen::via),
    GUIDE(GlyphPen::guide),
    FLYOVER(GlyphPen::flyover),
    FOOT(GlyphPen::foot),
    BIKE(GlyphPen::bike),
    WHAT_HERE(GlyphPen::whatHere),
    ASK_HERE(GlyphPen::askHere),
    HANDOFF(GlyphPen::handoff),
    HANDOUT(GlyphPen::handout),
    REACH(GlyphPen::reach),
    DANGER_FIELD(GlyphPen::dangerField),
    KEEP(GlyphPen::keep),
    RENAME(GlyphPen::rename),
    DISCARD(GlyphPen::discard),
    CONFIRM(GlyphPen::confirm),
    DISMISS(GlyphPen::dismiss),
    ;

    fun drawOn(pen: GlyphPen) = pen.draw()
}
