package local.phantom.companion.core.mobility

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Ознаки одного вікна — вхідний контракт класифікатора.
 *
 * Вихід свідомо `Map<String, Double>`, а не типізований клас: на це саме місце має
 * стати нейромодель, якій потрібен плаский вектор із стабільним порядком ключів,
 * і будь-яка зміна набору ознак не має ламати компіляцію споживачів. Щоб «плаский»
 * не означало «безіменний», усі ключі перелічені в [MotionFeatureKeys] і
 * використовуються тільки звідти — рядкових літералів у класифікаторі немає.
 *
 * Уся спектральна робота — руками ([MotionDsp]): жодних бібліотек, десятки рядків,
 * повністю тестується.
 */
object MotionFeatures {

    /**
     * Порахувати ознаки вікна.
     *
     * @param speedsMps швидкості з зовнішнього джерела (GNSS/фьюжен), що потрапили
     *   в це вікно. Порожній масив — законний стан: у тунелі і в кишені швидкості
     *   немає, і ознаки чесно кажуть про це через [MotionFeatureKeys.SPEED_AVAILABLE].
     */
    fun extract(window: MotionWindow, speedsMps: DoubleArray = DoubleArray(0)): Map<String, Double> {
        val k = MotionFeatureKeys
        val n = window.size
        val out = LinkedHashMap<String, Double>(40)
        out[k.WINDOW_SAMPLES] = n.toDouble()
        out[k.WINDOW_SAMPLE_HZ] = window.sampleHz
        out[k.WINDOW_GAP_FRACTION] = window.gapFraction
        out[k.WINDOW_DURATION_S] = window.durationMs / 1000.0
        // Коротке вікно — не привід вигадувати спектр. Класифікатор побачить
        // WINDOW_SAMPLES і поверне UNKNOWN; решта ключів просто відсутня.
        if (n < MIN_SAMPLES) return out

        val hz = window.sampleHz
        val mag = window.accelMag
        val mean = MotionDsp.mean(mag)
        val variance = MotionDsp.variance(mag, mean)
        out[k.ACC_MEAN] = mean
        out[k.ACC_VAR] = variance
        out[k.ACC_STD] = sqrt(variance)
        out[k.ACC_RANGE] = window.accelRange()
        out[k.ACC_ZERO_CROSS_HZ] = MotionDsp.zeroCrossingsPerSecond(mag, mean, hz)
        out[k.ACC_JERK_MEAN] = MotionDsp.meanAbsDerivative(mag, hz)
        out[k.ACC_GRAVITY] = window.gravityMps2

        // Каденс шукається у вертикальній складовій: крок — це насамперед
        // підйом і опускання центру мас, а горизонтальна складова забруднена
        // розмахуванням руки з телефоном.
        val vert = window.vertical
        val vertMean = MotionDsp.mean(vert)
        val peak = MotionDsp.spectralPeak(vert, vertMean, hz, CADENCE_MIN_HZ, CADENCE_MAX_HZ, CADENCE_STEP_HZ)
        out[k.CADENCE_HZ] = peak.hz
        out[k.CADENCE_STRENGTH] = peak.strength

        val acorr = MotionDsp.autocorrelationPeak(vert, vertMean, hz, CADENCE_MIN_HZ, CADENCE_MAX_HZ)
        out[k.ACORR_PEAK] = acorr.value
        out[k.ACORR_HZ] = acorr.hz

        val bands = MotionDsp.bandFractions(vert, vertMean, hz, BAND_EDGES_HZ)
        out[k.BAND_P0] = bands[0]
        out[k.BAND_P1] = bands[1]
        out[k.BAND_P2] = bands[2]
        out[k.BAND_P3] = bands[3]
        out[k.BAND_P4] = bands[4]

        val vertRms = MotionDsp.rms(vert, vertMean)
        val horizRms = MotionDsp.rms(window.horizontal, MotionDsp.mean(window.horizontal))
        out[k.VERT_RMS] = vertRms
        out[k.HORIZ_RMS] = horizRms
        // Частка, а не відношення: відношення вибухає в нескінченність на
        // нерухомому телефоні, частка лишається в [0,1] і порівнянна між режимами.
        out[k.VERT_FRACTION] = if (vertRms + horizRms > 1e-9) vertRms / (vertRms + horizRms) else 0.0

        if (window.hasGyro) {
            val gMean = MotionDsp.mean(window.gyroMag)
            out[k.GYRO_AVAILABLE] = 1.0
            out[k.GYRO_MEAN] = gMean
            out[k.GYRO_STD] = sqrt(MotionDsp.variance(window.gyroMag, gMean))
        } else {
            out[k.GYRO_AVAILABLE] = 0.0
        }

        val speeds = speedsMps.filter { it.isFinite() && it >= 0.0 }.toDoubleArray()
        if (speeds.isEmpty()) {
            out[k.SPEED_AVAILABLE] = 0.0
        } else {
            val sMean = MotionDsp.mean(speeds)
            val sStd = sqrt(MotionDsp.variance(speeds, sMean))
            out[k.SPEED_AVAILABLE] = 1.0
            out[k.SPEED_MEAN] = sMean
            out[k.SPEED_MAX] = speeds.max()
            out[k.SPEED_STD] = sStd
            // Коефіцієнт варіації: 3 м/с розкиду означає різне для пішохода і
            // для потяга, і саме нормована величина відрізняє «стабільну
            // швидкість велосипеда» від «міського трафіку».
            out[k.SPEED_CV] = if (sMean > 0.5) sStd / sMean else 0.0
            out[k.SPEED_STOP_FRACTION] = speeds.count { it < STOP_SPEED_MPS }.toDouble() / speeds.size
        }
        return out
    }

