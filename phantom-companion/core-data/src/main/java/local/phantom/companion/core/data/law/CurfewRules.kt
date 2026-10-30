package local.phantom.companion.core.data.law

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Law 3 (D8) — a warning is only ever as real as its source. A curfew rule
 * names the region it was verified for, its window in that region's OWN
 * clock, where it came from and when it was last checked. Nothing here is
 * inferred: a region with no entry produces no warning, silently.
 *
 * To add a region: verify the hours against the authority that published
 * them, then add ONE entry to that country's pack with a source you could
 * show the operator. Never seed a guess — a wrong curfew hour is worse than
 * no curfew line at all.
 */
enum class CurfewScope { NATIONAL, REGIONAL }

data class CurfewRule(
    val regionId: String,
    val scope: CurfewScope,
    /** The window is stated in the region's civil time, not the device's. */
    val zoneId: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val source: String,
    val verifiedAt: String,
) {
    fun covers(minuteOfDay: Int): Boolean =
        if (startMinuteOfDay <= endMinuteOfDay) {
            minuteOfDay >= startMinuteOfDay && minuteOfDay < endMinuteOfDay
        } else {
            minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
        }
}

/** A moment that lands inside a verified window, with the rule that says so. */
data class CurfewMatch(
    val rule: CurfewRule,
    val minuteOfDay: Int,
) {
    val atHhMm: String get() = hhmm(minuteOfDay)
    val windowHhMm: String get() = "${hhmm(rule.startMinuteOfDay)}–${hhmm(rule.endMinuteOfDay)}"
}

object CurfewRules {

    const val UKRAINE = "UA"

    /**
     * ATLAS-3 — the table is no longer seeded here: every rule comes from an
     * installed [local.phantom.companion.core.data.country.CountryPack]. No
     * pack, no rule, no warning — the same silence, now with one source.
     */
    val rules: List<CurfewRule>
        get() = local.phantom.companion.core.data.country.CountryPackRegistry.curfewRules()

    fun ruleFor(regionId: String?): CurfewRule? =
        regionId?.let { id -> rules.firstOrNull { it.regionId == id } }

    /** No region, or a region with no verified rule → null. No warning. */
    fun matchAt(regionId: String?, epochMs: Long): CurfewMatch? {
        val rule = ruleFor(regionId) ?: return null
        val minute = minuteOfDayIn(rule.zoneId, epochMs)
        return if (rule.covers(minute)) CurfewMatch(rule, minute) else null
    }

    private fun minuteOfDayIn(zoneId: String, epochMs: Long): Int {
        val cal = Calendar.getInstance(zone(zoneId))
        cal.timeInMillis = epochMs
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    // getTimeZone answers GMT for an id it does not know, without saying so.
    // tzdata older than 2022b spells Kyiv "Kiev", and the window would slide
    // three hours onto the wrong side of midnight.
    private val LEGACY_ZONE_IDS = mapOf("Europe/Kyiv" to "Europe/Kiev")

    private fun zone(id: String): TimeZone {
        val tz = TimeZone.getTimeZone(id)
        if (tz.id != "GMT" || id == "GMT") return tz
        return LEGACY_ZONE_IDS[id]?.let { TimeZone.getTimeZone(it) } ?: tz
    }
}

private fun hhmm(minuteOfDay: Int): String =
    String.format(Locale.US, "%02d:%02d", (minuteOfDay / 60) % 24, minuteOfDay % 60)
