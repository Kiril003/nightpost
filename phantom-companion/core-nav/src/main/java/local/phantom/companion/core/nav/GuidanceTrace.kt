package local.phantom.companion.core.nav

/**
 * Слід наведення — один рядок на кожен фікс, який побачила сесія супроводу.
 *
 * Реєстратор конвеєра (`core-sensor/nav/BlackBox`) уже пише сире небо, тіло,
 * нутрощі фільтра й вирок про довіру — і не бачить НІЧОГО з маршруту: ні
 * зшивання на дорогу, ні бічного зсуву, ні серії сходу, ні рішення
 * перебудувати. Через цю дірку скарги власника «притягує на ту дорогу, де
 * маршрут» і «каже "поверніться" і показує, що я їду в той бік» неможливо ні
 * відтворити, ні спростувати: позиція в записі є, маршруту немає.
 *
 * Тому це не новий записувач, а один шов у той, що вже літає. `core-nav` не
 * бачить `core-sensor` і не повинен, тож сюди їдуть лише прості числа, а хто
 * і куди їх запише — вирішує застосунок.
 *
 * Контракт: виклик стоїть на шляху КОЖНОГО фікса, тож мусить бути дешевим і не
 * кидати. Реєстратор за ним — черга з витісненням, не файл під замком.
 *
 * ЧОГО ЦЕЙ ПРИЛАД НЕ БАЧИТЬ ЗА ПОБУДОВОЮ — читай перед тим, як робити з нього
 * висновки:
 *  1. Він мовчить поза супроводом. Рядок пишеться лише коли сесія веде
 *     маршрутом; вільна їзда без маршруту не лишає жодного nav-рядка.
 *  2. Він знає про фікси, а не про час між ними. Усе, що сталось між двома
 *     фіксами, у слід не потрапляє.
 *  3. Він пише ЗАМОВЛЕННЯ перебудови, а не її наслідок. Скільки тривала
 *     відповідь і хто відповів — окремий рядок `route` реєстратора.
 *  4. Він не бачить малювання. Стрілку малює мапа з власним згладжуванням, і
 *     блимання може бути там, де в цих числах усе рівно.
 *  5. Кома в назві дороги стає крапкою з комою (реєстратор боронить колонки),
 *     тож текст у записі не завжди дослівний.
 */
fun interface GuidanceTrace {
    fun onFix(line: GuidanceTraceLine)
}

/**
 * Що варто знати про одну секунду супроводу, щоб потім судити її без дороги.
 *
 * Два напрямки лежать поруч навмисно. [routeBearingDeg] — напрямок ДОРОГИ, на
 * яку зшили точку; [motionBearingDeg] — напрямок, у якому пристрій справді
 * рухався (null, коли невідомий: стоїмо, або фікс його не несе). Сьогодні і
 * банер, і стрілка беруть перший — `GuidanceProgress.bearingDeg = snap.bearingDeg`.
 * Розбіжність між цими двома числами і є скаргою №5, і поки вони не лежать в
 * одному рядку, її нема чим ані довести, ані спростувати.
 *
 * [offMeters] і [offStreak] — так само: зсув каже, НАСКІЛЬКИ ми збоку, серія —
 * скільки фіксів поспіль це триває. Перебудова вирішується серією, тож без неї
 * «чому воно не перебудувало» лишається здогадом.
 */
data class GuidanceTraceLine(
    /** Де пристрій за фіксом, до зшивання. */
    val lat: Double,
    val lon: Double,
    /** Куди його поставив зшивач — те, що бачить людина. */
    val snapLat: Double,
    val snapLon: Double,
    /** Бічний зсув від маршруту, метри. */
    val offMeters: Double,
    /** Поріг уже застосовано: чи вважаємось поза маршрутом ЦИМ фіксом. */
    val offRoute: Boolean,
    /** Скільки фіксів поспіль ми поза маршрутом і справді рухаємось. */
    val offStreak: Int,
    /** Сесія попросила перебудову саме на цьому фіксі. */
    val requestReroute: Boolean,
    /** Напрямок дороги, на яку зшили. Його показує стрілка. */
    val routeBearingDeg: Double,
    /** Напрямок реального руху; null — невідомий. */
    val motionBearingDeg: Float?,
    val speedMps: Float?,
    /** Чи має цей фікс право свідчити (див. NavFix.trusted). */
    val trusted: Boolean,
    val accuracyM: Float?,
    /** Спосіб руху, як його визначили: пішки/авто/невідомо. */
    val gait: String,
    /** Котрий крок маршруту попереду, -1 поза супроводом. */
    val stepIndex: Int,
    val distanceToManeuverM: Double,
    val maneuver: String?,
    val roadName: String?,
)

/**
 * Складання рядка з того, що сесія щойно повернула.
 *
 * Стоїть окремо від контролера навмисно: контролер тягне за собою сцену,
 * планувальник і мережу, а це — чиста функція, тож сторож перевіряє саме шов,
 * а не оточення. Той самий виклик, що йде в реєстратор, іде і в тест.
 */
fun guidanceTraceLine(
    update: GuidanceSession.Update,
    lat: Double,
    lon: Double,
    speedMps: Float?,
    trusted: Boolean,
    accuracyM: Float?,
    gait: NavGait,
    motionBearingDeg: Float?,
): GuidanceTraceLine {
    val p = update.progress
    return GuidanceTraceLine(
        lat = lat,
        lon = lon,
        snapLat = p.snappedLat,
        snapLon = p.snappedLon,
        offMeters = update.offMeters,
        offRoute = p.offRoute,
        offStreak = update.offStreak,
        requestReroute = update.requestReroute,
        routeBearingDeg = p.bearingDeg,
        motionBearingDeg = motionBearingDeg,
        speedMps = speedMps,
        trusted = trusted,
        accuracyM = accuracyM,
        gait = gait.name,
        stepIndex = p.stepIndex,
        distanceToManeuverM = p.distanceToManeuverM,
        maneuver = p.maneuverType,
        roadName = p.roadName,
    )
}