    /** Менше — і спектр 18-секундного вікна не має сенсу. */
    const val MIN_SAMPLES = 32

    /** Швидкість, нижче якої це вже повна зупинка, а не повільний рух, м/с. */
    const val STOP_SPEED_MPS = 0.6

    /** Нижня межа пошуку каденсу, Гц (повільна прогулянка). */
    const val CADENCE_MIN_HZ = 0.5

    /** Верхня межа пошуку каденсу, Гц. Вище — уже за межею корисного при 10 Гц сітки. */
    const val CADENCE_MAX_HZ = 4.6

    /**
     * Крок сканування спектра, Гц. Природна роздільність 18-секундного вікна —
     * 1/18 ≈ 0.056 Гц, тож дрібніший крок нічого не додає, крім часу.
     */
    const val CADENCE_STEP_HZ = 0.025

    /**
     * Межі смуг, Гц. Підібрані під фізику режимів, а не рівномірно:
     * P0 (0.2–0.8) — коливання кузова і підвіски; P1 (0.8–1.6) — каденс
     * велосипедиста і повільна хода; P2 (1.6–2.6) — основна смуга ходьби;
     * P3 (2.6–3.6) — біг і друга гармоніка ходьби; P4 (3.6–4.8) — дрібна вібрація
     * рами і покриття.
     */
    val BAND_EDGES_HZ = doubleArrayOf(0.2, 0.8, 1.6, 2.6, 3.6, 4.8)
}

/**
 * Імена ознак — контракт між [MotionFeatures] і будь-яким класифікатором.
 *
 * Одиниці виміру в імені. Ключ, якого немає в мапі, означає «не вимірювалось»
 * (немає гіроскопа, немає швидкості), і це не те саме, що нуль.
 */
object MotionFeatureKeys {
    /** Кількість вузлів у вікні. */
    const val WINDOW_SAMPLES = "window.samples"

    /** Частота сітки, Гц. */
    const val WINDOW_SAMPLE_HZ = "window.sampleHz"

    /** Частка вузлів, добудованих через задовгу прогалину, 0..1. */
    const val WINDOW_GAP_FRACTION = "window.gapFraction"

    /** Тривалість вікна, с. */
    const val WINDOW_DURATION_S = "window.durationS"

    /** Середній модуль прискорення, м/с² (≈ 9.8 у спокої). */
    const val ACC_MEAN = "acc.mean"

    /** Дисперсія модуля прискорення, (м/с²)². */
    const val ACC_VAR = "acc.var"

    /** СКВ модуля прискорення, м/с². */
    const val ACC_STD = "acc.std"

    /** Розмах модуля прискорення, м/с². */
    const val ACC_RANGE = "acc.range"

    /** Перетини середнього рівня за секунду, 1/с. */
    const val ACC_ZERO_CROSS_HZ = "acc.zeroCrossHz"

    /** Середній модуль похідної прискорення (ривок), м/с³. */
    const val ACC_JERK_MEAN = "acc.jerkMean"

    /** Оцінене тяжіння за вікном, м/с². */
    const val ACC_GRAVITY = "acc.gravity"

    /** Частота спектрального піку вертикальної складової, Гц. */
    const val CADENCE_HZ = "cadence.hz"

    /** Частка енергії в піку, 0..1 — наскільки рух узагалі циклічний. */
    const val CADENCE_STRENGTH = "cadence.strength"

    /** Нормований автокореляційний пік у смузі каденсу, 0..1. */
    const val ACORR_PEAK = "acorr.peak"

    /** Частота, що відповідає автокореляційному піку, Гц. */
    const val ACORR_HZ = "acorr.hz"

    /** Частка енергії у смузі 0.2–0.8 Гц. */
    const val BAND_P0 = "band.p0"

    /** Частка енергії у смузі 0.8–1.6 Гц. */
    const val BAND_P1 = "band.p1"

