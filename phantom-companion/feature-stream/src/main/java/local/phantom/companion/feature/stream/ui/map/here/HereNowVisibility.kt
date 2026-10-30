package local.phantom.companion.feature.stream.ui.map.here

import local.phantom.companion.core.truth.FactKind
import local.phantom.companion.core.truth.Lang
import local.phantom.companion.core.truth.TruthState

internal data class HereWords(val held: String, val sent: String, val unsure: String)

/** Lines someone actually said: a verdict with a report behind it, or my own report. */
internal fun hereNowRows(state: HereNowState): List<HereRow> =
    state.rows.filter { it.verdict?.newestAtMs != null || it.held || it.sent }

internal fun hereNowShows(state: HereNowState): Boolean =
    state.reach == HereReach.ON && hereNowRows(state).isNotEmpty()

internal fun hereReportKinds(state: HereNowState): List<FactKind> =
    state.rows.map { it.kind }.distinct().filter { k -> k.positive?.let { k.opposite(it) } != null }

internal fun hereReportOffered(state: HereNowState): Boolean =
    state.reach == HereReach.ON && hereReportKinds(state).isNotEmpty()

internal fun hereFactsLine(rows: List<HereRow>, lang: Lang, words: HereWords): String =
    rows.joinToString(" · ") { r ->
        val v = r.verdict
        val reading = v?.reading?.takeIf { v.state == TruthState.LIKELY }
        val what = when {
            r.held -> words.held
            r.sent -> words.sent
            reading != null -> if (lang == Lang.EN) reading.en else reading.uk
            else -> words.unsure
        }
        if (!r.held && !r.sent && reading != null && !r.kind.binary) what else (if (lang == Lang.EN) r.kind.en else r.kind.uk) + ": " + what
    }

internal fun hereFreshestMs(rows: List<HereRow>): Long? = rows.mapNotNull { it.verdict?.newestAtMs }.maxOrNull()
