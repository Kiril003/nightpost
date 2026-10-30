package local.phantom.companion.core.truth

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

enum class Reading(val wire: String, val uk: String, val en: String) {
    OPEN("open", "відчинено", "open"),
    CLOSED("closed", "зачинено", "closed"),
    STEP_FREE("step_free", "без сходинок", "step-free"),
    HAS_STEPS("has_steps", "зі сходинками", "has steps"),
    WORKING("working", "працює", "working"),
    NOT_WORKING("not_working", "не працює", "not working"),
    ON("on", "є", "on"),
    OFF("off", "немає", "off"),
    QUEUE_NONE("q0", "без черги", "no queue"),
    QUEUE_SHORT("q15", "черга до 15 хв", "queue under 15 min"),
    QUEUE_MEDIUM("q45", "черга 15–45 хв", "queue 15–45 min"),
    QUEUE_LONG("q60", "черга понад 45 хв", "queue over 45 min"),
    PASSABLE("passable", "проїзд відкрито", "road open"),
    BLOCKED("blocked", "перекрито", "road closed"),
}

/**
 * Закритий словник. Півжиття — скільки часу минає, поки одне свідчення важить
 * удвічі менше; це не «строк придатності», а швидкість, з якою світ зазвичай
 * міняє цей стан.
 *
 * [remoteFactor] — вага свідчення без доказу присутності в комірці факту.
 * [embargoDuringAlert] — стан, чия раптова зміна під час тривоги видає місце
 * влучання (район без світла через хвилину після вибуху — це координата).
 */
enum class FactKind(
    val wire: String,
    val halfLifeMs: Long,
    val readings: List<Reading>,
    val positive: Reading?,
    val remoteFactor: Double,
    val importance: Double,
    val embargoDuringAlert: Boolean,
    val uk: String,
    val en: String,
) {
    // Режим відчинення укриття — домовленість із ключником і розклад, вона
    // міняється тижнями; 7 діб, а не 14, щоб одна зачинена знахідка все ж
    // переважила старий консенсус за тиждень, а не за місяць.
    SHELTER_OPEN(
        "shelter.open", 7 * DAY, listOf(Reading.OPEN, Reading.CLOSED), Reading.OPEN,
        0.3, 3.0, false, "Укриття", "Shelter",
    ),
    // Пандус — бетон, не розклад.
    SHELTER_STEP_FREE(
        "shelter.step_free", 90 * DAY, listOf(Reading.STEP_FREE, Reading.HAS_STEPS), Reading.STEP_FREE,
        0.5, 1.0, false, "Вхід в укриття", "Shelter entrance",
    ),
    // Пункти незламності вмикаються з відключеннями, а ті йдуть блоками по кілька годин.
    RESILIENCE_POINT(
        "point.resilience", 6 * HOUR, listOf(Reading.WORKING, Reading.NOT_WORKING), Reading.WORKING,
        0.3, 2.5, false, "Пункт незламності", "Resilience point",
    ),
    PHARMACY(
        "pharmacy.working", 3 * HOUR, listOf(Reading.WORKING, Reading.NOT_WORKING), Reading.WORKING,
        0.2, 1.5, false, "Аптека", "Pharmacy",
    ),
    SHOP(
        "shop.working", 3 * HOUR, listOf(Reading.WORKING, Reading.NOT_WORKING), Reading.WORKING,
        0.2, 1.0, false, "Магазин", "Shop",
    ),
    // Готівка в банкоматі закінчується за години, а сам він гасне разом зі світлом.
    ATM(
        "atm.working", 2 * HOUR, listOf(Reading.WORKING, Reading.NOT_WORKING), Reading.WORKING,
        0.2, 1.2, false, "Банкомат", "ATM",
    ),
    // Графіки відключень перемикають черги блоками 2–4 год: за годину звіт уже наполовину вгадування.
    POWER(
        "utility.power", 1 * HOUR, listOf(Reading.ON, Reading.OFF), Reading.ON,
        0.2, 2.0, true, "Світло", "Power",
    ),
    WATER(
        "utility.water", 6 * HOUR, listOf(Reading.ON, Reading.OFF), Reading.ON,
        0.2, 2.0, true, "Вода", "Water",
    ),
    // Акумулятори базових станцій тримають 2–4 год після зникнення світла.
    SIGNAL(
        "utility.signal", 2 * HOUR, listOf(Reading.ON, Reading.OFF), Reading.ON,
        0.0, 1.5, true, "Звʼязок", "Mobile signal",
    ),
    // Черга живе хвилинами; здалеку її не видно взагалі.
    QUEUE(
        "queue.wait", 30 * MINUTE,
        listOf(Reading.QUEUE_NONE, Reading.QUEUE_SHORT, Reading.QUEUE_MEDIUM, Reading.QUEUE_LONG), null,
        0.0, 1.0, false, "Черга", "Queue",
    ),
    ROAD(
        "road.passable", 4 * HOUR, listOf(Reading.PASSABLE, Reading.BLOCKED), Reading.PASSABLE,
        0.1, 2.0, true, "Дорога", "Road",
    ),
    ;

    val binary: Boolean get() = readings.size == 2

    fun opposite(r: Reading): Reading? = if (binary && r in readings) readings.first { it != r } else null

    companion object {
        /** Стан самої комірки, без обʼєкта: про них можна свідчити з довгого дотику. */
        val CELL: List<FactKind> get() = listOf(POWER, WATER, SIGNAL, ROAD)

        fun byWire(wire: String): FactKind? = entries.firstOrNull { it.wire == wire }
    }
}
