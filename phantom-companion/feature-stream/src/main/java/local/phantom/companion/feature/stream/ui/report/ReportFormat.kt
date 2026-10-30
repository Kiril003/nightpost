package local.phantom.companion.feature.stream.ui.report

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Форматування чисел звіту — українською і **детерміновано**.
 *
 * ## Чому не `String.format` з локаллю пристрою
 *
 * Звіт — це документ, який оператор може переслати, зберегти й порівняти з
 * учорашнім. Якщо той самий агрегат виглядає «38.4» на одному пристрої і
 * «38,4» на іншому, документи перестають бути порівнюваними, а тести —
 * можливими. Тому формат тут не залежить від системної локалі взагалі:
 * кома як десятковий роздільник, вузький нерозривний пробіл (U+202F) між
 * розрядами — українська типографська норма, зашита в код і пришпилена
 * тестом.
 *
 * ## Чому вузький нерозривний, а не звичайний пробіл
 *
 * «12 270 км» зі звичайним пробілом рветься по переносу рядка, і оператор
 * читає «12» в кінці одного рядка і «270 км» на початку наступного. У звіті
 * про підміну GPS це різниця між «дванадцять тисяч кілометрів» і нічим.
 *
 * ## Чому знак не дорівнює оцінці
 *
 * [deltaTone] бере окремий прапорець «зростання — це добре». Плюс до
 * економії й плюс до витрати мають протилежний сенс, і жоден парсер знака
 * цього не вгадає.
 */
object ReportFormat {

    /** Вузький нерозривний пробіл — роздільник розрядів. */
    const val THIN_NBSP = ' '

    /** Справжній мінус (U+2212), а не дефіс: у моноширинному ряду цифр
     *  дефіс читається як переніс, мінус — як знак. */
    const val MINUS = '−'

    /**
     * Число з [decimals] знаками після коми, з розрядами.
     * Округлення — «половина вгору», щоб суми в таблиці збігалися з
     * підсумком, порахованим окремо.
     */
    fun number(value: Double, decimals: Int = 0): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        val negative = value < 0
        val scale = pow10(decimals)
        val scaled = abs(value) * scale
        val rounded = scaled.roundToLong()
        val whole = rounded / scale.toLong()
        val frac = rounded % scale.toLong()

        val head = groupDigits(whole.toString())
        val body = if (decimals > 0) "$head,${frac.toString().padStart(decimals, '0')}" else head
        return if (negative && rounded != 0L) "$MINUS$body" else body
    }

    fun number(value: Float, decimals: Int = 0): String = number(value.toDouble(), decimals)

    /** Число зі своєю одиницею, розділені нерозривним пробілом. */
    fun withUnit(value: Double, unit: String, decimals: Int = 0): String =
        if (unit.isBlank()) number(value, decimals) else "${number(value, decimals)}$THIN_NBSP$unit"

    /**
     * Тривалість у людському вигляді: «4 год 12 хв», «38 хв», «52 с».
     * Секунди показуються лише коли хвилин нема — у звіті про тиждень
     * «12 год 03 хв 41 с» це точність, яку ніхто не просив.
     */
    fun duration(seconds: Long): String {
        if (seconds < 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "$h${THIN_NBSP}год $m${THIN_NBSP}хв"
            m > 0 -> "$m${THIN_NBSP}хв"
            else -> "$s${THIN_NBSP}с"
        }
    }

    /** Годинник з хвилин від опівночі: «08:26». Витримує перехід за добу. */
    fun clock(minutesFromMidnight: Int): String {
        val m = ((minutesFromMidnight % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** Відсоток зі знаком — для дельт. Нуль лишається без знака. */
    fun signedPercent(value: Double, decimals: Int = 0): String = when {
        value > 0 -> "+${number(value, decimals)}${THIN_NBSP}%"
        value < 0 -> "${number(value, decimals)}${THIN_NBSP}%"
        else -> "0${THIN_NBSP}%"
    }

    /** Будь-яка величина зі знаком: «+2,1 км», «−0,4 л/100 км». */
    fun signed(value: Double, unit: String = "", decimals: Int = 1): String {
        val core = withUnit(abs(value), unit, decimals)
        return when {
            value > 0 -> "+$core"
            value < 0 -> "$MINUS$core"
            else -> core
        }
    }

    /**
     * Тон дельти. [growthIsGood] = чи є зростання позитивною новиною.
     * Нульова дельта лишається нейтральною: «без змін» — це не досягнення.
     */
    fun deltaTone(value: Double, growthIsGood: Boolean): ReportTone = when {
        value == 0.0 -> ReportTone.NEUTRAL
        (value > 0) == growthIsGood -> ReportTone.GOOD
        else -> ReportTone.WARN
    }

    /**
     * Українська форма множини: 1 поїздка · 2 поїздки · 5 поїздок.
     * Правило — стандартне словʼянське one/few/many, включно з винятками
     * 11–14, які інакше отримали б форму «11 поїздка».
     */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val abs = abs(n)
        val mod10 = abs % 10
        val mod100 = abs % 100
        val form = when {
            mod10 == 1L && mod100 != 11L -> one
            mod10 in 2..4 && mod100 !in 12..14 -> few
            else -> many
        }
        return "$n$THIN_NBSP$form"
    }

    /** Гроші у гривні. Копійки показуємо лише коли сума менша за тисячу. */
    fun money(uah: Double): String =
        if (abs(uah) < 1000) "${number(uah, 2)}${THIN_NBSP}₴" else "${number(uah, 0)}${THIN_NBSP}₴"

    /** Мілісекунди у компактний вигляд для трасування агента. */
    fun millis(ms: Long): String = when {
        ms < 1000 -> "$ms${THIN_NBSP}мс"
        ms < 60_000 -> "${number(ms / 1000.0, 1)}${THIN_NBSP}с"
        else -> duration(ms / 1000)
    }

    /**
     * Компактна форма великих чисел для щільних місць (осі, легенди):
     * 1 240 → «1,2 тис», 12 270 → «12 тис». Повне число лишається в
     * таблицях, де є місце його прочитати.
     */
    fun compact(value: Double): String = when {
        abs(value) >= 1_000_000 -> "${number(value / 1_000_000, 1)}${THIN_NBSP}млн"
        abs(value) >= 10_000 -> "${number(value / 1000, 0)}${THIN_NBSP}тис"
        abs(value) >= 1_000 -> "${number(value / 1000, 1)}${THIN_NBSP}тис"
        abs(value) >= 10 -> number(value, 0)
        else -> number(value, 1)
    }

    /** Частка 0..1 у відсотки без знака: «34 %». */
    fun share(fraction: Double): String =
        "${number((fraction * 100).coerceIn(0.0, 100.0), 0)}${THIN_NBSP}%"

    private fun groupDigits(digits: String): String {
        // До пʼяти знаків не розділяємо: «1240» читається з одного погляду,
        // а «1 240» у щільній колонці лише додає вертикальних щілин.
        if (digits.length <= 4) return digits
        val sb = StringBuilder()
        var count = 0
        for (i in digits.lastIndex downTo 0) {
            sb.append(digits[i])
            count++
            if (count % 3 == 0 && i > 0) sb.append(THIN_NBSP)
        }
        return sb.reverse().toString()
    }

    private fun pow10(n: Int): Double {
        var r = 1.0
        repeat(n.coerceIn(0, 9)) { r *= 10 }
        return r
    }
}

/** Відсоток як ціле для підписів, де кома тільки заважає. */
internal fun Float.asPercentInt(): Int = (this * 100f).roundToInt()
