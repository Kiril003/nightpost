package local.phantom.companion.core.net.map

/**
 * NAV-4 — honest evaluator for the COMMON subset of OSM `opening_hours`.
 *
 * Handles what actually occurs on UA POIs: `24/7`, rule lists split by
 * `;`, day ranges/lists (`Mo-Fr`, `Sa,Su`, `Mo-We,Fr`), one or more time
 * ranges per rule (`08:00-13:00,14:00-20:00`), overnight ranges
 * (`22:00-06:00`), and `off`/`closed` rules. Anything outside that
 * (months, holidays `PH/SH`, sunrise/sunset, week numbers) returns
 * [Status.Unknown] — the UI then shows the raw string with no live badge
 * instead of guessing (Law 3 — honesty over confidence).
 */
object OpeningHours {

    sealed interface Status {
        /** Open right now; [untilMinuteOfDay] when today's closing time is known. */
        data class Open(val untilMinuteOfDay: Int?) : Status

        /** Closed right now; [opensAtMinuteOfDay] when today's next opening is known. */
        data class Closed(val opensAtMinuteOfDay: Int?) : Status

        /** The expression uses syntax this evaluator doesn't cover. */
        data object Unknown : Status
    }

    /** One `HH:MM-HH:MM` span; end < start means it crosses midnight. */
    private data class Span(val startMin: Int, val endMin: Int)

    /** One parsed rule: which ISO days (1=Mo..7=Su) + spans (empty = off). */
    private data class Rule(val days: Set<Int>, val spans: List<Span>, val off: Boolean)

    private val DAY_TOKENS = mapOf(
        "mo" to 1, "tu" to 2, "we" to 3, "th" to 4, "fr" to 5, "sa" to 6, "su" to 7,
    )

    /**
     * Evaluate [raw] at the given moment. [dayIso] is ISO-8601 (1=Monday …
     * 7=Sunday), [minuteOfDay] is 0..1439. Pure — unit-tested on the JVM.
     */
    fun status(raw: String?, dayIso: Int, minuteOfDay: Int): Status {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return Status.Unknown
        if (text == "24/7") return Status.Open(untilMinuteOfDay = null)

        val rules = parseRules(text) ?: return Status.Unknown

        // Later rules override earlier ones for the days they name (the OSM
        // convention for `Mo-Su 08:00-20:00; Su off` style expressions).
        val todayRule = rules.lastOrNull { dayIso in it.days }
        val openNow = todayRule != null && !todayRule.off &&
            todayRule.spans.any { minuteOfDay in it }

        // A span that crossed midnight from YESTERDAY can keep us open too.
        val yesterday = if (dayIso == 1) 7 else dayIso - 1
        val yRule = rules.lastOrNull { yesterday in it.days }
        val openFromYesterday = yRule != null && !yRule.off && yRule.spans.any {
            it.endMin < it.startMin && minuteOfDay < it.endMin
        }

        return when {
            openNow -> {
                val span = todayRule!!.spans.first { minuteOfDay in it }
                Status.Open(untilMinuteOfDay = if (span.endMin < span.startMin) null else span.endMin)
            }
            openFromYesterday -> {
                val span = yRule!!.spans.first { it.endMin < it.startMin && minuteOfDay < it.endMin }
                Status.Open(untilMinuteOfDay = span.endMin)
            }
            else -> {
                val opensAt = todayRule?.takeIf { !it.off }?.spans
                    ?.map { it.startMin }
                    ?.filter { it > minuteOfDay }
                    ?.minOrNull()
                Status.Closed(opensAtMinuteOfDay = opensAt)
            }
        }
    }

    /** Convenience over the device clock. */
    fun statusNow(raw: String?, now: java.util.Calendar = java.util.Calendar.getInstance()): Status {
        // Calendar: SUNDAY=1..SATURDAY=7 → ISO 1=Mo..7=Su.
        val dayIso = ((now.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return status(raw, dayIso, minute)
    }

    fun fmtMinute(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60 % 24, minuteOfDay % 60)

    private operator fun Span.contains(minute: Int): Boolean =
        if (endMin >= startMin) minute in startMin until endMin
        else minute >= startMin // the pre-midnight half; post-midnight is yesterday's business

    /** null = the expression contains something we don't parse. */
    private fun parseRules(text: String): List<Rule>? {
        val rules = mutableListOf<Rule>()
        for (part in text.split(';', '|').map { it.trim() }.filter { it.isNotEmpty() }) {
            val rule = parseRule(part) ?: return null
            rules += rule
        }
        return rules.takeIf { it.isNotEmpty() }
    }

    private fun parseRule(part: String): Rule? {
        val tokens = part.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val first = tokens.first()
        val hasDays = first.split(',').all { seg ->
            val range = seg.split('-')
            range.size in 1..2 && range.all { it.lowercase() in DAY_TOKENS }
        }
        val days: Set<Int>
        val rest: List<String>
        if (hasDays) {
            days = parseDays(first) ?: return null
            rest = tokens.drop(1)
        } else {
            days = (1..7).toSet() // no day prefix = every day
            rest = tokens
        }

        if (rest.isEmpty()) return null
        val restJoined = rest.joinToString(" ").lowercase()
        if (restJoined == "off" || restJoined == "closed") {
            return Rule(days, emptyList(), off = true)
        }
        if (restJoined == "24/7" || restJoined == "00:00-24:00") {
            return Rule(days, listOf(Span(0, 24 * 60)), off = false)
        }

        // The remainder must be comma-separated HH:MM-HH:MM spans.
        val spans = rest.joinToString("").split(',').map { seg ->
            val m = Regex("""^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})$""").find(seg.trim()) ?: return null
            val (h1, m1, h2, m2) = m.destructured
            val start = h1.toInt() * 60 + m1.toInt()
            val end = h2.toInt() * 60 + m2.toInt()
            if (h1.toInt() > 24 || h2.toInt() > 24) return null
            Span(start, if (end == 0 && start > 0) 24 * 60 else end)
        }
        return Rule(days, spans, off = false)
    }

    private fun parseDays(token: String): Set<Int>? {
        val days = mutableSetOf<Int>()
        for (seg in token.split(',')) {
            val range = seg.split('-')
            when (range.size) {
                1 -> days += DAY_TOKENS[range[0].lowercase()] ?: return null
                2 -> {
                    val from = DAY_TOKENS[range[0].lowercase()] ?: return null
                    val to = DAY_TOKENS[range[1].lowercase()] ?: return null
                    if (from <= to) days += (from..to)
                    else { days += (from..7); days += (1..to) } // Sa-Mo wraps
                }
                else -> return null
            }
        }
        return days
    }
}
