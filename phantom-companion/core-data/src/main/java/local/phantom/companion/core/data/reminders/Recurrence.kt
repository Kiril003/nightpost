package local.phantom.companion.core.data.reminders

import java.util.Calendar
import java.util.TimeZone

/**
 * The RRULE subset the reminder scheduler honours: FREQ + INTERVAL + UNTIL.
 * Anything else — BYDAY, COUNT, exotic frequencies — is read as one-shot
 * rather than guessed at, so a rule we cannot keep never silently becomes a
 * repeat we get wrong.
 */
object Recurrence {

    fun next(
        rule: String?,
        lastTriggerMs: Long,
        afterMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long? {
        val parts = parse(rule) ?: return null
        val field = when (parts["FREQ"]) {
            "DAILY" -> Calendar.DAY_OF_MONTH
            "WEEKLY" -> Calendar.WEEK_OF_YEAR
            "MONTHLY" -> Calendar.MONTH
            "YEARLY" -> Calendar.YEAR
            else -> return null
        }
        val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        val until = parts["UNTIL"]?.let(::parseUntil)

        val cal = Calendar.getInstance(zone).apply { timeInMillis = lastTriggerMs }
        var guard = 0
        while (cal.timeInMillis <= afterMs) {
            if (guard++ > MAX_STEPS) return null
            cal.add(field, interval)
        }
        val next = cal.timeInMillis
        if (until != null && next > until) return null
        return next
    }

    fun repeats(rule: String?): Boolean = parse(rule) != null

    /**
     * COUNT would repeat forever under our stepping, and a BYDAY list means the
     * series does not follow its own start — both refuse rather than mislead.
     * A single BYDAY on a weekly rule is redundant (stepping by week already
     * holds the weekday), so it passes.
     */
    private fun supported(parts: Map<String, String>): Boolean {
        if (parts["FREQ"] !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return false
        if (parts.containsKey("COUNT")) return false
        val byDay = parts["BYDAY"] ?: return true
        return parts["FREQ"] == "WEEKLY" && !byDay.contains(',')
    }

    private fun parse(rule: String?): Map<String, String>? {
        val clean = rule?.trim()?.removePrefix("RRULE:")?.takeIf { it.isNotEmpty() } ?: return null
        return clean.uppercase()
            .split(';')
            .mapNotNull { part ->
                val kv = part.split('=', limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1].trim() else null
            }
            .toMap()
            .takeIf { it.isNotEmpty() && supported(it) }
    }

    /** iCal UTC stamps: `20260731T180000Z`, or the date-only `20260731`. */
    private fun parseUntil(raw: String): Long? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length != 8 && digits.length != 14) return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt() - 1,
                digits.substring(6, 8).toInt(),
                if (digits.length == 14) digits.substring(8, 10).toInt() else 23,
                if (digits.length == 14) digits.substring(10, 12).toInt() else 59,
                if (digits.length == 14) digits.substring(12, 14).toInt() else 59,
            )
        }
        return cal.timeInMillis
    }

    private const val MAX_STEPS = 4000
}
