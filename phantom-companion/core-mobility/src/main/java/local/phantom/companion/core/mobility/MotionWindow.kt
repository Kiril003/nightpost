package local.phantom.companion.core.mobility

import kotlin.math.sqrt

/**
 * Приведення сирого потоку акселерометра до стабільної сітки і нарізка на вікна.
 *
 * **Чому 10 Гц.** Android не гарантує рівного кроку між подіями сенсора: заявлені
 * `SENSOR_DELAY_*` — це побажання, а не контракт, і реальний потік «дихає» від
 * навантаження системи. Класифікатор над нерівномірною сіткою неможливо чесно
 * перевірити: та сама поїздка на іншому телефоні дасть інший спектр. Тому вхід
 * інтерполюється на рівні 10 Гц.
 *
 * Саме 10, а не 50 чи 100 — це свідомий компроміс, а не економія на змісті:
 *  • тримати акселерометр на 100 Гц (а тим паче GPS) заради класифікації означає
 *    зʼїсти батарею за півдня — а режим руху потрібен цілий день, фоном;
 *  • смуги вище 5 Гц (межа Найквіста для 10 Гц) для розрізнення *режимів
 *    пересування* майже нічого не додають: каденс ходьби 1.4–2.3 Гц, бігу — до
 *    3.5 Гц, коливання підвіски авто — нижче 2 Гц, вібрація велосипедної рами —
 *    3–5 Гц. Усе, що вище, — це дрібна фактура покриття, яка розрізняє асфальт і
 *    бруківку, а не автомобіль і велосипед.
 *
 * **Чому 18 секунд.** Вікно має вміщати кілька повних циклів найповільнішого
 * корисного явища: повний цикл «розгін — рух — гальмування» тролейбуса між
 * зупинками не влазить ні в 5, ні в 10 секунд. З іншого боку, довше вікно
 * розмиває межу поїздки. 18 с при перекритті 50 % дає новий вердикт кожні 9 с.
 */
