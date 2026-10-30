package local.phantom.companion.core.indoor.pdr

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Виявлення кроків пішохода з акселерометра і оцінка довжини кроку.
 *
 * **Чому на вході модуль вектора, а не вертикальна вісь.** Телефон у кишені,
 * у руці біля вуха чи в підвісі перед грудьми має щоразу іншу орієнтацію, і
 * «вертикальна вісь пристрою» під землею нічого не означає. Модуль |a|
 * інваріантний до повороту пристрою, а силу тяжіння з нього прибирає
 * високочастотна частина смугового фільтра. Ціною є втрата знаку — але для
 * рахування кроків знак і не потрібен.
 *
 * **Чому смуга саме 0.5–3 Гц.** Каденс ходьби людини лежить у 1.4–2.3 Гц, біг —
 * до 3 Гц. Нижче 0.5 Гц — це дихання і повільний нахил корпусу, вище 3 Гц —
 * тремтіння руки, друк по екрану і вібрація ескалатора. Смуга відсікає обидва
 * джерела помилкових піків ще до порога.
 *
 * **Чому поріг адаптивний, але з абсолютним дном.** Амплітуда кроку залежить
 * від того, як несуть телефон: у кишені — 4–8 м/с², у витягнутій руці — 1–2.
 * Чистий відносний поріг підлаштувався б і під тряску на місці й почав би
 * рахувати кроки в людини, що стоїть. Тому нижня межа порога абсолютна
 * ([MIN_PEAK_MPS2]): коливання, слабші за неї, — не крок, ким би їх не робили.
 */
