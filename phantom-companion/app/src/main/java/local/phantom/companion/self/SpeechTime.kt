package local.phantom.companion.self

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object SpeechTime {
    data class Hit(
        val atMs: Long,
        val remaining: String,
        val whenLine: Line?,
        val clockSpoken: Boolean = false,
    )

    private const val DEFAULT_HOUR = 9
    private const val MAX_RELATIVE_MINUTES = 1440L

    private val APOSTROPHES = Regex("['’`´]")
    private val MULTI_SPACE = Regex("\\s{2,}")

    private fun norm(text: String): String = text.replace(APOSTROPHES, "ʼ").trim()
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val L_BORDER = "(?:^|\\s)"
    private const val R_BORDER = "(?=\\s|$|[.,;:!?](\\s|$))"

    private val EN_REL_HALF_HOUR_RX = rx("${L_BORDER}in\\s+(?:a\\s+)?half\\s+(?:an\\s+)?hour$R_BORDER")
    private val EN_REL_AN_HOUR_RX = rx("${L_BORDER}in\\s+an?\\s+hour$R_BORDER")
    private val EN_REL_NUM_RX = rx("${L_BORDER}in\\s+(\\d{1,5})\\s*([\\p{L}]+)$R_BORDER")

    private val UK_REL_HALF_HOUR_RX = rx("${L_BORDER}через\\s+(?:пів\\s*години|півгодини)$R_BORDER")
    private val UK_REL_AN_HOUR_RX = rx("${L_BORDER}через\\s+годину$R_BORDER")
    private val UK_REL_NUM_RX = rx("${L_BORDER}через\\s+(\\d{1,5})\\s*([\\p{L}.]+)$R_BORDER")

    private val EN_CLOCK_RX = rx("${L_BORDER}(?:at|by)\\s*(\\d{1,2})(?::(\\d+))?(?:\\s*(am|pm|a\\.m\\.|p\\.m\\.))?$R_BORDER")
    private val UK_CLOCK_RX = rx("${L_BORDER}(?:о|об)\\s*(\\d{1,2})(?::(\\d+))?(?:\\s*(ранку|вечора|дня|ночі))?$R_BORDER")

    private fun dayRx(uk: String, en: String) = rx("${L_BORDER}(?:$uk|$en)$R_BORDER")
    private val DAY_AFTER_TOMORROW_RX = dayRx("післязавтра", "(?:the\\s+)?day\\s+after\\s+tomorrow")
    private val TOMORROW_RX = dayRx("завтра", "tomorrow")
    private val TODAY_RX = dayRx("сьогодні(?:\\s+ввечері|\\s+зранку)?", "today|tonight")

    private val UK_WEEKDAYS = mapOf(
        "понеділок" to DayOfWeek.MONDAY,
        "вівторок" to DayOfWeek.TUESDAY,
        "середу" to DayOfWeek.WEDNESDAY,
        "середа" to DayOfWeek.WEDNESDAY,
        "четвер" to DayOfWeek.THURSDAY,
        "пʼятницю" to DayOfWeek.FRIDAY,
        "пятницю" to DayOfWeek.FRIDAY,
        "пʼятниця" to DayOfWeek.FRIDAY,
        "суботу" to DayOfWeek.SATURDAY,
        "субота" to DayOfWeek.SATURDAY,
        "неділю" to DayOfWeek.SUNDAY,
        "неділя" to DayOfWeek.SUNDAY,
    )

    private val EN_WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    private val UK_DOW_RX = rx("${L_BORDER}(?:у|в)\\s+(понеділок|вівторок|середу|середа|четвер|пʼятницю|пятницю|пʼятниця|суботу|субота|неділю|неділя)$R_BORDER")
    private val EN_DOW_RX = rx("${L_BORDER}on\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)$R_BORDER")

    private val EN_MONTHS = mapOf(
        "january" to 1, "jan" to 1,
        "february" to 2, "feb" to 2,
        "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4,
        "may" to 5,
        "june" to 6, "jun" to 6,
        "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8,
        "september" to 9, "sept" to 9, "sep" to 9,
        "october" to 10, "oct" to 10,
        "november" to 11, "nov" to 11,
        "december" to 12, "dec" to 12,
    )

    private val UK_MONTHS = mapOf(
        "січня" to 1, "січень" to 1, "січні" to 1, "січ" to 1,
        "лютого" to 2, "лютий" to 2, "лютому" to 2, "лют" to 2,
        "березня" to 3, "березень" to 3, "березні" to 3, "бер" to 3,
        "квітня" to 4, "квітень" to 4, "квітні" to 4, "квіт" to 4,
        "травня" to 5, "травень" to 5, "травні" to 5, "трав" to 5,
        "червня" to 6, "червень" to 6, "червні" to 6, "черв" to 6,
        "липня" to 7, "липень" to 7, "липні" to 7, "лип" to 7,
        "серпня" to 8, "серпень" to 8, "серпні" to 8, "серп" to 8,
        "вересня" to 9, "вересень" to 9, "вересні" to 9, "вер" to 9,
        "жовтня" to 10, "жовтень" to 10, "жовтні" to 10, "жовт" to 10,
        "листопада" to 11, "листопад" to 11, "листопаді" to 11, "лист" to 11,
        "грудня" to 12, "грудень" to 12, "грудні" to 12, "груд" to 12,
    )

    private val EN_MONTH_PATTERN = EN_MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private val UK_MONTH_PATTERN = UK_MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

    private val EN_DAY_MONTH_RX = rx("${L_BORDER}(?:on\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?($EN_MONTH_PATTERN)$R_BORDER")
    private val EN_MONTH_DAY_RX = rx("${L_BORDER}(?:on\\s+)?($EN_MONTH_PATTERN)\\s+(\\d{1,2})(?:st|nd|rd|th)?$R_BORDER")
    private val UK_DAY_MONTH_RX = rx("${L_BORDER}(?:на\\s+|у\\s+|в\\s+)?(\\d{1,2})(?:-го|-е)?\\s+($UK_MONTH_PATTERN)$R_BORDER")

    fun parse(
        text: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Hit? {
        val cleaned = norm(text)
        if (cleaned.isEmpty()) return null

        parseRelative(cleaned, nowMs)?.let { return it }

        var rem = cleaned
        val nowZoned = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today: LocalDate = nowZoned.toLocalDate()
        var targetDate: LocalDate? = null
        var targetTime: LocalTime? = null
        var whenLine: Line? = null

        val clockAttempt = extractClock(rem)
        if (clockAttempt.invalidSyntax) return null
        if (clockAttempt.time != null) {
            targetTime = clockAttempt.time
            rem = clockAttempt.remaining
        }

        val dateAttempt = extractDate(rem, today)
        if (dateAttempt.invalidSyntax) return null
        if (dateAttempt.date != null) {
            targetDate = dateAttempt.date
            whenLine = dateAttempt.whenLine
            rem = dateAttempt.remaining
        }

        val collapsedRem = rem.replace(MULTI_SPACE, " ").trim()

        return when {
            targetDate != null && targetTime != null -> {
                val atMs = targetDate.atTime(targetTime).atZone(zone).toInstant().toEpochMilli()
                Hit(atMs = atMs, remaining = collapsedRem, whenLine = whenLine, clockSpoken = true)
            }
            targetDate != null -> {
                val defaultAtNine = targetDate.atTime(DEFAULT_HOUR, 0)
                val chosenDateTime = if (targetDate == today) {
                    maxOf(defaultAtNine, nowZoned.plusHours(1).truncatedTo(ChronoUnit.MINUTES).toLocalDateTime())
                } else {
                    defaultAtNine
                }
                val atMs = chosenDateTime.atZone(zone).toInstant().toEpochMilli()
                Hit(atMs = atMs, remaining = collapsedRem, whenLine = whenLine, clockSpoken = false)
            }
            targetTime != null -> {
                val candidate = today.atTime(targetTime).atZone(zone).toInstant().toEpochMilli()
                val chosenDate = if (candidate > nowMs) today else today.plusDays(1)
                val resolvedWhen = if (chosenDate == today) Line.WHEN_TODAY else Line.WHEN_TOMORROW
                val atMs = chosenDate.atTime(targetTime).atZone(zone).toInstant().toEpochMilli()
                Hit(atMs = atMs, remaining = collapsedRem, whenLine = resolvedWhen, clockSpoken = true)
            }
            else -> null
        }
    }

    private fun parseRelative(text: String, nowMs: Long): Hit? {
        EN_REL_HALF_HOUR_RX.find(text)?.let { m ->
            return Hit(nowMs + 30 * 60_000L, cutRange(text, m.range), null, clockSpoken = true)
        }
        UK_REL_HALF_HOUR_RX.find(text)?.let { m ->
            return Hit(nowMs + 30 * 60_000L, cutRange(text, m.range), null, clockSpoken = true)
        }
        EN_REL_AN_HOUR_RX.find(text)?.let { m ->
            return Hit(nowMs + 60 * 60_000L, cutRange(text, m.range), null, clockSpoken = true)
        }
        UK_REL_AN_HOUR_RX.find(text)?.let { m ->
            return Hit(nowMs + 60 * 60_000L, cutRange(text, m.range), null, clockSpoken = true)
        }
        EN_REL_NUM_RX.find(text)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return null
            val unit = m.groupValues[2].lowercase()
            val minutes = when (unit) {
                "minute", "minutes", "min", "mins" -> n
                "hour", "hours", "hr", "hrs", "h" -> n * 60L
                else -> return null
            }
            if (minutes !in 1..MAX_RELATIVE_MINUTES) return null
            return Hit(nowMs + minutes * 60_000L, cutRange(text, m.range), null, clockSpoken = true)
        }
        UK_REL_NUM_RX.find(text)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return null
            val unit = m.groupValues[2].lowercase().trimEnd('.')
            val minutes = when (unit) {
                "хвилин", "хвилину", "хвилини", "хв" -> n
                "годин", "годину", "години", "год" -> n * 60L
                else -> return null
            }
            if (minutes !in 1..MAX_RELATIVE_MINUTES) return null
            return Hit(nowMs + minutes * 60_000L, cutRange(text, m.range), null, clockSpoken = true)
        }
        return null
    }

    private data class ClockExtract(val time: LocalTime?, val remaining: String, val invalidSyntax: Boolean = false)

    private fun extractClock(text: String): ClockExtract {
        EN_CLOCK_RX.find(text)?.let { m ->
            val rawHour = m.groupValues[1].toIntOrNull() ?: return ClockExtract(null, text, invalidSyntax = true)
            val rawMinStr = m.groupValues[2]
            val rawMin = if (rawMinStr.isEmpty()) 0 else {
                if (rawMinStr.length != 2) return ClockExtract(null, text, invalidSyntax = true)
                rawMinStr.toIntOrNull() ?: return ClockExtract(null, text, invalidSyntax = true)
            }
            if (rawMin !in 0..59) return ClockExtract(null, text, invalidSyntax = true)
            val ampm = m.groupValues[3].lowercase().replace(".", "")
            val hour = if (ampm.isNotEmpty()) {
                if (rawHour !in 1..12) return ClockExtract(null, text, invalidSyntax = true)
                when (ampm) {
                    "am" -> if (rawHour == 12) 0 else rawHour
                    "pm" -> if (rawHour == 12) 12 else rawHour + 12
                    else -> return ClockExtract(null, text, invalidSyntax = true)
                }
            } else {
                if (rawHour !in 0..23) return ClockExtract(null, text, invalidSyntax = true)
                rawHour
            }
            return ClockExtract(LocalTime.of(hour, rawMin), cutRange(text, m.range))
        }

        UK_CLOCK_RX.find(text)?.let { m ->
            val rawHour = m.groupValues[1].toIntOrNull() ?: return ClockExtract(null, text, invalidSyntax = true)
            val rawMinStr = m.groupValues[2]
            val rawMin = if (rawMinStr.isEmpty()) 0 else {
                if (rawMinStr.length != 2) return ClockExtract(null, text, invalidSyntax = true)
                rawMinStr.toIntOrNull() ?: return ClockExtract(null, text, invalidSyntax = true)
            }
            if (rawMin !in 0..59) return ClockExtract(null, text, invalidSyntax = true)
            if (rawHour !in 0..23) return ClockExtract(null, text, invalidSyntax = true)
            val period = m.groupValues[3].lowercase()
            val hour = when (period) {
                "вечора", "дня" -> if (rawHour in 1..11) rawHour + 12 else rawHour
                "ночі", "ранку" -> if (rawHour == 12) 0 else rawHour
                else -> rawHour
            }
            if (hour !in 0..23) return ClockExtract(null, text, invalidSyntax = true)
            return ClockExtract(LocalTime.of(hour, rawMin), cutRange(text, m.range))
        }

        return ClockExtract(null, text)
    }

    private data class DateExtract(
        val date: LocalDate?,
        val whenLine: Line?,
        val remaining: String,
        val invalidSyntax: Boolean = false,
    )

    private fun extractDate(text: String, today: LocalDate): DateExtract {
        DAY_AFTER_TOMORROW_RX.find(text)?.let { m ->
            return DateExtract(today.plusDays(2), Line.WHEN_DAY_AFTER_TOMORROW, cutRange(text, m.range))
        }
        TOMORROW_RX.find(text)?.let { m ->
            return DateExtract(today.plusDays(1), Line.WHEN_TOMORROW, cutRange(text, m.range))
        }
        TODAY_RX.find(text)?.let { m ->
            return DateExtract(today, Line.WHEN_TODAY, cutRange(text, m.range))
        }
        UK_DOW_RX.find(text)?.let { m ->
            val dow = UK_WEEKDAYS[m.groupValues[1].lowercase()]
            if (dow != null) {
                return DateExtract(nextWeekday(today, dow), null, cutRange(text, m.range))
            }
        }
        EN_DOW_RX.find(text)?.let { m ->
            val dow = EN_WEEKDAYS[m.groupValues[1].lowercase()]
            if (dow != null) {
                return DateExtract(nextWeekday(today, dow), null, cutRange(text, m.range))
            }
        }
        UK_DAY_MONTH_RX.find(text)?.let { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return DateExtract(null, null, text, invalidSyntax = true)
            val month = UK_MONTHS[m.groupValues[2].lowercase()] ?: return DateExtract(null, null, text, invalidSyntax = true)
            val resolved = resolveMonthDate(today, month, day) ?: return DateExtract(null, null, text, invalidSyntax = true)
            return DateExtract(resolved, null, cutRange(text, m.range))
        }
        EN_DAY_MONTH_RX.find(text)?.let { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return DateExtract(null, null, text, invalidSyntax = true)
            val month = EN_MONTHS[m.groupValues[2].lowercase()] ?: return DateExtract(null, null, text, invalidSyntax = true)
            val resolved = resolveMonthDate(today, month, day) ?: return DateExtract(null, null, text, invalidSyntax = true)
            return DateExtract(resolved, null, cutRange(text, m.range))
        }
        EN_MONTH_DAY_RX.find(text)?.let { m ->
            val month = EN_MONTHS[m.groupValues[1].lowercase()] ?: return DateExtract(null, null, text, invalidSyntax = true)
            val day = m.groupValues[2].toIntOrNull() ?: return DateExtract(null, null, text, invalidSyntax = true)
            val resolved = resolveMonthDate(today, month, day) ?: return DateExtract(null, null, text, invalidSyntax = true)
            return DateExtract(resolved, null, cutRange(text, m.range))
        }
        return DateExtract(null, null, text)
    }

    private fun resolveMonthDate(today: LocalDate, month: Int, day: Int): LocalDate? {
        if (day !in 1..31) return null
        val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
        if (thisYear != null && !thisYear.isBefore(today)) return thisYear
        return runCatching { LocalDate.of(today.year + 1, month, day) }.getOrNull()
    }

    private fun nextWeekday(today: LocalDate, target: DayOfWeek): LocalDate {
        val delta = ((target.value - today.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }
        return today.plusDays(delta.toLong())
    }

    private fun cutRange(text: String, range: IntRange): String =
        (text.substring(0, range.first) + " " + text.substring(range.last + 1))
            .replace(MULTI_SPACE, " ")
            .trim()
}