class MotionWindower(
    private val sampleHz: Int = TARGET_HZ,
    private val windowSec: Int = WINDOW_SEC,
    private val hopSec: Int = HOP_SEC,
    private val maxInterpolationGapMs: Long = MAX_INTERPOLATION_GAP_MS,
    private val resetGapMs: Long = RESET_GAP_MS,
) {

    private val windowLen = sampleHz * windowSec
    private val hopLen = sampleHz * hopSec

    /** Один вузол рівномірної сітки. */
    private class Grid(
        val tMs: Long,
        val ax: Double, val ay: Double, val az: Double,
        val gx: Double, val gy: Double, val gz: Double,
        val hasGyro: Boolean,
        /** Вузол отримано інтерполяцією через прогалину, довшу за дозволену. */
        val overGap: Boolean,
    )

    private val buffer = ArrayDeque<Grid>()
    private var prev: MotionSample? = null
    private var gridOriginMs = 0L
    private var gridIndex = 0L
    private var sinceEmit = 0
    private var emittedOnce = false

    /** Скинути стан: сітка почнеться заново з наступного зразка. */
    fun reset() {
        buffer.clear()
        prev = null
        gridOriginMs = 0L
        gridIndex = 0L
        sinceEmit = 0
        emittedOnce = false
    }

    /**
     * Прийняти один сирий зразок. Повертає вікна, що завершилися саме на ньому
     * (звичайно порожній список; на межі вікна — рівно одне).
     *
     * Оброблювані патології входу, кожна свідомо:
     *  • NaN/Inf — зразок відкидається цілком і навіть не стає опорою інтерполяції:
     *    один зіпсований вузол інакше протікає у два сусідні вікна;
     *  • годинник пішов назад — сітка не має сенсу, стан скидається;
     *  • прогалина довша за [resetGapMs] — це не «повільний сенсор», а перерва
     *    в спостереженні: інтерполювати крізь неї означало б вигадати рух;
     *  • прогалина довша за [maxInterpolationGapMs] — вузли позначаються, і вікно
     *    несе чесний [MotionWindow.gapFraction], за яким класифікатор мовчить.
     */
    fun offer(sample: MotionSample): List<MotionWindow> {
        if (!sample.isFinite) return emptyList()
        val p = prev
        if (p == null) {
            prev = sample
            gridOriginMs = sample.elapsedMs
            gridIndex = 0
            return emptyList()
        }
        if (sample.elapsedMs < p.elapsedMs) {
            reset()
            prev = sample
            gridOriginMs = sample.elapsedMs
            return emptyList()
        }
        val dt = sample.elapsedMs - p.elapsedMs
        if (dt > resetGapMs) {
            reset()
            prev = sample
            gridOriginMs = sample.elapsedMs
            return emptyList()
        }

        val out = ArrayList<MotionWindow>(1)
        val overGap = dt > maxInterpolationGapMs
        while (nextGridMs() <= sample.elapsedMs) {
            val t = if (dt <= 0L) 1.0 else (nextGridMs() - p.elapsedMs).toDouble() / dt
            push(interpolate(p, sample, t, nextGridMs(), overGap))
            gridIndex++
            emitIfDue()?.let { out += it }
        }
        prev = sample
        return out
    }

    private fun nextGridMs(): Long = gridOriginMs + (gridIndex * 1000L) / sampleHz

    private fun interpolate(a: MotionSample, b: MotionSample, t: Double, tMs: Long, overGap: Boolean) = Grid(
        tMs = tMs,
        ax = a.ax + (b.ax - a.ax) * t,
        ay = a.ay + (b.ay - a.ay) * t,
        az = a.az + (b.az - a.az) * t,
        gx = a.gyroX + (b.gyroX - a.gyroX) * t,
        gy = a.gyroY + (b.gyroY - a.gyroY) * t,
        gz = a.gyroZ + (b.gyroZ - a.gyroZ) * t,
        hasGyro = a.hasGyro && b.hasGyro,
        overGap = overGap,
    )

    private fun push(g: Grid) {
        buffer.addLast(g)
        while (buffer.size > windowLen) buffer.removeFirst()
        sinceEmit++
    }

    private fun emitIfDue(): MotionWindow? {
        if (buffer.size < windowLen) return null
        if (emittedOnce && sinceEmit < hopLen) return null
        sinceEmit = 0
        emittedOnce = true
        return build()
    }

    /**
     * Зібрати вікно, розклавши прискорення на вертикальну і горизонтальну складові.
     *
     * Напрям «вниз» береться як середній вектор прискорення за вікном: на 18 с
     * будь-якого реального руху інтеграл власного прискорення тіла близький до
     * нуля, тож середнє — це тяжіння. Це дешевше і надійніше за орієнтацію з
     * фільтра, і головне — не залежить від того, як телефон лежить у кишені.
     */
    private fun build(): MotionWindow {
        val n = buffer.size
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (g in buffer) { sx += g.ax; sy += g.ay; sz += g.az }
        val mx = sx / n; val my = sy / n; val mz = sz / n
        val gNorm = sqrt(mx * mx + my * my + mz * mz)
        val ux: Double; val uy: Double; val uz: Double
        if (gNorm > 1e-6) { ux = mx / gNorm; uy = my / gNorm; uz = mz / gNorm } else { ux = 0.0; uy = 0.0; uz = 1.0 }

        val mag = DoubleArray(n)
        val vert = DoubleArray(n)
        val horiz = DoubleArray(n)
        val hasGyro = buffer.all { it.hasGyro }
        val gyro = if (hasGyro) DoubleArray(n) else DoubleArray(0)
        var gaps = 0
        var i = 0
        for (g in buffer) {
            mag[i] = sqrt(g.ax * g.ax + g.ay * g.ay + g.az * g.az)
            val along = g.ax * ux + g.ay * uy + g.az * uz
            // Знакова вертикальна складова без тяжіння: саме її амплітуда
            // відрізняє крок від коливання підвіски.
            vert[i] = along - gNorm
            val hx = g.ax - along * ux
            val hy = g.ay - along * uy
            val hz = g.az - along * uz
            horiz[i] = sqrt(hx * hx + hy * hy + hz * hz)
            if (hasGyro) gyro[i] = sqrt(g.gx * g.gx + g.gy * g.gy + g.gz * g.gz)
            if (g.overGap) gaps++
            i++
        }
        return MotionWindow(
            startElapsedMs = buffer.first().tMs,
            sampleHz = sampleHz.toDouble(),
            accelMag = mag,
            vertical = vert,
            horizontal = horiz,
            gyroMag = gyro,
            gravityMps2 = gNorm,
            gapFraction = gaps.toDouble() / n,
        )
    }

    companion object {
        /** Цільова частота сітки, Гц. Обґрунтування — у KDoc класу. */
        const val TARGET_HZ = 10

        /** Довжина вікна, с. */
        const val WINDOW_SEC = 18

        /** Крок вікна, с (перекриття 50 %). Новий вердикт кожні 9 с. */
        const val HOP_SEC = 9

        /**
         * Найбільша прогалина, крізь яку інтерполяція ще вважається чесною, мс.
         * 400 мс — це чотири пропущені вузли: людина за цей час не встигає
         * змінити фазу кроку більше ніж на пів циклу.
         */
        const val MAX_INTERPOLATION_GAP_MS = 400L

        /**
         * Прогалина, після якої сітка починається заново, мс. Довша пауза — це вже
         * не сенсор, а сон системи або перереєстрація слухача.
         */
        const val RESET_GAP_MS = 3_000L
    }
}

