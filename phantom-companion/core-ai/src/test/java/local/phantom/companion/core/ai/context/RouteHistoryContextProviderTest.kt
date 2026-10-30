package local.phantom.companion.core.ai.context

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAP-AI-2 — pins the ROUTE_HISTORY prompt block contract.
 *
 * Privacy contract: only aggregate counts surface — never coords,
 * never per-point timestamps. The full timeline is reachable only
 * through the future `query_location_history(window)` tool call (MAP-AI-8)
 * which gates through operator consent.
 */
class RouteHistoryContextProviderTest {

    @Test
    fun `formatter emits none when point count is zero`() {
        assertEquals(
            "[ROUTE_HISTORY:none]",
            RouteHistoryContextFormatter.format(RouteHistorySummary.Empty),
        )
    }

    @Test
    fun `formatter emits days and points for non-empty summary`() {
        assertEquals(
            "[ROUTE_HISTORY: days=7 points=4231]",
            RouteHistoryContextFormatter.format(
                RouteHistorySummary(daysCovered = 7, pointCount = 4231),
            ),
        )
    }

    @Test
    fun `formatter degrades to none when days are zero but points nonzero`() {
        // Edge case — 0 days with non-zero points should still surface so
        // the AI can see the store has data; only empty store hides.
        val out = RouteHistoryContextFormatter.format(
            RouteHistorySummary(daysCovered = 0, pointCount = 5),
        )
        assertEquals("[ROUTE_HISTORY: days=0 points=5]", out)
    }

    @Test
    fun `formatter never includes coords or timestamps in output`() {
        val out = RouteHistoryContextFormatter.format(
            RouteHistorySummary(daysCovered = 30, pointCount = 9999),
        )
        assert("lat=" !in out)
        assert("lon=" !in out)
        assert("timestamp" !in out)
        assert("time=" !in out)
    }

    @Test
    fun `noop provider returns empty summary`() {
        assertEquals(RouteHistorySummary.Empty, NoopRouteHistoryContextProvider.current())
    }

    @Test
    fun `noop provider chained through formatter yields none`() {
        val out = RouteHistoryContextFormatter.format(NoopRouteHistoryContextProvider.current())
        assertEquals("[ROUTE_HISTORY:none]", out)
    }
}
