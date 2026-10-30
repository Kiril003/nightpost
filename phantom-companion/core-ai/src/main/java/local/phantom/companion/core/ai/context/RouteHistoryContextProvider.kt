package local.phantom.companion.core.ai.context

import java.util.Locale

/**
 * MAP-AI-2 — pure-Kotlin contract for "how much breadcrumb history is
 * recorded?" injected into the AI's enrichedContext block as
 * `[ROUTE_HISTORY: days=D points=P]` (or `[ROUTE_HISTORY:none]`).
 *
 * MAP-AI-8 lands the actual `:core-location-history` module with a
 * 90-day Douglas-Peucker-compressed Room table and a `TimelineSlider`
 * UI surface. Until then this stub returns an empty snapshot so the
 * prompt block remains deterministic and the wiring path is in place
 * for the future drop-in.
 *
 * Per Tier-29 constitutional axiom: raw coordinates / timestamps never
 * surface in the preamble — only aggregate counts. Replay-capable
 * queries (`query_location_history(window)` tool, MAP-AI-8) gate
 * through the existing question widget so the operator approves each
 * temporal disclosure.
 */
fun interface RouteHistoryContextProvider {
    /** @return aggregate summary of the breadcrumb store. */
    fun current(): RouteHistorySummary
}

/**
 * Aggregate view of the breadcrumb store. Only counts surface — no
 * coords, no timestamps, no source-device ids.
 */
data class RouteHistorySummary(
    val daysCovered: Int,
    val pointCount: Int,
) {
    companion object {
        val Empty = RouteHistorySummary(daysCovered = 0, pointCount = 0)
    }
}

/**
 * Formats route history for the AI prompt preamble.
 *
 * - Empty (`pointCount == 0`) → `[ROUTE_HISTORY:none]`.
 * - Otherwise → `[ROUTE_HISTORY: days=D points=P]`.
 */
object RouteHistoryContextFormatter {
    fun format(summary: RouteHistorySummary): String {
        if (summary.pointCount == 0) return "[ROUTE_HISTORY:none]"
        return String.format(
            Locale.US,
            "[ROUTE_HISTORY: days=%d points=%d]",
            summary.daysCovered,
            summary.pointCount,
        )
    }
}

/**
 * Default stub returned by AppContainer until MAP-AI-8 lands the real
 * `:core-location-history` module. Always emits an empty summary so
 * the formatter degrades to `[ROUTE_HISTORY:none]`.
 */
object NoopRouteHistoryContextProvider : RouteHistoryContextProvider {
    override fun current(): RouteHistorySummary = RouteHistorySummary.Empty
}
