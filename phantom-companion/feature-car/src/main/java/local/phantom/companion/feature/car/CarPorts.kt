package local.phantom.companion.feature.car

/**
 * HIGHWAY — everything the car head needs from the rest of PHANTOM, and
 * nothing else.
 *
 * The car process is started by the Android Auto host, on the host's schedule,
 * with no Activity alive and often with the phone screen off. It therefore
 * cannot reach into a ViewModel, a composition, or anything else that assumes
 * a foreground app. This interface is the whole of its contact with the world;
 * `:app` fills it in from the container that already outlives every screen.
 *
 * Keeping it this narrow is also what makes the car head testable at all: the
 * screens below are exercised against a fake of this and nothing more, with no
 * car, no host and no emulator in the loop.
 */
interface CarPorts {

    /** Where the operator is, or null when PHANTOM has no fix he trusts. */
    suspend fun currentLocation(): CarFix?

    /**
     * Places PHANTOM already knows about, searched with NO network.
     *
     * Backed by the offline gazetteer. This is not a degraded mode for the car
     * — it is the primary one: the moment a driver actually needs fuel is
     * disproportionately the moment he is between towns with one bar of signal.
     *
     * [query] is raw operator text (voice or the car's keyboard); sanitising is
     * the implementation's problem, not the screen's.
     */
    suspend fun search(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        subcategories: List<String>,
        limit: Int,
    ): List<CarPlace>

    /**
     * Hand a destination to the drive.
     *
     * Deliberately not "start guidance": the car head does not own the route,
     * and must not become a second navigator that can disagree with the phone
     * about where the operator is going. It states an intent; whoever owns the
     * drive decides what happens.
     */
    suspend fun navigateTo(place: CarPlace)

    /**
     * End the drive. Same reasoning as [navigateTo]: the car head states an
     * intent and the owner of the drive acts on it. The host itself asks for
     * this too — it may hand guidance to another app — and «stop» that only
     * closes a screen leaves the phone still navigating.
     */
    suspend fun stopDrive()

    /**
     * Скільки місць знає пристрій. Потрібне рівно для одного речення на екрані
     * машини — і саме воно було вимкнене літералом `1` у двох місцях
     * `CarScreens`, через що водій НІКОЛИ не бачив чесного «я ще не бачив цих
     * місць», лише «поруч такого немає». Порожній довідник і відсутність
     * потрібного місця — різні світи, і лікуються вони протилежним.
     */
    suspend fun placesKnown(): Int
}

/** A position, with no Android type attached. */
data class CarFix(val lat: Double, val lon: Double)

/** One place, as a car screen needs it: a name, a distance, a destination. */
data class CarPlace(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double,
    val subtitle: String? = null,
)

/**
 * The car head's category chips.
 *
 * A driver does not type. Each of these is one tap that becomes a category
 * browse over the gazetteer, and the OSM subcategory values are the actual
 * strings stored on `poi_gazetteer.subcategory` — get these wrong and the chip
 * silently returns nothing, which on a car screen looks exactly like "there is
 * no fuel here".
 */
enum class CarCategory(
    val label: String,
    val subcategories: List<String>,
    val glyph: CarGlyph,
) {
    FUEL("АЗС", listOf("fuel", "charging_station"), CarGlyph.FUEL),
    FOOD("Їжа", listOf("cafe", "restaurant", "fast_food"), CarGlyph.FOOD),
    // ЧИП «ПАРКІНГ» ЗНЯТО 31 серпня 2026, і це слід, а не забуття.
    //
    // Заміряно на асеті, що їде в застосунку: 25 паркінгів на весь Київ,
    // найближчий до дому власника за 3,79 км. Сусідні чипи стоять на сотнях
    // і тисячах: АЗС 393, аптек 1573, кафе 1981. Чип, який здебільшого
    // віддає «поруч такого немає», — вічна обіцянка: водій вчиться, що
    // кнопка не працює, і перестає натискати й ті, що працюють.
    //
    // Повернеться тим самим комітом, яким приїдуть дані. Нічого не
    // вигадано на його місце: три чипи чесніші за чотири, з яких один
    // порожній.
    // PARKING("Паркінг", listOf("parking"), CarGlyph.PARKING),
    PHARMACY("Аптека", listOf("pharmacy"), CarGlyph.PHARMACY),
    ;

    companion object {
        /** How far out a chip looks. Generous: between towns, the nearest fuel
         *  is routinely 20 km away, and "nothing found" is a worse answer than
         *  "20 km ahead". */
        const val RADIUS_METERS = 40_000
    }
}

/**
 * The shape a driver recognises before he reads the label — a grid of identical
 * pins makes him read all four, which is what a GridTemplate exists to avoid.
 *
 * A named glyph rather than a drawable id because a library module's unit tests
 * compile against a stub R where every id is 0: with resource ids the test that
 * matters — that no two categories look alike — would pass on four zeroes.
 */
enum class CarGlyph { FUEL, FOOD, PARKING, PHARMACY }
