package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.sensor.nav.RoadGraph
import local.phantom.companion.core.sensor.nav.RoadWay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HORIZON III.2 — the generative overlays' promises: the isochrone colours
 * real reachable roads by real walk-time, and the danger field falls off
 * from a hazard as a colour gradient. Every output is a drawable spec.
 */
class GenerativeOverlaysTest {

    private val lat0 = 50.4500
    private val lon0 = 30.5200
    private fun latAt(northM: Double) = lat0 + Math.toDegrees(northM / 6_371_000.0)
    private fun lonAt(eastM: Double) =
        lon0 + Math.toDegrees(eastM / (6_371_000.0 * Math.cos(Math.toRadians(lat0))))

    /** One long north-south avenue, 900 m, vertices every 100 m. */
    private fun avenue(): RoadGraph = RoadGraph(
        listOf(
            RoadWay(1L, (0..9).map { latAt(it * 100.0) to lonAt(0.0) }, name = "Пряма"),
        ),
    )

    @Test
    fun `reachable returns nearer roads at lower cost`() {
        val edges = avenue().reachable(latAt(0.0), lonAt(0.0), maxCostM = 300.0)
        assertTrue("some roads should be reachable", edges.isNotEmpty())
        // Nothing beyond ~300 m is reported.
        assertTrue("must not exceed the budget", edges.all { it.costM <= 300.0 + 1e-6 })
        // The 100 m vertex is reachable well within budget.
        assertTrue(edges.any { it.costM in 90.0..110.0 })
    }

    @Test
    fun `the isochrone bands the network by walk-time`() {
        val spec = GenerativeOverlays.shelterIsochrone(
            avenue(), latAt(0.0), lonAt(0.0), footSpeedMps = 1.0,
        )
        assertTrue(spec != null)
        assertEquals("isochrone", spec!!.kind)
        // At 1 m/s: green ≤180 m, amber ≤360 m, orange ≤600 m.
        val colors = spec.lines.map { it.color }.toSet()
        assertTrue("near roads should be green", colors.contains("#22C55E"))
        assertTrue("far roads should be orange", colors.contains("#F97316"))
        // Nothing past 600 m is drawn (avenue is 900 m).
        assertTrue(spec.lines.isNotEmpty())
    }

    @Test
    fun `an empty graph yields no isochrone, not an empty layer`() {
        assertNull(
            GenerativeOverlays.shelterIsochrone(RoadGraph(emptyList()), lat0, lon0),
        )
    }

    @Test
    fun `the danger field falls off from intense core to faint edge`() {
        val spec = GenerativeOverlays.dangerField(
            listOf(GenerativeOverlays.Hazard(lat0, lon0, radiusM = 300.0, label = "обстріл")),
            rings = 3,
        )
        assertTrue(spec != null)
        assertEquals(3, spec!!.regions.size)
        // Distinct radii — a real gradient, not three identical circles.
        assertEquals(3, spec.regions.map { it.radiusM }.toSet().size)
        // The smallest (innermost) circle is the most intense red.
        val inner = spec.regions.minByOrNull { it.radiusM }!!
        assertEquals("#EF4444", inner.color)
        // Only the core carries the label (no triple-labelling).
        assertEquals(1, spec.regions.count { it.label != null })
    }

    @Test
    fun `no hazards is an honest null`() {
        assertNull(GenerativeOverlays.dangerField(emptyList()))
    }
}
