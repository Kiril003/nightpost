package local.phantom.companion.feature.car

import local.phantom.companion.core.nav.RoutePoint
import local.phantom.companion.core.nav.RouteRegister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * design-critic §S5 — the rule that must hold here is PER-SEGMENT: a line
 * that mixes confident and uncertain stretches must draw both, not collapse
 * to whichever is worst. [GuidanceController.registerConfidenceText] (a
 * different rule, for a different, single-sentence channel) is pinned
 * separately in core-nav.
 */
class RegisterRunsTest {

    private fun pt(i: Int, reg: RouteRegister) = RoutePoint(50.0 + i * 0.001, 30.0, reg)

    @Test
    fun `a uniformly confident line is one run, not uncertain`() {
        val points = (0..4).map { pt(it, RouteRegister.DERIVED) }
        val runs = registerRuns(points)
        assertEquals(1, runs.size)
        assertEquals(false, runs[0].first)
        assertEquals(5, runs[0].second.size)
    }

    @Test
    fun `a guessed stretch inside a derived route is its own run, not the whole line`() {
        val points = listOf(
            pt(0, RouteRegister.DERIVED),
            pt(1, RouteRegister.DERIVED),
            pt(2, RouteRegister.GUESSED),
            pt(3, RouteRegister.GUESSED),
            pt(4, RouteRegister.DERIVED),
        )
        val runs = registerRuns(points)
        // Confident / uncertain / confident — the middle stretch never
        // spreads to the ends, which is the whole point of per-segment
        // rendering over worst-register-wins.
        assertEquals(3, runs.size)
        assertEquals(listOf(false, true, false), runs.map { it.first })
        assertTrue("mixed register must not average to one uncertain line", runs.any { !it.first })
    }

    @Test
    fun `runs share their boundary point so the drawn line has no gap`() {
        val points = listOf(
            pt(0, RouteRegister.DERIVED),
            pt(1, RouteRegister.GUESSED),
            pt(2, RouteRegister.GUESSED),
        )
        val runs = registerRuns(points)
        assertEquals(2, runs.size)
        // point 1 ends the first run AND starts the second.
        assertEquals(points[1], runs[0].second.last())
        assertEquals(points[1], runs[1].second.first())
    }

    @Test
    fun `remembered counts as uncertain the same as guessed`() {
        val points = listOf(pt(0, RouteRegister.MEASURED), pt(1, RouteRegister.REMEMBERED))
        assertEquals(listOf(false, true), registerRuns(points).map { it.first })
    }

    @Test
    fun `fewer than two points has no runs to draw`() {
        assertTrue(registerRuns(emptyList()).isEmpty())
        assertTrue(registerRuns(listOf(pt(0, RouteRegister.GUESSED))).isEmpty())
    }
}
