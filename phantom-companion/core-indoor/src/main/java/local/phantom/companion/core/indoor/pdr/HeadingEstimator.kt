package local.phantom.companion.core.indoor.pdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Курс пішохода: гіроскоп плюс магнітометр, із падінням довіри до магнітометра
 * там, де вірити йому не можна.
 *
 * **Розподіл ролей.** Гіроскоп бездоганний на секундах і безнадійний на
 * хвилинах: інтеграл кутової швидкості не має абсолютної привʼязки, і зсув
 * нуля в 0.5 °/с за десять хвилин повертає траєкторію на 300°. Магнітометр
 * навпаки — абсолютний, але не має права голосу там, де поле не земне.
 *
 * **Чому детектор аномалії обовʼязковий саме в метро.** Під землею магнітне
 * поле спотворене всім одразу: армована обробка тунелю, рейки, контактна
 * мережа з сотнями ампер, ескалатор із тяговим двигуном, сам вагон. Модуль
 * поля там стрибає з ~50 мкТл до 90 і назад за секунди. Курс за таким
 * магнітометром — не «трохи неточний», а повернутий на десятки градусів у
 * випадковий бік, і саме він створює той системний дрейф, який потім
 * доводиться виправляти суміщенням цілої кривої.
 *
 * Тому: різка зміна МОДУЛЯ поля — це відмова довіри. Модуль перевіряється, а не
 * напрямок, бо істинний модуль земного поля в одній точці міста постійний, і
 * будь-яка його зміна — свідчення стороннього заліза, тоді як зміна напрямку
 * може бути просто поворотом людини.
 *
 * Вхід — уже готовий магнітний курс і модуль поля, а не сирі три осі: нахил
 * компенсується орієнтацією пристрою, а власником орієнтації в цьому проєкті є
 * `:core-sensor`. Дублювати тут кватерніони немає сенсу, і це залишає цей файл
 * чистим Kotlin без жодного `android.hardware`.
 */
class HeadingEstimator {

    /** Поточний курс, радіани, від півночі за годинниковою стрілкою, у [0, 2π). */
    var headingRad: Double = 0.0
        private set

    /** Чесна оцінка невизначеності курсу (1σ, радіани). */
    var uncertaintyRad: Double = INITIAL_UNCERTAINTY_RAD
        private set

    /** Довіра до магнітометра в [0, 1]. Нуль — зараз він бреше. */
    var magTrust: Double = 0.0
        private set

    /** Магнітна аномалія активна прямо зараз. */
    var anomaly: Boolean = false
        private set

    private var haveHeading = false
    private var lastGyroMs: Long = 0L

    private var refFieldUt: Double = Double.NaN
    private var lastFieldUt: Double = Double.NaN
    private var lastMagMs: Long = 0L
    private var quietSinceMs: Long = 0L

    /**
     * Привʼязати курс до відомого зовнішнього значення — останній надійний
     * супутниковий курс перед входом у вестибюль.
     */
    fun seedHeading(headingRad: Double, uncertaintyRad: Double) {
        if (!headingRad.isFinite() || !uncertaintyRad.isFinite()) return
        this.headingRad = wrap(headingRad)
        this.uncertaintyRad = uncertaintyRad.coerceIn(MIN_UNCERTAINTY_RAD, PI)
        haveHeading = true
    }

    /**
     * Кутова швидкість навколо вертикалі (рад/с, проти годинникової стрілки —
     * знак як у `Sensor.TYPE_GYROSCOPE` після приведення до світової системи).
     */
    fun onGyro(elapsedMs: Long, yawRateRadPerS: Double) {
        if (!yawRateRadPerS.isFinite()) return
        if (lastGyroMs == 0L) {
            lastGyroMs = elapsedMs
            haveHeading = true
            return
        }
        val dtS = (elapsedMs - lastGyroMs) / 1000.0
        if (dtS <= 0.0) return
        lastGyroMs = elapsedMs
        if (dtS > MAX_GYRO_GAP_S) return

        // Знак: додатна кутова швидкість навколо вертикалі — це поворот проти
        // годинникової, тобто зменшення азимута.
        headingRad = wrap(headingRad - yawRateRadPerS * dtS)
        haveHeading = true

        // Випадкове блукання: дисперсія росте лінійно з ЧАСОМ (на відміну від
        // похибки PDR, що росте з відстанню) — це властивість самого гіроскопа,
        // а не ходьби.
        val varH = uncertaintyRad * uncertaintyRad + GYRO_RANDOM_WALK_VAR_PER_S * dtS
        uncertaintyRad = sqrt(varH).coerceAtMost(PI)

        decayTrust(elapsedMs)
    }