class StepDetector(
    /** Довірчий коефіцієнт довжини кроку; калібрується [calibrate]. */
    initialGain: Double = 1.0,
) {

    /** Множник моделі довжини кроку. 1.0 — «середній дорослий», далі вчиться. */
    var calibrationGain: Double = initialGain.coerceIn(MIN_GAIN, MAX_GAIN)
        private set

    private var gravityMps2: Double = Double.NaN
    private var fastMps2: Double = Double.NaN
    private var envelopeMps2: Double = 0.0

    private var lastMs: Long = 0L
    private var lastStepMs: Long = 0L

    private var inExcursion = false
    private var peakValue = 0.0
    private var peakMs = 0L
    private var cycleMin = 0.0

    /** Останній крок — потрібен калібруванню, щоб знати, що саме підкручувати. */
    private var lastRawLengthM = 0.0
    private var lastCadenceHz = 0.0

    /** Скільки кроків виявлено від [reset]. Діагностика. */
    var stepCount: Int = 0
        private set

    /**
     * Один семпл акселерометра.
     *
     * @param elapsedMs монотонний час семпла (`SystemClock.elapsedRealtime`)
     * @param accelMagnitudeMps2 модуль вектора прискорення разом із g
     * @return крок, якщо цей семпл закрив піковий екскурс, інакше `null`
     */
    fun onSample(elapsedMs: Long, accelMagnitudeMps2: Double): StepEvent? {
        // Сміття з сенсора не має права зсунути жоден фільтр: один NaN, що
        // потрапив у ковзне середнє, отруює його назавжди.
        if (!accelMagnitudeMps2.isFinite()) return null

        if (lastMs == 0L || gravityMps2.isNaN()) {
            initFilters(elapsedMs, accelMagnitudeMps2)
            return null
        }

        val dtS = (elapsedMs - lastMs) / 1000.0
        // Час назад — стрибок годинника або переплутані семпли; час уперед надто
        // далеко — потік урвався. В обох випадках фільтр не має інтегрувати крізь
        // розрив, інакше він видасть фальшивий пік на першому ж семплі після нього.
        if (dtS <= 0.0) return null
        if (dtS > MAX_GAP_S) {
            initFilters(elapsedMs, accelMagnitudeMps2)
            return null
        }
        lastMs = elapsedMs

        // Дві однополюсні ланки замість біквада: різниця «швидкого» і
        // «повільного» середніх і є смуговим фільтром, а сталі часу тут
        // читаються прямо в секундах, тож поріг можна обґрунтувати фізіологією,
        // а не коефіцієнтами.
        gravityMps2 += (1.0 - exp(-dtS / TAU_GRAVITY_S)) * (accelMagnitudeMps2 - gravityMps2)
        fastMps2 += (1.0 - exp(-dtS / TAU_FAST_S)) * (accelMagnitudeMps2 - fastMps2)
        val band = fastMps2 - gravityMps2

        envelopeMps2 += (1.0 - exp(-dtS / TAU_ENVELOPE_S)) * (abs(band) - envelopeMps2)
        val threshold = (ENVELOPE_K * envelopeMps2).coerceIn(MIN_PEAK_MPS2, MAX_PEAK_MPS2)

        if (band < cycleMin) cycleMin = band

        if (band > threshold) {
            if (!inExcursion) {
                inExcursion = true
                peakValue = band
                peakMs = elapsedMs
            } else if (band > peakValue) {
                peakValue = band
                peakMs = elapsedMs
            }
            return null
        }

        // Вихід із екскурсу навмисне нижчий за вхід: без гістерезису шум навколо
        // самого порога породив би десяток «кроків» на одному підйомі стопи.
        if (!inExcursion || band > threshold * EXIT_RATIO) return null
        inExcursion = false
        return closeStep()
    }

    /**
     * Калібрування довжини кроку за достовірною зовнішньою швидкістю.
     *
     * Викликати лише тоді, коли швидкість справді достовірна — останній
     * супутниковий розвʼязок перед входом у вестибюль, показ одометра
     * транспорту. Модель довжини кроку — це антропометрія конкретної людини
     * (зріст, довжина ноги, звичка), і 30 секунд на вулиці перед спуском дають
     * її точніше, ніж будь-яка «середня» константа.
     *
     * @param trustedSpeedMps горизонтальна швидкість із зовнішнього джерела
     * @param elapsedMs час цієї швидкості — застарілу до кроків не вʼяжемо
     */
    fun calibrate(trustedSpeedMps: Double, elapsedMs: Long) {
        if (!trustedSpeedMps.isFinite() || trustedSpeedMps < MIN_CAL_SPEED_MPS) return
        if (trustedSpeedMps > MAX_CAL_SPEED_MPS) return
        if (lastStepMs == 0L || lastCadenceHz <= 0.0 || lastRawLengthM <= 0.0) return
        if (elapsedMs - lastStepMs > CAL_MAX_AGE_MS) return

        // Модель уже враховує поточний gain, тож відношення прямо каже, у скільки
        // разів gain помиляється. Крок повільний навмисне: одна секунда чужої
        // швидкості не має права переписати антропометрію.
        val modelSpeed = lastRawLengthM * calibrationGain * lastCadenceHz
        if (modelSpeed <= 0.05) return
        val ratio = trustedSpeedMps / modelSpeed
        calibrationGain = (calibrationGain * (1.0 + CAL_RATE * (ratio - 1.0)))
            .coerceIn(MIN_GAIN, MAX_GAIN)
    }

    fun reset() {
        gravityMps2 = Double.NaN
        fastMps2 = Double.NaN
        envelopeMps2 = 0.0
        lastMs = 0L
        lastStepMs = 0L
        inExcursion = false
        peakValue = 0.0
        peakMs = 0L
        cycleMin = 0.0
        lastRawLengthM = 0.0
        lastCadenceHz = 0.0
        stepCount = 0
    }

    private fun initFilters(elapsedMs: Long, magnitude: Double) {
        gravityMps2 = magnitude
        fastMps2 = magnitude
        envelopeMps2 = 0.0
        lastMs = elapsedMs
        inExcursion = false
        cycleMin = 0.0
    }

    private fun closeStep(): StepEvent? {
        val interval = if (lastStepMs == 0L) 0L else peakMs - lastStepMs
        // Рефрактерне вікно: людина фізично не робить два кроки за 250 мс.
        // Усе, що швидше, — це другий горб того самого удару пʼяти або тряска.
        if (lastStepMs != 0L && interval < MIN_STEP_INTERVAL_MS) {
            cycleMin = 0.0
            return null
        }

        val cadenceHz = when {
            lastStepMs == 0L -> DEFAULT_CADENCE_HZ
            interval > MAX_STEP_INTERVAL_MS -> DEFAULT_CADENCE_HZ
            else -> 1000.0 / interval
        }.coerceIn(MIN_CADENCE_HZ, MAX_CADENCE_HZ)

        val amplitude = (peakValue - cycleMin).coerceIn(MIN_AMPLITUDE_MPS2, MAX_AMPLITUDE_MPS2)
        val rawLength = modelLengthM(cadenceHz, amplitude)
        val lengthM = (rawLength * calibrationGain).coerceIn(MIN_STEP_M, MAX_STEP_M)

        lastStepMs = peakMs
        lastRawLengthM = rawLength
        lastCadenceHz = cadenceHz
        cycleMin = 0.0
        stepCount++

        return StepEvent(
            elapsedMs = peakMs,
            lengthM = lengthM,
            cadenceHz = cadenceHz,
            amplitudeMps2 = amplitude,
        )
    }

    /**
     * Дві незалежні моделі, усереднені навпіл.
     *
     * Вайнберг (`L ∝ (a_max−a_min)^¼`) добре ловить різницю між кроком і
     * підтюпцем, але сліпне, коли телефон затиснуто в руці й вертикальна
     * амплітуда придушена. Лінійна від каденсу — навпаки: вона нечутлива до
     * хвату, але однаково оцінює обережний і широкий крок на тій самій частоті.
     * Помилки в них різної природи, тож середнє стабільніше за кожну окремо.
     */
    private fun modelLengthM(cadenceHz: Double, amplitudeMps2: Double): Double {
        val weinberg = WEINBERG_K * amplitudeMps2.pow(0.25)
        val cadenceModel = CADENCE_BASE_M + CADENCE_SLOPE_M_PER_HZ * (cadenceHz - DEFAULT_CADENCE_HZ)
        return 0.5 * (weinberg + cadenceModel)
    }

    companion object {
        /** Повільна ланка ≈ сила тяжіння разом із повільним нахилом корпусу. */
        const val TAU_GRAVITY_S = 1.0

        /** Швидка ланка: зріз ~2.6 Гц, тремтіння руки на 8 Гц слабне втричі. */
        const val TAU_FAST_S = 0.06

        /** Горизонт огинаючої: два-три кроки, щоб поріг ішов за манерою нести телефон. */
        const val TAU_ENVELOPE_S = 2.0

        const val ENVELOPE_K = 0.65
        const val EXIT_RATIO = 0.5

        /** Абсолютне дно порога: слабше — не крок, а тряска. */
        const val MIN_PEAK_MPS2 = 0.80
        const val MAX_PEAK_MPS2 = 4.0

        /** Розрив потоку — фільтри переініціалізувати, не інтегрувати крізь нього. */
        const val MAX_GAP_S = 1.5

        const val MIN_STEP_INTERVAL_MS = 250L
        const val MAX_STEP_INTERVAL_MS = 2_000L

        const val DEFAULT_CADENCE_HZ = 1.8
        const val MIN_CADENCE_HZ = 0.5
        const val MAX_CADENCE_HZ = 3.5

        const val MIN_AMPLITUDE_MPS2 = 0.2
        const val MAX_AMPLITUDE_MPS2 = 30.0

        const val WEINBERG_K = 0.45
        const val CADENCE_BASE_M = 0.72
        const val CADENCE_SLOPE_M_PER_HZ = 0.28

        const val MIN_STEP_M = 0.30
        const val MAX_STEP_M = 1.10

        const val MIN_GAIN = 0.6
        const val MAX_GAIN = 1.6

        /** Повільно: калібрування має усереднювати, а не стрибати за одним виміром. */
        const val CAL_RATE = 0.15
        const val MIN_CAL_SPEED_MPS = 0.5
        const val MAX_CAL_SPEED_MPS = 3.5
        const val CAL_MAX_AGE_MS = 3_000L
    }
}

/**
 * Один виявлений крок до того, як до нього приклали курс.
 *
 * @param elapsedMs час піку прискорення — саме він, а не час закриття екскурсу
 * @param lengthM оцінена довжина кроку з урахуванням калібрування
 * @param cadenceHz частота кроків на цьому інтервалі
 * @param amplitudeMps2 розмах смугового сигналу за цикл — вхід моделі Вайнберга
 */
data class StepEvent(
    val elapsedMs: Long,
    val lengthM: Double,
    val cadenceHz: Double,
    val amplitudeMps2: Double,
)