/**
 * Один сирий зразок IMU на монотонному годиннику.
 *
 * Гіроскоп необовʼязковий: на частині пристроїв його немає взагалі, і відсутність
 * позначається [Double.NaN], а не нулем — нуль означав би «виміряно нерухомість».
 *
 * @param elapsedMs монотонний годинник (`SystemClock.elapsedRealtime`)
 * @param ax прискорення по осях пристрою разом із тяжінням, м/с²
 */
data class MotionSample(
    val elapsedMs: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val gyroX: Double = Double.NaN,
    val gyroY: Double = Double.NaN,
    val gyroZ: Double = Double.NaN,
) {
    /** Гіроскоп присутній у цьому зразку. */
    val hasGyro: Boolean get() = !gyroX.isNaN() && !gyroY.isNaN() && !gyroZ.isNaN()

    /** Акселерометр дав кінцеві числа. Зразок без цього до сітки не потрапляє. */
    val isFinite: Boolean get() = ax.isFinite() && ay.isFinite() && az.isFinite()

    /** Модуль повного прискорення, м/с². */
    val magnitudeMps2: Double get() = sqrt(ax * ax + ay * ay + az * az)

    /** Модуль кутової швидкості, рад/с; [Double.NaN] без гіроскопа. */
    val gyroMagnitudeRadPerS: Double
        get() = if (hasGyro) sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ) else Double.NaN
}

/**
 * Готове вікно на рівномірній сітці.
 *
 * Не `data class` свідомо: усередині масиви, і згенерований `equals` порівнював би
 * їх за посиланням — тихий баг у будь-якому кеші чи множині.
 *
 * @param vertical знакова проєкція на напрям тяжіння з відрахованим g, м/с²
 * @param horizontal модуль складової, ортогональної до тяжіння, м/с²
 * @param gyroMag модуль кутової швидкості, рад/с; порожній масив, якщо гіроскопа не було
 * @param gravityMps2 оцінене тяжіння за вікном — діагностика: значення далеко від
 *   9.8 означає, що вікно захопило тривале власне прискорення (розгін літака)
 * @param gapFraction частка вузлів, отриманих інтерполяцією через задовгу прогалину
 */
class MotionWindow(
    val startElapsedMs: Long,
    val sampleHz: Double,
    val accelMag: DoubleArray,
    val vertical: DoubleArray,
    val horizontal: DoubleArray,
    val gyroMag: DoubleArray,
    val gravityMps2: Double,
    val gapFraction: Double,
) {
    /** Кількість вузлів. */
    val size: Int get() = accelMag.size

    /** Тривалість вікна, мс. */
    val durationMs: Long get() = if (size == 0) 0L else ((size - 1) * 1000L / sampleHz).toLong()

    /** Час останнього вузла на монотонному годиннику. */
    val endElapsedMs: Long get() = startElapsedMs + durationMs

    /** Гіроскоп доступний для всього вікна. */
    val hasGyro: Boolean get() = gyroMag.isNotEmpty()

    /** Середній модуль горизонтальної складової, м/с². */
    val horizontalRms: Double get() = rms(horizontal)

    private fun rms(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        var s = 0.0
        for (v in a) s += v * v
        return sqrt(s / a.size)
    }

    /** Відстань між першим і останнім вузлом у часі — для діагностики сітки. */
    fun gridStepMs(): Double = if (size < 2) 0.0 else durationMs.toDouble() / (size - 1)

    /** Найбільший розмах модуля прискорення, м/с². */
    fun accelRange(): Double {
        if (accelMag.isEmpty()) return 0.0
        var lo = accelMag[0]
        var hi = accelMag[0]
        for (v in accelMag) { if (v < lo) lo = v; if (v > hi) hi = v }
        return hi - lo
    }
}
