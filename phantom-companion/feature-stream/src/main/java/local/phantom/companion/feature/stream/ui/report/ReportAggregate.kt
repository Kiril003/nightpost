package local.phantom.companion.feature.stream.ui.report

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Агрегація та геометрія звіту — **уся математика, що не малює**.
 *
 * ## Чому окремо від рендера
 *
 * Все, що тут лежить, визначає, яке число оператор побачить і де саме
 * пройде лінія. Всередині `Canvas` це не перевірити: `DrawScope` не існує
 * поза кадром, а `@Composable` не існує поза Compose. Винесене сюди —
 * звичайні функції над списками, які тест може прогнати сотнею випадків,
 * включно з тими, що ламають наївний код: порожній ряд, усі значення
 * однакові (діапазон нуль → ділення на нуль), одна точка, відʼємні частки.
 *
 * ## Правило нульового діапазону
 *
 * Рівний ряд (усі значення однакові) — не помилка даних, а звичайний
 * тиждень без змін. Наївне `(v - min) / (max - min)` дає NaN і порожню
 * діаграму. [normalize] у цьому випадку кладе ряд на середину поля: це
 * чесно показує «нічого не змінювалось», а не «даних немає».
 */
object ReportAggregate {

    /* ── Базові агрегати ─────────────────────────────────────────── */

    fun total(values: List<Float>): Double = values.sumOf { it.toDouble() }

    fun average(values: List<Float>): Double =
        if (values.isEmpty()) 0.0 else total(values) / values.size

    /**
     * Дельта у відсотках. [previous] == 0 не дає «нескінченне зростання»:
     * ділити на нуль тут означає збрехати впевненим числом, тому
     * повертаємо `null`, і рендер покаже «—».
     */
    fun deltaPercent(current: Double, previous: Double): Double? =
        if (previous == 0.0) null else (current - previous) / abs(previous) * 100.0

