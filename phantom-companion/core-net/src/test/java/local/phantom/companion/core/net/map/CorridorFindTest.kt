package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-Ω1 — corridor ranking on a straight north-running route
 * (0.001° lat ≈ 111 m).
 */
class CorridorFindTest {

    private val route = (0..20).map { 50.0 + it * 0.001 to 30.0 }

    private fun node(id: Long, lat: Double, lon: Double) =
        OverpassElement(id = id, type = "node", lat = lat, lon = lon, tags = mapOf("name" to "p$id"))

    @Test
    fun `hits sort by along-route distance, not straight-line`() {
        // p1 sits further from the start point as the crow flies (off to the
        // east) but EARLIER along the route than p2.
        val hits = rankAlongRoute(
            elements = listOf(
                node(2, 50.015, 30.0),      // ~1 665 м along
                node(1, 50.005, 30.003),    // ~555 м along, ~215 м off
            ),
            route = route,
        )
        assertEquals(listOf(1L, 2L), hits.map { it.element.id })
        assertTrue(hits[0].alongM < hits[1].alongM)
    }

    @Test
    fun `far off-route hits are dropped`() {
        val hits = rankAlongRoute(
            elements = listOf(
                node(1, 50.005, 30.0),     // on the line
                node(2, 50.005, 30.02),    // ~1.4 km east — not «по дорозі»
            ),
            route = route,
        )
        assertEquals(listOf(1L), hits.map { it.element.id })
    }

    @Test
    fun `hits behind current progress are dropped`() {
        val hits = rankAlongRoute(
            elements = listOf(
                node(1, 50.002, 30.0),  // ~222 м along — behind
                node(2, 50.015, 30.0),  // ~1 665 м along — ahead
            ),
            route = route,
            minAheadM = 1_000.0,
        )
        assertEquals(listOf(2L), hits.map { it.element.id })
    }

    @Test
    fun `offRoute metres are reported honestly`() {
        val hits = rankAlongRoute(listOf(node(1, 50.005, 30.003)), route)
        // 0.003° lon at 50°N ≈ 215 м.
        assertTrue("off=${hits.single().offRouteM}", hits.single().offRouteM in 180.0..250.0)
    }

    @Test
    fun `way with centre only still ranks`() {
        val way = OverpassElement(
            id = 9, type = "way", lat = null, lon = null,
            center = OverpassCenter(50.01, 30.0), tags = mapOf("name" to "w9"),
        )
        val hits = rankAlongRoute(listOf(way), route)
        assertEquals(9L, hits.single().element.id)
    }

    @Test
    fun `degenerate route yields nothing`() {
        assertTrue(rankAlongRoute(listOf(node(1, 50.0, 30.0)), emptyList()).isEmpty())
        assertTrue(rankAlongRoute(listOf(node(1, 50.0, 30.0)), listOf(50.0 to 30.0)).isEmpty())
    }
}
