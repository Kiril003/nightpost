package local.phantom.companion.core.nav

import local.phantom.companion.core.data.law.CurfewMatch

/**
 * NAV-Ω2 — Overwatch Situation Narrator («Бенджі»).
 *
 * Provides tactical, audio-first situational commentary during active guidance:
 * - Route start brief (distance, ETA, threats status).
 * - Proximity alerts for speed cameras, roadblocks, and hazards along the corridor.
 * - En-route air-raid alarm changes in target or transit oblasts.
 * - Arrival deadline pacing updates.
 * - Destination arrival debrief.
 *
 * All cues are emitted as [NavCue.Urgency.INFO] so they never delay or preempt
 * crucial driving maneuver cues ([NavCue.Urgency.NOW] and [NavCue.Urgency.SOON]).
 */
class OverwatchNarrator(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val announcedHazards = mutableSetOf<String>()
    private var lastAlarmOblasts = emptySet<String>()

    data class SituationFrame(
        val destinationName: String?,
        val remainingDistanceM: Double,
        val remainingDurationS: Double,
        val speedKmh: Int?,
        val speedLimitKmh: Int?,
        val activeAlarms: Set<String> = emptySet(),
        val approachingHazardLabel: String? = null,
        val deadlineWarning: String? = null,
        val approachingCheckpoint: Boolean = false,
        val approachingBadSurfaceKm: Double? = null,
        /**
         * Law 3 (D8): a match resolved from [local.phantom.companion.core.data.law.CurfewRules]
         * for a region that HAS a verified rule — never a sentence handed in
         * from the outside, which is how an invented law gets spoken aloud.
         */
        val curfew: CurfewMatch? = null,
        val drivingDurationS: Long = 0L,
    )

    /** Reset state for a new guidance session. */
    fun reset() {
        announcedHazards.clear()
        lastAlarmOblasts = emptySet()
    }

    /**
     * Evaluates periodic updates and emits situational commentary if relevant events occur.
     */
    fun evaluate(frame: SituationFrame): NavCue? {
        val now = clockMs()

        if (frame.approachingCheckpoint && announcedHazards.add("checkpoint")) {
            return NavCue(
                text = "Попереду пункт пропуску чи оплати. Знизьте швидкість.",
                urgency = NavCue.Urgency.INFO,
                key = "overwatch_checkpoint",
                bornMs = now,
                ttlMs = 20_000L,
            )
        }

        // 2. Bad road surface / potholes warning
        if (frame.approachingBadSurfaceKm != null && announcedHazards.add("bad_surface_${frame.approachingBadSurfaceKm}")) {
            val kmText = String.format(java.util.Locale.US, "%.1f", frame.approachingBadSurfaceKm).trimEnd('0').trimEnd('.')
            return NavCue(
                text = "Попереду ділянка з поганим покриттям $kmText км. Знизьте швидкість.",
                urgency = NavCue.Urgency.INFO,
                key = "overwatch_bad_surface",
                bornMs = now,
                ttlMs = 20_000L,
            )
        }

        // 3. Curfew alert — spoken only from a verified rule, never a literal.
        val curfew = frame.curfew
        if (curfew != null && announcedHazards.add("curfew_${curfew.rule.regionId}_${curfew.atHhMm}")) {
            return NavCue(
                text = "Прибуття о ${curfew.atHhMm} потрапляє у вікно комендантської " +
                    "${curfew.windowHhMm} — ${curfew.rule.source}, звірено ${curfew.rule.verifiedAt}.",
                urgency = NavCue.Urgency.INFO,
                key = "overwatch_curfew",
                bornMs = now,
                ttlMs = 25_000L,
            )
        }

        // 4. Driver fatigue rest reminder (every 2 hours)
        val fatigueHours = (frame.drivingDurationS / 3600).toInt()
        if (fatigueHours >= 2 && announcedHazards.add("fatigue_$fatigueHours")) {
            return NavCue(
                text = "Ви за кермом вже $fatigueHours години. Рекомендуємо зробити коротку зупинку на відпочинок.",
                urgency = NavCue.Urgency.INFO,
                key = "overwatch_fatigue",
                bornMs = now,
                ttlMs = 25_000L,
            )
        }

        // 5. Approaching hazard (camera, radar alert)
        val hazard = frame.approachingHazardLabel
        if (hazard != null && announcedHazards.add(hazard)) {
            return NavCue(
                text = "Увага: попереду $hazard.",
                urgency = NavCue.Urgency.INFO,
                key = "overwatch_hazard_$hazard",
                bornMs = now,
                ttlMs = 15_000L,
            )
        }

        // 5. Alarm changes in tracked oblasts
        val newAlarms = frame.activeAlarms - lastAlarmOblasts
        lastAlarmOblasts = frame.activeAlarms
        if (newAlarms.isNotEmpty()) {
            val names = newAlarms.joinToString(", ")
            return NavCue(
                text = "Овервотч: зміна обстановки — повітряна тривога в $names.",
                urgency = NavCue.Urgency.INFO,
                key = "overwatch_alarm_change",
                bornMs = now,
                ttlMs = 20_000L,
            )
        }

        // 6. Deadline pacing shift
        if (frame.deadlineWarning != null) {
            return NavCue(
                text = frame.deadlineWarning,
                urgency = NavCue.Urgency.INFO,
                key = "overwatch_deadline",
                bornMs = now,
                ttlMs = 20_000L,
            )
        }

        return null
    }
}