    /** Частка енергії у смузі 1.6–2.6 Гц. */
    const val BAND_P2 = "band.p2"

    /** Частка енергії у смузі 2.6–3.6 Гц. */
    const val BAND_P3 = "band.p3"

    /** Частка енергії у смузі 3.6–4.8 Гц. */
    const val BAND_P4 = "band.p4"

    /** СКВ вертикальної складової, м/с². */
    const val VERT_RMS = "vert.rms"

    /** СКВ горизонтальної складової, м/с². */
    const val HORIZ_RMS = "horiz.rms"

    /** Частка вертикальної складової в сумі вертикальної і горизонтальної, 0..1. */
    const val VERT_FRACTION = "vert.fraction"

    /** 1.0, якщо гіроскоп був доступний усе вікно. */
    const val GYRO_AVAILABLE = "gyro.available"

    /** Середній модуль кутової швидкості, рад/с. */
    const val GYRO_MEAN = "gyro.mean"

    /** СКВ модуля кутової швидкості, рад/с. */
    const val GYRO_STD = "gyro.std"

    /** 1.0, якщо у вікні була хоч одна зовнішня швидкість. */
    const val SPEED_AVAILABLE = "speed.available"

    /** Середня швидкість за вікном, м/с. */
    const val SPEED_MEAN = "speed.mean"

    /** Максимальна швидкість за вікном, м/с. */
    const val SPEED_MAX = "speed.max"

    /** СКВ швидкості, м/с. */
    const val SPEED_STD = "speed.std"

    /** Коефіцієнт варіації швидкості, 0..∞. */
    const val SPEED_CV = "speed.cv"

    /** Частка вимірів із повною зупинкою, 0..1. */
    const val SPEED_STOP_FRACTION = "speed.stopFraction"
}

/**
 * Мала DSP-бібліотека модуля: рівно те, що потрібно ознакам, і нічого понад.
 *
 * Гоертцель замість FFT свідомо: нас цікавлять кілька десятків конкретних частот
 * у смузі 0.2–4.8 Гц, а не весь спектр. Гоертцель на одну частоту — це три змінні
 * і один прохід масивом; FFT потребувала б доповнення до степеня двійки, вікна і
 * бібліотеки, яку сюди тягнути заборонено.
 */
object MotionDsp {

    /** Пік спектра: частота і частка енергії в ній. */
    data class Peak(val hz: Double, val strength: Double)

    /** Пік автокореляції: нормоване значення 0..1 і відповідна частота. */
    data class AcorrPeak(val value: Double, val hz: Double)

