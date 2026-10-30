package local.phantom.companion.feature.stream.ui.map

import androidx.compose.ui.unit.dp
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.icons.glyph
import local.phantom.companion.feature.stream.ui.map.source.BatchESource

internal val THREAT_GLYPH = 20.dp

/**
 * СИРЕНА — the alarm brain behind every alert surface (map paint, banner,
 * the «Небо» command sheet, the brief, voice notices).
 *
 * Two jobs live here:
 *  - [ThreatMeta] — the single source of truth for how a [ThreatType] looks
 *    and reads (label, glyph, tint, severity). The brief sheet, the banner,
 *    the map badges and the alarm sheet all render from this one table, so
 *    a new threat type is added in exactly one place.
 *  - [diffAlerts] — the transition detector: two consecutive sky snapshots
 *    in, the list of things that actually CHANGED out (тривога / відбій /
 *    зміна загрози). Pure and clock-injected, so it is unit-testable and
 *    can never invent history it did not observe (a cold start diffs to
 *    nothing — an empty previous snapshot is ignorance, not silence).
 */

/** How a threat type presents everywhere. [severity] sorts boards: higher = worse. */
internal data class ThreatMeta(
    val label: String,
    val short: String,
    val glyph: local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph,
    val colorArgb: Long,
    val severity: Int,
)

// R0-04: тип загрози з фіда не показується — лише «тривога». Severity лишається для порядку.
internal val ThreatType.meta: ThreatMeta
    get() = ThreatMeta(
        MapText.s(R.string.mapl_e_threat_unknown_label),
        MapText.s(R.string.mapl_e_threat_unknown_short),
        ThreatType.UNKNOWN.glyph(),
        0xFFEF4444,
        when (this) {
            ThreatType.UNKNOWN -> 1
            ThreatType.SHAHED -> 3
            ThreatType.KAB, ThreatType.ARTILLERY -> 4
            ThreatType.MISSILE, ThreatType.URBAN -> 5
            ThreatType.MIG, ThreatType.BALLISTIC -> 6
            ThreatType.CHEMICAL -> 7
            ThreatType.NUCLEAR -> 8
        },
    )

/** «Київська область» → «Київська», «м. Київ» → «Київ»; AR names pass through.
 *  Public since СИРЕНА-3: the notification in `:app` names the same oblast the
 *  map does, and two spellings of «твоя область» would read as two places. */
fun oblastShortUk(name: String): String =
    name.removeSuffix(BatchESource.OBLAST_SUFFIX).removePrefix(BatchESource.CITY_PREFIX).trim()

/**
 * Elapsed-time phrase for tickers: «щойно», «12 хв», «1 год 05 хв», «3 дн 4 год».
 *
 * Rolls over past hours because Крим and Луганська have been under an
 * uninterrupted alert since 2022 — the hours-only form printed «37769 год».
 */
internal fun durationShortUk(sinceEpochMs: Long, nowEpochMs: Long): String {
    val mins = ((nowEpochMs - sinceEpochMs) / 60_000L).coerceAtLeast(0L)
    val hours = mins / 60L
    val days = hours / 24L
    return when {
        mins < 1L -> MapText.s(R.string.mapl_e_duration_just_now)
        mins < 60L -> MapText.s(R.string.mapl_e_duration_min, mins)
        hours < 24L -> MapText.s(R.string.mapl_e_duration_hr_min, hours, mins % 60L)
        days < 30L -> MapText.s(R.string.mapl_e_duration_day_hr, days, hours % 24L)
        days < 365L -> MapText.s(R.string.mapl_e_duration_month, days / 30L)
        else -> MapText.s(R.string.mapl_e_duration_year, days / 365L)
    }
}

/** «Покровський район · Липецька громада · та ще 4» — capped join. */
internal fun districtsLineUk(districts: List<DistrictAlert>, max: Int = 6): String {
    val shown = districts.take(max).joinToString(" · ") { it.name }
    val rest = districts.size - max
    return if (rest > 0) MapText.s(R.string.mapl_e_districts_more, shown, rest) else shown
}

// ── Transitions ─────────────────────────────────────────────────────────────

internal enum class AlertTransitionKind { STARTED, ENDED }

/** One observed change of the sky, journal-ready. */
internal data class AlertTransition(
    val oblast: String,
    val kind: AlertTransitionKind,
    val threatType: ThreatType,
    val atEpochMs: Long,
)

/**
 * What changed between two sky snapshots. An empty [prev] yields nothing:
 * the first fetch after launch shows a world already mid-state, and calling
 * every ongoing alarm «щойно почалась» would be an invented fact. A key
 * missing from [next] is feed drift, not a «відбій» — also silent.
 */
internal fun diffAlerts(
    prev: Map<String, AlertState>,
    next: Map<String, AlertState>,
    nowEpochMs: Long,
): List<AlertTransition> {
    if (prev.isEmpty()) return emptyList()
    val out = ArrayList<AlertTransition>()
    for ((oblast, state) in next) {
        val was = prev[oblast]
        val stamp = state.changedEpochMs ?: nowEpochMs
        when {
            state.active && was?.active != true ->
                out += AlertTransition(oblast, AlertTransitionKind.STARTED, state.threatType, stamp)
            !state.active && was?.active == true ->
                out += AlertTransition(oblast, AlertTransitionKind.ENDED, was.threatType, stamp)
        }
    }
    return out
}

/** The spoken/notice phrase for one transition (rides the guidance voice queue). */
internal fun alarmPhraseUk(t: AlertTransition): String = when (t.kind) {
    AlertTransitionKind.STARTED -> MapText.s(R.string.mapl_e_alarm_started, t.oblast)
    AlertTransitionKind.ENDED -> MapText.s(R.string.mapl_e_alarm_ended, t.oblast)
}

/**
 * The alarm board's ordering: the operator's own oblast first, then the
 * worst threat, then the freshest alarm.
 */
internal fun sortAlertsForBoard(
    alerts: Map<String, AlertState>,
    ownOblast: String?,
): List<Pair<String, AlertState>> =
    alerts.filterValues { it.active }
        .toList()
        .sortedWith(
            compareByDescending<Pair<String, AlertState>> { it.first == ownOblast }
                .thenByDescending { it.second.threatType.meta.severity }
                .thenByDescending { it.second.changedEpochMs ?: 0L },
        )
