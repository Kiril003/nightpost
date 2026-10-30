package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Курс шлагбаума. Сам вузол `barrier=*` напрямку не несе — його дає лише
 * дорога, у яку вузол вставлено. Без цього брус воріт малювати нічим, і тоді
 * його не малюють узагалі: орієнтація навмання гірша за її відсутність.
 */
class BarrierBearingTest {

    private fun node(id: Long, lat: Double, lon: Double) =
        OverpassElement(id = id, type = "node", lat = lat, lon = lon, tags = mapOf("barrier" to "gate"))

    private fun way(id: Long, nodes: List<Long>, geom: List<Pair<Double, Double>>) =
        OverpassElement(
            id = id,
            type = "way",
            geometry = geom.map { OverpassCenter(it.first, it.second) },
            nodes = nodes,
            tags = mapOf("highway" to "residential"),
        )

    @Test
    fun `a node inside a way takes the road's own heading`() {
        // Дорога строго на схід, вузол посередині.
        val road = way(
            10, listOf(1, 2, 3),
            listOf(50.4500 to 30.5200, 50.4500 to 30.5210, 50.4500 to 30.5220),
        )
        val bearing = barrierBearingDeg(node(2, 50.4500, 30.5210), listOf(road))
        assertEquals(90.0, bearing!!, 0.5)
    }

    @Test
    fun `the id decides, not the coincidence of coordinates`() {
        // Два шляхи в одній точці: тільки один справді містить цей вузол.
        val northSouth = way(
            11, listOf(7, 8, 9),
            listOf(50.4490 to 30.5210, 50.4500 to 30.5210, 50.4510 to 30.5210),
        )
        val eastWest = way(
            12, listOf(1, 2, 3),
            listOf(50.4500 to 30.5200, 50.4500 to 30.5210, 50.4500 to 30.5220),
        )
        val bearing = barrierBearingDeg(node(8, 50.4500, 30.5210), listOf(eastWest, northSouth))
        assertEquals(0.0, bearing!!, 0.5)
    }

    @Test
    fun `a way that does not name the node still answers if the node is on it`() {
        // `out geom` без списку вершин — старий кеш або чуже дзеркало.
        val road = OverpassElement(
            id = 13,
            type = "way",
            geometry = listOf(
                OverpassCenter(50.4500, 30.5200),
                OverpassCenter(50.4500, 30.5220),
            ),
            tags = mapOf("highway" to "service"),
        )
        val bearing = barrierBearingDeg(node(99, 50.4500, 30.5210), listOf(road))
        assertEquals(90.0, bearing!!, 0.5)
    }

    @Test
    fun `a road too far away is not this barrier's road`() {
        val road = OverpassElement(
            id = 14,
            type = "way",
            geometry = listOf(
                OverpassCenter(50.4600, 30.5200),
                OverpassCenter(50.4600, 30.5220),
            ),
            tags = mapOf("highway" to "service"),
        )
        assertNull(barrierBearingDeg(node(99, 50.4500, 30.5210), listOf(road)))
    }

    @Test
    fun `no ways at all means no rotation, and that is the honest answer`() {
        assertNull(barrierBearingDeg(node(1, 50.45, 30.52), emptyList()))
    }

    @Test
    fun `the end of a way still has a direction`() {
        val road = way(
            15, listOf(1, 2),
            listOf(50.4500 to 30.5200, 50.4510 to 30.5200),
        )
        val bearing = barrierBearingDeg(node(1, 50.4500, 30.5200), listOf(road))
        assertEquals(0.0, bearing!!, 0.5)
    }

    @Test
    fun `the way-through-nodes query asks for geometry and stays bounded`() {
        val q = OverpassQueries.waysThroughNodes((1L..100L).toList())
        assertTrue("рекурсія від вузла до шляхів", q.contains("way(bn.b)[highway]"))
        assertTrue("геометрія потрібна саме тут", q.contains("out geom"))
        assertEquals(
            "стеля вузлів у запиті",
            OverpassQueries.MAX_BARRIER_NODES,
            q.substringAfter("node(id:").substringBefore(")").split(",").size,
        )
    }
}