    /** Середнє. Порожній масив → 0.0. */
    fun mean(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) s += v
        return s / x.size
    }

    /** Дисперсія відносно заданого середнього. */
    fun variance(x: DoubleArray, mean: Double): Double {
        if (x.size < 2) return 0.0
        var s = 0.0
        for (v in x) { val d = v - mean; s += d * d }
        return s / x.size
    }

    /** СКВ відносно заданого середнього. */
    fun rms(x: DoubleArray, mean: Double): Double = sqrt(variance(x, mean))

    /**
     * Перетини середнього рівня за секунду.
     *
     * Перетин зараховується, лише якщо піврозмах перед ним мав реальну амплітуду
     * ([crossingGateFraction] від СКВ): інакше телефон на сидінні авто «ходить»,
     * бо шум АЦП перетинає нуль сотні разів на секунду.
     */
    fun zeroCrossingsPerSecond(
        x: DoubleArray,
        mean: Double,
        sampleHz: Double,
        crossingGateFraction: Double = 0.3,
    ): Double {
        if (x.size < 2 || sampleHz <= 0.0) return 0.0
        val gate = rms(x, mean) * crossingGateFraction
        var crossings = 0
        var peak = 0.0
        var prev = x[0] - mean
        for (i in 1 until x.size) {
            val cur = x[i] - mean
            if (abs(cur) > peak) peak = abs(cur)
            if (cur * prev < 0.0) {
                if (peak > gate) crossings++
                peak = 0.0
            }
            prev = cur
        }
        val seconds = x.size / sampleHz
        return if (seconds > 0.0) crossings / seconds else 0.0
    }

    /** Середній модуль похідної, одиниці входу за секунду. */
    fun meanAbsDerivative(x: DoubleArray, sampleHz: Double): Double {
        if (x.size < 2) return 0.0
        var s = 0.0
        for (i in 1 until x.size) s += abs(x[i] - x[i - 1])
        return s / (x.size - 1) * sampleHz
    }

    /**
     * Потужність сигналу на частоті [targetHz] за алгоритмом Гоертцеля.
     *
     * Нормована на N², щоб результат не залежав від довжини вікна і його можна
     * було порівнювати між смугами.
     */
    fun goertzelPower(x: DoubleArray, mean: Double, sampleHz: Double, targetHz: Double): Double {
        val n = x.size
        if (n < 4 || sampleHz <= 0.0) return 0.0
        val w = 2.0 * Math.PI * targetHz / sampleHz
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s0 = (v - mean) + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return if (power > 0.0) power / (n.toDouble() * n) else 0.0
    }

    /**
     * Найпотужніша частота в смузі [minHz]..[maxHz] і її частка в сумарній
     * енергії тієї ж смуги.
     *
     * Частка (а не абсолютна потужність) — тому що саме вона відповідає на
     * питання «рух циклічний чи просто трясе»: у ходьби пік збирає більшу
     * частину енергії, у поганої дороги енергія розмазана.
     */
    fun spectralPeak(
        x: DoubleArray,
        mean: Double,
        sampleHz: Double,
        minHz: Double,
        maxHz: Double,
        stepHz: Double,
    ): Peak {
        if (x.size < 8 || stepHz <= 0.0) return Peak(0.0, 0.0)
        var bestHz = 0.0
        var bestP = 0.0
        var total = 0.0
        var f = minHz
        while (f <= maxHz + 1e-9) {
            val p = goertzelPower(x, mean, sampleHz, f)
            total += p
            if (p > bestP) { bestP = p; bestHz = f }
            f += stepHz
        }
        val strength = if (total > 1e-18) bestP / total else 0.0
        return Peak(bestHz, strength.coerceIn(0.0, 1.0))
    }

    /**
     * Частки енергії по смугах, задані [edgesHz] (k+1 межа → k смуг).
     *
     * Нормування — на суму саме цих смуг, а не на повну потужність сигналу:
     * так частки завжди дають одиницю, і поріг «дві третини енергії нижче
     * 0.8 Гц» лишається зрозумілим числом.
     */
    fun bandFractions(x: DoubleArray, mean: Double, sampleHz: Double, edgesHz: DoubleArray): DoubleArray {
        val bands = edgesHz.size - 1
        val out = DoubleArray(bands)
        if (x.size < 8 || bands < 1) return out
        // Крок сканування — природна роздільність вікна: тонше не дасть нової
        // інформації, грубіше пропустить вузький пік.
        val step = (sampleHz / x.size).coerceAtLeast(0.01)
        var total = 0.0
        for (b in 0 until bands) {
            var s = 0.0
            var f = edgesHz[b]
            while (f < edgesHz[b + 1]) {
                s += goertzelPower(x, mean, sampleHz, f)
                f += step
            }
            out[b] = s
            total += s
        }
        if (total > 1e-18) for (b in 0 until bands) out[b] /= total
        return out
    }

    /**
     * Найбільший нормований пік автокореляції в смузі лагів, що відповідає
     * [minHz]..[maxHz].
     *
     * Автокореляція доповнює спектр, а не дублює його: вона відповідає на інше
     * питання — чи повторюється *форма* циклу. Синусоїда і серія ударів пʼятою
     * дають однаковий пік у спектрі, але різну автокореляцію.
     *
     * Роздільність за частотою тут гірша за спектральну (лаг цілий), тому пік
     * уточнюється параболою по трьох сусідніх лагах.
     */
    fun autocorrelationPeak(
        x: DoubleArray,
        mean: Double,
        sampleHz: Double,
        minHz: Double,
        maxHz: Double,
    ): AcorrPeak {
        val n = x.size
        if (n < 16 || sampleHz <= 0.0 || minHz <= 0.0) return AcorrPeak(0.0, 0.0)
        val minLag = (sampleHz / maxHz).toInt().coerceAtLeast(2)
        val maxLag = (sampleHz / minHz).toInt().coerceAtMost(n / 2)
        if (maxLag <= minLag) return AcorrPeak(0.0, 0.0)

        var r0 = 0.0
        for (v in x) { val d = v - mean; r0 += d * d }
        if (r0 < 1e-12) return AcorrPeak(0.0, 0.0)

        val r = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in lag until n) s += (x[i] - mean) * (x[i - lag] - mean)
            r[lag] = s / r0
        }
        var bestLag = minLag
        for (lag in minLag..maxLag) if (r[lag] > r[bestLag]) bestLag = lag
        var refined = bestLag.toDouble()
        if (bestLag > minLag && bestLag < maxLag) {
            val a = r[bestLag - 1]
            val b = r[bestLag]
            val c = r[bestLag + 1]
            val denom = a - 2 * b + c
            if (abs(denom) > 1e-12) refined = bestLag + 0.5 * (a - c) / denom
        }
        val hz = if (refined > 1e-6) sampleHz / refined else 0.0
        return AcorrPeak(r[bestLag].coerceIn(0.0, 1.0), hz)
    }
}
