package local.phantom.companion.core.nav

/**
 * NAV-Ω1 — «відлік»: an arrival deadline over the live guidance session.
 * Pure Kotlin state machine: fed the remaining-duration estimate on every
 * fix, it tracks the slack (deadline − ETA), classifies it into honest
 * bands and speaks each band transition exactly once — a nudge, not a
 * metronome. Recovering (slack going back up across a threshold) re-arms
 * the tighter band and announces the recovery once too.
 *
 * The slack math is only as good as the ETA under it (OSRM duration +
 * live progress) — the copy says «за оцінкою», never promises.
 */
class DeadlineWatch(
    /** Epoch millis the operator must arrive by. */
    val deadlineEpochMs: Long,
) {
    enum class Band { COMFORTABLE, TIGHT, LATE }

    sealed interface Cue {
        val spoken: String

        data class Tight(val slackMin: Int) : Cue {
            override val spoken: String
                get() = "Запас тане — за оцінкою лишається ${spokenMinutesUk(slackMin)} до дедлайну."
        }

        data class Late(val lateMin: Int) : Cue {
            override val spoken: String
                get() = if (lateMin <= 1) "За оцінкою прибуваємо впритул до дедлайну."
                else "За оцінкою спізнюємось приблизно на ${spokenMinutesUk(lateMin)}."
        }

        data object Recovered : Cue {
            override val spoken: String get() = "Знову встигаємо — запас відновлено."
        }
    }

    /** What the strip renders each tick. */
    data class Status(
        val band: Band,
        /** Positive = minutes to spare; negative = minutes late (estimate). */
        val slackMinutes: Int,
    )

    private var lastBand: Band? = null

    /**
     * One ETA update → current status + at most one cue. [remainingDurationS]
     * comes from GuidanceProgress; [nowMs] injected for tests.
     */
    fun onEstimate(remainingDurationS: Double, nowMs: Long): Pair<Status, Cue?> {
        val etaMs = nowMs + (remainingDurationS * 1000).toLong()
        val slackMs = deadlineEpochMs - etaMs
        val slackMin = (slackMs / 60_000.0).let { if (it >= 0) it else it }.toInt()
        val band = when {
            slackMs < 0 -> Band.LATE
            slackMs < TIGHT_THRESHOLD_MS -> Band.TIGHT
            else -> Band.COMFORTABLE
        }

        val prev = lastBand
        lastBand = band
        val cue: Cue? = when {
            prev == null -> null // first estimate seeds the band silently
            band == prev -> null
            band == Band.LATE -> Cue.Late(-slackMin)
            band == Band.TIGHT && prev == Band.COMFORTABLE -> Cue.Tight(slackMin)
            band == Band.COMFORTABLE || (band == Band.TIGHT && prev == Band.LATE) -> Cue.Recovered
            else -> null
        }
        return Status(band, slackMin) to cue
    }

    /**
     * Preview face (no guidance yet): the leave-by moment for a planned
     * route of [durationS] — «виїзд до HH:MM», null when already missed.
     */
    fun leaveByEpochMs(durationS: Double): Long? {
        val leaveBy = deadlineEpochMs - (durationS * 1000).toLong()
        return leaveBy.takeIf { it > 0 }
    }

    companion object {
        /** Under ten minutes of slack the countdown starts talking. */
        const val TIGHT_THRESHOLD_MS = 10 * 60_000L

        /**
         * "18:00" / "1800" / "18.00" today (tomorrow if already past) →
         * epoch ms, null when unparseable. Pure; [nowMs] injected.
         */
        fun parseDeadline(raw: String, nowMs: Long): Long? {
            val m = Regex("""^(\d{1,2})[:. ]?(\d{2})$""").find(raw.trim()) ?: return null
            val h = m.groupValues[1].toInt().takeIf { it in 0..23 } ?: return null
            val min = m.groupValues[2].toInt().takeIf { it in 0..59 } ?: return null
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(java.util.Calendar.HOUR_OF_DAY, h)
                set(java.util.Calendar.MINUTE, min)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= nowMs) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
    }
}

/** «5 хвилин», «21 хвилина», «22 хвилини» — TTS-friendly. */
fun spokenMinutesUk(min: Int): String {
    val n = if (min < 1) 1 else min
    val word = when {
        n % 100 in 11..14 -> "хвилин"
        n % 10 == 1 -> "хвилина"
        n % 10 in 2..4 -> "хвилини"
        else -> "хвилин"
    }
    return "$n $word"
}
