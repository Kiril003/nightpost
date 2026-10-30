package local.phantom.companion.core.indoor.baro

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round

/**
 * Вертикальна складова: тиск → відносна висота → рівень (поверх).
 *
 * **Чому це взагалі окреме джерело.** Горизонтальна позиція під землею
 * невизначена й дорога, а вертикальна — майже безплатна й напрочуд надійна:
 * барометр телефона розрізняє поверх упевнено, а підмінити його дистанційно
 * неможливо. Глушник може забрати небо; повітря в переході він забрати не може.
 * Для людини в метро «ти на рівні платформи, а не вестибюля» часто важливіше за
 * «ти за 8 метрів на північ».
 *
 * **Головна проблема — не шум, а погода.** Один гектопаскаль — це 8.3 метра,
 * тобто майже три поверхи. Проходження фронту дає 3–5 гПа за годину, і
 * некомпенсований барометр «підіймає» нерухому людину на десять поверхів за ніч.
 * Розділення робиться за швидкістю: погода змінює тиск на порядки повільніше за
 * рух тіла.
 *
 *   • спуск сходами — близько 0.03 гПа/с;
 *   • ліфт або ескалатор — 0.1 гПа/с і більше;
 *   • найгірша погода — 0.0015 гПа/с.
 *
 * Тому опорне значення повзе за повільною складовою і **заморожується, щойно
 * почався рух**: інакше воно «зʼїло» б справжній перехід між рівнями за кілька
 * хвилин і повернуло б людину на нульовий поверх, поки та стоїть на платформі.
 *
 * **Чому рівні не рівномірні.** У житловому будинку поверх — три метри, і
 * рівномірного кроку досить. У метро глибокого закладення вестибюль на −5 м, а
 * платформа на −45; ділити таку глибину на «поверхи по три метри» безглуздо.
 * Тому клас приймає [levelPlan] — таблицю відомих висот рівнів із плану станції,
 * і рівень між ними інтерполюється (проміжний сходовий майданчик чесно виходить
 * як −1.5).
 */