    /**
     * Магнітний курс і модуль поля.
     *
     * @param headingRad магнітний азимут з компенсацією нахилу
     * @param fieldMagnitudeUt модуль вектора поля, мкТл — земне поле 25–65
     */
    fun onMagnetic(elapsedMs: Long, headingRad: Double, fieldMagnitudeUt: Double) {
        if (!headingRad.isFinite() || !fieldMagnitudeUt.isFinite()) return
        // Поза цим діапазоном це вже не вимір поля Землі, а несправність або
        // магніт чохла впритул до сенсора.
        if (fieldMagnitudeUt < MIN_FIELD_UT || fieldMagnitudeUt > MAX_FIELD_UT) {
            anomaly = true
            magTrust = 0.0
            quietSinceMs = 0L
            return
        }

        val dtS = if (lastMagMs == 0L) 0.0 else (elapsedMs - lastMagMs) / 1000.0
        val rateUtPerS = if (dtS > 0.0 && lastFieldUt.isFinite()) {
            abs(fieldMagnitudeUt - lastFieldUt) / dtS
        } else {
            0.0
        }
        lastMagMs = elapsedMs
        lastFieldUt = fieldMagnitudeUt

        if (refFieldUt.isNaN()) refFieldUt = fieldMagnitudeUt

        val deviation = abs(fieldMagnitudeUt - refFieldUt)
        val relative = deviation / refFieldUt
        anomaly = deviation > ANOMALY_ABS_UT || relative > ANOMALY_REL || rateUtPerS > ANOMALY_RATE_UT_PER_S

        if (anomaly) {
            // Довіра падає МИТТЄВО, а відновлюється повільно. Несиметрично
            // навмисне: ціна одного хибно прийнятого курсу біля вагона — це
            // повернута траєкторія, ціна кількох зайвих секунд недовіри — трохи
            // більший дрейф гіроскопа, який суміщення потім і так виправить.
            magTrust = 0.0
            quietSinceMs = 0L
            return
        }

        // Опорне значення оновлюємо лише в спокої: інакше воно «доганяє» саму
        // аномалію і за півхвилини оголошує 90 мкТл нормою.
        val k = if (dtS > 0.0 && dtS < MAX_MAG_GAP_S) 1.0 - exp(-dtS / TAU_REF_S) else 0.0
        refFieldUt += k * (fieldMagnitudeUt - refFieldUt)

        if (quietSinceMs == 0L) quietSinceMs = elapsedMs
        if (elapsedMs - quietSinceMs < RECOVER_DELAY_MS) return

        val kTrust = if (dtS > 0.0 && dtS < MAX_MAG_GAP_S) 1.0 - exp(-dtS / TAU_TRUST_S) else 0.0
        magTrust = (magTrust + kTrust * (1.0 - magTrust)).coerceIn(0.0, 1.0)

        if (!haveHeading) {
            this.headingRad = wrap(headingRad)
            uncertaintyRad = MAG_SIGMA_RAD
            haveHeading = true
            return
        }

        val w = MAG_GAIN * magTrust
        if (w <= 0.0) return
        // Комплементарне зведення по найкоротшій дузі: пряме усереднення двох
        // азимутів навколо 0/2π дало б курс, протилежний обом.
        this.headingRad = wrap(this.headingRad + w * signedDelta(this.headingRad, headingRad))
        val varH = (1.0 - w) * (1.0 - w) * uncertaintyRad * uncertaintyRad +
            w * w * MAG_SIGMA_RAD * MAG_SIGMA_RAD
        uncertaintyRad = sqrt(varH).coerceAtLeast(MIN_UNCERTAINTY_RAD)
    }

    fun reset() {
        headingRad = 0.0
        uncertaintyRad = INITIAL_UNCERTAINTY_RAD
        magTrust = 0.0
        anomaly = false
        haveHeading = false
        lastGyroMs = 0L
        refFieldUt = Double.NaN
        lastFieldUt = Double.NaN
        lastMagMs = 0L
        quietSinceMs = 0L
    }

    /**
     * Магнітометр замовк (потік урвався або аномалія триває) — довіра має
     * згасати сама, без нових вимірів, інакше вона застрягне на старому рівні.
     */
    private fun decayTrust(elapsedMs: Long) {
        if (lastMagMs == 0L) return
        if (elapsedMs - lastMagMs > MAG_STALE_MS) magTrust = 0.0
    }

    private companion object {
        const val TAU = 2.0 * PI

        /** (0.02 рад/с)² — за хвилину без магнітометра σ ≈ 8.9°, що чесно. */
        const val GYRO_RANDOM_WALK_VAR_PER_S = 4.0e-4

        const val MAX_GYRO_GAP_S = 1.0
        const val MAX_MAG_GAP_S = 2.0
        const val MAG_STALE_MS = 3_000L

        const val INITIAL_UNCERTAINTY_RAD = 1.0
        const val MIN_UNCERTAINTY_RAD = 0.02

        /** Навіть у чистому полі компас телефона дає ±10°. */
        const val MAG_SIGMA_RAD = 0.17

        /** Частка невʼязки, що приймається за один вимір при повній довірі. */
        const val MAG_GAIN = 0.06

        const val MIN_FIELD_UT = 10.0
        const val MAX_FIELD_UT = 150.0

        const val ANOMALY_ABS_UT = 12.0
        const val ANOMALY_REL = 0.22
        const val ANOMALY_RATE_UT_PER_S = 25.0

        const val TAU_REF_S = 20.0
        const val TAU_TRUST_S = 6.0

        /** Поле має побути спокійним, перш ніж довіра почне зростати. */
        const val RECOVER_DELAY_MS = 2_000L

        fun wrap(r: Double): Double = ((r % TAU) + TAU) % TAU

        fun signedDelta(from: Double, to: Double): Double {
            var d = (to - from + PI) % TAU
            if (d < 0) d += TAU
            return d - PI
        }
    }
}