    /** Медіана — стійка до одного викиду, на відміну від середнього. */
    fun median(values: List<Float>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid].toDouble() else (s[mid - 1] + s[mid]) / 2.0
    }

    /**
     * Перцентиль методом найближчого рангу. Використовується для «95-й
     * перцентиль швидкості» — числа, яке описує манеру їзди краще за
     * максимум (максимум — це один спуск із гори).
     */
    fun percentile(values: List<Float>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val rank = (p.coerceIn(0.0, 100.0) / 100.0 * s.size)
        val idx = (kotlin.math.ceil(rank).toInt() - 1).coerceIn(0, s.lastIndex)
        return s[idx].toDouble()
    }

    /* ── Геометрія рядів ─────────────────────────────────────────── */

    /**
     * Ряд у [0..1]. Рівний ряд лягає на 0.5 (див. правило нульового
     * діапазону в доку класу).
     */
    fun normalize(values: List<Float>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val lo = values.min()
        val hi = values.max()
        if (hi - lo <= EPS) return values.map { 0.5f }
        return values.map { (it - lo) / (hi - lo) }
    }

    /**
     * Спільна нормалізація кількох рядів: у багаторядній діаграмі кожен
     * ряд, нормалізований окремо, малює красиву неправду — три криві з
     * різними масштабами виглядають як одна історія. Спільний діапазон —
     * єдиний чесний варіант.
     */
    fun normalizeTogether(series: List<List<Float>>): List<List<Float>> {
        val flat = series.flatten()
        if (flat.isEmpty()) return series.map { emptyList() }
        val lo = flat.min()
        val hi = flat.max()
        if (hi - lo <= EPS) return series.map { row -> row.map { 0.5f } }
        return series.map { row -> row.map { (it - lo) / (hi - lo) } }
    }

    /** Межі, які підпишуться на осі: (min, max) по всіх рядах. */
    fun bounds(series: List<List<Float>>): Pair<Float, Float> {
        val flat = series.flatten()
        if (flat.isEmpty()) return 0f to 0f
        return flat.min() to flat.max()
    }

    /**
     * Точки спарклайна у координатах поля. Один вимір лишається на місці:
     * ряд із однієї точки — це крапка посередині, а не порожнеча.
     */
    fun sparkPoints(values: List<Float>, width: Float, height: Float): List<Pair<Float, Float>> {
        if (values.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        if (values.size == 1) return listOf(width / 2f to height / 2f)
        val norm = normalize(values)
        val step = width / (values.size - 1)
        return norm.mapIndexed { i, v -> i * step to height - v * height }
    }

    /* ── Похідні структури блоків ────────────────────────────────── */

    /** Одна корзина гістограми. [from]/[to] — межі, [count] — влучань. */
    data class Bin(val from: Float, val to: Float, val count: Int)

    /**
     * Розподіл значень по [binCount] рівних корзинах. Верхня межа
     * включна для останньої корзини — інакше максимум завжди випадає з
     * гістограми, і читач бачить розподіл без свого найцікавішого хвоста.
     */
    fun histogram(values: List<Float>, binCount: Int): List<Bin> {
        if (values.isEmpty() || binCount <= 0) return emptyList()
        val lo = values.min()
        val hi = values.max()
        if (hi - lo <= EPS) return listOf(Bin(lo, hi, values.size))
        val step = (hi - lo) / binCount
        val counts = IntArray(binCount)
        values.forEach { v ->
            val idx = ((v - lo) / step).toInt().coerceIn(0, binCount - 1)
            counts[idx]++
        }
        return (0 until binCount).map { i ->
            Bin(lo + step * i, lo + step * (i + 1), counts[i])
        }
    }

    /** Прогін водоспаду: від якого рівня до якого йде стовпець. */
    data class Run(val label: String, val from: Float, val to: Float, val isTotal: Boolean)

    /**
     * Розкладає кроки водоспаду у пари «від → до». Підсумковий крок
     * малюється від нуля до накопиченого рівня, а не поверх попереднього:
     * підсумок — це не ще одна зміна, це стан.
     */
    fun waterfall(steps: List<WaterfallStep>): List<Run> {
        var acc = 0f
        return steps.map { s ->
            if (s.total) {
                Run(s.label, 0f, acc, true)
            } else {
                val from = acc
                acc += s.delta
                Run(s.label, from, acc, false)
            }
        }
    }

    /** Частки кільця, нормалізовані до одиниці; нульова сума → порожньо. */
    fun shares(slices: List<Slice>): List<Float> {
        val sum = slices.sumOf { it.value.toDouble() }
        if (sum <= 0.0) return slices.map { 0f }
        return slices.map { (it.value / sum).toFloat() }
    }

    /** Рейтинг за спаданням; частка рахується від лідера, а не від суми —
     *  бо читач порівнює рядки між собою, а не з невидимим цілим. */
    fun ranked(entries: List<RankEntry>): List<Pair<RankEntry, Float>> {
        if (entries.isEmpty()) return emptyList()
        val sorted = entries.sortedByDescending { it.value }
        val top = sorted.first().value
        return sorted.map { it to if (top <= 0f) 0f else (it.value / top) }
    }

    /**
     * Нормалізація теплової матриці до [0..1] по всій матриці — не по
     * рядках. Порядковість «понеділок гарячіший за вівторок» зникає, якщо
     * кожен рядок нормалізувати окремо.
     */
    fun heat(values: List<List<Float>>): List<List<Float>> {
        val flat = values.flatten()
        if (flat.isEmpty()) return values
        val hi = flat.max()
        if (hi <= EPS) return values.map { row -> row.map { 0f } }
        return values.map { row -> row.map { (it / hi).coerceIn(0f, 1f) } }
    }

    /** Стан швидкості щодо обмеження — три смуги, не градієнт. */
    enum class SpeedBand { UNDER, NEAR, OVER }

    /**
     * Смуга швидкості. «Біля межі» — це ±5 км/год: у цьому коридорі
     * похибка спідометра і GPS більша за саме відхилення, і фарбувати
     * його в тривожний колір означає брехати на власному шумі.
     */
    fun speedBand(kmh: Float, limitKmh: Float): SpeedBand = when {
        limitKmh <= 0f -> SpeedBand.UNDER
        kmh > limitKmh + SPEED_TOLERANCE -> SpeedBand.OVER
        kmh >= limitKmh - SPEED_TOLERANCE -> SpeedBand.NEAR
        else -> SpeedBand.UNDER
    }

    /**
     * Привʼязка географічних точок до поля рендера зі збереженням
     * пропорції. Без збереження пропорції маршрут «розтягується» під
     * форму картки, і кут повороту на карті перестає відповідати
     * реальному — для звіту про підміну GPS це прямо руйнує доказ.
     *
     * Довгота стискається на `cos(lat)`, інакше в широтах Києва (50°)
     * схід-захід виглядає у півтора раза довшим, ніж є.
     */
    fun project(
        points: List<GeoPoint>,
        width: Float,
        height: Float,
        padding: Float = 0f,
    ): List<Pair<Float, Float>> {
        if (points.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val latRad = Math.toRadians(points.map { it.lat }.average())
        val kx = kotlin.math.cos(latRad).coerceAtLeast(0.1)

        val xs = points.map { it.lon * kx }
        val ys = points.map { it.lat }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val spanX = max(maxX - minX, EPS_D)
        val spanY = max(maxY - minY, EPS_D)

        val innerW = (width - padding * 2).coerceAtLeast(1f)
        val innerH = (height - padding * 2).coerceAtLeast(1f)
        val scale = min(innerW / spanX, innerH / spanY)
        val drawnW = (spanX * scale).toFloat()
        val drawnH = (spanY * scale).toFloat()
        val offX = padding + (innerW - drawnW) / 2f
        val offY = padding + (innerH - drawnH) / 2f

        return points.indices.map { i ->
            val x = offX + ((xs[i] - minX) * scale).toFloat()
            // Екран росте вниз, широта — вгору.
            val y = offY + drawnH - ((ys[i] - minY) * scale).toFloat()
            x to y
        }
    }

    /**
     * Відстань між двома точками, км (гаверсинус). Потрібна саме для
     * звіту про підміну: «розбіжність 12 270 км» — це не метафора, це
     * порахована величина між супутниковим і мережевим рішенням.
     */
    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(la1) * kotlin.math.cos(la2) *
            kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h).coerceIn(0.0, 1.0))
    }

    private const val EPS = 1e-6f
    private const val EPS_D = 1e-9
    private const val SPEED_TOLERANCE = 5f
}
