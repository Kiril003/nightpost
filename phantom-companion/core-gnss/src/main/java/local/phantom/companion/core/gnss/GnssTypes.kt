package local.phantom.companion.core.gnss

/**
 * Спільний словник модуля: одна епоха спостережень і все, що з неї випливає.
 *
 * Свідомо plain-Kotlin, без жодного `android.location.*` у сигнатурах. Адаптер,
 * що читає `GnssMeasurementsEvent`, лишається єдиною точкою дотику до платформи;
 * уся математика нижче працює над цими типами, тому перевіряється синтетичними
 * епохами в JVM-тесті — без пристрою, без неба, без РЕБ у сусідньому кварталі.
 */

/** Супутникова система. Розділені, бо часові шкали та смуги в них різні. */
enum class Constellation { GPS, GLONASS, GALILEO, BEIDOU, QZSS, SBAS, IRNSS, UNKNOWN }

/**
 * Частотна смуга. Ключове розрізнення — L1 проти L5: двочастотний прийом дозволяє
 * побудувати безіоносферну комбінацію і зняти головне джерело затримки сигналу,
 * а вища тактова частота коду L5 різко знижує вплив багатошляховості в щільній
 * забудові. [UNKNOWN] — коли чіпсет не повідомив несучу частоту.
 */
enum class Band { L1, L2, L5, E5B, B2, UNKNOWN }

/**
 * Одне спостереження одного супутника в одну епоху.
 *
 * @param svid ідентифікатор супутника в межах своєї констеляції
 * @param cn0DbHz відношення несуча/шум. Головний індикатор здоровʼя сигналу:
 *   узгоджений обвал по всій констеляції — ознака придушення, а не хмар.
 * @param pseudorangeRateMps доплерівське зміщення, переведене у швидкість
 * @param accumulatedDeltaRangeM накопичена фаза несучої. Саме її різниця між
 *   епохами дає TDCP; значуща лише коли [adrValid] і не було зриву циклу.
 * @param adrValid стан фазового вимірювання дозволяє його використовувати
 * @param cycleSlip зафіксовано зрив фазового звʼязку — попередню фазу
 *   використовувати не можна, різниця буде сміттям
 * @param multipathSuspect чіпсет позначив ознаки багатошляховості
 * @param elevationDeg кут місця, якщо відомий: низькі супутники дають довший
 *   шлях крізь атмосферу і частіші перевідбиття
 */
data class SatObservation(
    val svid: Int,
    val constellation: Constellation,
    val band: Band,
    val cn0DbHz: Double,
    val pseudorangeRateMps: Double,
    val pseudorangeRateUncertaintyMps: Double = 0.0,
    val accumulatedDeltaRangeM: Double = 0.0,
    val accumulatedDeltaRangeUncertaintyM: Double = 0.0,
    val adrValid: Boolean = false,
    val cycleSlip: Boolean = false,
    val multipathSuspect: Boolean = false,
    val elevationDeg: Double? = null,
    val azimuthDeg: Double? = null,
    val usedInFix: Boolean = false,
) {
    /** Стабільний ключ супутника між епохами: свідомо включає смугу. */
    val key: String get() = "${constellation.name}-$svid-${band.name}"
}

/**
 * Знімок усіх спостережень на один момент часу.
 *
 * @param elapsedRealtimeNanos монотонний годинник пристрою — єдина шкала, якій
 *   можна довіряти для різниць: системний час стрибає при синхронізації.
 * @param clockBiasNanos зсув годинника приймача відносно шкали GNSS. Різкий
 *   стрибок без відповідного руху — самостійна ознака підміни.
 */
data class GnssEpoch(
    val elapsedRealtimeNanos: Long,
    val observations: List<SatObservation>,
    val clockBiasNanos: Double? = null,
    val clockDriftNanosPerSec: Double? = null,
    val hardwareClockDiscontinuityCount: Int = 0,
) {
    val satCount: Int get() = observations.size
    val dualFrequency: Boolean
        get() = observations.any { it.band == Band.L5 } && observations.any { it.band == Band.L1 }
}

/** Звідки насправді взято поточний розвʼязок. Показується користувачу як є. */
enum class FixSource { GNSS_RAW, GNSS_FUSED, DEAD_RECKONING, WIFI_RTT, ROAD_MATCH, NETWORK, NONE }

/**
 * Вердикт про цілісність супутникового сигналу.
 *
 * Розділення [jammingScore] і [spoofingScore] принципове: глушіння відбирає
 * сигнал (і це видно як обвал C/N0 та втрату супутників), підміна навпаки дає
 * сильний і «здоровий» сигнал, який бреше. Обробка в них протилежна — при
 * глушінні треба перейти на числення, при підміні треба відкинути супутники,
 * які виглядають найкраще.
 *
 * @param excluded супутники, виключені FDE, з причиною — щоб було що показати
 *   на екрані і що записати в чорну скриньку
 */
data class GnssIntegrity(
    val jammingScore: Double,
    val spoofingScore: Double,
    val usableSats: Int,
    val excluded: Map<String, String> = emptyMap(),
    val dualFrequency: Boolean = false,
    val source: FixSource = FixSource.NONE,
    val notes: List<String> = emptyList(),
) {
    val compromised: Boolean get() = jammingScore >= 0.6 || spoofingScore >= 0.6
}