class BarometricLevel(
    /** Висота одного рівня, коли плану станції немає. */
    private val floorHeightM: Double = DEFAULT_FLOOR_HEIGHT_M,
    /** Відомі висоти рівнів відносно точки калібрування. Порожня — рівномірний крок. */
    levelPlan: List<LevelMark> = emptyList(),
) {

    private val plan: List<LevelMark> = levelPlan
        .filter { it.altitudeM.isFinite() && it.level.isFinite() }
        .sortedBy { it.altitudeM }

    private var refHpa: Double = Double.NaN
    private var smoothedHpa: Double = Double.NaN
    private var slowHpa: Double = Double.NaN
    private var lastMs: Long = 0L
    private var lastAltitudeM: Double = 0.0

    /** Рівень, з якого починали: 0 — вулиця біля входу. */
    private var refLevel: Double = 0.0

    /** Висота відносно точки калібрування, м. Додатна — вище. */
    var relativeAltitudeM: Double = 0.0
        private set

    /** Швидкість вертикального руху, м/с. Додатна — вгору. */
    var verticalRateMps: Double = 0.0
        private set

    /** Рівень із гістерезисом, кратний 0.5. `null` — ще не було жодного виміру. */
    var level: Double? = null
        private set

    /** Той самий рівень без округлення — для діагностики й плавної анімації. */
    var continuousLevel: Double = 0.0
        private set

    /** Оцінена швидкість зміни тиску через погоду, гПа/с. Діагностика. */
    var weatherRateHpaPerS: Double = 0.0
        private set

    /** Людина зараз їде/йде вертикально: ліфт, ескалатор, сходи. */
    val inVerticalTransit: Boolean get() = abs(verticalRateMps) > TRANSIT_RATE_MPS

    /**
     * Один вимір тиску.
     *
     * @param hpa абсолютний тиск, гПа
     * @return поточний рівень або `null`, поки перший вимір лише ініціалізує стан
     */
    fun onPressure(elapsedMs: Long, hpa: Double): Double? {
        if (!hpa.isFinite() || hpa < MIN_PRESSURE_HPA || hpa > MAX_PRESSURE_HPA) return level

        if (refHpa.isNaN()) {
            init(elapsedMs, hpa)
            return level
        }

        val dtS = (elapsedMs - lastMs) / 1000.0
        // Стрибок годинника назад або надто довга пауза: інтегрувати дрейф
        // погоди крізь такий розрив не можна — компенсація перетвориться на
        // вигадану висоту.
        if (dtS <= 0.0) return level
        if (dtS > MAX_GAP_S) {
            lastMs = elapsedMs
            smoothedHpa = hpa
            return level
        }
        lastMs = elapsedMs

        smoothedHpa += (1.0 - exp(-dtS / TAU_FAST_S)) * (hpa - smoothedHpa)
        slowHpa += (1.0 - exp(-dtS / TAU_SLOW_S)) * (smoothedHpa - slowHpa)

        // Відставання однополюсної ланки від лінійного наростання дорівнює
        // швидкість×стала_часу. Звідси швидкість беруть просто як різницю
        // швидкого і повільного середніх, поділену на сталу часу, — і ця оцінка
        // усереднює шум сенсора, на відміну від прямої різниці сусідніх вимірів.
        val divergence = smoothedHpa - slowHpa
        if (abs(divergence) < MOVEMENT_GATE_HPA) {
            val implied = divergence / TAU_SLOW_S
            val k = 1.0 - exp(-dtS / TAU_WEATHER_S)
            weatherRateHpaPerS = (weatherRateHpaPerS + k * (implied - weatherRateHpaPerS))
                .coerceIn(-MAX_WEATHER_RATE, MAX_WEATHER_RATE)
        }
        // Опора повзе рівно настільки, наскільки повзе погода, — і ні на скільки
        // більше. Це і є розділення повільної складової від швидкої.
        refHpa += weatherRateHpaPerS * dtS

        val previousAltitude = lastAltitudeM
        relativeAltitudeM = altitudeM(smoothedHpa, refHpa)
        lastAltitudeM = relativeAltitudeM

        val instantRate = (relativeAltitudeM - previousAltitude) / dtS
        verticalRateMps += (1.0 - exp(-dtS / TAU_RATE_S)) * (instantRate - verticalRateMps)

        continuousLevel = levelForAltitude(relativeAltitudeM)
        level = snap(continuousLevel)
        return level
    }

    /**
     * Оголосити поточну висоту відомим рівнем: вхід у вестибюль (рівень 0),
     * вихід на платформу за схемою станції, ручна вказівка користувача.
     *
     * Це єдиний спосіб перетворити відносну висоту на абсолютний поверх:
     * атмосферний тиск на рівні моря невідомий, а QNH найближчого аеропорту —
     * не те, що чекає людина в переході.
     */
    fun calibrateAt(level: Double) {
        if (!level.isFinite() || smoothedHpa.isNaN()) return
        refHpa = smoothedHpa
        refLevel = level
        relativeAltitudeM = 0.0
        lastAltitudeM = 0.0
        verticalRateMps = 0.0
        continuousLevel = level
        this.level = snapValue(level)
    }

    fun reset() {
        refHpa = Double.NaN
        smoothedHpa = Double.NaN
        slowHpa = Double.NaN
        lastMs = 0L
        lastAltitudeM = 0.0
        refLevel = 0.0
        relativeAltitudeM = 0.0
        verticalRateMps = 0.0
        continuousLevel = 0.0
        weatherRateHpaPerS = 0.0
        level = null
    }

    private fun init(elapsedMs: Long, hpa: Double) {
        refHpa = hpa
        smoothedHpa = hpa
        slowHpa = hpa
        lastMs = elapsedMs
        relativeAltitudeM = 0.0
        lastAltitudeM = 0.0
        verticalRateMps = 0.0
        weatherRateHpaPerS = 0.0
        continuousLevel = refLevel
        level = snapValue(refLevel)
    }

    /** Барометрична формула стандартної атмосфери. */
    private fun altitudeM(p: Double, reference: Double): Double =
        44_330.0 * (1.0 - (p / reference).pow(1.0 / 5.255))

    private fun levelForAltitude(altitude: Double): Double {
        if (plan.size < 2) return refLevel + altitude / floorHeightM

        val first = plan.first()
        val last = plan.last()
        if (altitude <= first.altitudeM) return extrapolate(altitude, plan[0], plan[1])
        if (altitude >= last.altitudeM) return extrapolate(altitude, plan[plan.size - 2], last)
        for (i in 0 until plan.size - 1) {
            val a = plan[i]
            val b = plan[i + 1]
            if (altitude in a.altitudeM..b.altitudeM) {
                val span = b.altitudeM - a.altitudeM
                if (span <= 0.0) return a.level
                val t = (altitude - a.altitudeM) / span
                return a.level + t * (b.level - a.level)
            }
        }
        return last.level
    }

    private fun extrapolate(altitude: Double, a: LevelMark, b: LevelMark): Double {
        val span = b.altitudeM - a.altitudeM
        if (span == 0.0) return a.level
        val slope = (b.level - a.level) / span
        return a.level + (altitude - a.altitudeM) * slope
    }

    /**
     * Округлення до піврівня з гістерезисом.
     *
     * Без гістерезису людина, що стоїть біля межі між рівнями, бачила б, як
     * підпис блимає між −1 і −1.5 від шуму сенсора. Перемикання дозволене лише
     * тоді, коли безперервна оцінка відійшла від показаного значення далі, ніж
     * пів кроку плюс запас.
     */
    private fun snap(continuous: Double): Double {
        val current = level ?: return snapValue(continuous)
        if (abs(continuous - current) <= HALF_STEP + HYSTERESIS_LEVELS) return current
        return snapValue(continuous)
    }

    private fun snapValue(v: Double): Double = round(v / LEVEL_STEP) * LEVEL_STEP

    companion object {
        const val DEFAULT_FLOOR_HEIGHT_M = 3.0

        /** Крок звітності: пів рівня — це сходовий майданчик між поверхами. */
        const val LEVEL_STEP = 0.5
        const val HALF_STEP = LEVEL_STEP / 2.0
        const val HYSTERESIS_LEVELS = 0.15

        const val TAU_FAST_S = 1.5
        const val TAU_SLOW_S = 120.0
        const val TAU_WEATHER_S = 60.0
        const val TAU_RATE_S = 2.0

        /** Розбіжність швидкого і повільного середніх понад це — рух, не погода. */
        const val MOVEMENT_GATE_HPA = 0.08

        /** Найшвидший атмосферний фронт — близько 5 гПа/год. */
        const val MAX_WEATHER_RATE = 0.0015

        const val TRANSIT_RATE_MPS = 0.25

        const val MAX_GAP_S = 30.0
        const val MIN_PRESSURE_HPA = 300.0
        const val MAX_PRESSURE_HPA = 1_200.0
    }
}

/**
 * Відома висота рівня з плану будівлі чи станції.
 *
 * @param level номер рівня: 0 — вулиця, −1 — вестибюль, −2 — платформа
 * @param altitudeM висота цього рівня відносно точки калібрування, метри
 *   (відʼємна вниз)
 */
data class LevelMark(val level: Double, val altitudeM: Double)
